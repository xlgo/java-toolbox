package com.aqishi.toolbox.ui;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolContentHostTest {

    @Test
    void mountsOnlyTheSelectedToolAndBuildsItOnce() {
        AtomicInteger firstBuilds = new AtomicInteger();
        AtomicInteger secondBuilds = new AtomicInteger();
        ToolPanel first = tool("first", firstBuilds);
        ToolPanel second = tool("second", secondBuilds);
        ToolContentHost host = new ToolContentHost(Arrays.asList(first, second));

        assertEquals(0, host.getComponentCount());
        assertTrue(host.showTool("first"));
        assertEquals(1, firstBuilds.get());
        assertEquals(0, secondBuilds.get());
        assertTrue(host.showTool("first"));
        assertEquals(1, firstBuilds.get());
        assertTrue(host.showTool("second"));
        assertEquals(1, secondBuilds.get());
        assertEquals(2, host.getComponentCount());
    }

    @Test
    void ignoresUnknownToolWithoutChangingMountedViews() {
        ToolContentHost host = new ToolContentHost(
                Arrays.asList(tool("known", new AtomicInteger())));

        assertFalse(host.showTool("missing"));
        assertEquals(0, host.getComponentCount());
    }

    private static ToolPanel tool(String id, AtomicInteger builds) {
        return new ToolPanel("group", id) {
            @Override
            protected JComponent build() {
                builds.incrementAndGet();
                return new JPanel();
            }
        };
    }
}
