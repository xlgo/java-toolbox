package com.aqishi.toolbox.misc;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;

class BpmnPanelLazyInitializationTest {

    @Test
    void constructingPanelDoesNotAccessCanvasBeforeViewIsBuilt() throws Exception {
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> uncaught.set(error));
        try {
            SwingUtilities.invokeAndWait(BpmnPanel::new);
            SwingUtilities.invokeAndWait(() -> { });
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }

        assertNull(uncaught.get(), "constructor must not schedule work that needs a built canvas");
    }
}
