package com.beam.beam_desktop.tui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CliAppTest {

    private CliApp app(String stdin) {
        return new CliApp(null, null, null,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new BufferedReader(new StringReader(stdin)));
    }

    @Test
    void onlyExplicitYesAccepts() {
        assertTrue(app("y\n").promptAccept("a.pdf"));
        assertTrue(app("YES\n").promptAccept("a.pdf"));
        assertFalse(app("\n").promptAccept("a.pdf"));
        assertFalse(app("n\n").promptAccept("a.pdf"));
        assertFalse(app("").promptAccept("a.pdf"));
    }

    @Test
    void expandUserHandlesHomeAndQuotes() {
        String home = System.getProperty("user.home");
        assertEquals(home, CliApp.expandUser("~").toString());
        assertTrue(CliApp.expandUser("~/x.pdf").toString().startsWith(home));
        assertEquals("C:\\tmp\\a.pdf", CliApp.expandUser("\"C:\\tmp\\a.pdf\"").toString());
    }
}
