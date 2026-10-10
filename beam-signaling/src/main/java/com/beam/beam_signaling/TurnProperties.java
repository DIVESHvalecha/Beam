package com.beam.beam_signaling;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "beam.turn")
public class TurnProperties {
    private String host;
    private int port;
    private long ttlSeconds = 3600;
    private String secret;

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public long getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
}