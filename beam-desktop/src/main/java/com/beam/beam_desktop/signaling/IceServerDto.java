package com.beam.beam_desktop.signaling;

import java.util.List;

public class IceServerDto {
    private List<String> urls;
    private String username;
    private String credential;

    public IceServerDto() {}

    public List<String> getUrls() { return urls; }
    public void setUrls(List<String> urls) { this.urls = urls; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getCredential() { return credential; }
    public void setCredential(String credential) { this.credential = credential; }
}