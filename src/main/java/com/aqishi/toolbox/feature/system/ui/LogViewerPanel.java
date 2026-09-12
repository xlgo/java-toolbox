package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.system.domain.LogFileService;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 日志查看器：面向大文件的分页浏览 + 实时跟随（tail）+ 正则过滤高亮。
 *
 * <p>日志常是几百 MB 的追加文件，因此不做「整文件读入」：后端只按窗口读取当前页，
 * UI 只持有当前页文本。跟随模式下用定时器轮询文件长度增量，把新增行追加到底部。</p>
 */
public class LogViewerPanel extends ToolPanel implements ManagedResourceOwner {

    private static final int DEFAULT_PAGE_LINES = 500;
    private static final long MAX_PAGE_BYTES = 4L * 1024 * 1024;
    private static final long MAX_BACK_BYTES = 8L * 1024 * 1024;
    private static final long MAX_APPEND_BYTES = 2L * 1024 * 1024;
    private static final int FOLLOW_INTERVAL_MS = 1000;

    private final JTextArea content = Fields.output(20, 100);
    private final JTextField pathField = Fields.mono("");
    private final JTextField filterField = Fields.text("", I18n.get("log.viewer.filter.hint"));
    private final JCheckBox followBox = Fields.check(I18n.get("log.viewer.follow"), false);
    private final JCheckBox wrapBox = Fields.check(I18n.get("log.viewer.wrap"), false);
    private final JSpinner pageSizeSpinner = Fields.spinner(DEFAULT_PAGE_LINES, 50, 20000, 50);
    private final JLabel statusLabel = Fields.caption(I18n.get("log.viewer.noFile"));

