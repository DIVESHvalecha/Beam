package com.beam.beam_desktop.tui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBrowserModelTest {

    @Test
    void dirsFirstNewestFirst(@TempDir Path tmp) throws Exception {
        Path sub = Files.createDirectory(tmp.resolve("aaa-dir"));
        Path oldFile = Files.write(tmp.resolve("old.txt"), "old".getBytes());
        Thread.sleep(20);
        Path newFile = Files.write(tmp.resolve("new.txt"), "new".getBytes());
        assertTrue(oldFile.toFile().setLastModified(1_000_000L));
        assertTrue(newFile.toFile().setLastModified(9_000_000L));

        List<File> got = FileBrowserModel.list(tmp.toFile(), "", FileBrowserModel.Sort.NEWEST, false);
        assertEquals(3, got.size());
        assertEquals(sub.toFile().getName(), got.get(0).getName());
        assertEquals("new.txt", got.get(1).getName());
        assertEquals("old.txt", got.get(2).getName());
    }

    @Test
    void filterIsCaseInsensitiveSubstring(@TempDir Path tmp) throws Exception {
        Files.write(tmp.resolve("Photo-2024.jpg"), "a".getBytes());
        Files.write(tmp.resolve("notes.txt"), "b".getBytes());

        List<File> got = FileBrowserModel.list(tmp.toFile(), "photo", FileBrowserModel.Sort.NAME, false);
        assertEquals(1, got.size());
        assertEquals("Photo-2024.jpg", got.get(0).getName());
    }

    @Test
    void hiddenExcludedByDefault(@TempDir Path tmp) throws Exception {
        Files.write(tmp.resolve(".hidden"), "a".getBytes());
        Files.write(tmp.resolve("visible.txt"), "b".getBytes());

        assertEquals(1, FileBrowserModel.list(tmp.toFile(), "", FileBrowserModel.Sort.NAME, false).size());
        assertEquals(2, FileBrowserModel.list(tmp.toFile(), "", FileBrowserModel.Sort.NAME, true).size());
    }

    @Test
    void sortCycles(@TempDir Path tmp) {
        assertEquals(FileBrowserModel.Sort.NAME, FileBrowserModel.Sort.NEWEST.next());
        assertEquals(FileBrowserModel.Sort.NEWEST, FileBrowserModel.Sort.SMALLEST.next());
    }
}
