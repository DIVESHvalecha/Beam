package com.beam.beam_desktop.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSupportTest {

    @Test
    void validFileIds() {
        assertTrue(FileSupport.isValidFileId("0".repeat(64)));
        assertTrue(FileSupport.isValidFileId("abcdef0123456789".repeat(4)));
        assertFalse(FileSupport.isValidFileId(null));
        assertFalse(FileSupport.isValidFileId(""));
        assertFalse(FileSupport.isValidFileId("xyz"));
        assertFalse(FileSupport.isValidFileId("A".repeat(64))); // uppercase rejected
        assertFalse(FileSupport.isValidFileId("0".repeat(63)));
        assertFalse(FileSupport.isValidFileId("0".repeat(65)));
        assertFalse(FileSupport.isValidFileId("../../etc/passwd"));
    }

    @Test
    void sanitizeKeepsLastComponentOnly() {
        assertEquals("photo.jpg", FileSupport.sanitizeFileName("photo.jpg"));
        assertEquals("photo.jpg", FileSupport.sanitizeFileName("/a/b/photo.jpg"));
        assertEquals("photo.jpg", FileSupport.sanitizeFileName("../../photo.jpg"));
        assertEquals("beam-received-file", FileSupport.sanitizeFileName(null));
        assertEquals("beam-received-file", FileSupport.sanitizeFileName(""));
        assertEquals("beam-received-file", FileSupport.sanitizeFileName("   "));
        assertEquals("beam-received-file", FileSupport.sanitizeFileName(".."));
    }

    @Test
    void uniqueTargetNeverOverwrites(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "x");
        assertEquals(dir.resolve("a (1).txt"), FileSupport.uniqueTarget(dir, "a.txt"));
        Files.writeString(dir.resolve("a (1).txt"), "x");
        assertEquals(dir.resolve("a (2).txt"), FileSupport.uniqueTarget(dir, "a.txt"));
        // name without extension
        assertEquals(dir.resolve("README"), FileSupport.uniqueTarget(dir, "README"));
        Files.writeString(dir.resolve("README"), "x");
        assertEquals(dir.resolve("README (1)"), FileSupport.uniqueTarget(dir, "README"));
    }
}
