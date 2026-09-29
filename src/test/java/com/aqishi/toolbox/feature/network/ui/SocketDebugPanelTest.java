package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.network.application.SocketPresetStore;
import com.aqishi.toolbox.feature.network.application.SocketSession;
import com.aqishi.toolbox.feature.network.application.TcpClientSession;
import com.aqishi.toolbox.feature.network.application.TcpServerSession;
import com.aqishi.toolbox.feature.network.domain.PayloadChecksum;
import com.aqishi.toolbox.feature.network.domain.PayloadFormat;
import com.aqishi.toolbox.feature.network.domain.PayloadLineEnding;
import com.aqishi.toolbox.feature.network.domain.PayloadPreset;
import com.aqishi.toolbox.feature.network.domain.SocketEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SocketDebugPanelTest {

    private Preferences node;
    private SocketDebugPanel panel;

    @BeforeEach
    void setUp() {
        node = Preferences.userRoot().node("toolbox-test-" + UUID.randomUUID());
        panel = new SocketDebugPanel(ToolCatalog.SOCKET_DEBUG, node);
    }

    @AfterEach
    void tearDown() throws Exception {
        panel.closeResources();
        node.removeNode();
    }

    private static void onEdt(Runnable body) throws Exception {
        SwingUtilities.invokeAndWait(body);
    }

    /** 在 EDT 上反复求值条件直到成立；不在 EDT 上睡眠。 */
    private static void awaitEdt(String what, BooleanSupplier condition) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        AtomicBoolean ok = new AtomicBoolean();
        while (System.currentTimeMillis() < end) {
            SwingUtilities.invokeAndWait(() -> ok.set(condition.getAsBoolean()));
            if (ok.get()) {
                return;
            }
            Thread.sleep(20);
        }
        fail("Timed out waiting for " + what);
    }

    @Test
    void buildsViewWithCatalogIdentityAndClosesIdempotently() throws Exception {
        assertEquals("network", panel.getGroup());
        assertEquals("socket.debug", panel.getName());
        onEdt(() -> {
            assertNotNull(panel.getView());
            assertEquals(1, panel.tabCountForTest());
            assertEquals(SocketSession.Kind.TCP_CLIENT, panel.viewForTest(0).kind());
        });
        panel.closeResources();
        panel.closeResources();
        // 从未构建界面的面板也能安全释放
        new SocketDebugPanel(ToolCatalog.SOCKET_DEBUG, null).closeResources();
    }

    @Test
    void tcpClientTabConnectsSendsAndShowsEchoedReply() throws Exception {
        List<SocketEvent> serverEvents = new CopyOnWriteArrayList<>();
        TcpServerSession server = new TcpServerSession(new TcpServerSession.Options()
                .bindHost("127.0.0.1").port(0).echo(true), serverEvents::add);
        server.open();
        try {
            AtomicReference<SocketSessionView> ref = new AtomicReference<>();
            onEdt(() -> {
                panel.getView();
                SocketSessionView view = panel.viewForTest(0);
                ref.set(view);
                view.formForTest().hostField.setText("127.0.0.1");
                view.formForTest().portSpinner.setValue(server.actualPort());
                view.toggle();
            });
            SocketSessionView view = ref.get();
            awaitEdt("connected", view::isOpenForTest);

            onEdt(() -> {
                view.sendBoxForTest().inputForTest().setText("hello-panel");
                assertTrue(view.sendBoxForTest().sendNow());
            });
            awaitEdt("echo in log", () -> {
                view.logForTest().flush();
                String text = view.logForTest().textForTest();
                return text.contains("→ ") && text.contains("← ")
                        && text.indexOf("hello-panel") != text.lastIndexOf("hello-panel");
            });

            onEdt(view::toggle);
            awaitEdt("idle", view::isIdleForTest);
            long end = System.currentTimeMillis() + 5000;
            while (serverEvents.stream().noneMatch(e -> e.getType() == SocketEvent.Type.CLIENT_LEFT)
                    && System.currentTimeMillis() < end) {
                Thread.sleep(20);
            }
            assertTrue(serverEvents.stream().anyMatch(e -> e.getType() == SocketEvent.Type.CLIENT_LEFT));
        } finally {
            server.close();
        }
    }

    @Test
    void initialRetryKeepsTabActiveAndConnectsWhenServerStarts() throws Exception {
        int port;
        try (var probe = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            port = probe.getLocalPort();
        }
        AtomicReference<SocketSessionView> ref = new AtomicReference<>();
        onEdt(() -> {
            panel.getView();
            SocketSessionView view = panel.viewForTest(0);
            ref.set(view);
            view.formForTest().hostField.setText("127.0.0.1");
            view.formForTest().portSpinner.setValue(port);
            view.formForTest().reconnectCheck.setSelected(true);
            view.toggle();
        });
        var view = ref.get();
        awaitEdt("initial retry", () -> {
            view.logForTest().flush();
            return view.logForTest().textForTest().contains("127.0.0.1:" + port);
        });
        onEdt(() -> {
            assertFalse(view.isIdleForTest());
            assertFalse(view.isOpenForTest());
        });
        try (TcpServerSession server = new TcpServerSession(new TcpServerSession.Options()
                .bindHost("127.0.0.1").port(port), event -> { })) {
            server.open();
            awaitEdt("connected after retry", view::isOpenForTest);
            onEdt(view::toggle);
            awaitEdt("cancelled", view::isIdleForTest);
        }
    }

    @Test
    void serverTabListsClientsAndReceivesData() throws Exception {
        AtomicReference<SocketSessionView> ref = new AtomicReference<>();
        onEdt(() -> {
            panel.getView();
            SocketSessionView view = panel.addSession(SocketSession.Kind.TCP_SERVER);
            panel.addSession(SocketSession.Kind.UDP);
            assertEquals(3, panel.tabCountForTest());
            panel.closeTab(2);
            assertEquals(2, panel.tabCountForTest());
            ref.set(view);
            view.formForTest().bindCombo.setSelectedItem("127.0.0.1");
            view.formForTest().localPortSpinner.setValue(0);
            view.toggle();
        });
        SocketSessionView view = ref.get();
        awaitEdt("listening", view::isOpenForTest);
        int port = ((TcpServerSession) view.sessionForTest()).actualPort();

        TcpClientSession client = new TcpClientSession(new TcpClientSession.Options()
                .host("127.0.0.1").port(port), event -> { });
        client.open();
        try {
            client.send("from-client".getBytes(StandardCharsets.UTF_8));
            awaitEdt("client row", () -> view.clientTableForTest().rowCountForTest() == 1);
            awaitEdt("received entry", () -> {
                view.logForTest().flush();
                return view.logForTest().textForTest().contains("from-client");
            });
            onEdt(() -> {
                view.clientTableForTest().selectAllForTest();
                assertTrue(view.clientTableForTest().isSendToSelected());
                view.sendBoxForTest().inputForTest().setText("to-selected");
                assertTrue(view.sendBoxForTest().sendNow());
            });
        } finally {
            client.close();
        }
        onEdt(() -> panel.closeTab(1));
        assertTrue(view.sessionForTest() == null);
    }

    @Test
    void logViewThrottlesFloodAndTrimsOldEntries() throws Exception {
        AtomicReference<SocketLogView> ref = new AtomicReference<>();
        onEdt(() -> ref.set(new SocketLogView()));
        SocketLogView log = ref.get();
        try {
            onEdt(() -> log.setPausedForTest(true));
            byte[] data = "x".getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < SocketLogView.MAX_PENDING + 500; i++) {
                log.post(SocketLogEntry.payload(System.currentTimeMillis(), false, "peer", data));
            }
            onEdt(() -> {
                log.flush();
                assertEquals(0, log.shownCountForTest(), "paused view buffers instead of rendering");
                log.setPausedForTest(false);
                log.setMaxEntriesForTest(1000);
                log.flush();
                assertTrue(log.shownCountForTest() <= 1000);
                String text = log.textForTest();
                assertTrue(text.startsWith("[") || text.contains("peer"));
            });
            // 恢复后第一批就带"丢弃 500 条"提示（它不进有界队列，不会被再次丢弃）
            String note = com.aqishi.toolbox.util.I18n.get("tool.socketdebug.log.dropped", "500");
            // 整段在同一个 EDT 任务里完成，刷新定时器无法插进来
            onEdt(() -> {
                log.setMaxEntriesForTest(100_000);
                log.clear();
                log.setPausedForTest(true);
                for (int i = 0; i < SocketLogView.MAX_PENDING + 500; i++) {
                    log.post(SocketLogEntry.payload(System.currentTimeMillis(), false, "q", data));
                }
                log.setPausedForTest(false);
                log.flush();
                assertTrue(log.textForTest().contains(note), "drop note missing");
                assertEquals(SocketLogView.MAX_PER_FLUSH, log.shownCountForTest());
            });
            awaitEdt("backlog drained", () -> {
                log.flush();
                return log.shownCountForTest() == SocketLogView.MAX_PENDING + 1;
            });
        } finally {
            onEdt(log::dispose);
        }
    }

    @Test
    void sendBoxPreviewsChecksumAndRejectsBadHex() throws Exception {
        onEdt(() -> {
            panel.getView();
            SocketSendBox box = panel.viewForTest(0).sendBoxForTest();
            box.setFormatForTest(PayloadFormat.HEX);
            box.setChecksumForTest(PayloadChecksum.CRC16_MODBUS);
            box.inputForTest().setText("31 32 33 34 35 36 37 38 39");
            assertTrue(box.previewForTest().contains("0x4B37"), box.previewForTest());
            assertTrue(box.previewForTest().contains("[37 4B]"), box.previewForTest());
            box.inputForTest().setText("31 3");
            // 位置从 1 开始：第二个片段 "3" 位于第 4 列
            assertEquals(com.aqishi.toolbox.util.I18n.get("tool.socketdebug.parse.oddHex", "4"),
                    box.previewForTest());
            // 未连接时发送失败而不是抛异常
            assertFalse(box.sendNow());
        });
    }

    @Test
    void quickSendPresetsPersistAcrossPanels() throws Exception {
        QuickSendList.Shared shared = new QuickSendList.Shared(new SocketPresetStore(node));
        List<PayloadPreset> sent = new CopyOnWriteArrayList<>();
        AtomicReference<QuickSendList> ref = new AtomicReference<>();
        onEdt(() -> {
            QuickSendList list = new QuickSendList(shared, new QuickSendList.Host() {
                @Override
                public PayloadPreset capture(String name) {
                    return new PayloadPreset(name, PayloadFormat.TEXT, "", PayloadLineEnding.NONE, PayloadChecksum.NONE);
                }

                @Override
                public void sendPreset(PayloadPreset preset) {
                    sent.add(preset);
                }
            });
            ref.set(list);
            list.addPreset(new PayloadPreset("ping", PayloadFormat.TEXT, "PING", PayloadLineEnding.CRLF,
                    PayloadChecksum.NONE));
            list.addPreset(new PayloadPreset("modbus", PayloadFormat.HEX, "01 03 00 00 00 01",
                    PayloadLineEnding.NONE, PayloadChecksum.CRC16_MODBUS));
            list.removePreset(0);
            list.selectForTest(0);
            list.sendSelectedForTest();
        });
        assertEquals(1, sent.size());
        assertEquals("modbus", sent.get(0).getName());
        QuickSendList.Shared reloaded = new QuickSendList.Shared(new SocketPresetStore(node));
        assertEquals(1, reloaded.model.size());
        assertEquals(PayloadChecksum.CRC16_MODBUS, reloaded.model.get(0).checksumValue());
        assertNotNull(ref.get());
    }

    @Test
    void sendHistoryBrowsesLikeAShell() {
        SocketSendHistory history = new SocketSendHistory();
        assertEquals(null, history.previous("draft"));
        history.add("one");
        history.add("two");
        history.add("one");
        assertEquals(List.of("one", "two"), history.items());
        assertEquals("one", history.previous("draft"));
        assertEquals("two", history.previous("ignored"));
        assertEquals("two", history.previous("ignored"));
        assertEquals("one", history.next());
        assertEquals("draft", history.next());
        assertEquals(null, history.next());
        for (int i = 0; i < 80; i++) {
            history.add("m" + i);
        }
        assertEquals(SocketSendHistory.MAX, history.items().size());
        assertEquals("m79", history.items().get(0));
    }
}
