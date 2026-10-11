package com.beam.beam_desktop.tui;

import com.beam.beam_desktop.core.BeamConfig;
import com.beam.beam_desktop.core.BeamError;
import com.beam.beam_desktop.core.BeamListener;
import com.beam.beam_desktop.core.BeamSession;
import com.beam.beam_desktop.core.BeamState;
import com.beam.beam_desktop.core.FileDecision;
import com.beam.beam_desktop.core.IncomingFile;
import com.beam.beam_desktop.core.TransferDirection;
import com.beam.beam_desktop.core.TransferProgress;
import com.beam.beam_desktop.core.TransferResult;
import com.beam.beam_desktop.signaling.SignalMapper;
import com.google.zxing.common.BitMatrix;
import dev.onvoid.webrtc.PeerConnectionFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plain-console UI: the fallback when Lanterna cannot own the terminal (notably Windows
 * CMD, where no {@code stty} exists) and the explicit {@code --cli} mode. Prints the code,
 * join URL and an ANSI-background QR (same gapless technique as the TUI), text progress,
 * and a y/N accept prompt. No Lanterna, no new dependencies — stdout plus stdin only.
 */
public class CliApp implements BeamListener {

    private static final Logger log = LoggerFactory.getLogger(CliApp.class);
    static final long CONSENT_SECONDS = 120;
    private static final long PROGRESS_MIN_INTERVAL_MS = 1000;

    private final BeamConfig config;
    private final SignalMapper signalMapper;
    private final PeerConnectionFactory rtcFactory;
    private final PrintStream out;
    private final BufferedReader stdin;

    private final Object printLock = new Object();
    private final CountDownLatch done = new CountDownLatch(1);
    private final AtomicInteger exitCode = new AtomicInteger(1);
    private final AtomicBoolean resultShown = new AtomicBoolean(false);

    private volatile BeamSession session;
    private volatile BeamState lastState = BeamState.IDLE;
    private volatile TransferProgress lastProgress;
    private final SpeedTracker speed = new SpeedTracker();
    private volatile long lastProgressPrintMs;
    private volatile int lastProgressPct = -1;
    private volatile boolean progressLineOpen;

