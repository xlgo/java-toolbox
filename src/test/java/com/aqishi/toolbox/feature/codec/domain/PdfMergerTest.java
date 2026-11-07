package com.aqishi.toolbox.feature.codec.domain;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
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

        PdfMerger.merge(items, output, (cur, total, name) -> progressFiles.add(name), null);

        assertTrue(output.exists());
        assertEquals(3, progressFiles.size());
        assertEquals("doc1.pdf", progressFiles.get(0));
        assertEquals("doc2.pdf", progressFiles.get(1));
        assertEquals("doc3.pdf", progressFiles.get(2));

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
}
