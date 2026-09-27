package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.application.SocketPresetStore;
import com.aqishi.toolbox.feature.network.domain.PayloadChecksum;
import com.aqishi.toolbox.feature.network.domain.PayloadCodec;
import com.aqishi.toolbox.feature.network.domain.PayloadFormat;
import com.aqishi.toolbox.feature.network.domain.PayloadLineEnding;
import com.aqishi.toolbox.feature.network.domain.PayloadPreset;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.Errors;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 快捷发送列表：命名的预设报文，双击发送。所有会话标签共享同一个列表模型与持久化存储。
 */
final class QuickSendList extends JPanel {

    /** 与发送区交互。 */
    interface Host {
        /** 以当前发送区内容生成预设（新增时作为初值）。 */
        PayloadPreset capture(String name);

        /** 发送一条预设。 */
        void sendPreset(PayloadPreset preset);
    }

    /** 共享的列表模型 + 存储；由面板持有，各标签复用。 */
    static final class Shared {
        final DefaultListModel<PayloadPreset> model = new DefaultListModel<>();
        private final SocketPresetStore store;

        Shared(SocketPresetStore store) {
            this.store = store;
            if (store == null) {
                return;
            }
            try {
                for (PayloadPreset preset : store.load()) {
                    model.addElement(preset);
                }
            } catch (RuntimeException unreadable) {
                // 预设损坏不应让整个工具打不开；空列表起步，下次保存覆盖
                Errors.log("load socket debug presets", unreadable);
            }
        }

        void persist() {
            if (store == null) {
                return;
            }
            List<PayloadPreset> presets = new ArrayList<>();
            for (int i = 0; i < model.size(); i++) {
                presets.add(model.get(i));
            }
            try {
                store.save(presets);
            } catch (RuntimeException failed) {
                Errors.log("save socket debug presets", failed);
            }
        }
    }

    private final Shared shared;
    private final Host host;
    private final JList<PayloadPreset> list;

    QuickSendList(Shared shared, Host host) {
        super(new BorderLayout(0, Tokens.SPACE_XS));
        setOpaque(false);
        this.shared = shared;
        this.host = host;
        list = new JList<>(shared.model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setToolTipText(I18n.get("tool.socketdebug.quick.tip"));

        JButton addBtn = Buttons.snug(I18n.get("tool.socketdebug.quick.add"));
        JButton editBtn = Buttons.snug(I18n.get("tool.socketdebug.quick.edit"));
        JButton deleteBtn = Buttons.snug(I18n.get("tool.socketdebug.quick.delete"));
        JButton sendBtn = Buttons.snug(I18n.get("tool.socketdebug.quick.send"));
        add(Layouts.wrapRow(Tokens.SPACE_XS, Tokens.SPACE_XS, addBtn, editBtn, deleteBtn, sendBtn),
                BorderLayout.NORTH);
        JScrollPane scroll = Fields.scroll(list);
        scroll.setPreferredSize(new Dimension(180, 120));
        add(scroll, BorderLayout.CENTER);

        addBtn.addActionListener(e -> {
            PayloadPreset edited = edit(host.capture(I18n.get("tool.socketdebug.quick.defaultName",
                    String.valueOf(shared.model.size() + 1))));
            if (edited != null) {
                addPreset(edited);
            }
        });
        editBtn.addActionListener(e -> {
            int index = list.getSelectedIndex();
            if (index >= 0) {
                PayloadPreset edited = edit(shared.model.get(index));
                if (edited != null) {
                    replacePreset(index, edited);
                }
            }
        });
        deleteBtn.addActionListener(e -> {
            int index = list.getSelectedIndex();
            if (index >= 0) {
                removePreset(index);
            }
        });
        sendBtn.addActionListener(e -> sendSelected());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    sendSelected();
                }
            }
        });
    }

    private void sendSelected() {
        PayloadPreset preset = list.getSelectedValue();
        if (preset != null) {
            host.sendPreset(preset);
        }
    }

    void addPreset(PayloadPreset preset) {
        shared.model.addElement(preset);
        shared.persist();
    }

    void replacePreset(int index, PayloadPreset preset) {
        shared.model.set(index, preset);
        shared.persist();
    }

    void removePreset(int index) {
        shared.model.remove(index);
        shared.persist();
    }

    /** 弹出编辑对话框；取消返回 null。 */
    private PayloadPreset edit(PayloadPreset initial) {
        JTextField nameField = Fields.text(initial.getName());
        JComboBox<String> formatCombo = Fields.combo(new String[]{"TEXT", "HEX"});
        formatCombo.setSelectedIndex(initial.formatValue() == PayloadFormat.HEX ? 1 : 0);
        JComboBox<String> charsetCombo = Fields.combo(PayloadCodec.CHARSETS.toArray(new String[0]));
        charsetCombo.setSelectedItem(initial.charsetValue().name());
        JCheckBox escapesCheck = Fields.check(I18n.get("tool.socketdebug.send.escapes"), initial.isEscapes());
        JComboBox<String> endingCombo = Fields.combo(labels(PayloadLineEnding.values().length, true));
        endingCombo.setSelectedIndex(initial.lineEndingValue().ordinal());
        JComboBox<String> checksumCombo = Fields.combo(labels(PayloadChecksum.values().length, false));
        checksumCombo.setSelectedIndex(initial.checksumValue().ordinal());
        JTextArea contentArea = Fields.area(5, 32);
        contentArea.setFont(Tokens.fontMono());
        contentArea.setText(initial.getContent());

        FormGrid form = new FormGrid();
        form.row(I18n.get("tool.socketdebug.quick.name"), nameField);
        form.row(I18n.get("tool.socketdebug.send.format"),
                Layouts.wrapRow(Tokens.SPACE_SM, 0, formatCombo, charsetCombo, escapesCheck));
        form.row(I18n.get("tool.socketdebug.send.lineEnding"), endingCombo);
        form.row(I18n.get("tool.socketdebug.send.checksum"), checksumCombo);
        form.row(I18n.get("tool.socketdebug.quick.content"), Fields.scroll(contentArea));

        int answer = JOptionPane.showConfirmDialog(this, form, I18n.get("tool.socketdebug.quick.dialog"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return null;
        }
        PayloadPreset result = new PayloadPreset(nameField.getText().trim(),
                formatCombo.getSelectedIndex() == 1 ? PayloadFormat.HEX : PayloadFormat.TEXT,
                contentArea.getText(), PayloadLineEnding.values()[endingCombo.getSelectedIndex()],
                PayloadChecksum.values()[checksumCombo.getSelectedIndex()]);
        result.setCharset(String.valueOf(charsetCombo.getSelectedItem()));
        result.setEscapes(escapesCheck.isSelected());
        return result;
    }

    private static String[] labels(int count, boolean lineEndings) {
        String[] out = new String[count];
        for (int i = 0; i < count; i++) {
            out[i] = lineEndings ? SocketUiText.lineEnding(PayloadLineEnding.values()[i])
                    : SocketUiText.checksum(PayloadChecksum.values()[i]);
        }
        return out;
    }

    void selectForTest(int index) {
        list.setSelectedIndex(index);
    }

    void sendSelectedForTest() {
        sendSelected();
    }
}
