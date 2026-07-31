package com.aqishi.toolbox.misc.ssh.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.UUID;

/**
 * SSH 本地端口转发 / 隧道配置模型
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SshTunnelConfig implements Cloneable {

    public enum Status {
        STOPPED("已停止"),
        RUNNING("运行中"),
        PAUSED("已暂停(SSH断开)"),
        ERROR("启动异常");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum BrowserScheme {
        AUTO("自动"),
        HTTP("HTTP"),
        HTTPS("HTTPS");

        private final String label;

        BrowserScheme(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private String id;
    private String name;               // 服务名称（如 "内网 MySQL"）
    private String remoteHost = "127.0.0.1"; // 远程主机地址
    private int remotePort = 3306;     // 远程端口
    private boolean autoStart = false; // 是否随 SSH 会话建立自动连接
    private BrowserScheme browserScheme = BrowserScheme.AUTO;
    private String browserPath = "/";

    // 运行时属性
    private transient int assignedLocalPort = 0; // 系统自动分配并绑定的本地端口
    private transient int preferredLocalPort = 0;
    private transient Status status = Status.STOPPED;
    private transient String errorMessage;

    public SshTunnelConfig() {
        this.id = UUID.randomUUID().toString();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRemoteHost() {
        return (remoteHost == null || remoteHost.trim().isEmpty()) ? "127.0.0.1" : remoteHost.trim();
    }

    public void setRemoteHost(String remoteHost) {
        this.remoteHost = remoteHost;
    }

    public int getRemotePort() {
        return remotePort <= 0 ? 80 : remotePort;
    }

    public void setRemotePort(int remotePort) {
        this.remotePort = remotePort;
    }

    public boolean isAutoStart() {
        return autoStart;
    }

    public void setAutoStart(boolean autoStart) {
        this.autoStart = autoStart;
    }

    public BrowserScheme getBrowserScheme() {
        return browserScheme == null ? BrowserScheme.AUTO : browserScheme;
    }

    public void setBrowserScheme(BrowserScheme browserScheme) {
        this.browserScheme = browserScheme;
    }

    public String getBrowserPath() {
        return browserPath == null || browserPath.trim().isEmpty() ? "/" : browserPath.trim();
    }

    public void setBrowserPath(String browserPath) {
        this.browserPath = browserPath;
    }

    @JsonIgnore
    public int getAssignedLocalPort() {
        return assignedLocalPort;
    }

    @JsonIgnore
    public void setAssignedLocalPort(int assignedLocalPort) {
        this.assignedLocalPort = assignedLocalPort;
    }

    @JsonIgnore
    public int getPreferredLocalPort() {
        return preferredLocalPort;
    }

    @JsonIgnore
    public void setPreferredLocalPort(int preferredLocalPort) {
        this.preferredLocalPort = preferredLocalPort;
    }

    @JsonIgnore
    public Status getStatus() {
        return status == null ? Status.STOPPED : status;
    }

    @JsonIgnore
    public void setStatus(Status status) {
        this.status = status;
    }

    @JsonIgnore
    public String getErrorMessage() {
        return errorMessage;
    }

    @JsonIgnore
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    @JsonIgnore
    public String getLocalConnectionString() {
        if (status == Status.RUNNING && assignedLocalPort > 0) {
            return "127.0.0.1:" + assignedLocalPort;
        }
        return "-";
    }

    @JsonIgnore
    public String getBrowserUrl() {
        if (assignedLocalPort <= 0) return "";
        String scheme;
        switch (getBrowserScheme()) {
            case HTTP:
                scheme = "http";
                break;
            case HTTPS:
                scheme = "https";
                break;
            case AUTO:
            default:
                scheme = getRemotePort() == 443 ? "https" : "http";
                break;
        }
        String path = getBrowserPath();
        if (!path.startsWith("/")) path = "/" + path;
        return scheme + "://127.0.0.1:" + assignedLocalPort + path;
    }

    @Override
    public SshTunnelConfig clone() {
        try {
            return (SshTunnelConfig) super.clone();
        } catch (CloneNotSupportedException e) {
            SshTunnelConfig copy = new SshTunnelConfig();
            copy.id = this.id;
            copy.name = this.name;
            copy.remoteHost = this.remoteHost;
            copy.remotePort = this.remotePort;
            copy.autoStart = this.autoStart;
            copy.browserScheme = this.browserScheme;
            copy.browserPath = this.browserPath;
            return copy;
        }
    }
}
