package com.joshua.sp1merge;

import android.content.Context;
import android.net.Uri;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Full pipeline: decode 4 stem files, resample any that aren't 48kHz,
 * trim all four to the shortest one's length, write the SP-1 wav.
 * Mirrors process_one() in softmodded/sp1-merge's main.rs.
 */
public class StemMerger {

    public interface ProgressListener {
        void onProgress(String stage, int percent);
    }

    public static void merge(Context context, Uri vocalsUri, Uri otherUri, Uri bassUri, Uri drumsUri,
                              Uri outputUri, ProgressListener listener) throws IOException {

        DebugLog.log(context, "StemMerger", "merge() entered");

        listener.onProgress("Decoding & resampling vocals…", 0);
        Stem v = decodeAndResample(context, vocalsUri, "vocals", listener, 0);
        DebugLog.log(context, "StemMerger", "vocals done: " + v.frames + " frames");
        MergeCancelledException.checkCancelled();

        listener.onProgress("Decoding & resampling other…", 20);
        Stem o = decodeAndResample(context, otherUri, "other", listener, 20);
        DebugLog.log(context, "StemMerger", "other done: " + o.frames + " frames");
        MergeCancelledException.checkCancelled();

        listener.onProgress("Decoding & resampling bass…", 40);
        Stem b = decodeAndResample(context, bassUri, "bass", listener, 40);
        DebugLog.log(context, "StemMerger", "bass done: " + b.frames + " frames");
        MergeCancelledException.checkCancelled();

        listener.onProgress("Decoding & resampling drums…", 60);
        Stem d = decodeAndResample(context, drumsUri, "drums", listener, 60);
        DebugLog.log(context, "StemMerger", "drums done: " + d.frames + " frames");
        MergeCancelledException.checkCancelled();

        int minFrames = Math.min(Math.min(v.frames, o.frames), Math.min(b.frames, d.frames));
        if (minFrames == 0) {
            throw new IOException("one or more stems has no audio");
        }

        listener.onProgress("Writing WAV file…", 85);
        DebugLog.log(context, "StemMerger", "opening output stream");
        OutputStream out = context.getContentResolver().openOutputStream(outputUri);
        if (out == null) {
            throw new IOException("couldn't open output file");
        }
        Sp1WavWriter.write(out, trim(v, minFrames), trim(o, minFrames), trim(b, minFrames), trim(d, minFrames));
        DebugLog.log(context, "StemMerger", "write complete");
        listener.onProgress("Done", 100);
    }

    private static class Stem {
        short[] left;
        short[] right;
        int frames;
    }

    // Decodes then immediately resamples one stem, so the original-rate
    // DecodedStem (left/right arrays at source sample rate) falls out
    // of scope and is eligible for GC before the next stem is even
    // touched, instead of all 4 stems' original AND resampled copies
    // being held in memory at the same time.
    private static Stem decodeAndResample(Context context, Uri uri, String label,
                                           ProgressListener listener, int basePercent) throws IOException {
        StemDecoder.DecodedStem decoded = StemDecoder.decode(context, uri, label, listener, basePercent);
        Stem stem = new Stem();
        if (decoded.sampleRate == Sp1WavWriter.TARGET_RATE) {
            stem.left = decoded.left;
            stem.right = decoded.right;
            stem.frames = decoded.frames;
        } else {
            short[][] resampled = Resampler.resample(decoded.left, decoded.right, decoded.frames,
                    decoded.sampleRate, Sp1WavWriter.TARGET_RATE);
            stem.left = resampled[0];
            stem.right = resampled[1];
            stem.frames = resampled[0].length; // Resampler's output is always exactly sized
        }
        return stem;
    }

    // Only copies if the stem is actually longer than the target —
    // the (usually one) stem that's already the shortest just passes
    // its arrays through untouched.
    private static short[][] trim(Stem stem, int frames) {
        if (stem.frames == frames) {
            return new short[][]{stem.left, stem.right};
        }
        short[] l = new short[frames];
        short[] r = new short[frames];
        System.arraycopy(stem.left, 0, l, 0, frames);
        System.arraycopy(stem.right, 0, r, 0, frames);
        return new short[][]{l, r};
    }
}
