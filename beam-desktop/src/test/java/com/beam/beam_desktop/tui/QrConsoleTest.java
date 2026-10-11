package com.beam.beam_desktop.tui;

import static org.junit.jupiter.api.Assertions.*;

import com.google.zxing.common.BitMatrix;
import org.junit.jupiter.api.Test;

class QrConsoleTest {

    @Test
    void rendersQuietZoneSizedGridWithResetPerRow() {
        BitMatrix m = QrRenderer.encode("https://usebeam.tech/?c=ABCDEF");
        String out = QrConsole.render(m);
        int totalRows = m.getHeight() + QrRenderer.QUIET_ZONE * 2;
        String[] rows = out.split("\n");
        assertEquals(totalRows, rows.length);
        for (String row : rows) {
            assertTrue(row.endsWith("\u001B[0m"), "each row resets colors");
        }
        // Two cells per module column, same width as the TUI box.
        assertEquals(QrRenderer.renderedColumns(m), QrConsole.renderedColumns(m));
    }

    @Test
    void narrowConsolesSkipTheQr() {
        BitMatrix m = QrRenderer.encode("https://usebeam.tech/?c=ABCDEF");
        assertTrue(QrConsole.fitsWidth(m, 80));
        assertFalse(QrConsole.fitsWidth(m, 20));
    }
}
