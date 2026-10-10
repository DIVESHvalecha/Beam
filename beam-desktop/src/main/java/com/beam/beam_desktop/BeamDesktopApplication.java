package com.beam.beam_desktop;

import com.beam.beam_desktop.core.BeamConfig;
import com.beam.beam_desktop.signaling.SignalMapper;
import com.beam.beam_desktop.tui.TuiApp;
import com.beam.beam_desktop.tui.TuiArgs;
import dev.onvoid.webrtc.PeerConnectionFactory;
import java.nio.file.Files;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class BeamDesktopApplication {

    public static void main(String[] args) {
        SpringApplication.run(BeamDesktopApplication.class, args);
    }

    @Bean
    public CommandLineRunner runBeam(BeamConfig config, SignalMapper signalMapper,
                                     PeerConnectionFactory rtcFactory) {
        return args -> {
            TuiArgs parsed = TuiArgs.parse(args);
            switch (parsed.action()) {
                case HELP -> {
                    System.out.println("Usage: java -jar beam.jar [send [path] | receive | --help | --version]");
                    System.out.println();
                    System.out.println("  (no args)     open the Beam terminal UI");
                    System.out.println("  send [path]   open the UI straight into sending (file picker if no path)");
                    System.out.println("  receive       open the UI straight into receiving");
                    System.out.println("  --help        show this help");
                    System.out.println("  --version     show the version");
                    return;
                }
                case VERSION -> {
                    System.out.println("beam " + version());
                    return;
                }
                default -> {
                    if (parsed.action() == TuiArgs.Action.SEND && parsed.sendPath() != null
                            && !Files.isRegularFile(parsed.sendPath())) {
                        System.err.println("beam: file not found: " + parsed.sendPath());
                        System.err.println("Run without a path to pick a file in the UI.");
                        return;
                    }
                    var unsupported = PlatformSupport.check();
                    if (unsupported.isPresent()) {
                        System.err.println("beam: " + unsupported.get());
                        System.exit(1);
                        return;
                    }
                    // The TUI owns the terminal from here; nothing else may print.
                    new TuiApp(config, signalMapper, rtcFactory).run(parsed);
                }
            }
        };
    }

    static String version() {
        String v = BeamDesktopApplication.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }
}
