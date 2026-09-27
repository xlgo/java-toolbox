package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.domain.PayloadCodec;
import com.aqishi.toolbox.feature.network.domain.PayloadDisplayMode;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.Errors;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 收发日志视图。
 *
 * <p>网络线程只往无锁队列里放记录；EDT 上的 Swing Timer 每 100 ms 批量取出渲染，
 * 每次最多处理 {@link #MAX_PER_FLUSH} 条，同色连续记录合并成一次 insertString。
 * 待渲染队列有上限，洪水般的数据（每秒上万包）超出部分直接丢弃并显示"丢弃 N 条"，
 * 保证界面始终可操作。暂停时记录继续进入同一个有界队列，恢复后再显示。</p>
 */
final class SocketLogView extends JPanel {

    static final int FLUSH_INTERVAL_MS = 100;
    static final int MAX_PER_FLUSH = 2000;
    static final int MAX_PENDING = 20_000;
    static final int DEFAULT_MAX_ENTRIES = 5000;

    private final ConcurrentLinkedQueue<SocketLogEntry> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();
    /** 已显示的记录与其在文档中的长度，用于裁剪最旧记录与整体重绘。 */
    private final ArrayDeque<SocketLogEntry> shown = new ArrayDeque<>();
    private final ArrayDeque<Integer> shownLengths = new ArrayDeque<>();

    private final JTextPane pane = new JTextPane();
    private final StyledDocument document = pane.getStyledDocument();
    private final Timer flushTimer;

    private final JComboBox<String> displayCombo;
    private final JCheckBox hexDumpCheck;
    private final JComboBox<String> charsetCombo;
    private final JCheckBox timeCheck;
    private final JToggleButton pauseToggle;
    private final JCheckBox autoScrollCheck;
    private final JSpinner maxEntriesSpinner;

    SocketLogView() {
        super(new BorderLayout(0, Tokens.SPACE_XS));
        setOpaque(false);
        pane.setEditable(false);
        pane.setFont(Tokens.fontMono());
        DefaultCaret caret = (DefaultCaret) pane.getCaret();
        caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);

        displayCombo = Fields.combo(new String[]{
                I18n.get("tool.socketdebug.display.text"),
                I18n.get("tool.socketdebug.display.hex"),
                I18n.get("tool.socketdebug.display.both")});
        hexDumpCheck = Fields.check(I18n.get("tool.socketdebug.log.hexDump"), false);
        charsetCombo = Fields.combo(PayloadCodec.CHARSETS.toArray(new String[0]));
        timeCheck = Fields.check(I18n.get("tool.socketdebug.log.time"), true);
        pauseToggle = Buttons.toggle(I18n.get("tool.socketdebug.log.pause"), false);
        autoScrollCheck = Fields.check(I18n.get("tool.socketdebug.log.autoScroll"), true);
        maxEntriesSpinner = Fields.spinner(DEFAULT_MAX_ENTRIES, 100, 200_000, 500);
        JButton clearBtn = Buttons.snug(I18n.get("tool.socketdebug.log.clear"));
        JButton saveBtn = Buttons.snug(I18n.get("tool.socketdebug.log.save"));
        JButton copyBtn = Buttons.snug(I18n.get("tool.socketdebug.log.copy"));

        add(Layouts.wrapRow(Tokens.SPACE_SM, Tokens.SPACE_XS,
                Fields.label(I18n.get("tool.socketdebug.log.display")), displayCombo, hexDumpCheck,
                charsetCombo, timeCheck, autoScrollCheck,
                Fields.label(I18n.get("tool.socketdebug.log.maxEntries")), maxEntriesSpinner,
                pauseToggle, clearBtn, saveBtn, copyBtn), BorderLayout.NORTH);
        add(Fields.scroll(pane), BorderLayout.CENTER);

        displayCombo.addActionListener(e -> rerender());
        hexDumpCheck.addActionListener(e -> rerender());
        charsetCombo.addActionListener(e -> rerender());
        timeCheck.addActionListener(e -> rerender());
        maxEntriesSpinner.addChangeListener(e -> trim());
        clearBtn.addActionListener(e -> clear());
        saveBtn.addActionListener(e -> saveToFile());
        copyBtn.addActionListener(e -> copySelection());

        flushTimer = new Timer(FLUSH_INTERVAL_MS, e -> flush());
        flushTimer.setCoalesce(true);
        flushTimer.start();
    }

    /** 线程安全：任何线程都可以调用。队列满时丢弃并计数。 */
    void post(SocketLogEntry entry) {
        if (pendingCount.incrementAndGet() > MAX_PENDING) {
            pendingCount.decrementAndGet();
            dropped.incrementAndGet();
            return;
        }
        pending.add(entry);
    }

    /** EDT：取出一批待渲染记录追加到文档。暂停时不处理。 */
    void flush() {
        if (pauseToggle.isSelected()) {
            return;
        }
        long lost = dropped.getAndSet(0);
        // 丢弃提示不进有界队列（队列此时多半是满的），直接作为本批第一条渲染
        SocketLogEntry note = lost <= 0 ? null : SocketLogEntry.error(System.currentTimeMillis(),
                I18n.get("tool.socketdebug.log.dropped", String.valueOf(lost)));
        if (note == null && pending.isEmpty()) {
            return;
        }
        boolean showTime = timeCheck.isSelected();
        PayloadDisplayMode mode = displayMode();
        Charset charset = charset();
        boolean hexDump = hexDumpCheck.isSelected();

        StringBuilder run = new StringBuilder();
        SocketLogEntry.Kind runKind = null;
        int processed = 0;
        SocketLogEntry entry;
        while (processed < MAX_PER_FLUSH && (entry = nextEntry(note)) != null) {
            if (entry == note) {
                note = null;
            } else {
                pendingCount.decrementAndGet();
            }
            processed++;
            String text = entry.render(showTime, mode, charset, hexDump);
            if (runKind != null && runKind != entry.kind) {
                insert(run.toString(), runKind);
                run.setLength(0);
            }
            runKind = entry.kind;
            run.append(text);
            shown.addLast(entry);
            shownLengths.addLast(text.length());
        }
        if (runKind != null) {
            insert(run.toString(), runKind);
        }
        trim();
        if (autoScrollCheck.isSelected()) {
            pane.setCaretPosition(document.getLength());
        }
    }

    private SocketLogEntry nextEntry(SocketLogEntry note) {
        return note != null ? note : pending.poll();
    }

    private void insert(String text, SocketLogEntry.Kind kind) {
        try {
            document.insertString(document.getLength(), text, style(kind));
        } catch (BadLocationException impossible) {
            Errors.ignored("append at document end", impossible);
        }
    }

    /** 颜色在插入时取 Tokens 当前值，跟随亮/暗主题。 */
    private static SimpleAttributeSet style(SocketLogEntry.Kind kind) {
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        Color color;
        switch (kind) {
            case SENT:
                color = Tokens.accent();
                break;
            case RECEIVED:
                color = Tokens.success();
                break;
            case ERROR:
                color = Tokens.danger();
                break;
            default:
                color = Tokens.mutedForeground();
                break;
        }
        StyleConstants.setForeground(attributes, color);
        return attributes;
    }

    /** 超过最大条数时从头部删除最旧的记录。 */
    private void trim() {
        int max = ((Number) maxEntriesSpinner.getValue()).intValue();
        int removeChars = 0;
        while (shown.size() > max) {
            shown.removeFirst();
            removeChars += shownLengths.removeFirst();
        }
        if (removeChars > 0) {
            try {
                document.remove(0, Math.min(removeChars, document.getLength()));
            } catch (BadLocationException impossible) {
                Errors.ignored("trim log head", impossible);
            }
        }
    }

    /** 显示选项变化后按新选项重绘已显示的记录。 */
    private void rerender() {
        SocketLogEntry[] entries = shown.toArray(new SocketLogEntry[0]);
        shown.clear();
        shownLengths.clear();
        try {
            document.remove(0, document.getLength());
        } catch (BadLocationException impossible) {
            Errors.ignored("clear log", impossible);
        }
        // 已显示的记录直接同步重绘（不经过待渲染队列，避免与新到的记录交错）
        boolean showTime = timeCheck.isSelected();
        PayloadDisplayMode mode = displayMode();
        Charset charset = charset();
        boolean hexDump = hexDumpCheck.isSelected();
        StringBuilder run = new StringBuilder();
        SocketLogEntry.Kind runKind = null;
        for (SocketLogEntry entry : entries) {
            String text = entry.render(showTime, mode, charset, hexDump);
            if (runKind != null && runKind != entry.kind) {
                insert(run.toString(), runKind);
                run.setLength(0);
            }
            runKind = entry.kind;
            run.append(text);
            shown.addLast(entry);
            shownLengths.addLast(text.length());
        }
        if (runKind != null) {
            insert(run.toString(), runKind);
        }
        if (autoScrollCheck.isSelected()) {
            pane.setCaretPosition(document.getLength());
        }
    }

    void clear() {
        shown.clear();
        shownLengths.clear();
        pending.clear();
        pendingCount.set(0);
        dropped.set(0);
        pane.setText("");
    }

    private void saveToFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("socket-debug.txt"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        try {
            Files.write(file.toPath(), pane.getText().getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            Errors.report(this, I18n.get("tool.socketdebug.log.saveFailed"), error);
        }
    }

    private void copySelection() {
        String selected = pane.getSelectedText();
        UIUtils.copyToClipboard(selected == null || selected.isEmpty() ? pane.getText() : selected);
    }

    PayloadDisplayMode displayMode() {
        switch (displayCombo.getSelectedIndex()) {
            case 1:
                return PayloadDisplayMode.HEX;
            case 2:
                return PayloadDisplayMode.BOTH;
            default:
                return PayloadDisplayMode.TEXT;
        }
    }

    private Charset charset() {
        try {
            return Charset.forName(String.valueOf(charsetCombo.getSelectedItem()));
        } catch (RuntimeException unknown) {
            return StandardCharsets.UTF_8;
        }
    }

    /** 停止刷新定时器；视图关闭时调用。 */
    void dispose() {
        flushTimer.stop();
    }

    // ---- 测试辅助 ----

    String textForTest() {
        return pane.getText();
    }

    int shownCountForTest() {
        return shown.size();
    }

    void setDisplayModeForTest(int index) {
        displayCombo.setSelectedIndex(index);
    }

    void setMaxEntriesForTest(int max) {
        maxEntriesSpinner.setValue(max);
    }

    void setPausedForTest(boolean paused) {
        pauseToggle.setSelected(paused);
    }
}
