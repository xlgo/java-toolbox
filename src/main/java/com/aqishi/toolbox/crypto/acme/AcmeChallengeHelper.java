package com.aqishi.toolbox.crypto.acme;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * ACME HTTP-01 验证辅助工具：内置 HttpServer 服务与本地文件写入
 */
public class AcmeChallengeHelper {

    private static HttpServer server;
    private static int currentPort = -1;
    private static final Map<String, String> tokenToAuthorizationMap = new ConcurrentHashMap<>();

    /**
     * 注册 HTTP-01 响应 token 和 keyAuthorization
     */
    public static void registerToken(String token, String keyAuthorization) {
        tokenToAuthorizationMap.put(token, keyAuthorization);
    }

    /**
     * 移除 token 响应
     */
    public static void unregisterToken(String token) {
        tokenToAuthorizationMap.remove(token);
    }

    /**
     * 清空所有的 token
     */
    public static void clearTokens() {
        tokenToAuthorizationMap.clear();
    }

    /**
     * 启动内置 HTTP-01 服务器
     */
    public static synchronized void startHttpServer(int port) throws IOException {
        if (server != null) {
            if (currentPort == port) {
                return; // 已在运行
            }
            stopHttpServer();
        }

        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/.well-known/acme-challenge/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String path = exchange.getRequestURI().getPath();
                String token = path.substring(path.lastIndexOf('/') + 1);

                String keyAuth = tokenToAuthorizationMap.get(token);
                if (keyAuth != null) {
                    byte[] response = keyAuth.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(200, response.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response);
                    }
                } else {
                    String notFound = "Token not registered";
                    byte[] response = notFound.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(444, response.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response);
                    }
                }
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        currentPort = port;
    }

    /**
     * 停止内置 HTTP-01 服务器
     */
    public static synchronized void stopHttpServer() {
        if (server != null) {
            server.stop(0);
            server = null;
            currentPort = -1;
        }
    }

    /**
     * 检查服务是否正在运行
     */
    public static synchronized boolean isServerRunning() {
        return server != null;
    }

    /**
     * 写入 Challenge Token 到本地指定的 Web Directory 路径
     */
    public static File writeChallengeToFile(String webDirRoot, String token, String keyAuthorization) throws IOException {
        File challengeDir = new File(webDirRoot, ".well-known/acme-challenge");
        if (!challengeDir.exists()) {
            if (!challengeDir.mkdirs()) {
                throw new IOException("无法创建目录: " + challengeDir.getAbsolutePath());
            }
        }
        File tokenFile = new File(challengeDir, token);
        Files.write(tokenFile.toPath(), keyAuthorization.getBytes(StandardCharsets.UTF_8));
        return tokenFile;
    }
}
