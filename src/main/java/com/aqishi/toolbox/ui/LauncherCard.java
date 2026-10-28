package com.aqishi.toolbox.ui;

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
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * 首页工具卡：白底、12px 圆角、细描边，图标座 + 名称 + 一句话说明 + 分类标签。
 *
 * <p>对应效果页的 {@code .card}：整块可点击，右上角是收藏星标（常驻，避免「找不到收藏按钮」），
 * 悬停时描边加深并轻微上浮。文字用 {@link FontMetrics} 手工换行并截断到两行，
 * 既保证所有卡片等高，也不会因为描述过长把标签挤出卡片。</p>
 *
 * <p>颜色全部在 {@code paintComponent} 里按当前主题推导，因此切换主题后
 * 不需要重建任何卡片。</p>
 */
public final class LauncherCard extends JButton {

    /** 卡片阴影预留的空白，避免投影被组件边界裁掉 */
    private static final int SHADOW_INSET = 3;
    private static final int PADDING = 18;
    /** 图标与标题之间的呼吸感，效果页是 10px */
    private static final int ICON_TEXT_GAP = 11;
    private static final int ICON_SIZE = Tokens.TILE_ICON;
    private static final int STAR_SIZE = 22;
    private static final int DESC_LINES = 2;

    private final String toolId;
    private final String categoryId;
    private final String title;
    private final String subtitle;
    private final String tag;
    private final StarButton starButton = new StarButton();

    private boolean favorite;
    private boolean hover;

    private Font titleFont;
    private Font subtitleFont;
    private Font tagFont;

    public LauncherCard(String toolId, String categoryId, String title, String subtitle, String tag) {
        this.toolId = toolId;
        this.categoryId = categoryId;
        this.title = title == null ? "" : title;
        this.subtitle = subtitle == null ? "" : subtitle;
        this.tag = tag == null ? "" : tag;

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
        starButton.setFavorite(value);
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
        titleFont = Tokens.fontCardTitle();
        subtitleFont = Tokens.fontCardBody();
        tagFont = Tokens.fontCaption().deriveFont(Font.PLAIN, 11f);
    }

