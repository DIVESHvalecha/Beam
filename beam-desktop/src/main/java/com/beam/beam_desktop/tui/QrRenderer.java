package com.beam.beam_desktop.tui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.EnumMap;
import java.util.Map;

/**
 * QR encode + cell-grid model. Pure functions, unit-tested.
 *
 * <p>The TUI draws each module as two space characters with an explicit black or white
 * <i>background</i> (see QrBox). Cell backgrounds fill the whole character cell including
 * line spacing, so — unlike half-block/full-block glyphs, which leave hairline gaps
 * between rows in fonts like Menlo/SF Mono — the printed modules tile with zero gaps
 * and actually scan. Measured: glyph rendering of the same URL does not decode with
 * real macOS fonts; gapless background rendering does.
 */
public final class QrRenderer {

    private QrRenderer() {}

    /** Light modules around the code, in modules, on each side. */
    public static final int QUIET_ZONE = 2;

    /** Terminal cells per module horizontally. Cells are ~twice as tall as wide. */
    public static final int CELLS_PER_MODULE = 2;

    /**
     * Encode the join URL. Error-correction level L keeps our ~30-char URL at a 25x25
     * matrix (58x29 cells rendered); M would push it to 29x29 (66x33) with no practical
     * gain for a code scanned once off a screen at close range.
     */
    public static BitMatrix encode(String text) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
        hints.put(EncodeHintType.MARGIN, 0);
        try {
            // 0x0 asks the writer for the exact-size matrix (margin comes from MARGIN=0).
            return new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
        } catch (WriterException e) {
            throw new IllegalArgumentException("Cannot encode QR for: " + text, e);
        }
    }

    /** Terminal columns the code needs (modules + quiet zone, two cells per module). */
    public static int renderedColumns(BitMatrix matrix) {
        return (matrix.getWidth() + QUIET_ZONE * 2) * CELLS_PER_MODULE;
    }

    /** Terminal rows the code needs (modules + quiet zone, one cell per module row). */
    public static int renderedRows(BitMatrix matrix) {
        return matrix.getHeight() + QUIET_ZONE * 2;
    }

    /** True if module (mx, my) is dark; coordinates outside the matrix are light (quiet zone). */
    public static boolean isDark(BitMatrix matrix, int mx, int my) {
        if (mx < 0 || my < 0 || mx >= matrix.getWidth() || my >= matrix.getHeight()) {
            return false;
        }
        return matrix.get(mx, my);
    }
}
