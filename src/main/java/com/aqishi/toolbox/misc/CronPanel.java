package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * Cron 表达式解析器面板。
 */
public class CronPanel extends ToolPanel {

    public CronPanel() {
        super("dev", "cron.parser",
                "Cron", "定时", "调度", "表达式",
                "Cron表达式", "定时任务", "crontab");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // ===== 顶部：输入 =====
        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        
        JLabel label = new JLabel("Cron 表达式:");
        label.setFont(UIUtils.plainFont());
        JTextField input = new JTextField("0 */5 * * * ?");
        input.setFont(UIUtils.monoFont());
        input.setPreferredSize(new Dimension(0, 32));
        
        JButton btn = UIUtils.button("解析", 80);
        
        JPanel inputRow = new JPanel(new BorderLayout(6, 0));
        inputRow.add(label, BorderLayout.WEST);
        inputRow.add(input, BorderLayout.CENTER);
        inputRow.add(btn, BorderLayout.EAST);
        
        top.add(inputRow, BorderLayout.NORTH);
        
        JLabel desc = new JLabel("格式说明：[秒] 分 时 天 月 周（支持 5 位或 6 位，例如 */5 * * * *）");
        desc.setFont(UIUtils.plainFont().deriveFont(11f));
        desc.setForeground(UIManager.getColor("Label.disabledForeground"));
        top.add(desc, BorderLayout.SOUTH);
        
        root.add(top, BorderLayout.NORTH);

        // ===== 中间：解析结果 =====
        JTextArea out = new JTextArea();
        out.setFont(UIUtils.monoFont());
        out.setEditable(false);
        JScrollPane scroll = UIUtils.scrollText(out, "解析及未来执行时间");
        root.add(scroll, BorderLayout.CENTER);

        btn.addActionListener(e -> {
            try {
                String cron = input.getText().trim();
                List<Date> dates = getNextExecutions(cron, 5);
                StringBuilder sb = new StringBuilder();
                sb.append("表达式: ").append(cron).append("\n\n");
                sb.append("验证状态: 有效\n\n");
                sb.append("未来 5 次的预计执行时间:\n");
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                for (int i = 0; i < dates.size(); i++) {
                    sb.append(String.format("  第 %d 次: %s\n", i + 1, sdf.format(dates.get(i))));
                }
                out.setText(sb.toString());
            } catch (Exception ex) {
                out.setText("解析 Cron 表达式失败:\n" + ex.getMessage());
            }
        });

        // 默认点击一次
        btn.doClick();

        return root;
    }

    private static List<Date> getNextExecutions(String cronExpression, int count) throws Exception {
        String[] fields = cronExpression.trim().split("\\s+");
        if (fields.length != 5 && fields.length != 6) {
            throw new IllegalArgumentException("Cron 表达式必须包含 5 或 6 个字段");
        }

        boolean hasSeconds = fields.length == 6;
        String secField = hasSeconds ? fields[0] : "0";
        String minField = hasSeconds ? fields[1] : fields[0];
        String hourField = hasSeconds ? fields[2] : fields[1];
        String dayField = hasSeconds ? fields[3] : fields[2];
        String monthField = hasSeconds ? fields[4] : fields[3];
        String dowField = hasSeconds ? fields[5] : fields[4];

        Set<Integer> allowedSecs = parseField(secField, 0, 59);
        Set<Integer> allowedMins = parseField(minField, 0, 59);
        Set<Integer> allowedHours = parseField(hourField, 0, 23);
        Set<Integer> allowedDays = parseField(dayField, 1, 31);
        Set<Integer> allowedMonthsCron = parseField(monthField, 1, 12);
        Set<Integer> allowedMonths = new HashSet<>();
        for (int m : allowedMonthsCron) allowedMonths.add(m - 1);

        Set<Integer> allowedDowsCron = parseField(dowField, 0, 7);
        Set<Integer> allowedDows = new HashSet<>();
        for (int dow : allowedDowsCron) {
            if (dow == 0 || dow == 7) {
                allowedDows.add(Calendar.SUNDAY);
            } else {
                allowedDows.add(dow + 1);
            }
        }

        List<Date> results = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.SECOND, 1);
        cal.set(Calendar.MILLISECOND, 0);

        int maxSearches = 100000;
        int searches = 0;

