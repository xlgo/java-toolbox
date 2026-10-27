package com.aqishi.toolbox.feature.network.domain;

public record ProbeOutcome(String source, String ip, int port, State state, long elapsedMicros, String detail, long timestamp) {
    public enum State { OPEN, CONNECT_FAILED, TIMEOUT, UNREACHABLE, DNS_FAILED, ERROR, CANCELLED }
    public String endpoint() { return (ip.contains(":") ? "[" + ip + "]" : ip) + ":" + port; }
}
