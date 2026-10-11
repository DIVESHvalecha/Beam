package com.beam.beam_desktop.tui;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Parsed command-line arguments. `send` without a path opens the file picker in the TUI
 * (or prompts for a path in CLI mode). `--cli` forces plain-console mode, which is also
 * the automatic fallback when the Lanterna terminal cannot start (e.g. Windows CMD,
 * where no `stty` exists). `--tui` forces the full-screen UI.
 */
public record TuiArgs(Action action, Path sendPath, boolean cli) {

    public enum Action {
        HOME,
        SEND,
        RECEIVE,
        HELP,
        VERSION
    }

    public static TuiArgs parse(String[] args) {
        if (args == null || args.length == 0) {
            return new TuiArgs(Action.HOME, null, false);
        }
        boolean cli = false;
        boolean tui = false;
        List<String> rest = new ArrayList<>();
        for (String a : args) {
            switch (a) {
                case "--cli", "--no-tui" -> cli = true;
                case "--tui" -> tui = true;
                default -> rest.add(a);
            }
        }
        if (tui) {
            cli = false;
        }
        if (rest.isEmpty()) {
            return new TuiArgs(Action.HOME, null, cli);
        }
        switch (rest.get(0)) {
            case "--help", "-h", "help" -> {
                return new TuiArgs(Action.HELP, null, cli);
            }
            case "--version", "-v", "version" -> {
                return new TuiArgs(Action.VERSION, null, cli);
            }
            case "receive", "--receive" -> {
                return new TuiArgs(Action.RECEIVE, null, cli);
            }
            case "send", "--send" -> {
                if (rest.size() >= 2 && !rest.get(1).isBlank()) {
                    return new TuiArgs(Action.SEND, Paths.get(rest.get(1)).toAbsolutePath(), cli);
                }
                return new TuiArgs(Action.SEND, null, cli);
            }
            default -> {
                return new TuiArgs(Action.HELP, null, cli);
            }
        }
    }
}
