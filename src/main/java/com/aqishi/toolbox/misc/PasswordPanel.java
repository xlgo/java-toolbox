package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.security.SecureRandom;

/**
 * 强密码生成器工具面板。
 * <p>基于 SecureRandom 离线安全生成强密码，支持自定义长度、字符集与密码强度展示。</p>
 */
public class PasswordPanel extends ToolPanel {

    private JSlider lenSlider;
    private JLabel lenLabel;
    private JCheckBox upperCheck;
    private JCheckBox lowerCheck;
    private JCheckBox digitCheck;
    private JCheckBox specialCheck;
    private JCheckBox[] specialCharChecks;
    
    private JTextField passwordField;
    private JLabel strengthLabel;
    private JProgressBar strengthBar;

    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String DEFAULT_SPECIAL = "!@#$%^&*_+-=|";

    public PasswordPanel() {
        super("生成", "密码生成器",
                "密码", "Password", "随机密码", "强密码",
                "密码强度", "口令", "密码生成");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 12));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // ===== 顶部：配置区 =====
        JPanel configPanel = new JPanel(new GridBagLayout());
        configPanel.setBorder(BorderFactory.createTitledBorder("密码生成配置"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(6, 8, 6, 8);

        // 1. 长度滑块
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        configPanel.add(new JLabel("密码长度："), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        lenSlider = new JSlider(8, 64, 16);
        lenSlider.setPaintTicks(true);
        lenSlider.setMajorTickSpacing(8);
        lenSlider.setMinorTickSpacing(2);
        configPanel.add(lenSlider, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        lenLabel = new JLabel("16 位 ");
        lenLabel.setFont(UIUtils.plainFont());
        configPanel.add(lenLabel, gbc);

        // 2. 字符集选择
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 3;
        JPanel checkPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        upperCheck = new JCheckBox("包含大写 (A-Z)", true);
        lowerCheck = new JCheckBox("包含小写 (a-z)", true);
        digitCheck = new JCheckBox("包含数字 (0-9)", true);
        specialCheck = new JCheckBox("包含特殊字符", true);

        upperCheck.setFont(UIUtils.plainFont());
        lowerCheck.setFont(UIUtils.plainFont());
        digitCheck.setFont(UIUtils.plainFont());
        specialCheck.setFont(UIUtils.plainFont());

        checkPanel.add(upperCheck);
        checkPanel.add(lowerCheck);
        checkPanel.add(digitCheck);
        checkPanel.add(specialCheck);
        configPanel.add(checkPanel, gbc);

        // 3. 自定义特殊字符配置 (流式布局 + 全选/反选)
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1; gbc.weightx = 0;
        configPanel.add(new JLabel("选择特殊字符："), gbc);

        JPanel specialConfigPanel = new JPanel(new BorderLayout(8, 0));
        JPanel specialGrid = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        char[] specialArray = DEFAULT_SPECIAL.toCharArray();
        specialCharChecks = new JCheckBox[specialArray.length];
        for (int i = 0; i < specialArray.length; i++) {
            specialCharChecks[i] = new JCheckBox(String.valueOf(specialArray[i]), true);
            specialCharChecks[i].setFont(UIUtils.monoFont().deriveFont(13f));
            specialGrid.add(specialCharChecks[i]);
        }

        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton selectAllBtn = new JButton("全选");
        JButton invertBtn = new JButton("反选");
        selectAllBtn.setFont(UIUtils.plainFont().deriveFont(12f));
        invertBtn.setFont(UIUtils.plainFont().deriveFont(12f));
        selectAllBtn.setMargin(new Insets(2, 6, 2, 6));
        invertBtn.setMargin(new Insets(2, 6, 2, 6));
        actionPanel.add(selectAllBtn);
        actionPanel.add(invertBtn);

        specialConfigPanel.add(specialGrid, BorderLayout.CENTER);
        specialConfigPanel.add(actionPanel, BorderLayout.EAST);

        gbc.gridx = 1; gbc.gridwidth = 2; gbc.weightx = 1.0;
        configPanel.add(specialConfigPanel, gbc);

        root.add(configPanel, BorderLayout.NORTH);

        // ===== 中部：生成与展示区 =====
        JPanel centerPanel = new JPanel(new GridBagLayout());
        centerPanel.setBorder(BorderFactory.createTitledBorder("生成的密码"));
        GridBagConstraints gbc2 = new GridBagConstraints();
        gbc2.fill = GridBagConstraints.HORIZONTAL;
        gbc2.insets = new Insets(8, 8, 8, 8);

        // 密码输出框
        gbc2.gridx = 0; gbc2.gridy = 0; gbc2.weightx = 1.0;
        passwordField = new JTextField();
        passwordField.setFont(UIUtils.monoFont().deriveFont(15f)); // 稍微大一点
        passwordField.setEditable(false);
        centerPanel.add(passwordField, gbc2);

        // 操作按钮列
        gbc2.gridx = 1; gbc2.weightx = 0;
        JPanel btnCol = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton genBtn = UIUtils.button("生成密码", 100);
        JButton copyBtn = UIUtils.button("复制密码", 100);
        btnCol.add(genBtn);
        btnCol.add(copyBtn);
        centerPanel.add(btnCol, gbc2);

        // 3. 强度条
        gbc2.gridx = 0; gbc2.gridy = 1; gbc2.gridwidth = 2; gbc2.weightx = 1.0;
        JPanel strengthPanel = new JPanel(new BorderLayout(8, 0));
        strengthLabel = new JLabel("密码强度：未评估");
        strengthLabel.setFont(UIUtils.plainFont());
        strengthBar = new JProgressBar(0, 100);
        strengthBar.setValue(0);
        strengthBar.setPreferredSize(new Dimension(150, 14));
        strengthPanel.add(strengthLabel, BorderLayout.WEST);
        strengthPanel.add(strengthBar, BorderLayout.CENTER);
        centerPanel.add(strengthPanel, gbc2);

        root.add(centerPanel, BorderLayout.CENTER);

        // ===== 事件处理 =====
        lenSlider.addChangeListener(e -> {
            lenLabel.setText(lenSlider.getValue() + " 位 ");
        });

        specialCheck.addActionListener(e -> {
            boolean selected = specialCheck.isSelected();
            for (JCheckBox cb : specialCharChecks) {
                cb.setEnabled(selected);
            }
            selectAllBtn.setEnabled(selected);
            invertBtn.setEnabled(selected);
        });

        selectAllBtn.addActionListener(e -> {
            for (JCheckBox cb : specialCharChecks) {
                if (cb.isEnabled()) {
                    cb.setSelected(true);
                }
            }
        });

        invertBtn.addActionListener(e -> {
            for (JCheckBox cb : specialCharChecks) {
                if (cb.isEnabled()) {
                    cb.setSelected(!cb.isSelected());
                }
            }
        });

        genBtn.addActionListener(e -> generatePassword());
        copyBtn.addActionListener(e -> {
            String pwd = passwordField.getText();
            if (!pwd.isEmpty()) {
                UIUtils.copyToClipboard(pwd);
                strengthLabel.setText("密码强度：" + evaluateStrength(pwd) + " (已成功复制)");
            }
        });

        // 默认生成一个密码
        generatePassword();

        return root;
    }

    private String getSelectedSpecialChars() {
        StringBuilder sb = new StringBuilder();
        if (specialCharChecks != null) {
            for (JCheckBox cb : specialCharChecks) {
                if (cb.isSelected() && cb.isEnabled()) {
                    sb.append(cb.getText());
                }
            }
        }
        return sb.toString();
    }

    private void generatePassword() {
        int length = lenSlider.getValue();
        StringBuilder pool = new StringBuilder();

        if (upperCheck.isSelected()) pool.append(UPPER);
        if (lowerCheck.isSelected()) pool.append(LOWER);
        if (digitCheck.isSelected()) pool.append(DIGITS);
        
        String specialChars = "";
        if (specialCheck.isSelected()) {
            specialChars = getSelectedSpecialChars();
            if (specialChars.isEmpty()) {
                UIUtils.error(passwordField, "请至少勾选一个特殊字符！");
                return;
            }
            pool.append(specialChars);
        }

        if (pool.length() == 0) {
            UIUtils.error(passwordField, "请至少勾选一种包含的字符类型！");
            return;
        }

        SecureRandom random = new SecureRandom();
        StringBuilder pwd = new StringBuilder();

        // 强制确保勾选的字符集至少各占一个位置
        if (upperCheck.isSelected()) pwd.append(UPPER.charAt(random.nextInt(UPPER.length())));
        if (lowerCheck.isSelected()) pwd.append(LOWER.charAt(random.nextInt(LOWER.length())));
        if (digitCheck.isSelected()) pwd.append(DIGITS.charAt(random.nextInt(DIGITS.length())));
        if (specialCheck.isSelected() && !specialChars.isEmpty()) {
            pwd.append(specialChars.charAt(random.nextInt(specialChars.length())));
        }

        int remaining = length - pwd.length();
        if (remaining < 0) remaining = 0;
        for (int i = 0; i < remaining; i++) {
            pwd.append(pool.charAt(random.nextInt(pool.length())));
        }

        // 打乱密码顺序
        char[] chars = pwd.toString().toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char temp = chars[i];
            chars[i] = chars[j];
            chars[j] = temp;
        }

        String finalPwd = new String(chars);
        passwordField.setText(finalPwd);

        // 强度分析
        String strength = evaluateStrength(finalPwd);
        updateStrengthUI(strength);
    }

    private String evaluateStrength(String pwd) {
        int len = pwd.length();
        int types = 0;
        if (pwd.matches(".*[A-Z].*")) types++;
        if (pwd.matches(".*[a-z].*")) types++;
        if (pwd.matches(".*[0-9].*")) types++;
        
        String specialChars = specialCheck.isSelected() ? getSelectedSpecialChars() : DEFAULT_SPECIAL;
        if (!specialChars.isEmpty()) {
            boolean hasSpecial = false;
            for (char c : specialChars.toCharArray()) {
                if (pwd.indexOf(c) >= 0) {
                    hasSpecial = true;
                    break;
                }
            }
            if (hasSpecial) types++;
        }

        if (len < 10 || types <= 1) {
            return "低 (建议增加长度与字符类型)";
        } else if (len >= 14 && types >= 3) {
            return "高 (安全系数极高)";
        } else {
            return "中 (比较安全)";
        }
    }

    private void updateStrengthUI(String strength) {
        strengthLabel.setText("密码强度：" + strength);
        if (strength.startsWith("高")) {
            strengthBar.setValue(100);
            strengthBar.setForeground(new Color(46, 125, 50)); // 绿色
        } else if (strength.startsWith("中")) {
            strengthBar.setValue(60);
            strengthBar.setForeground(new Color(230, 81, 0)); // 橙色
        } else {
            strengthBar.setValue(25);
            strengthBar.setForeground(new Color(198, 40, 40)); // 红色
        }
    }
}
