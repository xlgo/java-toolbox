package com.aqishi.toolbox.feature.codec.ui;

import com.aqishi.toolbox.util.I18n;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AsyncTextToolsTest {
    @Test void slowRegexDoesNotBlockEdtAndNextInputCanMatch() throws Exception {
        RegexPanel panel = new RegexPanel();
        try {
            List<JTextArea> areas = new ArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                JComponent view = panel.getView();
                collect(view, JTextArea.class, areas);
                List<JTextField> fields = new ArrayList<>();
                collect(view, JTextField.class, fields);
                fields.get(0).setText("(a+)+$");
                areas.get(0).setText("a".repeat(40_000) + "!");
            });
            await(() -> areas.get(1).getText().contains(I18n.get("regex.timeout")));
            SwingUtilities.invokeAndWait(() -> areas.get(0).setText("aaa"));
            await(() -> areas.get(1).getText().startsWith("【aaa】"));
        } finally { SwingUtilities.invokeAndWait(panel::closeResources); }
    }

    @Test void clearingDiffCancelsWorkAndLateCompletionDoesNotRefillOutput() throws Exception {
        TextDiffPanel panel = new TextDiffPanel();
        List<JTextArea> areas = new ArrayList<>();
        List<JTextPane> results = new ArrayList<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                JComponent view = panel.getView();
                collect(view, JTextArea.class, areas);
                collect(view, JTextPane.class, results);
                button(view, "清空").doClick();
                areas.get(0).setText("a\nb\n".repeat(1200));
                areas.get(1).setText("b\na\n".repeat(1200));
                button(view, "对比差异").doClick();
                button(view, "清空").doClick();
            });
            // Drain cancellation callbacks after the worker's interruption has been observed.
            Thread.sleep(150);
            SwingUtilities.invokeAndWait(() -> assertEquals("", results.get(0).getText()));
        } finally { SwingUtilities.invokeAndWait(panel::closeResources); }
    }

    @Test void identicalLargeDiffCanRenderWithoutQuadraticMatrix() throws Exception {
        TextDiffPanel panel = new TextDiffPanel();
        List<JTextArea> areas = new ArrayList<>();
        List<JTextPane> results = new ArrayList<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                JComponent view = panel.getView();
                collect(view, JTextArea.class, areas);
                collect(view, JTextPane.class, results);
                button(view, "清空").doClick();
                String sample = "line\n".repeat(5999) + "line";
                areas.get(0).setText(sample);
                areas.get(1).setText(sample);
                button(view, "对比差异").doClick();
            });
            await(() -> results.get(0).getText().lines().count() == 6000);
        } finally { SwingUtilities.invokeAndWait(panel::closeResources); }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(6);
        AtomicBoolean done = new AtomicBoolean();
        while (System.nanoTime() < end) {
            SwingUtilities.invokeAndWait(() -> done.set(condition.getAsBoolean()));
            if (done.get()) return;
            Thread.sleep(20);
        }
        fail("UI result did not arrive");
    }
    private static <T> void collect(Component root, Class<T> type, List<T> results) {
        if (type.isInstance(root)) results.add(type.cast(root));
        if (root instanceof Container container) for (Component child : container.getComponents()) collect(child, type, results);
    }
    private static JButton button(Component root, String label) {
        List<JButton> buttons = new ArrayList<>();
        collect(root, JButton.class, buttons);
        return buttons.stream().filter(b -> label.equals(b.getText())).findFirst().orElseThrow();
    }
}
