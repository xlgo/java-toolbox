package com.aqishi.toolbox.feature.compute.domain;

import java.util.Locale;

/**
 * 计算器的表达式求值器（递归下降），是计算器唯一的求值路径。
 *
 * <p>支持：{@code + - * / %}、{@code ^}（乘方，右结合）、括号、小数、一元正负号，
 * 函数 {@code sqrt(x)}、{@code pow(a, b)}、{@code abs(x)}，常量 {@code pi}、{@code e}。
 * 为兼容旧按钮与历史输入，函数和常量前可以带 {@code Math.} 前缀。</p>
 *
 * <p>任何无法识别的字符都会抛出 {@link IllegalArgumentException}。早先的双栈实现会静默跳过
 * 不认识的字符、把运算符后的负号当成 {@code 0-}，于是 {@code 2x3} 得 3、{@code 2*-3} 得 -3、
 * {@code 10/-2} 得 Infinity——计算器算错而不报错，比报错糟糕得多。</p>
 *
 * <p>文法：</p>
 * <pre>
 * expression := term (('+' | '-') term)*
 * term       := unary (('*' | '/' | '%') unary)*
 * unary      := ('+' | '-') unary | power
 * power      := primary ('^' unary)?
 * primary    := number | constant | function '(' args ')' | '(' expression ')'
 * </pre>
 */
public final class SimpleEval {

    private final String text;
    private int position;

    private SimpleEval(String text) {
        this.text = text;
    }

    /**
     * 求值。
     *
     * @throws IllegalArgumentException 表达式为空、含非法字符、括号不匹配或函数参数个数不对
     */
    public static double eval(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            throw new IllegalArgumentException("expression is empty");
        }
        SimpleEval parser = new SimpleEval(expression);
        double value = parser.expression();
        parser.skipSpaces();
        if (parser.position < parser.text.length()) {
            throw parser.error("unexpected '" + parser.text.charAt(parser.position) + "'");
        }
        return value;
    }

    private double expression() {
        double value = term();
        while (true) {
            if (accept('+')) {
                value += term();
            } else if (accept('-')) {
                value -= term();
            } else {
                return value;
            }
        }
    }

    private double term() {
        double value = unary();
        while (true) {
            if (accept('*')) {
                value *= unary();
            } else if (accept('/')) {
                value /= unary();
            } else if (accept('%')) {
                value %= unary();
            } else {
                return value;
            }
        }
    }

    private double unary() {
        if (accept('-')) {
            return -unary();
        }
        if (accept('+')) {
            return unary();
        }
        return power();
    }

    /** 乘方右结合，且指数允许带符号：{@code 2^-1 = 0.5}，{@code 2^3^2 = 2^9}。 */
    private double power() {
        double base = primary();
        if (accept('^')) {
            return Math.pow(base, unary());
        }
        return base;
    }

    private double primary() {
        skipSpaces();
        if (accept('(')) {
            double value = expression();
            expect(')');
            return value;
        }
        if (position < text.length()) {
            char ch = text.charAt(position);
            if (Character.isDigit(ch) || ch == '.') {
                return number();
            }
            if (Character.isLetter(ch)) {
                return identifier();
            }
        }
        throw error(position < text.length()
                ? "unexpected '" + text.charAt(position) + "'"
                : "unexpected end of expression");
    }

    private double number() {
        int start = position;
        while (position < text.length()
                && (Character.isDigit(text.charAt(position)) || text.charAt(position) == '.')) {
            position++;
        }
        String literal = text.substring(start, position);
        try {
            return Double.parseDouble(literal);
        } catch (NumberFormatException malformed) {
            throw error("malformed number '" + literal + "'");
        }
    }

    private double identifier() {
        int start = position;
        while (position < text.length()
                && (Character.isLetterOrDigit(text.charAt(position)) || text.charAt(position) == '.')) {
            position++;
        }
        String name = text.substring(start, position);
        String bare = name.regionMatches(true, 0, "Math.", 0, 5) ? name.substring(5) : name;
        switch (bare.toLowerCase(Locale.ROOT)) {
            case "pi":
                return Math.PI;
            case "e":
                return Math.E;
            case "sqrt":
                return Math.sqrt(arguments(name, 1)[0]);
            case "abs":
                return Math.abs(arguments(name, 1)[0]);
            case "pow": {
                double[] args = arguments(name, 2);
                return Math.pow(args[0], args[1]);
            }
            default:
                throw new IllegalArgumentException("unknown name '" + name + "' at position " + (start + 1));
        }
    }

    private double[] arguments(String function, int count) {
        expect('(');
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                expect(',');
            }
            values[i] = expression();
        }
        skipSpaces();
        if (position < text.length() && text.charAt(position) == ',') {
            throw error(function + " takes " + count + " argument(s)");
        }
        expect(')');
        return values;
    }

    private boolean accept(char expected) {
        skipSpaces();
        if (position < text.length() && text.charAt(position) == expected) {
            position++;
            return true;
        }
        return false;
    }

    private void expect(char expected) {
        if (!accept(expected)) {
            throw error("expected '" + expected + "'");
        }
    }

    private void skipSpaces() {
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
            position++;
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at position " + (position + 1));
    }
}
