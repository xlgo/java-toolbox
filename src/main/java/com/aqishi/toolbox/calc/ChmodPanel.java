package com.aqishi.toolbox.calc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Card;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

/**
 * Linux 权限计算器 (Chmod Calculator)
 * 提供 r/w/x 权限勾选与数字 (755)、符号 (rwxr-xr-x) 及 chmod 命令的双向实时推导。
 */
public class ChmodPanel extends ToolPanel {

    // 复选框矩阵: [0: Owner, 1: Group, 2: Others] x [0: Read(4), 1: Write(2), 2: Execute(1)]
    private final JCheckBox[][] permBoxes = new JCheckBox[3][3];
    // 特殊权限: [0: SetUID(4), 1: SetGID(2), 2: Sticky(1)]
    private final JCheckBox[] specialBoxes = new JCheckBox[3];

    private JTextField octalField;
    private JTextField symbolicField;
    private JTextField chmodCmdField;
    private JTextField filenameField;

    private boolean isUpdatingFromCode = false;

    public ChmodPanel() {
        super("calc", "chmod.calc", "chmod", "permission", "linux", "octal", "rwxrwxrwx", "755", "777", "644", "权限");
    }

    @Override
    protected JComponent build() {
        JPanel mainPanel = new JPanel(new BorderLayout(0, 16));
        mainPanel.setBorder(new EmptyBorder(16, 16, 16, 16));

        // --- 顶栏：常用预设与快捷推导 ---
        Card topCard = Card.plain();
        topCard.setLayout(new BorderLayout(12, 12));
        topCard.setBorder(new EmptyBorder(12, 16, 12, 16));

        JPanel topGrid = new JPanel(new GridLayout(2, 2, 12, 8));

        topGrid.add(new JLabel("八进制数值 (Octal):"));
        octalField = new JTextField("0755");
        octalField.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
        topGrid.add(octalField);

        topGrid.add(new JLabel("符号格式 (Symbolic):"));
        symbolicField = new JTextField("rwxr-xr-x");
        symbolicField.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
        topGrid.add(symbolicField);

        JPanel presetPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        presetPanel.add(new JLabel("常用预设: "));

        addPresetButton(presetPanel, "755 (目录/脚本)", "755");
        addPresetButton(presetPanel, "644 (标准文件)", "644");
        addPresetButton(presetPanel, "700 (私有受限)", "700");
        addPresetButton(presetPanel, "600 (仅所有者读写)", "600");
        addPresetButton(presetPanel, "777 (全员只读写执行)", "777");

        topCard.add(topGrid, BorderLayout.CENTER);
        topCard.add(presetPanel, BorderLayout.SOUTH);

        // --- 中央：可视化勾选矩阵 ---
        Card matrixCard = Card.plain();
        matrixCard.setLayout(new BorderLayout(12, 12));
        matrixCard.setBorder(new EmptyBorder(16, 16, 16, 16));

        JLabel matrixTitle = new JLabel("权限勾选矩阵 (Permission Matrix)");
        matrixTitle.setFont(matrixTitle.getFont().deriveFont(Font.BOLD, 14f));
        matrixCard.add(matrixTitle, BorderLayout.NORTH);

        JPanel matrixGrid = new JPanel(new GridLayout(5, 4, 16, 10));

        // 表头
        matrixGrid.add(new JLabel("")); // 空白左上角
        JLabel l1 = new JLabel("所有者 (Owner / User)", SwingConstants.CENTER);
        JLabel l2 = new JLabel("同组 (Group)", SwingConstants.CENTER);
        JLabel l3 = new JLabel("其他人 (Others)", SwingConstants.CENTER);
        l1.setFont(l1.getFont().deriveFont(Font.BOLD));
        l2.setFont(l2.getFont().deriveFont(Font.BOLD));
        l3.setFont(l3.getFont().deriveFont(Font.BOLD));
        matrixGrid.add(l1);
        matrixGrid.add(l2);
        matrixGrid.add(l3);

        // Read (4)
        matrixGrid.add(new JLabel("读取 Read (r - 4):"));
        for (int i = 0; i < 3; i++) {
            permBoxes[i][0] = new JCheckBox("Read (4)");
            permBoxes[i][0].setHorizontalAlignment(SwingConstants.CENTER);
            permBoxes[i][0].addActionListener(e -> onCheckboxChanged());
            matrixGrid.add(permBoxes[i][0]);
        }

        // Write (2)
        matrixGrid.add(new JLabel("写入 Write (w - 2):"));
        for (int i = 0; i < 3; i++) {
            permBoxes[i][1] = new JCheckBox("Write (2)");
            permBoxes[i][1].setHorizontalAlignment(SwingConstants.CENTER);
            permBoxes[i][1].addActionListener(e -> onCheckboxChanged());
            matrixGrid.add(permBoxes[i][1]);
        }

        // Execute (1)
        matrixGrid.add(new JLabel("执行 Execute (x - 1):"));
        for (int i = 0; i < 3; i++) {
            permBoxes[i][2] = new JCheckBox("Execute (1)");
            permBoxes[i][2].setHorizontalAlignment(SwingConstants.CENTER);
            permBoxes[i][2].addActionListener(e -> onCheckboxChanged());
            matrixGrid.add(permBoxes[i][2]);
        }

        // Special Bits (4000, 2000, 1000)
        matrixGrid.add(new JLabel("特殊标志 (Special):"));
        specialBoxes[0] = new JCheckBox("SetUID (4000)");
        specialBoxes[1] = new JCheckBox("SetGID (2000)");
        specialBoxes[2] = new JCheckBox("Sticky (1000)");
        for (int i = 0; i < 3; i++) {
            specialBoxes[i].setHorizontalAlignment(SwingConstants.CENTER);
            specialBoxes[i].addActionListener(e -> onCheckboxChanged());
            matrixGrid.add(specialBoxes[i]);
        }

        matrixCard.add(matrixGrid, BorderLayout.CENTER);

        // --- 底栏：命令生成与一键复制 ---
        Card bottomCard = Card.plain();
        bottomCard.setLayout(new BorderLayout(12, 12));
        bottomCard.setBorder(new EmptyBorder(16, 16, 16, 16));

        JPanel cmdForm = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        cmdForm.add(new JLabel("目标文件名/路径:"), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        filenameField = new JTextField("filename");
        cmdForm.add(filenameField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        cmdForm.add(new JLabel("Chmod 执行命令:"), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        chmodCmdField = new JTextField();
        chmodCmdField.setEditable(false);
        chmodCmdField.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
        cmdForm.add(chmodCmdField, gbc);

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton copyCmdBtn = new JButton("复制 chmod 命令");
        copyCmdBtn.addActionListener(e -> copyToClipboard(chmodCmdField.getText()));

        JButton copyOctalBtn = new JButton("复制八进制 (755)");
        copyOctalBtn.addActionListener(e -> copyToClipboard(octalField.getText()));

        JButton copySymbolicBtn = new JButton("复制符号 (rwxr-xr-x)");
        copySymbolicBtn.addActionListener(e -> copyToClipboard(symbolicField.getText()));

        btnBar.add(copyOctalBtn);
        btnBar.add(copySymbolicBtn);
        btnBar.add(copyCmdBtn);

        bottomCard.add(cmdForm, BorderLayout.CENTER);
        bottomCard.add(btnBar, BorderLayout.SOUTH);

        // 组装主布局
        JPanel centerPanel = new JPanel(new BorderLayout(0, 16));
        centerPanel.add(topCard, BorderLayout.NORTH);
        centerPanel.add(matrixCard, BorderLayout.CENTER);

        mainPanel.add(centerPanel, BorderLayout.CENTER);
        mainPanel.add(bottomCard, BorderLayout.SOUTH);

        // 监听事件
        setupListeners();
        // 初始设为 755
        applyOctalString("755");

        return mainPanel;
    }

    private void addPresetButton(JPanel container, String label, String value) {
        JButton btn = new JButton(label);
        btn.addActionListener(e -> applyOctalString(value));
        container.add(btn);
    }

    private void setupListeners() {
        filenameField.getDocument().addDocumentListener(new SimpleDocumentListener() {
            @Override
            public void update() {
                updateChmodCmd();
            }
        });

        octalField.getDocument().addDocumentListener(new SimpleDocumentListener() {
            @Override
            public void update() {
                if (isUpdatingFromCode) return;
                String text = octalField.getText().trim();
                applyOctalString(text);
            }
        });

        symbolicField.getDocument().addDocumentListener(new SimpleDocumentListener() {
            @Override
            public void update() {
                if (isUpdatingFromCode) return;
                String text = symbolicField.getText().trim();
                applySymbolicString(text);
            }
        });
    }

    private void onCheckboxChanged() {
        if (isUpdatingFromCode) return;
        isUpdatingFromCode = true;
        try {
            // 计算 Special
            int special = 0;
            if (specialBoxes[0].isSelected()) special += 4;
            if (specialBoxes[1].isSelected()) special += 2;
            if (specialBoxes[2].isSelected()) special += 1;

            // 计算 roles
            int[] roles = new int[3];
            for (int i = 0; i < 3; i++) {
                if (permBoxes[i][0].isSelected()) roles[i] += 4;
                if (permBoxes[i][1].isSelected()) roles[i] += 2;
                if (permBoxes[i][2].isSelected()) roles[i] += 1;
            }

            String octalStr = (special > 0 ? String.valueOf(special) : "") + roles[0] + roles[1] + roles[2];
            octalField.setText(octalStr);

            String symStr = buildSymbolicString(special, roles[0], roles[1], roles[2]);
            symbolicField.setText(symStr);

            updateChmodCmd();
        } finally {
            isUpdatingFromCode = false;
        }
    }

    private void applyOctalString(String str) {
        if (str == null) return;
        String clean = str.trim();
        if (clean.startsWith("0")) clean = clean.substring(1);
        if (!clean.matches("^[0-7]{3,4}$")) return;

        isUpdatingFromCode = true;
        try {
            int special = 0;
            int u, g, o;
            if (clean.length() == 4) {
                special = clean.charAt(0) - '0';
                u = clean.charAt(1) - '0';
                g = clean.charAt(2) - '0';
                o = clean.charAt(3) - '0';
            } else {
                u = clean.charAt(0) - '0';
                g = clean.charAt(1) - '0';
                o = clean.charAt(2) - '0';
            }

            // 更新 Checkboxes
            updateBoxesFromValues(special, u, g, o);

            symbolicField.setText(buildSymbolicString(special, u, g, o));
            if (!octalField.getText().trim().equals(str)) {
                octalField.setText(str);
            }
            updateChmodCmd();
        } finally {
            isUpdatingFromCode = false;
        }
    }

    private void applySymbolicString(String str) {
        if (str == null) return;
        String clean = str.trim();
        if (clean.startsWith("-") || clean.startsWith("d")) {
            clean = clean.substring(1);
        }
        if (clean.length() != 9) return;

        isUpdatingFromCode = true;
        try {
            int u = parseTriplet(clean.substring(0, 3));
            int g = parseTriplet(clean.substring(3, 6));
            int o = parseTriplet(clean.substring(6, 9));

            int special = 0;
            if (clean.charAt(2) == 's' || clean.charAt(2) == 'S') special += 4;
            if (clean.charAt(5) == 's' || clean.charAt(5) == 'S') special += 2;
            if (clean.charAt(8) == 't' || clean.charAt(8) == 'T') special += 1;

            updateBoxesFromValues(special, u, g, o);

            String octStr = (special > 0 ? String.valueOf(special) : "") + u + g + o;
            octalField.setText(octStr);
            updateChmodCmd();
        } finally {
            isUpdatingFromCode = false;
        }
    }

    private int parseTriplet(String triplet) {
        int val = 0;
        char r = triplet.charAt(0);
        char w = triplet.charAt(1);
        char x = triplet.charAt(2);
        if (r == 'r') val += 4;
        if (w == 'w') val += 2;
        if (x == 'x' || x == 's' || x == 't') val += 1;
        return val;
    }

    private void updateBoxesFromValues(int special, int u, int g, int o) {
        specialBoxes[0].setSelected((special & 4) != 0);
        specialBoxes[1].setSelected((special & 2) != 0);
        specialBoxes[2].setSelected((special & 1) != 0);

        int[] vals = {u, g, o};
        for (int i = 0; i < 3; i++) {
            permBoxes[i][0].setSelected((vals[i] & 4) != 0);
            permBoxes[i][1].setSelected((vals[i] & 2) != 0);
            permBoxes[i][2].setSelected((vals[i] & 1) != 0);
        }
    }

    private String buildSymbolicString(int special, int u, int g, int o) {
        StringBuilder sb = new StringBuilder();

        // Owner
        sb.append((u & 4) != 0 ? 'r' : '-');
        sb.append((u & 2) != 0 ? 'w' : '-');
        if ((special & 4) != 0) {
            sb.append((u & 1) != 0 ? 's' : 'S');
        } else {
            sb.append((u & 1) != 0 ? 'x' : '-');
        }

        // Group
        sb.append((g & 4) != 0 ? 'r' : '-');
        sb.append((g & 2) != 0 ? 'w' : '-');
        if ((special & 2) != 0) {
            sb.append((g & 1) != 0 ? 's' : 'S');
        } else {
            sb.append((g & 1) != 0 ? 'x' : '-');
        }

        // Others
        sb.append((o & 4) != 0 ? 'r' : '-');
        sb.append((o & 2) != 0 ? 'w' : '-');
        if ((special & 1) != 0) {
            sb.append((o & 1) != 0 ? 't' : 'T');
        } else {
            sb.append((o & 1) != 0 ? 'x' : '-');
        }

        return sb.toString();
    }

    private void updateChmodCmd() {
        String oct = octalField.getText().trim();
        String file = filenameField.getText().trim();
        if (file.isEmpty()) file = "filename";
        chmodCmdField.setText("chmod " + oct + " " + file);
    }

    private void copyToClipboard(String text) {
        if (text == null || text.isEmpty()) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        JOptionPane.showMessageDialog(getView(), "已成功复制到剪贴板:\n" + text, "提示", JOptionPane.INFORMATION_MESSAGE);
    }

    @FunctionalInterface
    private interface SimpleDocumentListener extends DocumentListener {
        void update();
        @Override default void insertUpdate(DocumentEvent e) { update(); }
        @Override default void removeUpdate(DocumentEvent e) { update(); }
        @Override default void changedUpdate(DocumentEvent e) { update(); }
    }
}
