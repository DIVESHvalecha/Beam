package com.beam.beam_desktop.tui;

/** Pure formatting/sanitising helpers. No I/O, no UI — unit-tested. */
public final class TextUtil {

    private TextUtil() {}

    /** "8405238" -> "8.0 MB". Binary units, one decimal. */
    public static String humanSize(long bytes) {
        if (bytes < 0) {
            bytes = 0;
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double v = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        String unit = "KB";
        v /= 1024.0;
        for (int i = 0; i < units.length; i++) {
            unit = units[i];
            if (v < 1024.0 || i == units.length - 1) {
                break;
            }
            v /= 1024.0;
        }
        return String.format("%.1f %s", v, unit);
    }

    /** "4194304 B/s" -> "4.0 MB/s". Returns "—" when the rate is unknown/zero. */
    public static String formatSpeed(double bytesPerSecond) {
        if (!(bytesPerSecond > 0)) {
            return "--";
        }
        return humanSize((long) bytesPerSecond) + "/s";
    }

    /** Seconds -> "02:37". Negative/unknown -> "--:--". */
    public static String formatEta(long seconds) {
        if (seconds < 0) {
            return "--:--";
        }
        long m = seconds / 60;
        long s = seconds % 60;
        if (m > 99) {
            return "99:59+";
        }
        return String.format("%02d:%02d", m, s);
    }

    /** "RUUJ3V" -> "R U U J 3 V" for the large pairing-code display. */
    public static String spacedCode(String code) {
        if (code == null || code.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(code.charAt(i));
        }
        return sb.toString();
    }

    /**
     * Makes a remote-supplied file name safe to show in the terminal: strips all control
     * characters and ESC sequences (terminal escape injection), keeps only the last path
     * component, and truncates long names with an ellipsis. Display only — saving still
     * goes through the existing sanitiser, which is not weakened.
     */
    public static String sanitizeDisplayName(String raw, int maxLength) {
        if (raw == null) {
            return "unnamed file";
        }
        // Strip ANSI CSI escape sequences first (ESC [ ... letter), then any lone ESC,
        // then every remaining control character.
        String s = raw.replaceAll("\u001B\\[[0-9;?]*[ -/]*[@-~]", "");
        s = s.replace("\u001B", "");
        s = s.replaceAll("\\p{Cntrl}", "");
        // Keep only the last path component (either separator style).
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        s = s.trim();
        if (s.isEmpty() || s.equals(".") || s.equals("..")) {
            return "unnamed file";
        }
        if (maxLength > 0 && s.length() > maxLength) {
            return s.substring(0, Math.max(0, maxLength - 1)) + "…";
        }
        return s;
    }
}
