package com.beam.beam_desktop;

import dev.onvoid.webrtc.PeerConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/** Shared, long-lived WebRTC factory. BeamSession instances borrow it; they never dispose it. */
@Configuration
public class RtcConfig {

    // Lazy: the native library loads on first use, after PlatformSupport has vetted
    // the machine — so an unsupported platform gets a friendly message, not a stack trace.
    @Bean
    @Lazy
    public PeerConnectionFactory peerConnectionFactory() {
        return new PeerConnectionFactory();
    }
}
