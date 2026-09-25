package com.aqishi.toolbox.feature.compute.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.compute.domain.SimpleEval;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;

import javax.swing.*;
import java.awt.*;

/**
 * 科学计算器面板：表达式求值 + 常用数学函数按钮，求值由 {@link SimpleEval} 完成。
 *
 * <p>早先优先交给 JS 脚本引擎求值，但 JDK 15 起已移除 Nashorn、项目也未引入其他引擎，
 * 那条路径从未生效；而按钮插入的 {@code Math.sqrt(...)} 回退求值器又不认识，
 * 函数按钮因此一直报错。现在按钮只插入求值器支持的写法。</p>
 */
public class CalculatorPanel extends ToolPanel {

    public CalculatorPanel() {
        super(ToolCatalog.CALCULATOR);
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // ===== 显示卡片：表达式是视觉焦点，单独一张卡放在最上方 =====
        JTextField expr = Fields.mono("");
        expr.setFont(Tokens.fontMono().deriveFont(Font.BOLD, 20f));
        expr.setHorizontalAlignment(JTextField.RIGHT);
        // 只放大高度，宽度仍由卡片的 BorderLayout 拉伸；不加高的话 20pt 数字会被 32px 的常规控件高度切掉
        int displayHeight = Tokens.CONTROL_HEIGHT + Tokens.SPACE_MD;
        expr.setPreferredSize(new Dimension(expr.getPreferredSize().width, displayHeight));
        expr.setMinimumSize(new Dimension(56, displayHeight));

        Card displayCard = Card.titled("表达式", "回车或按 = 求值");
        displayCard.setContent(expr);

        // ===== 按键卡片：保持 GridLayout，等宽等高并随窗口拉伸 =====
        String[] keys = {
                "C", "(", ")", "/",
                "7", "8", "9", "*",
                "4", "5", "6", "-",
                "1", "2", "3", "+",
                "0", ".", "=", "sqrt",
                "pow", "pi", "e", "<-"
        };
        JPanel pad = new JPanel(new GridLayout(6, 4, Tokens.SPACE_SM, Tokens.SPACE_SM));
        pad.setOpaque(false);
        for (String k : keys) {
            JButton b;
            if ("=".equals(k)) {
                b = Buttons.primary(k);          // 键盘区唯一的主操作
            } else if ("C".equals(k)) {
                b = Buttons.danger(k);           // 清空整条表达式，标为危险动作
            } else {
                b = Buttons.secondary(k);
            }
            pad.add(b);
        }
        Card padCard = Card.titled("按键");
        padCard.setContent(pad);

        // ===== 历史卡片：与键盘并排，纵向留给它整屏高度，长记录不必频繁滚动 =====
        JTextArea history = Fields.output(5, 30);
        Card historyCard = Card.flush("历史记录");
        historyCard.setContent(Fields.scroll(history));

        root.add(displayCard, BorderLayout.NORTH);
        root.add(Layouts.columns(Tokens.SPACE_LG, padCard, historyCard), BorderLayout.CENTER);

        // 按钮事件
        for (Component comp : pad.getComponents()) {
            JButton b = (JButton) comp;
            b.addActionListener(e -> {
                String k = b.getText();
                switch (k) {
                    case "C":
                        expr.setText("");
                        break;
                    case "<-":
                        String t = expr.getText();
                        if (!t.isEmpty()) expr.setText(t.substring(0, t.length() - 1));
                        break;
                    case "=":
                        String result = evaluate(expr.getText());
                        history.append(expr.getText() + " = " + result + "\n");
                        expr.setText(result);
                        break;
                    case "sqrt":
                        expr.setText("sqrt(" + (expr.getText().isEmpty() ? "0" : expr.getText()) + ")");
                        break;
                    case "pow":
                        expr.setText(expr.getText() + "pow(,)");
                        break;
                    case "pi":
                        expr.setText(expr.getText() + "pi");
                        break;
                    case "e":
                        expr.setText(expr.getText() + "e");
                        break;
                    default:
                        expr.setText(expr.getText() + k);
                }
            });
        }

        // 回车求值
        expr.addActionListener(e -> {
            String result = evaluate(expr.getText());
            history.append(expr.getText() + " = " + result + "\n");
            expr.setText(result);
        });

        return root;
    }

    private String evaluate(String expression) {
        if (expression == null || expression.trim().isEmpty()) return "0";
        try {
            return formatResult(SimpleEval.eval(expression));
        } catch (IllegalArgumentException ex) {
            return "错误";
        }
    }

    /** 格式化结果：消除浮点误差（如 2-1.1 → 0.9 而非 0.8999999999） */
    private static String formatResult(double value) {
        if (Double.isNaN(value)) return "NaN";
        if (Double.isInfinite(value)) return value > 0 ? "∞" : "-∞";
        // 保留 10 位小数并去掉尾部多余的 0
        String s = String.format("%.10f", value).replaceAll("0+$", "");
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
