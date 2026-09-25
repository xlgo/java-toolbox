package com.aqishi.toolbox.util;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ErrorsTest {

    @Test
    void describeUsesTypeAndMessage() {
        assertEquals("IllegalStateException: broken", Errors.describe(new IllegalStateException("broken")));
        assertEquals("IllegalStateException", Errors.describe(new IllegalStateException()));
    }

    /** HttpClient 的连接失败就长这样：外层没有 message，原因在 cause 里。 */
    @Test
    void describeRootDigsPastWrappersWithoutMessage() {
        ConnectException wrapper = new ConnectException();
        wrapper.initCause(new ConnectException("Connection refused"));

        assertEquals("ConnectException: Connection refused", Errors.describeRoot(wrapper));
    }

    /** SwingWorker.get() 抛的 ExecutionException 自带一个复述 cause 的 message，要取的是最里层。 */
    @Test
    void describeRootPrefersDeepestMessage() {
        ExecutionException wrapper = new ExecutionException(new IllegalArgumentException("bad input"));

        assertEquals("IllegalArgumentException: bad input", Errors.describeRoot(wrapper));
    }

    /** 最里层没有 message 时，退回到最近一个带说明的外层，而不是只剩一个类名。 */
    @Test
    void describeRootFallsBackToNearestMessage() {
        RuntimeException outer = new RuntimeException("load failed", new NullPointerException());

        assertEquals("RuntimeException: load failed", Errors.describeRoot(outer));
    }

    @Test
    void describeRootHandlesNull() {
        assertEquals(Errors.describe(null), Errors.describeRoot(null));
    }
}
