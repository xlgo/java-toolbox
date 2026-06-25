package com.aqishi.toolbox.calc;

import java.util.Deque;
import java.util.LinkedList;

/**
 * 简易中缀表达式求值器（双栈法），支持 + - * / % 和括号、小数。
 * <p>用于脚本引擎不可用时的回退方案。</p>
 */
final class SimpleEval {

    private SimpleEval() {
    }

    static double eval(String expr) {
        Deque<Double> nums = new LinkedList<>();
        Deque<Character> ops = new LinkedList<>();
        nums.push(0.0); // 容忍首字符为符号
        int i = 0, n = expr.length();
        boolean expectNum = true;
        while (i < n) {
            char ch = expr.charAt(i);
            if (ch == ' ') { i++; continue; }
            if (Character.isDigit(ch) || ch == '.') {
                int j = i;
                while (j < n && (Character.isDigit(expr.charAt(j)) || expr.charAt(j) == '.')) j++;
                nums.push(Double.parseDouble(expr.substring(i, j)));
                i = j;
                expectNum = false;
                continue;
            }
            if (ch == '(') {
                ops.push(ch);
                expectNum = true;
            } else if (ch == ')') {
                while (!ops.isEmpty() && ops.peek() != '(') calcOnce(nums, ops);
                if (!ops.isEmpty()) ops.pop();
                expectNum = false;
            } else if (isOp(ch)) {
                // 处理一元负号：期望数字时遇到 - 视为负
                if (expectNum && (ch == '-' || ch == '+')) {
                    nums.push(0.0);
                }
                while (!ops.isEmpty() && priority(ops.peek()) >= priority(ch)) calcOnce(nums, ops);
                ops.push(ch);
                expectNum = true;
            }
            i++;
        }
        while (!ops.isEmpty()) calcOnce(nums, ops);
        return nums.pop();
    }

    private static boolean isOp(char c) {
        return c == '+' || c == '-' || c == '*' || c == '/' || c == '%';
    }

    private static int priority(char op) {
        switch (op) {
            case '+': case '-': return 1;
            case '*': case '/': case '%': return 2;
            default: return 0;
        }
    }

    private static void calcOnce(Deque<Double> nums, Deque<Character> ops) {
        if (nums.size() < 2 || ops.isEmpty()) return;
        double b = nums.pop(), a = nums.pop();
        char op = ops.pop();
        double r;
        switch (op) {
            case '+': r = a + b; break;
            case '-': r = a - b; break;
            case '*': r = a * b; break;
            case '/': r = a / b; break;
            case '%': r = a % b; break;
            default: throw new IllegalStateException("未知运算符 " + op);
        }
        nums.push(r);
    }
}
