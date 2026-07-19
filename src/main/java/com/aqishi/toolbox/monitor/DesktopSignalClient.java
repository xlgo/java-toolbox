package com.aqishi.toolbox.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 远程桌面信令中转客户端。
 * 兼容标准 WebRTC 信令交互 (Offer, Answer, ICE Candidate)。
 */
public class DesktopSignalClient extends WebSocketClient {

    private final ObjectMapper mapper = new ObjectMapper();
    private String clientId;
    private DesktopSignalListener listener;
    private Consumer<String> logListener;
    private ScheduledExecutorService heartbeatScheduler;

    public DesktopSignalClient(URI serverUri) {
        super(serverUri);
    }

    public void setListener(DesktopSignalListener listener) {
        this.listener = listener;
    }

    public void setLogListener(Consumer<String> logListener) {
        this.logListener = logListener;
    }

    public String getClientId() {
        return clientId;
    }

    private void log(String msg) {
        if (logListener != null) {
            logListener.accept(msg);
        } else {
            System.out.println("[SignalClient] " + msg);
        }
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        log("已成功连接到信令服务器: " + getURI());
        startHeartbeat();
    }

    @Override
    public void onMessage(String message) {
        log("收到服务器原始信令: " + message);
        try {
            Map<String, Object> json = mapper.readValue(message, Map.class);
            String type = (String) json.get("type");
            if (type == null) return;

            switch (type) {
                case "joined": {
                    this.clientId = (String) json.get("id");
                    if (listener != null) {
                        listener.onRegistered(this.clientId);
                    }
                    break;
                }
                case "peers": {
                    List<Map<String, String>> peers = (List<Map<String, String>>) json.get("peers");
                    if (listener != null) {
                        listener.onUserListReceived(peers);
                    }
                    break;
                }
                case "offer": {
                    String fromId = (String) json.get("from");
                    String sdp = (String) json.get("sdp");
                    if (listener != null && fromId != null) {
                        listener.onOfferReceived(fromId, sdp);
                    }
                    break;
                }
                case "answer": {
                    String fromId = (String) json.get("from");
                    String sdp = (String) json.get("sdp");
                    if (listener != null && fromId != null) {
                        listener.onAnswerReceived(fromId, sdp);
                    }
                    break;
                }
                case "ice": {
                    String fromId = (String) json.get("from");
                    Map<String, Object> cand = (Map<String, Object>) json.get("candidate");
                    if (cand != null && listener != null && fromId != null) {
                        String candStr = (String) cand.get("candidate");
                        if (candStr != null) {
                            listener.onIceCandidateReceived(fromId, candStr);
                        }
                    }
                    break;
                }
                case "msg":
                case "send": {
                    // 收到来自其他成员的转发数据
                    String fromId = (String) json.get("from");
                    if (fromId == null) {
                        fromId = (String) json.get("fromId");
                    }
                    String content = (String) json.get("msg");
                    if (content == null) {
                        content = (String) json.get("data");
                    }
                    
                    if (fromId != null && content != null) {
                        try {
                            byte[] raw = Base64.getDecoder().decode(content);
                            if (listener != null) {
                                listener.onPeerMessage(fromId, raw);
                            }
                        } catch (IllegalArgumentException e) {
                            // 忽略非 Base64 的数据
                        }
                    }
                    break;
                }
            }
        } catch (Exception e) {
            log("解析信令数据失败: " + e.getMessage());
        }
    }

    @Override
    public void onMessage(ByteBuffer bytes) {
        // 收到纯二进制消息，交由上层处理
        if (listener != null && bytes != null) {
            byte[] data = bytes.array();
            if (data.length >= 37) {
                // 还原 from 字段
                String fromId = new String(data, 0, 36, StandardCharsets.UTF_8).trim();
                byte[] rawPayload = new byte[data.length - 36];
                System.arraycopy(data, 36, rawPayload, 0, rawPayload.length);
                listener.onPeerMessage(fromId, rawPayload);
            }
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log("信令服务器连接断开: code=" + code + ", reason=" + reason + ", remote=" + remote);
        stopHeartbeat();
        if (listener != null) {
            listener.onPeerDisconnected(null);
        }
    }

    @Override
    public void onError(Exception ex) {
        log("WebSocket 连接异常: " + ex.getMessage());
    }

    /**
     * 加入组/房间
     */
    public void join(String id, String group, String name) {
        this.clientId = id;
        try {
            Map<String, Object> msg = new ConcurrentHashMap<>();
            msg.put("type", "join");
            msg.put("id", id);
            msg.put("group", group);
            msg.put("name", name);
            
            String jsonStr = mapper.writeValueAsString(msg);
            send(jsonStr);
            log("已向服务发送 Join 请求: ID=" + id + ", Group=" + group + ", Name=" + name);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 转发数据包给目标成员
     */
    public void sendToPeer(String toId, String base64Data) {
        try {
            Map<String, Object> msg = new ConcurrentHashMap<>();
            msg.put("type", "msg");
            msg.put("to", toId);
            msg.put("msg", base64Data);
            msg.put("data", base64Data);
            
            send(mapper.writeValueAsString(msg));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void sendOffer(String toId, String sdp) {
        try {
            Map<String, Object> msg = new ConcurrentHashMap<>();
            msg.put("type", "offer");
            msg.put("to", toId);
            msg.put("sdp", sdp);
            send(mapper.writeValueAsString(msg));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void sendAnswer(String toId, String sdp) {
        try {
            Map<String, Object> msg = new ConcurrentHashMap<>();
            msg.put("type", "answer");
            msg.put("to", toId);
            msg.put("sdp", sdp);
            send(mapper.writeValueAsString(msg));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void sendIceCandidate(String toId, String candidateStr) {
        try {
            Map<String, Object> msg = new ConcurrentHashMap<>();
            msg.put("type", "ice");
            msg.put("to", toId);
            Map<String, Object> cand = new ConcurrentHashMap<>();
            cand.put("candidate", candidateStr);
            cand.put("sdpMid", "0");
            cand.put("sdpMLineIndex", 0);
            msg.put("candidate", cand);
            send(mapper.writeValueAsString(msg));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startHeartbeat() {
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (isOpen()) {
                sendPing();
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
            heartbeatScheduler = null;
        }
    }

    public interface DesktopSignalListener {
        default void onRegistered(String clientId) {}
        default void onUserListReceived(List<Map<String, String>> users) {}
        default void onPeerMessage(String fromId, byte[] rawData) {}
        default void onPeerDisconnected(String peerId) {}
        default void onOfferReceived(String fromId, String sdp) {}
        default void onAnswerReceived(String fromId, String sdp) {}
        default void onIceCandidateReceived(String fromId, String candidate) {}
    }
}
