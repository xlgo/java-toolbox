package com.aqishi.toolbox.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 支持同组广播与转发的信令中转服务器。
 */
public class DesktopSignalServer extends WebSocketServer {

    private final Map<String, WebSocket> idToWs = new ConcurrentHashMap<>();
    private final Map<WebSocket, String> wsToId = new ConcurrentHashMap<>();
    private final Map<WebSocket, String> wsToGroup = new ConcurrentHashMap<>();
    private final Map<WebSocket, String> wsToName = new ConcurrentHashMap<>();

    private final ObjectMapper mapper = new ObjectMapper();
    private Consumer<String> logListener;

    public DesktopSignalServer(int port) {
        super(new InetSocketAddress(port));
    }

    public void setLogListener(Consumer<String> logListener) {
        this.logListener = logListener;
    }

    private void log(String msg) {
        if (logListener != null) {
            logListener.accept(msg);
        } else {
            System.out.println("[SignalServer] " + msg);
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        log("接收到连接: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String id = wsToId.remove(conn);
        String group = wsToGroup.remove(conn);
        wsToName.remove(conn);
        if (id != null) {
            idToWs.remove(id);
            log("客户端断开: ID=" + id + ", Group=" + group);
            if (group != null) {
                broadcastUserList(group);
            }
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            Map<String, Object> json = mapper.readValue(message, Map.class);
            String type = (String) json.get("type");
            if (type == null) return;

            switch (type) {
                case "join": {
                    String id = (String) json.get("id");
                    String group = (String) json.get("group");
                    String name = (String) json.get("name");

                    if (id == null || id.trim().isEmpty()) {
                        id = "RD-" + (int)((Math.random() * 9 + 1) * 100000);
                    }
                    if (group == null || group.trim().isEmpty()) {
                        group = "default";
                    }
                    if (name == null) {
                        name = "";
                    }

                    idToWs.put(id, conn);
                    wsToId.put(conn, id);
                    wsToGroup.put(conn, group);
                    wsToName.put(conn, name);

                    log("加入组成功: ID=" + id + ", Group=" + group + ", Name=" + name);
                    
                    // 发送 joined 回执
                    Map<String, Object> joinedResp = new HashMap<>();
                    joinedResp.put("type", "joined");
                    joinedResp.put("id", id);
                    joinedResp.put("group", group);
                    conn.send(mapper.writeValueAsString(joinedResp));
                    
                    // 广播当前组最新的在线成员列表
                    broadcastUserList(group);
                    break;
                }
                case "msg": {
                    String toId = (String) json.get("to");
                    String msgStr = (String) json.get("msg");
                    if (msgStr == null) {
                        msgStr = (String) json.get("data");
                    }
                    
                    String fromId = wsToId.get(conn);
                    if (fromId == null || toId == null || msgStr == null) return;

                    WebSocket targetWs = idToWs.get(toId);
                    if (targetWs != null && targetWs.isOpen()) {
                        Map<String, Object> forward = new HashMap<>();
                        forward.put("type", "msg");
                        forward.put("from", fromId);
                        forward.put("msg", msgStr);
                        forward.put("data", msgStr);

                        targetWs.send(mapper.writeValueAsString(forward));
                    } else {
                        log("转发失败: 目标 ID " + toId + " 不在线");
                    }
                    break;
                }
                case "offer":
                case "answer":
                case "ice": {
                    String toId = (String) json.get("to");
                    String fromId = wsToId.get(conn);
                    if (fromId == null || toId == null) return;

                    WebSocket targetWs = idToWs.get(toId);
                    if (targetWs != null && targetWs.isOpen()) {
                        json.put("from", fromId);
                        targetWs.send(mapper.writeValueAsString(json));
                    } else {
                        log("转发 " + type + " 失败: 目标 ID " + toId + " 不在线");
                    }
                    break;
                }
            }
        } catch (Exception e) {
            log("处理信令消息失败: " + e.getMessage());
        }
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        // 兼容处理纯二进制转发 (如果在组会话中有指定绑定)
    }

    private void broadcastUserList(String group) {
        List<Map<String, String>> usersList = new ArrayList<>();
        List<WebSocket> groupConns = new ArrayList<>();

        for (Map.Entry<WebSocket, String> entry : wsToGroup.entrySet()) {
            if (group.equals(entry.getValue())) {
                WebSocket conn = entry.getKey();
                groupConns.add(conn);

                Map<String, String> userMap = new HashMap<>();
                userMap.put("id", wsToId.get(conn));
                userMap.put("name", wsToName.get(conn));
                usersList.add(userMap);
            }
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("type", "peers");
        resp.put("peers", usersList);

        try {
            String jsonResp = mapper.writeValueAsString(resp);
            for (WebSocket conn : groupConns) {
                if (conn.isOpen()) {
                    conn.send(jsonResp);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        log("服务器异常: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        log("信令中转服务器启动成功，监听端口: " + getPort());
    }
}
