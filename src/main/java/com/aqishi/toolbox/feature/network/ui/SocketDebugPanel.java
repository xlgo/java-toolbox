package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.catalog.ToolDescriptor;
import com.aqishi.toolbox.feature.network.application.SocketPresetStore;
import com.aqishi.toolbox.feature.network.application.SocketSession;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.prefs.Preferences;

/**
 * TCP / UDP 网络调试助手：多个相互独立的会话标签（TCP 客户端、TCP 服务端、UDP），
 * 十六进制/文本收发、校验值、分帧、定时发送、文件发送与快捷发送。
 */
public class SocketDebugPanel extends ToolPanel implements ManagedResourceOwner {

    private final Preferences preferences;
    private final List<SocketSessionView> views = new ArrayList<>();
    private final Map<SocketSession.Kind, Integer> counters = new EnumMap<>(SocketSession.Kind.class);
    private QuickSendList.Shared presets;
    private JTabbedPane tabs;

    public SocketDebugPanel() {
        this(ToolCatalog.SOCKET_DEBUG, Preferences.userNodeForPackage(SocketDebugPanel.class));
    }

    /**
     * @param preferences 快捷发送预设的存放节点；传 null 则不持久化（测试用）
     */
    public SocketDebugPanel(ToolDescriptor descriptor, Preferences preferences) {
        super(Objects.requireNonNull(descriptor, "descriptor"));
        this.preferences = preferences;
    }

    @Override
    protected JComponent build() {
        presets = new QuickSendList.Shared(preferences == null ? null : new SocketPresetStore(preferences));
        tabs = new JTabbedPane(JTabbedPane.TOP);
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.putClientProperty("JTabbedPane.tabClosable", Boolean.TRUE);
        tabs.putClientProperty("JTabbedPane.tabCloseToolTipText", I18n.get("tool.socketdebug.tab.close"));
        tabs.putClientProperty("JTabbedPane.tabCloseCallback", (IntConsumer) this::closeTab);

        JButton addBtn = Buttons.snug("+");
        addBtn.setToolTipText(I18n.get("tool.socketdebug.tab.new"));
        addBtn.addActionListener(e -> showNewMenu(addBtn));
        tabs.putClientProperty("JTabbedPane.trailingComponent", addBtn);
        tabs.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int index = tabs.indexAtLocation(event.getX(), event.getY());
                if (index < 0) {
                    return;
                }
                if (SwingUtilities.isRightMouseButton(event)) {
                    showTabMenu(index, event);
                } else if (event.getClickCount() == 2) {
                    renameTab(index);
                }
            }
        });

        addSession(SocketSession.Kind.TCP_CLIENT);
        JPanel root = Layouts.page();
        root.add(tabs, BorderLayout.CENTER);
        return root;
    }

    private void showNewMenu(Component anchor) {
        JPopupMenu menu = new JPopupMenu();
        for (SocketSession.Kind kind : SocketSession.Kind.values()) {
            JMenuItem item = new JMenuItem(kindLabel(kind));
            item.addActionListener(e -> addSession(kind));
            menu.add(item);
        }
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void showTabMenu(int index, MouseEvent event) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem rename = new JMenuItem(I18n.get("tool.socketdebug.tab.rename"));
        rename.addActionListener(e -> renameTab(index));
        JMenuItem close = new JMenuItem(I18n.get("tool.socketdebug.tab.close"));
        close.addActionListener(e -> closeTab(index));
        menu.add(rename);
        menu.add(close);
        menu.show(tabs, event.getX(), event.getY());
    }

    private static String kindLabel(SocketSession.Kind kind) {
        switch (kind) {
            case TCP_SERVER: return I18n.get("tool.socketdebug.kind.tcpServer");
            case UDP: return I18n.get("tool.socketdebug.kind.udp");
            default: return I18n.get("tool.socketdebug.kind.tcpClient");
        }
    }

    /** 新建一个会话标签并选中。 */
    SocketSessionView addSession(SocketSession.Kind kind) {
        int number = counters.merge(kind, 1, Integer::sum);
        SocketSessionView view = new SocketSessionView(kind, presets);
        views.add(view);
        tabs.addTab(kindLabel(kind) + " " + number, view);
        tabs.setSelectedComponent(view);
        return view;
    }

    private void renameTab(int index) {
        if (index < 0 || index >= tabs.getTabCount()) {
            return;
        }
        String name = UIUtils.input(tabs, I18n.get("tool.socketdebug.tab.renamePrompt"), tabs.getTitleAt(index));
        if (name != null && !name.trim().isEmpty()) {
            tabs.setTitleAt(index, name.trim());
        }
    }

    /** 关闭标签即停止该会话；最后一个标签关闭后自动补一个 TCP 客户端，界面不会空着。 */
    void closeTab(int index) {
        if (index < 0 || index >= tabs.getTabCount()) {
            return;
        }
        Component component = tabs.getComponentAt(index);
        tabs.removeTabAt(index);
        if (component instanceof SocketSessionView) {
            SocketSessionView view = (SocketSessionView) component;
            views.remove(view);
            view.dispose();
        }
        if (tabs.getTabCount() == 0) {
            addSession(SocketSession.Kind.TCP_CLIENT);
        }
    }

    /** 停止所有会话、定时器与后台线程；可重复调用，也可在界面从未构建时调用。 */
    @Override
    public void closeResources() {
        for (SocketSessionView view : new ArrayList<>(views)) {
            view.dispose();
        }
    }

    // ---- 测试辅助 ----

    int tabCountForTest() {
        return tabs.getTabCount();
    }

    String tabTitleForTest(int index) {
        return tabs.getTitleAt(index);
    }

    SocketSessionView viewForTest(int index) {
        return (SocketSessionView) tabs.getComponentAt(index);
    }
}
