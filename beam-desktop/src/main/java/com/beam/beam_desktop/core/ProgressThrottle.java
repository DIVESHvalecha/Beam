package com.beam.beam_desktop.core;

/**
 * Time-throttled progress gate: at most one event every {@code intervalMs},
 * plus a guaranteed final event at 100%. Pure logic, unit-tested, no I/O.
 */
public final class ProgressThrottle {
    private final long intervalMs;
    private long lastEmitMs = Long.MIN_VALUE;
    private boolean first = true;

    public ProgressThrottle(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    /** Returns true if a progress event for this sample should be emitted now. */
    public synchronized boolean shouldEmit(long bytesDone, long bytesTotal, long nowMs) {
        if (bytesDone >= bytesTotal) {
            return true; // guaranteed final event
        }
        if (first) {
            first = false;
            lastEmitMs = nowMs;
            return true;
        }
        if (nowMs - lastEmitMs >= intervalMs) {
            lastEmitMs = nowMs;
            return true;
        }
        return false;
    }

    public synchronized void reset() {
        lastEmitMs = Long.MIN_VALUE;
        first = true;
    }
}
