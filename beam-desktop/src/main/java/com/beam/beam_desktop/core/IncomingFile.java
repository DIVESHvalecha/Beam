package com.beam.beam_desktop.core;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Offer parked by WebRtcClient until the user accepts or rejects via {@link FileDecision}. */
public final class IncomingFile {
    private final String fileId;
    private final String fileName;
    private final long size;
    private final String mimeType;
    private final long existingOffset;

    public IncomingFile(String fileId, String fileName, long size, String mimeType, long existingOffset) {
        this.fileId = fileId;
        this.fileName = fileName;
        this.size = size;
        this.mimeType = mimeType;
        this.existingOffset = existingOffset;
    }

    public String fileId() {
        return fileId;
    }

    /** Sanitized (last path component only). */
    public String fileName() {
        return fileName;
    }

    public long size() {
        return size;
    }

    public String mimeType() {
        return mimeType;
    }

    /** Bytes already held from a previous attempt (0 if none). */
    public long existingOffset() {
        return existingOffset;
    }
}
