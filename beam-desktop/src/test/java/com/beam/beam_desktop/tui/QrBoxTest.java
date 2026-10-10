package com.beam.beam_desktop.tui;

import static org.junit.jupiter.api.Assertions.*;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.BinaryBitmap;
import org.junit.jupiter.api.Test;

/**
 * Draws QrBox onto a headless virtual terminal and reads back the actual printed cells:
 * every cell must carry an explicit black/white background forming the exact module grid
 * (two cells per module), and the read-back grid must decode to the join URL. No fonts
 * involved — this validates what Lanterna emits, which is what any terminal shows.
 */
class QrBoxTest {

    @Test
    void drawnCellsMatchMatrixAndDecode() throws Exception {
        String url = "https://usebeam.tech/?c=RUUJ3V";
        BitMatrix matrix = QrRenderer.encode(url);
        int cols = QrRenderer.renderedColumns(matrix);
        int rows = QrRenderer.renderedRows(matrix);

        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(cols + 4, rows + 2));
        Screen screen = new TerminalScreen(terminal);
        screen.startScreen();
        try {
            QrBox box = new QrBox();
            box.setMatrix(matrix);
            TextGraphics g = screen.newTextGraphics()
                    .newTextGraphics(new TerminalPosition(0, 0), new TerminalSize(cols, rows));
            QrBox.drawCells(g, matrix, cols, rows);
            screen.refresh();

            BitMatrix read = new BitMatrix(cols, rows);
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    TextCharacter c = screen.getBackCharacter(x, y);
                    boolean dark = TextColor.ANSI.BLACK.equals(c.getBackgroundColor());
                    boolean light = TextColor.ANSI.WHITE.equals(c.getBackgroundColor());
                    assertTrue(dark || light, "cell (" + x + "," + y + ") has no explicit QR color");
                    if (dark) {
                        read.set(x, y);
                    }
                    int mx = x / QrRenderer.CELLS_PER_MODULE - QrRenderer.QUIET_ZONE;
                    int my = y - QrRenderer.QUIET_ZONE;
                    assertEquals(QrRenderer.isDark(matrix, mx, my), dark,
                            "cell (" + x + "," + y + ") mismatch");
                }
            }

            // Decode check: cells are ~twice as tall as wide on a real terminal, so
            // expand each cell 2x wide / 4x tall to get square pixels before decoding.
            BitMatrix squared = new BitMatrix(cols * 2, rows * 4);
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    if (read.get(x, y)) {
                        for (int dy = 0; dy < 4; dy++) {
                            for (int dx = 0; dx < 2; dx++) {
                                squared.set(x * 2 + dx, y * 4 + dy);
                            }
                        }
                    }
                }
            }
            Result decoded = new QRCodeReader().decode(QrRendererTest.bitmapOf(squared, 2, 0));
            assertEquals(url, decoded.getText());
        } finally {
            screen.stopScreen();
        }
    }
}
