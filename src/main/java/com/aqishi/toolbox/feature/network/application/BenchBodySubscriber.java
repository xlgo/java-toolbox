package com.aqishi.toolbox.feature.network.application;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * 压测用的响应体订阅者：只数字节，需要做"响应体包含"断言时才保留前 {@link #CAPTURE_LIMIT} 字节。
 *
 * <p>压测不关心响应内容，把每个响应体都攒成字节数组或字符串会让压测端自己先被 GC 拖慢。
 * 断言只检查前 1 MiB，超出部分照样计入字节数但不再保存。</p>
 */
final class BenchBodySubscriber implements HttpResponse.BodySubscriber<BenchBodySubscriber.Body> {

    static final int CAPTURE_LIMIT = 1 << 20;

    /** 响应体的统计结果。 */
    static final class Body {
        final long bytes;
        final boolean containsExpected;

        Body(long bytes, boolean containsExpected) {
            this.bytes = bytes;
            this.containsExpected = containsExpected;
        }
    }

    private final String needle;
    private final CompletableFuture<Body> result = new CompletableFuture<>();
    private ByteArrayOutputStream captured;
    private long bytes;

    /** @param needle 期望包含的文本；空串表示不检查 */
    BenchBodySubscriber(String needle) {
        this.needle = needle == null ? "" : needle;
        if (!this.needle.isEmpty()) {
            captured = new ByteArrayOutputStream();
        }
    }

    static HttpResponse.BodyHandler<Body> handler(String needle) {
        return info -> new BenchBodySubscriber(needle);
    }

    @Override
    public CompletionStage<Body> getBody() {
        return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
        for (ByteBuffer buffer : items) {
            int remaining = buffer.remaining();
            bytes += remaining;
            if (captured != null && captured.size() < CAPTURE_LIMIT) {
                int take = Math.min(remaining, CAPTURE_LIMIT - captured.size());
                byte[] chunk = new byte[take];
                buffer.get(chunk);
                captured.write(chunk, 0, take);
            }
            buffer.position(buffer.limit());
        }
    }

    @Override
    public void onError(Throwable throwable) {
        result.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
        boolean contains = true;
        if (captured != null) {
            contains = new String(captured.toByteArray(), StandardCharsets.UTF_8).contains(needle);
        }
        result.complete(new Body(bytes, contains));
    }
}
