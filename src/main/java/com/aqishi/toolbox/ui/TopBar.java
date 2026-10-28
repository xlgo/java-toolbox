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
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * 应用顶栏：品牌、全局搜索、五档外观、强调色、语言与实时时钟。
 *
 * <p>效果页把搜索放在窗口最上方而不是侧栏里，因为它同时承担工具跳转与筛选；
 * 顶栏右侧直接暴露主题圆点，常用外观切换无需再打开菜单。项目仍保留 50 多套
 * 历史主题与中英切换，它们收进齿轮菜单，不占用主操作区。</p>
 *
 * <p>搜索带 140ms 去抖，中文输入法组字期间不触发，避免拼音被当成关键词筛掉全部工具。</p>
 */
public final class TopBar extends JPanel {

    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int SEARCH_DEBOUNCE_MS = 140;

    private final JButton sidebarButton = new JButton();
    private final JLabel appName = new JLabel();
    private final JLabel appTag = new JLabel();
    private final SearchBox searchBox = new SearchBox();
    private final JTextField searchField = searchBox.field();
    private final JLabel themeLabel = new JLabel();
    private final JPanel themeDots = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JPanel accentDots = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
    private final JLabel clock = new JLabel("--:--:--");
    private final JButton appearanceButton = new JButton();
    private JComponent leadingPanel;
    private JComponent trailingPanel;
    private SearchArea searchArea;
    private final Timer searchDebounce;
    private final Timer clockTimer;

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
        setBorder(new javax.swing.border.EmptyBorder(8, 14, 8, 14));

        leadingPanel = buildLeading();
        searchArea = new SearchArea(searchBox);
        trailingPanel = buildTrailing();
        add(leadingPanel);
        add(searchArea);
        add(trailingPanel);

        searchDebounce = new Timer(SEARCH_DEBOUNCE_MS, event -> queryListener.accept(searchField.getText()));
        searchDebounce.setRepeats(false);

