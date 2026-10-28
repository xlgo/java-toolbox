package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Consumer;

/**
 * 工具页标题区：返回按钮 + 语义图标 + 名称 + 一句话说明 + 收藏星标。
 *
 * <p>对应效果页的 {@code .tool-head}。用户从一个工具切到另一个工具时，
 * 标题、图标和描述会同时变化，不必依赖侧栏高亮才能确认当前页；
 * 左上角的返回键回到首页的工具卡片，不必知道快捷键。</p>
 */
public final class ToolHeader extends JPanel {

    private final JLabel title = new JLabel();
    private final JLabel description = new JLabel();
    private final IconBadge badge = new IconBadge();
    private final JButton favorite = new JButton();
    private final JButton back = new JButton();

    private String toolId;
    private String categoryId;
    private ToolPanel tool;
    private Consumer<String> favoriteListener = ignored -> { };
    private Runnable backListener = () -> { };

    public ToolHeader() {
        super(new BorderLayout(Tokens.SPACE_MD + 2, 0));
        setOpaque(false);
        setName("workbench.toolHeader");

        title.setName("workbench.toolTitle");
        title.setFont(Tokens.fontPageTitle());
        title.setForeground(Tokens.foreground());
        description.setName("workbench.toolDescription");
        description.setFont(Tokens.fontCardBody());
        description.setForeground(Tokens.secondaryForeground());

        badge.setPreferredSize(new Dimension(44, 44));
        badge.setMinimumSize(new Dimension(44, 44));
        badge.setMaximumSize(new Dimension(44, 44));

        back.setName("workbench.toolBack");
        back.setIcon(WorkbenchIcons.back(14));
        back.setText(I18n.get("nav.back"));
        back.setFont(Tokens.fontBody());
        back.setIconTextGap(Tokens.SPACE_XS);
        back.setFocusPainted(false);
        back.setMargin(new Insets(4, 10, 4, 12));
        back.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        back.putClientProperty("JComponent.minimumWidth", 0);
        back.addActionListener(event -> backListener.run());
        Dimension backSize = new Dimension(back.getPreferredSize().width, Tokens.CONTROL_HEIGHT);
        back.setPreferredSize(backSize);
        back.setMinimumSize(backSize);

        favorite.setName("workbench.toolFavorite");
        favorite.setFont(Tokens.fontBody());
        favorite.setFocusPainted(false);
        favorite.setMargin(new Insets(3, 8, 3, 8));
        favorite.setHorizontalAlignment(SwingConstants.CENTER);
        favorite.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        favorite.putClientProperty("JComponent.minimumWidth", 0);
        favorite.addActionListener(event -> {
            if (toolId != null) {
                favoriteListener.accept(toolId);
            }
        });

        JPanel texts = new JPanel();
        texts.setOpaque(false);
        texts.setLayout(new javax.swing.BoxLayout(texts, javax.swing.BoxLayout.Y_AXIS));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        description.setAlignmentX(Component.LEFT_ALIGNMENT);
        texts.add(title);
        texts.add(javax.swing.Box.createVerticalStrut(3));
        texts.add(description);

        JPanel identity = new JPanel(new BorderLayout(Tokens.SPACE_MD, 0));
        identity.setOpaque(false);
        identity.add(badge, BorderLayout.WEST);
        identity.add(texts, BorderLayout.CENTER);

        JPanel left = new JPanel(new BorderLayout(Tokens.SPACE_MD + 2, 0));
        left.setOpaque(false);
        left.add(back, BorderLayout.WEST);
        left.add(identity, BorderLayout.CENTER);

        JPanel actions = new JPanel(new BorderLayout());
        actions.setOpaque(false);
        actions.add(favorite, BorderLayout.EAST);

        add(left, BorderLayout.CENTER);
        add(actions, BorderLayout.EAST);
    }

    public void setBackListener(Runnable listener) {
        backListener = listener == null ? () -> { } : listener;
    }

    public void setTool(ToolPanel tool) {
        if (tool == null) return;
        this.tool = tool;
        toolId = tool.getName();
        categoryId = tool.getGroup();
        title.setText(tool.getLabel());
        description.setText(summary(tool));
        description.setToolTipText(description.getText());
        badge.setTool(toolId, categoryId);
        getAccessibleContext().setAccessibleName(tool.getLabel());
        refreshFavorite(favorite.getClientProperty("favorite") == Boolean.TRUE);
    }

    public void setFavorite(boolean value) {
        refreshFavorite(value);
    }

    public void setFavoriteListener(Consumer<String> listener) {
        favoriteListener = listener == null ? ignored -> { } : listener;
    }

    public String getToolId() {
        return toolId;
    }

    /** 文案或主题变化后重新取色、取字。 */
    public void refresh() {
        title.setFont(Tokens.fontPageTitle());
        title.setForeground(Tokens.foreground());
        description.setFont(Tokens.fontCardBody());
        description.setForeground(Tokens.secondaryForeground());
        back.setFont(Tokens.fontBody());
        back.setText(I18n.get("nav.back"));
        back.setIcon(WorkbenchIcons.back(14));
        badge.repaint();
        if (tool != null) {
            title.setText(tool.getLabel());
            description.setText(summary(tool));
            description.setToolTipText(description.getText());
        }
        refreshFavorite(favorite.getClientProperty("favorite") == Boolean.TRUE);
        revalidate();
        repaint();
    }

    private void refreshFavorite(boolean value) {
        favorite.setFont(Tokens.fontBody());
        favorite.putClientProperty("favorite", value);
        favorite.setIcon(WorkbenchIcons.star(value, 15));
        String text = I18n.get(value ? "tool.favorite.on.short" : "tool.favorite.off.short");
        favorite.setText(text);
        favorite.setToolTipText(I18n.get(value
                ? "launcher.favorite.remove" : "launcher.favorite.add"));
        favorite.setForeground(value ? Tokens.favorite() : Tokens.foreground());
        favorite.getAccessibleContext().setAccessibleName(favorite.getToolTipText());
        int textWidth = favorite.getFontMetrics(favorite.getFont()).stringWidth(text);
        Dimension size = new Dimension(Math.max(84, textWidth + 40), Tokens.CONTROL_HEIGHT);
        favorite.setPreferredSize(size);
        favorite.setMinimumSize(size);
    }

    private static String summary(ToolPanel tool) {
        String key = "tool." + tool.getName() + ".summary";
        String value = I18n.get(key);
        return key.equals(value) ? I18n.get("tool.summary.generic") : value;
    }

    /** 44px 圆角图标座：浅强调底色 + 语义线框。 */
    private static final class IconBadge extends JPanel {

        private String toolId;
        private String categoryId;

        IconBadge() {
            setOpaque(false);
        }

        void setTool(String toolId, String categoryId) {
            this.toolId = toolId;
            this.categoryId = categoryId;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                RoundRectangle2D shape = new RoundRectangle2D.Float(
                        0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, 10, 10);
                g.setColor(Tokens.accentSoft());
                g.fill(shape);
                g.setColor(Tokens.accentMid());
                g.setStroke(new BasicStroke(1f));
                g.draw(shape);
            } finally {
                g.dispose();
            }
            if (toolId != null) {
                ToolGlyph.outline(toolId, categoryId, 22).paintIcon(this, graphics, 11, 11);
            }
        }
    }
}
