package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.ActionBar;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;

import javax.swing.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * Cron 表达式解析器及可视化配置面板（支持双向绑定与弹出多选）。
 */
public class CronPanel extends ToolPanel {

    private JTextField input;
    private JButton btn;
    private JTextArea out;

    private CronFieldPanel secPanel;
    private CronFieldPanel minPanel;
    private CronFieldPanel hourPanel;
    private CronFieldPanel dayPanel;
    private CronFieldPanel monthPanel;
    private CronFieldPanel weekPanel;

    private boolean isRebuilding = false;

    public CronPanel() {
        super("dev", "cron.parser",
                "Cron", "定时", "调度", "表达式",
                "Cron表达式", "定时任务", "crontab");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // ===== 表达式卡片 =====
        // 这个输入框同时是「手工录入」和「可视化配置的表达式预览」——
        // 下面每改一个字段都会回写到这里，所以它单独成卡放在最上方，格式说明降为副标题。
        input = Fields.mono("0 */5 * * * ?");
        btn = Buttons.primary("解析");

        Card exprCard = Card.titled("Cron 表达式",
                "格式说明：[秒] 分 时 天 月 周（支持 5 位 or 6 位，例如 */5 * * * *）");
        exprCard.setContent(input);
        exprCard.addHeaderAction(btn);

        // ===== 可视化配置：六个字段各占一个标签页 =====
        // 定义状态变更监听器
        CronFieldPanel.FieldChangeListener changeListener = source -> {
            // “日” 与 “周” 的互斥处理
            if ("day".equals(source.getType())) {
                if (!source.isNoneSelected()) {
                    weekPanel.setNone();
                }
            } else if ("week".equals(source.getType())) {
                if (!source.isNoneSelected()) {
                    dayPanel.setNone();
                }
            }
            rebuildCronExpression();
        };

        // 实例化各个维度的配置面板
        secPanel = new CronFieldPanel("sec", "秒", 0, 59, "秒", changeListener);
        minPanel = new CronFieldPanel("min", "分", 0, 59, "分", changeListener);
        hourPanel = new CronFieldPanel("hour", "时", 0, 23, "时", changeListener);
        dayPanel = new CronFieldPanel("day", "日", 1, 31, "日", changeListener);
        monthPanel = new CronFieldPanel("month", "月", 1, 12, "月", changeListener);
        weekPanel = new CronFieldPanel("week", "周", 1, 7, "", changeListener);

        JTabbedPane configTabs = new JTabbedPane();
        // 标签外观（下划线样式、行高）由全局主题统一决定，这里只去掉自带描边
        configTabs.setBorder(null);
        configTabs.addTab("秒", secPanel);
        configTabs.addTab("分", minPanel);
        configTabs.addTab("时", hourPanel);
        configTabs.addTab("日", dayPanel);
        configTabs.addTab("月", monthPanel);
        configTabs.addTab("周", weekPanel);

        // ===== 结果卡片：未来执行时间列表 =====
        out = Fields.output(16, 48);
        Card resultCard = Card.flush("解析及未来执行时间");
        resultCard.setContent(Fields.scroll(out));
        // 结果区的高度下限：配置区标签页内容很高，不给下限的话分隔条初始就会把它压没
        resultCard.setMinimumSize(new Dimension(0, Tokens.CONTROL_HEIGHT * 4));

        root.add(exprCard, BorderLayout.NORTH);
        // 配置区（六个标签页）与结果区都需要真实高度，谁也不该被压成一条缝，
        // 因此用可拖动的细分隔条分配剩余空间，而不是把结果区钉死在 SOUTH。
        root.add(Layouts.splitVertical(configTabs, resultCard, 0.45), BorderLayout.CENTER);

        // ===== 绑定解析按钮事件 =====
        btn.addActionListener(e -> {
            try {
                String cron = input.getText().trim();

                // 点击解析时反向同步到配置面板
                if (!isRebuilding) {
                    try {
                        parseAndSyncToUI(cron);
                    } catch (Exception ex) {
                        // 忽略反解析异常，避免阻断正常的解析失败显示
                    }
                }

                List<Date> dates = getNextExecutions(cron, 15);
                StringBuilder sb = new StringBuilder();
                sb.append("表达式: ").append(cron).append("\n\n");
                sb.append("验证状态: 有效\n\n");
                sb.append("未来 15 次的预计执行时间:\n");
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                for (int i = 0; i < dates.size(); i++) {
                    sb.append(String.format("  第 %d 次: %s\n", i + 1, sdf.format(dates.get(i))));
                }
                out.setText(sb.toString());
            } catch (Exception ex) {
                out.setText("解析 Cron 表达式失败:\n" + ex.getMessage());
            }
        });

        // 初始化各个面板的默认值并完成首次解析
        initDefaultUIValues();

        return root;
    }

