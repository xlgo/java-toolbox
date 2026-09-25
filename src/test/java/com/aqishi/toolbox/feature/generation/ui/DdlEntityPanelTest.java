package com.aqishi.toolbox.feature.generation.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DdlEntityPanelTest {

    @Test
    void buildsViewWithCatalogIdentity() {
        DdlEntityPanel panel = new DdlEntityPanel();

        assertEquals("generation", panel.getGroup());
        assertEquals("ddl.entity", panel.getName());
        assertNotNull(panel.getView());
        assertDoesNotThrow(panel::closeResources);
        assertDoesNotThrow(panel::closeResources);
    }

    @Test
    void sampleProducesOneFilePerTable() {
        DdlEntityPanel panel = new DdlEntityPanel();
        panel.getView();

        panel.regenerateNow();

        // 默认前缀 t_,sys_ 会被去掉
        assertEquals(List.of("User.java", "UserRole.java"), panel.fileNames());
        assertTrue(panel.codeText().contains("public class User {"), panel.codeText());
        assertTrue(panel.codeText().contains("@Data"));

        panel.selectStyle(2);
        panel.regenerateNow();
        assertTrue(panel.codeText().contains("public record User("), panel.codeText());
        panel.closeResources();
    }
}
