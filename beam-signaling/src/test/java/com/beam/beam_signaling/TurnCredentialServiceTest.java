package com.beam.beam_signaling;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TurnCredentialServiceTest {

    private static final String SECRET = "test-secret";
    private static final String HOST = "3.111.131.68";
    private static final int PORT = 3478;
    private static final long TTL = 3600;

    private TurnProperties props() {
        TurnProperties p = new TurnProperties();
        p.setHost(HOST);
        p.setPort(PORT);
        p.setTtlSeconds(TTL);
        p.setSecret(SECRET);
        return p;
    }

    @Test
    void generatesExpectedHmacForKnownTimestamp() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);

        TurnCredentialService service = new TurnCredentialService(props(), fixedClock);
        List<IceServerDto> servers = service.generateIceServers();

        IceServerDto turnServer = servers.stream()
                .filter(s -> s.getUsername() != null)
                .findFirst()
                .orElseThrow();

        long expectedExpiry = now.getEpochSecond() + TTL; // 1700003600
        assertEquals(String.valueOf(expectedExpiry), turnServer.getUsername());

        // Cross-checked via:
        //   echo -n "1700003600" | openssl dgst -sha1 -hmac "test-secret" -binary | base64
        assertEquals("gVY/1zMgKISiRHdrzD1pbFO2RSc=", turnServer.getCredential());
    }

    @Test
    void differentPairingTimesProduceDifferentCredentials() {
        Instant t1 = Instant.ofEpochSecond(1_700_000_000L);
        Instant t2 = Instant.ofEpochSecond(1_700_000_001L); // 1 second later

        TurnCredentialService serviceA = new TurnCredentialService(props(), Clock.fixed(t1, ZoneOffset.UTC));
        TurnCredentialService serviceB = new TurnCredentialService(props(), Clock.fixed(t2, ZoneOffset.UTC));

        IceServerDto turnA = serviceA.generateIceServers().stream()
                .filter(s -> s.getUsername() != null).findFirst().orElseThrow();
        IceServerDto turnB = serviceB.generateIceServers().stream()
                .filter(s -> s.getUsername() != null).findFirst().orElseThrow();

        assertNotEquals(turnA.getUsername(), turnB.getUsername());
        assertNotEquals(turnA.getCredential(), turnB.getCredential());
    }

    @Test
    void iceServersIncludeStunAndBothTurnTransports() {
        TurnCredentialService service = new TurnCredentialService(props(), Clock.systemUTC());
        List<IceServerDto> servers = service.generateIceServers();

        assertTrue(servers.stream().anyMatch(s -> s.getUrls().contains("stun:stun.l.google.com:19302")));
        assertTrue(servers.stream().anyMatch(s -> s.getUrls().contains("stun:" + HOST + ":" + PORT)));

        IceServerDto turn = servers.stream().filter(s -> s.getUsername() != null).findFirst().orElseThrow();
        assertTrue(turn.getUrls().contains("turn:" + HOST + ":" + PORT + "?transport=udp"));
        assertTrue(turn.getUrls().contains("turn:" + HOST + ":" + PORT + "?transport=tcp"));
    }
}