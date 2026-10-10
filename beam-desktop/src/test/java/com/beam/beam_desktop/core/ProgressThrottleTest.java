package com.beam.beam_desktop.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProgressThrottleTest {

    @Test
    void firstSampleAlwaysEmits() {
        ProgressThrottle t = new ProgressThrottle(150);
        assertTrue(t.shouldEmit(0, 1000, 0));
    }

    @Test
    void throttlesWithinInterval() {
        ProgressThrottle t = new ProgressThrottle(150);
        assertTrue(t.shouldEmit(10, 1000, 1_000));
        assertFalse(t.shouldEmit(20, 1000, 1_050));
        assertFalse(t.shouldEmit(30, 1000, 1_149));
        assertTrue(t.shouldEmit(40, 1000, 1_150));
    }

    @Test
    void finalSampleAlwaysEmitsEvenInsideInterval() {
        ProgressThrottle t = new ProgressThrottle(150);
        assertTrue(t.shouldEmit(10, 1000, 1_000));
        assertTrue(t.shouldEmit(1000, 1000, 1_001));
    }

    @Test
    void zeroSizeFileEmits() {
        ProgressThrottle t = new ProgressThrottle(150);
        assertTrue(t.shouldEmit(0, 0, 5_000));
    }
}
