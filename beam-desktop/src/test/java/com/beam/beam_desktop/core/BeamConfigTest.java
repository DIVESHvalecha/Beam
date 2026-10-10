package com.beam.beam_desktop.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BeamConfigTest {

    @Test
    void defaults() {
        BeamConfig c = new BeamConfig();
        assertEquals("wss://signal.usebeam.tech/signal", c.getSignalingUrl());
        assertEquals("https://usebeam.tech", c.getWebBaseUrl());
        assertTrue(c.getDownloadDir().endsWith("Downloads"));
        assertTrue(c.getPartialDir().endsWith(".beam/partial".replace("/", java.io.File.separator)));
    }

    @Test
    void joinUrlFormats() {
        BeamConfig c = new BeamConfig();
        assertEquals("https://usebeam.tech/?c=AB12", c.joinUrl("AB12"));
        c.setWebBaseUrl("https://usebeam.tech/");
        assertEquals("https://usebeam.tech/?c=AB12", c.joinUrl("AB12"));
    }
}
