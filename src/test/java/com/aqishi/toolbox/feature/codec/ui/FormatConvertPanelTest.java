package com.aqishi.toolbox.feature.codec.ui;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatConvertPanelTest {

    @Test
    void exposesFormatConversionViewAndNewFormatSearchTerms() {
        FormatConvertPanel panel = new FormatConvertPanel();

        assertEquals("format", panel.getGroup());
        assertEquals("format.convert", panel.getName());
        assertTrue(Arrays.asList(panel.getSearchKeywords()).contains("INI"));
        assertTrue(Arrays.asList(panel.getSearchKeywords()).contains("TOML"));
        assertNotNull(panel.getView());
    }
}