        clockTimer = new Timer(1000, event -> updateClock());
        clockTimer.setInitialDelay(0);
        clockTimer.start();

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                adaptToWidth();
            }
        });

        refreshLabels();
        refreshThemeIndicators();
        updateClock();
    }

    // ---------------------------------------------------------------- 组件

    private JComponent buildLeading() {
        JPanel leading = new JPanel();
        leading.setOpaque(false);
        leading.setLayout(new BoxLayout(leading, BoxLayout.X_AXIS));

        sidebarButton.setName("workbench.sidebarToggle");
        sidebarButton.setText(null);
        sidebarButton.setIcon(WorkbenchIcons.sidebar(false));
        sidebarButton.setFocusPainted(true);
        sidebarButton.putClientProperty("JButton.buttonType", "toolBarButton");
        sidebarButton.putClientProperty("JComponent.minimumWidth", 0);
        sidebarButton.setMargin(new Insets(2, 5, 2, 5));
        sidebarButton.setPreferredSize(new Dimension(30, Tokens.CONTROL_HEIGHT));
        sidebarButton.setMaximumSize(new Dimension(30, Tokens.CONTROL_HEIGHT));
        sidebarButton.addActionListener(event -> sidebarListener.run());

        JLabel brandIcon = new JLabel(WorkbenchIcons.brand(20));
        brandIcon.setPreferredSize(new Dimension(20, 20));
        brandIcon.setVerticalAlignment(SwingConstants.CENTER);

        appName.setFont(Tokens.fontBodyStrong());
        appName.setForeground(Tokens.foreground());
        appTag.setFont(Tokens.fontCaption().deriveFont(Font.BOLD, 10.5f));
        appTag.setForeground(Tokens.accent());
        appTag.setBorder(BorderFactory.createEmptyBorder(1, 0, 0, 0));

        JPanel brandText = new JPanel();
        brandText.setOpaque(false);
        brandText.setLayout(new BoxLayout(brandText, BoxLayout.Y_AXIS));
        appName.setAlignmentX(Component.LEFT_ALIGNMENT);
        appTag.setAlignmentX(Component.LEFT_ALIGNMENT);
        brandText.add(appName);
        brandText.add(appTag);

        leading.add(sidebarButton);
        leading.add(Box.createHorizontalStrut(6));
        leading.add(brandIcon);
        leading.add(Box.createHorizontalStrut(8));
        leading.add(brandText);
        leading.add(Box.createHorizontalStrut(8));
        return leading;
    }

    private JComponent buildTrailing() {
        JPanel trailing = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        trailing.setOpaque(false);

        themeLabel.setFont(Tokens.fontCaption().deriveFont(Font.BOLD, 11f));
        themeLabel.setForeground(Tokens.mutedForeground());
        themeDots.setOpaque(false);
        accentDots.setOpaque(false);
        // 主题文字与强调色点占宽最多；窗口首次布局前先收起，待拿到真实宽度后再按阈值显示，
        // 否则最小窗口首帧会把搜索框压到不可用。
        themeLabel.setVisible(false);
        accentDots.setVisible(false);

        for (Skin skin : new Skin[]{Skin.LIGHT, Skin.DARK, Skin.MIDNIGHT, Skin.FOREST, Skin.PAPER}) {
            ThemeDot dot = new ThemeDot(skin);
            dot.setName("top.theme." + skin.name().toLowerCase());
            dot.addActionListener(event -> skinListener.accept(skin));
            themeDots.add(dot);
        }
        String[] accentKeys = {"teal", "blue", "red", "amber", "violet", "pink", "slate"};
        for (int i = 0; i < ThemeManager.ACCENTS.length; i++) {
            int rgb = ThemeManager.ACCENTS[i];
            AccentDot dot = new AccentDot(rgb, accentKeys[i]);
            dot.setName("top.accent." + accentKeys[i]);
            dot.addActionListener(event -> accentListener.accept(rgb));
            accentDots.add(dot);
        }

        clock.setName("workbench.clock");
        clock.setFont(Tokens.fontMono().deriveFont(12f));
        clock.setForeground(Tokens.secondaryForeground());
        clock.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));

        appearanceButton.setName("workbench.appearance");
        appearanceButton.setIcon(WorkbenchIcons.gear(16));
        appearanceButton.setText(null);
        appearanceButton.setFocusPainted(true);
        appearanceButton.putClientProperty("JButton.buttonType", "toolBarButton");
        appearanceButton.putClientProperty("JComponent.minimumWidth", 0);
        appearanceButton.setMargin(new Insets(2, 5, 2, 5));
        appearanceButton.setPreferredSize(new Dimension(30, Tokens.CONTROL_HEIGHT));
        appearanceButton.addActionListener(event -> showAppearanceMenu());

        trailing.add(themeLabel);
        trailing.add(themeDots);
        trailing.add(accentDots);
        trailing.add(clock);
        trailing.add(appearanceButton);
        return trailing;
    }

    private void adaptToWidth() {
        updateResponsiveVisibility(getWidth());
        revalidate();
        repaint();
    }

    /**
     * 在布局前按实际宽度收起非必要控件。
     *
     * <p>仅靠 componentResized 会晚一帧：首帧仍按大窗口的宽度预留主题色点，
     * 最小窗口就会把搜索框压到一百来像素。布局阶段先做一次判定可以消除这个空窗。</p>
     */
    private void updateResponsiveVisibility(int width) {
        boolean showLabel = width >= 1060;
        boolean showAccents = width >= 1280;
        boolean showClock = width >= 820;
        if (themeLabel.isVisible() != showLabel) themeLabel.setVisible(showLabel);
        if (accentDots.isVisible() != showAccents) accentDots.setVisible(showAccents);
        if (clock.isVisible() != showClock) clock.setVisible(showClock);
    }

    @Override
    public Dimension getPreferredSize() {
        // null 布局不会从子组件推导高度；顶栏高度必须显式回报，
        // 否则放进 BorderLayout.NORTH 时会被压成 0。
        return new Dimension(640, 48);
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
        int searchWidth = Math.min(520, Math.max(120, available));
        int searchX = insetLeft + (getWidth() - insetLeft - insetRight - searchWidth) / 2;
        int searchHeight = Math.min(32, contentHeight);
        searchArea.setBounds(searchX, insetTop + (contentHeight - searchHeight) / 2,
                searchWidth, searchHeight);
    }

    // ---------------------------------------------------------------- 搜索

    private void scheduleSearch() {
        if (!composing) {
            searchDebounce.restart();
        }
    }

    /**
     * 搜索区：把搜索框限制在 520px 并居中。
     *
     * <p>BorderLayout 的 CENTER 会无条件拉满剩余宽度，窗口变大后搜索框会横跨
     * 大半个顶栏，和效果页的紧凑居中不符。这里用单子组件布局显式限宽。</p>
     */
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
            int width = Math.min(520, getWidth());
            int x = Math.max(0, (getWidth() - width) / 2);
            box.setBounds(x, 0, width, getHeight());
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(520, 32);
        }
    }

    /** 圆角搜索框：内部是无边框文本框，键盘提示与放大镜都由这层负责绘制。 */
    private final class SearchBox extends JPanel {

        private final JTextField field = new JTextField();
        private final JLabel hint = new JLabel("Ctrl K");
        private boolean focus;

        SearchBox() {
            super(new BorderLayout(6, 0));
            setOpaque(false);
            setBorder(new javax.swing.border.EmptyBorder(0, 10, 0, 8));
            setPreferredSize(new Dimension(520, 32));
            setMinimumSize(new Dimension(190, 32));
            setMaximumSize(new Dimension(520, 32));

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

            hint.setFont(Tokens.fontMono().deriveFont(10f));
            hint.setForeground(Tokens.mutedForeground());
            hint.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Tokens.borderStrong()),
                    new javax.swing.border.EmptyBorder(0, 4, 0, 4)));
            hint.setHorizontalAlignment(SwingConstants.CENTER);

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
            hint.setFont(Tokens.fontMono().deriveFont(10f));
            hint.setForeground(Tokens.mutedForeground());
            hint.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Tokens.borderStrong()),
                    new javax.swing.border.EmptyBorder(0, 4, 0, 4)));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                RoundRectangle2D shape = new RoundRectangle2D.Float(
                        0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, 8, 8);
                g.setColor(Tokens.fieldBackground());
                g.fill(shape);
                if (focus) {
                    g.setColor(Tokens.accentSoft());
                    g.setStroke(new BasicStroke(4f));
                    g.draw(new RoundRectangle2D.Float(2f, 2f, getWidth() - 4f, getHeight() - 4f, 8, 8));
                }
                g.setColor(focus ? Tokens.accent() : Tokens.borderStrong());
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
        for (int color : ThemeManager.ACCENTS) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(
                    I18n.get("top.accent." + String.format("%06X", color).toLowerCase()));
            item.setFont(Tokens.fontBody());
            item.setIcon(accentIcon(color));
            item.setSelected(ThemeManager.accentRgb() == color);
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
        appName.setText(I18n.get("app.name.brand"));
        appTag.setText(I18n.get("app.name.tag"));
        themeLabel.setText(I18n.get("top.theme"));
        searchField.putClientProperty("JTextField.placeholderText", I18n.get("top.search.placeholder.long"));
        searchField.getAccessibleContext().setAccessibleName(I18n.get("nav.search.accessible"));
        clock.setToolTipText(I18n.get("top.clock.tip"));
        appearanceButton.setToolTipText(I18n.get("launcher.appearance.title"));
        appearanceButton.getAccessibleContext().setAccessibleName(I18n.get("launcher.appearance.title"));
        setSidebarExpanded(sidebarExpanded);
        refreshThemeIndicators();
        revalidate();
        repaint();
    }

    public void refreshThemeIndicators() {
        appName.setFont(Tokens.fontBodyStrong());
        appName.setForeground(Tokens.foreground());
        appTag.setFont(Tokens.fontCaption().deriveFont(Font.BOLD, 10.5f));
        appTag.setForeground(Tokens.accent());
        themeLabel.setFont(Tokens.fontCaption().deriveFont(Font.BOLD, 11f));
        themeLabel.setForeground(Tokens.mutedForeground());
        clock.setFont(Tokens.fontMono().deriveFont(12f));
        clock.setForeground(Tokens.secondaryForeground());
        searchBox.restyle();
        for (Component component : themeDots.getComponents()) {
            if (component instanceof ThemeDot dot) {
                dot.setSelected(dot.skin == ThemeManager.skin());
                dot.repaint();
            }
        }
        for (Component component : accentDots.getComponents()) {
            if (component instanceof AccentDot dot) {
                dot.setSelected(dot.rgb == ThemeManager.accentRgb());
                dot.repaint();
            }
        }
        repaint();
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (!visible) clockTimer.stop();
        else if (!clockTimer.isRunning()) clockTimer.start();
    }

    private void updateClock() {
        clock.setText(LocalTime.now().format(CLOCK_FORMAT));
    }

    // ---------------------------------------------------------------- 自绘控件

    private final class ThemeDot extends JToggleButton {

        private final Skin skin;
        private boolean hover;

        private ThemeDot(Skin skin) {
            this.skin = skin;
            setText(null);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(true);
            setPreferredSize(new Dimension(20, 20));
            setMinimumSize(new Dimension(20, 20));
            setMaximumSize(new Dimension(20, 20));
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

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int inset = isSelected() ? 4 : 3;
                int size = getWidth() - inset * 2;
                if (isSelected()) {
                    g.setColor(Tokens.accentSoft());
                    g.fill(new Ellipse2D.Float(0, 0, getWidth(), getHeight()));
                } else if (hover) {
                    g.setColor(Tokens.hoverSurface());
                    g.fill(new Ellipse2D.Float(1, 1, getWidth() - 2, getHeight() - 2));
                }
                Ellipse2D circle = new Ellipse2D.Float(inset, inset, size, size);
                g.setClip(circle);
                Color first = dotColor(skin, true);
                Color second = dotColor(skin, false);
                g.setColor(first);
                g.fillRect(0, 0, getWidth(), getHeight());
                Path2D half = new Path2D.Float();
                half.moveTo(0, 0);
                half.lineTo(getWidth(), getHeight());
                half.lineTo(0, getHeight());
                half.closePath();
                g.setColor(second);
                g.fill(half);
                g.setClip(null);
                g.setColor(isSelected() ? Tokens.accent() : Tokens.borderStrong());
                g.setStroke(new BasicStroke(isSelected() ? 2f : 1.4f));
                g.draw(circle);
            } finally {
                g.dispose();
            }
        }
    }

    private static Color dotColor(Skin skin, boolean upper) {
        return switch (skin) {
            case LIGHT -> upper ? new Color(0xFFFFFF) : new Color(0xDFE5DC);
            case DARK -> upper ? new Color(0x2A3038) : new Color(0x0E1013);
            case MIDNIGHT -> upper ? new Color(0x274B8F) : new Color(0x070D1A);
            case FOREST -> upper ? new Color(0x2F5D3A) : new Color(0x0A120D);
            case PAPER -> upper ? new Color(0xFFFAF0) : new Color(0xD9C491);
            case EYE_CARE -> upper ? new Color(0xFBF4E3) : new Color(0xD9C491);
        };
    }

    private final class AccentDot extends JToggleButton {

        private final int rgb;
        private final String key;

        private AccentDot(int rgb, String key) {
            this.rgb = rgb;
            this.key = key;
            setText(null);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(true);
            setPreferredSize(new Dimension(16, 16));
            setMinimumSize(new Dimension(16, 16));
            setMaximumSize(new Dimension(16, 16));
            setToolTipText(I18n.get("top.accent." + key));
            getAccessibleContext().setAccessibleName(getToolTipText());
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (isSelected()) {
                    g.setColor(Tokens.cardBackground());
                    g.fill(new Ellipse2D.Float(0, 0, getWidth(), getHeight()));
                    g.setColor(new Color(rgb));
                    g.setStroke(new BasicStroke(1.5f));
                    g.draw(new Ellipse2D.Float(0.8f, 0.8f, getWidth() - 1.6f, getHeight() - 1.6f));
                    g.fill(new Ellipse2D.Float(4, 4, getWidth() - 8, getHeight() - 8));
                } else {
                    g.setColor(new Color(rgb));
                    g.fill(new Ellipse2D.Float(1, 1, getWidth() - 2, getHeight() - 2));
                }
            } finally {
                g.dispose();
            }
        }
    }
}
