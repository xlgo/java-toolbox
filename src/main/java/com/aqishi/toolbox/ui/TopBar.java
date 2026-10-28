package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.ui.kit.Skin;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.ConfigManager;
import com.aqishi.toolbox.util.I18n;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * 应用顶栏：侧栏开关、居中搜索、五套主题色卡与外观菜单。
 *
 * <p>对应效果页的 {@code .topbar}：搜索框在窗口正中（不是靠左），右侧直接摆出
 * 五个主题圆点与当前主题名，换肤是一步操作。主题库里的其它外观、强调色与语言
 * 收进齿轮菜单，不占用主操作区。</p>
 *
 * <p>搜索带 140ms 去抖，中文输入法组字期间不触发，避免拼音被当成关键词筛掉全部工具。</p>
 */
public final class TopBar extends JPanel {

    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int SEARCH_DEBOUNCE_MS = 140;

    /** 顶栏直接展示的主题：与效果页的五个色卡一一对应 */
    private static final Skin[] SWATCHES = {
            Skin.LIGHT, Skin.DARK, Skin.MIDNIGHT, Skin.FOREST, Skin.PAPER
    };

    private final JButton sidebarButton = new JButton();
    private final SearchBox searchBox = new SearchBox();
    private final JTextField searchField = searchBox.field();
    private final JLabel themeLabel = new JLabel();
    private final JLabel themeName = new JLabel();
    private final JPanel swatchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final JButton appearanceButton = new JButton();
    private JComponent leadingPanel;
    private JComponent trailingPanel;
    private SearchArea searchArea;
    private final Timer searchDebounce;

    private Consumer<String> queryListener = ignored -> { };
    private Consumer<Skin> skinListener = ignored -> { };
    private Consumer<Integer> accentListener = ignored -> { };
    private Consumer<String> themeListener = ignored -> { };
    private Consumer<String> localeListener = ignored -> { };
    private Runnable sidebarListener = () -> { };
    private Runnable searchDownListener = () -> { };

    private boolean composing;
    private boolean sidebarExpanded = true;

    public TopBar() {
        super(null);
        setOpaque(false);
        setName("workbench.topbar");
        setBorder(new javax.swing.border.EmptyBorder(0, 14, 0, 14));

        leadingPanel = buildLeading();
        searchArea = new SearchArea(searchBox);
        trailingPanel = buildTrailing();
        add(leadingPanel);
        add(searchArea);
        add(trailingPanel);

        searchDebounce = new Timer(SEARCH_DEBOUNCE_MS, event -> queryListener.accept(searchField.getText()));
        searchDebounce.setRepeats(false);

        refreshLabels();
        refreshThemeIndicators();
    }

    // ---------------------------------------------------------------- 组件

    private JComponent buildLeading() {
        JPanel leading = new JPanel();
        leading.setOpaque(false);
        leading.setLayout(new BoxLayout(leading, BoxLayout.X_AXIS));
        // 顶栏 54px、控件 34px：上下各留 10px，行内控件与搜索框基线对齐
        leading.setBorder(new javax.swing.border.EmptyBorder(10, 0, 10, 0));

        sidebarButton.setName("workbench.sidebarToggle");
        sidebarButton.setText(null);
        sidebarButton.setIcon(WorkbenchIcons.sidebar(false));
        sidebarButton.setFocusPainted(false);
        sidebarButton.setContentAreaFilled(false);
        sidebarButton.setBorderPainted(false);
        sidebarButton.putClientProperty("JComponent.minimumWidth", 0);
        sidebarButton.setMargin(new Insets(2, 5, 2, 5));
        sidebarButton.setPreferredSize(new Dimension(30, Tokens.CONTROL_HEIGHT));
        sidebarButton.setMaximumSize(new Dimension(30, Tokens.CONTROL_HEIGHT));
        sidebarButton.addActionListener(event -> sidebarListener.run());

        leading.add(sidebarButton);
        return leading;
    }

