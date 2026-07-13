package com.aqishi.toolbox.convert;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;
import com.formdev.flatlaf.FlatLaf;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 时间戳 ↔ 日期互转面板，支持秒/毫秒、自定义格式与时区。
 * 按照新版 UI 设计重构：顶部当前时间卡片，左右对称转换卡片，支持 LAF 主题切换。
 */
public class TimePanel extends ToolPanel {

    private Timer timer;
    private JLabel topMsValueLabel;
    private JLabel topSecValueLabel;

    public TimePanel() {
        super("convert", "timestamp",
                "Unix", "Timestamp", "时间戳", "日期转换", "时区",
                "毫秒", "秒戳", "DateTime", "时间格式化");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // ==========================================
        // 1. 顶部当前时间卡片
        // ==========================================
        JPanel topCard = new JPanel(new GridBagLayout()) {
            @Override
            public void updateUI() {
                super.updateUI();
                setBorder(new CompoundBorder(
                        new LineBorder(UIManager.getColor("Component.borderColor"), 1, true),
                        new EmptyBorder(12, 16, 12, 16)
                ));
            }
        };

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(4, 4, 4, 4);

        // 标题 "● 当前时间 (CURRENT TIME)"
        JLabel topTitleLabel = new JLabel("● 当前时间 (CURRENT TIME)");
        topTitleLabel.setFont(UIUtils.titleFont().deriveFont(12f));
        topTitleLabel.setForeground(UIManager.getColor("Component.accentColor"));

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 3; gbc.weightx = 1.0; gbc.weighty = 0.0;
        topCard.add(topTitleLabel, gbc);

        // Unix 毫秒 (ms)
        JPanel msPanel = new JPanel(new GridBagLayout());
        msPanel.setOpaque(false);
        JLabel msTitle = new JLabel("Unix 毫秒 (ms)");
        msTitle.setFont(UIUtils.plainFont().deriveFont(11f));
        msTitle.setForeground(UIManager.getColor("Label.disabledForeground"));
        topMsValueLabel = new JLabel("0000000000000");
        topMsValueLabel.setFont(new Font("Monospaced", Font.BOLD, 22));
        topMsValueLabel.setForeground(UIManager.getColor("Component.accentColor"));

        GridBagConstraints cSub = new GridBagConstraints();
        cSub.fill = GridBagConstraints.HORIZONTAL;
        cSub.gridx = 0; cSub.gridy = 0; cSub.weightx = 1.0;
        msPanel.add(msTitle, cSub);
        cSub.gridy = 1;
        msPanel.add(topMsValueLabel, cSub);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 0.4;
        topCard.add(msPanel, gbc);

        // Unix 秒 (s)
        JPanel secPanel = new JPanel(new GridBagLayout());
        secPanel.setOpaque(false);
        JLabel secTitle = new JLabel("Unix 秒 (s)");
        secTitle.setFont(UIUtils.plainFont().deriveFont(11f));
        secTitle.setForeground(UIManager.getColor("Label.disabledForeground"));
        topSecValueLabel = new JLabel("0000000000");
        topSecValueLabel.setFont(new Font("Monospaced", Font.BOLD, 22));

        cSub.gridy = 0;
        secPanel.add(secTitle, cSub);
        cSub.gridy = 1;
        secPanel.add(topSecValueLabel, cSub);

        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 0.4;
        topCard.add(secPanel, gbc);

        // 按钮区：刷新当前，复制
        JPanel topBtnArea = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        topBtnArea.setOpaque(false);
        JButton refreshBtn = UIUtils.button("↻ 刷新当前", 100);
        refreshBtn.putClientProperty("JComponent.roundRect", true);
        JButton copyTopBtn = UIUtils.button("❐ 复制", 80);
        copyTopBtn.putClientProperty("JComponent.roundRect", true);

        refreshBtn.addActionListener(e -> updateCurrentTimeLabels());
        copyTopBtn.addActionListener(e -> {
            UIUtils.copyToClipboard(topMsValueLabel.getText());
            timeIndicatorToast(copyTopBtn, "已复制");
        });

        topBtnArea.add(refreshBtn);
        topBtnArea.add(copyTopBtn);

        gbc.gridx = 2; gbc.gridy = 1; gbc.weightx = 0.2;
        topCard.add(topBtnArea, gbc);

        root.add(topCard, BorderLayout.NORTH);

        // ==========================================
        // 2. 中部双卡片转换区
        // ==========================================
        JPanel mainGrid = new JPanel(new GridLayout(1, 2, 16, 0));

        // ------------------------------------------
        // 2.1 左卡片：时间戳 -> 时间
        // ------------------------------------------
        JPanel leftCard = new JPanel(new GridBagLayout()) {
            @Override
            public void updateUI() {
                super.updateUI();
                setBorder(new CompoundBorder(
                        new LineBorder(UIManager.getColor("Component.borderColor"), 1, true),
                        new EmptyBorder(16, 16, 16, 16)
                ));
            }
        };

        // 标题行
        JLabel leftTitle = new JLabel("➔ 时间戳 ➔ 时间");
        leftTitle.setFont(UIUtils.titleFont());
        JLabel leftBadge = new JLabel("UNIX TO HUMAN");
        leftBadge.setFont(new Font("Monospaced", Font.BOLD, 10));
        leftBadge.setForeground(UIManager.getColor("Label.disabledForeground"));
        
        gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 4, 8, 4);

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 1.0;
        leftCard.add(leftTitle, gbc);
        gbc.gridx = 1; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.EAST;
        leftCard.add(leftBadge, gbc);

