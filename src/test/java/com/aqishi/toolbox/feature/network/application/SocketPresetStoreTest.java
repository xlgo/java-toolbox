package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.PayloadChecksum;
import com.aqishi.toolbox.feature.network.domain.PayloadFormat;
import com.aqishi.toolbox.feature.network.domain.PayloadLineEnding;
import com.aqishi.toolbox.feature.network.domain.PayloadPreset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketPresetStoreTest {

    private Preferences node;

    @BeforeEach
    void setUp() {
        node = Preferences.userRoot().node("toolbox-test-" + UUID.randomUUID());
    }

    @AfterEach
    void tearDown() throws Exception {
        node.removeNode();
    }

    @Test
    void roundTripsPresetsInOrderAllowingDuplicateNames() {
        SocketPresetStore store = new SocketPresetStore(node);
        assertTrue(store.load().isEmpty());

        PayloadPreset modbus = new PayloadPreset("读寄存器", PayloadFormat.HEX, "01 03 00 00 00 0A",
                PayloadLineEnding.NONE, PayloadChecksum.CRC16_MODBUS);
        PayloadPreset at = new PayloadPreset("AT", PayloadFormat.TEXT, "AT+GMR",
                PayloadLineEnding.CRLF, PayloadChecksum.NONE);
        at.setCharset("GBK");
        at.setEscapes(true);
        PayloadPreset again = new PayloadPreset("AT", PayloadFormat.TEXT, "AT", PayloadLineEnding.CR,
                PayloadChecksum.NONE);
        store.save(Arrays.asList(modbus, at, again));

        List<PayloadPreset> loaded = new SocketPresetStore(node).load();
        assertEquals(3, loaded.size());
        assertEquals("读寄存器", loaded.get(0).getName());
        assertEquals(PayloadChecksum.CRC16_MODBUS, loaded.get(0).checksumValue());
        assertEquals(PayloadFormat.HEX, loaded.get(0).formatValue());
        assertEquals("GBK", loaded.get(1).getCharset());
        assertTrue(loaded.get(1).isEscapes());
        assertEquals(PayloadLineEnding.CRLF, loaded.get(1).lineEndingValue());
        assertEquals(PayloadLineEnding.CR, loaded.get(2).lineEndingValue());
        assertTrue(node.get(SocketPresetStore.KEY, "").contains("AT+GMR"));
    }
}
