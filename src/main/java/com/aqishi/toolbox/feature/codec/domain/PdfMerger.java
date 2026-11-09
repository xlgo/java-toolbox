package com.aqishi.toolbox.feature.codec.domain;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * PDF 合并核心逻辑。
 *
 * <p>统一走"逐页导入"路径：所有页面按 {@link PdfMergeItem#resolvePages()} 的顺序写入新文档，
 * 因此删页、页序调整、重复页、按页取消、按页进度都是同一套语义。</p>
 *
 * <p>安全性约定：</p>
 * <ul>
 *   <li>先写入<strong>输出同目录</strong>的临时文件，成功后才原子替换目标；任何失败或取消
 *       只删除临时文件，<strong>绝不删除目标路径上原有的文件</strong>。</li>
 *   <li>输出路径不得与任一输入文件相同（含大小写不敏感文件系统与符号链接）。</li>
 *   <li>源文件可以带打开口令，口令仅来自内存；输出文档从不设置加密字典，恒为不加密 PDF。</li>
 * </ul>
 *
 * <p>已知边界：逐页导入会保留页面可视内容，但不会迁移源文档内部的书签、链接注解与表单域；
 * 输出改为按源文件分组重建顶级书签。PDF 本身不是可编辑格式，本类不提供内容改写能力。</p>
 */
public final class PdfMerger {

    @FunctionalInterface
    public interface ProgressListener {
        /** @param currentPage 已写入的页数（1-based） @param totalPages 计划写入的总页数 */
        void onProgress(int currentPage, int totalPages, String currentFileName);
    }

    private PdfMerger() {
    }

    /** 快速读取 PDF 页数（无口令）。 */
    public static int readPageCount(File file) throws IOException {
        return readPageCount(file, null);
    }

    /** 快速读取 PDF 页数，可传入打开口令。口令错误会抛 {@link InvalidPasswordException}。 */
    public static int readPageCount(File file, String password) throws IOException {
        Objects.requireNonNull(file, "file");
        try (PDDocument doc = load(file, password)) {
            return doc.getNumberOfPages();
        }
    }

    /** 判断源文件是否加密（含仅需所有者口令即可打开的情形）。 */
    public static boolean isEncrypted(File file) throws IOException {
        Objects.requireNonNull(file, "file");
        try (PDDocument doc = PDDocument.load(file, "")) {
            return doc.isEncrypted();
        } catch (InvalidPasswordException needsUserPassword) {
            return true;
        }
    }

    private static PDDocument load(File file, String password) throws IOException {
        return password == null || password.isEmpty()
                ? PDDocument.load(file)
                : PDDocument.load(file, password);
    }

    /** 一次打开就能拿到的文件信息，避免界面为页数和加密状态重复加载大文件。 */
    public static final class ProbeResult {
        private final int pageCount;
        private final boolean encrypted;
        private final String error;

        private ProbeResult(int pageCount, boolean encrypted, String error) {
            this.pageCount = pageCount;
            this.encrypted = encrypted;
            this.error = error;
        }

        static ProbeResult ok(int pageCount, boolean encrypted) {
            return new ProbeResult(pageCount, encrypted, null);
        }

        static ProbeResult needsPassword() {
            return new ProbeResult(0, true, null);
        }

        static ProbeResult failed(String message) {
            return new ProbeResult(0, false, message);
        }

        public int getPageCount() {
            return pageCount;
        }

        public boolean isEncrypted() {
            return encrypted;
        }

        public boolean isNeedsPassword() {
            return encrypted && pageCount <= 0;
        }

        public String getError() {
            return error;
        }
    }

    /** 探测 PDF 页数与加密状态；口令错误返回 needsPassword，损坏文件返回 failed。 */
    public static ProbeResult probe(File file, String password) {
        try (PDDocument doc = load(file, password)) {
            return ProbeResult.ok(doc.getNumberOfPages(), doc.isEncrypted());
        } catch (InvalidPasswordException needsUserPassword) {
            return ProbeResult.needsPassword();
        } catch (Exception e) {
            return ProbeResult.failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * 按列表顺序与各自的页面保留集合并为一个目标文件。
     *
     * @throws IOException          文件不存在、口令错误、输出不可写或 PDF 损坏
     * @throws InterruptedException 用户取消或线程被中断
     */
    public static void merge(List<PdfMergeItem> items,
                             File outputFile,
                             ProgressListener listener,
                             BooleanSupplier cancellation) throws IOException, InterruptedException {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("PDF file list must not be empty");
        }
        Objects.requireNonNull(outputFile, "outputFile");

        List<PdfMergeItem> active = new java.util.ArrayList<>();
        for (PdfMergeItem item : items) {
            File f = item.getFile();
            if (!f.exists() || !f.isFile()) {
                throw new IOException("PDF file missing or not a regular file: " + f.getAbsolutePath());
            }
            if (!item.isReadable()) {
                throw new IOException("PDF not loaded (an open password may be required): " + f.getAbsolutePath());
            }
            if (!item.resolvePages().isEmpty()) {
                active.add(item);
            }
        }
        if (active.isEmpty()) {
            throw new IllegalArgumentException("No pages to output");
        }

        int totalPages = 0;
        for (PdfMergeItem item : active) {
            totalPages += item.getEffectivePageCount();
        }
        if (totalPages <= 0) {
            throw new IllegalArgumentException("No pages to output");
        }

        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs() && !parentDir.exists()) {
                throw new IOException("Cannot create output directory: " + parentDir.getAbsolutePath());
            }
        }
        ensureOutputIsNotInput(outputFile, active);

        // 临时文件必须与输出在同一目录，避免跨卷导致 ATOMIC_MOVE 失败
        Path temp = Files.createTempFile(
                parentDir == null ? outputFile.toPath().toAbsolutePath().getParent() : parentDir.toPath(),
                ".pdfmerge-", ".tmp");
        boolean published = false;
        try {
            try (PDDocument target = new PDDocument(MemoryUsageSetting.setupTempFileOnly())) {
                PDDocumentOutline outline = new PDDocumentOutline();
                target.getDocumentCatalog().setDocumentOutline(outline);

                int written = 0;
                PDDocumentInformation firstInfo = null;
                for (PdfMergeItem item : active) {
                    checkCancelled(cancellation);
                    PDPage firstImportedPage = null;
                    try (PDDocument source = load(item.getFile(), item.getPassword())) {
                        if (firstInfo == null) {
                            firstInfo = snapshotInformation(source);
                        }
                        int pageCount = source.getNumberOfPages();
                        for (int pageNumber : item.resolvePages()) {
                            checkCancelled(cancellation);
                            if (pageNumber < 1 || pageNumber > pageCount) {
                                throw new IOException("Page " + pageNumber + " out of range (1 ~ " + pageCount + "): "
                                        + item.getFileName());
                            }
                            PDPage imported = target.importPage(source.getPage(pageNumber - 1));
                            if (firstImportedPage == null) {
                                firstImportedPage = imported;
                            }
                            written++;
                            if (listener != null) {
                                listener.onProgress(written, totalPages, item.getFileName());
                            }
                        }
                    } catch (InvalidPasswordException badPassword) {
                        throw new IOException("Incorrect open password or corrupted file: " + item.getFileName(), badPassword);
                    }
                    appendOutline(outline, item.getFileName(), firstImportedPage);
                }
                applyInformation(target, firstInfo, outputFile.getName());

                checkCancelled(cancellation);
                // 新文档从未设置加密字典：无论源文件是否加密，输出恒为不加密 PDF
                target.save(temp.toFile());
            }
            replaceOutput(temp, outputFile.toPath());
            published = true;
        } catch (InterruptedException e) {
            throw e;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("PDF merge failed: " + e.getMessage(), e);
        } finally {
            if (!published) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                    // 只清理临时文件；目标路径上的原有文件始终保留
                }
            }
        }
    }

    private static void ensureOutputIsNotInput(File output, List<PdfMergeItem> items) throws IOException {
        Path out = output.toPath().toAbsolutePath().normalize();
        for (PdfMergeItem item : items) {
            Path in = item.getFile().toPath().toAbsolutePath().normalize();
            if (out.equals(in)) {
                throw new IOException("Output file must not be the same as an input file: " + output.getAbsolutePath());
            }
            // 大小写不敏感文件系统 / 符号链接 / 硬链接：输出已存在时交给系统判定同一文件
            if (Files.exists(out)) {
                try {
                    if (Files.isSameFile(out, in)) {
                        throw new IOException("Output file must not be the same as an input file: " + output.getAbsolutePath());
                    }
                } catch (java.nio.file.NoSuchFileException ignored) {
                    // 文件在检查瞬间被移走，按不同文件处理
                }
            }
        }
    }

    private static void replaceOutput(Path temp, Path output) throws IOException {
        try {
            try {
                Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException crossDevice) {
                // 临时文件与输出同目录通常不会跨卷，仍保留降级路径
                Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (java.nio.file.FileSystemException locked) {
            throw new IOException("Cannot replace output file; ensure it is not open in a PDF reader and the directory is writable: "
                    + output + " (" + locked.getReason() + ")", locked);
        }
    }

    private static void appendOutline(PDDocumentOutline outline, String title, PDPage firstPage) {
        if (firstPage == null) {
            return;
        }
        PDOutlineItem entry = new PDOutlineItem();
        entry.setTitle(title == null || title.isBlank() ? "PDF" : title);
        PDPageFitWidthDestination destination = new PDPageFitWidthDestination();
        destination.setPage(firstPage);
        entry.setDestination(destination);
        outline.addLast(entry);
    }

    private static PDDocumentInformation snapshotInformation(PDDocument source) {
        PDDocumentInformation src = source.getDocumentInformation();
        PDDocumentInformation copy = new PDDocumentInformation();
        copy.setTitle(src.getTitle());
        copy.setAuthor(src.getAuthor());
        copy.setSubject(src.getSubject());
        copy.setCreator(src.getCreator());
        copy.setKeywords(src.getKeywords());
        return copy;
    }

    private static void applyInformation(PDDocument target, PDDocumentInformation source, String outputName) {
        PDDocumentInformation info = target.getDocumentInformation();
        if (source != null) {
            if (source.getTitle() != null) info.setTitle(source.getTitle());
            if (source.getAuthor() != null) info.setAuthor(source.getAuthor());
            if (source.getSubject() != null) info.setSubject(source.getSubject());
            if (source.getCreator() != null) info.setCreator(source.getCreator());
            if (source.getKeywords() != null) info.setKeywords(source.getKeywords());
        }
        info.setProducer("Java Toolbox - PDF Merger");
        if (info.getTitle() == null || info.getTitle().isBlank()) {
            info.setTitle(outputName);
        }
    }

    private static void checkCancelled(BooleanSupplier cancellation) throws InterruptedException {
        if (cancellation != null && cancellation.getAsBoolean()) {
            throw new InterruptedException("Cancelled");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Interrupted");
        }
    }
}
