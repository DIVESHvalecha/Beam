package com.beam.beam_desktop.signaling;

public class IceCandidateDto {
    public String candidate;
    public String sdpMid;
    public int sdpMLineIndex;
    public String usernameFragment;

    public IceCandidateDto() {}

    public IceCandidateDto(String candidate, String sdpMid, int sdpMLineIndex) {
        this.candidate = candidate;
        this.sdpMid = sdpMid;
        this.sdpMLineIndex = sdpMLineIndex;
    }
}