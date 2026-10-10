package com.beam.beam_desktop.core;

/** The only thing a UI talks to. All callbacks arrive on BeamSession's single event thread, in order. */
public interface BeamListener {
    void onStateChanged(BeamState state);

    /** joinUrl = config.webBaseUrl + "/?c=" + code */
    void onCodeReady(String code, String joinUrl);

    void onPeerConnected();

    /** Call {@code decision.accept()} or {@code decision.reject(reason)}. Auto-rejects after 120 s. */
    void onIncomingFile(IncomingFile offer, FileDecision decision);

    void onProgress(TransferProgress p);

    void onTransferFinished(TransferResult r);

    /** Link dropped mid-transfer; a new {@code onCodeReady} follows with a fresh code. */
    void onPaused(String reason);

    void onError(BeamError e);
}
