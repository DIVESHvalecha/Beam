package com.beam.beam_desktop.tui;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Pure file-listing logic for the send-file browser. No I/O beyond File metadata, no UI — unit-tested. */
public final class FileBrowserModel {

    public enum Sort {
        NEWEST("Newest"),
        NAME("Name"),
        OLDEST("Oldest"),
        LARGEST("Largest"),
        SMALLEST("Smallest");

        private final String label;

        Sort(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private FileBrowserModel() {}

    /**
     * Lists children of {@code dir}: directories first, then files per {@code sort}.
     * Applies case-insensitive substring {@code filter} on names; hides dot-files unless
     * {@code showHidden}. Never returns null; unreadable dirs yield an empty list.
     */
    public static List<File> list(File dir, String filter, Sort sort, boolean showHidden) {
        List<File> out = new ArrayList<>();
        if (dir == null || sort == null) {
            return out;
        }
        File[] kids;
        try {
            kids = dir.listFiles();
        } catch (SecurityException e) {
            return out;
        }
        if (kids == null) {
            return out;
        }
        String q = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        List<File> dirs = new ArrayList<>();
        List<File> files = new ArrayList<>();
        for (File f : kids) {
            if (!showHidden && f.getName().startsWith(".")) {
                continue;
            }
            if (!q.isEmpty() && !f.getName().toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            if (f.isDirectory()) {
                dirs.add(f);
            } else {
                files.add(f);
            }
        }
        dirs.sort(Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        files.sort(fileComparator(sort));
        out.addAll(dirs);
        out.addAll(files);
        return out;
    }

    static Comparator<File> fileComparator(Sort sort) {
        return switch (sort) {
            case NAME -> Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT));
            case OLDEST -> Comparator.comparingLong(File::lastModified);
            case LARGEST -> Comparator.comparingLong(File::length).reversed();
            case SMALLEST -> Comparator.comparingLong(File::length);
            case NEWEST -> Comparator.comparingLong(File::lastModified).reversed();
        };
    }
}