    private void initDefaultUIValues() {
        // 默认匹配: 0 */5 * * * ?
        secPanel.initDefault("spec", 0);
        minPanel.initDefault("cycle", 0, 5);
        hourPanel.initDefault("any");
        dayPanel.initDefault("any");
        monthPanel.initDefault("any");
        weekPanel.initDefault("none");

        rebuildCronExpression();
    }

    private void rebuildCronExpression() {
        if (isRebuilding) return;
        isRebuilding = true;
        try {
            String sec = secPanel.getFieldValue();
            String min = minPanel.getFieldValue();
            String hour = hourPanel.getFieldValue();
            String day = dayPanel.getFieldValue();
            String month = monthPanel.getFieldValue();
            String week = weekPanel.getFieldValue();

            String expr = sec + " " + min + " " + hour + " " + day + " " + month + " " + week;
            input.setText(expr);
            btn.doClick();
        } finally {
            isRebuilding = false;
        }
    }

    private void parseAndSyncToUI(String cron) {
        String[] fields = cron.trim().split("\\s+");
        if (fields.length != 5 && fields.length != 6) {
            return;
        }
        boolean hasSeconds = fields.length == 6;
        String secVal = hasSeconds ? fields[0] : "0";
        String minVal = hasSeconds ? fields[1] : fields[0];
        String hourVal = hasSeconds ? fields[2] : fields[1];
        String dayVal = hasSeconds ? fields[3] : fields[2];
        String monthVal = hasSeconds ? fields[4] : fields[3];
        String weekVal = hasSeconds ? fields[5] : fields[4];

        // 临时阻断重构联动，防止组件设值时导致再次拼接覆盖
        isRebuilding = true;
        try {
            secPanel.setFieldValue(secVal);
            minPanel.setFieldValue(minVal);
            hourPanel.setFieldValue(hourVal);
            dayPanel.setFieldValue(dayVal);
            monthPanel.setFieldValue(monthVal);
            weekPanel.setFieldValue(weekVal);
        } finally {
            isRebuilding = false;
        }
    }

    // ==========================================
    // 可视化配置子面板内部类
    // ==========================================
    private static class CronFieldPanel extends JPanel {
        private final String type;
        private final int min;
        private final int max;

        private final JRadioButton anyRadio;
        private final JRadioButton noneRadio;
        private final JRadioButton cycleRadio;
        private final JRadioButton rangeRadio;
        private final JRadioButton specRadio;

        private JSpinner cycleStartSpinner;
        private JSpinner cycleStepSpinner;
        private JSpinner rangeFromSpinner;
        private JSpinner rangeToSpinner;
        private JSpinner rangeStepSpinner; // 区间步长

        private JComboBox<String> weekStartCombo;
        private JComboBox<String> weekFromCombo;
        private JComboBox<String> weekToCombo;

        // 具体数值多选：等宽网格 + 全选/清空 + 已选摘要
        private JPanel specGrid;
        private JCheckBox[] specCheckBoxes;
        private JTextField specTextField; // 用于日的指定文本输入框
        private JLabel specSummary;
        private JButton selectAllBtn;
        private JButton clearBtn;

        private boolean isUpdating = false;
        private final FieldChangeListener changeListener;

        public interface FieldChangeListener {
            void onChange(CronFieldPanel source);
        }