        // 输入时间戳 & 单位
        JLabel tsLabel = new JLabel("Unix 时间戳");
        tsLabel.setFont(UIUtils.plainFont().deriveFont(12f));
        JLabel unitLabel = new JLabel("单位");
        unitLabel.setFont(UIUtils.plainFont().deriveFont(12f));

        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.75;
        leftCard.add(tsLabel, gbc);
        gbc.gridx = 1; gbc.weightx = 0.25;
        leftCard.add(unitLabel, gbc);

        JTextField tsInput = new JTextField(String.valueOf(System.currentTimeMillis() / 1000));
        tsInput.setFont(new Font("Monospaced", Font.PLAIN, 14));
        tsInput.putClientProperty("JComponent.roundRect", true);

        JComboBox<String> unitCombo = new JComboBox<>(new String[]{"秒 (s)", "毫秒 (ms)"});
        unitCombo.putClientProperty("JComponent.roundRect", true);
        unitCombo.addActionListener(e -> {
            boolean isMs = unitCombo.getSelectedIndex() == 1;
            long now = System.currentTimeMillis();
            tsInput.setText(String.valueOf(isMs ? now : now / 1000));
        });

        gbc.gridy = 2;
        gbc.gridx = 0; gbc.weightx = 0.75;
        leftCard.add(tsInput, gbc);
        gbc.gridx = 1; gbc.weightx = 0.25;
        leftCard.add(unitCombo, gbc);

        // 格式 & 时区
        JLabel fmtLabel = new JLabel("格式");
        fmtLabel.setFont(UIUtils.plainFont().deriveFont(12f));
        JLabel tzLabel = new JLabel("时区");
        tzLabel.setFont(UIUtils.plainFont().deriveFont(12f));

        gbc.gridy = 3;
        gbc.gridx = 0; gbc.weightx = 0.5;
        leftCard.add(fmtLabel, gbc);
        gbc.gridx = 1; gbc.weightx = 0.5;
        leftCard.add(tzLabel, gbc);

        JComboBox<String> fmtCombo = new JComboBox<>(new String[]{
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss.SSS",
                "yyyy/MM/dd HH:mm:ss",
                "yyyy年MM月dd日 HH:mm:ss",
                "yyyy-MM-dd",
                "HH:mm:ss"
        });
        fmtCombo.setEditable(true);
        fmtCombo.putClientProperty("JComponent.roundRect", true);

