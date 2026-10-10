package com.beam.beam_desktop.signaling;

import java.util.List;

public class SignalMessage {
    private String type;
    private String sessionCode;
    private String address;
    private String errorMessage;
    private String sdp;           // added in Phase 4
    private String candidate;
    private List<IceServerDto> iceServers;

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