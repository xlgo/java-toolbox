package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.misc.ssh.session.PortUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

public class PortUtilsTest {

    @Test
    public void testAvailablePortDiscovery() throws Exception {
        int availablePort = PortUtils.findAvailablePort(0);
        Assertions.assertTrue(availablePort > 0 && availablePort <= 65535);

        // 占用该端口
        try (ServerSocket socket = new ServerSocket(availablePort)) {
            // 此时该端口已占用，findAvailablePort 应自动避开并返回另一个可用端口
            int fallbackPort = PortUtils.findAvailablePort(availablePort);
            Assertions.assertNotEquals(availablePort, fallbackPort);
            Assertions.assertTrue(fallbackPort > 0 && fallbackPort <= 65535);
        }
    }
}
