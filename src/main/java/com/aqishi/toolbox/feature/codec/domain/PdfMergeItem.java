package com.aqishi.toolbox.feature.codec.domain;

import java.io.File;
import java.util.List;
import java.util.Objects;

/**
 * 待合并的 PDF 文件项。
 */
public final class PdfMergeItem {

    private final File file;
    private final long fileSize;
    private int pageCount;
    private String pageRange;
    private boolean selected = true;

    public PdfMergeItem(File file, long fileSize, int pageCount, String pageRange) {
        this(file, fileSize, pageCount, pageRange, true);
    }

    public PdfMergeItem(File file, long fileSize, int pageCount, String pageRange, boolean selected) {
        this.file = Objects.requireNonNull(file, "file");
        this.fileSize = fileSize;
        this.pageCount = Math.max(0, pageCount);
        this.pageRange = pageRange == null ? "" : pageRange.trim();
        this.selected = selected;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public File getFile() {
        return file;
    }

    public String getFileName() {
        return file.getName();
    }

    public String getAbsolutePath() {
        return file.getAbsolutePath();
    }

    public long getFileSize() {
        return fileSize;
    }

    public int getPageCount() {
        return pageCount;
    }

    public void setPageCount(int pageCount) {
        this.pageCount = Math.max(0, pageCount);
    }

    public String getPageRange() {
        return pageRange;
    }

    public void setPageRange(String pageRange) {
        this.pageRange = pageRange == null ? "" : pageRange.trim();
    }

    /**
     * 判断是否合并该文件的全部页面。
     */
    public boolean isAllPages() {
        return pageRange.isEmpty() || "全部".equalsIgnoreCase(pageRange) || "all".equalsIgnoreCase(pageRange);
    }

    /**
     * 解析本项最终需要合并的页码列表。
     */
    public List<Integer> resolvePages() {
        return PageRangeParser.parse(pageRange, pageCount);
    }

    /**
     * 计算本项计划合并的页数。
     */
    public int getEffectivePageCount() {
        if (pageCount <= 0) {
            return 0;
        }
        if (isAllPages()) {
            return pageCount;
        }
        try {
            return resolvePages().size();
        } catch (Exception e) {
            return 0;
        }
    }
}