    public CliApp(BeamConfig config, SignalMapper signalMapper, PeerConnectionFactory rtcFactory) {
        this(config, signalMapper, rtcFactory, System.out,
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
    }

    CliApp(BeamConfig config, SignalMapper signalMapper, PeerConnectionFactory rtcFactory,
            PrintStream out, BufferedReader stdin) {
        this.config = config;
        this.signalMapper = signalMapper;
        this.rtcFactory = rtcFactory;
        this.out = out;
        this.stdin = stdin;
    }

    /**
     * Runs the send/receive flow on the console. Blocks until the transfer finishes or
     * fails. Returns a process exit code (0 = ok).
     */
    public int run(TuiArgs args) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            BeamSession s = session;
            if (s != null) {
                s.cancel();
            }
        }, "beam-cli-shutdown"));
        switch (args.action()) {
            case SEND -> {
                Path file = args.sendPath() != null ? args.sendPath() : promptForSendPath();
                if (file == null) {
                    return 1;
                }
                if (!Files.isRegularFile(file)) {
                    println("beam: not a file: " + file);
                    return 1;
                }
                println("Sending " + file.getFileName() + " (" + TextUtil.humanSize(sizeOf(file)) + ")");
                session = newSession();
                session.send(file.toAbsolutePath());
            }
            case RECEIVE -> {
                println("Waiting for a file from your phone…");
                session = newSession();
                session.receive();
            }
            default -> {
                println("Usage: java -jar beam.jar --cli (--send [path] | --receive)");
                return 1;
            }
        }
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelSession();
            return 1;
        }
        return exitCode.get();
    }

    // ---- BeamListener (all arrive on BeamSession's single event thread, in order) ----

    @Override
    public void onStateChanged(BeamState state) {
        lastState = state;
        switch (state) {
            case CONNECTING -> println("Connecting to the Beam server…");
            case WAITING_FOR_PEER -> println("Waiting for your phone to scan…");
            case NEGOTIATING -> println("Pairing… setting up encryption…");
            case CONNECTED -> println("Phone connected.");
            case TRANSFERRING -> println("Transferring…");
            default -> {
            }
        }
    }

    @Override
    public void onCodeReady(String code, String joinUrl) {
        println("");
        println("Code: " + TextUtil.spacedCode(code));
        println("Link: " + joinUrl);
        BitMatrix matrix = QrRenderer.encode(joinUrl);
        if (QrConsole.fitsWidth(matrix, consoleColumns())) {
            println(QrConsole.render(matrix));
        } else {
            println("(terminal too narrow for the QR — type the code instead)");
        }
        println("Have your phone scan the code, or open the link and enter it.");
    }

    @Override
    public void onPeerConnected() {
        println("Phone connected — getting ready…");
    }

    @Override
    public void onIncomingFile(IncomingFile offer, FileDecision decision) {
        String display = TextUtil.sanitizeDisplayName(offer.fileName(), 60);
        StringBuilder sb = new StringBuilder();
        sb.append("Incoming file: ").append(display)
                .append(" (").append(TextUtil.humanSize(offer.size())).append(")");
        if (offer.existingOffset() > 0) {
            long pct = offer.size() > 0 ? (100 * offer.existingOffset()) / offer.size() : 0;
            sb.append(" — resuming at ").append(pct).append("%");
        }
        println(sb.toString());
        if (promptAccept(display)) {
            lastProgress = null;
            decision.accept();
        } else {
            decision.reject("rejected");
        }
    }

    @Override
    public void onProgress(TransferProgress p) {
        long now = System.currentTimeMillis();
        speed.add(p.bytesDone(), now);
        lastProgress = p;
        long pct = p.bytesTotal() > 0 ? Math.min(100, (100 * p.bytesDone()) / p.bytesTotal()) : 0;
        boolean finished = p.bytesDone() >= p.bytesTotal();
        if (!finished && pct <= lastProgressPct && now - lastProgressPrintMs < PROGRESS_MIN_INTERVAL_MS) {
            return;
        }
        lastProgressPct = (int) pct;
        lastProgressPrintMs = now;
        String verb = p.direction() == TransferDirection.SEND ? "Sending" : "Receiving";
        double bps = speed.bytesPerSecond(now);
        long eta = speed.etaSeconds(p.bytesTotal(), p.bytesDone(), now);
        String line = String.format("%s %s: %d%% (%s/%s, %s/s, ETA %s)", verb,
                TextUtil.sanitizeDisplayName(p.fileName(), 40), pct,
                TextUtil.humanSize(p.bytesDone()), TextUtil.humanSize(p.bytesTotal()),
                TextUtil.formatSpeed(bps), TextUtil.formatEta(eta));
        printProgress(line);
        if (finished) {
            endProgressLine();
        }
    }

    @Override
    public void onTransferFinished(TransferResult r) {
        endProgressLine();
        String name = TextUtil.sanitizeDisplayName(r.fileName(), 60);
        if (r.ok()) {
            if (r.direction() == TransferDirection.SEND) {
                println("Done — sent and verified: " + name);
            } else {
                println("Done — saved to "
                        + (r.savedPath() != null ? r.savedPath().toAbsolutePath() : name));
            }
            finish(0);
        } else {
            String reason = r.failureReason() != null ? r.failureReason() : "transfer failed";
            println("Transfer failed: " + name + " — " + reason);
            finish(1);
        }
    }

    @Override
    public void onPaused(String reason) {
        long pct = 0;
        TransferProgress p = lastProgress;
        if (p != null && p.bytesTotal() > 0) {
            pct = Math.min(100, (100 * p.bytesDone()) / p.bytesTotal());
        }
        endProgressLine();
        println("Connection lost (" + reason + "). Transfer paused at " + pct
                + "%. Getting a new code — have your phone scan again to resume.");
    }

    @Override
    public void onError(BeamError e) {
        endProgressLine();
        println("Error: " + TuiApp.friendlyError(e).replace("\n", " "));
        // Only terminal before a transfer starts; mid-transfer errors are followed by
        // pause/resume or a transfer verdict, which finish the run themselves.
        if (lastState == BeamState.IDLE || lastState == BeamState.CONNECTING
                || lastState == BeamState.WAITING_FOR_PEER || lastState == BeamState.NEGOTIATING
                || lastState == BeamState.FAILED) {
            finish(1);
        }
    }

    // ---- helpers ----

    private BeamSession newSession() {
        return new BeamSession(config, signalMapper, rtcFactory, this);
    }

    private void cancelSession() {
        BeamSession s = session;
        session = null;
        if (s != null) {
            s.cancel();
        }
    }

    private void finish(int code) {
        if (resultShown.compareAndSet(false, true)) {
            exitCode.set(code);
            cancelSession();
            done.countDown();
        }
    }

    private Path promptForSendPath() {
        String line = prompt("File to send: ");
        if (line == null || line.isBlank()) {
            println("beam: no file given.");
            return null;
        }
        return expandUser(line.trim());
    }

    /**
     * Accept prompt with a safe default: only an explicit {@code y} accepts, matching the
     * TUI dialog (which focuses Reject). Empty input, EOF and the 120 s timeout reject.
     */
    boolean promptAccept(String displayName) {
        String answer = readLineWithTimeout(
                "Accept \"" + displayName + "\"? [y/N]: ", CONSENT_SECONDS);
        if (answer == null) {
            println("No response within 120 seconds — rejected.");
            return false;
        }
        String a = answer.trim();
        return a.equalsIgnoreCase("y") || a.equalsIgnoreCase("yes");
    }

    private String prompt(String message) {
        synchronized (printLock) {
            out.print(message);
            out.flush();
        }
        try {
            return stdin.readLine();
        } catch (IOException e) {
            log.debug("stdin read: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Reads a line on a daemon thread so the event thread never blocks past the timeout.
     * Stale reader threads (after a timeout) are daemons and never touch the decision —
     * {@link FileDecision} is first-call-wins, so a late answer cannot override the reject.
     */
    private String readLineWithTimeout(String message, long timeoutSeconds) {
        synchronized (printLock) {
            out.print(message);
            out.flush();
        }
        final String[] holder = new String[1];
        final boolean[] got = new boolean[1];
        Thread t = new Thread(() -> {
            try {
                String line = stdin.readLine();
                synchronized (holder) {
                    holder[0] = line;
                    got[0] = true;
                    holder.notifyAll();
                }
            } catch (IOException e) {
                synchronized (holder) {
                    got[0] = true;
                    holder.notifyAll();
                }
            }
        }, "beam-cli-prompt");
        t.setDaemon(true);
        t.start();
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        synchronized (holder) {
            while (!got[0]) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) {
                    return null;
                }
                try {
                    holder.wait(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return holder[0];
    }

    private void println(String s) {
        synchronized (printLock) {
            if (progressLineOpen) {
                out.println();
                progressLineOpen = false;
            }
            out.println(s);
        }
    }

    private void printProgress(String line) {
        synchronized (printLock) {
            out.print("\r" + line + "                      ");
            out.flush();
            progressLineOpen = true;
        }
    }

    private void endProgressLine() {
        synchronized (printLock) {
            if (progressLineOpen) {
                out.println();
                progressLineOpen = false;
            }
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    /** Expands a leading {@code ~}, strips drag-and-drop quotes (Windows CMD adds them). */
    static Path expandUser(String raw) {
        String s = raw;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.equals("~")) {
            return Paths.get(System.getProperty("user.home"));
        }
        if (s.startsWith("~" + java.io.File.separator) || s.startsWith("~/")) {
            return Paths.get(System.getProperty("user.home"), s.substring(2));
        }
        return Paths.get(s);
    }

    static int consoleColumns() {
        String cols = System.getenv("COLUMNS");
        if (cols != null) {
            try {
                return Integer.parseInt(cols.trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return 80;
    }
}
