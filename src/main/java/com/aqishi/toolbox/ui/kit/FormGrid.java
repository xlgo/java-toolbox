package com.aqishi.toolbox.ui.kit;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * 表单网格：标签列右对齐、输入列拉伸、行距统一。
 *
 * <p>解决各面板用 {@code FlowLayout} 手工拼行导致的标签不对齐、行距不一致问题。</p>
 *
 * <pre>{@code
 * FormGrid form = new FormGrid();
 * form.row("IP/CIDR", input, Buttons.primary("计算"));
 * form.row("掩码", maskCombo);
 * form.caption("例如 192.168.1.100/24");
 * }</pre>
 */
public class FormGrid extends JPanel {

    private int row;
    private final int labelGap;
    private final int rowGap;

    public FormGrid() {
        this(Tokens.SPACE_MD, Tokens.SPACE_SM);
    }

    public FormGrid(int labelGap, int rowGap) {
        super(new GridBagLayout());
        setOpaque(false);
        this.labelGap = labelGap;
        this.rowGap = rowGap;
    }

    /** 一行：标签 + 输入控件 */
    public FormGrid row(String label, Component field) {
        return row(label, field, null);
    }

    /**
     * 一行：标签 + 输入控件 + 行尾控件（按钮、单位、复选框等）。
     *
     * @param trailing 可为 null
     */
    public FormGrid row(String label, Component field, Component trailing) {
        return row(Fields.label(label == null ? "" : label), field, trailing, true);
    }

    /**
     * 一行，但输入控件保持自身首选宽度、左对齐，不拉满整列。
     *
     * <p>下拉框、微调器这类控件拉满一整行会显得松散，用这个重载。</p>
     */
    public FormGrid rowCompact(String label, Component field) {
        return rowCompact(label, field, null);
    }

    /** 不拉伸的一行，带行尾控件 */
    public FormGrid rowCompact(String label, Component field, Component trailing) {
        return row(Fields.label(label == null ? "" : label), field, trailing, false);
    }

    /**
     * 一行，标签由调用方提供。
     *
     * <p>标签需要跟随输入控件一起显示 / 隐藏，或者标签文本是动态的（例如「速度：15 次/秒」）时使用。</p>
     */
    public FormGrid row(JLabel label, Component field, Component trailing, boolean stretch) {
        JLabel labelComponent = label;
        labelComponent.setFont(Tokens.fontBody());
        labelComponent.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        labelComponent.setLabelFor(field instanceof JComponent ? (JComponent) field : null);

        GridBagConstraints labelSpec = new GridBagConstraints();
        labelSpec.gridx = 0;
        labelSpec.gridy = row;
        labelSpec.anchor = GridBagConstraints.LINE_END;
        labelSpec.insets = new Insets(row == 0 ? 0 : rowGap, 0, 0, labelGap);
        add(labelComponent, labelSpec);

        GridBagConstraints fieldSpec = new GridBagConstraints();
        fieldSpec.gridx = 1;
        fieldSpec.gridy = row;
        fieldSpec.weightx = 1.0;
        fieldSpec.fill = stretch ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        fieldSpec.anchor = GridBagConstraints.LINE_START;
        fieldSpec.insets = new Insets(row == 0 ? 0 : rowGap, 0, 0, 0);
        if (trailing == null) {
            fieldSpec.gridwidth = 2;
        }
        add(field, fieldSpec);

        if (trailing != null) {
            GridBagConstraints trailingSpec = new GridBagConstraints();
            trailingSpec.gridx = 2;
            trailingSpec.gridy = row;
            trailingSpec.anchor = GridBagConstraints.LINE_START;
            trailingSpec.insets = new Insets(row == 0 ? 0 : rowGap, Tokens.SPACE_SM, 0, 0);
            add(trailing, trailingSpec);
        }
        row++;
        return this;
    }

    /** 整行控件（跨标签列），用于复选框组、分段控制等 */
    public FormGrid fullRow(Component component) {
        GridBagConstraints spec = new GridBagConstraints();
        spec.gridx = 0;
        spec.gridy = row;
        spec.gridwidth = 3;
        spec.weightx = 1.0;
        spec.fill = GridBagConstraints.HORIZONTAL;
        spec.anchor = GridBagConstraints.LINE_START;
        spec.insets = new Insets(row == 0 ? 0 : rowGap, 0, 0, 0);
        add(component, spec);
        row++;
        return this;
    }

    /** 与输入列左边缘对齐的说明文字 */
    public FormGrid caption(String text) {
        GridBagConstraints spec = new GridBagConstraints();
        spec.gridx = 1;
        spec.gridy = row;
        spec.gridwidth = 2;
        spec.weightx = 1.0;
        spec.fill = GridBagConstraints.HORIZONTAL;
        spec.anchor = GridBagConstraints.LINE_START;
        spec.insets = new Insets(Tokens.SPACE_XS, 0, 0, 0);
        add(Fields.caption(text), spec);
        row++;
        return this;
    }

    /** 让最后一行之下的空白由网格吸收，避免控件被垂直居中拉开 */
    public FormGrid glue() {
        GridBagConstraints spec = new GridBagConstraints();
        spec.gridx = 0;
        spec.gridy = row;
        spec.gridwidth = 3;
        spec.weighty = 1.0;
        spec.fill = GridBagConstraints.BOTH;
        add(javax.swing.Box.createGlue(), spec);
        row++;
        return this;
    }

    /** 当前已添加的行数 */
    public int getRowCount() {
        return row;
    }
}
