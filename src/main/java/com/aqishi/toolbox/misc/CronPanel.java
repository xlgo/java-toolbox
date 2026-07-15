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
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // ===== 顶部：输入栏 =====
        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        
        JLabel label = new JLabel("Cron 表达式:");
        label.setFont(UIUtils.plainFont());
        input = new JTextField("0 */5 * * * ?");
        input.setFont(UIUtils.monoFont());
        input.setPreferredSize(new Dimension(0, 32));
        
        btn = UIUtils.button("解析", 80);
        
        JPanel inputRow = new JPanel(new BorderLayout(6, 0));
        inputRow.add(label, BorderLayout.WEST);
        inputRow.add(input, BorderLayout.CENTER);
        inputRow.add(btn, BorderLayout.EAST);
        
        top.add(inputRow, BorderLayout.NORTH);
        
        JLabel desc = new JLabel("格式说明：[秒] 分 时 天 月 周（支持 5 位 or 6 位，例如 */5 * * * *）");
        desc.setFont(UIUtils.plainFont().deriveFont(11f));
        desc.setForeground(UIManager.getColor("Label.disabledForeground"));
        top.add(desc, BorderLayout.SOUTH);
        
        root.add(top, BorderLayout.NORTH);

        // ===== 中部：可视化配置与解析结果 =====
        JPanel centerPanel = new JPanel(new BorderLayout(8, 8));

        // 1. 可视化配置 TabbedPane
        JTabbedPane configTabs = new JTabbedPane();
        configTabs.setFont(UIUtils.plainFont().deriveFont(13f));

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

        configTabs.addTab("秒", secPanel);
        configTabs.addTab("分", minPanel);
        configTabs.addTab("时", hourPanel);
        configTabs.addTab("日", dayPanel);
        configTabs.addTab("月", monthPanel);
        configTabs.addTab("周", weekPanel);

        centerPanel.add(configTabs, BorderLayout.CENTER);

        // 2. 解析结果面板
        out = new JTextArea();
        out.setFont(UIUtils.monoFont());
        out.setEditable(false);
        JScrollPane scroll = UIUtils.scrollText(out, "解析及未来执行时间");
        scroll.setPreferredSize(new Dimension(0, 300)); // 给结果区适当增加高度
        centerPanel.add(scroll, BorderLayout.SOUTH);

        root.add(centerPanel, BorderLayout.CENTER);

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

        // 下拉多选相关组件 (弹出式 JPopupMenu)
        private JButton selectButton;
        private JPopupMenu selectPopup;
        private JPanel specGrid;
        private JCheckBox[] specCheckBoxes;
        private JTextField specTextField; // 用于日的指定文本输入框
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

            // 初始化 Radio
            anyRadio = new JRadioButton("每" + label + " (*)");
            anyRadio.setFont(UIUtils.plainFont().deriveFont(13f));
            noneRadio = new JRadioButton("不指定 (?)");
            noneRadio.setFont(UIUtils.plainFont().deriveFont(13f));
            cycleRadio = new JRadioButton("周期：");
            cycleRadio.setFont(UIUtils.plainFont().deriveFont(13f));
            rangeRadio = new JRadioButton("区间：");
            rangeRadio.setFont(UIUtils.plainFont().deriveFont(13f));
            specRadio = new JRadioButton("指定具体数值：");
            specRadio.setFont(UIUtils.plainFont().deriveFont(13f));

            // 根据类型初始化输入控制组件
            if ("week".equals(type)) {
                String[] weekDays = {"周一 (1)", "周二 (2)", "周三 (3)", "周四 (4)", "周五 (5)", "周六 (6)", "周日 (7)"};
                weekStartCombo = new JComboBox<>(weekDays);
                weekFromCombo = new JComboBox<>(weekDays);
                weekToCombo = new JComboBox<>(weekDays);

                weekStartCombo.setFont(UIUtils.plainFont().deriveFont(12f));
                weekFromCombo.setFont(UIUtils.plainFont().deriveFont(12f));
                weekToCombo.setFont(UIUtils.plainFont().deriveFont(12f));

                cycleStepSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 7, 1));
            } else {
                cycleStartSpinner = new JSpinner(new SpinnerNumberModel(min, min, max, 1));
                cycleStepSpinner = new JSpinner(new SpinnerNumberModel(1, 1, max - min > 0 ? max - min : 1, 1));
                rangeFromSpinner = new JSpinner(new SpinnerNumberModel(min, min, max, 1));
                rangeToSpinner = new JSpinner(new SpinnerNumberModel(min, min, max, 1));
                rangeStepSpinner = new JSpinner(new SpinnerNumberModel(1, 1, max - min > 0 ? max - min : 1, 1));

                cycleStartSpinner.setFont(UIUtils.monoFont().deriveFont(12f));
                cycleStepSpinner.setFont(UIUtils.monoFont().deriveFont(12f));
                rangeFromSpinner.setFont(UIUtils.monoFont().deriveFont(12f));
                rangeToSpinner.setFont(UIUtils.monoFont().deriveFont(12f));
                rangeStepSpinner.setFont(UIUtils.monoFont().deriveFont(12f));
            }

            if ("day".equals(type)) {
                specTextField = new JTextField("1,15");
                specTextField.setFont(UIUtils.monoFont().deriveFont(13f));
                specTextField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                    public void insertUpdate(javax.swing.event.DocumentEvent e) { if (specRadio.isSelected()) triggerChange(); }
                    public void removeUpdate(javax.swing.event.DocumentEvent e) { if (specRadio.isSelected()) triggerChange(); }
                    public void changedUpdate(javax.swing.event.DocumentEvent e) { if (specRadio.isSelected()) triggerChange(); }
                });
            } else {
                specCheckBoxes = new JCheckBox[max - min + 1];
                initPopupMenu(label);
            }

            initUI(label, unit);
        }

        private void initPopupMenu(String label) {
            selectButton = new JButton("选择具体数值...");
            selectButton.setFont(UIUtils.plainFont().deriveFont(12f));

            selectPopup = new JPopupMenu();

            int cols = 4;
            if ("week".equals(type)) cols = 3;
            else if ("month".equals(type)) cols = 3;
            else if ("hour".equals(type)) cols = 4;

            JPanel popupContent = new JPanel(new BorderLayout(4, 4));
            popupContent.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

            // 全选与清空
            JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
            selectAllBtn = new JButton("全选");
            clearBtn = new JButton("清空");
            selectAllBtn.setFont(UIUtils.plainFont().deriveFont(11f));
            clearBtn.setFont(UIUtils.plainFont().deriveFont(11f));
            selectAllBtn.setMargin(new Insets(1, 4, 1, 4));
            clearBtn.setMargin(new Insets(1, 4, 1, 4));
            actionPanel.add(selectAllBtn);
            actionPanel.add(clearBtn);
            popupContent.add(actionPanel, BorderLayout.NORTH);

            // 勾选网格
            specGrid = new JPanel(new GridLayout(0, cols, 4, 4));

            for (int i = min; i <= max; i++) {
                String text;
                if ("week".equals(type)) {
                    text = getWeekName(i);
                } else if ("month".equals(type)) {
                    text = i + "月";
                } else {
                    text = String.format("%02d", i);
                }
                JCheckBox cb = new JCheckBox(text);
                cb.setFont(UIUtils.monoFont().deriveFont(12f));
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

            JScrollPane gridScroll = new JScrollPane(specGrid);
            gridScroll.setBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")));

            // 设置不同维度的滚动窗口首选大小，使其紧凑整齐
            int prefW = 200;
            int prefH = 150;
            if ("week".equals(type)) {
                prefW = 160;
                prefH = 90;
            } else if ("month".equals(type)) {
                prefW = 180;
                prefH = 110;
            } else if ("hour".equals(type)) {
                prefW = 200;
                prefH = 120;
            }
            gridScroll.setPreferredSize(new Dimension(prefW, prefH));
            popupContent.add(gridScroll, BorderLayout.CENTER);

            selectPopup.add(popupContent);

            // 弹出显示
            selectButton.addActionListener(e -> {
                selectPopup.show(selectButton, 0, selectButton.getHeight());
            });

            // 绑定全选与清空按钮事件
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
        }

        private void initUI(String label, String unit) {
            setLayout(new BorderLayout());
            setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            // 单选按钮选项区 (不再常驻庞大的复选网格，高度大幅缩短)
            JPanel radioPanel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.anchor = GridBagConstraints.WEST;
            gbc.insets = new Insets(3, 4, 3, 4);
            gbc.weightx = 1.0;

            ButtonGroup group = new ButtonGroup();
            group.add(anyRadio);
            group.add(noneRadio);
            group.add(cycleRadio);
            group.add(rangeRadio);
            group.add(specRadio);

            // 1. 每X (*)
            radioPanel.add(anyRadio, gbc);
            gbc.gridy++;

            // 2. 不指定 (?) (仅日/周显示)
            if ("day".equals(type) || "week".equals(type)) {
                radioPanel.add(noneRadio, gbc);
                gbc.gridy++;
            }

            // 3. 周期 (/)
            JPanel cycleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            cycleRow.add(cycleRadio);
            cycleRow.add(new JLabel("从第"));
            if ("week".equals(type)) {
                cycleRow.add(weekStartCombo);
            } else {
                cycleRow.add(cycleStartSpinner);
            }
            cycleRow.add(new JLabel(unit + "开始，每"));
            cycleRow.add(cycleStepSpinner);
            cycleRow.add(new JLabel(unit + "执行一次"));
            radioPanel.add(cycleRow, gbc);
            gbc.gridy++;

            // 4. 区间 (-) 以及区间周期 (/)
            JPanel rangeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            rangeRow.add(rangeRadio);
            rangeRow.add(new JLabel("从"));
            if ("week".equals(type)) {
                rangeRow.add(weekFromCombo);
            } else {
                rangeRow.add(rangeFromSpinner);
            }
            rangeRow.add(new JLabel(unit + "到"));
            if ("week".equals(type)) {
                rangeRow.add(weekToCombo);
            } else {
                rangeRow.add(rangeToSpinner);
            }
            rangeRow.add(new JLabel(unit));
            if (!"week".equals(type)) {
                rangeRow.add(new JLabel("，每隔"));
                rangeRow.add(rangeStepSpinner);
                rangeRow.add(new JLabel(unit + "执行一次"));
            }
            radioPanel.add(rangeRow, gbc);
            gbc.gridy++;

            // 5. 指定 (多选下拉或文本框录入)
            JPanel specRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            specRow.add(specRadio);
            if ("day".equals(type)) {
                specRow.add(new JLabel("天数输入："));
                specRow.add(specTextField);
                specTextField.setPreferredSize(new Dimension(220, 24));
            } else {
                specRow.add(selectButton);
            }
            radioPanel.add(specRow, gbc);

            add(radioPanel, BorderLayout.NORTH);

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

            if (selectButton != null) {
                selectButton.setEnabled(specSel);
            }
        }

        private void setAllCheckBoxes(boolean selected) {
            if (specCheckBoxes != null) {
                for (JCheckBox cb : specCheckBoxes) {
                    if (cb != null) cb.setSelected(selected);
                }
            }
        }

        private void updateSelectButtonText() {
            if ("day".equals(type) || selectButton == null) return;
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
                selectButton.setText("选择值 (未选)");
            } else {
                String listStr = sb.toString();
                if (listStr.length() > 18) {
                    selectButton.setText("已选 (" + count + "个): " + listStr.substring(0, 15) + "...");
                } else {
                    selectButton.setText("已选 (" + count + "个): " + listStr);
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
