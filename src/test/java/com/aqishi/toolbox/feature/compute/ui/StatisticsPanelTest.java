package com.aqishi.toolbox.feature.compute.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StatisticsPanelTest {
    @Test void nonFiniteValuesCannotPoisonAllStatistics() {
        String input = "1,NaN,2,Infinity,-Infinity,1e999,invalid,3";
        assertArrayEquals(new double[]{1,2,3}, StatisticsPanel.parse(input));
        assertEquals(5, StatisticsPanel.countSkipped(input));
    }
    @Test void recognizesSignedDecimalsAndExponents() {
        String input = "-0.5；+2，1e3\n  \t";
        assertArrayEquals(new double[]{-0.5,2,1000}, StatisticsPanel.parse(input));
        assertEquals(0, StatisticsPanel.countSkipped(input));
    }
}
