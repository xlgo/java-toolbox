package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Consumer;

/**
 * 工具页标题区：语义图标 + 名称 + 一句话说明 + 收藏按钮。
 *
 * <p>效果页在每个工具上方保留固定的识别区，用户从一个工具切到另一个工具时，
 * 标题、图标和描述会同时变化，不必依赖侧栏高亮才能确认当前页。该组件只负责展示，
 * 收藏状态由主窗口从侧栏的持久化结果同步进来。</p>
 */
public final class ToolHeader extends JPanel {

    private final JLabel title = new JLabel();
    private final JLabel description = new JLabel();
    private final AccentBadge badge = new AccentBadge();
    private final JButton favorite = new JButton();
    private final JPanel textBox = new JPanel();

    private String toolId;
    private String categoryId;
    private ToolPanel tool;
    private Consumer<String> favoriteListener = ignored -> { };

    public ToolHeader() {
        super(new BorderLayout(Tokens.SPACE_MD, 0));
        setOpaque(false);
        setName("workbench.toolHeader");

        title.setName("workbench.toolTitle");
        title.setFont(Tokens.fontPageTitle());
        title.setForeground(Tokens.foreground());
        description.setName("workbench.toolDescription");
        description.setFont(Tokens.fontBody());
        description.setForeground(Tokens.secondaryForeground());

        textBox.setOpaque(false);
        textBox.setLayout(new BoxLayout(textBox, BoxLayout.Y_AXIS));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        description.setAlignmentX(Component.LEFT_ALIGNMENT);
        textBox.add(title);
        textBox.add(Box.createVerticalStrut(3));
        textBox.add(description);

        badge.setPreferredSize(new Dimension(46, 46));
        badge.setMinimumSize(new Dimension(46, 46));
        badge.setMaximumSize(new Dimension(46, 46));

        favorite.setName("workbench.toolFavorite");
        favorite.setFont(Tokens.fontBody());
        favorite.setFocusPainted(true);
        favorite.setMargin(new java.awt.Insets(3, 12, 3, 12));
        favorite.setHorizontalAlignment(SwingConstants.CENTER);
        favorite.addActionListener(event -> {
            if (toolId != null) {
                favoriteListener.accept(toolId);
            }
        });

        JPanel leading = new JPanel(new BorderLayout(Tokens.SPACE_MD, 0));
        leading.setOpaque(false);
        leading.add(badge, BorderLayout.WEST);
        leading.add(textBox, BorderLayout.CENTER);

        add(leading, BorderLayout.CENTER);
        add(favorite, BorderLayout.EAST);
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
        refreshFavoriteLabel(favorite.getClientProperty("favorite") == Boolean.TRUE);
    }

    public void setFavorite(boolean value) {
        refreshFavoriteLabel(value);
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
        description.setFont(Tokens.fontBody());
        description.setForeground(Tokens.secondaryForeground());
        badge.repaint();
        if (tool != null) {
            title.setText(tool.getLabel());
            description.setText(summary(tool));
            description.setToolTipText(description.getText());
        }
        refreshFavoriteLabel(favorite.getClientProperty("favorite") == Boolean.TRUE);
        revalidate();
        repaint();
    }

    private void refreshFavoriteLabel(boolean value) {
        favorite.setFont(Tokens.fontBody());
        favorite.putClientProperty("favorite", value);
        String text = I18n.get(value ? "tool.favorite.on" : "tool.favorite.off");
        favorite.setText(text);
        favorite.setToolTipText(text);
        favorite.getAccessibleContext().setAccessibleName(text);
        int textWidth = favorite.getFontMetrics(favorite.getFont()).stringWidth(text);
        favorite.setPreferredSize(new Dimension(
                Math.max(96, textWidth + 28), Tokens.CONTROL_HEIGHT));
        favorite.setMinimumSize(favorite.getPreferredSize());
    }

    private static String summary(ToolPanel tool) {
        String key = "tool." + tool.getName() + ".summary";
        String value = I18n.get(key);
        return key.equals(value) ? I18n.get("tool.summary.generic") : value;
    }

    /** 46px 圆角图标座：浅强调底色 + 语义线框。 */
    private static final class AccentBadge extends JPanel {

        private String toolId;
        private String categoryId;

        AccentBadge() {
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
                        0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, 12, 12);
                g.setColor(Tokens.accentSoft());
                g.fill(shape);
                g.setColor(Tokens.accentMid());
                g.draw(shape);
            } finally {
                g.dispose();
            }
            if (toolId != null) {
                ToolGlyph.outline(toolId, categoryId, 23).paintIcon(this, graphics, 11, 11);
            }
        }
    }
}
