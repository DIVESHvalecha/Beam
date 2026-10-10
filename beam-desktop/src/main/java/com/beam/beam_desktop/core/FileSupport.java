package com.beam.beam_desktop.core;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/** Pure filename / fileId helpers shared by WebRtcClient and unit tests. No I/O beyond existence checks. */
public final class FileSupport {
    private FileSupport() {}

    private static final Pattern FILE_ID_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    public static boolean isValidFileId(String fileId) {
        return fileId != null && FILE_ID_PATTERN.matcher(fileId).matches();
    }

    /** The name comes from the remote peer: keep only the last path component. */
    public static String sanitizeFileName(String declared) {
        String candidate = "beam-received-file";
        if (declared == null) {
            return candidate;
        }
        try {
            Path fileName = Paths.get(declared).getFileName();
            if (fileName != null) {
                String s = fileName.toString().trim();
                if (!s.isEmpty() && !s.equals(".") && !s.equals("..")) {
                    candidate = s;
                }
            }
        } catch (InvalidPathException ignored) {
            // fall through to default
        }
        return candidate;
    }

    /** Never overwrite an existing file: {@code name}, {@code name (1).ext}, ... */
    public static Path uniqueTarget(Path dir, String name) {
        Path target = dir.resolve(name);
        if (!Files.exists(target)) {
            return target;
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; ; i++) {
            Path candidate = dir.resolve(base + " (" + i + ")" + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }
}
