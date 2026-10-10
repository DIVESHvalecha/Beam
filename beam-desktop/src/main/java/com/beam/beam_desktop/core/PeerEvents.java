package com.beam.beam_desktop.core;

/**
 * WebRTC/DataChannel callbacks fired by WebRtcClient. Implemented by {@link BeamSession}.
 * May arrive on libwebrtc threads; BeamSession re-dispatches UI callbacks onto its event thread.
 */
public interface PeerEvents {
    void onLocalIceCandidate(String candidateJson);

    void onChannelOpen();

    void onChannelClosed();

    void onIncomingFile(IncomingFile offer, FileDecision decision);

    void onProgress(TransferProgress progress);

    void onTransferFinished(TransferResult result);

    void onTransferPaused(String reason);
}
