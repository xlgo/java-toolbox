package com.aqishi.toolbox.feature.compute.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    /** 回归：旧实现把运算符后的负号当成 "0-"，2*-3 得 -3、10/-2 得 Infinity。 */
    @Test
    void appliesUnaryMinusAfterOperators() {
        assertEquals(-6.0, eval("2*-3"), 1e-9);
        assertEquals(-5.0, eval("10/-2"), 1e-9);
        assertEquals(5.0, eval("2--3"), 1e-9);
        assertEquals(-4.0, eval("-(1+3)"), 1e-9);
    }

    /** 回归：函数按钮插入的是 sqrt/pow/pi/e，旧实现不认识，一直报错。 */
    @Test
    void supportsFunctionsAndConstants() {
        assertEquals(3.0, eval("sqrt(9)"), 1e-9);
        assertEquals(8.0, eval("pow(2, 3)"), 1e-9);
        assertEquals(Math.PI * 2, eval("2*pi"), 1e-9);
        assertEquals(Math.E, eval("e"), 1e-9);
        assertEquals(5.0, eval("abs(-5)"), 1e-9);
        assertEquals(4.0, eval("sqrt(pow(2,4))"), 1e-9);
    }

    @Test
    void acceptsLegacyMathPrefix() {
        assertEquals(3.0, eval("Math.sqrt(9)"), 1e-9);
        assertEquals(Math.PI, eval("Math.PI"), 1e-9);
    }

    @Test
    void powerIsRightAssociativeAndBindsTighterThanUnaryMinus() {
        assertEquals(512.0, eval("2^3^2"), 1e-9);
        assertEquals(0.5, eval("2^-1"), 1e-9);
        assertEquals(-4.0, eval("-2^2"), 1e-9);
    }

    /** 回归：旧实现静默跳过不认识的字符，2x3 得 3、abc 得 0。 */
    @Test
    void rejectsUnknownInputInsteadOfGuessing() {
        assertThrows(IllegalArgumentException.class, () -> eval("2x3"));
        assertThrows(IllegalArgumentException.class, () -> eval("abc"));
        assertThrows(IllegalArgumentException.class, () -> eval("(1+2"));
        assertThrows(IllegalArgumentException.class, () -> eval("1+"));
        assertThrows(IllegalArgumentException.class, () -> eval("pow(1)"));
        assertThrows(IllegalArgumentException.class, () -> eval("sqrt(1,2)"));
        assertThrows(IllegalArgumentException.class, () -> eval("1..2"));
        assertThrows(IllegalArgumentException.class, () -> eval(" "));
    }
}
