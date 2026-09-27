package com.aqishi.toolbox.feature.network.domain;

/**
 * 时间线上的一秒：按<em>完成时刻</em>归入计量开始后的第 {@code second} 秒（从 0 起）。
 *
 * @param second     计量开始后的秒序号
 * @param requests   这一秒内完成的计量请求数（含失败）
 * @param errors     其中失败的数量
 * @param bytes      这一秒内收到的响应体字节数
 * @param p50Micros  这一秒内有响应的请求的 p50 延迟（微秒），没有响应时为 0
 * @param p99Micros  同上的 p99
 */
public record BenchSecond(long second, long requests, long errors, long bytes,
                          long p50Micros, long p99Micros) {
}
