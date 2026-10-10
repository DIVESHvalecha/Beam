package com.beam.beam_desktop.signaling;

import com.beam.beam_desktop.core.FileDecision;
import com.beam.beam_desktop.core.FileSupport;
import com.beam.beam_desktop.core.IncomingFile;
import com.beam.beam_desktop.core.PeerEvents;
import com.beam.beam_desktop.core.ProgressThrottle;
import com.beam.beam_desktop.core.TransferDirection;
import com.beam.beam_desktop.core.TransferProgress;
import com.beam.beam_desktop.core.TransferResult;
import dev.onvoid.webrtc.*;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns the RTCPeerConnection and DataChannel plus the file-transfer protocol.
 * Reports everything through {@link PeerEvents}; never prints, never blocks a
 * libwebrtc callback thread on user input or hashing.
 *
 * <p>Wire format is unchanged: FILE_META / RESUME_OFFSET / FILE_COMPLETE / FILE_RESULT,
 * 16 KB binary chunks, fileId = SHA-256 hex of {@code name|size|first 64 KB}.
 */
public class WebRtcClient {

    private static final Logger log = LoggerFactory.getLogger(WebRtcClient.class);

    static final int CHUNK_SIZE = 16 * 1024;
    private static final long BUFFER_HIGH_WATER = 1L * 1024 * 1024;
    private static final int FILE_ID_SAMPLE_BYTES = 64 * 1024;
    /** Outlives the 120 s receiver-consent prompt. */
    static final long RESUME_TIMEOUT_SECONDS = 130;
    private static final long RESULT_TIMEOUT_SECONDS = 120;
    static final long CONSENT_TIMEOUT_SECONDS = 120;
    private static final long PROGRESS_INTERVAL_MS = 150;

    private final PeerConnectionFactory factory;
    private final PeerEvents events;
    private final Path partialDir;
    private final Path downloadDir;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private volatile RTCPeerConnection peerConnection;
    private volatile RTCDataChannel dataChannel;
    private volatile String remotePeerId;

