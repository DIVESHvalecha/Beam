package com.beam.beam_desktop.core;

public record BeamError(String code, String message, Throwable cause) {
    public static BeamError of(String code, String message) {
        return new BeamError(code, message, null);
    }

    public static BeamError of(String code, String message, Throwable cause) {
        return new BeamError(code, message, cause);
    }
}
