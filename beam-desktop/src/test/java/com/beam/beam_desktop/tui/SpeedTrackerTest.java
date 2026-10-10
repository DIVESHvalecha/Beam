package com.beam.beam_desktop.tui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SpeedTrackerTest {

    @Test
    void unknownWithFewerThanTwoSamples() {
        SpeedTracker t = new SpeedTracker();
        assertEquals(-1, t.bytesPerSecond(1000));
        t.add(1000, 1000);
        assertEquals(-1, t.bytesPerSecond(1100));
    }

    @Test
    void steadyRate() {
        SpeedTracker t = new SpeedTracker();
        t.add(0, 0);
        t.add(1_000_000, 1000); // 1 MB in 1 s
        assertEquals(1_000_000.0, t.bytesPerSecond(1000), 1.0);
        assertEquals(9, t.etaSeconds(10_000_000, 1_000_000, 1000));
    }

    @Test
    void oldSamplesExpire() {
        SpeedTracker t = new SpeedTracker();
        t.add(0, 0);
        t.add(1_000_000, 1000);
        // At t=5000 the first two samples are outside the 2 s window; only the
        // two newest (kept by design) remain, so the rate reflects recent movement.
        t.add(1_500_000, 5000);
        double bps = t.bytesPerSecond(5000);
        assertTrue(bps > 0, "expected a recent rate, got " + bps);
    }

    @Test
    void finishedTransferHasZeroEta() {
        SpeedTracker t = new SpeedTracker();
        assertEquals(0, t.etaSeconds(100, 100, 5000));
        assertEquals(-1, t.etaSeconds(100, 50, 5000));
    }
}
