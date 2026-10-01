package com.aqishi.toolbox.feature.cloud.ui;

import com.aqishi.toolbox.feature.cloud.application.KubectlPortForward.State;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PortForwardSessionsViewTest {
    private PortForwardSessionsView.Row row(State state, String log) {
        return new PortForwardSessionsView.Row("default / service/api", "127.0.0.1:8000", "80", state, log);
    }

    @Test void refreshPreservesSelectedSessionAndLogSelection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var view = new PortForwardSessionsView(() -> {}, () -> {});
            var original = List.of(row(State.STOPPED, "first"), row(State.RUNNING, "line one\nline two\n"));
            view.update(original); view.select(1);
            JTextArea log = field(view, "log", JTextArea.class);
            JCheckBox follow = field(view, "follow", JCheckBox.class);
            follow.setSelected(false); log.select(0,8);
            var document = log.getDocument();
            for (int i=0;i<10;i++) view.update(original);
            assertSame(document, log.getDocument()); assertEquals(1, view.selectedIndex());
            assertEquals("line one", log.getSelectedText());
            view.update(List.of(row(State.STOPPED, "first"), row(State.RUNNING, "line one\nline two\nline three\n")));
            assertEquals("line one", log.getSelectedText()); assertEquals(1, view.selectedIndex());
            assertTrue(field(view, "copy", JButton.class).isEnabled());
            view.update(List.of(row(State.STOPPED, "first"), row(State.FAILED, "failure")));
            assertFalse(field(view, "copy", JButton.class).isEnabled()); assertFalse(field(view, "stop", JButton.class).isEnabled());
        });
    }

    @Test void changingSelectionShowsCorrectLogImmediatelyAndFollowScrolls() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var view = new PortForwardSessionsView(() -> {}, () -> {});
            assertFalse(field(view, "copy", JButton.class).isEnabled());
            view.update(List.of(row(State.STARTING, "starting"), row(State.RUNNING, "listening")));
            view.select(0); JTextArea log = field(view, "log", JTextArea.class);
            assertEquals("starting", log.getText()); assertTrue(field(view, "stop", JButton.class).isEnabled());
            assertFalse(field(view, "copy", JButton.class).isEnabled());
            view.select(1); assertEquals("listening", log.getText()); assertEquals(log.getDocument().getLength(), log.getCaretPosition());
            view.update(List.of()); assertEquals("", log.getText()); assertFalse(field(view, "stop", JButton.class).isEnabled());
        });
    }
    private static <T>T field(Object target,String name,Class<T> type) {
        try { var f=target.getClass().getDeclaredField(name); f.setAccessible(true);return type.cast(f.get(target)); }
        catch(Exception error) { throw new AssertionError(error); }
    }
}
