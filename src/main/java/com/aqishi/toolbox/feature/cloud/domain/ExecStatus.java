package com.aqishi.toolbox.feature.cloud.domain;

import com.aqishi.toolbox.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Kubernetes exec（v4 协议）在 3 号通道上发回的命令结束状态。
 *
 * <p>命令正常结束时服务器也会发 {@code {"metadata":{},"status":"Success"}}。早先把 3 号通道
 * 与 stderr 一起当成"有输出就失败"，于是每次下载都被判为失败并删掉已下载的文件。
 * 成败应以这条状态为准：stderr 上的内容（例如 tar 的 "Removing leading '/'" 提示）只是附加信息。</p>
 */
public final class ExecStatus {

    private final boolean success;
    private final String message;

    private ExecStatus(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    /**
     * 解析 3 号通道收到的全部文本。
     *
     * @param channel3 可能为空：旧版协议或连接异常断开时服务器不会发状态
     * @param stderr   2 号通道累积的文本
     */
    public static ExecStatus of(String channel3, String stderr) {
        String status = channel3 == null ? "" : channel3.trim();
        String errorText = stderr == null ? "" : stderr.trim();
        if (status.isEmpty()) {
            // 没有状态帧：只能退回"stderr 为空即成功"的旧判断。
            return errorText.isEmpty() ? new ExecStatus(true, "") : new ExecStatus(false, errorText);
        }
        try {
            JsonNode root = Json.mapper().readTree(status);
            if ("Success".equals(root.path("status").asText())) {
                return new ExecStatus(true, errorText);
            }
            String reason = root.path("message").asText(root.path("reason").asText(status));
            return new ExecStatus(false, errorText.isEmpty() ? reason : reason + "\n" + errorText);
        } catch (Exception notJson) {
            return new ExecStatus(false, errorText.isEmpty() ? status : status + "\n" + errorText);
        }
    }

    public boolean isSuccess() {
        return success;
    }

    /** 失败原因；成功时为 stderr 上的附加提示（可能为空）。 */
    public String message() {
        return message;
    }
}
