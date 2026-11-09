package com.aqishi.toolbox.feature.codec.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfMergeItemTest {

    @TempDir
    Path tempDir;

    private PdfMergeItem item(int pages) throws IOException {
        File file = Files.createFile(tempDir.resolve("sample.pdf")).toFile();
        return new PdfMergeItem(file, 0L, pages, "");
    }

    @Test
    void defaultsToAllPages() throws Exception {
        PdfMergeItem item = item(5);
        assertTrue(item.isAllPages());
        assertEquals(List.of(1, 2, 3, 4, 5), item.resolvePages());
        assertEquals(5, item.getEffectivePageCount());
        assertEquals("", item.getPageRange());
    }

    @Test
    void deletePagesKeepsOriginalOrder() throws Exception {
        PdfMergeItem item = item(5);
        item.removePages(List.of(2, 4));
        assertEquals(List.of(1, 3, 5), item.resolvePages());
        assertFalse(item.isAllPages());
        assertEquals("1, 3, 5", item.getPageRange());
    }

    @Test
    void restoreAllPages() throws Exception {
        PdfMergeItem item = item(4);
        item.removePages(List.of(1, 3));
        assertEquals(2, item.getEffectivePageCount());
        item.restoreAllPages();
        assertTrue(item.isAllPages());
        assertEquals(List.of(1, 2, 3, 4), item.resolvePages());
    }

    @Test
    void duplicatePagesAreAllowed() throws Exception {
        PdfMergeItem item = item(3);
        item.setPageRange("1, 1, 3");
        assertEquals(List.of(1, 1, 3), item.resolvePages());
        assertEquals(3, item.getEffectivePageCount());
        assertFalse(item.isAllPages());
    }

    @Test
    void cannotDropLastPage() throws Exception {
        PdfMergeItem item = item(3);
        assertThrows(IllegalArgumentException.class, () -> item.removePages(List.of(1, 2, 3)));
        assertThrows(IllegalArgumentException.class, () -> item.setKeptPages(List.of()));
        assertEquals(List.of(1, 2, 3), item.resolvePages());
    }

    @Test
    void setKeptPagesValidatesRange() throws Exception {
        PdfMergeItem item = item(3);
        assertThrows(IllegalArgumentException.class, () -> item.setKeptPages(List.of(1, 9)));
        item.setKeptPages(List.of(3, 1));
        assertEquals(List.of(3, 1), item.resolvePages());
    }

    @Test
    void passwordUnlockRefreshesPageSelection() throws Exception {
        File file = Files.createFile(tempDir.resolve("encrypted.pdf")).toFile();
        PdfMergeItem item = new PdfMergeItem(file, 0L, 0, "");
        item.setEncrypted(true);
        assertTrue(item.isPasswordRequired());
        assertFalse(item.isReadable());

        item.setPassword("secret");
        item.applyLoadedPageCount(4);
        assertTrue(item.isReadable());
        assertFalse(item.isEncrypted());
        assertEquals(List.of(1, 2, 3, 4), item.resolvePages());

        item.clearPassword();
        assertNull(item.getPassword());
    }
}
