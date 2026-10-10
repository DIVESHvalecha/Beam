package com.beam.beam_desktop.tui;

import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.Panel;

/** Beam home screen: title, tagline, three buttons, key hints. */
public class HomePanel extends Panel {

    public HomePanel(Runnable onSend, Runnable onReceive, Runnable onQuit) {
        super();
        addComponent(new EmptySpace());
        addComponent(new Label("  Beam"));
        addComponent(new Label("  Private phone-to-laptop file transfer. No cloud, no account."));
        addComponent(new EmptySpace());
        addComponent(new Button("Send a file", onSend));
        addComponent(new Button("Receive a file", onReceive));
        addComponent(new Button("Quit", onQuit));
        addComponent(new EmptySpace());
        addComponent(new Label("  Keys: (S)end   (R)eceive   (Q)uit"));
    }
}
