package com.beam.beam_desktop;

import java.util.Locale;
import java.util.Optional;

/**
 * Pre-flight platform check, run before the TUI starts so an unsupported machine gets
 * one human-readable line instead of an UnsatisfiedLinkError stack trace.
 *
 * <p>webrtc-java's NativeLoader maps os.name/os.arch to a platform name and loads
 * {@code libwebrtc-java-<platform>.dylib/.so/.dll} from the classpath. This check
 * mirrors that mapping and verifies the matching classifier jar actually made it
 * into the packaged beam.jar.
 */
public final class PlatformSupport {

    private PlatformSupport() {}

    /** Minimum Java feature version (LTS 21: Spring Boot 4 floor is 17, 21 is our release). */
    public static final int MIN_JAVA = 21;

    /** Empty when this machine is supported; otherwise the message to print. */
    public static Optional<String> check() {
        int java = Runtime.version().feature();
        if (java < MIN_JAVA) {
            return Optional.of("beam needs Java " + MIN_JAVA + " or newer (found Java " + java
                    + "). Install a current LTS from https://adoptium.net and run: java -jar beam.jar");
        }
        String platform = currentPlatform();
        if (platform == null) {
            return Optional.of("beam doesn't support this platform (" + System.getProperty("os.name")
                    + " / " + System.getProperty("os.arch") + "). Supported: macOS, Linux and"
                    + " Windows on 64-bit Intel/ARM.");
        }
        String ext = platform.startsWith("windows") ? "dll"
                : platform.startsWith("macos") ? "dylib" : "so";
        String resource = "/libwebrtc-java-" + platform + "." + ext;
        if (PeerConnectionFactoryHolder.class.getResourceAsStream(resource) == null) {
            return Optional.of("beam.jar is missing the native library for this platform (" + platform
                    + "). Download a fresh beam.jar from the GitHub release page.");
        }
        return Optional.empty();
    }

    /** Loads the class only (never triggers native loading); keeps the check side-effect free. */
    private static final class PeerConnectionFactoryHolder {
        // uses its own class loader lookup via Class#getResourceAsStream
    }

    /** Maps os.name/os.arch onto a webrtc-java classifier, or null when unsupported. */
    static String currentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String family;
        if (os.contains("mac")) family = "macos";
        else if (os.contains("linux")) family = "linux";
        else if (os.contains("win")) family = "windows";
        else return null;
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        boolean x64 = arch.contains("x86_64") || arch.contains("amd64");
        if (!arm && !x64) return null;
        return family + "-" + (arm ? "aarch64" : "x86_64");
    }
}
