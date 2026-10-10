package com.beam.beam_desktop.tui;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Rolling transfer-speed average over a ~2 s window. Feed every progress event with the
 * cumulative byte count; all time is explicit milliseconds so this is unit-testable.
 */
public final class SpeedTracker {

    /** Samples older than this (relative to "now") are dropped. */
    public static final long WINDOW_MS = 2000;

    private record Sample(long bytesDone, long atMs) {}

    private final Deque<Sample> samples = new ArrayDeque<>();

    /** Record a progress sample. Out-of-order and duplicate samples are tolerated. */
    public synchronized void add(long bytesDone, long nowMs) {
        if (!samples.isEmpty() && nowMs < samples.getLast().atMs()) {
            return; // ignore time going backwards
        }
        samples.addLast(new Sample(bytesDone, nowMs));
        evict(nowMs);
    }

    /** Bytes/second over the window, or -1 when unknown (fewer than 2 samples or no movement). */
    public synchronized double bytesPerSecond(long nowMs) {
        evict(nowMs);
        if (samples.size() < 2) {
            return -1;
        }
        Sample first = samples.getFirst();
        Sample last = samples.getLast();
        long dt = last.atMs() - first.atMs();
        long db = last.bytesDone() - first.bytesDone();
        if (dt <= 0 || db <= 0) {
            return -1;
        }
        return (db * 1000.0) / dt;
    }

    /** Whole seconds until {@code bytesTotal}, or -1 when unknown. */
    public synchronized long etaSeconds(long bytesTotal, long bytesDone, long nowMs) {
        double bps = bytesPerSecond(nowMs);
        if (!(bps > 0) || bytesDone >= bytesTotal) {
            return bytesDone >= bytesTotal ? 0 : -1;
        }
        return (long) ((bytesTotal - bytesDone) / bps);
    }

    private void evict(long nowMs) {
        while (samples.size() > 2 && nowMs - samples.getFirst().atMs() > WINDOW_MS) {
            samples.removeFirst();
        }
    }
}
