package com.aqishi.toolbox.feature.codec.domain;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * PDF 合并核心逻辑。
 */
public final class PdfMerger {

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(int currentItemIndex, int totalItems, String currentFileName);
    }

    private PdfMerger() {
    }

    /**
     * 快速读取 PDF 文件页数。
     *
     * @param file PDF 文件
     * @return 总页数
     * @throws IOException 读取失败时抛出
     */
    public static int readPageCount(File file) throws IOException {
        Objects.requireNonNull(file, "file");
        try (PDDocument doc = PDDocument.load(file)) {
            return doc.getNumberOfPages();
        }
    }

    /**
     * 按照列表顺序将多个 PDF 文件合并为一个目标文件。
     *
     * @param items        待合并文件项列表（有序）
     * @param outputFile   输出目标文件
     * @param listener     进度回调（可为 null）
     * @param cancellation 取消检查（可为 null）
     * @throws IOException              IO 异常
     * @throws InterruptedException     被用户中断或取消
     * @throws IllegalArgumentException 参数不合法
     */
    public static void merge(List<PdfMergeItem> items,
                             File outputFile,
                             ProgressListener listener,
                             BooleanSupplier cancellation) throws IOException, InterruptedException {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("待合并的 PDF 文件列表不能为空");
        }
        Objects.requireNonNull(outputFile, "outputFile");

        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs() && !parentDir.exists()) {
                throw new IOException("无法创建输出目录: " + parentDir.getAbsolutePath());
            }
        }

        // 校验输入文件有效性
        for (PdfMergeItem item : items) {
            File f = item.getFile();
            if (!f.exists() || !f.isFile()) {
                throw new IOException("PDF 文件不存在或不是有效文件: " + f.getAbsolutePath());
            }
        }

        boolean allFullDocuments = items.stream().allMatch(PdfMergeItem::isAllPages);

        try {
            if (allFullDocuments) {
                mergeFullDocuments(items, outputFile, listener, cancellation);
            } else {
                mergeCustomPages(items, outputFile, listener, cancellation);
            }
        } catch (InterruptedException e) {
            // 取消时清理未完成的输出文件
            cleanOutputFile(outputFile);
            throw e;
        } catch (Exception e) {
            cleanOutputFile(outputFile);
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("PDF 合并失败: " + e.getMessage(), e);
        }
    }

    /**
     * 整篇文档快速合并（利用 PDFMergerUtility，保留书签结构，内存优化）。
     */
    private static void mergeFullDocuments(List<PdfMergeItem> items,
                                          File outputFile,
                                          ProgressListener listener,
                                          BooleanSupplier cancellation) throws IOException, InterruptedException {
        PDFMergerUtility merger = new PDFMergerUtility();
        merger.setDestinationFileName(outputFile.getAbsolutePath());

        int total = items.size();
        for (int i = 0; i < total; i++) {
            checkCancelled(cancellation);
            PdfMergeItem item = items.get(i);
            if (listener != null) {
                listener.onProgress(i + 1, total, item.getFileName());
            }
            merger.addSource(item.getFile());
        }

        checkCancelled(cancellation);
        // 使用临时文件模式，防止大文件合并消耗过多堆内存
        merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly());
    }

    /**
     * 按自定义页面范围合并（利用 PDDocument.importPage）。
     */
    private static void mergeCustomPages(List<PdfMergeItem> items,
                                        File outputFile,
                                        ProgressListener listener,
                                        BooleanSupplier cancellation) throws IOException, InterruptedException {
        int total = items.size();
        try (PDDocument targetDoc = new PDDocument(MemoryUsageSetting.setupTempFileOnly())) {
            for (int i = 0; i < total; i++) {
                checkCancelled(cancellation);
                PdfMergeItem item = items.get(i);
                if (listener != null) {
                    listener.onProgress(i + 1, total, item.getFileName());
                }

                try (PDDocument sourceDoc = PDDocument.load(item.getFile())) {
                    int pagesInDoc = sourceDoc.getNumberOfPages();
                    List<Integer> pagesToImport = PageRangeParser.parse(item.getPageRange(), pagesInDoc);
                    for (int pageNum : pagesToImport) {
                        checkCancelled(cancellation);
                        targetDoc.importPage(sourceDoc.getPage(pageNum - 1));
                    }
                }
            }

            checkCancelled(cancellation);
            targetDoc.save(outputFile);
        }
    }

    private static void checkCancelled(BooleanSupplier cancellation) throws InterruptedException {
        if (cancellation != null && cancellation.getAsBoolean()) {
            throw new InterruptedException("操作已取消");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("操作已中断");
        }
    }

    private static void cleanOutputFile(File file) {
        if (file != null && file.exists()) {
            try {
                Files.deleteIfExists(file.toPath());
            } catch (Exception ignored) {
            }
        }
    }
}
