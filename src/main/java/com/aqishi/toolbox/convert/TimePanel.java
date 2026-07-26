package com.aqishi.toolbox.convert;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
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
 * 按照新版 UI 设计重构：卡片纵向堆叠（当前时间 / 时间戳换算 / 时间换算），整体可滚动。
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
        JPanel root = Layouts.page();

        // ==========================================
        // 1. 当前时间卡片
        // ==========================================
        topMsValueLabel = new JLabel("0000000000000");
        topMsValueLabel.setFont(Tokens.fontMono().deriveFont(Font.BOLD, 22f));
        topMsValueLabel.setForeground(Tokens.accent());

        topSecValueLabel = new JLabel("0000000000");
        topSecValueLabel.setFont(Tokens.fontMono().deriveFont(Font.BOLD, 22f));
        topSecValueLabel.setForeground(Tokens.foreground());

        final JButton refreshBtn = Buttons.secondary("↻ 刷新当前");
        final JButton copyTopBtn = Buttons.secondary("❐ 复制");
        refreshBtn.addActionListener(e -> updateCurrentTimeLabels());
        copyTopBtn.addActionListener(e -> {
            UIUtils.copyToClipboard(topMsValueLabel.getText());
            timeIndicatorToast(copyTopBtn, "已复制");
        });

        Card currentCard = Card.titled("当前时间 (CURRENT TIME)");
        // 两个读数等宽并列，刷新/复制走卡片标题右侧，省掉原来那一列悬空的按钮
        currentCard.setContent(Layouts.columns(Tokens.SPACE_XL,
                valueBlock("Unix 毫秒 (ms)", topMsValueLabel),
                valueBlock("Unix 秒 (s)", topSecValueLabel)));
        currentCard.addHeaderAction(refreshBtn);
        currentCard.addHeaderAction(copyTopBtn);

        // ==========================================
        // 2. 时间戳 → 时间
        // ==========================================
        final JTextField tsInput = Fields.mono(String.valueOf(System.currentTimeMillis() / 1000));

        final JComboBox<String> unitCombo = Fields.combo(new String[]{"秒 (s)", "毫秒 (ms)"});
        unitCombo.addActionListener(e -> {
            boolean isMs = unitCombo.getSelectedIndex() == 1;
            long now = System.currentTimeMillis();
            tsInput.setText(String.valueOf(isMs ? now : now / 1000));
        });

        final JComboBox<String> fmtCombo = Fields.combo(new String[]{
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss.SSS",
                "yyyy/MM/dd HH:mm:ss",
                "yyyy年MM月dd日 HH:mm:ss",
                "yyyy-MM-dd",
                "HH:mm:ss"
        });
        fmtCombo.setEditable(true);

        final JComboBox<String> tzCombo = Fields.combo(buildTimeZones());
        tzCombo.setEditable(true);

        final JTextField leftResultField = Fields.mono("");
        leftResultField.setEditable(false);
        leftResultField.setFont(Tokens.fontMono().deriveFont(Font.BOLD, 14f));

        final JButton copyLeftResultBtn = Buttons.ghost("❐");
        copyLeftResultBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        copyLeftResultBtn.addActionListener(e -> {
            if (!leftResultField.getText().isEmpty()) {
                UIUtils.copyToClipboard(leftResultField.getText());
                timeIndicatorToast(copyLeftResultBtn, "已复制");
            }
        });

        JButton leftConvertBtn = Buttons.primary("⇄ 立即转换");
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

        // 每项各占一行：标签列右对齐，输入列统一拉伸，比原来左右两栏挤在一起更好读
        FormGrid tsForm = new FormGrid();
        tsForm.row("Unix 时间戳", tsInput);
        tsForm.row("单位", unitCombo);
        tsForm.row("格式", fmtCombo);
        tsForm.row("时区", tzCombo);
        tsForm.row("转换结果", leftResultField, copyLeftResultBtn);

        Card tsCard = Card.titled("时间戳 ➔ 时间", "UNIX TO HUMAN");
        tsCard.setContent(tsForm);
        tsCard.addHeaderAction(leftConvertBtn);

        // ==========================================
        // 3. 时间 → 时间戳
        // ==========================================
        final JTextField dateStrInput = Fields.mono("");
        // 初始化为当前时间
        LocalDateTime initLdt = LocalDateTime.now();
        dateStrInput.setText(initLdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        final JComboBox<String> rightTzCombo = Fields.combo(buildTimeZones());
        rightTzCombo.setEditable(true);

        JButton setNowBtn = Buttons.secondary("设为当前");
        setNowBtn.addActionListener(e -> {
            String pattern = ((String) fmtCombo.getSelectedItem()).trim();
            dateStrInput.setText(LocalDateTime.now().format(DateTimeFormatter.ofPattern(pattern)));
        });

        // 用 ghost 而不是 compact：图标字形在缺字体的环境下会被 32px 的方形按钮截成省略号
        JButton clearBtn = Buttons.ghost("🗑");
        clearBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        clearBtn.setToolTipText("清空输入");
        clearBtn.addActionListener(e -> dateStrInput.setText(""));

        // 两个按钮都作用于「时间字符串」，跟着它排在同一行行尾
        JPanel dateStrActions = Layouts.box(Tokens.SPACE_XS, 0);
        dateStrActions.add(setNowBtn, BorderLayout.CENTER);
        dateStrActions.add(clearBtn, BorderLayout.EAST);

        final JTextField secResultField = Fields.mono("");
        secResultField.setEditable(false);
        secResultField.setFont(Tokens.fontMono().deriveFont(Font.BOLD, 13f));

        final JTextField msResultField = Fields.mono("");
        msResultField.setEditable(false);
        msResultField.setFont(Tokens.fontMono().deriveFont(Font.BOLD, 13f));

        JPanel doubleResultPanel = Layouts.columns(Tokens.SPACE_SM,
                resultBox(secResultField, "S"),
                resultBox(msResultField, "MS"));

        JButton rightConvertBtn = Buttons.primary("⇄ 立即转换");
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

        FormGrid humanForm = new FormGrid();
        humanForm.row("时间字符串", dateStrInput, dateStrActions);
        humanForm.row("指定时区", rightTzCombo);
        humanForm.row("转换结果 (Unix)", doubleResultPanel);

        Card humanCard = Card.titled("时间 ➔ 时间戳", "HUMAN TO UNIX");
        humanCard.setContent(humanForm);
        humanCard.addHeaderAction(rightConvertBtn);

        // ==========================================
        // 4. 三张卡片纵向堆叠后放进滚动容器
        // ==========================================
        // 卡片总高超过 500px，窗口压到最小高度时需要能滚动，而不是把最后一张卡片切掉
        JScrollPane scroll = Fields.scroll(
                Layouts.stack(Tokens.SPACE_LG, currentCard, tsCard, humanCard));
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        root.add(scroll, BorderLayout.CENTER);

        // 开启时间自动更新定时器
        startTimer();

        return root;
    }

    /** 说明文字在上、大号读数在下的竖排块，用于当前时间卡片 */
    private static JPanel valueBlock(String caption, JLabel value) {
        JPanel block = Layouts.box(0, Tokens.SPACE_XS);
        block.add(Fields.caption(caption), BorderLayout.NORTH);
        block.add(value, BorderLayout.CENTER);
        return block;
    }

    /** 只读结果框：单位前缀 + 数值 + 复制按钮，取代原先自绘描边的 StyledResultBox */
    private JPanel resultBox(final JTextField field, String suffixText) {
        JPanel box = Layouts.box(Tokens.SPACE_XS, 0);
        if (suffixText != null && !suffixText.isEmpty()) {
            box.add(Fields.caption(suffixText), BorderLayout.WEST);
        }
        box.add(field, BorderLayout.CENTER);

        final JButton copyBtn = Buttons.ghost("❐");
        copyBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        copyBtn.addActionListener(e -> {
            if (!field.getText().isEmpty()) {
                UIUtils.copyToClipboard(field.getText());
                timeIndicatorToast(copyBtn, "已复制");
            }
        });
        box.add(copyBtn, BorderLayout.EAST);
        return box;
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

