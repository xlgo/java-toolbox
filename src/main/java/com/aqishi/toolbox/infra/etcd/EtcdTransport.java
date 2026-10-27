package com.aqishi.toolbox.infra.etcd;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

/** A connection-scoped HTTP route, including cancellation and any SSH forwarding lease. */
public interface EtcdTransport extends AutoCloseable {
    JsonNode request(String method, String path, String contentType, byte[] body, String authorization) throws IOException;
    void cancel();
    @Override void close();
}
