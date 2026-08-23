package com.aqishi.toolbox.feature.network.infra;

import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Converts a local HTTP exchange into the immutable callback-mock request model. */
public final class MockHttpRequestParser {
    public static final int MAX_BODY_BYTES = 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final FormBodyParser formBodyParser;
    private final MultipartFormDataParser multipartFormDataParser;

    public MockHttpRequestParser() {
        this(new ObjectMapper());
    }

    public MockHttpRequestParser(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper must not be null");
        }
        this.objectMapper = objectMapper;
        this.formBodyParser = new FormBodyParser();
        this.multipartFormDataParser = new MultipartFormDataParser();
    }

    public MockRequest parse(HttpExchange exchange) throws IOException {
        if (exchange == null) {
            throw new IllegalArgumentException("exchange must not be null");
        }

        URI requestUri = exchange.getRequestURI();
        MockRequest.Builder request = MockRequest.builder()
                .method(exchange.getRequestMethod())
                .path(requestUri == null ? "" : requestUri.getPath())
                .rawQuery(requestUri == null ? null : requestUri.getRawQuery())
                .clientAddress(clientAddress(exchange.getRemoteAddress()));

        addValues(request, formBodyParser.parse(
                requestUri == null ? null : requestUri.getRawQuery()), false);
        Headers headers = exchange.getRequestHeaders();
        addHeaders(request, headers);

        Body body = readBody(exchange.getRequestBody());
        request.body(body.text, body.truncated);
        if (!body.truncated) {
            parseBody(request, body.text, firstHeader(headers, "Content-Type"));
        }
        return request.build();
    }

    private void parseBody(MockRequest.Builder request, String body,
                           String contentType) {
        String mediaType = mediaType(contentType);
        if ("application/x-www-form-urlencoded".equals(mediaType)) {
            addValues(request, formBodyParser.parse(body), true);
            return;
        }
        if ("multipart/form-data".equals(mediaType)) {
            addValues(request, multipartFormDataParser.parse(body, contentType), true);
            return;
        }
        if ("application/json".equals(mediaType) || mediaType.endsWith("+json")) {
            try {
                JsonNode root = objectMapper.readTree(body);
                if (root != null) {
                    request.json(root);
                }
            } catch (IOException ignored) {
                // A malformed body is a normal no-match condition, not a server error.
            }
        }
    }

    private static void addHeaders(MockRequest.Builder request, Headers headers) {
        if (headers == null) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            List<String> values = entry.getValue();
            if (values == null) {
                continue;
            }
            for (String value : values) {
                request.header(entry.getKey(), value);
            }
        }
    }

    private static void addValues(MockRequest.Builder request,
                                  Map<String, List<String>> values,
                                  boolean formValues) {
        for (Map.Entry<String, List<String>> entry : values.entrySet()) {
            List<String> repeated = entry.getValue();
            if (repeated == null) {
                continue;
            }
            for (String value : repeated) {
                if (formValues) {
                    request.form(entry.getKey(), value);
                } else {
                    request.query(entry.getKey(), value);
                }
            }
        }
    }

    private static Body readBody(InputStream requestBody) throws IOException {
        if (requestBody == null) {
            return new Body("", false);
        }
        try (InputStream input = requestBody;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int remaining = MAX_BODY_BYTES;
            while (remaining > 0) {
                int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
                if (count < 0) {
                    return new Body(new String(output.toByteArray(), StandardCharsets.UTF_8), false);
                }
                if (count == 0) {
                    continue;
                }
                output.write(buffer, 0, count);
                remaining -= count;
            }
            boolean truncated = input.read() >= 0;
            return new Body(new String(output.toByteArray(), StandardCharsets.UTF_8), truncated);
        }
    }

    private static String firstHeader(Headers headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null
                    && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    private static String mediaType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int parameters = contentType.indexOf(';');
        String type = parameters < 0 ? contentType : contentType.substring(0, parameters);
        return type.trim().toLowerCase(Locale.ROOT);
    }

    private static String clientAddress(InetSocketAddress address) {
        return address == null ? null : address.toString();
    }

    private static final class Body {
        private final String text;
        private final boolean truncated;

        private Body(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }
    }
}
