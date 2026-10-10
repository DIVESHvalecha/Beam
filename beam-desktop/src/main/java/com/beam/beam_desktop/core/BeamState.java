package com.beam.beam_desktop.core;

/** Lifecycle states reported via {@link BeamListener#onStateChanged}. */
public enum BeamState {
    IDLE,
    CONNECTING,
    WAITING_FOR_PEER,
    NEGOTIATING,
    CONNECTED,
    TRANSFERRING,
    PAUSED,
    DONE,
    FAILED
}
