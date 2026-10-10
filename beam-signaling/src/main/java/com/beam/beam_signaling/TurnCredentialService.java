package com.beam.beam_signaling;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class TurnCredentialService {

    private final TurnProperties properties;
    private final Clock clock;

    @Autowired
    public TurnCredentialService(TurnProperties properties) {
        this(properties, Clock.systemUTC());
    }

    // package-private — lets the test inject a fixed Clock for deterministic output
    TurnCredentialService(TurnProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @PostConstruct
    void validateSecret() {
        if (properties.getSecret() == null || properties.getSecret().isBlank()) {
            throw new IllegalStateException(
                    "beam.turn.secret is missing or blank. Set the TURN_SECRET environment " +
                            "variable before starting beam-signaling, e.g.: TURN_SECRET=... ./mvnw spring-boot:run"
            );
        }
    }

    public List<IceServerDto> generateIceServers() {
        long expiry = clock.instant().getEpochSecond() + properties.getTtlSeconds();
        String username = String.valueOf(expiry);
        String credential = computeHmacSha1Base64(properties.getSecret(), username);

        String host = properties.getHost();
        int port = properties.getPort();

        List<IceServerDto> servers = new ArrayList<>();

        servers.add(new IceServerDto(
                List.of("stun:stun.l.google.com:19302"), null, null));

        servers.add(new IceServerDto(
                List.of("stun:" + host + ":" + port), null, null));

        servers.add(new IceServerDto(
                List.of(
                        "turn:" + host + ":" + port + "?transport=udp",
                        "turn:" + host + ":" + port + "?transport=tcp"
                ),
                username,
                credential
        ));

        return servers;
    }

    static String computeHmacSha1Base64(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] rawHmac = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(rawHmac);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute TURN credential HMAC", e);
        }
    }
}