        public CronFieldPanel(String type, String label, int min, int max, String unit, FieldChangeListener changeListener) {
            this.type = type;
            this.min = min;
            this.max = max;
            this.changeListener = changeListener;

            // 初始化 Radio（字号、焦点框统一由 Fields 决定）
            anyRadio = Fields.radio("每" + label + " (*)", false);
            noneRadio = Fields.radio("不指定 (?)", false);
            cycleRadio = Fields.radio("周期：", false);
            rangeRadio = Fields.radio("区间：", false);
            specRadio = Fields.radio("指定具体数值：", false);

            // 根据类型初始化输入控制组件
            if ("week".equals(type)) {
                String[] weekDays = {"周一 (1)", "周二 (2)", "周三 (3)", "周四 (4)", "周五 (5)", "周六 (6)", "周日 (7)"};
                // 星期名长度固定，给下拉框一个固定宽度，避免被行内其它控件挤扁
                weekStartCombo = Fields.combo(weekDays, 110);
                weekFromCombo = Fields.combo(weekDays, 110);
                weekToCombo = Fields.combo(weekDays, 110);

                cycleStepSpinner = Fields.spinner(1, 1, 7, 1);
            } else {
                cycleStartSpinner = Fields.spinner(min, min, max, 1);
                cycleStepSpinner = Fields.spinner(1, 1, max - min > 0 ? max - min : 1, 1);
                rangeFromSpinner = Fields.spinner(min, min, max, 1);
                rangeToSpinner = Fields.spinner(min, min, max, 1);
                rangeStepSpinner = Fields.spinner(1, 1, max - min > 0 ? max - min : 1, 1);
            }

            if ("day".equals(type)) {
                specTextField = Fields.mono("1,15");
                specTextField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                    public void insertUpdate(javax.swing.event.DocumentEvent e) { if (specRadio.isSelected()) triggerChange(); }
                    public void removeUpdate(javax.swing.event.DocumentEvent e) { if (specRadio.isSelected()) triggerChange(); }
                    public void changedUpdate(javax.swing.event.DocumentEvent e) { if (specRadio.isSelected()) triggerChange(); }
                });
            } else {
                specCheckBoxes = new JCheckBox[max - min + 1];
                initSpecValues();
            }

