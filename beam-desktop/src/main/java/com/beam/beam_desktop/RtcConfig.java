package com.beam.beam_desktop;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/** Shared, long-lived WebRTC factory. BeamSession instances borrow it; they never dispose it. */
@Configuration
public class RtcConfig {

    // Dummy audio: Beam only uses DataChannels (never mic/speaker), and the default
    // platform audio device fails to initialize on hosts without an audio system
    // (servers, CI runners, minimal Linux installs). kDummyAudio makes the factory
    // constructible everywhere.
    // Lazy: the native library then loads on first transfer at the latest, after
    // PlatformSupport has vetted the machine with a friendly message on failure.
    @Bean
    @Lazy
    public PeerConnectionFactory peerConnectionFactory() {
        AudioDeviceModule adm = new AudioDeviceModule(AudioLayer.kDummyAudio);
        return new PeerConnectionFactory(adm);
    }
}