    private JComponent buildTrailing() {
        JPanel trailing = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        trailing.setOpaque(false);
        trailing.setBorder(new javax.swing.border.EmptyBorder(10, 0, 10, 0));

        themeLabel.setFont(Tokens.fontCaption());
        themeLabel.setForeground(Tokens.secondaryForeground());
        themeLabel.setPreferredSize(new Dimension(30, Tokens.CONTROL_HEIGHT));
        themeLabel.setVerticalAlignment(SwingConstants.CENTER);
        swatchRow.setOpaque(false);
        swatchRow.setPreferredSize(new Dimension(148, Tokens.CONTROL_HEIGHT));

        for (Skin skin : SWATCHES) {
            ThemeSwatch swatch = new ThemeSwatch(skin);
            swatch.setName("top.theme." + skin.name().toLowerCase());
            swatch.addActionListener(event -> skinListener.accept(skin));
            swatchRow.add(swatch);
        }

        themeName.setFont(Tokens.fontBody());
        themeName.setForeground(Tokens.foreground());
        themeName.setPreferredSize(new Dimension(58, Tokens.CONTROL_HEIGHT));
        themeName.setVerticalAlignment(SwingConstants.CENTER);

        appearanceButton.setName("workbench.appearance");
        appearanceButton.setIcon(WorkbenchIcons.gear(16));
        appearanceButton.setText(null);
        appearanceButton.setFocusPainted(false);
        appearanceButton.setContentAreaFilled(false);
        appearanceButton.setBorderPainted(false);
        appearanceButton.putClientProperty("JComponent.minimumWidth", 0);
        appearanceButton.setMargin(new Insets(2, 5, 2, 5));
        appearanceButton.setPreferredSize(new Dimension(30, Tokens.CONTROL_HEIGHT));
        appearanceButton.addActionListener(event -> showAppearanceMenu());

        trailing.add(themeLabel);
        trailing.add(swatchRow);
        trailing.add(themeName);
        trailing.add(appearanceButton);
        return trailing;
    }

    private void adaptToWidth() {
        updateResponsiveVisibility(getWidth());
        revalidate();
        repaint();
    }

    private void updateResponsiveVisibility(int width) {
        boolean showLabel = width >= 980;
        boolean showName = width >= 900;
        if (themeLabel.isVisible() != showLabel) themeLabel.setVisible(showLabel);
        if (themeName.isVisible() != showName) themeName.setVisible(showName);
    }

    @Override
    public Dimension getPreferredSize() {
        // null 布局不会从子组件推导高度；顶栏高度必须显式回报，
        // 否则放进 BorderLayout.NORTH 时会被压成 0。
        return new Dimension(640, Tokens.TOPBAR_HEIGHT);
    }

    @Override
    public void doLayout() {
        updateResponsiveVisibility(getWidth());
        int insetTop = getInsets().top;
        int insetLeft = getInsets().left;
        int insetRight = getInsets().right;
        int contentHeight = Math.max(1, getHeight() - insetTop - getInsets().bottom);

        Dimension leadingSize = leadingPanel.getPreferredSize();
        Dimension trailingSize = trailingPanel.getPreferredSize();
        leadingPanel.setBounds(insetLeft, insetTop, leadingSize.width, contentHeight);
        trailingPanel.setBounds(
                getWidth() - insetRight - trailingSize.width, insetTop,
                trailingSize.width, contentHeight);

        // 搜索框相对窗口居中，而不是在左右两侧剩余空间里居中：
        // 右侧控件远多于左侧，后者会明显偏左。
        int side = Math.max(leadingSize.width, trailingSize.width);
        int available = getWidth() - insetLeft - insetRight - side * 2 - Tokens.SPACE_LG;
        int searchWidth = Math.min(460, Math.max(120, available));
        int searchX = insetLeft + (getWidth() - insetLeft - insetRight - searchWidth) / 2;
        int searchHeight = Math.min(34, contentHeight);
        searchArea.setBounds(searchX, insetTop + (contentHeight - searchHeight) / 2,
                searchWidth, searchHeight);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        graphics.setColor(Tokens.chromeBackground());
        graphics.fillRect(0, 0, getWidth(), getHeight());
        super.paintComponent(graphics);
    }

    // ---------------------------------------------------------------- 搜索

    private void scheduleSearch() {
        if (!composing) {
            searchDebounce.restart();
        }
    }

    /** 搜索区：把搜索框限制在 460px 并居中。 */
    private static final class SearchArea extends JPanel {

        private final SearchBox box;

        private SearchArea(SearchBox box) {
            super(null);
            this.box = box;
            setOpaque(false);
            add(box);
        }

        @Override
        public void doLayout() {
            int width = Math.min(460, getWidth());
            int x = Math.max(0, (getWidth() - width) / 2);
            box.setBounds(x, 0, width, getHeight());
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(460, 34);
        }
    }

