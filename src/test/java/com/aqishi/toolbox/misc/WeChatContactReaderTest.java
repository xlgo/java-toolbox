package com.aqishi.toolbox.misc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class WeChatContactReaderTest {

    private File tempDbFile;

    @BeforeEach
    public void setUp() throws Exception {
        // Create a temporary database file
        tempDbFile = File.createTempFile("mock_wechat_", ".db");
        tempDbFile.deleteOnExit();

        // Populate with mock tables and records
        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDbFile.getAbsolutePath());
             Statement stmt = conn.createStatement()) {

            // Create tables matching WeChat schema
            stmt.execute("CREATE TABLE Contact (" +
                    "UserName TEXT PRIMARY KEY, " +
                    "Alias TEXT, " +
                    "NickName TEXT, " +
                    "Remark TEXT, " +
                    "m_ui_Sex INTEGER, " +
                    "Type INTEGER" +
                    ")");

            stmt.execute("CREATE TABLE ContactHeadImgUrl (" +
                    "usrName TEXT PRIMARY KEY, " +
                    "smallHeadImgUrl TEXT, " +
                    "bigHeadImgUrl TEXT" +
                    ")");

            // Insert a friend (Alice)
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO Contact (UserName, Alias, NickName, Remark, m_ui_Sex, Type) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, "wxid_alice123");
                ps.setString(2, "alice_wechat");
                ps.setString(3, "Alice Nickname");
                ps.setString(4, "爱丽丝");
                ps.setInt(5, 2); // Female
                ps.setInt(6, 3); // Friend
                ps.execute();

                // Insert Bob (Male Friend)
                ps.setString(1, "wxid_bob456");
                ps.setString(2, "bob_wechat");
                ps.setString(3, "Bob Nickname");
                ps.setString(4, "");
                ps.setInt(5, 1); // Male
                ps.setInt(6, 3); // Friend
                ps.execute();

                // Insert Tencent News (Official Account)
                ps.setString(1, "gh_tencent");
                ps.setString(2, "tencent_news");
                ps.setString(3, "腾讯新闻");
                ps.setString(4, "");
                ps.setInt(5, 0); // Unknown
                ps.setInt(6, 8); // Official
                ps.execute();

                // Insert Group Chat
                ps.setString(1, "123456@chatroom");
                ps.setString(2, "");
                ps.setString(3, "Java Group");
                ps.setString(4, "");
                ps.setInt(5, 0); // Unknown
                ps.setInt(6, 2); // Chatroom
                ps.execute();
            }

            // Insert avatar urls
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ContactHeadImgUrl (usrName, smallHeadImgUrl, bigHeadImgUrl) VALUES (?, ?, ?)")) {
                ps.setString(1, "wxid_alice123");
                ps.setString(2, "https://thirdwx.qlogo.cn/alice_small");
                ps.setString(3, "https://thirdwx.qlogo.cn/alice_big");
                ps.execute();

                ps.setString(1, "wxid_bob456");
                ps.setString(2, "https://thirdwx.qlogo.cn/bob_small");
                ps.setString(3, "https://thirdwx.qlogo.cn/bob_big");
                ps.execute();
            }
        }
    }

    @AfterEach
    public void tearDown() {
        if (tempDbFile != null && tempDbFile.exists()) {
            tempDbFile.delete();
        }
    }

    @Test
    public void testReadContacts() throws Exception {
        List<WeChatContactReader.ContactInfo> contacts = WeChatContactReader.readContacts(tempDbFile);
        assertNotNull(contacts);
        assertEquals(4, contacts.size());

        // Find Alice
        WeChatContactReader.ContactInfo alice = contacts.stream()
                .filter(c -> "wxid_alice123".equals(c.username))
                .findFirst()
                .orElse(null);
        assertNotNull(alice);
        assertEquals("alice_wechat", alice.alias);
        assertEquals("Alice Nickname", alice.nickname);
        assertEquals("爱丽丝", alice.remark);
        assertEquals("爱丽丝", alice.getDisplayName());
        assertEquals("alice_wechat", alice.getWeChatId());
        assertEquals(2, alice.gender); // Female
        assertEquals("https://thirdwx.qlogo.cn/alice_small", alice.avatarUrl);

        // Find Bob
        WeChatContactReader.ContactInfo bob = contacts.stream()
                .filter(c -> "wxid_bob456".equals(c.username))
                .findFirst()
                .orElse(null);
        assertNotNull(bob);
        assertEquals("Bob Nickname", bob.getDisplayName()); // Remark is empty, fallback to NickName
        assertEquals(1, bob.gender); // Male
        assertEquals("https://thirdwx.qlogo.cn/bob_small", bob.avatarUrl);
    }

    @Test
    public void testExportContactsToExcel() throws Exception {
        List<WeChatContactReader.ContactInfo> contacts = WeChatContactReader.readContacts(tempDbFile);
        File excelFile = File.createTempFile("wechat_export_", ".xlsx");
        excelFile.deleteOnExit();

        WeChatContactReader.exportContactsToExcel(contacts, excelFile);
        assertTrue(excelFile.exists());
        assertTrue(excelFile.length() > 0);
        excelFile.delete();
    }
}