        JComboBox<String> tzCombo = new JComboBox<>(buildTimeZones());
        tzCombo.setEditable(true);
        tzCombo.putClientProperty("JComponent.roundRect", true);

        // 统一左侧输入组件高度
        tsInput.setPreferredSize(new Dimension(tsInput.getPreferredSize().width, 32));
        unitCombo.setPreferredSize(new Dimension(unitCombo.getPreferredSize().width, 32));
        fmtCombo.setPreferredSize(new Dimension(fmtCombo.getPreferredSize().width, 32));
        tzCombo.setPreferredSize(new Dimension(tzCombo.getPreferredSize().width, 32));

        gbc.gridy = 4;
        gbc.gridx = 0; gbc.weightx = 0.5;
        leftCard.add(fmtCombo, gbc);
        gbc.gridx = 1; gbc.weightx = 0.5;
        leftCard.add(tzCombo, gbc);

        // 转换结果
        JLabel resLabel = new JLabel("转换结果");
        resLabel.setFont(UIUtils.plainFont().deriveFont(12f));
        gbc.gridy = 5; gbc.gridx = 0; gbc.gridwidth = 2; gbc.weightx = 1.0;
        leftCard.add(resLabel, gbc);

        JTextField leftResultField = new JTextField();
        leftResultField.setEditable(false);
        leftResultField.setFont(new Font("Monospaced", Font.BOLD, 14));
        leftResultField.setBorder(new EmptyBorder(0, 8, 0, 8));
        leftResultField.setOpaque(false);