    /** 圆角搜索框：内部是无边框文本框，放大镜与快捷键提示都由这层负责绘制。 */
    private final class SearchBox extends JPanel {

        private final JTextField field = new JTextField();
        private final JLabel hint = new JLabel("/");
        private boolean focus;

        SearchBox() {
            super(new BorderLayout(6, 0));
            setOpaque(false);
            setBorder(new javax.swing.border.EmptyBorder(0, 10, 0, 8));
            setPreferredSize(new Dimension(460, 34));
            setMinimumSize(new Dimension(190, 34));
            setMaximumSize(new Dimension(460, 34));

            field.setName("workbench.search");
            field.setOpaque(false);
            field.setBorder(BorderFactory.createEmptyBorder());
            field.putClientProperty("JTextField.showClearButton", true);
            field.putClientProperty("JTextField.leadingIcon", WorkbenchIcons.search());
            field.setFont(Tokens.fontBody());
            field.getAccessibleContext().setAccessibleName(I18n.get("nav.search.accessible"));
            field.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override public void focusGained(java.awt.event.FocusEvent event) {
                    focus = true;
                    repaint();
                }
                @Override public void focusLost(java.awt.event.FocusEvent event) {
                    focus = false;
                    repaint();
                }
            });
            field.addInputMethodListener(new java.awt.event.InputMethodListener() {
                @Override
                public void inputMethodTextChanged(java.awt.event.InputMethodEvent event) {
                    int length = event.getText() == null
                            ? 0
                            : event.getText().getEndIndex() - event.getText().getBeginIndex();
                    composing = length > event.getCommittedCharacterCount();
                    if (!composing) scheduleSearch();
                }
                @Override public void caretPositionChanged(java.awt.event.InputMethodEvent event) { }
            });
            field.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent event) { scheduleSearch(); }
                @Override public void removeUpdate(DocumentEvent event) { scheduleSearch(); }
                @Override public void changedUpdate(DocumentEvent event) { scheduleSearch(); }
            });
            field.getInputMap().put(
                    javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DOWN, 0),
                    "sidebar.nextTool");
            field.getActionMap().put("sidebar.nextTool", new javax.swing.AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                    searchDownListener.run();
                }
            });

            hint.setFont(Tokens.fontCaption());
            hint.setForeground(Tokens.mutedForeground());
            hint.setPreferredSize(new Dimension(18, 18));
            hint.setHorizontalAlignment(SwingConstants.CENTER);
            hint.setBorder(BorderFactory.createLineBorder(Tokens.borderStrong()));

            add(field, BorderLayout.CENTER);
            add(hint, BorderLayout.EAST);

            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent event) {
                    field.requestFocusInWindow();
                }
            });
            setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
        }

        JTextField field() {
            return field;
        }

        void restyle() {
            field.setFont(Tokens.fontBody());
            hint.setFont(Tokens.fontCaption());
            hint.setForeground(Tokens.mutedForeground());
            hint.setBorder(BorderFactory.createLineBorder(Tokens.borderStrong()));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                RoundRectangle2D shape = new RoundRectangle2D.Float(
                        0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f,
                        Tokens.RADIUS_CONTROL, Tokens.RADIUS_CONTROL);
                g.setColor(Tokens.panelAlt());
                g.fill(shape);
                g.setColor(focus ? Tokens.accent() : Tokens.border());
                g.setStroke(new BasicStroke(1f));
                g.draw(shape);
            } finally {
                g.dispose();
            }
        }
    }

    // ---------------------------------------------------------------- 菜单

    public void showAppearanceMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.add(sectionLabel(I18n.get("launcher.appearance.title")));

        ButtonGroup skinGroup = new ButtonGroup();
        for (Skin skin : Skin.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(skin.label());
            item.setFont(Tokens.fontBody());
            item.setSelected(ThemeManager.skin() == skin);
            item.addActionListener(event -> {
                skinListener.accept(skin);
                refreshThemeIndicators();
            });
            skinGroup.add(item);
            menu.add(item);
        }

        menu.addSeparator();
        menu.add(sectionLabel(I18n.get("top.accent.label")));
        ButtonGroup accentGroup = new ButtonGroup();
        JRadioButtonMenuItem follow = new JRadioButtonMenuItem(I18n.get("top.accent.follow"));
        follow.setFont(Tokens.fontBody());
        follow.setSelected(!ThemeManager.hasCustomAccent());
        follow.addActionListener(event -> accentListener.accept(0));
        accentGroup.add(follow);
        menu.add(follow);
        for (int color : ThemeManager.ACCENTS) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(
                    I18n.get("top.accent." + String.format("%06X", color).toLowerCase()));
            item.setFont(Tokens.fontBody());
            item.setIcon(accentIcon(color));
            item.setSelected(ThemeManager.hasCustomAccent() && ThemeManager.accentRgb() == color);
            item.addActionListener(event -> accentListener.accept(color));
            accentGroup.add(item);
            menu.add(item);
        }

        menu.addSeparator();
        JMenuItem allThemes = new JMenuItem(I18n.get("launcher.appearance.more"));
        allThemes.setFont(Tokens.fontBody());
        allThemes.addActionListener(event -> showAllThemes());
        menu.add(allThemes);

        JMenuItem language = new JMenuItem(I18n.get("top.lang"));
        language.setFont(Tokens.fontBody());
        language.addActionListener(event -> showLanguageMenu());
        menu.add(language);

        menu.show(appearanceButton, -180, appearanceButton.getHeight() + 4);
    }

    private void showAllThemes() {
        JPopupMenu menu = new JPopupMenu();
        menu.setLayout(new BorderLayout());
        JComboBox<String> box = new JComboBox<>(ThemeManager.names());
        box.setSelectedItem(ThemeManager.current().name);
        box.setFont(Tokens.fontBody());
        box.setPreferredSize(new Dimension(300, Tokens.CONTROL_HEIGHT));
        box.addActionListener(event -> {
            themeListener.accept((String) box.getSelectedItem());
            menu.setVisible(false);
        });
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(new javax.swing.border.EmptyBorder(6, 6, 6, 6));
        wrapper.add(box, BorderLayout.CENTER);
        menu.add(wrapper, BorderLayout.CENTER);
        menu.show(appearanceButton, -260, appearanceButton.getHeight() + 4);
        box.requestFocusInWindow();
    }

    private void showLanguageMenu() {
        JPopupMenu menu = new JPopupMenu();
        ButtonGroup group = new ButtonGroup();
        String current = ConfigManager.get("locale", "zh_CN");
        for (String locale : new String[]{"zh_CN", "en_US"}) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(
                    I18n.get("language." + ("en_US".equals(locale) ? "en" : "zh")));
            item.setFont(Tokens.fontBody());
            item.setSelected(locale.equals(current));
            item.addActionListener(event -> localeListener.accept(locale));
            group.add(item);
            menu.add(item);
        }
        menu.show(appearanceButton, -140, appearanceButton.getHeight() + 4);
    }

    private static JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Tokens.fontCaption().deriveFont(Font.BOLD, 11f));
        label.setForeground(Tokens.mutedForeground());
        label.setBorder(new javax.swing.border.EmptyBorder(6, 10, 4, 10));
        return label;
    }

    private static javax.swing.Icon accentIcon(int rgb) {
        return new javax.swing.Icon() {
            @Override public int getIconWidth() { return 13; }
            @Override public int getIconHeight() { return 13; }
            @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(rgb));
                    g.fill(new Ellipse2D.Float(x, y, 13, 13));
                } finally {
                    g.dispose();
                }
            }
        };
    }

    // ---------------------------------------------------------------- 外部接口

    public void setQueryListener(Consumer<String> listener) {
        queryListener = listener == null ? ignored -> { } : listener;
    }

    public void setSkinListener(Consumer<Skin> listener) {
        skinListener = listener == null ? ignored -> { } : listener;
    }

    public void setAccentListener(Consumer<Integer> listener) {
        accentListener = listener == null ? ignored -> { } : listener;
    }

    public void setThemeListener(Consumer<String> listener) {
        themeListener = listener == null ? ignored -> { } : listener;
    }

    public void setLocaleListener(Consumer<String> listener) {
        localeListener = listener == null ? ignored -> { } : listener;
    }

    public void setSidebarListener(Runnable listener) {
        sidebarListener = listener == null ? () -> { } : listener;
    }

    public void setSearchDownListener(Runnable listener) {
        searchDownListener = listener == null ? () -> { } : listener;
    }

    public JTextField getSearchField() {
        return searchField;
    }

    public void focusSearch() {
        searchField.requestFocusInWindow();
        searchField.selectAll();
    }

    public void setQuery(String value) {
        if (!searchField.getText().equals(value == null ? "" : value)) {
            searchField.setText(value == null ? "" : value);
        }
    }

    public void setSidebarExpanded(boolean expanded) {
        sidebarExpanded = expanded;
        sidebarButton.setIcon(WorkbenchIcons.sidebar(!expanded));
        sidebarButton.setToolTipText(I18n.get(expanded ? "nav.collapse" : "nav.expand"));
        sidebarButton.getAccessibleContext().setAccessibleName(sidebarButton.getToolTipText());
    }

    public void refreshLabels() {
        themeLabel.setText(I18n.get("top.theme"));
        searchField.putClientProperty("JTextField.placeholderText",
                I18n.get("top.search.placeholder.card"));
        searchField.getAccessibleContext().setAccessibleName(I18n.get("nav.search.accessible"));
        appearanceButton.setToolTipText(I18n.get("launcher.appearance.title"));
        appearanceButton.getAccessibleContext().setAccessibleName(I18n.get("launcher.appearance.title"));
        setSidebarExpanded(sidebarExpanded);
        refreshThemeIndicators();
        revalidate();
        repaint();
    }

    public void refreshThemeIndicators() {
        themeLabel.setFont(Tokens.fontCaption());
        themeLabel.setForeground(Tokens.secondaryForeground());
        themeName.setFont(Tokens.fontBody());
        themeName.setForeground(Tokens.foreground());
        for (Component component : swatchRow.getComponents()) {
            if (component instanceof ThemeSwatch swatch) {
                swatch.setSelectedKey(swatch.skin == ThemeManager.skin());
                swatch.repaint();
            }
        }
        themeName.setText(ThemeManager.skin().label());
        searchBox.restyle();
        repaint();
    }

    // ---------------------------------------------------------------- 自绘控件

    /**
     * 主题色卡：效果页的圆形色块，左上半是主题底色、右下半是强调色。
     *
     * <p>用两段纯色拼出「这个主题长什么样」，比只画一个纯色点更有辨识度，
     * 也不需要为每套主题维护缩略图。</p>
     */
    private final class ThemeSwatch extends JToggleButton {

        private final Skin skin;
        private boolean hover;

        private ThemeSwatch(Skin skin) {
            this.skin = skin;
            setText(null);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setPreferredSize(new Dimension(26, Tokens.CONTROL_HEIGHT));
            setMinimumSize(new Dimension(26, Tokens.CONTROL_HEIGHT));
            setMaximumSize(new Dimension(26, Tokens.CONTROL_HEIGHT));
            setToolTipText(skin.label());
            getAccessibleContext().setAccessibleName(skin.label());
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent event) {
                    hover = true;
                    repaint();
                }
                @Override public void mouseExited(MouseEvent event) {
                    hover = false;
                    repaint();
                }
            });
        }

        void setSelectedKey(boolean value) {
            setSelected(value);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int size = Math.min(getWidth(), Tokens.CONTROL_HEIGHT) - 8;
                int x = (getWidth() - size) / 2;
                int y = (getHeight() - size) / 2;
                Ellipse2D circle = new Ellipse2D.Float(x, y, size, size);

                if (isSelected()) {
                    g.setColor(Tokens.accentSoft());
                    g.fill(new Ellipse2D.Float(0, 0, getWidth(), getHeight()));
                } else if (hover) {
                    g.setColor(Tokens.hoverSurface());
                    g.fill(new Ellipse2D.Float(1, 1, getWidth() - 2, getHeight() - 2));
                }

                g.setClip(circle);
                Color base = ThemeManager.palette(skin).workspace();
                Color accent = ThemeManager.palette(skin).accent();
                g.setColor(base);
                g.fill(circle);
                g.setColor(accent);
                java.awt.geom.Path2D half = new java.awt.geom.Path2D.Float();
                half.moveTo(x, y + size);
                half.lineTo(x + size, y);
                half.lineTo(x + size, y + size);
                half.closePath();
                g.fill(half);
                g.setClip(null);

                g.setColor(isSelected() ? Tokens.accent() : Tokens.borderStrong());
                g.setStroke(new BasicStroke(isSelected() ? 2f : 1.2f));
                g.draw(circle);
            } finally {
                g.dispose();
            }
        }
    }
}
