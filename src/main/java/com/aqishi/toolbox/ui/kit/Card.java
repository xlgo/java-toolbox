package com.aqishi.toolbox.ui.kit;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 面板容器：圆角描边 + 可选标题 + 内容区 + 可选折叠支持。
 *
 * <p>对应效果页的 {@code .panel}：白底、1px 描边、12px 圆角、20px 内边距，
 * 标题是 13px 加粗的次要色文字。用来替代到处出现的 {@code TitledBorder}，
 * 背景与描边在绘制时从 {@link Tokens} 读取，切换主题即跟随。</p>
 *
 * <p>支持原生折叠功能：配置卡片可一键折叠收缩为单行标题栏，极大地释放主要操作区域的可视空间，
 * 折叠后标题右侧的动作栏（连接/开始/停止等）依然可见可用。</p>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * Card card = Card.titled("随机数据配置").setCollapsible(true);
 * card.setContent(form);
 * card.addHeaderAction(Buttons.primary("生成数据"));
 * }</pre>
 */
public class Card extends JPanel {

    private JPanel header;
    private JLabel titleLabel;
    private JLabel subtitleLabel;
    private JPanel titleBox;
    private JPanel actionBox;
    private JPanel footer;
    private final JPanel content = new JPanel(new BorderLayout());
    private boolean flush;

    // 折叠支持
    private boolean collapsible = false;
    private boolean collapsed = false;
    private JLabel collapseIndicator;
    private JPanel headerWrapper;
    private List<Consumer<Boolean>> collapseListeners;

    /** 无标题卡片 */
    public static Card plain() {
        return new Card(null, null);
    }

    /** 带标题的卡片 */
    public static Card titled(String title) {
        return new Card(title, null);
    }

    /** 带标题与副标题（说明文字）的卡片 */
    public static Card titled(String title, String subtitle) {
        return new Card(title, subtitle);
    }

    /** 创建支持折叠的带标题卡片 */
    public static Card collapsible(String title) {
        return titled(title).setCollapsible(true);
    }

    /** 创建支持折叠的带标题与副标题卡片 */
    public static Card collapsible(String title, String subtitle) {
        return titled(title, subtitle).setCollapsible(true);
    }

    /** 带标题的卡片，内容直接铺满（内容自带边距时使用，例如表格、编辑器） */
    public static Card flush(String title) {
        return flush(title, null);
    }

    /** 带标题与副标题的铺满型卡片 */
    public static Card flush(String title, String subtitle) {
        Card card = new Card(title, subtitle);
        card.setFlush(true);
        return card;
    }

    public Card(String title, String subtitle) {
        super(new BorderLayout());
        setOpaque(false);
        content.setOpaque(false);
        applyContentPadding();
        add(content, BorderLayout.CENTER);
        if (title != null) {
            buildHeader(title, subtitle);
            applyContentPadding();
        }
    }

