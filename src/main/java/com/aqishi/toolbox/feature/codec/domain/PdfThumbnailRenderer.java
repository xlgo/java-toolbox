package com.aqishi.toolbox.feature.codec.domain;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * PDF 页面缩略图渲染。同步方法，调用方负责放到后台线程执行。
 *
 * <p>纯 Java 实现（PDFBox + AWT），Windows / macOS / Linux 均可用，无原生依赖。
 * 单页渲染失败（缺字体、内容流损坏等）由调用方降级为占位图，不影响其它页面。</p>
 */
public final class PdfThumbnailRenderer {

    /** 缩略图目标宽度（像素） */
    public static final int DEFAULT_WIDTH = 120;
    /** 占位图高度，渲染失败时使用 */
    public static final int PLACEHOLDER_HEIGHT = 165;

    private PdfThumbnailRenderer() {
    }

    /**
     * 渲染指定页。
     *
     * @param oneBasedPage 1-based 页码
     * @param targetWidth  目标宽度（像素），按页面实际宽度等比缩放
     */
    public static BufferedImage render(File file, String password, int oneBasedPage, int targetWidth)
            throws IOException {
        if (oneBasedPage < 1) {
            throw new IllegalArgumentException("Page number must be >= 1");
        }
        try (PDDocument doc = open(file, password)) {
            int index = oneBasedPage - 1;
            if (index >= doc.getNumberOfPages()) {
                throw new IOException("Page number out of range: " + oneBasedPage);
            }
            PDPage page = doc.getPage(index);
            float widthPt = page.getMediaBox().getWidth();
            float scale = widthPt > 0 ? (float) targetWidth / widthPt : 1f;
            scale = Math.max(0.05f, Math.min(scale, 4f));
            return new PDFRenderer(doc).renderImage(index, scale, ImageType.RGB);
        }
    }

    private static PDDocument open(File file, String password) throws IOException {
        try {
            return password == null || password.isEmpty()
                    ? PDDocument.load(file)
                    : PDDocument.load(file, password);
        } catch (InvalidPasswordException e) {
            throw new IOException("Incorrect open password or corrupted PDF: " + file.getName(), e);
        }
    }

    /** 渲染不可用时的占位图，保持网格尺寸稳定。 */
    public static BufferedImage placeholder(int width, int height, String label) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(0xF2, 0xF2, 0xF2));
            g.fillRect(0, 0, width, height);
            g.setColor(new Color(0xCC, 0xCC, 0xCC));
            g.drawRect(0, 0, width - 1, height - 1);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0x88, 0x88, 0x88));
            g.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
            String text = label == null ? "" : label;
            FontMetrics fm = g.getFontMetrics();
            int x = Math.max(4, (width - fm.stringWidth(text)) / 2);
            int y = Math.max(16, (height + fm.getAscent()) / 2);
            g.drawString(text, x, y);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** 批量渲染回调，避免每页都重新打开整个文档。 */
    @FunctionalInterface
    public interface PageSink {
        void onPage(int oneBasedPage, BufferedImage image);
    }

    /**
     * 一次打开文档、按序渲染多页。单页失败会回填占位图而不是中断整批。
     * 由调用方在后台线程执行，{@code sink} 里应使用 SwingWorker.publish 回到 EDT。
     *
     * @param maxPages 最多渲染多少页；超出的页不回调，由界面保留占位图
     */
    public static void renderAll(File file, String password, int totalPages, int maxPages,
                                 int targetWidth, PageSink sink, java.util.function.BooleanSupplier cancelled)
            throws IOException {
        int limit = Math.min(totalPages, maxPages);
        try (PDDocument doc = open(file, password)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int p = 1; p <= limit; p++) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    return;
                }
                try {
                    PDPage page = doc.getPage(p - 1);
                    float widthPt = page.getMediaBox().getWidth();
                    float scale = widthPt > 0 ? (float) targetWidth / widthPt : 1f;
                    scale = Math.max(0.05f, Math.min(scale, 4f));
                    sink.onPage(p, renderer.renderImage(p - 1, scale, ImageType.RGB));
                } catch (Exception singlePageFailure) {
                    sink.onPage(p, placeholder(targetWidth, PLACEHOLDER_HEIGHT, String.valueOf(p)));
                }
            }
        }
    }
}