    private final Deque<Long> pageHistory = new ArrayDeque<>();
    private final Highlighter.HighlightPainter highlightPainter =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(0xFF, 0xE0, 0x66));

    private Path currentFile;
    private long pageStart;
    private long pageEnd;
    private long visibleEndOffset;
    private Pattern filterPattern;
    private boolean loading;
    private Timer followTimer;

    public LogViewerPanel() {
        super(ToolCatalog.LOG_VIEWER);
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();
        root.add(buildToolbar(), BorderLayout.NORTH);
        root.add(buildBody(), BorderLayout.CENTER);
        return root;
    }

    private JComponent buildToolbar() {
        JButton open = Buttons.primary(I18n.get("log.viewer.open"));
        JButton reload = Buttons.secondary(I18n.get("log.viewer.reload"));
        JButton first = Buttons.secondary(I18n.get("log.viewer.first"));
        JButton prev = Buttons.secondary(I18n.get("log.viewer.prev"));
        JButton next = Buttons.secondary(I18n.get("log.viewer.next"));
        JButton tail = Buttons.secondary(I18n.get("log.viewer.tail"));
        JButton apply = Buttons.secondary(I18n.get("log.viewer.filter.apply"));
        JButton clear = Buttons.ghost(I18n.get("log.viewer.filter.clear"));

        open.addActionListener(e -> chooseFile());
        reload.addActionListener(e -> reload());
        first.addActionListener(e -> loadPage(0, true, false));
        prev.addActionListener(e -> prevPage());
        next.addActionListener(e -> nextPage());
        tail.addActionListener(e -> goTail());
        apply.addActionListener(e -> applyFilter());
        clear.addActionListener(e -> {
            filterField.setText("");
            applyFilter();
        });
        followBox.addActionListener(e -> updateFollow());
        wrapBox.addActionListener(e -> content.setLineWrap(wrapBox.isSelected()));

        pathField.setEditable(false);
        pageSizeSpinner.setToolTipText(I18n.get("log.viewer.pageSize"));

        JPanel controls = Layouts.wrapRow(open, reload, Box.createHorizontalStrut(Tokens.SPACE_SM),
                first, prev, next, tail, Box.createHorizontalStrut(Tokens.SPACE_SM),
                followBox, wrapBox, pageSizeSpinner);
        JPanel filterRow = Layouts.wrapRow(Tokens.SPACE_MD, Tokens.SPACE_XS, filterField, apply, clear);

        Card config = Card.titled(I18n.get("log.viewer.title"),
                I18n.get("log.viewer.subtitle"));
        JPanel rows = Layouts.stack(Tokens.SPACE_SM, pathField, controls, filterRow);
        config.setContent(rows);
        return config;
    }

    private JComponent buildBody() {
        content.setLineWrap(false);
        content.setEditable(false);

        Card body = Card.flush(I18n.get("log.viewer.contentTitle"));
        body.setContent(Fields.scroll(content));
        body.setFooter(statusLabel);
        return body;
    }

    // ==================== 文件与分页 ====================

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(I18n.get("log.viewer.choose"));
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(content) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File selected = chooser.getSelectedFile();
        if (selected == null) {
            return;
        }
        currentFile = selected.toPath();
        pathField.setText(currentFile.toString());
        filterPattern = null;
        filterField.setText("");
        pageHistory.clear();
        loadPage(0, false, true);
    }

    private void reload() {
        if (currentFile == null) {
            return;
        }
        pageHistory.clear();
        loadPage(0, false, true);
    }

    private void nextPage() {
        if (currentFile == null || pageEnd <= pageStart) {
            return;
        }
        pageHistory.push(pageStart);
        loadPage(pageEnd, false, true);
    }

    private void prevPage() {
        if (currentFile == null) {
            return;
        }
        if (!pageHistory.isEmpty()) {
            loadPage(pageHistory.pop(), false, true);
            return;
        }
        long target = Math.max(0, pageStart);
        long prev = pageStart > 0 ? computePrevStart(pageStart) : 0;
        loadPage(prev < target ? prev : Math.max(0, target - 1), false, true);
    }

    private long computePrevStart(long from) {
        try {
            return LogFileService.previousPageStart(currentFile, from, pageSize(), MAX_BACK_BYTES);
        } catch (Exception ex) {
            return Math.max(0, from - MAX_BACK_BYTES);
        }
    }

    private void goTail() {
        if (currentFile == null) {
            return;
        }
        try {
            long size = Files.size(currentFile);
            pageHistory.clear();
            long start = LogFileService.previousPageStart(currentFile, size, pageSize(), MAX_BACK_BYTES);
            loadPage(start, false, true);
        } catch (Exception ex) {
            UIUtils.error(content, I18n.get("log.viewer.read.failed", ex.getMessage()));
        }
    }

    /** 载入从 {@code offset} 开始的一页。 */
    private void loadPage(long offset, boolean pushHistory, boolean replace) {
        if (currentFile == null || loading) {
            return;
        }
        loading = true;
        setStatus(I18n.get("log.viewer.loading"));
        final Path file = currentFile;
        final int lines = pageSize();
        new SwingWorker<LogFileService.Chunk, Void>() {
            @Override
            protected LogFileService.Chunk doInBackground() throws Exception {
                return LogFileService.readLines(file, offset, lines, MAX_PAGE_BYTES);
            }

            @Override
            protected void done() {
                loading = false;
                try {
                    LogFileService.Chunk chunk = get();
                    pageStart = chunk.getStartOffset();
                    pageEnd = chunk.getNextOffset();
                    visibleEndOffset = chunk.getNextOffset();
                    render(chunk.getLines(), false);
                    if (replace) {
                        content.setCaretPosition(0);
                    }
                    updateStatus(chunk);
                } catch (Exception ex) {
                    UIUtils.error(content, I18n.get("log.viewer.read.failed", rootMessage(ex)));
                }
            }
        }.execute();
    }

    private void updateStatus(LogFileService.Chunk chunk) {
        try {
            long size = Files.size(currentFile);
            setStatus(I18n.get("log.viewer.status",
                    currentFile.getFileName().toString(),
                    chunk.getLines().size(),
                    String.valueOf(pageStart),
                    String.valueOf(pageEnd),
                    String.valueOf(size)));
        } catch (Exception ex) {
            setStatus(currentFile.getFileName().toString());
        }
    }

    // ==================== 过滤与渲染 ====================

    private void applyFilter() {
        String text = filterField.getText().trim();
        if (text.isEmpty()) {
            filterPattern = null;
        } else {
            try {
                filterPattern = Pattern.compile(text);
            } catch (PatternSyntaxException ex) {
                UIUtils.error(content, I18n.get("log.viewer.filter.error", ex.getDescription()));
                return;
            }
        }
        if (currentFile == null) {
            return;
        }
        // 过滤只影响当前页显示，直接重读当前页
        loadPage(pageStart, false, false);
    }

    private void render(List<String> lines, boolean append) {
        List<String> shown = new ArrayList<>();
        for (String line : lines) {
            if (filterPattern == null || filterPattern.matcher(line).find()) {
                shown.add(line);
            }
        }
        if (append) {
            appendText(shown);
        } else {
            content.setText("");
            appendText(shown);
        }
    }

    /** 把若干行追加到文本域，并对正则命中处加高亮。 */
    private void appendText(List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        try {
            var doc = content.getDocument();
            Highlighter highlighter = content.getHighlighter();
            boolean needLeadingNewline = doc.getLength() > 0;
            StringBuilder sb = new StringBuilder();
            List<int[]> ranges = new ArrayList<>();
            int base = doc.getLength();
            for (String line : lines) {
                if (needLeadingNewline || sb.length() > 0) {
                    sb.append('\n');
                }
                int lineStart = base + sb.length();
                sb.append(line);
                if (filterPattern != null) {
                    for (int[] r : LogFileService.matchRanges(line, filterPattern)) {
                        ranges.add(new int[]{lineStart + r[0], lineStart + r[1]});
                    }
                }
            }
            doc.insertString(doc.getLength(), sb.toString(), null);
            for (int[] r : ranges) {
                if (r[0] < r[1] && r[1] <= doc.getLength()) {
                    highlighter.addHighlight(r[0], r[1], highlightPainter);
                }
            }
        } catch (BadLocationException ex) {
            // 理论不可达：偏移均由文档长度推导；忽略以保证 UI 不中断
        }
    }

    // ==================== 跟随（tail） ====================

    private void updateFollow() {
        if (followBox.isSelected()) {
            if (followTimer == null) {
                followTimer = new Timer(FOLLOW_INTERVAL_MS, e -> followTick());
                followTimer.setRepeats(true);
            }
            followTimer.start();
        } else if (followTimer != null) {
            followTimer.stop();
        }
    }

    private void followTick() {
        if (currentFile == null || loading) {
            return;
        }
        try {
            long size = Files.size(currentFile);
            if (size < visibleEndOffset) {
                // 文件被截断/轮转，从头重读
                pageHistory.clear();
                loadPage(0, false, false);
                return;
            }
            if (size == visibleEndOffset) {
                return;
            }
            final long from = visibleEndOffset;
            loading = true;
            new SwingWorker<LogFileService.Chunk, Void>() {
                @Override
                protected LogFileService.Chunk doInBackground() throws Exception {
                    return LogFileService.readLines(currentFile, from, pageSize(), MAX_APPEND_BYTES);
                }

                @Override
                protected void done() {
                    loading = false;
                    try {
                        LogFileService.Chunk chunk = get();
                        visibleEndOffset = chunk.getNextOffset();
                        pageEnd = chunk.getNextOffset();
                        render(chunk.getLines(), true);
                        content.setCaretPosition(content.getDocument().getLength());
                        updateStatus(chunk);
                    } catch (Exception ex) {
                        stopFollowQuietly();
                    }
                }
            }.execute();
        } catch (Exception ex) {
            stopFollowQuietly();
        }
    }

    private void stopFollowQuietly() {
        if (followTimer != null) {
            followTimer.stop();
        }
        followBox.setSelected(false);
    }

    // ==================== 基础设施 ====================

    private int pageSize() {
        Object value = pageSizeSpinner.getValue();
        return value instanceof Number ? ((Number) value).intValue() : DEFAULT_PAGE_LINES;
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    private static String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    @Override
    public void closeResources() {
        if (followTimer != null) {
            followTimer.stop();
        }
    }
}
