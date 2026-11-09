package com.aqishi.toolbox.feature.codec.domain;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfMergerTest {

    @TempDir
    Path tempDir;

    private File createTestPdf(String name, int pageCount) throws IOException {
        File file = tempDir.resolve(name).toFile();
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= pageCount; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 14);
                    cs.newLineAtOffset(100, 700);
                    cs.showText("File: " + name + " Page " + i);
                    cs.endText();
                }
            }
            doc.save(file);
        }
        return file;
    }

    @Test
    void readPageCount() throws IOException {
        File file = createTestPdf("sample.pdf", 3);
        assertEquals(3, PdfMerger.readPageCount(file));
    }

    @Test
    void mergeFullDocuments() throws Exception {
        File f1 = createTestPdf("doc1.pdf", 2);
        File f2 = createTestPdf("doc2.pdf", 3);
        File f3 = createTestPdf("doc3.pdf", 1);

        List<PdfMergeItem> items = List.of(
                new PdfMergeItem(f1, f1.length(), 2, ""),
                new PdfMergeItem(f2, f2.length(), 3, "全部"),
                new PdfMergeItem(f3, f3.length(), 1, null)
        );

        File output = tempDir.resolve("merged_full.pdf").toFile();
        List<String> progressFiles = new ArrayList<>();
        List<Integer> progressPages = new ArrayList<>();

        PdfMerger.merge(items, output, (cur, total, name) -> {
            assertEquals(6, total);
            progressPages.add(cur);
            progressFiles.add(name);
        }, null);

        assertTrue(output.exists());
        // 进度按页而非按文件：2 + 3 + 1 = 6 次回调
        assertEquals(List.of(1, 2, 3, 4, 5, 6), progressPages);
        assertEquals("doc1.pdf", progressFiles.get(0));
        assertEquals("doc2.pdf", progressFiles.get(2));
        assertEquals("doc3.pdf", progressFiles.get(5));

        // 验证总页数: 2 + 3 + 1 = 6
        assertEquals(6, PdfMerger.readPageCount(output));
    }

    @Test
    void mergeCustomPages() throws Exception {
        File f1 = createTestPdf("doc1.pdf", 5);
        File f2 = createTestPdf("doc2.pdf", 4);

        // f1 提取第 1, 3 页 (2页)，f2 提取第 2-4 页 (3页) -> 总共 5 页
        List<PdfMergeItem> items = List.of(
                new PdfMergeItem(f1, f1.length(), 5, "1, 3"),
                new PdfMergeItem(f2, f2.length(), 4, "2-4")
        );

        File output = tempDir.resolve("merged_custom.pdf").toFile();
        PdfMerger.merge(items, output, null, null);

        assertTrue(output.exists());
        assertEquals(5, PdfMerger.readPageCount(output));
    }

    @Test
    void mergeCancelledCleansUpFile() throws Exception {
        File f1 = createTestPdf("doc1.pdf", 2);
        File f2 = createTestPdf("doc2.pdf", 2);

        List<PdfMergeItem> items = List.of(
                new PdfMergeItem(f1, f1.length(), 2, ""),
                new PdfMergeItem(f2, f2.length(), 2, "")
        );

        File output = tempDir.resolve("cancelled.pdf").toFile();
        assertThrows(InterruptedException.class, () ->
                PdfMerger.merge(items, output, null, () -> true)
        );

        assertFalse(output.exists());
    }

    @Test
    void testItemSelectionState() throws IOException {
        File f1 = createTestPdf("select1.pdf", 2);
        PdfMergeItem item = new PdfMergeItem(f1, f1.length(), 2, "全部");
        assertTrue(item.isSelected());

        item.setSelected(false);
        assertFalse(item.isSelected());

        PdfMergeItem item2 = new PdfMergeItem(f1, f1.length(), 2, "全部", false);
        assertFalse(item2.isSelected());
    }

    private File createEncryptedTestPdf(String name, int pageCount, String userPassword) throws IOException {
        File file = tempDir.resolve(name).toFile();
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= pageCount; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 12);
                    cs.newLineAtOffset(80, 700);
                    cs.showText("Secret " + i);
                    cs.endText();
                }
            }
            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy("owner-secret", userPassword, new AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            doc.save(file);
        }
        return file;
    }

    @Test
    void failurePreservesExistingOutputFile() throws Exception {
        File f1 = createTestPdf("source.pdf", 2);
        File output = tempDir.resolve("already-there.pdf").toFile();
        Files.writeString(output.toPath(), "ORIGINAL", StandardCharsets.UTF_8);

        // 声明 5 页但实际只有 2 页：写入临时文件阶段抛错
        PdfMergeItem inconsistent = new PdfMergeItem(f1, f1.length(), 5, "");
        assertThrows(IOException.class, () -> PdfMerger.merge(List.of(inconsistent), output, null, null));
        assertEquals("ORIGINAL", Files.readString(output.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void cancellationPreservesExistingOutputFile() throws Exception {
        File f1 = createTestPdf("source.pdf", 2);
        File output = tempDir.resolve("already-there.pdf").toFile();
        Files.writeString(output.toPath(), "ORIGINAL", StandardCharsets.UTF_8);

        PdfMergeItem item = new PdfMergeItem(f1, f1.length(), 2, "");
        assertThrows(InterruptedException.class,
                () -> PdfMerger.merge(List.of(item), output, null, () -> true));
        assertEquals("ORIGINAL", Files.readString(output.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsOutputEqualToInput() throws Exception {
        File f1 = createTestPdf("source.pdf", 2);
        PdfMergeItem item = new PdfMergeItem(f1, f1.length(), 2, "");
        assertThrows(IOException.class, () -> PdfMerger.merge(List.of(item), f1, null, null));
        assertEquals(2, PdfMerger.readPageCount(f1));
    }

    @Test
    void encryptedSourceProducesUnencryptedOutput() throws Exception {
        File encrypted = createEncryptedTestPdf("secret.pdf", 3, "pw123");
        assertTrue(PdfMerger.isEncrypted(encrypted));
        assertTrue(PdfMerger.probe(encrypted, null).isNeedsPassword());
        assertTrue(PdfMerger.probe(encrypted, "wrong").isNeedsPassword());
        assertEquals(3, PdfMerger.probe(encrypted, "pw123").getPageCount());

        PdfMergeItem item = new PdfMergeItem(encrypted, encrypted.length(), 3, "");
        item.setEncrypted(true);
        item.setPassword("pw123");
        File output = tempDir.resolve("unencrypted.pdf").toFile();
        PdfMerger.merge(List.of(item), output, null, null);

        try (PDDocument doc = PDDocument.load(output)) {
            assertFalse(doc.isEncrypted(), "合并结果必须是不加密 PDF");
            assertEquals(3, doc.getNumberOfPages());
        }
    }

    @Test
    void deletedPagesAreNotWritten() throws Exception {
        File f1 = createTestPdf("doc.pdf", 5);
        PdfMergeItem item = new PdfMergeItem(f1, f1.length(), 5, "");
        item.removePages(List.of(2, 4));
        File output = tempDir.resolve("deleted.pdf").toFile();
        PdfMerger.merge(List.of(item), output, null, null);
        assertEquals(3, PdfMerger.readPageCount(output));
    }

    @Test
    void duplicatePagesAreWrittenTwice() throws Exception {
        File f1 = createTestPdf("doc.pdf", 3);
        PdfMergeItem item = new PdfMergeItem(f1, f1.length(), 3, "1, 1, 3");
        File output = tempDir.resolve("duplicated.pdf").toFile();
        PdfMerger.merge(List.of(item), output, null, null);
        assertEquals(3, PdfMerger.readPageCount(output));
    }

    @Test
    void progressIsReportedPerPage() throws Exception {
        File f1 = createTestPdf("a.pdf", 2);
        File f2 = createTestPdf("b.pdf", 3);
        List<PdfMergeItem> items = List.of(
                new PdfMergeItem(f1, f1.length(), 2, ""),
                new PdfMergeItem(f2, f2.length(), 3, ""));
        List<Integer> seen = new ArrayList<>();
        PdfMerger.merge(items, tempDir.resolve("progress.pdf").toFile(),
                (cur, total, name) -> {
                    assertEquals(5, total);
                    seen.add(cur);
                }, null);
        assertEquals(List.of(1, 2, 3, 4, 5), seen);
    }

    @Test
    void cancellationIsCheckedDuringPageLoop() throws Exception {
        File f1 = createTestPdf("a.pdf", 5);
        PdfMergeItem item = new PdfMergeItem(f1, f1.length(), 5, "");
        List<Integer> seen = new ArrayList<>();
        assertThrows(InterruptedException.class, () -> PdfMerger.merge(
                List.of(item), tempDir.resolve("cancel.pdf").toFile(),
                (cur, total, name) -> seen.add(cur),
                () -> curHasReachedThree(seen)));
    }

    private static boolean curHasReachedThree(List<Integer> seen) {
        return seen.size() >= 3;
    }

    @Test
    void outlineHasOneEntryPerSourceFile() throws Exception {
        File f1 = createTestPdf("a.pdf", 2);
        File f2 = createTestPdf("b.pdf", 1);
        List<PdfMergeItem> items = List.of(
                new PdfMergeItem(f1, f1.length(), 2, ""),
                new PdfMergeItem(f2, f2.length(), 1, ""));
        File output = tempDir.resolve("outline.pdf").toFile();
        PdfMerger.merge(items, output, null, null);

        try (PDDocument doc = PDDocument.load(output)) {
            PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
            assertNotNull(outline);
            int count = 0;
            PDOutlineItem current = outline.getFirstChild();
            List<String> titles = new ArrayList<>();
            while (current != null) {
                count++;
                titles.add(current.getTitle());
                current = current.getNextSibling();
            }
            assertEquals(2, count);
            assertEquals(List.of("a.pdf", "b.pdf"), titles);
        }
    }
}
