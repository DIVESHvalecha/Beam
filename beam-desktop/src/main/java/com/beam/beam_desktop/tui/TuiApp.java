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
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowListenerAdapter;
import com.googlecode.lanterna.gui2.dialogs.FileDialogBuilder;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import dev.onvoid.webrtc.PeerConnectionFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Full-screen terminal UI. Owns the Lanterna screen, the current BeamSession, and all
 * navigation. Implements BeamListener; every callback is marshalled onto the Lanterna
 * GUI thread. One fresh BeamSession per action; the old one is cancelled on every
 * screen change.
 */
public class TuiApp implements BeamListener {

    private static final Logger log = LoggerFactory.getLogger(TuiApp.class);
    private static final long CONSENT_SECONDS = 120;

    private final BeamConfig config;
    private final SignalMapper signalMapper;
    private final PeerConnectionFactory rtcFactory;

    private Screen screen;
    private Terminal terminal;
    private MultiWindowTextGUI gui;
    private BasicWindow mainWindow;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean screenStopped = new AtomicBoolean(false);
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "beam-tui-timers");
        t.setDaemon(true);
        return t;
    });

    private enum Flow { NONE, SEND, RECEIVE }

    private enum Main { HOME, PAIRING, PROGRESS, RESULT, ERROR }

    private Flow flow = Flow.NONE;
    private Main current = Main.HOME;
    private BeamSession session;
    private BeamState lastState = BeamState.IDLE;

    private PairingPanel pairingPanel;
    private ProgressPanel progressPanel;
    private final SpeedTracker speed = new SpeedTracker();
    private TransferProgress lastProgress;

    private IncomingFile pendingOffer;
    private FileDecision pendingDecision;
    private AcceptDialog acceptDialog;
    private ScheduledFuture<?> countdownTask;

    private String pausedNote;

    public TuiApp(BeamConfig config, SignalMapper signalMapper, PeerConnectionFactory rtcFactory) {
        this.config = config;
        this.signalMapper = signalMapper;
        this.rtcFactory = rtcFactory;
    }

    /** Opens the TUI and blocks until it exits. Nothing is written to stdout/stderr in here. */
    public void run(TuiArgs args) throws IOException {
        // Keep the Terminal (Screen hides it): it is needed for resize notifications.
        terminal = new DefaultTerminalFactory().createTerminal();
        screen = new TerminalScreen(terminal);
        screen.startScreen();
        gui = new MultiWindowTextGUI(screen);
        mainWindow = new BasicWindow("Beam");
        mainWindow.addWindowListener(new WindowListenerAdapter() {
            @Override
            public void onInput(Window basePane, KeyStroke keyStroke, AtomicBoolean deliver) {
                if (keyStroke.getKeyType() == KeyType.Escape) {
                    deliver.set(false);
                    onEscape();
                } else if (keyStroke.getKeyType() == KeyType.Character
                        && keyStroke.getCharacter() != null) {
                    char c = keyStroke.getCharacter();
                    if (c == '\u0003') { // Ctrl+C arrives as a key when Lanterna owns the terminal
                        deliver.set(false);
                        quit();
                    } else if (current == Main.HOME) {
                        if (c == 's' || c == 'S') {
                            startSendFlow();
                        } else if (c == 'r' || c == 'R') {
                            startReceiveFlow();
                        } else if (c == 'q' || c == 'Q') {
                            quit();
                        }
                    }
                }
            }
        });
        running.set(true);
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "beam-tui-shutdown"));

        switch (args.action()) {
            case SEND -> {
                if (args.sendPath() != null) {
                    beginSend(args.sendPath());
                } else {
                    showHome();
                    startSendFlow();
                }
            }
            case RECEIVE -> startReceiveFlow();
            default -> showHome();
        }
        gui.addWindowAndWait(mainWindow);
        shutdown();
    }

    // ---- navigation (all on the GUI thread) ----

    private void showHome() {
        cancelSession();
        flow = Flow.NONE;
        closeDialog();
        setMain(new HomePanel(this::startSendFlow, this::startReceiveFlow, this::quit), Main.HOME);
    }

    private void startSendFlow() {
        File chosen = pickFile();
        if (chosen == null) {
            showHome();
            return;
        }
        if (!chosen.isFile()) {
            flow = Flow.SEND;
            showError("Not a file:\n" + chosen.getAbsolutePath());
            return;
        }
        beginSend(chosen.toPath().toAbsolutePath());
    }

    private void beginSend(Path file) {
        cancelSession();
        flow = Flow.SEND;
        closeDialog();
        lastProgress = null;
        pairingPanel = new PairingPanel(gui, terminal);
        pairingPanel.setStatus("Connecting…");
        setMain(pairingPanel, Main.PAIRING);
        session = newSession();
        session.send(file);
    }

    private void startReceiveFlow() {
        cancelSession();
        flow = Flow.RECEIVE;
        closeDialog();
        lastProgress = null;
        pairingPanel = new PairingPanel(gui, terminal);
        pairingPanel.setStatus("Connecting…");
        setMain(pairingPanel, Main.PAIRING);
        session = newSession();
        session.receive();
    }

    private File pickFile() {
        File downloads = new File(System.getProperty("user.home"), "Downloads");
        File start = downloads.isDirectory() ? downloads : new File(System.getProperty("user.home"));
        return new FileDialogBuilder()
                .setTitle("Choose a file to send")
                .setDescription("Pick a file, then have your phone scan the code.")
                .setActionLabel("Send")
                .setSelectedFile(start)
                .build()
                .showDialog(gui);
    }

    private void setMain(Panel panel, Main next) {
        current = next;
        mainWindow.setComponent(panel);
    }

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

    private void onEscape() {
        if (current == Main.HOME) {
            quit();
            return;
        }
        showHome();
    }

    private void quit() {
        shutdown();
        try {
            mainWindow.close();
        } catch (Exception ignored) {
            // already closed
        }
    }

    /** Idempotent: safe from the GUI thread, the shutdown hook, and after-window-close. */
    private void shutdown() {
        running.set(false);
        cancelSession();
        closeDialog();
        timers.shutdownNow();
        try {
            if (mainWindow != null && mainWindow.isVisible()) {
                mainWindow.close(); // unblock addWindowAndWait so the runner can return
            }
        } catch (Exception e) {
            log.debug("window close: {}", e.getMessage());
        }
        if (screenStopped.compareAndSet(false, true)) {
            try {
                if (screen != null) {
                    screen.stopScreen();
                }
            } catch (Exception e) {
                log.debug("stopScreen: {}", e.getMessage());
            }
        }
    }

    private void onGui(Runnable r) {
        if (!running.get()) {
            return;
        }
        try {
            gui.getGUIThread().invokeLater(() -> {
                if (running.get()) {
                    r.run();
                }
            });
        } catch (Exception e) {
            log.debug("invokeLater dropped: {}", e.getMessage());
        }
    }

    // ---- BeamListener (arrive on BeamSession's event thread; hop onto the GUI thread) ----

    @Override
    public void onStateChanged(BeamState state) {
        lastState = state;
        onGui(() -> {
            if (current != Main.PAIRING || pairingPanel == null) {
                return;
            }
            switch (state) {
                case CONNECTING -> pairingPanel.setStatus("Connecting…");
                case WAITING_FOR_PEER -> pairingPanel.setStatus("Waiting for your phone to scan…");
                case NEGOTIATING -> pairingPanel.setStatus("Pairing… setting up encryption…");
                case CONNECTED -> pairingPanel.setStatus("Connected.");
                default -> {
                }
            }
        });
    }

    @Override
    public void onCodeReady(String code, String joinUrl) {
        onGui(() -> {
            if (pairingPanel != null && (current == Main.PAIRING)) {
                pairingPanel.setCode(code, joinUrl);
                if (pausedNote != null) {
                    pairingPanel.setStatus(pausedNote + "  Scan the NEW code to continue.");
                } else if (lastState == BeamState.WAITING_FOR_PEER) {
                    pairingPanel.setStatus("Waiting for your phone to scan…");
                }
            }
        });
    }

    @Override
    public void onPeerConnected() {
        onGui(() -> {
            if (current == Main.PAIRING && pairingPanel != null) {
                pairingPanel.setStatus("Phone connected — getting ready…");
            }
        });
    }

    @Override
    public void onIncomingFile(IncomingFile offer, FileDecision decision) {
        onGui(() -> {
            closeDialog();
            if (flow != Flow.RECEIVE) {
                decision.reject("not receiving");
                return;
            }
            pendingOffer = offer;
            pendingDecision = decision;
            String display = TextUtil.sanitizeDisplayName(offer.fileName(), 48);
            Long resumePct = null;
            if (offer.existingOffset() > 0 && offer.size() > 0) {
                resumePct = (100 * offer.existingOffset()) / offer.size();
            }
            acceptDialog = new AcceptDialog(display, TextUtil.humanSize(offer.size()), resumePct,
                    this::acceptOffer, () -> rejectOffer("rejected"));
            gui.addWindow(acceptDialog);
            acceptDialog.focusReject();
            acceptDialog.setCountdown(CONSENT_SECONDS);
            startCountdown();
        });
    }

    private void startCountdown() {
        cancelCountdown();
        long[] left = {CONSENT_SECONDS};
        countdownTask = timers.scheduleAtFixedRate(() -> onGui(() -> {
            if (acceptDialog == null) {
                cancelCountdown();
                return;
            }
            left[0]--;
            if (left[0] <= 0) {
                rejectOffer("no response within 120 seconds");
            } else {
                acceptDialog.setCountdown(left[0]);
            }
        }), 1, 1, TimeUnit.SECONDS);
    }

    private void cancelCountdown() {
        ScheduledFuture<?> t = countdownTask;
        countdownTask = null;
        if (t != null) {
            t.cancel(false);
        }
    }

    private void acceptOffer() {
        FileDecision d = pendingDecision;
        IncomingFile offer = pendingOffer;
        closeDialog();
        if (d == null || offer == null) {
            return;
        }
        pendingDecision = null;
        progressPanel = new ProgressPanel();
        progressPanel.showFile("Receiving",
                TextUtil.sanitizeDisplayName(offer.fileName(), 48), offer.size(), offer.existingOffset());
        setMain(progressPanel, Main.PROGRESS);
        d.accept();
    }

    private void rejectOffer(String reason) {
        FileDecision d = pendingDecision;
        pendingDecision = null;
        pendingOffer = null;
        closeDialog();
        if (d != null) {
            d.reject(reason);
        }
        if (flow == Flow.RECEIVE && current != Main.RESULT && current != Main.ERROR) {
            if (pairingPanel == null) {
                pairingPanel = new PairingPanel(gui, terminal);
            }
            pairingPanel.setStatus("Rejected. Waiting for another file…");
            setMain(pairingPanel, Main.PAIRING);
        }
    }

    private void closeDialog() {
        cancelCountdown();
        pendingDecision = null;
        if (acceptDialog != null) {
            try {
                acceptDialog.close();
            } catch (Exception ignored) {
                // already closed
            }
            acceptDialog = null;
        }
    }

    @Override
    public void onProgress(TransferProgress p) {
        long now = System.currentTimeMillis();
        speed.add(p.bytesDone(), now);
        onGui(() -> {
            lastProgress = p;
            if (current != Main.PROGRESS || progressPanel == null) {
                progressPanel = new ProgressPanel();
                String verb = flow == Flow.SEND ? "Sending" : "Receiving";
                progressPanel.showFile(verb, TextUtil.sanitizeDisplayName(p.fileName(), 48),
                        p.bytesTotal(), p.resumedFromBytes());
                setMain(progressPanel, Main.PROGRESS);
            }
            double bps = speed.bytesPerSecond(System.currentTimeMillis());
            long eta = speed.etaSeconds(p.bytesTotal(), p.bytesDone(), System.currentTimeMillis());
            progressPanel.update(p.bytesDone(), p.bytesTotal(), bps, eta);
        });
    }

    @Override
    public void onTransferFinished(TransferResult r) {
        onGui(() -> {
            closeDialog();
            String name = TextUtil.sanitizeDisplayName(r.fileName(), 60);
            if (r.ok()) {
                String detail = r.direction() == TransferDirection.SEND
                        ? "Sent and verified: " + name
                        : "Saved to " + (r.savedPath() != null ? r.savedPath().toAbsolutePath() : name);
                if (flow == Flow.SEND) {
                    setMain(new ResultPanel("Done", detail, List.of(
                            new ResultPanel.Action("Send another", this::startSendFlow),
                            new ResultPanel.Action("Home", this::showHome),
                            new ResultPanel.Action("Quit", this::quit))), Main.RESULT);
                } else {
                    setMain(new ResultPanel("Done", detail, List.of(
                            new ResultPanel.Action("Receive another", this::startReceiveFlow),
                            new ResultPanel.Action("Home", this::showHome),
                            new ResultPanel.Action("Quit", this::quit))), Main.RESULT);
                }
            } else {
                String reason = r.failureReason() != null ? r.failureReason() : "transfer failed";
                ResultPanel.Action again = flow == Flow.SEND
                        ? new ResultPanel.Action("Send another", this::startSendFlow)
                        : new ResultPanel.Action("Receive another", this::startReceiveFlow);
                setMain(new ResultPanel("Transfer failed", name + "\n" + reason, List.of(
                        again,
                        new ResultPanel.Action("Home", this::showHome),
                        new ResultPanel.Action("Quit", this::quit))), Main.RESULT);
            }
        });
    }

    @Override
    public void onPaused(String reason) {
        onGui(() -> {
            long pct = 0;
            TransferProgress p = lastProgress;
            if (p != null && p.bytesTotal() > 0) {
                pct = Math.min(100, (100 * p.bytesDone()) / p.bytesTotal());
            }
            pausedNote = "Connection lost. Transfer paused at " + pct + "%.";
            pairingPanel = new PairingPanel(gui, terminal);
            pairingPanel.setStatus(pausedNote + "  Getting a new code…");
            Panel wrap = new Panel();
            wrap.addComponent(new EmptySpace());
            wrap.addComponent(new Label("  " + pausedNote));
            if (flow == Flow.SEND) {
                wrap.addComponent(new Label("  Sending resumes automatically once your phone rejoins."));
            }
            wrap.addComponent(pairingPanel);
            setMain(wrap, Main.PAIRING);
        });
    }

    @Override
    public void onError(BeamError e) {
        onGui(() -> {
            closeDialog();
            showError(friendlyError(e));
            pausedNote = null;
        });
    }

    private void showError(String message) {
        Runnable retry = flow == Flow.SEND ? this::startSendFlow : this::startReceiveFlow;
        if (flow == Flow.NONE) {
            retry = this::showHome;
        }
        setMain(new ErrorPanel(message, retry, this::showHome, this::quit), Main.ERROR);
    }

    static String friendlyError(BeamError e) {
        String msg = e.message() != null ? e.message() : "unknown error";
        return switch (e.code()) {
            case "signaling-unreachable" ->
                "Cannot reach the Beam signaling server\n(check your internet connection)";
            case "signaling-closed" -> "Lost the connection to the Beam server\n(" + msg + ")";
            case "server-error" -> "Signaling server error:\n" + msg;
            default -> msg;
        };
    }
}
