package com.beam.beam_desktop.tui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TextUtilTest {

    @Test
    void humanSize() {
        assertEquals("0 B", TextUtil.humanSize(0));
        assertEquals("512 B", TextUtil.humanSize(512));
        assertEquals("1.0 KB", TextUtil.humanSize(1024));
        assertEquals("16.0 KB", TextUtil.humanSize(16 * 1024));
        assertEquals("8.0 MB", TextUtil.humanSize(8405238));
        assertEquals("48.4 MB", TextUtil.humanSize(50707487));
        assertEquals("1.0 GB", TextUtil.humanSize(1024L * 1024 * 1024));
    }

    @Test
    void speedAndEta() {
        assertEquals("--", TextUtil.formatSpeed(-1));
        assertEquals("--", TextUtil.formatSpeed(0));
        assertEquals("4.0 MB/s", TextUtil.formatSpeed(4 * 1024 * 1024));
        assertEquals("--:--", TextUtil.formatEta(-1));
        assertEquals("00:00", TextUtil.formatEta(0));
        assertEquals("02:37", TextUtil.formatEta(157));
    }

    @Test
    void spacedCode() {
        assertEquals("R 8 J C H 7", TextUtil.spacedCode("R8JCH7"));
    }

    @Test
    void sanitizeDisplayName() {
        assertEquals("photo.jpg", TextUtil.sanitizeDisplayName("photo.jpg", 48));
        // Path traversal collapses to the last component.
        assertEquals("evil.sh", TextUtil.sanitizeDisplayName("../../tmp/evil.sh", 48));
        assertEquals("evil.sh", TextUtil.sanitizeDisplayName("C:\\Users\\x\\evil.sh", 48));
        // Terminal escape injection is stripped (CSI sequences, lone ESC, controls).
        assertEquals("hi", TextUtil.sanitizeDisplayName("\u001B[2J\u001B[1;31mhi\u001B[0m", 48));
        assertEquals("abc", TextUtil.sanitizeDisplayName("a\u0007b\u001Bc", 48));
        // Long names truncate with an ellipsis.
        assertEquals("abcd…", TextUtil.sanitizeDisplayName("abcdefghij.jpg", 5));
        assertEquals("unnamed file", TextUtil.sanitizeDisplayName("..", 48));
        assertEquals("unnamed file", TextUtil.sanitizeDisplayName(null, 48));
    }
}
