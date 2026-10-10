package com.beam.beam_desktop.core;

import com.beam.beam_desktop.signaling.IceServerDto;
import com.beam.beam_desktop.signaling.SignalMapper;
import com.beam.beam_desktop.signaling.SignalingClient;
import com.beam.beam_desktop.signaling.WebRtcClient;
import dev.onvoid.webrtc.PeerConnectionFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Front-end-agnostic transfer driver. One instance per user action ({@code receive} or
 * {@code send}); create a fresh one for each action. Owns its SignalingClient and WebRtcClient
 * (plain objects); only SignalMapper and the shared PeerConnectionFactory are injected.
 *
 * <p>ALL {@link BeamListener} callbacks are delivered from a single-thread executor, in order,
 * so a UI never deals with libwebrtc or WebSocket threads.
 */
public class BeamSession implements SignalingEvents, PeerEvents {

    private static final Logger log = LoggerFactory.getLogger(BeamSession.class);

    private enum Mode {
        SEND,
        RECEIVE
    }

    private final BeamConfig config;
    private final SignalMapper signalMapper;
    private final PeerConnectionFactory rtcFactory;
    private final BeamListener listener;

    private final ExecutorService events = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "beam-events");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private volatile Mode mode;
    private volatile Path sendFile;
    private volatile BeamState state = BeamState.IDLE;
    private volatile SignalingClient signaling;
    private volatile WebRtcClient webrtc;
    private volatile String currentCode;
    private volatile boolean finished;

    public BeamSession(BeamConfig config, SignalMapper signalMapper,
                       PeerConnectionFactory rtcFactory, BeamListener listener) {
        this.config = config;
        this.signalMapper = signalMapper;
        this.rtcFactory = rtcFactory;
        this.listener = listener;
    }

    /** Connect, register, emit the code, and wait for the peer and an incoming file. */
    public void receive() {
        events.submit(() -> start(Mode.RECEIVE, null));
    }

    /** Connect, register, emit the code; when the channel opens, send {@code file}. */
    public void send(Path file) {
        events.submit(() -> start(Mode.SEND, file));
    }

    /** Tear down everything: sockets, channels, executors. Idempotent. */
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        events.submit(this::tearDown);
        events.shutdown();
    }

    // ---- startup ----

    private void start(Mode m, Path file) {
        if (cancelled.get()) {
            return;
        }
        this.mode = m;
        this.sendFile = file;
        setState(BeamState.CONNECTING);
        SignalingClient sig = createSignaling();
        this.signaling = sig;
        WebRtcClient rtc = createRtc(sig);
        this.webrtc = rtc;
        if (rtc != null) {
            rtc.setOfferSender(sig::sendOffer);
        }
        sig.connect();
    }

    /** Seam for tests: override to avoid opening a real WebSocket. */
    protected SignalingClient createSignaling() {
        return new SignalingClient(signalMapper, this, config.getSignalingUrl());
    }

    /** Seam for tests: override to avoid creating a real WebRtcClient (loads native code). */
    protected WebRtcClient createRtc(SignalingClient sig) {
        return new WebRtcClient(rtcFactory, this, config.partialDirPath(), config.downloadDirPath());
    }

    private void setState(BeamState next) {
        state = next;
        try {
            listener.onStateChanged(next);
        } catch (Exception e) {
            log.warn("BeamListener threw in onStateChanged: {}", e.getMessage());
        }
    }

    private void tearDown() {
        SignalingClient sig = signaling;
        signaling = null;
        if (sig != null) {
            sig.close();
        }
        WebRtcClient rtc = webrtc;
        webrtc = null;
        if (rtc != null) {
            rtc.shutdown();
        }
    }

    // ---- SignalingEvents (already on the event thread: SignalingClient calls us, we re-dispatch)
    // To guarantee ordering with PeerEvents, every entry point hops onto `events`. ----

    @Override
    public void onRegistered(String code) {
        events.submit(() -> {
            if (finished || cancelled.get()) return;
            currentCode = code;
            log.info("Registered with code {}", code);
            setState(BeamState.WAITING_FOR_PEER);
            safe(() -> listener.onCodeReady(code, config.joinUrl(code)));
        });
    }

    @Override
    public void onPaired(List<IceServerDto> iceServers) {
        events.submit(() -> {
            if (finished || cancelled.get()) return;
            log.info("Paired, starting negotiation");
            setState(BeamState.NEGOTIATING);
            WebRtcClient rtc = webrtc;
            if (rtc != null) {
                rtc.startNegotiation(iceServers);
            }
        });
    }

    @Override
    public void onAnswer(String sdp) {
        events.submit(() -> {
            if (finished || cancelled.get()) return;
            WebRtcClient rtc = webrtc;
            if (rtc != null) {
                rtc.handleAnswer(sdp);
            }
        });
    }

    @Override
    public void onRemoteCandidate(String candidateJson) {
        events.submit(() -> {
            if (finished || cancelled.get()) return;
            WebRtcClient rtc = webrtc;
            if (rtc != null) {
                rtc.handleRemoteCandidate(candidateJson);
            }
        });
    }

    @Override
    public void onPeerLeft() {
        events.submit(() -> {
            if (finished || cancelled.get()) return;
            WebRtcClient rtc = webrtc;
            if (rtc != null && rtc.isDataChannelOpen()) {
                // The signaling socket is no longer needed — keep the direct connection.
                log.info("Peer left signaling but the DataChannel is open — keeping the connection");
                return;
            }
            if (state == BeamState.TRANSFERRING || state == BeamState.CONNECTED) {
                pauseAndReRegister("peer left");
            } else {
                // Still waiting (or negotiating): drop stale state and wait on a fresh code.
                log.info("Peer left before transfer — registering for a new code");
                if (rtc != null) {
                    rtc.closeConnection();
                }
                SignalingClient sig = signaling;
                if (sig != null) {
                    sig.register();
                }
            }
        });
    }

    @Override
    public void onServerError(String message) {
        events.submit(() -> {
            if (finished || cancelled.get()) return;
            log.warn("Signaling server error: {}", message);
            safe(() -> listener.onError(BeamError.of("server-error",
                    message != null ? message : "signaling server error")));
            if (state == BeamState.CONNECTING || state == BeamState.WAITING_FOR_PEER) {
                finished = true;
                setState(BeamState.FAILED);
            }
        });
    }

    @Override
    public void onSignalingClosed(int code, String reason) {
        events.submit(() -> {
            if (finished || cancelled.get()) return;
            WebRtcClient rtc = webrtc;
            if (rtc != null && rtc.isDataChannelOpen()) {
                log.info("Signaling closed but DataChannel is open — keeping the connection");
                return;
            }
            if (state == BeamState.CONNECTING || state == BeamState.WAITING_FOR_PEER
                    || state == BeamState.NEGOTIATING) {
                log.warn("Signaling closed before pairing (code={}) — failing", code);
                safe(() -> listener.onError(BeamError.of("signaling-closed",
                        "signaling connection closed before pairing (code " + code + ")")));
                finished = true;
                setState(BeamState.FAILED);
            } else if (state == BeamState.TRANSFERRING || state == BeamState.CONNECTED) {
                pauseAndReconnect("signaling closed");
            }
            // PAUSED: a reconnect is already in flight; ignore.
        });
    }

    @Override
    public void onConnectionFailed(Throwable cause) {
        events.submit(() -> {
            if (finished || cancelled.get()) return;
            WebRtcClient rtc = webrtc;
            if (rtc != null && rtc.isDataChannelOpen()) {
                log.info("Signaling connect failed but DataChannel is open — keeping the connection");
                return;
            }
            log.warn("Signaling connection failed: {}", cause.getMessage());
            safe(() -> listener.onError(BeamError.of("signaling-unreachable",
                    "cannot reach the signaling server: "
                            + (cause.getMessage() != null ? cause.getMessage() : cause.toString()),
                    cause)));
            finished = true;
            setState(BeamState.FAILED);
        });
    }

    // ---- PeerEvents (arrive on libwebrtc threads; hop onto the event thread) ----

    @Override
    public void onLocalIceCandidate(String candidateJson) {
        events.submit(() -> {
            SignalingClient sig = signaling;
            if (sig != null && !finished && !cancelled.get()) {
                sig.sendIceCandidate(candidateJson);
            }
        });
    }

    @Override
    public void onChannelOpen() {
        events.submit(() -> {
            if (finished || cancelled.get()) return;
            log.info("DataChannel open");
            setState(BeamState.CONNECTED);
            safe(listener::onPeerConnected);
            if (mode == Mode.SEND && sendFile != null) {
                Path file = sendFile;
                if (!Files.isRegularFile(file)) {
                    finished = true;
                    setState(BeamState.FAILED);
                    safe(() -> listener.onTransferFinished(TransferResult.failure(
                            TransferDirection.SEND, file.toString(), "file not found: " + file)));
                    return;
                }
                setState(BeamState.TRANSFERRING);
                WebRtcClient rtc = webrtc;
                if (rtc != null) {
                    rtc.sendFileAsync(file);
                }
            }
        });
    }

    @Override
    public void onChannelClosed() {
        events.submit(() -> {
            if (finished || cancelled.get()) return;
            if (state == BeamState.TRANSFERRING) {
                pauseAndReRegister("connection lost");
            }
            // Otherwise WebRtcClient already cleaned up; nothing to do (and never re-register
            // after DONE — see onTransferFinished).
        });
    }

    @Override
    public void onIncomingFile(IncomingFile offer, FileDecision decision) {
        FileDecision[] holder = new FileDecision[1];
        events.submit(() -> {
            if (finished || cancelled.get()) {
                decision.reject("session ended");
                return;
            }
            setState(BeamState.TRANSFERRING);
            holder[0] = decision;
            safe(() -> listener.onIncomingFile(offer, decision));
        });
    }

    @Override
    public void onProgress(TransferProgress progress) {
        events.submit(() -> {
            if (finished || cancelled.get()) return;
            safe(() -> listener.onProgress(progress));
        });
    }

    @Override
    public void onTransferFinished(TransferResult result) {
        events.submit(() -> {
            if (finished || cancelled.get()) return;
            if (!result.ok() && state == BeamState.PAUSED) {
                // The send loop died because the link dropped; the resume flow (new code +
                // auto re-send) is already in motion — don't surface this as a terminal failure.
                log.info("Ignoring send-loop failure while paused (resume in motion)");
                return;
            }
            finished = true;
            if (result.ok()) {
                setState(BeamState.DONE);
            } else {
                setState(BeamState.FAILED);
            }
            safe(() -> listener.onTransferFinished(result));
            // Release the pairing without tripping the resume logic: mark finished first so
            // PEER_LEFT / channel-closed / signaling-closed arriving hereafter are ignored.
            SignalingClient sig = signaling;
            signaling = null;
            if (sig != null) {
                sig.close();
            }
        });
    }

    @Override
    public void onTransferPaused(String reason) {
        events.submit(() -> {
            if (finished || cancelled.get()) return;
            if (state == BeamState.TRANSFERRING) {
                pauseAndReRegister(reason);
            }
        });
    }

    // ---- pause / resume ----

    private void pauseAndReRegister(String reason) {
        if (finished || state == BeamState.PAUSED || state == BeamState.DONE) {
            return;
        }
        log.info("Transfer paused ({}) — registering for a new code", reason);
        setState(BeamState.PAUSED);
        safe(() -> listener.onPaused(reason));
        SignalingClient sig = signaling;
        if (sig != null) {
            sig.register();
        }
    }

    private void pauseAndReconnect(String reason) {
        if (finished || state == BeamState.PAUSED || state == BeamState.DONE) {
            return;
        }
        log.info("Transfer paused ({}) — reconnecting signaling", reason);
        setState(BeamState.PAUSED);
        safe(() -> listener.onPaused(reason));
        SignalingClient sig = signaling;
        if (sig != null) {
            sig.connect();
        }
    }

    private void safe(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.warn("BeamListener threw: {}", e.getMessage());
        }
    }

    // ---- test hooks ----

    BeamState currentState() {
        return state;
    }
}
