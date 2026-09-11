package com.aqishi.toolbox.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 {@link UIUtils} 的对话框门面。
 *
 * <p>这些方法是全应用 360 余处提示的唯一出口，却长期没有测试：一次脚本化替换曾把
 * 它们改成自调用，导致任何提示框都直接 {@code StackOverflowError}，而编译、构建、
 * 既有测试全部通过。因此这里逐个断言「恰好转发一次、消息体原样、级别正确」。</p>
 */
class UIUtilsDialogTest {

    /** 记录每次转发的出口实现，避免测试真的弹窗 */
    private static final class RecordingSink implements UIUtils.DialogSink {
        private final List<String> titles = new ArrayList<>();
        private final List<Object> messages = new ArrayList<>();
        private final List<Integer> types = new ArrayList<>();
        private String inputResult;
        private boolean confirmResult;

        @Override
        public void message(Component parent, Object msg, String title, int messageType) {
            messages.add(msg);
            titles.add(title);
            types.add(messageType);
        }

        @Override
        public String input(Component parent, Object msg, String title, String initialValue) {
            messages.add(msg);
            titles.add(title);
            return inputResult;
        }

        @Override
        public boolean confirm(Component parent, Object msg, String title) {
            messages.add(msg);
            titles.add(title);
            return confirmResult;
        }

        int calls() {
            return messages.size();
        }
    }

    private final RecordingSink sink = new RecordingSink();

    @BeforeEach
    void installRecordingSink() {
        UIUtils.setDialogSink(sink);
    }

    @AfterEach
    void restoreDefaultSink() {
        UIUtils.setDialogSink(null);
    }

    @Test
    @DisplayName("info/warn/error/dialog 各自转发一次并带上对应的消息级别")
    void messageDialogsForwardOnceWithMatchingLevel() {
        UIUtils.info(null, "i");
        UIUtils.warn(null, "w", "T");
        UIUtils.error(null, "e");
        UIUtils.dialog(null, "d", "T");

        assertEquals(4, sink.calls(), "每个方法应恰好转发一次；递归或漏转发都会让这里失败");
        assertEquals(List.of("i", "w", "e", "d"), sink.messages);
        assertEquals(
                List.of(JOptionPane.INFORMATION_MESSAGE, JOptionPane.WARNING_MESSAGE,
                        JOptionPane.ERROR_MESSAGE, JOptionPane.PLAIN_MESSAGE),
                sink.types);
    }

    @Test
    @DisplayName("省略标题时回退到本地化默认标题，且不为空")
    void omittedTitleFallsBackToLocalizedDefault() {
        UIUtils.info(null, "m");
        UIUtils.error(null, "m");
        UIUtils.warn(null, "m", null);
        UIUtils.input(null, "m", null);
        UIUtils.confirm(null, "m", null);

        assertEquals(5, sink.calls());
        for (String title : sink.titles) {
            assertTrue(title != null && !title.isBlank(), "默认标题不应为空：" + title);
        }
    }

    @Test
    @DisplayName("显式标题原样透传，不被默认值覆盖")
    void explicitTitleIsPassedThrough() {
        UIUtils.error(null, "m", "自定义标题");
        assertEquals(List.of("自定义标题"), sink.titles);
    }

    @Test
    @DisplayName("消息体按 Object 透传，支持嵌套组件而不被 toString")
    void messageIsForwardedAsObject() {
        Object panel = new Object();
        UIUtils.dialog(null, panel, "T");
        assertSame(panel, sink.messages.get(0));
    }

    @Test
    @DisplayName("input 与 confirm 原样返回出口结果")
    void inputAndConfirmReturnSinkResult() {
        sink.inputResult = "typed";
        sink.confirmResult = true;
        assertEquals("typed", UIUtils.input(null, "m", "def"));
        assertTrue(UIUtils.confirm(null, "m", "T"));

        sink.inputResult = null;
        sink.confirmResult = false;
        assertNull(UIUtils.input(null, "m", "def"), "取消输入应返回 null");
        assertFalse(UIUtils.confirm(null, "m", "T"));
    }

    @Test
    @DisplayName("传 null 可恢复默认 Swing 出口，避免测试间串台")
    void nullSinkRestoresDefault() {
        UIUtils.setDialogSink(null);
        UIUtils.setDialogSink(sink);
        UIUtils.info(null, "m");
        assertEquals(1, sink.calls());
    }
}
