package com.joshua.sp1merge;

/**
 * Thrown cooperatively when a merge is cancelled mid-flight. Unchecked
 * so it can be thrown from any point in the pipeline (decode, resample,
 * write) without touching every method's throws clause, and unwinds
 * straight up to MergeService's catch block.
 */
public class MergeCancelledException extends RuntimeException {

    public MergeCancelledException() {
        super("cancelled");
    }

    /** Call from hot loops to bail out as soon as a cancel has been requested. */
    public static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new MergeCancelledException();
        }
    }
}