        while (results.size() < count && searches < maxSearches) {
            searches++;

            int sec = cal.get(Calendar.SECOND);
            if (!allowedSecs.contains(sec)) {
                int nextSec = getNextAllowed(sec, allowedSecs);
                if (nextSec < sec) {
                    cal.add(Calendar.MINUTE, 1);
                }
                cal.set(Calendar.SECOND, nextSec);
                continue;
            }

            int min = cal.get(Calendar.MINUTE);
            if (!allowedMins.contains(min)) {
                int nextMin = getNextAllowed(min, allowedMins);
                if (nextMin < min) {
                    cal.add(Calendar.HOUR_OF_DAY, 1);
                }
                cal.set(Calendar.MINUTE, nextMin);
                cal.set(Calendar.SECOND, getMin(allowedSecs));
                continue;
            }

            int hour = cal.get(Calendar.HOUR_OF_DAY);
            if (!allowedHours.contains(hour)) {
                int nextHour = getNextAllowed(hour, allowedHours);
                if (nextHour < hour) {
                    cal.add(Calendar.DAY_OF_MONTH, 1);
                }
                cal.set(Calendar.HOUR_OF_DAY, nextHour);
                cal.set(Calendar.MINUTE, getMin(allowedMins));
                cal.set(Calendar.SECOND, getMin(allowedSecs));
                continue;
            }

            int day = cal.get(Calendar.DAY_OF_MONTH);
            int month = cal.get(Calendar.MONTH);
            int dow = cal.get(Calendar.DAY_OF_WEEK);

            if (!allowedMonths.contains(month)) {
                int nextMonth = getNextAllowed(month, allowedMonths);
                if (nextMonth < month) {
                    cal.add(Calendar.YEAR, 1);
                }
                cal.set(Calendar.MONTH, nextMonth);
                cal.set(Calendar.DAY_OF_MONTH, 1);
                cal.set(Calendar.HOUR_OF_DAY, getMin(allowedHours));
                cal.set(Calendar.MINUTE, getMin(allowedMins));
                cal.set(Calendar.SECOND, getMin(allowedSecs));
                continue;
            }

            boolean dayMatches = allowedDays.contains(day);
            boolean dowMatches = allowedDows.contains(dow);

            boolean dayIsWildcard = dayField.equals("*") || dayField.equals("?");
            boolean dowIsWildcard = dowField.equals("*") || dowField.equals("?");

            boolean dateMatches;
            if (!dayIsWildcard && !dowIsWildcard) {
                dateMatches = dayMatches || dowMatches;
            } else {
                dateMatches = dayMatches && dowMatches;
            }

            if (!dateMatches) {
                cal.add(Calendar.DAY_OF_MONTH, 1);
                cal.set(Calendar.HOUR_OF_DAY, getMin(allowedHours));
                cal.set(Calendar.MINUTE, getMin(allowedMins));
                cal.set(Calendar.SECOND, getMin(allowedSecs));
                continue;
            }

            results.add(cal.getTime());
            cal.add(Calendar.SECOND, 1);
        }

        if (results.isEmpty() && searches >= maxSearches) {
            throw new IllegalStateException("未能匹配到 Cron 执行周期。");
        }

        return results;
    }

    private static Set<Integer> parseField(String field, int min, int max) {
        Set<Integer> values = new TreeSet<>();
        if (field.equals("*") || field.equals("?")) {
            for (int i = min; i <= max; i++) values.add(i);
            return values;
        }
        String[] parts = field.split(",");
        for (String part : parts) {
            if (part.contains("/")) {
                String[] stepParts = part.split("/");
                String range = stepParts[0];
                int step = Integer.parseInt(stepParts[1]);
                int start = min;
                int end = max;
                if (!range.equals("*")) {
                    if (range.contains("-")) {
                        String[] rangeParts = range.split("-");
                        start = Integer.parseInt(rangeParts[0]);
                        end = Integer.parseInt(rangeParts[1]);
                    } else {
                        start = Integer.parseInt(range);
                    }
                }
                for (int i = start; i <= end; i += step) {
                    if (i >= min && i <= max) values.add(i);
                }
            } else if (part.contains("-")) {
                String[] rangeParts = part.split("-");
                int start = Integer.parseInt(rangeParts[0]);
                int end = Integer.parseInt(rangeParts[1]);
                for (int i = start; i <= end; i++) {
                    if (i >= min && i <= max) values.add(i);
                }
            } else {
                int val = Integer.parseInt(part);
                if (val >= min && val <= max) values.add(val);
            }
        }
        return values;
    }

    private static int getNextAllowed(int current, Set<Integer> allowed) {
        for (int val : allowed) {
            if (val >= current) return val;
        }
        return getMin(allowed);
    }

    private static int getMin(Set<Integer> allowed) {
        return allowed.iterator().next();
    }
}