    // Sending runs on its own thread: libwebrtc delivers callbacks on its internal threads, and
    // sendFile() blocks waiting for a reply that arrives via one of those callbacks.
    private final ExecutorService transferExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "beam-transfer");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "beam-consent-timeout");
        t.setDaemon(true);
        return t;
    });

    // ---- sender-side state ----
    private record PendingSend(String fileId,
                               CompletableFuture<Long> resumeOffset,
                               CompletableFuture<FileControlMessage> result) {}

    private volatile PendingSend pendingSend;

    // ---- receiver-side state (guarded by 'this') ----
    private static class IncomingTransfer {
        final FileControlMessage meta;
        final Path part;
        final Path sidecar;
        final OutputStream out;
        long received;
        final long resumedFrom;
        final ProgressThrottle throttle = new ProgressThrottle(PROGRESS_INTERVAL_MS);

        IncomingTransfer(FileControlMessage meta, Path part, Path sidecar, OutputStream out,
                         long received, long resumedFrom) {
            this.meta = meta;
            this.part = part;
            this.sidecar = sidecar;
            this.out = out;
            this.received = received;
            this.resumedFrom = resumedFrom;
        }
    }

    /** Offer parked while waiting for the user's accept/reject (guarded by 'this'). */
    private static class PendingConsent {
        final FileControlMessage meta;
        final long offset;
        final String sanitizedName;
        ScheduledFuture<?> timeout;
        final AtomicBoolean decided = new AtomicBoolean(false);

        PendingConsent(FileControlMessage meta, long offset, String sanitizedName) {
            this.meta = meta;
            this.offset = offset;
            this.sanitizedName = sanitizedName;
        }
    }

    private PendingConsent pendingConsent;
    private IncomingTransfer incoming;

    public WebRtcClient(PeerConnectionFactory factory, PeerEvents events, Path partialDir, Path downloadDir) {
        this.factory = factory;
        this.events = events;
        this.partialDir = partialDir;
        this.downloadDir = downloadDir;
    }

    // =====================================================================
    // Connection setup
    // =====================================================================

    public void startNegotiation(List<IceServerDto> iceServers) {
        closeConnection(); // make sure nothing from a previous pairing lingers

        RTCConfiguration config = new RTCConfiguration();
        if (iceServers != null) {
            for (IceServerDto dto : iceServers) {
                RTCIceServer server = new RTCIceServer();
                server.urls.addAll(dto.getUrls());
                if (dto.getUsername() != null) {
                    server.username = dto.getUsername();
                    server.password = dto.getCredential();
                }
                config.iceServers.add(server);
            }
        }

        PeerConnectionObserver observer = new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                log.debug("Local ICE candidate");
                IceCandidateDto dto = new IceCandidateDto(
                        candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex);
                try {
                    events.onLocalIceCandidate(jsonMapper.writeValueAsString(dto));
                } catch (Exception e) {
                    log.warn("Failed to serialize ICE candidate: {}", e.getMessage());
                }
            }

            @Override
            public void onConnectionChange(RTCPeerConnectionState state) {
                log.info("PeerConnection state: {}", state);
            }

            @Override
            public void onDataChannel(RTCDataChannel channel) {
                dataChannel = channel;
                attachDataChannelHandlers(channel);
            }
        };

        peerConnection = factory.createPeerConnection(config, observer);

        RTCDataChannelInit dcInit = new RTCDataChannelInit();
        RTCDataChannel channel = peerConnection.createDataChannel("fileTransfer", dcInit);
        dataChannel = channel;
        attachDataChannelHandlers(channel);

        createOffer();
    }

    private void createOffer() {
        RTCOfferOptions options = new RTCOfferOptions();
        peerConnection.createOffer(options, new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                peerConnection.setLocalDescription(description, new SetSessionDescriptionObserver() {
                    @Override
                    public void onSuccess() {
                        log.info("Local description set, sending OFFER");
                        onLocalOfferReady(description.sdp);
                    }

                    @Override
                    public void onFailure(String error) {
                        log.warn("setLocalDescription failed: {}", error);
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                log.warn("createOffer failed: {}", error);
            }
        });
    }

    /** Hook for BeamSession to send the offer over signaling (avoids a back-reference). */
    private volatile java.util.function.Consumer<String> offerSender = sdp -> {};

    public void setOfferSender(java.util.function.Consumer<String> offerSender) {
        this.offerSender = offerSender;
    }

    private void onLocalOfferReady(String sdp) {
        offerSender.accept(sdp);
    }

    public void handleAnswer(String sdp) {
        RTCPeerConnection pc = peerConnection;
        if (pc == null) {
            log.warn("ANSWER received with no PeerConnection — ignoring");
            return;
        }
        RTCSessionDescription answer = new RTCSessionDescription(RTCSdpType.ANSWER, sdp);
        pc.setRemoteDescription(answer, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                log.info("Remote ANSWER set");
            }

            @Override
            public void onFailure(String error) {
                log.warn("setRemoteDescription (answer) failed: {}", error);
            }
        });
    }

    public void handleRemoteCandidate(String candidateJson) {
        try {
            IceCandidateDto dto = jsonMapper.readValue(candidateJson, IceCandidateDto.class);
            RTCIceCandidate candidate = new RTCIceCandidate(dto.sdpMid, dto.sdpMLineIndex, dto.candidate);
            RTCPeerConnection pc = peerConnection;
            if (pc != null) {
                pc.addIceCandidate(candidate);
            }
        } catch (Exception e) {
            log.warn("Failed to parse/add remote ICE candidate: {}", e.getMessage());
        }
    }

    /** Tears down the current connection. Partial downloads stay on disk so they can be resumed. */
    public void closeConnection() {
        boolean hadActiveTransfer;
        synchronized (this) {
            hadActiveTransfer = incoming != null || pendingConsent != null || pendingSend != null;
            cancelConsentLocked("connection closed");
            suspendIncomingLocked("connection closed");
        }
        failPendingSend("connection closed");

        RTCDataChannel ch = dataChannel;
        RTCPeerConnection pc = peerConnection;
        dataChannel = null;
        peerConnection = null;

        // closed outside any lock: close() can trigger callbacks that take the lock
        if (ch != null) {
            try { ch.close(); } catch (Exception ignored) {}
        }
        if (pc != null) {
            try { pc.close(); } catch (Exception ignored) {}
        }
        if (hadActiveTransfer) {
            events.onTransferPaused("connection closed");
        }
    }

    /** Shuts down internal executors. Call once when the session is over. */
    public void shutdown() {
        closeConnection();
        transferExecutor.shutdownNow();
        scheduler.shutdownNow();
    }

    // =====================================================================
    // Sending (resume-aware)
    // =====================================================================

    /** Runs the send on a dedicated thread. Never call sendFile() directly from a WebRTC callback. */
    public void sendFileAsync(Path file) {
        transferExecutor.submit(() -> {
            try {
                sendFile(file);
            } catch (Exception e) {
                log.info("Send interrupted ({}); re-send the same file after reconnecting to resume.",
                        e.getMessage());
                events.onTransferFinished(TransferResult.failure(
                        TransferDirection.SEND, file.getFileName().toString(),
                        e.getMessage() != null ? e.getMessage() : "send interrupted"));
            }
        });
    }

    public void sendFile(Path file) throws Exception {
        RTCDataChannel ch = dataChannel;
        if (ch == null || ch.getState() != RTCDataChannelState.OPEN) {
            throw new IllegalStateException("DataChannel is not open");
        }

        long totalSize = Files.size(file);
        String fileName = file.getFileName().toString();
        String fileId = computeFileId(file);

        PendingSend pending = new PendingSend(fileId, new CompletableFuture<>(), new CompletableFuture<>());
        pendingSend = pending;
        ProgressThrottle throttle = new ProgressThrottle(PROGRESS_INTERVAL_MS);

        try {
            FileControlMessage meta = new FileControlMessage();
            meta.setType("FILE_META");
            meta.setFileId(fileId);
            meta.setName(fileName);
            meta.setSize(totalSize);
            String mime = Files.probeContentType(file);
            meta.setMimeType(mime != null ? mime : "application/octet-stream");
            sendControl(ch, meta);

            // The receiver tells us how many bytes it already has
            long offset = await(pending.resumeOffset(), RESUME_TIMEOUT_SECONDS, "the receiver's RESUME_OFFSET");
            if (offset < 0 || offset > totalSize) {
                log.info("Ignoring invalid resume offset {}, starting from 0", offset);
                offset = 0;
            }
            if (offset > 0) {
                log.info("Resuming {} from byte {} of {}", fileName, offset, totalSize);
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long sent = offset;
            emitProgress(throttle, TransferDirection.SEND, fileName, sent, totalSize, offset, true);

            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                // The final checksum covers the whole file, so hash the part the receiver already has
                digestPrefix(raf, offset, digest);
                raf.seek(offset);

                byte[] buf = new byte[CHUNK_SIZE];
                int read;
                while ((read = raf.read(buf)) != -1) {
                    if (ch.getState() != RTCDataChannelState.OPEN) {
                        throw new IllegalStateException("DataChannel closed at byte " + sent);
                    }
                    if (pending.result().isDone()) {
                        throw new IllegalStateException("Receiver aborted the transfer at byte " + sent);
                    }

                    byte[] chunk = Arrays.copyOf(buf, read); // always copy: buf is reused on the next read
                    digest.update(chunk);

                    waitForBufferSpace(ch);
                    ch.send(new RTCDataChannelBuffer(ByteBuffer.wrap(chunk), true));

                    sent += read;
                    emitProgress(throttle, TransferDirection.SEND, fileName, sent, totalSize, offset, false);
                }
            }
            emitProgress(throttle, TransferDirection.SEND, fileName, sent, totalSize, offset, true);

            FileControlMessage complete = new FileControlMessage();
            complete.setType("FILE_COMPLETE");
            complete.setFileId(fileId);
            complete.setChecksum(bytesToHex(digest.digest()));
            sendControl(ch, complete);
            log.info("All bytes sent, waiting for the receiver to verify...");

            try {
                FileControlMessage result = await(pending.result(), RESULT_TIMEOUT_SECONDS, "the receiver's verification");
                if (Boolean.TRUE.equals(result.getOk())) {
                    log.info("Receiver verified {}", fileName);
                    events.onTransferFinished(TransferResult.success(TransferDirection.SEND, fileName, file));
                } else {
                    log.info("Receiver rejected the file: {} (the next attempt restarts from byte 0)",
                            result.getReason());
                    events.onTransferFinished(TransferResult.failure(
                            TransferDirection.SEND, fileName,
                            result.getReason() != null ? result.getReason() : "receiver rejected the file"));
                }
            } catch (IllegalStateException e) {
                log.info("No verification received: {}", e.getMessage());
                events.onTransferFinished(TransferResult.failure(
                        TransferDirection.SEND, fileName, e.getMessage()));
            }
        } finally {
            if (pendingSend == pending) {
                pendingSend = null;
            }
        }
    }

    private void emitProgress(ProgressThrottle throttle, TransferDirection dir, String fileName,
                              long done, long total, long resumedFrom, boolean force) {
        if (force || throttle.shouldEmit(done, total, System.currentTimeMillis())) {
            events.onProgress(new TransferProgress(dir, fileName, done, total, resumedFrom));
        }
    }

    private static <T> T await(CompletableFuture<T> future, long seconds, String what) throws Exception {
        try {
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Timed out waiting for " + what);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Aborted while waiting for " + what + ": "
                    + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
        }
    }

    private void failPendingSend(String reason) {
        PendingSend p = pendingSend;
        if (p == null) return;
        pendingSend = null;
        Exception ex = new IllegalStateException(reason);
        p.resumeOffset().completeExceptionally(ex);
        p.result().completeExceptionally(ex);
    }

    private void waitForBufferSpace(RTCDataChannel ch) throws InterruptedException {
        while (ch.getBufferedAmount() > BUFFER_HIGH_WATER) {
            if (ch.getState() != RTCDataChannelState.OPEN) {
                throw new IllegalStateException("DataChannel closed");
            }
            Thread.sleep(20);
        }
    }

    private void sendControl(RTCDataChannel ch, FileControlMessage msg) throws Exception {
        byte[] bytes = jsonMapper.writeValueAsString(msg).getBytes(StandardCharsets.UTF_8);
        ch.send(new RTCDataChannelBuffer(ByteBuffer.wrap(bytes), false));
    }

    static String computeFileId(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update((file.getFileName().toString() + "|" + Files.size(file) + "|").getBytes(StandardCharsets.UTF_8));
        try (InputStream in = Files.newInputStream(file)) {
            digest.update(in.readNBytes(FILE_ID_SAMPLE_BYTES));
        }
        return bytesToHex(digest.digest());
    }

    private static void digestPrefix(RandomAccessFile raf, long length, MessageDigest digest) throws IOException {
        raf.seek(0);
        byte[] buf = new byte[64 * 1024];
        long remaining = length;
        while (remaining > 0) {
            int n = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n == -1) throw new EOFException("File is shorter than expected");
            digest.update(buf, 0, n);
            remaining -= n;
        }
    }

    // =====================================================================
    // Receiving (resume-aware, streamed to disk, consent-gated)
    // =====================================================================

    private void attachDataChannelHandlers(RTCDataChannel channel) {
        channel.registerObserver(new RTCDataChannelObserver() {
            @Override
            public void onStateChange() {
                RTCDataChannelState state = channel.getState();
                log.info("DataChannel state: {}", state);

                if (state == RTCDataChannelState.OPEN) {
                    log.info("DataChannel OPEN — ready to transfer!");
                    events.onChannelOpen();
                } else if (state == RTCDataChannelState.CLOSED) {
                    boolean hadActive;
                    synchronized (WebRtcClient.this) {
                        hadActive = incoming != null || pendingSend != null;
                        cancelConsentLocked("DataChannel closed");
                        suspendIncomingLocked("DataChannel closed");
                    }
                    failPendingSend("DataChannel closed");
                    events.onChannelClosed();
                    if (hadActive) {
                        events.onTransferPaused("DataChannel closed");
                    }
                }
            }

            @Override
            public void onMessage(RTCDataChannelBuffer buffer) {
                if (buffer.binary) {
                    byte[] chunk = new byte[buffer.data.remaining()];
                    buffer.data.get(chunk);
                    handleIncomingChunk(chunk);
                    return;
                }

                byte[] textBytes = new byte[buffer.data.remaining()];
                buffer.data.get(textBytes);
                String text = new String(textBytes, StandardCharsets.UTF_8);

                try {
                    FileControlMessage msg = jsonMapper.readValue(text, FileControlMessage.class);
                    if (msg.getType() == null) return;
                    switch (msg.getType()) {
                        case "FILE_META" -> handleFileMeta(msg);
                        case "FILE_COMPLETE" -> handleFileComplete(msg);
                        case "RESUME_OFFSET" -> completeResume(msg);
                        case "FILE_RESULT" -> completeResult(msg);
                        default -> log.debug("Unknown control message: {}", msg.getType());
                    }
                } catch (Exception e) {
                    log.warn("Failed to handle control message: {}", e.getMessage());
                }
            }

            @Override
            public void onBufferedAmountChange(long previousAmount) {
                // backpressure is handled by polling in waitForBufferSpace()
            }
        });
    }

    private void completeResume(FileControlMessage msg) {
        PendingSend p = pendingSend;
        if (p != null && p.fileId().equals(msg.getFileId()) && msg.getOffset() != null) {
            p.resumeOffset().complete(msg.getOffset());
        }
    }

    private void completeResult(FileControlMessage msg) {
        PendingSend p = pendingSend;
        if (p == null || !p.fileId().equals(msg.getFileId())) return;
        p.result().complete(msg);
        if (Boolean.FALSE.equals(msg.getOk())) {
            // if this arrives before RESUME_OFFSET, the receiver refused the transfer outright
            p.resumeOffset().completeExceptionally(
                    new IllegalStateException("Receiver rejected the transfer: " + msg.getReason()));
        }
    }

    /**
     * Parks the offer and asks the user. Never blocks: RESUME_OFFSET is sent from
     * {@code accept()}, FILE_RESULT(false) from {@code reject()} or the 120 s auto-reject.
     */
    private void handleFileMeta(FileControlMessage meta) {
        PendingConsent consent;
        synchronized (this) {
            suspendIncomingLocked("new incoming file"); // a previous unfinished transfer keeps its partial on disk
            cancelConsentLocked("superseded by a new offer");

            String fileId = meta.getFileId();
            if (fileId == null || !FileSupport.isValidFileId(fileId)
                    || meta.getName() == null || meta.getSize() == null || meta.getSize() < 0) {
                log.warn("Ignoring invalid FILE_META");
                return;
            }

            long offset = readExistingOffset(meta);
            String sanitized = FileSupport.sanitizeFileName(meta.getName());
            consent = new PendingConsent(meta, offset, sanitized);
            pendingConsent = consent;
            if (offset > 0) {
                log.info("Offer {} resumes at byte {} of {}", sanitized, offset, meta.getSize());
            }
            consent.timeout = scheduler.schedule(
                    () -> autoReject(consent, "no response within 120 seconds"),
                    CONSENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        IncomingFile offer = new IncomingFile(
                consent.meta.getFileId(), consent.sanitizedName,
                consent.meta.getSize(), consent.meta.getMimeType(), consent.offset);
        FileDecision decision = new FileDecision() {
            private final AtomicBoolean used = new AtomicBoolean(false);

            @Override
            public void accept() {
                if (!used.compareAndSet(false, true)) return;
                acceptConsent(consent);
            }

            @Override
            public void reject(String reason) {
                if (!used.compareAndSet(false, true)) return;
                rejectConsent(consent, reason != null ? reason : "rejected");
            }
        };
        events.onIncomingFile(offer, decision);
    }

    private long readExistingOffset(FileControlMessage meta) {
        try {
            Path part = partialDir.resolve(meta.getFileId() + ".part");
            Path sidecar = partialDir.resolve(meta.getFileId() + ".json");
            if (Files.exists(part) && Files.exists(sidecar)) {
                FileControlMessage saved = jsonMapper.readValue(Files.readString(sidecar), FileControlMessage.class);
                long len = Files.size(part);
                boolean sameFile = meta.getName().equals(saved.getName())
                        && meta.getSize().equals(saved.getSize());
                if (sameFile && len <= meta.getSize()) {
                    return len; // the persisted file is a contiguous prefix, so its length IS the offset
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read existing partial, starting from 0: {}", e.getMessage());
        }
        return 0;
    }

    private void acceptConsent(PendingConsent consent) {
        FileControlMessage reply;
        synchronized (this) {
            if (pendingConsent != consent || !consent.decided.compareAndSet(false, true)) {
                return;
            }
            if (consent.timeout != null) {
                consent.timeout.cancel(false);
            }
            pendingConsent = null;
            try {
                Files.createDirectories(partialDir);
                Path part = partialDir.resolve(consent.meta.getFileId() + ".part");
                Path sidecar = partialDir.resolve(consent.meta.getFileId() + ".json");
                long offset = consent.offset;
                if (offset == 0) {
                    Files.deleteIfExists(part); // fresh start, or a stale partial that doesn't match
                    Files.writeString(sidecar, jsonMapper.writeValueAsString(consent.meta));
                }
                OutputStream out = Files.newOutputStream(part,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                incoming = new IncomingTransfer(consent.meta, part, sidecar, out, offset, offset);
                log.info("Receiving file: {} ({} bytes)", consent.meta.getName(), consent.meta.getSize());
            } catch (IOException e) {
                log.warn("Failed to prepare download: {}", e.getMessage());
                sendFileResult(consent.meta.getFileId(), false, "receiver failed to prepare download");
                return;
            }
            reply = new FileControlMessage();
            reply.setType("RESUME_OFFSET");
            reply.setFileId(consent.meta.getFileId());
            reply.setOffset(consent.offset);
        }
        sendReply(reply);
        emitProgress(new IncomingTransferSnapshot(consent.meta.getFileId(), consent.sanitizedName,
                consent.meta.getSize(), consent.offset, consent.offset));
    }

    private void rejectConsent(PendingConsent consent, String reason) {
        synchronized (this) {
            if (pendingConsent != consent || !consent.decided.compareAndSet(false, true)) {
                return;
            }
            if (consent.timeout != null) {
                consent.timeout.cancel(false);
            }
            pendingConsent = null;
        }
        log.info("Incoming file rejected: {}", reason);
        sendFileResult(consent.meta.getFileId(), false, reason);
    }

    private void autoReject(PendingConsent consent, String reason) {
        rejectConsent(consent, reason);
    }

    private void cancelConsentLocked(String reason) {
        PendingConsent c = pendingConsent;
        pendingConsent = null;
        if (c != null) {
            c.decided.set(true);
            if (c.timeout != null) {
                c.timeout.cancel(false);
            }
            log.debug("Dropping parked offer: {}", reason);
        }
    }

    private void handleIncomingChunk(byte[] chunk) {
        IncomingTransferSnapshot snap = null;
        synchronized (this) {
            IncomingTransfer t = incoming;
            if (t == null) return; // no active transfer (e.g. waiting for user consent)

            try {
                if (t.received + chunk.length > t.meta.getSize()) {
                    log.warn("Received more bytes than declared — aborting transfer");
                    String fileId = t.meta.getFileId();
                    suspendIncomingLocked("oversize chunk");
                    sendFileResult(fileId, false, "received more bytes than declared");
                    return;
                }
                t.out.write(chunk);
                t.received += chunk.length;
                if (t.throttle.shouldEmit(t.received, t.meta.getSize(), System.currentTimeMillis())) {
                    snap = new IncomingTransferSnapshot(t.meta.getFileId(),
                            FileSupport.sanitizeFileName(t.meta.getName()),
                            t.meta.getSize(), t.received, t.resumedFrom);
                }
            } catch (IOException e) {
                log.warn("Failed to write chunk: {}", e.getMessage());
                String fileId = t.meta.getFileId();
                suspendIncomingLocked("receiver write failed");
                sendFileResult(fileId, false, "receiver write failed");
            }
        }
        if (snap != null) {
            emitProgress(snap);
        }
    }

    private record IncomingTransferSnapshot(String fileId, String name, long total, long received, long resumedFrom) {}

    private void emitProgress(IncomingTransferSnapshot snap) {
        events.onProgress(new TransferProgress(
                TransferDirection.RECEIVE, snap.name(), snap.received(), snap.total(), snap.resumedFrom()));
    }

    /**
     * Captures the transfer state under lock, then hashes and finalizes on the transfer
     * executor — never on the libwebrtc callback thread.
     */
    private void handleFileComplete(FileControlMessage msg) {
        final IncomingTransfer t;
        synchronized (this) {
            IncomingTransfer cur = incoming;
            if (cur == null || !cur.meta.getFileId().equals(msg.getFileId())) {
                log.warn("FILE_COMPLETE for an unknown transfer — ignoring");
                return;
            }
            incoming = null;
            t = cur;
        }
        final String expectedChecksum = msg.getChecksum();
        transferExecutor.submit(() -> finalizeIncoming(t, expectedChecksum));
    }

    private void finalizeIncoming(IncomingTransfer t, String expectedChecksum) {
        String fileId = t.meta.getFileId();
        String sanitized = FileSupport.sanitizeFileName(t.meta.getName());
        try {
            t.out.close();

            long size = Files.size(t.part);
            String actual = sha256OfFile(t.part);

            String failure = null;
            if (size != t.meta.getSize()) {
                failure = "size mismatch";
            } else if (!actual.equalsIgnoreCase(expectedChecksum)) {
                failure = "checksum mismatch";
            }

            if (failure == null) {
                events.onProgress(new TransferProgress(
                        TransferDirection.RECEIVE, sanitized, size, t.meta.getSize(), t.resumedFrom));
                Path saved = moveToDownloads(t.part, sanitized);
                Files.deleteIfExists(t.sidecar);
                log.info("Checksum verified OK, saved to {}", saved.toAbsolutePath());
                sendFileResult(fileId, true, null);
                events.onTransferFinished(TransferResult.success(TransferDirection.RECEIVE, sanitized, saved));
            } else {
                log.warn("{} — discarding partial file; the sender must restart from 0", failure);
                Files.deleteIfExists(t.part);
                Files.deleteIfExists(t.sidecar);
                sendFileResult(fileId, false, failure);
                events.onTransferFinished(TransferResult.failure(TransferDirection.RECEIVE, sanitized, failure));
            }
        } catch (Exception e) {
            log.warn("Failed to finalize incoming file: {}", e.getMessage());
            sendFileResult(fileId, false, "receiver failed to finalize file");
            events.onTransferFinished(TransferResult.failure(
                    TransferDirection.RECEIVE, sanitized, "receiver failed to finalize file"));
        }
    }

    /** Closes the stream but KEEPS the partial file and sidecar so the transfer can resume later. */
    private void suspendIncomingLocked(String reason) {
        IncomingTransfer t = incoming;
        if (t == null) return;
        incoming = null;
        try {
            t.out.close();
        } catch (IOException ignored) {}
        log.info("Transfer paused at {}/{} bytes ({}) — partial file kept for resume",
                t.received, t.meta.getSize(), reason);
    }

    private void sendFileResult(String fileId, boolean ok, String reason) {
        FileControlMessage msg = new FileControlMessage();
        msg.setType("FILE_RESULT");
        msg.setFileId(fileId);
        msg.setOk(ok);
        msg.setReason(reason);
        sendReply(msg);
    }

    private void sendReply(FileControlMessage msg) {
        RTCDataChannel ch = dataChannel;
        if (ch == null || ch.getState() != RTCDataChannelState.OPEN) return;
        try {
            sendControl(ch, msg);
        } catch (Exception e) {
            log.warn("Failed to send {}: {}", msg.getType(), e.getMessage());
        }
    }

    static String sha256OfFile(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
        }
        return bytesToHex(digest.digest());
    }

    // ---- output file handling ----

    private Path moveToDownloads(Path part, String sanitizedName) throws IOException {
        Files.createDirectories(downloadDir);
        Path target = FileSupport.uniqueTarget(downloadDir, sanitizedName);
        return Files.move(part, target); // no REPLACE_EXISTING: never overwrite an existing file
    }

    static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public boolean isDataChannelOpen() {
        RTCDataChannel ch = dataChannel;
        return ch != null && ch.getState() == RTCDataChannelState.OPEN;
    }
}
