package com.beam.beam_desktop;

import com.beam.beam_desktop.core.BeamConfig;
import com.beam.beam_desktop.signaling.SignalMapper;
import com.beam.beam_desktop.tui.CliApp;
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
                    System.out.println("       java -jar beam.jar --cli (--send [path] | --receive)");
                    System.out.println();
                    System.out.println("  (no args)     open the Beam terminal UI");
                    System.out.println("  send [path]   open the UI straight into sending (file picker if no path)");
                    System.out.println("  receive       open the UI straight into receiving");
                    System.out.println("  --cli         plain-console mode (no full-screen UI); works everywhere,");
                    System.out.println("                including terminals the UI cannot use (e.g. Windows CMD)");
                    System.out.println("  --tui         force the full-screen UI (default unless --cli)");
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
                    if (parsed.cli()) {
                        int code = new CliApp(config, signalMapper, rtcFactory).run(parsed);
                        if (code != 0) {
                            System.exit(code);
                        }
                        return;
                    }
                    try {
                        new TuiApp(config, signalMapper, rtcFactory).run(parsed);
                    } catch (java.io.IOException e) {
                        // e.g. Windows CMD: Lanterna shells out to /bin/stty, which does not
                        // exist there. Fall back to the console UI instead of a stack trace.
                        System.out.println("beam: full-screen UI unavailable ("
                                + e.getMessage() + ") — using console mode.");
                        int code = new CliApp(config, signalMapper, rtcFactory).run(parsed);
                        if (code != 0) {
                            System.exit(code);
                        }
                    }
                }
            }
        };
    }

    static String version() {
        String v = BeamDesktopApplication.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }
}
