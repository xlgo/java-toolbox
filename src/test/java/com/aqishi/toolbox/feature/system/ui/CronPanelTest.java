package com.aqishi.toolbox.feature.system.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.lang.reflect.*;
import static org.junit.jupiter.api.Assertions.*;

class CronPanelTest {
    @Test void changingAnotherFieldPreservesSundayAliasAndRangeStep() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new CronPanel();
            panel.getView();
            for (String expression : new String[]{"0 0 12 ? * 0", "0 0 12 ? * 1-5/2", "0 0,15-20 12 ? * 1"}) {
                invoke(panel, "parseAndSyncToUI", new Class<?>[]{String.class}, expression);
                Object hours = field(panel, "hourPanel");
                invoke(hours, "setFieldValue", new Class<?>[]{String.class}, "13");
                invoke(panel, "rebuildCronExpression", new Class<?>[0]);
                assertEquals(expression.replace(" 12 ", " 13 "), ((JTextField) field(panel, "input")).getText());
            }
        });
    }
    private static Object field(Object value, String name) {
        try { var f = value.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static Object invoke(Object value, String name, Class<?>[] signature, Object... args) {
        try { var m = value.getClass().getDeclaredMethod(name, signature); m.setAccessible(true); return m.invoke(value, args); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
