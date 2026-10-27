package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.ui.kit.Skin;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.SwingConstants;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/**
 * 启动器工具磁贴：彩色渐变底 + 图标 + 中英文名称 + 收藏星标。
 *
 * <p>按效果图实现：卡片是整块可点击区域，右下角是名称层级（中文在上、英文在下），
 * 右上角是只在悬停或已收藏时才出现的星标。悬停时卡片微微上浮并加深投影，
 * 让「可点击」这件事不依赖文字提示。</p>
 *
 * <p>所有颜色在 {@code paintComponent} 里按当前皮肤即时推导，因此切换主题后
 * 不需要重建任何卡片。</p>
 */
public final class LauncherCard extends JButton {

    /** 卡片阴影预留的空白，避免投影被组件边界裁掉 */
    private static final int SHADOW_INSET = 8;
    private static final int ICON_SIZE = Tokens.TILE_ICON;
    private static final int PADDING = 16;
    /** 星标按钮边长 */
    private static final int STAR_SIZE = 24;

    private final String toolId;
    private final String categoryId;
    private final String title;
    private final String subtitle;
    private final JButton starButton = new StarButton();

    private boolean favorite;
    private boolean hover;

    private Font titleFont;
    private Font subtitleFont;

    public LauncherCard(String toolId, String categoryId, String title, String subtitle) {
        this.toolId = toolId;
        this.categoryId = categoryId;
        this.title = title == null ? "" : title;
        this.subtitle = subtitle == null ? "" : subtitle;

        setLayout(null);
        setOpaque(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setBorder(BorderFactory.createEmptyBorder());
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setName("launcher.tile." + toolId);
        setToolTipText(this.subtitle.isEmpty() ? this.title : this.title + " · " + this.subtitle);
        getAccessibleContext().setAccessibleName(
                this.subtitle.isEmpty() ? this.title : this.title + " " + this.subtitle);
        getAccessibleContext().setAccessibleDescription(this.subtitle);

        refreshFonts();

        starButton.setName("launcher.favorite." + toolId);
        starButton.setToolTipText(I18n.get("launcher.favorite.add"));
        starButton.addActionListener(event -> setFavorite(!favorite, true));
        add(starButton);

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                hover = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent event) {
                // 进入星标子组件时也会触发 exited，用坐标再确认一次
                if (!contains(event.getPoint())) {
                    hover = false;
                    repaint();
                }
            }
        });
    }

    public String getToolId() {
        return toolId;
    }

    public boolean isFavorite() {
        return favorite;
    }

    /**
     * 设置收藏状态。
     *
     * @param notify 是否通知外部监听器（由收藏仓储恢复状态时为 false）
     */
    public void setFavorite(boolean value, boolean notify) {
        if (favorite == value) {
            return;
        }
        favorite = value;
        starButton.setIcon(WorkbenchIcons.star(value, 16));
        starButton.setToolTipText(I18n.get(value
                ? "launcher.favorite.remove"
                : "launcher.favorite.add"));
        repaint();
        if (notify) {
            firePropertyChange("favorite", !value, value);
        }
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(Tokens.TILE_WIDTH, Tokens.TILE_HEIGHT);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        refreshFonts();
        repaint();
    }

    private void refreshFonts() {
        titleFont = Tokens.fontBodyStrong().deriveFont(Font.BOLD, 13.5f);
        subtitleFont = Tokens.fontCaption().deriveFont(Font.PLAIN, 11f);
    }

    /** 星标常驻在右上角，但只有悬停或已收藏时才可见 */
    @Override
    public void doLayout() {
        int x = getWidth() - SHADOW_INSET - STAR_SIZE - 2;
        int y = SHADOW_INSET + 2;
        starButton.setBounds(x, y, STAR_SIZE, STAR_SIZE);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            Skin skin = ThemeManager.skin();
            Color seed = Skin.seed(categoryId, toolId);
            Rectangle card = cardBounds();
            float radius = Tokens.RADIUS_TILE;

            int lift = hover || isFocusOwner() ? 2 : 0;
            paintShadow(g, card, radius, lift);

            RoundRectangle2D shape = new RoundRectangle2D.Float(
                    card.x, card.y - lift, card.width, card.height, radius, radius);

            // 主体渐变：左上浅、右下深，与效果图的斜向柔光一致
            g.setPaint(new LinearGradientPaint(
                    card.x, card.y - lift, card.x + card.width, card.y + card.height - lift,
                    new float[]{0f, 1f},
                    new Color[]{skin.tileFrom(seed), skin.tileTo(seed)}));
            g.fill(shape);

            // 顶部高光：一层很淡的白色弧，避免整块纯色显得死板
            g.setPaint(new GradientPaint(
                    card.x, card.y - lift, new Color(255, 255, 255, skin.isDark() ? 18 : 70),
                    card.x, card.y - lift + card.height * 0.55f, new Color(255, 255, 255, 0)));
            g.fill(shape);

            // 描边：浅色皮肤下是淡色边，深色皮肤下靠它勾出轮廓
            g.setStroke(new BasicStroke(1f));
            g.setColor(skin.tileBorder(seed));
            g.draw(new RoundRectangle2D.Float(
                    card.x + 0.5f, card.y - lift + 0.5f,
                    card.width - 1f, card.height - 1f, radius, radius));

            // 图标
            int iconX = card.x + PADDING;
            int iconY = card.y - lift + PADDING;
            ToolGlyph.of(toolId, categoryId, ICON_SIZE).paintIcon(this, g, iconX, iconY);

            paintLabels(g, card, lift);

            // 星标：悬停或已收藏时可见；位置与 StarButton 一致，避免文字与按钮错位
            boolean starVisible = favorite || hover;
            starButton.setVisible(starVisible);
        } finally {
            g.dispose();
        }
    }

    /** 卡片实际绘制区域（去掉四周的阴影留白） */
    private Rectangle cardBounds() {
        return new Rectangle(
                SHADOW_INSET, SHADOW_INSET,
                Math.max(1, getWidth() - SHADOW_INSET * 2),
                Math.max(1, getHeight() - SHADOW_INSET * 2));
    }

    /**
     * 三层递进的圆角矩形模拟投影。
     *
     * <p>Java2D 没有模糊滤镜，逐层放大并降低透明度足以在 4px 范围内得到柔和的暗角，
     * 成本也远低于离屏高斯模糊。</p>
     */
    private void paintShadow(Graphics2D g, Rectangle card, float radius, int lift) {
        Color base = Tokens.shadow();
        int strength = hover || isFocusOwner() ? 64 : 42;
        for (int layer = 4; layer >= 1; layer--) {
            int spread = layer * 2;
            int alpha = Math.max(4, strength / layer);
            g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha));
            g.fill(new RoundRectangle2D.Float(
                    card.x - spread,
                    card.y - lift + spread * 0.6f,
                    card.width + spread * 2f,
                    card.height + spread,
                    radius + spread, radius + spread));
        }
    }

    private void paintLabels(Graphics2D g, Rectangle card, int lift) {
        int textX = card.x + PADDING;
        int maxWidth = card.width - PADDING * 2;

        g.setFont(titleFont);
        g.setColor(Tokens.foreground());
        FontMetrics titleMetrics = g.getFontMetrics();
        String shownTitle = ellipsize(this.title, titleMetrics, maxWidth);
        int titleBaseline = card.y - lift + card.height - 32;
        g.drawString(shownTitle, textX, titleBaseline);

        if (!subtitle.isEmpty()) {
            g.setFont(subtitleFont);
            g.setColor(Tokens.mutedForeground());
            FontMetrics subtitleMetrics = g.getFontMetrics();
            String shownSubtitle = ellipsize(subtitle, subtitleMetrics, maxWidth);
            g.drawString(shownSubtitle, textX, card.y - lift + card.height - 12);
        }
    }

    /** 按像素宽度截断并补省略号，避免长英文名压到圆角 */
    private static String ellipsize(String text, FontMetrics metrics, int maxWidth) {
        if (text == null || text.isEmpty() || metrics.stringWidth(text) <= maxWidth) {
            return text == null ? "" : text;
        }
        String ellipsis = "…";
        int budget = maxWidth - metrics.stringWidth(ellipsis);
        int end = text.length();
        while (end > 0 && metrics.stringWidth(text.substring(0, end)) > budget) {
            end--;
        }
        return text.substring(0, Math.max(0, end)).trim() + ellipsis;
    }

    /** 星标按钮：无边框，只画一颗星，悬停时提亮 */
    private final class StarButton extends JButton {

        private boolean starHover;

        StarButton() {
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setBorder(BorderFactory.createEmptyBorder());
            setHorizontalAlignment(SwingConstants.CENTER);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setIcon(WorkbenchIcons.star(false, 16));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent event) {
                    starHover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    starHover = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            if (starHover) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(Tokens.accentSoft());
                    g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 8, 8));
                } finally {
                    g.dispose();
                }
            }
            super.paintComponent(graphics);
        }
    }
}
