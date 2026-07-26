package com.aqishi.toolbox.misc;

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
        super("generate", "password.generator",
                "密码", "Password", "随机密码", "强密码",
                "密码强度", "口令", "密码生成");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // ===== 配置卡片 =====
        // 长度滑块占满输入列，实时位数跟在行尾，滑动时不会把标签列推来推去
        lenSlider = new JSlider(8, 64, 16);
        lenSlider.setPaintTicks(true);
        lenSlider.setMajorTickSpacing(8);
        lenSlider.setMinorTickSpacing(2);
        lenSlider.setOpaque(false);
        lenLabel = Fields.label("16 位 ");

        upperCheck = Fields.check("包含大写 (A-Z)", true);
        lowerCheck = Fields.check("包含小写 (a-z)", true);
        digitCheck = Fields.check("包含数字 (0-9)", true);
        specialCheck = Fields.check("包含特殊字符", true);
        JPanel charsetRow = new WrapRow(Tokens.SPACE_MD, Tokens.SPACE_XS);
        charsetRow.add(upperCheck);
        charsetRow.add(lowerCheck);
        charsetRow.add(digitCheck);
        charsetRow.add(specialCheck);

        // 13 个单字符复选框条目多，用可换行的流式行，窄窗口下折行而不是被裁掉
        JPanel specialRow = new WrapRow(Tokens.SPACE_SM, Tokens.SPACE_XS);
        char[] specialArray = DEFAULT_SPECIAL.toCharArray();
        specialCharChecks = new JCheckBox[specialArray.length];
        for (int i = 0; i < specialArray.length; i++) {
            specialCharChecks[i] = Fields.check(String.valueOf(specialArray[i]), true);
            specialCharChecks[i].setFont(Tokens.fontMono());
            specialRow.add(specialCharChecks[i]);
        }

        JButton selectAllBtn = Buttons.ghost("全选");
        JButton invertBtn = Buttons.ghost("反选");
        // 等宽两列而不是 FlowLayout：窄窗口下两个按钮一起变窄，不会有一个被整体丢掉
        JPanel specialActions = Layouts.columns(Tokens.SPACE_XS, selectAllBtn, invertBtn);

        FormGrid form = new FormGrid();
        form.row("密码长度", lenSlider, lenLabel);
        form.fullRow(charsetRow);
        form.row("选择特殊字符", specialRow, specialActions);

        Card configCard = Card.titled("密码生成配置");
        configCard.setContent(form);

        // ===== 结果卡片：密码 + 强度指示，主操作放标题右侧 =====
        passwordField = Fields.mono("");
        passwordField.setFont(Tokens.fontMono().deriveFont(15f)); // 稍微大一点
        passwordField.setEditable(false);

        strengthLabel = new JLabel("密码强度：未评估");
        strengthLabel.setFont(Tokens.fontBody());
        strengthBar = new JProgressBar(0, 100);
        strengthBar.setValue(0);
        strengthBar.setOpaque(false);
        // 只用来锁定 14px 的条高，宽度由 BorderLayout.CENTER 拉伸
        strengthBar.setPreferredSize(new Dimension(150, 14));
        JPanel strengthRow = Layouts.box(Tokens.SPACE_MD, 0);
        strengthRow.add(strengthLabel, BorderLayout.WEST);
        strengthRow.add(strengthBar, BorderLayout.CENTER);

        JButton genBtn = Buttons.primary("生成密码");
        JButton copyBtn = Buttons.secondary("复制密码");

        Card resultCard = Card.titled("生成的密码");
        JPanel resultBody = Layouts.box(0, Tokens.SPACE_MD);
        resultBody.add(passwordField, BorderLayout.NORTH);
        resultBody.add(strengthRow, BorderLayout.CENTER);
        resultCard.setContent(resultBody);
        resultCard.addHeaderAction(genBtn);
        resultCard.addHeaderAction(copyBtn);

        // 这个工具的结果只有一行密码 + 一条强度指示，让卡片去吸收整页高度只会留下大片空白；
        // 两张卡片按自然高度顶到页首，剩余空间交回页面底色。
        JPanel column = Layouts.box(0, Tokens.SPACE_LG);
        column.add(configCard, BorderLayout.NORTH);
        column.add(resultCard, BorderLayout.CENTER);
        root.add(column, BorderLayout.NORTH);

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

    /** 强度着色走语义色令牌，跟随主题切换；文字描述保持不变 */
    private void updateStrengthUI(String strength) {
        strengthLabel.setText("密码强度：" + strength);
        Color tone;
        if (strength.startsWith("高")) {
            strengthBar.setValue(100);
            tone = Tokens.success();
        } else if (strength.startsWith("中")) {
            strengthBar.setValue(60);
            tone = Tokens.warning();
        } else {
            strengthBar.setValue(25);
            tone = Tokens.danger();
        }
        strengthBar.setForeground(tone);
        strengthLabel.setForeground(tone);
    }

    /**
     * 会换行、且把换行算进首选高度的横向控件行。
     *
     * <p>原生 {@link FlowLayout} 虽然会折行，但首选高度永远按一行算；放进
     * {@code FormGrid}（按首选高度分配行高）之后，折下去的那一行会被直接裁掉。
     * 这里按实际可用宽度重算高度，并在宽度变化时主动 revalidate。</p>
     */
    private static final class WrapRow extends JPanel {

        WrapRow(int hgap, int vgap) {
            super(new FlowLayout(FlowLayout.LEFT, hgap, vgap));
            setOpaque(false);
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent event) {
                    // 首次布局时宽度还是 0，拿不到真实可用宽度；拿到之后再算一次行高
                    revalidate();
                }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            FlowLayout layout = (FlowLayout) getLayout();
            Insets insets = getInsets();
            int usable = getWidth() - insets.left - insets.right - layout.getHgap() * 2;
            if (usable <= 0) {
                return super.getPreferredSize();
            }

            int rowWidth = 0;
            int rowHeight = 0;
            int width = 0;
            int height = 0;
            for (int i = 0; i < getComponentCount(); i++) {
                Component child = getComponent(i);
                if (!child.isVisible()) {
                    continue;
                }
                Dimension size = child.getPreferredSize();
                if (rowWidth > 0 && rowWidth + layout.getHgap() + size.width > usable) {
                    width = Math.max(width, rowWidth);
                    height += (height > 0 ? layout.getVgap() : 0) + rowHeight;
                    rowWidth = 0;
                    rowHeight = 0;
                }
                rowWidth += (rowWidth > 0 ? layout.getHgap() : 0) + size.width;
                rowHeight = Math.max(rowHeight, size.height);
            }
            width = Math.max(width, rowWidth);
            height += (height > 0 ? layout.getVgap() : 0) + rowHeight;

            return new Dimension(
                    width + insets.left + insets.right + layout.getHgap() * 2,
                    height + insets.top + insets.bottom + layout.getVgap() * 2);
        }

        /**
         * 最窄只要求放得下最宽的一个条目，其余靠换行消化。
         *
         * <p>否则窄窗口下 {@code GridBagLayout} 会改用最小尺寸排版，把整整一行控件的宽度
         * 当成不可压缩的硬需求，进而挤掉同一行行尾的按钮。</p>
         */
        @Override
        public Dimension getMinimumSize() {
            FlowLayout layout = (FlowLayout) getLayout();
            Insets insets = getInsets();
            int widest = 0;
            for (int i = 0; i < getComponentCount(); i++) {
                Component child = getComponent(i);
                if (child.isVisible()) {
                    widest = Math.max(widest, child.getPreferredSize().width);
                }
            }
            return new Dimension(
                    widest + insets.left + insets.right + layout.getHgap() * 2,
                    getPreferredSize().height);
        }
    }
}
