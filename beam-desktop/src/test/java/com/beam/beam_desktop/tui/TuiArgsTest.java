package com.beam.beam_desktop.tui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TuiArgsTest {

    @Test
    void defaultsToHome() {
        assertEquals(TuiArgs.Action.HOME, TuiArgs.parse(new String[]{}).action());
        assertEquals(TuiArgs.Action.HOME, TuiArgs.parse(null).action());
    }

    @Test
    void verbs() {
        assertEquals(TuiArgs.Action.RECEIVE, TuiArgs.parse(new String[]{"receive"}).action());
        assertEquals(TuiArgs.Action.HELP, TuiArgs.parse(new String[]{"--help"}).action());
        assertEquals(TuiArgs.Action.HELP, TuiArgs.parse(new String[]{"-h"}).action());
        assertEquals(TuiArgs.Action.VERSION, TuiArgs.parse(new String[]{"--version"}).action());
        assertEquals(TuiArgs.Action.VERSION, TuiArgs.parse(new String[]{"-v"}).action());
        assertEquals(TuiArgs.Action.HELP, TuiArgs.parse(new String[]{"bogus"}).action());
    }

    @Test
    void sendWithAndWithoutPath() {
        TuiArgs withPath = TuiArgs.parse(new String[]{"send", "/tmp/a.pdf"});
        assertEquals(TuiArgs.Action.SEND, withPath.action());
        assertNotNull(withPath.sendPath());
        assertTrue(withPath.sendPath().isAbsolute());

        TuiArgs picker = TuiArgs.parse(new String[]{"send"});
        assertEquals(TuiArgs.Action.SEND, picker.action());
        assertNull(picker.sendPath());
    }

    @Test
    void dashAliases() {
        assertEquals(TuiArgs.Action.SEND, TuiArgs.parse(new String[]{"--send"}).action());
        assertEquals(TuiArgs.Action.RECEIVE, TuiArgs.parse(new String[]{"--receive"}).action());
        TuiArgs withPath = TuiArgs.parse(new String[]{"--send", "/tmp/a.pdf"});
        assertEquals(TuiArgs.Action.SEND, withPath.action());
        assertNotNull(withPath.sendPath());
    }

    @Test
    void cliFlag() {
        assertFalse(TuiArgs.parse(new String[]{"send"}).cli());
        assertTrue(TuiArgs.parse(new String[]{"--cli", "send"}).cli());
        assertTrue(TuiArgs.parse(new String[]{"send", "--cli"}).cli());
        assertTrue(TuiArgs.parse(new String[]{"--cli"}).cli());
        // --tui wins over --cli so the default stays the full-screen UI.
        assertFalse(TuiArgs.parse(new String[]{"--cli", "--tui", "send"}).cli());
    }
}