    private void buildHeader(String title, String subtitle) {
        header = new JPanel(new HeaderLayout());
        header.setOpaque(false);

        titleLabel = new JLabel(title);
        titleLabel.setFont(Tokens.fontSectionTitle());
        titleLabel.setForeground(Tokens.secondaryForeground());

        collapseIndicator = new JLabel(new ChevronIcon(true, 14));
        collapseIndicator.setVisible(false);
        collapseIndicator.setBorder(new EmptyBorder(0, 0, 0, Tokens.SPACE_XS));

        titleBox = new JPanel();
        titleBox.setOpaque(false);
        titleBox.setLayout(new javax.swing.BoxLayout(titleBox, javax.swing.BoxLayout.Y_AXIS));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleBox.add(titleLabel);

        if (subtitle != null && !subtitle.isEmpty()) {
            subtitleLabel = new JLabel(subtitle);
            subtitleLabel.setFont(Tokens.fontCaption());
            subtitleLabel.setForeground(Tokens.mutedForeground());
            subtitleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            titleBox.add(javax.swing.Box.createVerticalStrut(2));
            titleBox.add(subtitleLabel);
        }

        // 动作行可换行：按钮一多、窗口一窄就折到下一行，而不是被裁掉
        actionBox = new JPanel(new WrapRowLayout(
                Tokens.SPACE_SM, Tokens.SPACE_SM, WrapRowLayout.Align.RIGHT));
        actionBox.setOpaque(false);
        actionBox.setVisible(false);

        header.add(collapseIndicator);
        header.add(titleBox);
        header.add(actionBox);

        headerWrapper = new JPanel(new BorderLayout());
        headerWrapper.setOpaque(false);
        headerWrapper.setBorder(new EmptyBorder(
                17, Tokens.CARD_PADDING, 0, Tokens.CARD_PADDING));
        headerWrapper.add(header, BorderLayout.CENTER);
        header.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent event) {
                // 动作行是否折行取决于宽度：宽度变化后头部高度需要重算
                headerWrapper.revalidate();
            }
        });

        // 效果页的面板标题与正文同底色、中间不画分隔线，只靠留白分层
        JPanel headerBlock = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics graphics) {
                graphics.setColor(Tokens.cardHeaderBackground());
                graphics.fillRect(0, 0, getWidth(), getHeight());
                super.paintComponent(graphics);
            }
        };
        headerBlock.setOpaque(false);
        headerBlock.add(headerWrapper, BorderLayout.CENTER);
        add(headerBlock, BorderLayout.NORTH);

        java.awt.event.MouseAdapter clickAdapter = new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (!collapsible) return;
                if (actionBox != null && SwingUtilities.isDescendingFrom(e.getComponent(), actionBox)) {
                    return;
                }
                toggleCollapsed();
            }
        };
        headerWrapper.addMouseListener(clickAdapter);
        header.addMouseListener(clickAdapter);
        titleBox.addMouseListener(clickAdapter);
        titleLabel.addMouseListener(clickAdapter);
        collapseIndicator.addMouseListener(clickAdapter);
    }

    private void applyContentPadding() {
        if (flush) {
            content.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
        } else if (header != null) {
            // 标题自带 16px 上边距与 2px 下边距，正文只需再补 10px 就与效果页的标题间距一致
            content.setBorder(new EmptyBorder(
                    10, Tokens.CARD_PADDING, Tokens.CARD_PADDING, Tokens.CARD_PADDING));
        } else {
            content.setBorder(new EmptyBorder(
                    Tokens.CARD_PADDING, Tokens.CARD_PADDING, Tokens.CARD_PADDING, Tokens.CARD_PADDING));
        }
    }

    /** 内容是否铺满卡片（不加内边距） */
    public Card setFlush(boolean value) {
        this.flush = value;
        applyContentPadding();
        return this;
    }

    /** 标题文字色：跟随主题的次要文字色 */
    public void restyle() {
        if (titleLabel != null) {
            titleLabel.setFont(Tokens.fontSectionTitle());
            titleLabel.setForeground(Tokens.secondaryForeground());
        }
        if (subtitleLabel != null) {
            subtitleLabel.setFont(Tokens.fontCaption());
            subtitleLabel.setForeground(Tokens.mutedForeground());
        }
        updateCollapseIndicator();
        applyContentPadding();
        repaint();
    }

    /** 内容容器，可直接按 BorderLayout 使用 */
    public JPanel getContent() {
        return content;
    }

    /** 设置卡片主体内容（占据 CENTER） */
    public Card setContent(Component component) {
        content.removeAll();
        content.add(component, BorderLayout.CENTER);
        return this;
    }

    /**
     * 在标题右侧添加操作控件。
     *
     * <p>按调用顺序从左到右排列，所以**主操作最后添加**才会落在最右侧。</p>
     */
    public Card addHeaderAction(Component component) {
        if (actionBox == null) {
            throw new IllegalStateException("card has no header");
        }
        if (actionBox.getComponentCount() > 0) {
            actionBox.add(javax.swing.Box.createHorizontalStrut(Tokens.SPACE_SM));
        }
        // 只钉住按钮这类固定宽度控件；标签文本会变（例如连接状态），钉死会让它永远保持添加时的宽度
        if (component instanceof javax.swing.AbstractButton) {
            JComponent target = (JComponent) component;
            Dimension preferred = target.getPreferredSize();
            target.setMaximumSize(new Dimension(preferred.width, Integer.MAX_VALUE));
        }
        actionBox.add(component);
        actionBox.setVisible(true);
        return this;
    }

    /**
     * 卡片底部状态条：与内容之间用细线分隔。
     *
     * <p>用于放统计信息、连接状态、进度这类附属信息。</p>
     */
    public Card setFooter(Component component) {
        if (footer != null) {
            remove(footer);
        }
        footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        JPanel inner = new JPanel(new BorderLayout());
        inner.setOpaque(false);
        inner.setBorder(new EmptyBorder(
                Tokens.SPACE_SM, Tokens.CARD_PADDING, Tokens.SPACE_SM, Tokens.CARD_PADDING));
        inner.add(component, BorderLayout.CENTER);
        footer.add(new Hairline(), BorderLayout.NORTH);
        footer.add(inner, BorderLayout.CENTER);
        if (collapsed) {
            footer.setVisible(false);
        }
        add(footer, BorderLayout.SOUTH);
        return this;
    }

    /** 更新标题文本（语言切换时使用） */
    public void setTitle(String title) {
        if (titleLabel != null) {
            titleLabel.setText(title);
        }
    }

    /** 更新副标题文本 */
    public void setSubtitle(String subtitle) {
        if (subtitleLabel != null) {
            subtitleLabel.setText(subtitle);
        }
    }

    /** 开启或关闭卡片折叠能力 */
    public Card setCollapsible(boolean collapsible) {
        return setCollapsible(collapsible, false);
    }

    /** 开启卡片折叠能力，并指定是否默认折叠 */
    public Card setCollapsible(boolean collapsible, boolean defaultCollapsed) {
        if (header == null && collapsible) {
            throw new IllegalStateException("card has no header to collapse");
        }
        this.collapsible = collapsible;
        if (collapseIndicator != null) {
            collapseIndicator.setVisible(collapsible);
        }
        updateCursorAndTooltip();
        if (collapsible && defaultCollapsed) {
            setCollapsed(true);
        }
        return this;
    }

    public boolean isCollapsible() {
        return collapsible;
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    public Card toggleCollapsed() {
        return setCollapsed(!collapsed);
    }

    public Card setCollapsed(boolean value) {
        if (!collapsible) {
            return this;
        }
        if (this.collapsed == value) {
            return this;
        }
        this.collapsed = value;
        content.setVisible(!value);
        if (footer != null) {
            footer.setVisible(!value);
        }
        updateCollapseIndicator();
        updateCursorAndTooltip();
        revalidate();
        repaint();

        Container parent = getParent();
        if (parent != null) {
            parent.revalidate();
            parent.repaint();
        }

        if (collapseListeners != null) {
            for (Consumer<Boolean> listener : collapseListeners) {
                try {
                    listener.accept(value);
                } catch (Throwable ignored) {
                }
            }
        }
        return this;
    }

    public Card addCollapseListener(Consumer<Boolean> listener) {
        if (collapseListeners == null) {
            collapseListeners = new ArrayList<>();
        }
        collapseListeners.add(listener);
        return this;
    }

    private void updateCollapseIndicator() {
        if (collapseIndicator != null) {
            collapseIndicator.setIcon(new ChevronIcon(!collapsed, 14));
        }
    }

    private void updateCursorAndTooltip() {
        if (headerWrapper != null) {
            Cursor cursor = collapsible ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor();
            headerWrapper.setCursor(cursor);
            if (titleBox != null) titleBox.setCursor(cursor);
            if (titleLabel != null) titleLabel.setCursor(cursor);
            if (collapseIndicator != null) collapseIndicator.setCursor(cursor);

            String tip = collapsible
                    ? com.aqishi.toolbox.util.I18n.get(collapsed
                            ? "card.expand"
                            : "card.collapse")
                    : null;
            if (collapseIndicator != null) collapseIndicator.setToolTipText(tip);
            if (titleLabel != null) titleLabel.setToolTipText(tip);
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // 主题切换后重新取色取字，避免沿用旧主题的前景色
        if (titleLabel != null) {
            titleLabel.setFont(Tokens.fontSectionTitle());
            titleLabel.setForeground(Tokens.secondaryForeground());
        }
        if (subtitleLabel != null) {
            subtitleLabel.setFont(Tokens.fontCaption());
            subtitleLabel.setForeground(Tokens.mutedForeground());
        }
        updateCollapseIndicator();
        updateCursorAndTooltip();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Tokens.cardBackground());
            g2.fill(shape());
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }

    /**
     * 子组件按圆角裁剪，避免内部文本域 / 表格的方角盖住卡片圆角。
     */
    @Override
    protected void paintChildren(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.clip(shape());
            super.paintChildren(g2);
        } finally {
            g2.dispose();
        }
    }

    /**
     * 描边最后绘制：盖住上一步硬裁剪留下的锯齿，得到平滑圆角。
     */
    @Override
    public void paint(Graphics g) {
        super.paint(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(Tokens.border());
            g2.draw(shape());
        } finally {
            g2.dispose();
        }
    }

    private RoundRectangle2D.Float shape() {
        float arc = Tokens.RADIUS_CARD;
        return new RoundRectangle2D.Float(
                0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, arc, arc);
    }

    /**
     * 卡片头部布局：标题在左、动作在右；放不下时动作整体折到第二行。
     *
     * <p>效果页的面板标题行经常带三到五个操作按钮。{@code BorderLayout.EAST}
     * 会无条件按首选宽度放置动作区，窗口一窄就把右侧按钮挤出卡片；
     * 这里按可用宽度先试一行，放不下就折行，卡片高度随之增加，按钮文字永远不会被截断。</p>
     */
    private static final class HeaderLayout implements java.awt.LayoutManager {

        private static final int GAP = Tokens.SPACE_SM;

        @Override
        public void addLayoutComponent(String name, Component component) { }

        @Override
        public void removeLayoutComponent(Component component) { }

        private static Dimension sizeOf(Component component) {
            if (component == null || !component.isVisible()) {
                return new Dimension(0, 0);
            }
            return component.getPreferredSize();
        }

        /** 容器宽度；首次布局时祖先也可能还没有宽度，按「放得下」处理 */
        private static int available(java.awt.Container parent) {
            int width = parent.getWidth();
            java.awt.Container cursor = parent;
            while (width <= 0 && cursor.getParent() != null) {
                cursor = cursor.getParent();
                width = cursor.getWidth();
            }
            java.awt.Insets insets = parent.getInsets();
            return width <= 0 ? Integer.MAX_VALUE : Math.max(1, width - insets.left - insets.right);
        }

        private static int wrappedHeight(Component actions, int width) {
            if (actions instanceof JPanel panel && panel.getLayout() instanceof WrapRowLayout wrap) {
                return wrap.heightFor(panel, width);
            }
            return sizeOf(actions).height;
        }

        /** 一行放得下就并排，放不下就把动作整体折到标题下方 */
        private static boolean stacked(java.awt.Container parent, int available) {
            if (parent.getComponentCount() < 3) return false;
            Dimension indicator = sizeOf(parent.getComponent(0));
            Dimension title = sizeOf(parent.getComponent(1));
            Dimension actions = sizeOf(parent.getComponent(2));
            if (actions.width == 0) return false;
            return indicator.width + title.width + GAP + actions.width > available;
        }

        @Override
        public Dimension preferredLayoutSize(java.awt.Container parent) {
            java.awt.Insets insets = parent.getInsets();
            int available = available(parent);
            Dimension indicator = parent.getComponentCount() > 0
                    ? sizeOf(parent.getComponent(0)) : new Dimension(0, 0);
            Dimension title = parent.getComponentCount() > 1
                    ? sizeOf(parent.getComponent(1)) : new Dimension(0, 0);
            Dimension actions = parent.getComponentCount() > 2
                    ? sizeOf(parent.getComponent(2)) : new Dimension(0, 0);

            int titleHeight = Math.max(indicator.height, title.height);
            int actionHeight = actions.width == 0 ? 0
                    : (stacked(parent, available)
                            ? wrappedHeight(parent.getComponent(2), available)
                            : actions.height);
            int height = actionHeight == 0
                    ? titleHeight
                    : (stacked(parent, available)
                            ? titleHeight + GAP + actionHeight
                            : Math.max(titleHeight, actionHeight));
            int width = indicator.width + title.width + (actions.width > 0 ? GAP + actions.width : 0);
            return new Dimension(width + insets.left + insets.right,
                    height + insets.top + insets.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(java.awt.Container parent) {
            return preferredLayoutSize(parent);
        }

        @Override
        public void layoutContainer(java.awt.Container parent) {
            java.awt.Insets insets = parent.getInsets();
            int available = available(parent);
            Component indicator = parent.getComponentCount() > 0 ? parent.getComponent(0) : null;
            Component title = parent.getComponentCount() > 1 ? parent.getComponent(1) : null;
            Component actions = parent.getComponentCount() > 2 ? parent.getComponent(2) : null;
            Dimension indicatorSize = sizeOf(indicator);
            Dimension titleSize = sizeOf(title);
            Dimension actionSize = sizeOf(actions);

            int titleHeight = Math.max(indicatorSize.height, titleSize.height);
            boolean stack = stacked(parent, available);

            if (indicator != null && indicatorSize.height > 0) {
                indicator.setBounds(insets.left, insets.top, indicatorSize.width, titleHeight);
            }
            if (title != null && titleSize.height > 0) {
                title.setBounds(insets.left + indicatorSize.width, insets.top,
                        Math.max(1, available - indicatorSize.width), titleHeight);
            }
            if (actions == null || actionSize.width == 0) {
                return;
            }
            if (stack) {
                int height = wrappedHeight(actions, available);
                actions.setBounds(insets.left, insets.top + titleHeight + GAP, available, height);
            } else {
                actions.setBounds(insets.left + available - actionSize.width,
                        insets.top + Math.max(0, (titleHeight - actionSize.height) / 2),
                        actionSize.width, actionSize.height);
            }
        }
    }

    /** 卡片内部使用的 1px 细线 */
    public static final class Hairline extends JComponent {
        @Override
        public Dimension getPreferredSize() {
            return new Dimension(1, 1);
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(Tokens.borderSubtle());
            g.fillRect(0, 0, getWidth(), 1);
        }
    }

    /** 矢量折叠指示 Chevron 图标 */
    private static final class ChevronIcon implements Icon {
        private final boolean down;
        private final int size;

        ChevronIcon(boolean down, int size) {
            this.down = down;
            this.size = size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g2.setColor(Tokens.mutedForeground());
                g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                float midX = x + size / 2f;
                float midY = y + size / 2f;
                Path2D path = new Path2D.Float();
                if (down) {
                    path.moveTo(midX - 4f, midY - 2f);
                    path.lineTo(midX, midY + 2f);
                    path.lineTo(midX + 4f, midY - 2f);
                } else {
                    path.moveTo(midX - 2f, midY - 4f);
                    path.lineTo(midX + 2f, midY);
                    path.lineTo(midX - 2f, midY + 4f);
                }
                g2.draw(path);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }
}
