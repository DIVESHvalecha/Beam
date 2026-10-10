package com.beam.beam_desktop.core;

import static org.junit.jupiter.api.Assertions.*;

import com.beam.beam_desktop.signaling.SignalMapper;
import com.beam.beam_desktop.signaling.SignalingClient;
import com.beam.beam_desktop.signaling.WebRtcClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * State-transition tests for BeamSession. No libwebrtc involved: the session is built
 * with a null WebRtcClient and driven via its SignalingEvents/PeerEvents entry points.
 */
class BeamSessionTest {

    private RecordingListener listener;
    private BeamSession session;

    @BeforeEach
    void setUp() {
        listener = new RecordingListener();
        BeamConfig config = new BeamConfig();
        session = new BeamSession(config, new SignalMapper(), null, listener) {
            @Override
            protected WebRtcClient createRtc(SignalingClient sig) {
                return null; // no native WebRTC in unit tests
            }

            @Override
            protected SignalingClient createSignaling() {
                return new SignalingClient(new SignalMapper(), this, "wss://localhost/none") {
                    @Override
                    public void connect() {} // no real network in unit tests

                    @Override
                    public void register() {}

                    @Override
                    public void close() {}
                };
            }
        };
    }

    @AfterEach
    void tearDown() {
        session.cancel();
    }

    @Test
    void receiveRegistersAndEmitsCode() throws Exception {
        session.receive();
        waitFor(() -> listener.states.contains(BeamState.CONNECTING));
        session.onRegistered("AB12");
        waitFor(() -> listener.states.contains(BeamState.WAITING_FOR_PEER));
        waitFor(() -> listener.codes.size() == 1);
        assertEquals("AB12", listener.codes.get(0));
        assertEquals("https://usebeam.tech/?c=AB12", listener.joinUrls.get(0));
    }

    @Test
    void doneIgnoresPeerLeftAndChannelClosed() throws Exception {
        session.receive();
        session.onRegistered("AB12");
        session.onIncomingFile(new IncomingFile("0".repeat(64), "f.bin", 10, "application/octet-stream", 0),
                acceptAll());
        waitFor(() -> listener.states.contains(BeamState.TRANSFERRING));
        session.onTransferFinished(TransferResult.success(TransferDirection.RECEIVE, "f.bin", null));
        waitFor(() -> listener.states.contains(BeamState.DONE));

        int codesBefore = listener.codes.size();
        session.onPeerLeft();
        session.onChannelClosed();
        session.onSignalingClosed(1006, "gone");
        Thread.sleep(200); // let the event thread drain
        assertEquals(codesBefore, listener.codes.size(), "no new code may be emitted after DONE");
        assertEquals(BeamState.DONE, lastState());
    }

    @Test
    void linkDropWhileTransferringPausesAndEmitsNewCode() throws Exception {
        session.receive();
        session.onRegistered("OLD");
        waitFor(() -> listener.codes.size() == 1);
        session.onIncomingFile(new IncomingFile("0".repeat(64), "f.bin", 10, "application/octet-stream", 0),
                acceptAll());
        waitFor(() -> listener.states.contains(BeamState.TRANSFERRING));

        session.onChannelClosed(); // link drops mid-transfer
        waitFor(() -> listener.states.contains(BeamState.PAUSED));
        waitFor(() -> listener.pausedReasons.size() == 1);

        session.onRegistered("NEW"); // re-register succeeds
        waitFor(() -> listener.codes.size() == 2);
        assertEquals("NEW", listener.codes.get(1));
    }

    @Test
    void peerLeftWhileWaitingAcceptsFreshCodeWithoutPause() throws Exception {
        session.receive();
        session.onRegistered("OLD");
        waitFor(() -> listener.codes.size() == 1);
        session.onPeerLeft(); // nobody to transfer with yet: just re-register, no pause shown
        assertTrue(listener.pausedReasons.isEmpty());
        session.onRegistered("NEW");
        waitFor(() -> listener.codes.size() == 2);
    }

    @Test
    void signalingClosedBeforePairingFailsLoudly() throws Exception {
        session.receive();
        session.onRegistered("AB12");
        session.onSignalingClosed(1006, "boom");
        // WAITING_FOR_PEER is "before pairing" here only if never paired; after REGISTERED we
        // are still waiting for a peer, and the socket dying means no silent hang.
        waitFor(() -> listener.states.contains(BeamState.FAILED));
        assertFalse(listener.errors.isEmpty());
    }

    @Test
    void connectionFailureFailsLoudly() throws Exception {
        session.receive();
        session.onConnectionFailed(new RuntimeException("refused"));
        waitFor(() -> listener.states.contains(BeamState.FAILED));
        assertFalse(listener.errors.isEmpty());
    }

    // ---- helpers ----

    private BeamState lastState() {
        return listener.states.get(listener.states.size() - 1);
    }

    private static void waitFor(Supplier<Boolean> cond) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (!cond.get()) {
            if (System.currentTimeMillis() > deadline) {
                throw new TimeoutException("condition not met in time");
            }
            Thread.sleep(20);
        }
    }

    private static FileDecision acceptAll() {
        return new FileDecision() {
            @Override
            public void accept() {}

            @Override
            public void reject(String reason) {}
        };
    }

    private static class RecordingListener implements BeamListener {
        final List<BeamState> states = new CopyOnWriteArrayList<>();
        final List<String> codes = new CopyOnWriteArrayList<>();
        final List<String> joinUrls = new CopyOnWriteArrayList<>();
        final List<String> pausedReasons = new CopyOnWriteArrayList<>();
        final List<BeamError> errors = new CopyOnWriteArrayList<>();
        final List<TransferProgress> progress = new CopyOnWriteArrayList<>();
        final List<TransferResult> finished = new CopyOnWriteArrayList<>();
        int peerConnected;

        @Override
        public void onStateChanged(BeamState state) {
            states.add(state);
        }

        @Override
        public void onCodeReady(String code, String joinUrl) {
            codes.add(code);
            joinUrls.add(joinUrl);
        }

        @Override
        public void onPeerConnected() {
            peerConnected++;
        }

        @Override
        public void onIncomingFile(IncomingFile offer, FileDecision decision) {}

        @Override
        public void onProgress(TransferProgress p) {
            progress.add(p);
        }

        @Override
        public void onTransferFinished(TransferResult r) {
            finished.add(r);
        }

        @Override
        public void onPaused(String reason) {
            pausedReasons.add(reason);
        }

        @Override
        public void onError(BeamError e) {
            errors.add(e);
        }
    }
}
