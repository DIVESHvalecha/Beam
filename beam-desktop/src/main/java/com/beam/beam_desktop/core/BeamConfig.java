package com.beam.beam_desktop.core;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Runtime configuration. Defaults below; override via {@code application.yaml} ({@code beam.*})
 * or environment variables {@code BEAM_SIGNALING_URL}, {@code BEAM_WEB_URL},
 * {@code BEAM_DOWNLOAD_DIR}, {@code BEAM_PARTIAL_DIR} (relaxed binding maps them onto {@code beam.*}).
 */
@Component
@ConfigurationProperties(prefix = "beam")
public class BeamConfig {
    private String signalingUrl = "wss://signal.usebeam.tech/signal";
    private String webBaseUrl = "https://usebeam.tech";
    private String downloadDir = Paths.get(System.getProperty("user.home"), "Downloads").toString();
    private String partialDir = Paths.get(System.getProperty("user.home"), ".beam", "partial").toString();

    public String getSignalingUrl() {
        return signalingUrl;
    }

    public void setSignalingUrl(String signalingUrl) {
        this.signalingUrl = signalingUrl;
    }

    public String getWebBaseUrl() {
        return webBaseUrl;
    }

    public void setWebBaseUrl(String webBaseUrl) {
        this.webBaseUrl = webBaseUrl;
    }

    public String getDownloadDir() {
        return downloadDir;
    }

    public void setDownloadDir(String downloadDir) {
        this.downloadDir = downloadDir;
    }

    public String getPartialDir() {
        return partialDir;
    }

    public void setPartialDir(String partialDir) {
        this.partialDir = partialDir;
    }

    public Path downloadDirPath() {
        return Paths.get(downloadDir);
    }

    public Path partialDirPath() {
        return Paths.get(partialDir);
    }

    public String joinUrl(String code) {
        String base = webBaseUrl.endsWith("/") ? webBaseUrl.substring(0, webBaseUrl.length() - 1) : webBaseUrl;
        return base + "/?c=" + code;
    }
}
