package com.beam.beam_signaling;

import java.util.List;

public class SignalMessage {
    private String type;          // REGISTER, REGISTERED, JOIN, OFFER, ANSWER, ICE_CANDIDATE, ERROR
    private String sessionCode;   // used in JOIN request, REGISTERED response
    private String address;       // legacy — no longer used once WebRTC replaces plain address exchange
    private String errorMessage;  // used in ERROR
    private String sdp;           // used in OFFER / ANSWER — the SDP blob
    private String candidate;     // used in ICE_CANDIDATE — one ICE candidate string
    private List<IceServerDto> iceServers; // NEW — populated on PAIRED


    public SignalMessage() {}

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getSessionCode() { return sessionCode; }
    public void setSessionCode(String sessionCode) { this.sessionCode = sessionCode; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getSdp() { return sdp; }
    public void setSdp(String sdp) { this.sdp = sdp; }

    public String getCandidate() { return candidate; }
    public void setCandidate(String candidate) { this.candidate = candidate; }

    public List<IceServerDto> getIceServers() { return iceServers; }
    public void setIceServers(List<IceServerDto> iceServers) { this.iceServers = iceServers; }
}