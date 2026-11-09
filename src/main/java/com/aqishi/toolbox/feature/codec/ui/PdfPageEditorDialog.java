package com.aqishi.toolbox.feature.codec.ui;

import com.aqishi.toolbox.feature.codec.domain.PageRangeParser;
import com.aqishi.toolbox.feature.codec.domain.PdfMergeItem;
import com.aqishi.toolbox.feature.codec.domain.PdfThumbnailRenderer;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 单文件页面整理对话框。
 *
 * <p>点击缩略图 = 标记删除该页，再点一次取消；确定后写回 {@link PdfMergeItem#setKeptPages(List)}。
 * 只影响合并结果，<b>不修改、不删除磁盘上的原文件</b>。</p>
 *
 * <p>缩略图在后台线程批量渲染，单页失败显示占位图；超过 {@link #MAX_THUMBS} 页时
 * 只预览前若干页，其余页默认保留，可通过页码范围文本框精确管理。</p>
 */
public final class PdfPageEditorDialog extends JDialog {

    private static final int MAX_THUMBS = 300;
    private static final int TILE_WIDTH = PdfThumbnailRenderer.DEFAULT_WIDTH;
    private static final int TILE_HEIGHT = PdfThumbnailRenderer.PLACEHOLDER_HEIGHT + 34;
    private static final int COLUMNS = 5;

    private final PdfMergeItem item;
    private final JToggleButton[] tiles;
    private final JLabel summaryLabel;
    private final JTextField rangeField;
    private final JProgressBar progressBar;
    private SwingWorker<Void, TileUpdate> renderWorker;
    private List<Integer> appliedRange;
    private boolean confirmed;
    private String normalizeNote = "";

    private static final class TileUpdate {
        final int page;
        final BufferedImage image;

        TileUpdate(int page, BufferedImage image) {
            this.page = page;
            this.image = image;
        }
    }

    public PdfPageEditorDialog(Window owner, PdfMergeItem item) {
        super(owner, I18n.get("tool.pdfmerge.page.title"), ModalityType.APPLICATION_MODAL);
        this.item = item;
        int shown = Math.min(item.getPageCount(), MAX_THUMBS);
        this.tiles = new JToggleButton[shown];
        this.summaryLabel = new JLabel();
        this.rangeField = Fields.text("");
        this.progressBar = new JProgressBar(0, Math.max(1, shown));
        this.progressBar.setStringPainted(true);

        JPanel content = new JPanel(new BorderLayout(Tokens.SPACE_SM, Tokens.SPACE_SM));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setContentPane(content);

        content.add(buildHeader(), BorderLayout.NORTH);
        content.add(buildGrid(), BorderLayout.CENTER);
        content.add(buildFooter(), BorderLayout.SOUTH);

        initializeFromItem();
        updateSummary();
        setSize(760, 620);
        setLocationRelativeTo(owner);
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);
        String name = item.getFileName();
        JLabel title = new JLabel(String.format(
                I18n.get("tool.pdfmerge.page.file"), name, item.getPageCount()));
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD));
        String hint = I18n.get("tool.pdfmerge.page.hint");
        if (item.getPageCount() > MAX_THUMBS) {
            hint = hint + " · " + String.format(
                    I18n.get("tool.pdfmerge.page.limit"),
                    MAX_THUMBS);
        }
        JLabel hintLabel = new JLabel(hint);
        hintLabel.setForeground(Tokens.mutedForeground());

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_SM, 0));
        toolbar.setOpaque(false);
        JButton selectAllBtn = Buttons.secondary(I18n.get("tool.pdfmerge.page.btn.markAll"));
        JButton invertBtn = Buttons.secondary(I18n.get("tool.pdfmerge.page.btn.invert"));
        JButton restoreBtn = Buttons.secondary(I18n.get("tool.pdfmerge.page.btn.restore"));
        JButton oddBtn = Buttons.ghost(I18n.get("tool.pdfmerge.page.btn.odd"));
        JButton evenBtn = Buttons.ghost(I18n.get("tool.pdfmerge.page.btn.even"));
        selectAllBtn.addActionListener(e -> markAllButOne());
        invertBtn.addActionListener(e -> invertMarks());
        restoreBtn.addActionListener(e -> restoreAll());
        oddBtn.addActionListener(e -> markParity(true));
        evenBtn.addActionListener(e -> markParity(false));
        toolbar.add(selectAllBtn);
        toolbar.add(invertBtn);
        toolbar.add(restoreBtn);
        toolbar.add(oddBtn);
        toolbar.add(evenBtn);

        header.add(title);
        header.add(hintLabel);
        header.add(toolbar);
        return header;
    }

    private JScrollPane buildGrid() {
        JPanel grid = new JPanel(new GridLayout(0, COLUMNS, 8, 8));
        grid.setOpaque(false);
        grid.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));
        for (int p = 1; p <= tiles.length; p++) {
            JToggleButton tile = new JToggleButton();
            tile.setVerticalTextPosition(SwingConstants.BOTTOM);
            tile.setHorizontalTextPosition(SwingConstants.CENTER);
            tile.setHorizontalAlignment(SwingConstants.CENTER);
            tile.setFocusPainted(false);
            tile.setPreferredSize(new Dimension(TILE_WIDTH + 16, TILE_HEIGHT));
            tile.setIcon(new ImageIcon(PdfThumbnailRenderer.placeholder(
                    TILE_WIDTH, PdfThumbnailRenderer.PLACEHOLDER_HEIGHT, String.valueOf(p))));
            final int page = p;
            tile.addActionListener(e -> {
                appliedRange = null;
                refreshTile(page);
                updateSummary();
            });
            tiles[p - 1] = tile;
            refreshTile(p);
            grid.add(tile);
        }
        JScrollPane scroll = new JScrollPane(grid);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        return scroll;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(Tokens.SPACE_SM, Tokens.SPACE_SM));
        footer.setOpaque(false);

        JPanel rangePanel = new JPanel(new BorderLayout(Tokens.SPACE_SM, 0));
        rangePanel.setOpaque(false);
        rangePanel.add(new JLabel(I18n.get("tool.pdfmerge.page.range")), BorderLayout.WEST);
        rangePanel.add(rangeField, BorderLayout.CENTER);
        JButton applyBtn = Buttons.secondary(I18n.get("tool.pdfmerge.page.btn.apply"));
        applyBtn.addActionListener(e -> applyRange());
        rangePanel.add(applyBtn, BorderLayout.EAST);

        JPanel statusPanel = new JPanel(new BorderLayout(Tokens.SPACE_SM, 0));
        statusPanel.setOpaque(false);
        statusPanel.add(summaryLabel, BorderLayout.CENTER);
        statusPanel.add(progressBar, BorderLayout.SOUTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, Tokens.SPACE_SM, 0));
        buttons.setOpaque(false);
        JButton okBtn = Buttons.primary(I18n.get("tool.pdfmerge.page.btn.ok"));
        JButton cancelBtn = Buttons.ghost(I18n.get("tool.pdfmerge.page.btn.cancel"));
        okBtn.addActionListener(e -> confirm());
        cancelBtn.addActionListener(e -> dispose());
        buttons.add(okBtn);
        buttons.add(cancelBtn);

        footer.add(rangePanel, BorderLayout.NORTH);
        footer.add(statusPanel, BorderLayout.CENTER);
        footer.add(buttons, BorderLayout.SOUTH);
        return footer;
    }

    private void initializeFromItem() {
        Set<Integer> kept = new HashSet<>(item.resolvePages());
        boolean normalized = kept.size() != item.getEffectivePageCount();
        for (int p = 1; p <= tiles.length; p++) {
            tiles[p - 1].setSelected(!kept.contains(p));
        }
        rangeField.setText(PageRangeParser.format(item.resolvePages()));
        if (normalized) {
            normalizeNote = I18n.get("tool.pdfmerge.page.msg.normalized");
        }
        startRendering();
    }

    private void startRendering() {
        progressBar.setValue(0);
        renderWorker = new SwingWorker<Void, TileUpdate>() {
            @Override
            protected Void doInBackground() throws Exception {
                PdfThumbnailRenderer.renderAll(
                        item.getFile(), item.getPassword(), item.getPageCount(), MAX_THUMBS, TILE_WIDTH,
                        (page, image) -> publish(new TileUpdate(page, image)),
                        this::isCancelled);
                return null;
            }

            @Override
            protected void process(List<TileUpdate> chunks) {
                for (TileUpdate update : chunks) {
                    if (update.page >= 1 && update.page <= tiles.length) {
                        tiles[update.page - 1].setIcon(new ImageIcon(update.image));
                    }
                }
                progressBar.setValue(Math.min(progressBar.getMaximum(), progressBar.getValue() + chunks.size()));
            }

            @Override
            protected void done() {
                progressBar.setVisible(false);
            }
        };
        renderWorker.execute();
    }

    private void refreshTile(int page) {
        JToggleButton tile = tiles[page - 1];
        boolean deleted = tile.isSelected();
        tile.setText(String.format(deleted
                        ? I18n.get("tool.pdfmerge.page.tile.deleted")
                        : I18n.get("tool.pdfmerge.page.tile.page"),
                page));
        tile.setToolTipText(deleted
                ? I18n.get("tool.pdfmerge.page.tip.deleted")
                : I18n.get("tool.pdfmerge.page.tip.kept"));
    }

    private void markAllButOne() {
        appliedRange = null;
        for (JToggleButton tile : tiles) {
            tile.setSelected(true);
        }
        // 若所有页都在预览范围内，强制留最后一页；否则由超出预览范围的页兜底
        if (tiles.length > 0 && item.getPageCount() <= tiles.length) {
            tiles[tiles.length - 1].setSelected(false);
        }
        refreshAllTiles();
        updateSummary();
    }

    private void invertMarks() {
        appliedRange = null;
        for (int p = 1; p <= tiles.length; p++) {
            tiles[p - 1].setSelected(!tiles[p - 1].isSelected());
        }
        refreshAllTiles();
        updateSummary();
    }

    private void restoreAll() {
        appliedRange = null;
        for (JToggleButton tile : tiles) {
            tile.setSelected(false);
        }
        refreshAllTiles();
        rangeField.setText(PageRangeParser.format(collectKeptPages()));
        updateSummary();
    }

    private void markParity(boolean odd) {
        appliedRange = null;
        for (int p = 1; p <= tiles.length; p++) {
            if ((p % 2 == 1) == odd) {
                tiles[p - 1].setSelected(true);
            }
        }
        refreshAllTiles();
        updateSummary();
    }

    private void refreshAllTiles() {
        for (int p = 1; p <= tiles.length; p++) {
            refreshTile(p);
        }
    }

    private void applyRange() {
        try {
            List<Integer> parsed = PageRangeParser.parse(rangeField.getText(), item.getPageCount());
            if (parsed.isEmpty()) {
                throw new IllegalArgumentException(I18n.get("tool.pdfmerge.page.msg.keepOne"));
            }
            appliedRange = parsed;
            Set<Integer> kept = new HashSet<>(parsed);
            for (int p = 1; p <= tiles.length; p++) {
                tiles[p - 1].setSelected(!kept.contains(p));
            }
            refreshAllTiles();
            updateSummary();
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this,
                    I18n.get("tool.pdfmerge.page.msg.rangeError") + ex.getMessage(),
                    I18n.get("tool.pdfmerge.title"),
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private List<Integer> collectKeptPages() {
        List<Integer> kept = new ArrayList<>();
        for (int p = 1; p <= tiles.length; p++) {
            if (!tiles[p - 1].isSelected()) {
                kept.add(p);
            }
        }
        // 超出预览范围的页默认保留
        for (int p = tiles.length + 1; p <= item.getPageCount(); p++) {
            kept.add(p);
        }
        return kept;
    }

    private void updateSummary() {
        int kept = appliedRange != null ? appliedRange.size() : collectKeptPages().size();
        int deleted = Math.max(0, item.getPageCount() - kept);
        summaryLabel.setText(String.format(
                I18n.get("tool.pdfmerge.page.summary"),
                item.getPageCount(), kept, deleted) + normalizeNote);
    }

    private void confirm() {
        try {
            item.setKeptPages(appliedRange != null ? appliedRange : collectKeptPages());
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                    I18n.get("tool.pdfmerge.title"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        confirmed = true;
        dispose();
    }

    /** 显示模态对话框，返回用户是否确认了修改。 */
    public boolean showDialog() {
        setVisible(true);
        return confirmed;
    }

    @Override
    public void dispose() {
        if (renderWorker != null && !renderWorker.isDone()) {
            renderWorker.cancel(true);
        }
        super.dispose();
    }
}
