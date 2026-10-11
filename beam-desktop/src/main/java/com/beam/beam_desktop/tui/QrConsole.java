package com.beam.beam_desktop.tui;

import com.google.zxing.common.BitMatrix;

/**
 * QR rendering for plain consoles (the CLI fallback). Same gapless technique as the
 * TUI's {@link QrBox}: each module is two spaces with an explicit black/white ANSI
 * background, which scans reliably, unlike block glyphs that leave hairline gaps.
 * Pure functions — unit-tested.
 */
public final class QrConsole {

    private static final String BLACK_BG = "\u001B[40m";
    private static final String WHITE_BG = "\u001B[47m";
    private static final String RESET = "\u001B[0m";

    private QrConsole() {}

    /** Renders the matrix with quiet zone, one string row per module row. */
    public static String render(BitMatrix matrix) {
        StringBuilder sb = new StringBuilder();
        int totalCols = matrix.getWidth() + QrRenderer.QUIET_ZONE * 2;
        int totalRows = matrix.getHeight() + QrRenderer.QUIET_ZONE * 2;
        for (int my = 0; my < totalRows; my++) {
            for (int mx = 0; mx < totalCols; mx++) {
                sb.append(QrRenderer.isDark(matrix, mx - QrRenderer.QUIET_ZONE, my - QrRenderer.QUIET_ZONE)
                        ? BLACK_BG
                        : WHITE_BG);
                sb.append("  ");
            }
            sb.append(RESET).append('\n');
        }
        return sb.toString();
    }

    /** Terminal columns the rendered code needs (same width as the TUI's QrBox). */
    public static int renderedColumns(BitMatrix matrix) {
        return QrRenderer.renderedColumns(matrix);
    }

    /** True when the code fits in a console {@code columns} wide. */
    public static boolean fitsWidth(BitMatrix matrix, int columns) {
        return renderedColumns(matrix) <= columns;
    }
}
