package com.aqishi.toolbox.feature.generation.ui;

import com.aqishi.toolbox.feature.generation.domain.SnowflakeCodec;
import com.aqishi.toolbox.feature.generation.domain.SnowflakeLayout;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdToolkitPanelTest {

    @Test
    void buildsViewWithCatalogIdentity() {
        IdToolkitPanel panel = new IdToolkitPanel();

        assertEquals("generation", panel.getGroup());
        assertEquals("id.toolkit", panel.getName());
        assertNotNull(panel.getView());
        panel.closeResources();
        panel.closeResources();
    }

    @Test
    void decodesEveryLineAndShowsDetailsForTheSelection() {
        IdToolkitPanel panel = new IdToolkitPanel();
        panel.getView();
        panel.decodeInputForTest().setText("507f1f77bcf86cd799439011\n\nnot-an-id\n01ARYZ6S41TSV4RRFFQ69G5FAV");
        panel.runDecode();

        assertEquals(3, panel.decodeTableForTest().getRowCount());
        assertEquals("2012-10-17T21:13:27Z", panel.decodeTableForTest().getValueAt(0, 3));
        assertEquals("", panel.decodeTableForTest().getValueAt(1, 3));
        assertEquals(0, panel.decodeTableForTest().getSelectedRow());
        assertTrue(panel.decodeDetailForTest().getText().contains("507f1f77bcf86cd799439011"));
        panel.closeResources();
    }

    @Test
    void editingALayoutSwitchesToCustomAndEncodes() {
        IdToolkitPanel panel = new IdToolkitPanel();
        panel.getView();
        panel.applyLayoutPreset(SnowflakeLayout.Preset.DISCORD);
        assertTrue(panel.currentLayoutForTest().sameStructure(SnowflakeLayout.Preset.DISCORD.layout()));

        panel.epochFieldForTest().setText("2021-01-01T00:00:00Z");
        assertEquals(SnowflakeLayout.Preset.CUSTOM.ordinal(), panel.layoutPresetComboForTest().getSelectedIndex());
        SnowflakeLayout custom = panel.currentLayoutForTest();
        assertEquals(Instant.parse("2021-01-01T00:00:00Z"), custom.epoch());

        panel.encodeInstantForTest().setText("2024-02-03T04:05:06.789Z");
        panel.encode();
        long id = Long.parseLong(panel.encodeResultForTest().getText());
        assertEquals(Instant.parse("2024-02-03T04:05:06.789Z"), SnowflakeCodec.decode(id, custom).timestamp());

        panel.epochFieldForTest().setText("not a date");
        assertNull(panel.currentLayoutForTest());
        panel.closeResources();
    }

    @Test
    void capacityNumbersAreReadable() {
        assertEquals("4,096", IdToolkitPanel.formatCount(4096));
        assertEquals("25.60", IdToolkitPanel.formatCount(25.6));
    }
}
