package com.beam.beam_desktop.tui;

import static org.junit.jupiter.api.Assertions.*;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;

/** The encoded module matrix must decode back to the original join URL. */
class QrRendererTest {

    @Test
    void roundTripJoinUrl() throws Exception {
        String url = "https://usebeam.tech/?c=RUUJ3V";
        BitMatrix matrix = QrRenderer.encode(url);
        Result decoded = new QRCodeReader().decode(bitmapOf(matrix, 4, QrRenderer.QUIET_ZONE));
        assertEquals(url, decoded.getText());
    }

    @Test
    void dimensions() {
        BitMatrix matrix = QrRenderer.encode("https://usebeam.tech/?c=AB12CD");
        // ~30-char URL at EC level L: 25x25 modules + 2 quiet zone each side,
        // two cells per module horizontally.
        assertEquals(25, matrix.getWidth());
        assertEquals(25, matrix.getHeight());
        assertEquals((matrix.getWidth() + 4) * 2, QrRenderer.renderedColumns(matrix));
        assertEquals(matrix.getHeight() + 4, QrRenderer.renderedRows(matrix));
        assertTrue(QrRenderer.isDark(matrix, 0, 0)); // finder pattern corner
        assertFalse(QrRenderer.isDark(matrix, -1, 0)); // quiet zone
        assertFalse(QrRenderer.isDark(matrix, matrix.getWidth(), matrix.getHeight()));
    }

    /** Scales each module (plus a quiet zone) into a decodable bitmap. */
    static BinaryBitmap bitmapOf(BitMatrix matrix, int scale, int quietModules) {
        int w = (matrix.getWidth() + quietModules * 2) * scale;
        int h = (matrix.getHeight() + quietModules * 2) * scale;
        int[] pixels = new int[w * h]; // 0 = black
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0xFF; // white
        }
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                if (!matrix.get(x, y)) {
                    continue;
                }
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        int px = (x + quietModules) * scale + dx;
                        int py = (y + quietModules) * scale + dy;
                        pixels[py * w + px] = 0x00;
                    }
                }
            }
        }
        LuminanceSource source = new LuminanceSource(w, h) {
            @Override
            public byte[] getRow(int y, byte[] row) {
                if (row == null || row.length < w) {
                    row = new byte[w];
                }
                for (int x = 0; x < w; x++) {
                    row[x] = (byte) pixels[y * w + x];
                }
                return row;
            }

            @Override
            public byte[] getMatrix() {
                byte[] out = new byte[w * h];
                for (int i = 0; i < pixels.length; i++) {
                    out[i] = (byte) pixels[i];
                }
                return out;
            }
        };
        return new BinaryBitmap(new HybridBinarizer(source));
    }
}
