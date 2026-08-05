package com.joshua.sp1merge;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Writes the 8-channel, 24-bit, 48kHz WAVE_FORMAT_EXTENSIBLE file the
 * SP-1 firmware expects. Byte layout matches what softmodded/sp1-merge
 * produces, so files written here load the same way as ones made with
 * the desktop tool.
 *
 * Channel order per frame: vocals L, vocals R, other L, other R,
 * bass L, bass R, drums L, drums R.
 */
public class Sp1WavWriter {

    public static final int TARGET_RATE = 48000;
    public static final int TARGET_BITS = 24;
    private static final int CHANNELS = 8;

    private static final byte[] PCM_SUBFORMAT_GUID = {
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
            (byte) 0x80, 0x00, 0x00, (byte) 0xAA, 0x00, 0x38, (byte) 0x9B, 0x71
    };

    /**
     * @param rawOut an open stream to the destination (e.g. from
     *               ContentResolver#openOutputStream on the Uri
     *               returned by ACTION_CREATE_DOCUMENT). Closed by
     *               this method when done.
     * @param vocals {left[], right[]} raw 16-bit PCM
     * @param other  same layout
     * @param bass   same layout
     * @param drums  same layout — all four must already be the same
     *               length (StemMerger trims them before calling this)
     */
    public static void write(OutputStream rawOut, short[][] vocals, short[][] other,
                              short[][] bass, short[][] drums) throws IOException {
        int frames = vocals[0].length;
        long dataSize = (long) frames * CHANNELS * 3;
        long fileSize = 4 + 8 + 40 + 8 + dataSize; // "WAVE" + fmt chunk + data header + data

        BufferedOutputStream w = new BufferedOutputStream(rawOut, 1 << 16);
        try {
            // RIFF header
            w.write("RIFF".getBytes(StandardCharsets.US_ASCII));
            writeLE32(w, fileSize);
            w.write("WAVE".getBytes(StandardCharsets.US_ASCII));

            // fmt chunk (WAVE_FORMAT_EXTENSIBLE, 40 bytes)
            w.write("fmt ".getBytes(StandardCharsets.US_ASCII));
            writeLE32(w, 40);
            writeLE16(w, 0xFFFE);                 // format tag: extensible
            writeLE16(w, CHANNELS);
            writeLE32(w, TARGET_RATE);
            long byteRate = (long) TARGET_RATE * CHANNELS * (TARGET_BITS / 8);
            writeLE32(w, byteRate);
            int blockAlign = CHANNELS * (TARGET_BITS / 8);
            writeLE16(w, blockAlign);
            writeLE16(w, TARGET_BITS);            // container bits per sample
            writeLE16(w, 22);                     // cbSize
            writeLE16(w, TARGET_BITS);            // valid bits per sample
            writeLE32(w, 0);                      // channel mask
            w.write(PCM_SUBFORMAT_GUID);

            // data chunk
            w.write("data".getBytes(StandardCharsets.US_ASCII));
            writeLE32(w, dataSize);

            short[][][] stemOrder = {vocals, other, bass, drums};
            byte[] frameBuf = new byte[CHANNELS * 3];
            for (int i = 0; i < frames; i++) {
                if ((i & 8191) == 0) {
                    MergeCancelledException.checkCancelled();
                }
                int pos = 0;
                for (short[][] stem : stemOrder) {
                    pos = writeSample24(frameBuf, pos, stem[0][i]); // L
                    pos = writeSample24(frameBuf, pos, stem[1][i]); // R
                }
                w.write(frameBuf);
            }
        } finally {
            w.flush();
            w.close();
        }
    }

    // Widens a 16-bit sample into the 24-bit slot by shifting it up
    // (low byte zero) — standard bit-depth widening, and lossless
    // relative to the source, which never had more than 16 bits of
    // real precision to begin with.
    private static int writeSample24(byte[] buf, int pos, short sample) {
        int v = ((int) sample) << 8;
        buf[pos] = (byte) (v & 0xFF);
        buf[pos + 1] = (byte) ((v >> 8) & 0xFF);
        buf[pos + 2] = (byte) ((v >> 16) & 0xFF);
        return pos + 3;
    }

    private static void writeLE16(BufferedOutputStream w, int v) throws IOException {
        w.write(v & 0xFF);
        w.write((v >> 8) & 0xFF);
    }

    private static void writeLE32(BufferedOutputStream w, long v) throws IOException {
        w.write((int) (v & 0xFF));
        w.write((int) ((v >> 8) & 0xFF));
        w.write((int) ((v >> 16) & 0xFF));
        w.write((int) ((v >> 24) & 0xFF));
    }
}
