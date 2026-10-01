package com.aqishi.toolbox.ui.kit;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import static org.junit.jupiter.api.Assertions.*;

class WrappingNoteTest {
    @Test void longNotesWrapAndRecoverHeightWhenResized() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JLabel note = Fields.note("Export the current snapshot without rerunning SQL. Truncated results export only the loaded rows. ".repeat(3));
            note.setSize(600, 10); int wide = note.getPreferredSize().height;
            note.setSize(230, 10); int narrow = note.getPreferredSize().height;
            assertTrue(narrow > wide, "A narrower note must report the height of its wrapped lines");
            assertTrue(narrow > note.getFontMetrics(note.getFont()).getHeight() * 3);
            note.setSize(600, 10); assertEquals(wide, note.getPreferredSize().height);
        });
    }
}