            initUI(label, unit);
        }

        /**
         * 具体数值选择器：复选框网格直接铺在标签页里。
         *
         * <p>用固定列数的 {@code GridLayout} 而不是流式换行行：这一组是等宽的纯数字条目，
         * 定列后每列上下对齐；而且它的首选高度与容器宽度无关，卡片、滚动区才能算出正确高度。</p>
         */
        private void initSpecValues() {
            // 列数按条目宽度定：秒 / 分 是两位数字且有 60 个，铺 15 列刚好 4 行；
            // 时只有 24 个，12 列 2 行；月、周条目是中文，列数取少免得挤。
            int cols = 15;
            if ("week".equals(type)) {
                cols = 7;
            } else if ("month".equals(type)) {
                cols = 6;
            } else if ("hour".equals(type)) {
                cols = 12;
            }

            specGrid = new JPanel(new GridLayout(0, cols, Tokens.SPACE_SM, Tokens.SPACE_XS));
            specGrid.setOpaque(false);

            for (int i = min; i <= max; i++) {
                String text;
                if ("week".equals(type)) {
                    text = getWeekName(i);
                } else if ("month".equals(type)) {
                    text = i + "月";
                } else {
                    text = String.format("%02d", i);
                }
                JCheckBox cb = Fields.check(text, false);
                if (!"week".equals(type) && !"month".equals(type)) {
                    cb.setFont(Tokens.fontMono()); // 纯数字用等宽字体，网格才对得齐
                }
                final int val = i;
                cb.putClientProperty("value", val);
                specCheckBoxes[i - min] = cb;
                specGrid.add(cb);

                cb.addActionListener(e -> {
                    specRadio.setSelected(true);
                    updateSelectButtonText();
                    updateEnabledState();
                    triggerChange();
                });
            }

            // 全选与清空：放到卡片标题右侧，不再占一整行
            selectAllBtn = Buttons.secondary("全选");
            clearBtn = Buttons.ghost("清空");

            selectAllBtn.addActionListener(e -> {
                setAllCheckBoxes(true);
                specRadio.setSelected(true);
                updateSelectButtonText();
                updateEnabledState();
                triggerChange();
            });

            clearBtn.addActionListener(e -> {
                setAllCheckBoxes(false);
                specRadio.setSelected(true);
                updateSelectButtonText();
                updateEnabledState();
                triggerChange();
            });

            specSummary = Fields.caption("选择值 (未选)");
        }

        private void initUI(String label, String unit) {
            setLayout(new BorderLayout(0, Tokens.SPACE_LG));
            setOpaque(false);
            // 标签页里再补一层比 page() 薄的内边距，避免与页面外边距叠加过厚
            setBorder(KitBorders.padding(Tokens.SPACE_MD));

            ButtonGroup group = new ButtonGroup();
            group.add(anyRadio);
            group.add(noneRadio);
            group.add(cycleRadio);
            group.add(rangeRadio);
            group.add(specRadio);

            // 五个取值规则是互斥选项，各占整行；行内控件与说明文字穿插成一句话读下来。
            // 每行用 ActionBar（横向 BoxLayout）而不是流式换行行：这一句话不该被拆开，
            // 而且它的最小高度始终只有一行，外层分隔条才敢压缩配置区、把高度让给结果区。
            FormGrid form = new FormGrid();

            // 1. 每X (*)
            form.fullRow(new ActionBar().left(anyRadio));

            // 2. 不指定 (?) (仅日/周显示)
            if ("day".equals(type) || "week".equals(type)) {
                form.fullRow(new ActionBar().left(noneRadio));
            }

            // 3. 周期 (/)
            ActionBar cycleRow = new ActionBar();
            cycleRow.left(cycleRadio);
            cycleRow.left(Fields.label("从第"));
            if ("week".equals(type)) {
                cycleRow.left(weekStartCombo);
            } else {
                cycleRow.left(cycleStartSpinner);
            }
            cycleRow.left(Fields.label(unit + "开始，每"));
            cycleRow.left(cycleStepSpinner);
            cycleRow.left(Fields.label(unit + "执行一次"));
            form.fullRow(cycleRow);

            // 4. 区间 (-) 以及区间周期 (/)
            ActionBar rangeRow = new ActionBar();
            rangeRow.left(rangeRadio);
            rangeRow.left(Fields.label("从"));
            if ("week".equals(type)) {
                rangeRow.left(weekFromCombo);
            } else {
                rangeRow.left(rangeFromSpinner);
            }
            rangeRow.left(Fields.label(unit + "到"));
            if ("week".equals(type)) {
                rangeRow.left(weekToCombo);
            } else {
                rangeRow.left(rangeToSpinner);
            }
            rangeRow.left(Fields.label(unit));
            if (!"week".equals(type)) {
                rangeRow.left(Fields.label("，每隔"));
                rangeRow.left(rangeStepSpinner);
                rangeRow.left(Fields.label(unit + "执行一次"));
            }
            form.fullRow(rangeRow);

            // 5. 指定 (日为文本框录入，其余在同卡片下半部的数值网格里勾选)
            ActionBar specRow = new ActionBar();
            specRow.left(specRadio);
            if ("day".equals(type)) {
                specRow.left(Fields.label("天数输入："));
                specRow.left(specTextField);
            }
            form.fullRow(specRow);

            // 一个字段一张卡：规则在上、具体数值网格在下。
            // 合成一张卡而不是两张，是因为标签页里还要给下面的执行时间列表留高度，
            // 多一张卡就要多付一层标题带 + 内边距 + 卡间距。
            Card fieldCard = Card.titled(label, "取值范围 " + min + " - " + max);

            if (specGrid != null) {
                fieldCard.addHeaderAction(selectAllBtn);
                fieldCard.addHeaderAction(clearBtn);

                // 网格钉在顶部：卡片被拉高时不要把复选框行整体拉散；
                // 外面套滚动区，是为了窗口很矮时也只是出滚动条而不是把行裁掉。
                JPanel gridHolder = Layouts.box();
                gridHolder.add(specGrid, BorderLayout.NORTH);
                JScrollPane gridScroll = Fields.scrollTransparent(gridHolder);
                // 网格本身「不可压缩」的话，外层分隔条会认定配置区必须占满，
                // 把结果区挤成 0 高；这里放开最小高度，窗口矮时出滚动条即可。
                gridScroll.setMinimumSize(new Dimension(0, 0));

                JPanel body = Layouts.box(0, Tokens.SPACE_MD);
                body.add(form, BorderLayout.NORTH);
                body.add(gridScroll, BorderLayout.CENTER);
                fieldCard.setContent(body);
                fieldCard.setFooter(specSummary);
            } else {
                // 日没有数值网格，glue 把选项钉在卡片顶部，剩余空间留白而不是把行距拉开
                form.glue();
                fieldCard.setContent(form);
            }

            add(fieldCard, BorderLayout.CENTER);

            // ===== 绑定交互联动 =====
            anyRadio.addActionListener(e -> { updateEnabledState(); triggerChange(); });
            noneRadio.addActionListener(e -> { updateEnabledState(); triggerChange(); });
            cycleRadio.addActionListener(e -> { updateEnabledState(); triggerChange(); });
            rangeRadio.addActionListener(e -> { updateEnabledState(); triggerChange(); });
            specRadio.addActionListener(e -> { updateEnabledState(); triggerChange(); });

            if (cycleStartSpinner != null) {
                cycleStartSpinner.addChangeListener(e -> { if (cycleRadio.isSelected()) triggerChange(); });
                cycleStepSpinner.addChangeListener(e -> { if (cycleRadio.isSelected()) triggerChange(); });
                rangeFromSpinner.addChangeListener(e -> { if (rangeRadio.isSelected()) triggerChange(); });
                rangeToSpinner.addChangeListener(e -> { if (rangeRadio.isSelected()) triggerChange(); });
                rangeStepSpinner.addChangeListener(e -> { if (rangeRadio.isSelected()) triggerChange(); });
            }

            if (weekStartCombo != null) {
                weekStartCombo.addActionListener(e -> { if (cycleRadio.isSelected()) triggerChange(); });
                weekFromCombo.addActionListener(e -> { if (rangeRadio.isSelected()) triggerChange(); });
                weekToCombo.addActionListener(e -> { if (rangeRadio.isSelected()) triggerChange(); });
            }

            anyRadio.setSelected(true);
            updateEnabledState();
            if (!"day".equals(type)) {
                updateSelectButtonText();
            }
        }

        private void triggerChange() {
            if (changeListener != null && !isUpdating) {
                changeListener.onChange(this);
            }
        }

        private void updateEnabledState() {
            boolean cycleSel = cycleRadio.isSelected();
            boolean rangeSel = rangeRadio.isSelected();
            boolean specSel = specRadio.isSelected();

            if (cycleStartSpinner != null) {
                cycleStartSpinner.setEnabled(cycleSel);
                cycleStepSpinner.setEnabled(cycleSel);
                rangeFromSpinner.setEnabled(rangeSel);
                rangeToSpinner.setEnabled(rangeSel);
                rangeStepSpinner.setEnabled(rangeSel);
            }
            if (weekStartCombo != null) {
                weekStartCombo.setEnabled(cycleSel);
                cycleStepSpinner.setEnabled(cycleSel);
                weekFromCombo.setEnabled(rangeSel);
                weekToCombo.setEnabled(rangeSel);
            }

            if (specTextField != null) {
                specTextField.setEnabled(specSel);
            }

            // 原先靠禁用「选择具体数值...」按钮来锁定这一组，网格内联后改为整组联动，语义不变
            if (specCheckBoxes != null) {
                for (JCheckBox cb : specCheckBoxes) {
                    if (cb != null) cb.setEnabled(specSel);
                }
            }
            if (selectAllBtn != null) {
                selectAllBtn.setEnabled(specSel);
                clearBtn.setEnabled(specSel);
            }
        }

        private void setAllCheckBoxes(boolean selected) {
            if (specCheckBoxes != null) {
                for (JCheckBox cb : specCheckBoxes) {
                    if (cb != null) cb.setSelected(selected);
                }
            }
        }

        /**
         * 刷新「已选」摘要文案（原来写在弹出按钮上，现在写在数值卡片的底部状态条）。
         *
         * <p>方法名沿用旧名，避免改动 {@code setFieldValue} / {@code initDefault} 等取值方法的调用点。</p>
         */
        private void updateSelectButtonText() {
            if ("day".equals(type) || specSummary == null) return;
            StringBuilder sb = new StringBuilder();
            int count = 0;
            if (specCheckBoxes != null) {
                for (JCheckBox cb : specCheckBoxes) {
                    if (cb != null && cb.isSelected()) {
                        if (sb.length() > 0) sb.append(",");
                        if ("week".equals(type)) {
                            sb.append(getWeekName((Integer) cb.getClientProperty("value")));
                        } else {
                            sb.append(cb.getClientProperty("value"));
                        }
                        count++;
                    }
                }
            }
            if (count == 0) {
                specSummary.setText("选择值 (未选)");
            } else {
                String listStr = sb.toString();
                if (listStr.length() > 18) {
                    specSummary.setText("已选 (" + count + "个): " + listStr.substring(0, 15) + "...");
                } else {
                    specSummary.setText("已选 (" + count + "个): " + listStr);
                }
            }
        }

        public String getType() {
            return type;
        }

        public boolean isNoneSelected() {
            return noneRadio != null && noneRadio.isSelected();
        }

        public void setNone() {
            if (noneRadio != null && !noneRadio.isSelected()) {
                isUpdating = true;
                noneRadio.setSelected(true);
                updateEnabledState();
                isUpdating = false;
            }
        }

        public void initDefault(String defaultType, int... specValues) {
            isUpdating = true;
            if ("any".equals(defaultType)) {
                anyRadio.setSelected(true);
            } else if ("none".equals(defaultType)) {
                if (noneRadio != null) noneRadio.setSelected(true);
            } else if ("cycle".equals(defaultType) && specValues.length >= 2) {
                cycleRadio.setSelected(true);
                if (cycleStartSpinner != null) {
                    cycleStartSpinner.setValue(specValues[0]);
                    cycleStepSpinner.setValue(specValues[1]);
                }
            } else if ("spec".equals(defaultType)) {
                specRadio.setSelected(true);
                if ("day".equals(type)) {
                    if (specTextField != null) {
                        StringBuilder sb = new StringBuilder();
                        for (int val : specValues) {
                            if (sb.length() > 0) sb.append(",");
                            sb.append(val);
                        }
                        specTextField.setText(sb.toString());
                    }
                } else {
                    setAllCheckBoxes(false);
                    for (int val : specValues) {
                        if (val >= min && val <= max && specCheckBoxes != null) {
                            specCheckBoxes[val - min].setSelected(true);
                        }
                    }
                    updateSelectButtonText();
                }
            }
            updateEnabledState();
            isUpdating = false;
        }

        public void setFieldValue(String value) {
            isUpdating = true;
            try {
                if ("*".equals(value)) {
                    anyRadio.setSelected(true);
                } else if ("?".equals(value)) {
                    if (noneRadio != null) noneRadio.setSelected(true);
                } else if (value.contains("/")) {
                    String[] parts = value.split("/");
                    String left = parts[0];
                    int step = Integer.parseInt(parts[1]);

                    if (left.contains("-")) {
                        // A-B/C (区间周期)
                        rangeRadio.setSelected(true);
                        String[] range = left.split("-");
                        int from = Integer.parseInt(range[0]);
                        int to = Integer.parseInt(range[1]);

                        if ("week".equals(type)) {
                            if (weekFromCombo != null) weekFromCombo.setSelectedIndex(from - 1);
                            if (weekToCombo != null) weekToCombo.setSelectedIndex(to - 1);
                        } else {
                            if (rangeFromSpinner != null) rangeFromSpinner.setValue(from);
                            if (rangeToSpinner != null) rangeToSpinner.setValue(to);
                            if (rangeStepSpinner != null) rangeStepSpinner.setValue(step);
                        }
                    } else {
                        // A/B (周期)
                        cycleRadio.setSelected(true);
                        int start = "*".equals(left) ? min : Integer.parseInt(left);

                        if ("week".equals(type)) {
                            if (weekStartCombo != null) weekStartCombo.setSelectedIndex(start - 1);
                        } else {
                            if (cycleStartSpinner != null) cycleStartSpinner.setValue(start);
                        }
                        if (cycleStepSpinner != null) cycleStepSpinner.setValue(step);
                    }
                } else if (value.contains("-")) {
                    // A-B (区间)
                    rangeRadio.setSelected(true);
                    String[] range = value.split("-");
                    int from = Integer.parseInt(range[0]);
                    int to = Integer.parseInt(range[1]);

                    if ("week".equals(type)) {
                        if (weekFromCombo != null) weekFromCombo.setSelectedIndex(from - 1);
                        if (weekToCombo != null) weekToCombo.setSelectedIndex(to - 1);
                    } else {
                        if (rangeFromSpinner != null) rangeFromSpinner.setValue(from);
                        if (rangeToSpinner != null) rangeToSpinner.setValue(to);
                        if (rangeStepSpinner != null) rangeStepSpinner.setValue(1);
                    }
                } else {
                    // 指定值
                    specRadio.setSelected(true);
                    if ("day".equals(type)) {
                        if (specTextField != null) {
                            specTextField.setText(value);
                        }
                    } else {
                        setAllCheckBoxes(false);
                        String[] items = value.split(",");
                        for (String item : items) {
                            try {
                                int val = Integer.parseInt(item);
                                if (val >= min && val <= max && specCheckBoxes != null) {
                                    specCheckBoxes[val - min].setSelected(true);
                                }
                            } catch (NumberFormatException ignored) {}
                        }
                        updateSelectButtonText();
                    }
                }
                updateEnabledState();
            } finally {
                isUpdating = false;
            }
        }

        public String getFieldValue() {
            if (anyRadio.isSelected()) {
                return "*";
            }
            if (noneRadio != null && noneRadio.isSelected()) {
                return "?";
            }
            if (cycleRadio.isSelected()) {
                int start;
                if ("week".equals(type)) {
                    start = weekStartCombo.getSelectedIndex() + 1;
                } else {
                    start = (Integer) cycleStartSpinner.getValue();
                }
                int step = (Integer) cycleStepSpinner.getValue();
                return start + "/" + step;
            }
            if (rangeRadio.isSelected()) {
                if ("week".equals(type)) {
                    int from = weekFromCombo.getSelectedIndex() + 1;
                    int to = weekToCombo.getSelectedIndex() + 1;
                    return from + "-" + to;
                } else {
                    int from = (Integer) rangeFromSpinner.getValue();
                    int to = (Integer) rangeToSpinner.getValue();
                    int step = (Integer) rangeStepSpinner.getValue();
                    if (step <= 1) {
                        return from + "-" + to;
                    } else {
                        return from + "-" + to + "/" + step;
                    }
                }
            }
            if (specRadio.isSelected()) {
                if ("day".equals(type)) {
                    if (specTextField != null) {
                        String val = specTextField.getText().replaceAll("\\s+", "");
                        if (val.isEmpty()) {
                            return String.valueOf(min);
                        }
                        return val;
                    }
                } else {
                    StringBuilder sb = new StringBuilder();
                    if (specCheckBoxes != null) {
                        for (JCheckBox cb : specCheckBoxes) {
                            if (cb != null && cb.isSelected()) {
                                if (sb.length() > 0) sb.append(",");
                                sb.append(cb.getClientProperty("value"));
                            }
                        }
                    }
                    if (sb.length() == 0) {
                        return String.valueOf(min);
                    }
                    return sb.toString();
                }
            }
            return "*";
        }

        private String getWeekName(int dow) {
            switch (dow) {
                case 1: return "周一";
                case 2: return "周二";
                case 3: return "周三";
                case 4: return "周四";
                case 5: return "周五";
                case 6: return "周六";
                case 7: return "周日";
                default: return "";
            }
        }
    }

    // ==========================================
    // 核心解析算法 (保持原样)
    // ==========================================
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
