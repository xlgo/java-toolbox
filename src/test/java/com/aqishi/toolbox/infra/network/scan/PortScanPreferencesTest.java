package com.aqishi.toolbox.infra.network.scan;

import static org.junit.jupiter.api.Assertions.*;

import com.aqishi.toolbox.infra.secrets.InMemoryPreferences;

import org.junit.jupiter.api.Test;

import java.util.Map;

class PortScanPreferencesTest {
    @Test
    void longValidPortExpressionsAndCustomGroupsRoundTripWithoutPreferenceValueLimit() {
        InMemoryPreferences preferences = new InMemoryPreferences();
        PortScanPreferences store = new PortScanPreferences(preferences);
        String ports = "22,80,443,".repeat(2000);
        store.set("customPorts", ports);
        store.saveGroups(Map.of("Custom group", ports));
        PortScanPreferences loaded = new PortScanPreferences(preferences);
        assertEquals(ports, loaded.get("customPorts", ""));
        assertEquals(ports, loaded.groups().get("Custom group"));
        loaded.set("customPorts", "22");
        assertEquals("22", new PortScanPreferences(preferences).get("customPorts", ""));
    }
}
