package com.aqishi.toolbox.crypto.acme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cloudflare DNS API v4 交互工具，用于 ACME DNS-01 自动解析验证
 */
public class CloudflareDnsProvider {

    private static final String CF_API_BASE = "https://api.cloudflare.com/client/v4";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Map<String, String> zoneCache = new ConcurrentHashMap<>();

    /**
     * 根据域名获取对应的 Cloudflare Zone ID (支持自动向上向上追溯主域名)
     */
    public static String getZoneId(String apiToken, String domain) throws Exception {
        String cleanDomain = domain.replaceAll("^\\*\\.", "").trim();

        if (zoneCache.containsKey(cleanDomain)) {
            return zoneCache.get(cleanDomain);
        }

        String searchName = cleanDomain;
        while (searchName.contains(".")) {
            String url = CF_API_BASE + "/zones?name=" + URLEncoder.encode(searchName, "UTF-8");
            HttpResponse resp = sendApiRequest(apiToken, "GET", url, null);

            if (resp.statusCode == 200) {
                JsonNode root = mapper.readTree(resp.body);
                if (root.get("success").asBoolean() && root.get("result").size() > 0) {
                    String zoneId = root.get("result").get(0).get("id").asText();
                    zoneCache.put(cleanDomain, zoneId);
                    return zoneId;
                }
            }

            // 裁剪上一级域名重试
            int firstDot = searchName.indexOf('.');
            if (firstDot > 0 && firstDot < searchName.lastIndexOf('.')) {
                searchName = searchName.substring(firstDot + 1);
            } else {
                break;
            }
        }

        throw new RuntimeException("未能通过 Cloudflare API 获取域名 [" + cleanDomain + "] 的 Zone ID，请检查 API Token 权限或域名托管状态");
    }

    /**
     * 添加 TXT 解析记录，返回记录 ID
     */
    public static String addTxtRecord(String apiToken, String domain, String recordName, String recordValue) throws Exception {
        String zoneId = getZoneId(apiToken, domain);
        String url = CF_API_BASE + "/zones/" + zoneId + "/dns_records";

        ObjectNode body = mapper.createObjectNode();
        body.put("type", "TXT");
        body.put("name", recordName);
        body.put("content", recordValue);
        body.put("ttl", 120);

        HttpResponse resp = sendApiRequest(apiToken, "POST", url, mapper.writeValueAsString(body));
        if (resp.statusCode != 200 && resp.statusCode != 201) {
            throw new RuntimeException("在 Cloudflare 创建 TXT 记录失败 [HTTP " + resp.statusCode + "]: " + resp.body);
        }

        JsonNode root = mapper.readTree(resp.body);
        if (!root.get("success").asBoolean()) {
            throw new RuntimeException("Cloudflare API 返回错误: " + resp.body);
        }

        return root.get("result").get("id").asText();
    }

    /**
     * 删除指定的 TXT 记录
     */
    public static void deleteTxtRecord(String apiToken, String domain, String recordId) throws Exception {
        String zoneId = getZoneId(apiToken, domain);
        String url = CF_API_BASE + "/zones/" + zoneId + "/dns_records/" + recordId;

        HttpResponse resp = sendApiRequest(apiToken, "DELETE", url, null);
        if (resp.statusCode != 200) {
            throw new RuntimeException("删除 Cloudflare TXT 记录失败 [HTTP " + resp.statusCode + "]: " + resp.body);
        }
    }

    private static class HttpResponse {
        int statusCode;
        String body;
    }

    private static HttpResponse sendApiRequest(String apiToken, String method, String urlStr, String jsonBody) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Authorization", "Bearer " + apiToken.trim());
        conn.setRequestProperty("Content-Type", "application/json");

        if (jsonBody != null && !jsonBody.isEmpty()) {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
        }

        HttpResponse response = new HttpResponse();
        response.statusCode = conn.getResponseCode();

        InputStream is = (response.statusCode >= 200 && response.statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
        if (is != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                response.body = sb.toString();
            }
        } else {
            response.body = "";
        }

        conn.disconnect();
        return response;
    }
}
