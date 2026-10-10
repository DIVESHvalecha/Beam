package com.beam.beam_desktop.core;

import java.nio.file.Path;

/** Final verdict of a transfer. {@code ok=true} carries the saved path (receive) or is informational (send). */
public record TransferResult(
        boolean ok, TransferDirection direction, String fileName, Path savedPath, String failureReason) {
    public static TransferResult success(TransferDirection direction, String fileName, Path savedPath) {
        return new TransferResult(true, direction, fileName, savedPath, null);
    }

    public static TransferResult failure(TransferDirection direction, String fileName, String reason) {
        return new TransferResult(false, direction, fileName, null, reason);
    }
}
