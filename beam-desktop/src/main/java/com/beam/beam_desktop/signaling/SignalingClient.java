package com.beam.beam_desktop.signaling;

import com.beam.beam_desktop.core.SignalingEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * Signaling transport only: connects, sends REGISTER/OFFER/ICE_CANDIDATE, and forwards
 * everything received to {@link SignalingEvents}. Never references WebRtcClient and never
 * decides when to re-register — that is BeamSession's job.
 */
public class SignalingClient extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SignalingClient.class);

    private final SignalMapper signalMapper;
    private final SignalingEvents events;
    private final String url;

    private volatile WebSocketSession session;

    public SignalingClient(SignalMapper signalMapper, SignalingEvents events, String url) {
        this.signalMapper = signalMapper;
        this.events = events;
        this.url = url;
    }

    /** Opens the WebSocket; on success the caller should invoke {@link #register()}. */
    public void connect() {
        StandardWebSocketClient client = new StandardWebSocketClient();
        client.execute(this, url).whenComplete((ws, throwable) -> {
            if (throwable != null) {
                log.warn("Failed to connect to signaling server {}: {}", url, throwable.getMessage());
                events.onConnectionFailed(throwable);
                return;
            }
            this.session = new ConcurrentWebSocketSessionDecorator(ws, 10_000, 64 * 1024);
            register();
        });
    }

    public void register() {
        try {
            SignalMessage msg = new SignalMessage();
            msg.setType("REGISTER");
            sendRaw(signalMapper.toJson(msg));
        } catch (Exception e) {
            log.warn("Failed to send REGISTER: {}", e.getMessage());
        }
    }

    public void sendOffer(String sdp) {
        try {
            SignalMessage msg = new SignalMessage();
            msg.setType("OFFER");
            msg.setSdp(sdp);
            sendRaw(signalMapper.toJson(msg));
        } catch (Exception e) {
            log.warn("Failed to send OFFER: {}", e.getMessage());
        }
    }

    public void sendIceCandidate(String candidateJson) {
        try {
            SignalMessage msg = new SignalMessage();
            msg.setType("ICE_CANDIDATE");
            msg.setCandidate(candidateJson);
            sendRaw(signalMapper.toJson(msg));
        } catch (Exception e) {
            log.warn("Failed to send ICE_CANDIDATE: {}", e.getMessage());
        }
    }

    private void sendRaw(String json) {
        WebSocketSession s = session;
        if (s == null || !s.isOpen()) {
            log.warn("Signaling socket not open, dropping outgoing message");
            return;
        }
        try {
            // ConcurrentWebSocketSessionDecorator makes this safe from any thread.
            s.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.warn("Failed to send signaling message: {}", e.getMessage());
        }
    }

    public void close() {
        WebSocketSession s = session;
        session = null;
        if (s != null && s.isOpen()) {
            try {
                s.close();
            } catch (Exception e) {
                log.debug("Error closing signaling socket: {}", e.getMessage());
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SignalMessage incoming;
        try {
            incoming = signalMapper.toMessage(message.getPayload());
        } catch (Exception e) {
            log.warn("Ignoring unparseable signaling message: {}", e.getMessage());
            return;
        }
        log.debug("Signaling <- {}", message.getPayload());
        if (incoming.getType() == null) {
            return;
        }
        switch (incoming.getType()) {
            case "REGISTERED" -> events.onRegistered(incoming.getSessionCode());
            case "PAIRED" -> events.onPaired(incoming.getIceServers());
            case "ANSWER" -> events.onAnswer(incoming.getSdp());
            case "ICE_CANDIDATE" -> events.onRemoteCandidate(incoming.getCandidate());
            case "ERROR" -> events.onServerError(incoming.getErrorMessage());
            case "PEER_LEFT" -> events.onPeerLeft();
            default -> log.debug("Unhandled signaling message type: {}", incoming.getType());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("Signaling socket closed: code={} reason={}", status.getCode(), status.getReason());
        events.onSignalingClosed(status.getCode(), status.getReason());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Signaling transport error: {}", exception.getMessage());
    }
}