        JButton copyLeftResultBtn = new JButton("❐");
        copyLeftResultBtn.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 14));
        copyLeftResultBtn.putClientProperty("JButton.buttonType", "toolBarButton");
        copyLeftResultBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));

        JPanel leftResultContainer = new JPanel(new BorderLayout(4, 0)) {
            @Override
            public void updateUI() {
                super.updateUI();
                setOpaque(false);
                Color borderCol = UIManager.getColor("Component.borderColor");
                if (borderCol == null) borderCol = Color.GRAY;
                setBorder(new CompoundBorder(
                        new LineBorder(borderCol, 1, true),
                        new EmptyBorder(6, 8, 6, 8)
                ));
            }
        };
        leftResultContainer.setPreferredSize(new Dimension(leftResultContainer.getPreferredSize().width, 32));

        leftResultContainer.add(leftResultField, BorderLayout.CENTER);
        leftResultContainer.add(copyLeftResultBtn, BorderLayout.EAST);

        copyLeftResultBtn.addActionListener(e -> {
            if (!leftResultField.getText().isEmpty()) {
                UIUtils.copyToClipboard(leftResultField.getText());
                timeIndicatorToast(copyLeftResultBtn, "已复制");
            }
        });

        gbc.gridy = 6;
        leftCard.add(leftResultContainer, gbc);

        // 立即转换按钮
        JButton leftConvertBtn = UIUtils.button("⇄ 立即转换", 120);
        leftConvertBtn.putClientProperty("JComponent.roundRect", true);
        leftConvertBtn.setFont(UIUtils.plainFont().deriveFont(Font.BOLD, 13f));
        leftConvertBtn.addActionListener(e -> {
            try {
                String inputStr = tsInput.getText().trim();
                if (inputStr.isEmpty()) return;
                long ts = Long.parseLong(inputStr);
                boolean isMs = unitCombo.getSelectedIndex() == 1;
                long ms = isMs ? ts : ts * 1000;
                String selectedTz = getSelectedZoneId(tzCombo);
                ZoneId zone = ZoneId.of(selectedTz);
                ZonedDateTime zdt = Instant.ofEpochMilli(ms).atZone(zone);
                String pattern = ((String) fmtCombo.getSelectedItem()).trim();
                leftResultField.setText(zdt.format(DateTimeFormatter.ofPattern(pattern)));
            } catch (Exception ex) {
                UIUtils.error(root, "转换失败，请检查时间戳格式与输入值。");
            }
        });

        gbc.gridy = 7;
        gbc.insets = new Insets(16, 4, 4, 4);
        leftCard.add(leftConvertBtn, gbc);

        // 添加底部垂直占位，使卡片内容顶部对齐
        gbc.gridy = 8;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        leftCard.add(new JPanel() {{ setOpaque(false); }}, gbc);

        mainGrid.add(leftCard);

        // ------------------------------------------
        // 2.2 右卡片：时间 -> 时间戳
        // ------------------------------------------
        JPanel rightCard = new JPanel(new GridBagLayout()) {
            @Override
            public void updateUI() {
                super.updateUI();
                setBorder(new CompoundBorder(
                        new LineBorder(UIManager.getColor("Component.borderColor"), 1, true),
                        new EmptyBorder(16, 16, 16, 16)
                ));
            }
        };

        JLabel rightTitle = new JLabel("➔ 时间 ➔ 时间戳");
        rightTitle.setFont(UIUtils.titleFont());
        JLabel rightBadge = new JLabel("HUMAN TO UNIX");
        rightBadge.setFont(new Font("Monospaced", Font.BOLD, 10));
        rightBadge.setForeground(UIManager.getColor("Label.disabledForeground"));

        gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 4, 8, 4);

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 1.0; gbc.gridwidth = 2;
        rightCard.add(rightTitle, gbc);
        gbc.gridx = 2; gbc.gridwidth = 1; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.EAST;
        rightCard.add(rightBadge, gbc);

        // 时间字符串
        JLabel dateStrLabel = new JLabel("时间字符串");
        dateStrLabel.setFont(UIUtils.plainFont().deriveFont(12f));
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridy = 1; gbc.gridx = 0; gbc.gridwidth = 3; gbc.weightx = 1.0;
        rightCard.add(dateStrLabel, gbc);

        JTextField dateStrInput = new JTextField();
        dateStrInput.setFont(new Font("Monospaced", Font.PLAIN, 14));
        dateStrInput.putClientProperty("JComponent.roundRect", true);
        // 初始化为当前时间
        LocalDateTime initLdt = LocalDateTime.now();
        dateStrInput.setText(initLdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        dateStrInput.setPreferredSize(new Dimension(dateStrInput.getPreferredSize().width, 32));

        gbc.gridy = 2;
        rightCard.add(dateStrInput, gbc);

        // 指定时区
        JLabel targetTzLabel = new JLabel("指定时区");
        targetTzLabel.setFont(UIUtils.plainFont().deriveFont(12f));
        gbc.gridy = 3;
        rightCard.add(targetTzLabel, gbc);

        JComboBox<String> rightTzCombo = new JComboBox<>(buildTimeZones());
        rightTzCombo.setEditable(true);
        rightTzCombo.putClientProperty("JComponent.roundRect", true);
        rightTzCombo.setPreferredSize(new Dimension(rightTzCombo.getPreferredSize().width, 32));

        JButton setNowBtn = UIUtils.button("设为当前", 80);
        setNowBtn.setMargin(new Insets(2, 4, 2, 4));
        setNowBtn.putClientProperty("JComponent.roundRect", true);
        setNowBtn.addActionListener(e -> {
            String pattern = ((String) fmtCombo.getSelectedItem()).trim();
            dateStrInput.setText(LocalDateTime.now().format(DateTimeFormatter.ofPattern(pattern)));
        });

        JButton clearBtn = new JButton("🗑");
        clearBtn.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 14));
        clearBtn.putClientProperty("JButton.buttonType", "toolBarButton");
        clearBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        clearBtn.setToolTipText("清空输入");
        clearBtn.setPreferredSize(new Dimension(32, 32));
        clearBtn.addActionListener(e -> dateStrInput.setText(""));

        JPanel tzControlPanel = new JPanel(new BorderLayout(6, 0));
        tzControlPanel.setOpaque(false);
        tzControlPanel.add(rightTzCombo, BorderLayout.CENTER);
        
        JPanel rightActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        rightActions.setOpaque(false);
        rightActions.add(setNowBtn);
        rightActions.add(clearBtn);
        tzControlPanel.add(rightActions, BorderLayout.EAST);

        gbc.gridy = 4;
        rightCard.add(tzControlPanel, gbc);

        // 转换结果
        JLabel rightResLabel = new JLabel("转换结果 (Unix)");
        rightResLabel.setFont(UIUtils.plainFont().deriveFont(12f));
        gbc.gridy = 5;
        rightCard.add(rightResLabel, gbc);

        // 两个输出框：秒戳和毫秒戳
        JTextField secResultField = new JTextField();
        secResultField.setEditable(false);
        secResultField.setFont(new Font("Monospaced", Font.BOLD, 13));
        secResultField.putClientProperty("JComponent.roundRect", true);

        JTextField msResultField = new JTextField();
        msResultField.setEditable(false);
        msResultField.setFont(new Font("Monospaced", Font.BOLD, 13));
        msResultField.putClientProperty("JComponent.roundRect", true);

        // 主题自适应的轻量高亮背景容器
        class StyledResultBox extends JPanel {
            private final JTextField field;
            private final String suffixText;

            public StyledResultBox(JTextField field, String suffixText) {
                super(new BorderLayout(4, 0));
                this.field = field;
                this.suffixText = suffixText;
                field.setBorder(null);
                field.setOpaque(false);
                add(field, BorderLayout.CENTER);
                if (suffixText != null && !suffixText.isEmpty()) {
                    JLabel sLabel = new JLabel(suffixText);
                    sLabel.setFont(UIUtils.plainFont().deriveFont(10f));
                    sLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
                    add(sLabel, BorderLayout.WEST);
                }
                JButton copyBtn = new JButton("❐");
                copyBtn.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 12));
                copyBtn.putClientProperty("JButton.buttonType", "toolBarButton");
                copyBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
                copyBtn.addActionListener(e -> {
                    if (!field.getText().isEmpty()) {
                        UIUtils.copyToClipboard(field.getText());
                        timeIndicatorToast(copyBtn, "已复制");
                    }
                });
                add(copyBtn, BorderLayout.EAST);
                setPreferredSize(new Dimension(getPreferredSize().width, 32));
                updateUI();
            }

            @Override
            public void updateUI() {
                super.updateUI();
                setOpaque(false);
                Color borderCol = UIManager.getColor("Component.borderColor");
                if (borderCol == null) borderCol = Color.GRAY;
                setBorder(new CompoundBorder(
                        new LineBorder(borderCol, 1, true),
                        new EmptyBorder(6, 8, 6, 8)
                ));
            }
        }

        JPanel doubleResultPanel = new JPanel(new GridLayout(1, 2, 8, 0));
        doubleResultPanel.setOpaque(false);
        doubleResultPanel.add(new StyledResultBox(secResultField, "S"));
        doubleResultPanel.add(new StyledResultBox(msResultField, "MS"));

        gbc.gridy = 6;
        rightCard.add(doubleResultPanel, gbc);

        // 立即转换按钮
        JButton rightConvertBtn = UIUtils.button("⇄ 立即转换", 120);
        rightConvertBtn.putClientProperty("JComponent.roundRect", true);
        rightConvertBtn.setFont(UIUtils.plainFont().deriveFont(Font.BOLD, 13f));
        rightConvertBtn.addActionListener(e -> {
            try {
                String inputStr = dateStrInput.getText().trim();
                if (inputStr.isEmpty()) return;
                String pattern = ((String) fmtCombo.getSelectedItem()).trim();
                String selectedTz = getSelectedZoneId(rightTzCombo);
                ZoneId zone = ZoneId.of(selectedTz);
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
                LocalDateTime ldt = LocalDateTime.parse(inputStr, formatter);
                long ms = ldt.atZone(zone).toInstant().toEpochMilli();
                secResultField.setText(String.valueOf(ms / 1000));
                msResultField.setText(String.valueOf(ms));
            } catch (Exception ex) {
                UIUtils.error(root, "解析失败，请检查时间格式串是否与所选格式匹配。");
            }
        });

        gbc.gridy = 7;
        gbc.insets = new Insets(16, 4, 4, 4);
        rightCard.add(rightConvertBtn, gbc);

        // 添加底部垂直占位，使卡片内容顶部对齐
        gbc.gridy = 8;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        rightCard.add(new JPanel() {{ setOpaque(false); }}, gbc);

        mainGrid.add(rightCard);

        // 使用 BorderLayout.NORTH 包裹，防止整个 Grid 垂直居中拉伸
        JPanel centerWrapper = new JPanel(new BorderLayout());
        centerWrapper.setOpaque(false);
        centerWrapper.add(mainGrid, BorderLayout.NORTH);
        root.add(centerWrapper, BorderLayout.CENTER);

        // 开启时间自动更新定时器
        startTimer();

        return root;
    }

    private void updateCurrentTimeLabels() {
        long ms = System.currentTimeMillis();
        if (topMsValueLabel != null) {
            topMsValueLabel.setText(String.valueOf(ms));
        }
        if (topSecValueLabel != null) {
            topSecValueLabel.setText(String.valueOf(ms / 1000));
        }
    }

    private void startTimer() {
        if (timer != null && timer.isRunning()) return;
        timer = new Timer(100, e -> updateCurrentTimeLabels());
        timer.start();
    }

    private void timeIndicatorToast(JButton btn, String toastText) {
        String oldText = btn.getText();
        btn.setText(toastText);
        btn.setEnabled(false);
        Timer toastTimer = new Timer(1000, evt -> {
            btn.setText(oldText);
            btn.setEnabled(true);
        });
        toastTimer.setRepeats(false);
        toastTimer.start();
    }

    private String formatOffsetShort(String offsetStr) {
        if (offsetStr == null || "Z".equals(offsetStr) || "+00:00".equals(offsetStr) || "-00:00".equals(offsetStr) || "00:00".equals(offsetStr)) {
            return "+0";
        }
        try {
            String[] parts = offsetStr.split(":");
            int hours = Integer.parseInt(parts[0]);
            int minutes = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            if (minutes == 0) {
                return (hours >= 0 ? "+" : "") + hours;
            } else {
                return (hours >= 0 ? "+" : "") + hours + ":" + String.format("%02d", minutes);
            }
        } catch (Exception e) {
            return offsetStr;
        }
    }

    private String[] buildTimeZones() {
        List<String> list = new ArrayList<>();
        // 构造本地系统时区
        ZoneId sysId = ZoneId.systemDefault();
        String sysName = sysId.getId();
        ZoneOffset offset = Instant.now().atZone(sysId).getOffset();
        String offsetStr = offset.getId();
        String sysDisplay = sysName + " (UTC" + formatOffsetShort(offsetStr) + ")";
        list.add(sysDisplay);

        // 常见时区
        String[][] common = {
                {"Asia/Shanghai", "+08:00"},
                {"UTC", "+00:00"},
                {"GMT", "+00:00"},
                {"Asia/Tokyo", "+09:00"},
                {"Europe/London", "+00:00"},
                {"America/New_York", "-05:00"},
                {"Europe/Paris", "+01:00"},
                {"Australia/Sydney", "+10:00"}
        };
        for (String[] entry : common) {
            String disp = entry[0] + " (UTC" + formatOffsetShort(entry[1]) + ")";
            if (!entry[0].equals(sysName) && !list.contains(disp)) {
                list.add(disp);
            }
        }
        return list.toArray(new String[0]);
    }

    private String getSelectedZoneId(JComboBox<String> combo) {
        String selected = (String) combo.getSelectedItem();
        if (selected == null) return ZoneId.systemDefault().getId();
        if (selected.contains(" ")) {
            return selected.split(" ")[0];
        }
        return selected;
    }
}

