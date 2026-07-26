package com.aqishi.toolbox.calc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.swing.*;
import java.awt.*;

/**
 * 科学计算器面板：支持表达式求值（基于 Nashorn/兼容引擎）+ 常用数学函数按钮。
 * <p>注意：JDK 15+ 移除了 Nashorn，本面板在无 JS 引擎时回退到简易中缀求值器。</p>
 */
public class CalculatorPanel extends ToolPanel {

    public CalculatorPanel() {
        super("calc", "calculator",
                "表达式", "求值", "计算器", "Calc",
                "数学", "函数", "sqrt", "pow");
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
                        expr.setText("Math.sqrt(" + (expr.getText().isEmpty() ? "0" : expr.getText()) + ")");
                        break;
                    case "pow":
                        expr.setText(expr.getText() + "Math.pow(,)");
                        break;
                    case "pi":
                        expr.setText(expr.getText() + "Math.PI");
                        break;
                    case "e":
                        expr.setText(expr.getText() + "Math.E");
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

    /** 优先使用脚本引擎，失败回退简易求值器 */
    private String evaluate(String expression) {
        if (expression == null || expression.trim().isEmpty()) return "0";
        try {
            ScriptEngine engine = new ScriptEngineManager().getEngineByName("js");
            if (engine != null) {
                Object r = engine.eval(expression);
                double val = ((Number) r).doubleValue();
                return formatResult(val);
            }
        } catch (ScriptException ignore) {
        }
        // 回退：简易中缀求值
        try {
            double val = SimpleEval.eval(expression);
            return formatResult(val);
        } catch (Exception ex) {
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
