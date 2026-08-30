package com.aqishi.toolbox.feature.compute.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimpleEvalTest {

    private static double eval(String expr) {
        return SimpleEval.eval(expr);
    }

    @Test
    void evaluatesBasicArithmetic() {
        assertEquals(7.0, eval("3+4"), 1e-9);
        assertEquals(2.0, eval("6/3"), 1e-9);
        assertEquals(6.0, eval("2*3"), 1e-9);
        assertEquals(1.0, eval("7%2"), 1e-9);
    }

    @Test
    void respectsOperatorPrecedence() {
        assertEquals(14.0, eval("2+3*4"), 1e-9);
        assertEquals(8.0, eval("2*3+14/7"), 1e-9);
        assertEquals(1.5, eval("1+2*3%5/2"), 1e-9);
    }

    @Test
    void evaluatesParentheses() {
        assertEquals(20.0, eval("(2+3)*4"), 1e-9);
        assertEquals(9.0, eval("((1+2))*3"), 1e-9);
        assertEquals(2.0, eval("(8-(2+4))/1"), 1e-9);
    }

    @Test
    void handlesUnaryMinusAndPlus() {
        assertEquals(-3.0, eval("-3"), 1e-9);
        assertEquals(3.0, eval("+3"), 1e-9);
        assertEquals(-7.0, eval("2-3*3"), 1e-9);
        assertEquals(-6.0, eval("-2*3"), 1e-9);
        assertEquals(1.0, eval("-2+3"), 1e-9);
    }

    @Test
    void handlesDecimalsAndWhitespace() {
        assertEquals(3.5, eval("1.5 + 2"), 1e-9);
        assertEquals(0.5, eval(" 1.5 - 1 "), 1e-9);
        assertEquals(6.25, eval("2.5*2.5"), 1e-9);
    }

    @Test
    void toleratesLeadingOperatorAsZeroStart() {
        // 首字符为运算符时以 0 起始：-3 → 0-3
        assertEquals(3.0, eval("-3+6"), 1e-9);
    }
}
