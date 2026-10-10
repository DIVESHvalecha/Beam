package com.beam.beam_desktop.tui;

import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.Panel;
import java.util.List;

/** Error screen: friendly message plus Retry / Home / Quit. */
public class ErrorPanel extends Panel {

    public ErrorPanel(String message, Runnable onRetry, Runnable onHome, Runnable onQuit) {
        super();
        addComponent(new EmptySpace());
        addComponent(new Label("  Something went wrong"));
        addComponent(new EmptySpace());
        for (String line : message.split("\n")) {
            addComponent(new Label("  " + line));
        }
        addComponent(new EmptySpace());
        for (ResultPanel.Action action : List.of(
                new ResultPanel.Action("Retry", onRetry),
                new ResultPanel.Action("Home", onHome),
                new ResultPanel.Action("Quit", onQuit))) {
            addComponent(new Button(action.label(), action.handler()));
        }
        addComponent(new EmptySpace());
        addComponent(new Label("Esc: home"));
    }
}
