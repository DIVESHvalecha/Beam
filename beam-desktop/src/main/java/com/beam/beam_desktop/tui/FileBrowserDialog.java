package com.beam.beam_desktop.tui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.ActionListBox;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowListenerAdapter;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-TUI file browser for the send flow. Directories first, files default to
 * newest-first with visible size/modified columns, live substring filter, sort
 * cycling, and hidden-file toggle — the OS-picker essentials Lanterna's
 * FileDialogBuilder lacks (unsorted, no search).
 */
public final class FileBrowserDialog extends BasicWindow {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private File currentDir;
    private FileBrowserModel.Sort sort = FileBrowserModel.Sort.NEWEST;
    private boolean showHidden;
    private final AtomicReference<File> chosen = new AtomicReference<>();
    private final AtomicBoolean done = new AtomicBoolean(false);

    private final Label pathLabel = new Label("");
    private final Label sortLabel = new Label("");
    private final TextBox filterBox = new TextBox(new TerminalSize(40, 1));
    private final ActionListBox listBox = new ActionListBox(new TerminalSize(72, 14));
    private final Label hintLabel = new Label("  Enter: open/send   Backspace: up   s: sort   h: hidden   Esc: cancel");

    private FileBrowserDialog(File startDir) {
        super("Choose a file to send");
        this.currentDir = startDir;
        setHints(List.of(Window.Hint.CENTERED));

        Panel root = new Panel(new LinearLayout(Direction.VERTICAL));
        root.addComponent(pathLabel);
        root.addComponent(new Label("Filter (/ to jump here, type to narrow):"));
        filterBox.setTextChangeListener((s, byUser) -> {
            if (byUser) {
                refresh();
            }
        });
        root.addComponent(filterBox);
        Panel sortRow = new Panel(new LinearLayout(Direction.HORIZONTAL));
        sortRow.addComponent(sortLabel);
        sortRow.addComponent(new Button("Sort [s]", this::cycleSort));
        sortRow.addComponent(new Button("Hidden [h]", this::toggleHidden));
        root.addComponent(sortRow);
        root.addComponent(new Label("Name                                          Size        Modified"));
        root.addComponent(listBox);
        root.addComponent(hintLabel);
        Panel btnRow = new Panel(new LinearLayout(Direction.HORIZONTAL));
        btnRow.addComponent(new Button("Send", this::sendSelected));
        btnRow.addComponent(new Button("Cancel", this::cancel));
        root.addComponent(btnRow);
        setComponent(root);

        addWindowListener(new WindowListenerAdapter() {
            @Override
            public void onInput(Window basePane, KeyStroke keyStroke, AtomicBoolean deliver) {
                if (keyStroke.getKeyType() == KeyType.Escape) {
                    deliver.set(false);
                    cancel();
                    return;
                }
                if (keyStroke.getKeyType() == KeyType.Backspace) {
                    // Backspace navigates up unless the filter box has focus and text.
                    if (!filterBox.isFocused() || filterBox.getText().isEmpty()) {
                        deliver.set(false);
                        goUp();
                        return;
                    }
                }
                if (keyStroke.getKeyType() == KeyType.Character && keyStroke.getCharacter() != null) {
                    char c = keyStroke.getCharacter();
                    if (c == '/' && !filterBox.isFocused()) {
                        deliver.set(false);
                        filterBox.takeFocus();
                        return;
                    }
                    if (!filterBox.isFocused()) {
                        if (c == 's' || c == 'S') {
                            cycleSort();
                            return;
                        }
                        if (c == 'h' || c == 'H') {
                            toggleHidden();
                            return;
                        }
                    }
                }
                if (keyStroke.getKeyType() == KeyType.Enter && filterBox.isFocused()
                        && !filterBox.getText().isEmpty()) {
                    // Enter in a non-empty filter jumps to the list instead of doing nothing.
                    deliver.set(false);
                    listBox.takeFocus();
                }
            }
        });
        refresh();
    }

    /** Blocks until the user picks a file or cancels. Returns null on cancel. */
    public static File showDialog(MultiWindowTextGUI gui, File startDir) {
        File dir = startDir;
        if (dir == null || !dir.isDirectory()) {
            dir = new File(System.getProperty("user.home"));
        }
        FileBrowserDialog dlg = new FileBrowserDialog(dir.getAbsoluteFile());
        gui.addWindowAndWait(dlg);
        return dlg.chosen.get();
    }

    private void refresh() {
        pathLabel.setText(" " + currentDir.getAbsolutePath());
        sortLabel.setText(" Sort: " + sort.label() + (showHidden ? " (hidden shown)" : ""));
        listBox.clearItems();
        if (currentDir.getParentFile() != null) {
            listBox.addItem("..  <up>", this::goUp);
        }
        List<File> kids = FileBrowserModel.list(currentDir, filterBox.getText(), sort, showHidden);
        if (kids.isEmpty()) {
            listBox.addItem("<empty — Backspace to go up, clear filter to see more>", () -> {});
        }
        for (File f : kids) {
            String row = formatRow(f);
            if (f.isDirectory()) {
                listBox.addItem(row, () -> enterDir(f));
            } else {
                listBox.addItem(row, () -> chooseAndClose(f));
            }
        }
    }

    private void enterDir(File dir) {
        currentDir = dir;
        filterBox.setText("");
        refresh();
    }

    private void goUp() {
        File parent = currentDir.getParentFile();
        if (parent != null) {
            enterDir(parent);
        }
    }

    private void cycleSort() {
        sort = sort.next();
        refresh();
    }

    private void toggleHidden() {
        showHidden = !showHidden;
        refresh();
    }

    private void sendSelected() {
        int idx = listBox.getSelectedIndex();
        // Index 0 may be ".."; rows map 1:1 after that when parent exists.
        List<File> kids = FileBrowserModel.list(currentDir, filterBox.getText(), sort, showHidden);
        int offset = currentDir.getParentFile() != null ? 1 : 0;
        int kidIdx = idx - offset;
        if (kidIdx >= 0 && kidIdx < kids.size()) {
            File f = kids.get(kidIdx);
            if (f.isDirectory()) {
                enterDir(f);
            } else {
                chooseAndClose(f);
            }
        } else if (!kids.isEmpty()) {
            // Fallback: <empty> row or stale selection — pick first file if any.
            for (File f : kids) {
                if (!f.isDirectory()) {
                    chooseAndClose(f);
                    return;
                }
            }
        }
    }

    private void chooseAndClose(File f) {
        if (done.compareAndSet(false, true)) {
            chosen.set(f);
            close();
        }
    }

    private void cancel() {
        if (done.compareAndSet(false, true)) {
            chosen.set(null);
            close();
        }
    }

    private static String formatRow(File f) {
        String name = TextUtil.sanitizeDisplayName(f.getName(), 42);
        if (f.isDirectory()) {
            return String.format("[%-40s]  <dir>", truncate(name, 40));
        }
        String size = TextUtil.humanSize(f.length());
        String date = formatDate(f.lastModified());
        return String.format("%-42s  %9s  %s", truncate(name, 42), size, date);
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String formatDate(long epochMillis) {
        if (epochMillis <= 0) {
            return "--";
        }
        try {
            LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
            return dt.format(DATE_FMT);
        } catch (Exception e) {
            return "--";
        }
    }
}
