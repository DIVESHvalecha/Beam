package com.beam.beam_desktop.tui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.google.zxing.common.BitMatrix;

/**
 * Draws a QR code as gapless colored cells: each module is two space characters with an
 * explicit black or white background. Backgrounds fill the whole character cell, so the
 * printed code has no glyph gaps and scans reliably (see QrRenderer).
 */
public class QrBox extends AbstractComponent<QrBox> {

    private volatile BitMatrix matrix;

    public QrBox() {
        this.matrix = null;
    }

    public void setMatrix(BitMatrix matrix) {
        this.matrix = matrix;
        invalidate();
    }

    @Override
    protected ComponentRenderer<QrBox> createDefaultRenderer() {
        return new ComponentRenderer<QrBox>() {
            @Override
            public TerminalSize getPreferredSize(QrBox component) {
                BitMatrix m = component.matrix;
                if (m == null) {
                    return new TerminalSize(20, 1);
                }
                return new TerminalSize(QrRenderer.renderedColumns(m), QrRenderer.renderedRows(m));
            }

            @Override
            public void drawComponent(TextGUIGraphics g, QrBox component) {
                BitMatrix m = component.matrix;
                if (m == null) {
                    g.setBackgroundColor(TextColor.ANSI.WHITE);
                    g.fill(' ');
                    return;
                }
                drawCells(g, m, g.getSize().getColumns(), g.getSize().getRows());
            }
        };
    }

    /**
     * Paints the code onto any text graphics (GUI-backed or plain): white background
     * everywhere, then black two-cell-wide runs for dark modules. Package-visible for tests.
     */
    static void drawCells(TextGraphics g, BitMatrix m, int widthCells, int heightCells) {
        g.setBackgroundColor(TextColor.ANSI.WHITE);
        g.fill(' ');
        g.setBackgroundColor(TextColor.ANSI.BLACK);
        int totalCols = m.getWidth() + QrRenderer.QUIET_ZONE * 2;
        int totalRows = m.getHeight() + QrRenderer.QUIET_ZONE * 2;
        for (int my = 0; my < totalRows && my < heightCells; my++) {
            for (int mx = 0; mx < totalCols
                    && (mx + 1) * QrRenderer.CELLS_PER_MODULE <= widthCells; mx++) {
                if (QrRenderer.isDark(m, mx - QrRenderer.QUIET_ZONE, my - QrRenderer.QUIET_ZONE)) {
                    int x = mx * QrRenderer.CELLS_PER_MODULE;
                    g.setCharacter(x, my, ' ');
                    g.setCharacter(x + 1, my, ' ');
                }
            }
        }
    }
}
