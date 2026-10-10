package com.beam.beam_signaling;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class SignalingHandler extends TextWebSocketHandler {

    private final SignalMapper signalMapper;
    private final TurnCredentialService turnCredentialService;

    private record WaitingSession(WebSocketSession session, Instant registeredAt) {}

    private final Map<String, WaitingSession> waitingSessions = new ConcurrentHashMap<>();
    private final Map<WebSocketSession, WebSocketSession> pairedPeers = new ConcurrentHashMap<>();

    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration EXPIRY = Duration.ofMinutes(5);

    public SignalingHandler(SignalMapper signalMapper, TurnCredentialService turnCredentialService) {
        this.signalMapper = signalMapper;
        this.turnCredentialService = turnCredentialService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        System.out.println("Connected: " + session.getId() + " from " + session.getRemoteAddress());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        SignalMessage incoming = signalMapper.toMessage(message.getPayload());
        System.out.println("Received from " + session.getId() + ": " + message.getPayload());

        switch (incoming.getType()) {
            case "REGISTER" -> handleRegister(session);
            case "JOIN" -> handleJoin(session, incoming.getSessionCode());
            case "OFFER", "ANSWER", "ICE_CANDIDATE" -> forwardToPeer(session, incoming);
            default -> sendError(session, "Unknown message type: " + incoming.getType());
        }
    }

    private void handleRegister(WebSocketSession session) throws Exception {
        String code = generateCode();
        waitingSessions.put(code, new WaitingSession(session, Instant.now()));

        SignalMessage response = new SignalMessage();
        response.setType("REGISTERED");
        response.setSessionCode(code);

        session.sendMessage(new TextMessage(signalMapper.toJson(response)));
        System.out.println("Registered session " + code + " for " + session.getId());
    }

    private void handleJoin(WebSocketSession joiningSession, String code) throws Exception {
        if (code == null) {
            sendError(joiningSession, "sessionCode is required for JOIN");
            return;
        }

        WaitingSession waiting = waitingSessions.get(code);
        if (waiting == null) {
            sendError(joiningSession, "Invalid or expired session code");
            return;
        }

        waitingSessions.remove(code);
        WebSocketSession waitingSession = waiting.session();

        pairedPeers.put(waitingSession, joiningSession);
        pairedPeers.put(joiningSession, waitingSession);

        notifyPaired(waitingSession);
        notifyPaired(joiningSession);

        System.out.println("Paired session " + code + ": "
                + waitingSession.getId() + " <-> " + joiningSession.getId());
    }

    private void notifyPaired(WebSocketSession recipient) throws Exception {
        SignalMessage paired = new SignalMessage();
        paired.setType("PAIRED");
        paired.setIceServers(turnCredentialService.generateIceServers()); // fresh per-peer credentials
        recipient.sendMessage(new TextMessage(signalMapper.toJson(paired)));
    }

    private void forwardToPeer(WebSocketSession sender, SignalMessage message) throws Exception {
        WebSocketSession peer = pairedPeers.get(sender);
        if (peer == null) {
            sendError(sender, "No paired peer to forward message to");
            return;
        }
        peer.sendMessage(new TextMessage(signalMapper.toJson(message)));
    }

    private void sendError(WebSocketSession session, String msg) throws Exception {
        SignalMessage error = new SignalMessage();
        error.setType("ERROR");
        error.setErrorMessage(msg);
        session.sendMessage(new TextMessage(signalMapper.toJson(error)));
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    @Scheduled(fixedRate = 60_000)
    public void expireStaleSessions() {
        Instant cutoff = Instant.now().minus(EXPIRY);
        int before = waitingSessions.size();
        waitingSessions.entrySet().removeIf(entry -> entry.getValue().registeredAt().isBefore(cutoff));
        int removed = before - waitingSessions.size();
        if (removed > 0) {
            System.out.println("Expired " + removed + " stale session(s)");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        System.out.println("Disconnected: " + session.getId() + " — " + status);
        waitingSessions.values().removeIf(w -> w.session().equals(session));

        WebSocketSession peer = pairedPeers.remove(session);
        if (peer != null) {
            pairedPeers.remove(peer);
            System.out.println("Unpaired " + session.getId() + " from " + peer.getId() + " (disconnect)");
            notifyPeerLeft(peer);
        }
    }

    private void notifyPeerLeft(WebSocketSession recipient) {
        if (!recipient.isOpen()) return;
        try {
            SignalMessage msg = new SignalMessage();
            msg.setType("PEER_LEFT");
            recipient.sendMessage(new TextMessage(signalMapper.toJson(msg)));
        } catch (Exception e) {
            System.err.println("Failed to send PEER_LEFT to " + recipient.getId() + ": " + e.getMessage());
        }
    }
}