package com.beam.beam_desktop.core;

import com.beam.beam_desktop.signaling.IceServerDto;
import java.util.List;

/** Signaling transport callbacks. Implemented by {@link BeamSession}. */
public interface SignalingEvents {
    void onRegistered(String code);

    void onPaired(List<IceServerDto> iceServers);

    void onAnswer(String sdp);

    void onRemoteCandidate(String candidateJson);

    void onPeerLeft();

    void onServerError(String message);

    void onSignalingClosed(int code, String reason);

    void onConnectionFailed(Throwable cause);
}
