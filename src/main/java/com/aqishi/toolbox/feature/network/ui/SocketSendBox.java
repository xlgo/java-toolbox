package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.domain.PayloadChecksum;
import com.aqishi.toolbox.feature.network.domain.PayloadCodec;
import com.aqishi.toolbox.feature.network.domain.PayloadFormat;
import com.aqishi.toolbox.feature.network.domain.PayloadLineEnding;
import com.aqishi.toolbox.feature.network.domain.PayloadParseException;
import com.aqishi.toolbox.feature.network.domain.PayloadPreset;
import com.aqishi.toolbox.feature.network.domain.PayloadRenderer;
import com.aqishi.toolbox.feature.network.domain.SocketSendException;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 发送区：格式、字符集、转义、行尾、校验（附计算结果预览）、多行输入、历史、定时发送。
 * 发送文件的部分见 {@link SocketSendFileBar}。
 */
final class SocketSendBox extends JPanel {

    /** 发送去向，由会话视图实现。 */
    interface Sink {
        /** 入队一条载荷；失败抛出 SocketSendException。 */
        void send(byte[] payload);

        /** 会话是否可发送。 */
        boolean isReady();

        /** 把一条提示写进日志。 */
        void report(String message, boolean error);
    }

    static final int MIN_INTERVAL_MS = 10;

    private final Sink sink;
    private final SocketSendHistory history = new SocketSendHistory();

    private final JComboBox<String> formatCombo;
    private final JComboBox<String> charsetCombo;
    private final JCheckBox escapesCheck;
    private final JComboBox<String> lineEndingCombo;
    private final JComboBox<String> checksumCombo;
    private final JLabel previewLabel;
    private final JTextArea input;
    private final JButton sendBtn;
    private final JCheckBox clearAfterSendCheck;
    private final JCheckBox autoSendCheck;
    private final JSpinner intervalSpinner;
    private final JSpinner countSpinner;
    private Timer autoTimer;
    private int autoSent;

