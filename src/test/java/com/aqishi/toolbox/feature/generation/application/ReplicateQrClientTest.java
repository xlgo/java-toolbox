package com.aqishi.toolbox.feature.generation.application;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplicateQrClientTest {

    @Test
    void readsStatusUrlFromSubmitResponse() throws IOException {
        assertEquals("https://api.replicate.com/v1/predictions/abc",
                ReplicateQrClient.parseSubmit("{\"id\":\"abc\",\"urls\":{\"get\":\"https://api.replicate.com/v1/predictions/abc\"}}")
                        .toString());
    }

    /** 缺 urls.get 时以前会用空串去轮询，每次都失败又被吞掉；现在立即以不可重试错误结束。 */
    @Test
    void rejectsSubmitResponseWithoutStatusUrl() {
        ReplicateQrClient.ReplicateException error = assertThrows(ReplicateQrClient.ReplicateException.class,
                () -> ReplicateQrClient.parseSubmit("{\"detail\":\"Invalid version\"}"));
        assertFalse(error.isRetryable());
    }

    @Test
    void treatsIntermediateStatesAsRunning() throws IOException {
        for (String state : new String[]{"starting", "processing", "something-new"}) {
            ReplicateQrClient.Status status =
                    ReplicateQrClient.parseStatus("{\"status\":\"" + state + "\"}");
            assertEquals(ReplicateQrClient.State.RUNNING, status.state(), state);
            assertFalse(status.isTerminal());
        }
    }

    @Test
    void readsImageFromArrayOrStringOutput() throws IOException {
        assertEquals("https://x/1.png", ReplicateQrClient.parseStatus(
                "{\"status\":\"succeeded\",\"output\":[\"https://x/1.png\"]}").imageUrl());
        assertEquals("https://x/2.png", ReplicateQrClient.parseStatus(
                "{\"status\":\"succeeded\",\"output\":\"https://x/2.png\"}").imageUrl());
    }

    /** 回归：succeeded 却没有 output 时，旧代码在轮询线程里 NPE 然后被吞掉，永远轮询下去。 */
    @Test
    void succeededWithoutOutputIsAFailure() throws IOException {
        ReplicateQrClient.Status status = ReplicateQrClient.parseStatus("{\"status\":\"succeeded\",\"output\":[]}");

        assertEquals(ReplicateQrClient.State.FAILED, status.state());
        assertTrue(status.isTerminal());
        assertNull(status.imageUrl());
    }

    @Test
    void reportsFailureReason() throws IOException {
        ReplicateQrClient.Status status =
                ReplicateQrClient.parseStatus("{\"status\":\"failed\",\"error\":\"NSFW content\"}");

        assertEquals(ReplicateQrClient.State.FAILED, status.state());
        assertEquals("NSFW content", status.error());
        assertEquals("canceled", ReplicateQrClient.parseStatus("{\"status\":\"canceled\"}").error());
    }
}
