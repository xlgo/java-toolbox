package com.aqishi.toolbox.feature.codec.domain;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 待合并的 PDF 文件项。
 *
 * <p>页面保留集 {@code keptPages} 是页面选择的唯一真源：按输出顺序存放 1-based 源页码，
 * 允许重复（同一页输出多次）。"删除某一页"即从该集合中移除对应页码；文本页码范围
 * 只是它的可编辑镜像，两者通过 {@link PageRangeParser} 双向同步。</p>
 *
 * <p>打开口令只保存在内存中，绝不写入配置、合并清单或日志；无论源文件是否加密，
 * 合并结果始终是<b>不加密</b>的 PDF。</p>
 */
public final class PdfMergeItem {

    private final File file;
    private final long fileSize;
    private int pageCount;
    private final List<Integer> keptPages = new ArrayList<>();
    private boolean selected = true;

    /** 内存态打开口令，仅用于本次会话读取源文件 */
    private String password;
    /** 源文件是否加密且尚未成功读取 */
    private boolean encrypted;
    /** 最近一次读取失败的原因，供界面提示 */
    private String loadError;

    public PdfMergeItem(File file, long fileSize, int pageCount, String pageRange) {
        this(file, fileSize, pageCount, pageRange, true);
    }

    public PdfMergeItem(File file, long fileSize, int pageCount, String pageRange, boolean selected) {
        this.file = Objects.requireNonNull(file, "file");
        this.fileSize = fileSize;
        this.pageCount = Math.max(0, pageCount);
        this.selected = selected;
        try {
            this.keptPages.addAll(PageRangeParser.parse(pageRange, this.pageCount));
        } catch (IllegalArgumentException invalidLegacyRange) {
            // 兼容历史清单中的非法范围：退化为全部页面，不让界面在加载时崩溃
            restoreAllPages();
        }
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

    /**
     * 成功读取到真实页数后调用（例如用户输入口令之后）：更新页数并把页面保留集重置为全部页。
     */
    public void applyLoadedPageCount(int count) {
        this.pageCount = Math.max(0, count);
        this.encrypted = false;
        this.loadError = null;
        restoreAllPages();
    }

    /**
     * 保留旧签名，仅更新页数；页面保留集按新页数裁剪。
     */
    public void setPageCount(int pageCount) {
        this.pageCount = Math.max(0, pageCount);
        if (!keptPages.isEmpty()) {
            keptPages.removeIf(page -> page > this.pageCount);
        }
    }

    public boolean isReadable() {
        return pageCount > 0;
    }

    public boolean isPasswordRequired() {
        return encrypted && !isReadable();
    }

    public boolean isEncrypted() {
        return encrypted;
    }

    public void setEncrypted(boolean encrypted) {
        this.encrypted = encrypted;
    }

    public String getLoadError() {
        return loadError;
    }

    public void setLoadError(String loadError) {
        this.loadError = loadError;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void clearPassword() {
        this.password = null;
    }

    /**
     * 供 UI 显示的页码范围文本；"\u5168\u90e8"由本地化层负责渲染，此处返回空串。
     */
    public String getPageRange() {
        return isAllPages() ? "" : PageRangeParser.format(keptPages);
    }

    /**
     * 通过文本表达式设置页面保留集。解析失败时抛出，由界面提示且不改动原集合。
     */
    public void setPageRange(String pageRange) {
        List<Integer> parsed = PageRangeParser.parse(pageRange, pageCount);
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("Keep at least one page; uncheck or remove the file instead");
        }
        keptPages.clear();
        keptPages.addAll(parsed);
    }

    /**
     * 判断是否为完整顺序的全部页面（无删页、无重排、无重复）。
     */
    public boolean isAllPages() {
        if (pageCount <= 0 || keptPages.size() != pageCount) {
            return false;
        }
        for (int i = 0; i < pageCount; i++) {
            if (keptPages.get(i) != i + 1) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解析本项最终需要合并的页码列表（按输出顺序，可含重复项）。
     */
    public List<Integer> resolvePages() {
        return new ArrayList<>(keptPages);
    }

    public List<Integer> getKeptPages() {
        return Collections.unmodifiableList(keptPages);
    }

    /**
     * 整体替换页面保留集，校验页码范围且不允许清空（至少保留一页）。
     */
    public void setKeptPages(List<Integer> pages) {
        Objects.requireNonNull(pages, "pages");
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("Keep at least one page; uncheck or remove the file instead");
        }
        for (Integer page : pages) {
            if (page == null || page < 1 || page > pageCount) {
                throw new IllegalArgumentException("Page " + page + " out of range (valid: 1 ~ " + pageCount + ")");
            }
        }
        keptPages.clear();
        keptPages.addAll(pages);
    }

    /**
     * 删除指定源页码。若保留集中该页重复出现，所有副本一并移除。
     */
    public void removePages(Collection<Integer> pageNumbers) {
        if (pageNumbers == null || pageNumbers.isEmpty()) {
            return;
        }
        Set<Integer> toRemove = new LinkedHashSet<>(pageNumbers);
        List<Integer> remaining = new ArrayList<>();
        for (Integer page : keptPages) {
            if (!toRemove.contains(page)) {
                remaining.add(page);
            }
        }
        if (remaining.isEmpty()) {
            throw new IllegalArgumentException("Keep at least one page; uncheck or remove the file instead");
        }
        keptPages.clear();
        keptPages.addAll(remaining);
    }

    /** 删除当前保留集里未出现的源页之外，恢复为完整顺序的全部页面。 */
    public void restoreAllPages() {
        keptPages.clear();
        for (int i = 1; i <= pageCount; i++) {
            keptPages.add(i);
        }
    }

    public int getEffectivePageCount() {
        return keptPages.size();
    }
}
