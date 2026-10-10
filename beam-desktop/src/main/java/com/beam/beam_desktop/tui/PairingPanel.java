package com.beam.beam_desktop.tui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.Border;
import com.googlecode.lanterna.gui2.Borders;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.terminal.Terminal;
import com.google.zxing.common.BitMatrix;

/**
 * Pairing panel: large spaced code, QR (or a too-small-terminal fallback), join URL, status.
 * Re-renders the QR when the terminal is resized.
 */
public class PairingPanel extends Panel {

    private final WindowBasedTextGUI gui;
    private final Label codeLabel = new Label("…");
    private final Label urlLabel = new Label("");
    private final Label statusLabel = new Label("");
    private final Panel qrHolder = new Panel();
    private final QrBox qrBox = new QrBox();

    private volatile BitMatrix qrMatrix;
    private boolean qrShown = false;

    public PairingPanel(WindowBasedTextGUI gui, Terminal terminal) {
        super();
        this.gui = gui;
        addComponent(new EmptySpace());
        addComponent(codeLabel);
        addComponent(new EmptySpace());
        Border qrFrame = Borders.singleLine("Scan with your phone");
        qrFrame.setComponent(qrHolder);
        qrHolder.addComponent(qrBox);
        addComponent(qrFrame);
        addComponent(new EmptySpace());
        addComponent(urlLabel);
        addComponent(new EmptySpace());
        addComponent(statusLabel);
        addComponent(new EmptySpace());
        addComponent(new Label("Esc: cancel"));
        terminal.addResizeListener((t, newSize) -> gui.getGUIThread().invokeLater(this::refreshQr));
    }

    public void setCode(String code, String joinUrl) {
        gui.getGUIThread().invokeLater(() -> {
            codeLabel.setText("  " + TextUtil.spacedCode(code));
            urlLabel.setText("  " + joinUrl);
            try {
                qrMatrix = QrRenderer.encode(joinUrl);
            } catch (IllegalArgumentException e) {
                qrMatrix = null;
            }
            refreshQr();
        });
    }

    public void setStatus(String status) {
        gui.getGUIThread().invokeLater(() -> statusLabel.setText("  " + status));
    }

    /** Must run on the GUI thread. */
    private void refreshQr() {
        BitMatrix matrix = qrMatrix;
        if (matrix != null && qrFits(matrix)) {
            qrBox.setMatrix(matrix);
            if (!qrShown) {
                qrShown = true;
                qrHolder.removeAllComponents();
                qrHolder.addComponent(qrBox);
            }
        } else {
            qrShown = false;
            qrHolder.removeAllComponents();
            if (matrix != null) {
                qrHolder.addComponent(new Label("Make the terminal larger"));
                qrHolder.addComponent(new Label("to see the QR code."));
            } else {
                qrHolder.addComponent(new Label("Waiting for code…"));
            }
        }
        try {
            qrHolder.invalidate();
        } catch (Exception ignored) {
            // holder not yet attached; harmless
        }
    }

    private boolean qrFits(BitMatrix matrix) {
        TerminalSize size;
        try {
            size = gui.getScreen().getTerminalSize();
        } catch (Exception e) {
            return false;
        }
        // Reserve room for the code, URL, status and window chrome around the QR.
        int availCols = size.getColumns() - 8;
        int availRows = size.getRows() - 14;
        return availCols >= QrRenderer.renderedColumns(matrix)
                && availRows >= QrRenderer.renderedRows(matrix);
    }
}
