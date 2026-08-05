package com.joshua.sp1merge;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Decodes a stereo stem file (wav/mp3/flac/m4a/ogg — whatever the
 * device's codecs support) to interleaved-then-split float PCM.
 * Reads from a content Uri (as returned by ACTION_OPEN_DOCUMENT) via
 * MediaExtractor + MediaCodec, so no bundled ffmpeg binary or local
 * file copy is needed.
 */
public class StemDecoder {

    public static class DecodedStem {
        public short[] left;
        public short[] right;
        public int frames; // valid length — left/right may be slightly larger
        public int sampleRate;
    }

    public static DecodedStem decode(Context context, Uri uri, String label,
                                      StemMerger.ProgressListener listener, int basePercent) throws IOException {
        listener.onProgress("Opening " + label + "…", basePercent);
        DebugLog.log(context, "StemDecoder", label + ": creating MediaExtractor");
        MediaExtractor extractor = new MediaExtractor();
        DebugLog.log(context, "StemDecoder", label + ": calling setDataSource on " + uri);
        extractor.setDataSource(context, uri, null);
        DebugLog.log(context, "StemDecoder", label + ": setDataSource returned");

        int trackIndex = -1;
        MediaFormat format = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                trackIndex = i;
                format = f;
                break;
            }
        }
        if (trackIndex < 0 || format == null) {
            DebugLog.log(context, "StemDecoder", label + ": no audio track found");
            extractor.release();
            throw new IOException("no audio track found in " + label);
        }
        extractor.selectTrack(trackIndex);
        DebugLog.log(context, "StemDecoder", label + ": track selected");

        int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        if (channels != 2) {
            DebugLog.log(context, "StemDecoder", label + ": wrong channel count " + channels);
            extractor.release();
            throw new IOException(label + " has " + channels + " channels, expected 2 (stereo)");
        }

        String mime = format.getString(MediaFormat.KEY_MIME);
        DebugLog.log(context, "StemDecoder", label + ": creating decoder for mime=" + mime
                + " sampleRate=" + sampleRate);
        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        codec.configure(format, null, null, 0);
        codec.start();
        DebugLog.log(context, "StemDecoder", label + ": codec started, entering decode loop");

        listener.onProgress("Reading " + label + "…", basePercent);
        int estimatedFrames = estimateFrameCount(format, sampleRate);
        try {
            return decodeLoop(context, label, extractor, codec, sampleRate, estimatedFrames);
        } finally {
            codec.stop();
            codec.release();
            extractor.release();
            DebugLog.log(context, "StemDecoder", label + ": codec/extractor released");
        }
    }

    // Duration-based sizing hint so the growable arrays below rarely
    // need to reallocate/copy themselves as they fill up. Falls back
    // to a reasonable default if the source doesn't report a duration.
    private static int estimateFrameCount(MediaFormat format, int sampleRate) {
        if (format.containsKey(MediaFormat.KEY_DURATION)) {
            long durationUs = format.getLong(MediaFormat.KEY_DURATION);
            if (durationUs > 0) {
                return (int) (durationUs * sampleRate / 1_000_000L) + 1024;
            }
        }
        return 1 << 20; // ~1M frames
    }

    // Simple growable primitive short array — avoids the boxing
    // overhead of ArrayList<Short> for what can be tens of millions
    // of samples. Stores raw 16-bit PCM directly (no float
    // conversion) since that's the decoder's native precision anyway.
    private static class ShortGrowBuffer {
        short[] data;
        int size = 0;

        ShortGrowBuffer(int initialCapacity) {
            data = new short[Math.max(initialCapacity, 16)];
        }

        void add(short v) {
            if (size == data.length) {
                data = java.util.Arrays.copyOf(data, data.length + (data.length >> 1) + 16);
            }
            data[size++] = v;
        }
    }

    private static DecodedStem decodeLoop(Context context, String label,
                                           MediaExtractor extractor, MediaCodec codec,
                                           int sampleRate, int estimatedFrames)
            throws IOException {
        ShortGrowBuffer left = new ShortGrowBuffer(estimatedFrames);
        ShortGrowBuffer right = new ShortGrowBuffer(estimatedFrames);
        long totalBytes = 0;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;
        int loopCount = 0;

        while (!outputDone) {
            MergeCancelledException.checkCancelled();
            loopCount++;
            if (loopCount % 500 == 0) {
                DebugLog.log(context, "StemDecoder", label + ": loop iteration " + loopCount
                        + " (inputDone=" + inputDone + ", frames so far=" + left.size + ")");
            }
            if (!inputDone) {
                int inIndex = codec.dequeueInputBuffer(10000);
                if (inIndex >= 0) {
                    ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                    int sampleSize = inBuf != null ? extractor.readSampleData(inBuf, 0) : -1;
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        long pts = extractor.getSampleTime();
                        codec.queueInputBuffer(inIndex, 0, sampleSize, pts, 0);
                        extractor.advance();
                    }
                }
            }

            int outIndex = codec.dequeueOutputBuffer(info, 10000);
            if (outIndex >= 0) {
                ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                if (outBuf != null && info.size > 0) {
                    outBuf.position(info.offset);
                    outBuf.limit(info.offset + info.size);
                    ShortBuffer shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                    int frames = shorts.remaining() / 2;
                    for (int i = 0; i < frames; i++) {
                        left.add(shorts.get(2 * i));
                        right.add(shorts.get(2 * i + 1));
                    }
                    totalBytes += info.size;
                }
                codec.releaseOutputBuffer(outIndex, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat outFormat = codec.getOutputFormat();
                if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                }
            }
        }

        DebugLog.log(context, "StemDecoder", label + ": decode loop finished after " + loopCount
                + " iterations, " + totalBytes + " bytes, " + left.size + " frames");

        DecodedStem stem = new DecodedStem();
        stem.sampleRate = sampleRate;
        stem.left = left.data;
        stem.right = right.data;
        stem.frames = left.size;
        return stem;
    }
}
