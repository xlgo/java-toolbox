package com.aqishi.toolbox.feature.system.application;

import org.junit.jupiter.api.Test;
import java.io.BufferedReader;
import java.io.StringReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class WeChatContactCollectorTest {
    private static final String CONTACT = "{\"type\":\"contact\",\"username\":\"wxid_1\",\"wechat_id\":\"alias\",\"nickname\":\"Same Name\",\"remark\":\"First Last\",\"tag\":\"Work\",\"gender\":2,\"avatar_url\":\"https://avatar.invalid/a\"}\n";

    @Test void installsOnlyCompletedSnapshotAndPreservesFields() throws Exception {
        var rows = WeChatContactCollector.readProtocol(new BufferedReader(new StringReader(CONTACT + "{\"type\":\"complete\",\"count\":1}\n")), ignored -> {});
        assertEquals(1, rows.size());
        var row = rows.get(0);
        assertEquals("wxid_1", row.username);
        assertEquals("alias", row.alias);
        assertEquals("First Last", row.remark);
        assertEquals("Work", row.tag);
        assertEquals(2, row.gender);
        assertEquals("https://avatar.invalid/a", row.avatarUrl);
    }
    @Test void identicalDisplayNamesDoNotMerge() throws Exception {
        String second = CONTACT.replace("wxid_1", "wxid_2");
        var rows = WeChatContactCollector.readProtocol(new BufferedReader(new StringReader(CONTACT + second + "{\"type\":\"complete\",\"count\":2}")), ignored -> {});
        assertEquals(2, rows.size());
    }
    @Test void prematureExitDuplicateIdAndCountMismatchAreFailures() {
        for (String protocol : new String[]{CONTACT, CONTACT + CONTACT + "{\"type\":\"complete\",\"count\":2}", CONTACT + "{\"type\":\"complete\",\"count\":0}", "bad JSON"})
            assertThrows(IOException.class, () -> WeChatContactCollector.readProtocol(new BufferedReader(new StringReader(protocol)), ignored -> {}));
    }
    @Test void cancellationAndErrorsAreNotContacts() {
        assertThrows(CancellationException.class, () -> WeChatContactCollector.readProtocol(new BufferedReader(new StringReader("{\"type\":\"cancelled\"}")), ignored -> {}));
        var error = assertThrows(IOException.class, () -> WeChatContactCollector.readProtocol(new BufferedReader(new StringReader("{\"type\":\"error\",\"error\":\"Not signed in\"}")), ignored -> {}));
        assertEquals("Not signed in", error.getMessage());
    }
    @Test void statusAndEmptyCompletedListAreHandled() throws Exception {
        var statuses = new ArrayList<String>();
        var rows = WeChatContactCollector.readProtocol(new BufferedReader(new StringReader("{\"type\":\"status\",\"status\":\"Reading\"}\n{\"type\":\"complete\",\"count\":0}")), statuses::add);
        assertTrue(rows.isEmpty());
        assertEquals(java.util.List.of("Reading"), statuses);
    }
}
