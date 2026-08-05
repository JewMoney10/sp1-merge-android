package com.joshua.sp1merge;

/**
 * Band-limited (windowed-sinc) sample rate converter. Simpler than a
 * full polyphase resampler, but avoids the aliasing/imaging artifacts
 * plain linear interpolation would introduce — good enough for stem
 * playback and self-contained (no native/JNI dependency).
 */
public class Resampler {

    private static final int HALF_TAPS = 16; // 33-tap kernel per output sample

    public static short[][] resample(short[] left, short[] right, int length, int srcRate, int dstRate) {
        if (srcRate == dstRate) {
            return new short[][]{left, right};
        }
        double ratio = (double) dstRate / srcRate;
        int outLen = (int) Math.floor(length * ratio);
        short[] outL = new short[outLen];
        short[] outR = new short[outLen];

        // band-limit at the lower of the two rates so downsampling doesn't alias
        double cutoff = Math.min(1.0, (double) dstRate / srcRate);

        // the window shape only depends on tap position, not on which
        // output sample we're at — compute it once instead of on every
        // pass through the inner loop
        int tapCount = 2 * HALF_TAPS + 1;
        double[] window = new double[tapCount];
        for (int k = 0; k < tapCount; k++) {
            window[k] = hann(k, 2 * HALF_TAPS);
        }

        for (int i = 0; i < outLen; i++) {
            if ((i & 4095) == 0) {
                MergeCancelledException.checkCancelled();
            }
            double srcPos = i / ratio;
            int center = (int) Math.floor(srcPos);
            double frac = srcPos - center;

            double accL = 0, accR = 0;
            for (int k = -HALF_TAPS; k <= HALF_TAPS; k++) {
                int srcIdx = center + k;
                if (srcIdx < 0 || srcIdx >= length) continue;
                double x = (k - frac) * cutoff;
                double weight = sinc(x) * cutoff * window[k + HALF_TAPS];
                accL += left[srcIdx] * weight;
                accR += right[srcIdx] * weight;
            }
            outL[i] = clampToShort(accL);
            outR[i] = clampToShort(accR);
        }
        return new short[][]{outL, outR};
    }

    private static short clampToShort(double v) {
        long rounded = Math.round(v);
        if (rounded > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (rounded < Short.MIN_VALUE) return Short.MIN_VALUE;
        return (short) rounded;
    }

    private static double sinc(double x) {
        if (x == 0) return 1.0;
        double px = Math.PI * x;
        return Math.sin(px) / px;
    }

    private static double hann(int n, int windowSpan) {
        return 0.5 - 0.5 * Math.cos(2 * Math.PI * n / windowSpan);
    }
}
