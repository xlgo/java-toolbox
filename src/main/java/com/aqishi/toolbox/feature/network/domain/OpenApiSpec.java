package com.aqishi.toolbox.feature.network.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAPI / Swagger 接口规范解析模型。
 */
public class OpenApiSpec {

    private String title = "Untitled API";
    private String version = "1.0.0";
    private String description = "";
    private List<String> servers = new ArrayList<>();
    private List<ApiEndpoint> endpoints = new ArrayList<>();

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getServers() {
        return servers;
    }

    public void setServers(List<String> servers) {
        this.servers = servers != null ? servers : new ArrayList<>();
    }

    public List<ApiEndpoint> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(List<ApiEndpoint> endpoints) {
        this.endpoints = endpoints != null ? endpoints : new ArrayList<>();
    }

    /**
     * 单个 API 端点模型。
     */
    public static class ApiEndpoint {
        private String method = "GET";
        private String path = "/";
        private String summary = "";
        private String description = "";
        private String tag = "Default";
        private List<Parameter> parameters = new ArrayList<>();
        private String requestContentType = "application/json";
        private String requestBodyExample = "";
        private List<ResponseItem> responses = new ArrayList<>();

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getSummary() {
            return summary;
        }

        public void setSummary(String summary) {
            this.summary = summary;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getTag() {
            return tag;
        }

        public void setTag(String tag) {
            this.tag = tag;
        }

        public List<Parameter> getParameters() {
            return parameters;
        }

        public void setParameters(List<Parameter> parameters) {
            this.parameters = parameters != null ? parameters : new ArrayList<>();
        }

        public String getRequestContentType() {
            return requestContentType;
        }

        public void setRequestContentType(String requestContentType) {
            this.requestContentType = requestContentType;
        }

        public String getRequestBodyExample() {
            return requestBodyExample;
        }

        public void setRequestBodyExample(String requestBodyExample) {
            this.requestBodyExample = requestBodyExample;
        }

        public List<ResponseItem> getResponses() {
            return responses;
        }

        public void setResponses(List<ResponseItem> responses) {
            this.responses = responses != null ? responses : new ArrayList<>();
        }

        @Override
        public String toString() {
            return method + " " + path + (summary != null && !summary.isEmpty() ? " (" + summary + ")" : "");
        }
    }

    /**
     * 参数定义模型（Query / Path / Header）。
     */
    public static class Parameter {
        private String name;
        private String in; // "query", "path", "header"
        private boolean required;
        private String type = "string";
        private String description = "";
        private String example = "";

        public Parameter() {}

        public Parameter(String name, String in, boolean required, String type, String description, String example) {
            this.name = name;
            this.in = in;
            this.required = required;
            this.type = type;
            this.description = description;
            this.example = example;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getIn() {
            return in;
        }

        public void setIn(String in) {
            this.in = in;
        }

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getExample() {
            return example;
        }

        public void setExample(String example) {
            this.example = example;
        }
    }

    /**
     * 响应项模型。
     */
    public static class ResponseItem {
        private String statusCode;
        private String description;

        public ResponseItem() {}

        public ResponseItem(String statusCode, String description) {
            this.statusCode = statusCode;
            this.description = description;
        }

        public String getStatusCode() {
            return statusCode;
        }

        public void setStatusCode(String statusCode) {
            this.statusCode = statusCode;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}
