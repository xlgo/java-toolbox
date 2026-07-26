package com.aqishi.toolbox.ui.kit;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 卡片容器：圆角描边 + 可选标题带 + 内容区。
 *
 * <p>用来替代到处出现的 {@code TitledBorder}。标题带与内容之间只用一条细线分隔，
 * 不叠加阴影或额外留白；背景与描边在绘制时从 {@link Tokens} 读取，切换主题即跟随。</p>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * Card card = Card.titled("随机数据配置");
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
        }
    }

    private void buildHeader(String title, String subtitle) {
        header = new JPanel(new BorderLayout(Tokens.SPACE_SM, 0));
        header.setOpaque(false);

        titleLabel = new JLabel(title);
        titleLabel.setFont(Tokens.fontSectionTitle());
        titleLabel.setForeground(Tokens.foreground());

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

        // 用 BoxLayout 而不是 FlowLayout：动作多于两三个时，窄窗口下不会把标题挤没或换行
        actionBox = new JPanel();
        actionBox.setLayout(new javax.swing.BoxLayout(actionBox, javax.swing.BoxLayout.X_AXIS));
        actionBox.setOpaque(false);
        actionBox.setVisible(false);

        header.add(titleBox, BorderLayout.CENTER);
        header.add(actionBox, BorderLayout.EAST);

        JPanel headerWrapper = new JPanel(new BorderLayout());
        headerWrapper.setOpaque(false);
        headerWrapper.setBorder(new EmptyBorder(
                Tokens.SPACE_MD - 2, Tokens.CARD_PADDING, Tokens.SPACE_SM, Tokens.CARD_PADDING));
        headerWrapper.add(header, BorderLayout.CENTER);

        JPanel headerBlock = new JPanel(new BorderLayout());
        headerBlock.setOpaque(false);
        headerBlock.add(headerWrapper, BorderLayout.CENTER);
        headerBlock.add(new Hairline(), BorderLayout.SOUTH);
        add(headerBlock, BorderLayout.NORTH);
    }

    private void applyContentPadding() {
        if (flush) {
            content.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
        } else {
            content.setBorder(new EmptyBorder(
                    Tokens.SPACE_MD, Tokens.CARD_PADDING, Tokens.CARD_PADDING, Tokens.CARD_PADDING));
        }
    }

    /** 内容是否铺满卡片（不加内边距） */
    public Card setFlush(boolean value) {
        this.flush = value;
        applyContentPadding();
        return this;
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
            java.awt.Dimension preferred = target.getPreferredSize();
            target.setMaximumSize(new java.awt.Dimension(preferred.width, Integer.MAX_VALUE));
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

    @Override
    public void updateUI() {
        super.updateUI();
        // 主题切换后重新取色取字，避免沿用旧主题的前景色
        if (titleLabel != null) {
            titleLabel.setFont(Tokens.fontSectionTitle());
            titleLabel.setForeground(Tokens.foreground());
        }
        if (subtitleLabel != null) {
            subtitleLabel.setFont(Tokens.fontCaption());
            subtitleLabel.setForeground(Tokens.mutedForeground());
        }
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
            g2.setStroke(new java.awt.BasicStroke(1f));
            g2.setColor(Tokens.border());
            g2.draw(shape());
        } finally {
            g2.dispose();
        }
    }

    private java.awt.geom.RoundRectangle2D.Float shape() {
        float arc = Tokens.RADIUS_CARD;
        return new java.awt.geom.RoundRectangle2D.Float(
                0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, arc, arc);
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
}