    /** 星标常驻右上角 */
    @Override
    public void doLayout() {
        int x = getWidth() - SHADOW_INSET - PADDING - STAR_SIZE + 4;
        int y = SHADOW_INSET + PADDING - 4;
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

            Rectangle card = cardBounds();
            float radius = Tokens.RADIUS_TILE;
            int lift = hover || isFocusOwner() ? 2 : 0;

            // 悬停投影：效果页用一层很淡的阴影表达「可点击」，静态时不画
            if (hover || isFocusOwner()) {
                paintShadow(g, card, radius, lift);
            }

            RoundRectangle2D shape = new RoundRectangle2D.Float(
                    card.x, card.y - lift, card.width, card.height, radius, radius);
            g.setColor(Tokens.cardBackground());
            g.fill(shape);

            g.setStroke(new BasicStroke(isFocusOwner() ? 2f : 1f));
            g.setColor(isFocusOwner() ? Tokens.accent()
                    : hover ? Tokens.borderStrong() : Tokens.border());
            float inset = isFocusOwner() ? 1f : 0.5f;
            g.draw(new RoundRectangle2D.Float(
                    card.x + inset, card.y - lift + inset,
                    card.width - inset * 2, card.height - inset * 2, radius, radius));

            int left = card.x + PADDING;
            int top = card.y - lift + PADDING;
            ToolGlyph.of(toolId, categoryId, ICON_SIZE).paintIcon(this, g, left, top);

            paintText(g, card, lift);
        } finally {
            g.dispose();
        }
    }

    private Rectangle cardBounds() {
        return new Rectangle(
                SHADOW_INSET, SHADOW_INSET,
                Math.max(1, getWidth() - SHADOW_INSET * 2),
                Math.max(1, getHeight() - SHADOW_INSET * 2));
    }

    private void paintShadow(Graphics2D g, Rectangle card, float radius, int lift) {
        Color base = Tokens.shadow();
        // 三层递减的光晕，模拟效果页的 0 10px 30px rgba(25,35,70,.10)
        for (int layer = 3; layer >= 1; layer--) {
            int spread = layer * 2;
            int alpha = Math.max(5, 16 / layer);
            g.setColor(Tokens.alpha(base, alpha));
            g.fill(new RoundRectangle2D.Float(
                    card.x - spread,
                    card.y - lift + spread * 0.8f,
                    card.width + spread * 2f,
                    card.height + spread * 1.2f,
                    radius + spread, radius + spread));
        }
    }

    private void paintText(Graphics2D g, Rectangle card, int lift) {
        int left = card.x + PADDING;
        int maxWidth = card.width - PADDING * 2;

        int titleY = card.y - lift + PADDING + ICON_SIZE + ICON_TEXT_GAP
                + g.getFontMetrics(titleFont).getAscent();
        g.setFont(titleFont);
        g.setColor(Tokens.foreground());
        FontMetrics titleMetrics = g.getFontMetrics();
        g.drawString(ellipsize(title, titleMetrics, maxWidth), left, titleY);

        g.setFont(subtitleFont);
        g.setColor(Tokens.secondaryForeground());
        FontMetrics metrics = g.getFontMetrics();
        int lineHeight = metrics.getHeight();
        int y = titleY + 7 + metrics.getAscent();
        for (String line : wrap(subtitle, metrics, maxWidth, DESC_LINES)) {
            g.drawString(line, left, y);
            y += lineHeight;
        }

        if (!tag.isEmpty()) {
            g.setFont(tagFont);
            g.setColor(Tokens.faintForeground());
            FontMetrics tagMetrics = g.getFontMetrics();
            int tagBaseline = card.y - lift + card.height - PADDING;
            g.drawString(ellipsize(tag, tagMetrics, maxWidth), left, tagBaseline);
        }
    }

    /**
     * 按像素宽度折行，尽量在词边界断开，最后一行用省略号收尾。
     *
     * <p>逐字符折行会在 Latin 单词中间断开（例如 "P / roperties"），中英混排的
     * 说明文字读起来很别扭。这里记录每一行最近一个可断点（空格、顿号、逗号、
     * 斜杠等），超宽时回到该断点换行；找不到断点才按字符硬断。</p>
     */
    private static List<String> wrap(String text, FontMetrics metrics, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        StringBuilder current = new StringBuilder();
        int lastBreak = -1;
        int index = 0;
        int consumedChars = 0;
        while (index < text.length()) {
            char ch = text.charAt(index);
            if (ch == '\n') {
                lines.add(current.toString());
                consumedChars += current.length() + 1;
                current.setLength(0);
                lastBreak = -1;
                index++;
                if (lines.size() == maxLines) {
                    break;
                }
                continue;
            }
            current.append(ch);
            if (isBreakOpportunity(ch)) {
                lastBreak = current.length();
            }
            if (metrics.stringWidth(current.toString()) > maxWidth && current.length() > 1) {
                int breakAt = lastBreak > 0 && lastBreak < current.length() ? lastBreak : current.length() - 1;
                String head = current.substring(0, breakAt).trim();
                String tail = current.substring(breakAt).trim();
                lines.add(head);
                consumedChars += head.length();
                current.setLength(0);
                current.append(tail);
                lastBreak = -1;
                if (lines.size() == maxLines) {
                    break;
                }
            }
            index++;
        }
        if (lines.size() < maxLines && current.length() > 0) {
            lines.add(current.toString().trim());
            consumedChars += current.length();
        }
        boolean truncated = consumedChars < text.replace("\n", "").trim().length();
        if (truncated && !lines.isEmpty()) {
            int last = lines.size() - 1;
            lines.set(last, ellipsize(lines.get(last) + "…", metrics, maxWidth));
        }
        return lines;
    }

    /** 可断行的位置：空白与中英文标点 */
    private static boolean isBreakOpportunity(char ch) {
        return Character.isWhitespace(ch)
                || "、，。；：/|,.;:!?)]}）】”’".indexOf(ch) >= 0;
    }

    /** 按像素宽度截断并补省略号 */
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

    /** 星标按钮：无边框，只画一颗星；已收藏时用暖黄色实心 */
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

        void setFavorite(boolean value) {
            setIcon(WorkbenchIcons.star(value, 16));
            setForeground(value ? Tokens.favorite() : Tokens.mutedForeground());
            setToolTipText(I18n.get(value ? "launcher.favorite.remove" : "launcher.favorite.add"));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                if (starHover) {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(Tokens.panelAlt());
                    g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 6, 6));
                }
            } finally {
                g.dispose();
            }
            setForeground(favorite ? Tokens.favorite() : Tokens.mutedForeground());
            super.paintComponent(graphics);
        }
    }
}
