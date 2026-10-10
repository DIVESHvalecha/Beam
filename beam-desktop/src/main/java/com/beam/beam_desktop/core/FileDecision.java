package com.beam.beam_desktop.core;

/** User verdict on an {@link IncomingFile}. Exactly one of accept/reject takes effect (first call wins). */
public interface FileDecision {
    void accept();

    void reject(String reason);
}
