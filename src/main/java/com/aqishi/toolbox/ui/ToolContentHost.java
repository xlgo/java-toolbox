package com.aqishi.toolbox.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 以稳定工具 ID 管理惰性挂载的内容视图。
 *
 * <p>构造时只建立 ID 到 {@link ToolPanel} 的索引；首次显示工具时才创建并挂载
 * Swing 组件。所有公开方法都应在 Swing EDT 上调用，以保证 {@link CardLayout}
 * 和组件树的访问安全。</p>
 *
 * <p>每个工具视图外面套一层按需滚动的容器：内容比视口矮时铺满（工具页不会缩在
 * 左上角），内容更高时整页可滚动。这样高密度工具（端口扫描、K8s 管理）在小窗口下
 * 不会把底部状态条画到卡片外面。</p>
 */
public final class ToolContentHost extends JPanel {

    private final CardLayout cards = new CardLayout();
    private final Map<String, ToolPanel> toolsById = new LinkedHashMap<>();
    private final Set<String> mountedToolIds = new LinkedHashSet<>();

    public ToolContentHost(List<ToolPanel> tools) {
        super();
        setLayout(cards);
        for (ToolPanel tool : tools) {
            toolsById.put(tool.getName(), tool);
        }
    }

    public boolean showTool(String toolId) {
        ToolPanel tool = toolsById.get(toolId);
        if (tool == null) {
            return false;
        }
        if (mountedToolIds.add(toolId)) {
            add(new PageScroll(tool.getView()), toolId);
        }
        cards.show(this, toolId);
        return true;
    }

    public boolean isMounted(String toolId) {
        return mountedToolIds.contains(toolId);
    }

    /** 工具页外层滚动容器：无边框、透明视口、按需纵向滚动 */
    private static final class PageScroll extends JScrollPane {

        PageScroll(Component view) {
            super(new ScrollBody(view));
            setName("workbench.toolScroll");
            setBorder(BorderFactory.createEmptyBorder());
            setOpaque(false);
            getViewport().setOpaque(false);
            setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            getVerticalScrollBar().setUnitIncrement(18);
        }
    }

    /**
     * 宽度跟随视口、高度按内容的滚动主体。
     *
     * <p>{@link #getScrollableTracksViewportHeight()} 在内容比视口矮时返回 true，
     * 让工具页铺满可用高度（表格、终端才有地方伸展）；内容更高时返回 false，
     * 由外层滚动条接管，避免控件被裁掉。</p>
     */
    private static final class ScrollBody extends JPanel implements Scrollable {

        ScrollBody(Component view) {
            super(new BorderLayout());
            setOpaque(false);
            add(view, BorderLayout.CENTER);
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent event) {
                    revalidate();
                }
            });
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visible.height : visible.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            Container parent = getParent();
            if (parent == null) {
                return false;
            }
            // 最小高度装得下就铺满视口（表格、终端需要伸展空间），
            // 装不下才算「内容确实溢出」，交给滚动条，避免控件被裁掉。
            return getMinimumSize().height <= parent.getHeight();
        }
    }
}
