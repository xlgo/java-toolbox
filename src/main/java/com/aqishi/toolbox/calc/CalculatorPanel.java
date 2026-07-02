package com.aqishi.toolbox.calc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.swing.*;
import java.awt.*;
import java.util.Arrays;

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
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        JTextField expr = new JTextField();
        expr.setFont(UIUtils.monoFont().deriveFont(16f));
        expr.setHorizontalAlignment(JTextField.RIGHT);
        root.add(expr, BorderLayout.NORTH);

        // 按钮区：5x6 网格
        String[] keys = {
                "C", "(", ")", "/",
                "7", "8", "9", "*",
                "4", "5", "6", "-",
                "1", "2", "3", "+",
                "0", ".", "=", "sqrt",
                "pow", "pi", "e", "<-"
        };
        JPanel pad = new JPanel(new GridLayout(6, 4, 4, 4));
        for (String k : keys) {
            JButton b = new JButton(k);
            b.setFont(UIUtils.plainFont().deriveFont(14f));
            b.setFocusPainted(false);
            pad.add(b);
        }
        root.add(pad, BorderLayout.CENTER);

        JTextArea history = new JTextArea(5, 30);
        history.setFont(UIUtils.monoFont());
        history.setEditable(false);
        root.add(UIUtils.scrollText(history, "历史记录"), BorderLayout.SOUTH);

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
