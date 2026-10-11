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

    @Test
    void nativeResourceNamesMatchPublishedArtifacts() {
        // Unix artifacts carry the `lib` prefix; the Windows DLLs ship without it
        // (verified against the webrtc-java 0.19.0 classifier jars on Maven Central).
        assertEquals("/libwebrtc-java-macos-aarch64.dylib",
                PlatformSupport.nativeResourceName("macos-aarch64"));
        assertEquals("/libwebrtc-java-macos-x86_64.dylib",
                PlatformSupport.nativeResourceName("macos-x86_64"));
        assertEquals("/libwebrtc-java-linux-x86_64.so",
                PlatformSupport.nativeResourceName("linux-x86_64"));
        assertEquals("/libwebrtc-java-linux-aarch64.so",
                PlatformSupport.nativeResourceName("linux-aarch64"));
        assertEquals("/webrtc-java-windows-x86_64.dll",
                PlatformSupport.nativeResourceName("windows-x86_64"));
        assertEquals("/webrtc-java-windows-aarch64.dll",
                PlatformSupport.nativeResourceName("windows-aarch64"));
        assertNull(PlatformSupport.nativeResourceName(null));
        assertNull(PlatformSupport.nativeResourceName("solaris-sparc"));
    }

    @Test
    void allPlatformResourcesResolveOnClasspath() {
        // Every classifier jar is a compile dependency, so every platform's native
        // library must resolve here — this catches naming mistakes without Windows CI.
        for (String platform : new String[]{"macos-aarch64", "macos-x86_64",
                "linux-x86_64", "linux-aarch64", "windows-x86_64", "windows-aarch64"}) {
            String resource = PlatformSupport.nativeResourceName(platform);
            assertNotNull(getClass().getResourceAsStream(resource),
                    "missing native library for " + platform + " (" + resource + ")");
        }
    }
}
