package com.aqishi.toolbox.convert;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * 时间戳 ↔ 日期互转面板，支持秒/毫秒、自定义格式与时区。
 */
public class TimePanel extends ToolPanel {

    public TimePanel() {
        super("转换", "时间戳转换",
                "Unix", "Timestamp", "时间戳", "日期转换", "时区",
                "毫秒", "秒戳", "DateTime", "时间格式化");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 10));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // ===== 当前时间快速区 =====
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JLabel nowMs = new JLabel();
        JLabel nowSec = new JLabel();
        JButton refresh = UIUtils.button("刷新当前", 100);
        refresh.addActionListener(e -> {
            long ms = System.currentTimeMillis();
            nowMs.setText("毫秒戳: " + ms);
            nowSec.setText("秒戳: " + (ms / 1000));
        });
        top.add(refresh); top.add(nowMs); top.add(nowSec);
        root.add(top, BorderLayout.NORTH);
        refresh.doClick();

        // ===== 转换区 =====
        JPanel center = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        c.fill = GridBagConstraints.HORIZONTAL;

        JTextField tsInput = new JTextField(String.valueOf(System.currentTimeMillis() / 1000));
        JComboBox<String> unit = new JComboBox<>(new String[]{"秒", "毫秒"});
        JTextField fmt = new JTextField("yyyy-MM-dd HH:mm:ss");
        JComboBox<String> tz = new JComboBox<>(new String[]{"Asia/Shanghai", "UTC", "America/New_York", "Europe/London"});
        JTextField dateOutput = new JTextField();
        dateOutput.setEditable(false);

        c.gridx = 0; c.gridy = 0; center.add(new JLabel("时间戳"), c);
        c.gridx = 1; c.weightx = 1; center.add(tsInput, c);
        c.gridx = 2; c.weightx = 0; center.add(unit, c);

        c.gridx = 0; c.gridy = 1; c.weightx = 0; center.add(new JLabel("格式"), c);
        c.gridx = 1; c.weightx = 1; center.add(fmt, c);
        c.gridx = 2; c.weightx = 0; center.add(tz, c);

        c.gridx = 0; c.gridy = 2; center.add(new JLabel("日期结果"), c);
        c.gridx = 1; c.gridwidth = 2; c.weightx = 1; center.add(dateOutput, c);

        JButton toDate = UIUtils.button("→ 日期", 100);
        toDate.addActionListener(e -> {
            try {
                long ts = Long.parseLong(tsInput.getText().trim());
                long ms = "秒".equals(unit.getSelectedItem()) ? ts * 1000 : ts;
                ZoneId zone = ZoneId.of((String) tz.getSelectedItem());
                ZonedDateTime zdt = Instant.ofEpochMilli(ms).atZone(zone);
                dateOutput.setText(zdt.format(DateTimeFormatter.ofPattern(fmt.getText().trim())));
            } catch (Exception ex) {
                UIUtils.error(root, "转换失败：" + ex.getMessage());
            }
        });
        c.gridx = 0; c.gridy = 3; c.gridwidth = 3; center.add(toDate, c);

        // ===== 反向：日期 → 时间戳 =====
        JTextField dateInput = new JTextField();
        JTextField tsOutput = new JTextField();
        tsOutput.setEditable(false);

        c.gridwidth = 1;
        c.gridx = 0; c.gridy = 4; c.weightx = 0; center.add(new JLabel("日期字符串"), c);
        c.gridx = 1; c.weightx = 1; c.gridwidth = 2; center.add(dateInput, c);

        c.gridwidth = 1;
        c.gridx = 0; c.gridy = 5; c.weightx = 0; center.add(new JLabel("时间戳结果"), c);
        c.gridx = 1; c.weightx = 1; c.gridwidth = 2; center.add(tsOutput, c);

        JButton toTs = UIUtils.button("→ 时间戳", 110);
        toTs.addActionListener(e -> {
            try {
                ZoneId zone = ZoneId.of((String) tz.getSelectedItem());
                DateTimeFormatter f = DateTimeFormatter.ofPattern(fmt.getText().trim());
                LocalDateTime ldt = LocalDateTime.parse(dateInput.getText().trim(), f);
                long ms = ldt.atZone(zone).toInstant().toEpochMilli();
                tsOutput.setText("秒".equals(unit.getSelectedItem())
                        ? String.valueOf(ms / 1000) : String.valueOf(ms));
            } catch (Exception ex) {
                UIUtils.error(root, "解析失败：" + ex.getMessage());
            }
        });
        c.gridx = 0; c.gridy = 6; c.gridwidth = 3; center.add(toTs, c);

        root.add(center, BorderLayout.CENTER);

        // 兼容旧版 SimpleDateFormat 警告消除：仅作占位，未实际使用
        new SimpleDateFormat();
        return root;
    }
}
