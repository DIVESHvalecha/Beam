package com.beam.beam_desktop.tui;

import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.Panel;
import java.util.List;

/** Generic end-of-flow screen: heading, detail lines, action buttons. */
public class ResultPanel extends Panel {

    public record Action(String label, Runnable handler) {}

    public ResultPanel(String heading, String detail, List<Action> actions) {
        super();
        addComponent(new EmptySpace());
        addComponent(new Label("  " + heading));
        addComponent(new EmptySpace());
        if (detail != null && !detail.isBlank()) {
            for (String line : detail.split("\n")) {
                addComponent(new Label("  " + line));
            }
            addComponent(new EmptySpace());
        }
        for (Action action : actions) {
            addComponent(new Button(action.label(), action.handler()));
        }
        addComponent(new EmptySpace());
        addComponent(new Label("Esc: home"));
    }
}
