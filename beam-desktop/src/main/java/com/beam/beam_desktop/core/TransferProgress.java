package com.beam.beam_desktop.core;

public record TransferProgress(
        TransferDirection direction, String fileName, long bytesDone, long bytesTotal, long resumedFromBytes) {}
