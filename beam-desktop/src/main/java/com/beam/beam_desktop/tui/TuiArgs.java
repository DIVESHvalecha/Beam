package com.beam.beam_desktop.tui;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Parsed command-line arguments. `send` without a path opens the file picker. */
public record TuiArgs(Action action, Path sendPath) {

    public enum Action {
        HOME,
        SEND,
        RECEIVE,
        HELP,
        VERSION
    }

    public static TuiArgs parse(String[] args) {
        if (args == null || args.length == 0) {
            return new TuiArgs(Action.HOME, null);
        }
        switch (args[0]) {
            case "--help", "-h", "help" -> {
                return new TuiArgs(Action.HELP, null);
            }
            case "--version", "-v", "version" -> {
                return new TuiArgs(Action.VERSION, null);
            }
            case "receive" -> {
                return new TuiArgs(Action.RECEIVE, null);
            }
            case "send" -> {
                if (args.length >= 2 && !args[1].isBlank()) {
                    return new TuiArgs(Action.SEND, Paths.get(args[1]).toAbsolutePath());
                }
                return new TuiArgs(Action.SEND, null);
            }
            default -> {
                return new TuiArgs(Action.HELP, null);
            }
        }
    }
}
