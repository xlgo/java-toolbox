package com.aqishi.toolbox.feature.network.domain;

import java.io.IOException;
import java.util.*;

public final class PortPresets {
    private static final Properties DATA = new Properties();

    static {
        try (var input =
                PortPresets.class.getResourceAsStream(
                        "/com/aqishi/toolbox/portscan-presets.properties")) {
            if (input == null) throw new IOException("Missing port presets");
            DATA.load(input);
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private PortPresets() {}

    public static List<String> groups() {
        return List.of("web", "database", "cache", "messaging", "remote", "mail", "observability");
    }

    public static String ports(String group) {
        return DATA.getProperty("group." + group, "");
    }

    public static String service(int port) {
        return DATA.getProperty("service." + port, "");
    }
}
