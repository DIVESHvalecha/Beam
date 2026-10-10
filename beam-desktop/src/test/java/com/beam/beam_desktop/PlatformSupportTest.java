package com.beam.beam_desktop;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PlatformSupportTest {

    @Test
    void currentMachineIsSupported() {
        String platform = PlatformSupport.currentPlatform();
        assertNotNull(platform, "tests run on a supported dev/CI machine");
        assertTrue(platform.matches("^(macos|linux|windows)-(aarch64|x86_64)$"), platform);
        assertTrue(PlatformSupport.check().isEmpty(), "dev/CI machine must pass the preflight check");
    }
}