    SocketSendBox(Sink sink, SocketSendFileBar fileBar) {
        super(new BorderLayout(0, Tokens.SPACE_XS));
        setOpaque(false);
        this.sink = sink;

        formatCombo = Fields.combo(new String[]{"TEXT", "HEX"}, 78);
        charsetCombo = Fields.combo(PayloadCodec.CHARSETS.toArray(new String[0]), 115);
        escapesCheck = Fields.check(I18n.get("tool.socketdebug.send.escapes"), false);
        escapesCheck.setToolTipText(I18n.get("tool.socketdebug.send.escapes.tip"));
        String[] endings = new String[PayloadLineEnding.values().length];
        for (PayloadLineEnding ending : PayloadLineEnding.values()) {
            endings[ending.ordinal()] = SocketUiText.lineEnding(ending);
        }
        lineEndingCombo = Fields.combo(endings, 115);
        String[] checksums = new String[PayloadChecksum.values().length];
        for (PayloadChecksum checksum : PayloadChecksum.values()) {
            checksums[checksum.ordinal()] = SocketUiText.checksum(checksum);
        }
        checksumCombo = Fields.combo(checksums, 145);
        previewLabel = Fields.caption("");

        input = Fields.area(4, 30);
        input.setFont(Tokens.fontMono());
        sendBtn = Buttons.primary(I18n.get("tool.socketdebug.send.send"));
        sendBtn.setToolTipText(I18n.get("tool.socketdebug.send.send.tip"));
        JButton historyBtn = Buttons.snug(I18n.get("tool.socketdebug.send.history"));
        clearAfterSendCheck = Fields.check(I18n.get("tool.socketdebug.send.clearAfter"), false);
        autoSendCheck = Fields.check(I18n.get("tool.socketdebug.send.auto"), false);
        intervalSpinner = Fields.spinner(1000, MIN_INTERVAL_MS, 3_600_000, 100);
        countSpinner = Fields.spinner(0, 0, 1_000_000_000, 1);
        countSpinner.setToolTipText(I18n.get("tool.socketdebug.send.count.tip"));

        add(Layouts.wrapRow(Tokens.SPACE_SM, Tokens.SPACE_XS,
                Fields.label(I18n.get("tool.socketdebug.send.format")), formatCombo, charsetCombo, escapesCheck,
                Fields.label(I18n.get("tool.socketdebug.send.lineEnding")), lineEndingCombo,
                Fields.label(I18n.get("tool.socketdebug.send.checksum")), checksumCombo, previewLabel),
                BorderLayout.NORTH);
        JScrollPane editor = Fields.scrollBoxed(input);
        editor.setPreferredSize(new Dimension(0, 90));
        editor.setMinimumSize(new Dimension(0, 70));
        add(editor, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(0, Tokens.SPACE_XS));
        south.setOpaque(false);
        south.add(Layouts.wrapRow(Tokens.SPACE_SM, Tokens.SPACE_XS,
                autoSendCheck, Fields.label(I18n.get("tool.socketdebug.send.interval")), intervalSpinner,
                Fields.label(I18n.get("tool.socketdebug.send.count")), countSpinner,
                clearAfterSendCheck, historyBtn), BorderLayout.NORTH);
        if (fileBar != null) {
            south.add(fileBar, BorderLayout.SOUTH);
        }
        add(south, BorderLayout.SOUTH);

        sendBtn.addActionListener(e -> sendNow());
        historyBtn.addActionListener(e -> showHistory(historyBtn));
        autoSendCheck.addActionListener(e -> {
            if (autoSendCheck.isSelected()) {
                startAutoSend();
            } else {
                stopAutoSend();
            }
        });
        installKeys();
        Runnable refresh = this::updatePreview;
        formatCombo.addActionListener(e -> refresh.run());
        charsetCombo.addActionListener(e -> refresh.run());
        escapesCheck.addActionListener(e -> refresh.run());
        lineEndingCombo.addActionListener(e -> refresh.run());
        checksumCombo.addActionListener(e -> refresh.run());
        input.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refresh.run(); }
            @Override public void removeUpdate(DocumentEvent e) { refresh.run(); }
            @Override public void changedUpdate(DocumentEvent e) { refresh.run(); }
        });
        updatePreview();
    }

    /** The parent card keeps Send visible while the options and payload area scroll. */
    JButton sendButton() { return sendBtn; }

    /** Ctrl+Enter 发送；单行内容时上下键翻历史，多行时用 Ctrl+上/下。 */
    private void installKeys() {
        InputMap map = input.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actions = input.getActionMap();
        map.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "socketSend");
        actions.put("socketSend", action(this::sendNow));
        map.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.CTRL_DOWN_MASK), "socketHistoryPrev");
        map.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.CTRL_DOWN_MASK), "socketHistoryNext");
        actions.put("socketHistoryPrev", action(() -> browse(true)));
        actions.put("socketHistoryNext", action(() -> browse(false)));

        Action caretUp = actions.get(map.get(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0)));
        Action caretDown = actions.get(map.get(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0)));
        map.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "socketUp");
        map.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "socketDown");
        actions.put("socketUp", action(() -> arrow(true, caretUp)));
        actions.put("socketDown", action(() -> arrow(false, caretDown)));
    }

    private void arrow(boolean up, Action caretAction) {
        if (input.getText().indexOf('\n') < 0 && !history.isEmpty()) {
            browse(up);
        } else if (caretAction != null) {
            caretAction.actionPerformed(new java.awt.event.ActionEvent(input, 0, null));
        }
    }

    private void browse(boolean older) {
        String value = older ? history.previous(input.getText()) : history.next();
        if (value != null) {
            input.setText(value);
        }
    }

    private static Action action(Runnable body) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                body.run();
            }
        };
    }

    private void showHistory(Component anchor) {
        JPopupMenu menu = new JPopupMenu();
        if (history.isEmpty()) {
            JMenuItem empty = new JMenuItem(I18n.get("tool.socketdebug.send.history.empty"));
            empty.setEnabled(false);
            menu.add(empty);
        }
        for (String item : history.items()) {
            String label = item.length() > 60 ? item.substring(0, 60) + "..." : item;
            JMenuItem entry = new JMenuItem(label.replace("\n", " "));
            entry.addActionListener(e -> input.setText(item));
            menu.add(entry);
        }
        menu.show(anchor, 0, anchor.getHeight());
    }

    PayloadCodec.Spec spec() {
        return new PayloadCodec.Spec(format(), charset(), escapesCheck.isSelected(),
                PayloadLineEnding.values()[Math.max(0, lineEndingCombo.getSelectedIndex())],
                PayloadChecksum.values()[Math.max(0, checksumCombo.getSelectedIndex())]);
    }

    private PayloadFormat format() {
        return formatCombo.getSelectedIndex() == 1 ? PayloadFormat.HEX : PayloadFormat.TEXT;
    }

    private Charset charset() {
        try {
            return Charset.forName(String.valueOf(charsetCombo.getSelectedItem()));
        } catch (RuntimeException unknown) {
            return StandardCharsets.UTF_8;
        }
    }

    /** 实时预览：字节数与校验值，输入有误时显示出错位置。 */
    private void updatePreview() {
        PayloadCodec.Spec spec = spec();
        try {
            byte[] payload = PayloadCodec.encode(input.getText(), spec);
            PayloadChecksum checksum = spec.getChecksum();
            String text = I18n.get("tool.socketdebug.send.preview.bytes", String.valueOf(payload.length));
            if (checksum != PayloadChecksum.NONE) {
                byte[] tail = new byte[checksum.width()];
                System.arraycopy(payload, payload.length - tail.length, tail, 0, tail.length);
                long value = checksum.compute(java.util.Arrays.copyOf(payload, payload.length - tail.length));
                text += "  " + checksum.format(value) + " [" + PayloadRenderer.toHex(tail) + "]";
            }
            previewLabel.setText(text);
            previewLabel.setForeground(Tokens.mutedForeground());
        } catch (PayloadParseException error) {
            previewLabel.setText(SocketUiText.parseError(error));
            previewLabel.setForeground(Tokens.danger());
        }
    }

    /** 编码并发送当前输入；成功返回 true。 */
    boolean sendNow() {
        if (!sink.isReady()) {
            sink.report(SocketUiText.error(com.aqishi.toolbox.feature.network.domain.SocketError.NOT_CONNECTED), true);
            return false;
        }
        String text = input.getText();
        byte[] payload;
        try {
            payload = PayloadCodec.encode(text, spec());
        } catch (PayloadParseException error) {
            sink.report(SocketUiText.parseError(error), true);
            return false;
        }
        try {
            sink.send(payload);
        } catch (SocketSendException error) {
            sink.report(SocketUiText.error(error.getError()) + " (" + error.getMessage() + ")", true);
            return false;
        }
        history.add(text);
        if (clearAfterSendCheck.isSelected() && (autoTimer == null || !autoTimer.isRunning())) {
            input.setText("");
        }
        return true;
    }

    private void startAutoSend() {
        stopAutoSend();
        autoSent = 0;
        int interval = Math.max(MIN_INTERVAL_MS, ((Number) intervalSpinner.getValue()).intValue());
        int limit = ((Number) countSpinner.getValue()).intValue();
        autoTimer = new Timer(interval, e -> {
            if (!sendNow()) {
                stopAutoSend();
                return;
            }
            autoSent++;
            if (limit > 0 && autoSent >= limit) {
                stopAutoSend();
            }
        });
        autoTimer.setInitialDelay(0);
        autoTimer.start();
        autoSendCheck.setSelected(true);
    }

    /** 停止定时发送；断开或关闭时由会话视图调用。 */
    void stopAutoSend() {
        if (autoTimer != null) {
            autoTimer.stop();
            autoTimer = null;
        }
        autoSendCheck.setSelected(false);
    }

    boolean isAutoSending() {
        return autoTimer != null && autoTimer.isRunning();
    }

    /** 把预设载入发送区。 */
    void loadPreset(PayloadPreset preset) {
        formatCombo.setSelectedIndex(preset.formatValue() == PayloadFormat.HEX ? 1 : 0);
        charsetCombo.setSelectedItem(preset.charsetValue().name());
        escapesCheck.setSelected(preset.isEscapes());
        lineEndingCombo.setSelectedIndex(preset.lineEndingValue().ordinal());
        checksumCombo.setSelectedIndex(preset.checksumValue().ordinal());
        input.setText(preset.getContent());
    }

    /** 以当前发送区内容生成预设。 */
    PayloadPreset toPreset(String name) {
        PayloadCodec.Spec spec = spec();
        PayloadPreset preset = new PayloadPreset(name, spec.getFormat(), input.getText(),
                spec.getLineEnding(), spec.getChecksum());
        preset.setCharset(spec.getCharset().name());
        preset.setEscapes(spec.isEscapes());
        return preset;
    }

    void dispose() {
        stopAutoSend();
    }

    JTextArea inputForTest() {
        return input;
    }

    String previewForTest() {
        return previewLabel.getText();
    }

    void setChecksumForTest(PayloadChecksum checksum) {
        checksumCombo.setSelectedIndex(checksum.ordinal());
    }

    void setFormatForTest(PayloadFormat format) {
        formatCombo.setSelectedIndex(format == PayloadFormat.HEX ? 1 : 0);
    }

    void startAutoSendForTest(int intervalMs, int count) {
        intervalSpinner.setValue(intervalMs);
        countSpinner.setValue(count);
        startAutoSend();
    }
}
