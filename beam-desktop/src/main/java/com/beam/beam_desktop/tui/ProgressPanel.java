package com.beam.beam_desktop.tui;

import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.ProgressBar;

/** Shared send/receive progress panel: bar, percent, bytes, speed, ETA, resume note. */
public class ProgressPanel extends Panel {

    private final Label titleLabel = new Label("");
    private final ProgressBar bar = new ProgressBar(0, 100, 40);
    private final Label statsLabel = new Label("");
    private final Label noteLabel = new Label("");

    public ProgressPanel() {
        super();
        addComponent(new EmptySpace());
        addComponent(titleLabel);
        addComponent(new EmptySpace());
        addComponent(bar);
        addComponent(statsLabel);
        addComponent(noteLabel);
        addComponent(new EmptySpace());
        addComponent(new Label("Esc: cancel"));
    }

    public void showFile(String verb, String displayName, long totalBytes, long resumedFromBytes) {
        titleLabel.setText("  " + verb + " " + displayName + " (" + TextUtil.humanSize(totalBytes) + ")");
        if (resumedFromBytes > 0 && totalBytes > 0) {
            long pct = 100 * resumedFromBytes / totalBytes;
            noteLabel.setText("  Resumed from " + pct + "% (" + TextUtil.humanSize(resumedFromBytes) + " kept)");
        } else {
            noteLabel.setText("");
        }
        update(0, totalBytes, -1, -1);
    }

    public void update(long bytesDone, long bytesTotal, double bytesPerSecond, long etaSeconds) {
        int pct = bytesTotal > 0 ? (int) Math.min(100, (100 * bytesDone) / bytesTotal) : 100;
        bar.setValue(pct);
        statsLabel.setText("  " + pct + "%   " + TextUtil.humanSize(bytesDone) + " / "
                + TextUtil.humanSize(bytesTotal) + "   " + TextUtil.formatSpeed(bytesPerSecond) + "   ETA "
                + TextUtil.formatEta(bytesPerSecond > 0 ? etaSeconds : -1));
    }
}
