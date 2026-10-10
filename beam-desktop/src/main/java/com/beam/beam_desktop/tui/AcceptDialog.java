package com.beam.beam_desktop.tui;

import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowListenerAdapter;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.concurrent.atomic.AtomicBoolean;

/** Modal incoming-file prompt. Never auto-accepts: Accept is always an explicit key press. */
public class AcceptDialog extends BasicWindow {

    private final Label countdownLabel = new Label("");
    private final Button acceptButton;
    private final Button rejectButton;

    public AcceptDialog(String displayName, String humanSize, Long resumePercent,
                        Runnable onAccept, Runnable onReject) {
        super("Incoming file");
        Panel root = new Panel();
        root.addComponent(new Label("  \"" + displayName + "\" (" + humanSize + ")"));
        root.addComponent(new Label("  wants to send you a file."));
        if (resumePercent != null) {
            root.addComponent(new Label("  A partial copy exists; transfer will"));
            root.addComponent(new Label("  resume at " + resumePercent + "%."));
        }
        root.addComponent(new EmptySpace());
        root.addComponent(countdownLabel);
        root.addComponent(new EmptySpace());

        Panel buttons = new Panel(new LinearLayout(Direction.HORIZONTAL));
        acceptButton = new Button("Accept", onAccept);
        rejectButton = new Button("Reject", onReject);
        buttons.addComponent(acceptButton);
        buttons.addComponent(rejectButton);
        root.addComponent(buttons);
        setComponent(root);

        // Esc rejects. Default focus is Reject so Enter never accepts by accident.
        addWindowListener(new WindowListenerAdapter() {
            @Override
            public void onInput(Window basePane, KeyStroke keyStroke, AtomicBoolean deliver) {
                if (keyStroke.getKeyType() == KeyType.Escape) {
                    deliver.set(false);
                    onReject.run();
                }
            }
        });
    }

    /** Focus Reject; call on the GUI thread after the dialog is shown. */
    public void focusReject() {
        rejectButton.takeFocus();
    }

    public void setCountdown(long secondsLeft) {
        countdownLabel.setText("  Auto-rejects in " + secondsLeft + " s");
    }
}
