package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.ui.kit.Skin;
import com.aqishi.toolbox.ui.kit.Tokens;
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
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.function.Consumer;

/**
 * 顶栏：返回、搜索、收藏、主题切换、语言与外观设置。
 *
 * <p>效果图把搜索放在应用主区顶端，因为搜索是启动器最主要的入口。这里沿用这个层级：
 * 搜索框常驻在最左，其后是收藏筛选；右侧是一次性外观操作（深浅切换、皮肤菜单、语言）。</p>
 *
 * <p>搜索带 140ms 去抖，避免每敲一个字就重建一次磁贴网格；中文输入法组字期间不触发，
 * 否则拼音字母会先被当成关键词过滤一遍，出现「越打越空」的闪烁。</p>
 */
public final class TopBar extends JPanel {

    private final JButton expandNavButton = new JButton();
    private final JButton backButton = new JButton();
    private final JLabel contextLabel = new EllipsisLabel();
    private final JTextField searchField = new JTextField();
    private final JToggleButton favoriteButton = new JToggleButton();
    private final JButton themeButton = new JButton();
    private final JButton settingsButton = new JButton();
    private final JComboBox<String> languageBox = new JComboBox<>();
    private final Timer searchDebounce;

    private Consumer<String> queryListener = ignored -> { };
    private Consumer<Boolean> favoriteListener = ignored -> { };
    private Runnable backListener = () -> { };
    private Runnable themeToggleListener = () -> { };
    private Runnable expandNavListener = () -> { };
    private Runnable searchDownListener = () -> { };
    private Consumer<Skin> skinListener = ignored -> { };
    private Consumer<String> themeListener = ignored -> { };
    private Consumer<String> localeListener = ignored -> { };

    private boolean composing;

    public TopBar() {
        super(new BorderLayout(Tokens.SPACE_MD, 0));
        setOpaque(false);
        setBorder(new javax.swing.border.EmptyBorder(
                Tokens.SPACE_SM, Tokens.SPACE_LG, Tokens.SPACE_SM, Tokens.SPACE_LG));

        add(buildLeading(), BorderLayout.WEST);
        add(buildTrailing(), BorderLayout.EAST);

        searchDebounce = new Timer(140, event -> queryListener.accept(searchField.getText()));
        searchDebounce.setRepeats(false);

        refreshLabels();
        refreshThemeIndicators();
    }

    // ---------------------------------------------------------------- 组件

    private JComponent buildLeading() {
        JPanel leading = new JPanel();
        leading.setOpaque(false);
        leading.setLayout(new BoxLayout(leading, BoxLayout.X_AXIS));

        expandNavButton.setName("workbench.expandNavigation");
        expandNavButton.setText(null);
        expandNavButton.setIcon(WorkbenchIcons.sidebar(true));
        expandNavButton.setFocusPainted(true);
        expandNavButton.putClientProperty("JButton.buttonType", "toolBarButton");
        expandNavButton.putClientProperty("JComponent.minimumWidth", 0);
        expandNavButton.setMargin(new Insets(2, 6, 2, 6));
        expandNavButton.setPreferredSize(new Dimension(32, Tokens.CONTROL_HEIGHT));
        expandNavButton.setMaximumSize(new Dimension(32, Tokens.CONTROL_HEIGHT));
        expandNavButton.setVisible(false);
        expandNavButton.addActionListener(event -> expandNavListener.run());

        backButton.setName("workbench.back");
        backButton.setIcon(WorkbenchIcons.back(16));
        backButton.setText(null);
        backButton.setFocusPainted(true);
        backButton.putClientProperty("JButton.buttonType", "toolBarButton");
        backButton.putClientProperty("JComponent.minimumWidth", 0);
        backButton.setMargin(new Insets(2, 6, 2, 6));
        backButton.setPreferredSize(new Dimension(32, Tokens.CONTROL_HEIGHT));
        backButton.setMaximumSize(new Dimension(32, Tokens.CONTROL_HEIGHT));
        backButton.setVisible(false);
        backButton.addActionListener(event -> backListener.run());

        contextLabel.setName("workbench.title");
        contextLabel.setFont(Tokens.fontBodyStrong());
        contextLabel.setForeground(Tokens.foreground());
        contextLabel.setVisible(false);
        // 长工具名必须在这里收口：否则 BoxLayout 会把它撑开，把搜索框顶出可视区
        contextLabel.setMaximumSize(new Dimension(260, Tokens.CONTROL_HEIGHT));
        contextLabel.setMinimumSize(new Dimension(0, Tokens.CONTROL_HEIGHT));

        searchField.setName("workbench.search");
        searchField.putClientProperty("JTextField.showClearButton", true);
        searchField.putClientProperty("JTextField.leadingIcon", WorkbenchIcons.search());
        searchField.putClientProperty("JTextField.placeholderText", I18n.get("top.search.placeholder"));
        searchField.setFont(Tokens.fontBody());
        searchField.setPreferredSize(new Dimension(300, Tokens.CONTROL_HEIGHT + 2));
        searchField.setMaximumSize(new Dimension(300, Tokens.CONTROL_HEIGHT + 2));
        searchField.getAccessibleContext().setAccessibleName(I18n.get("nav.search.accessible"));
        searchField.addInputMethodListener(new java.awt.event.InputMethodListener() {
            @Override
            public void inputMethodTextChanged(java.awt.event.InputMethodEvent event) {
                int length = event.getText() == null
                        ? 0
                        : event.getText().getEndIndex() - event.getText().getBeginIndex();
                composing = length > event.getCommittedCharacterCount();
                if (!composing) scheduleSearch();
            }

            @Override
            public void caretPositionChanged(java.awt.event.InputMethodEvent event) {
            }
        });
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { scheduleSearch(); }
            @Override public void removeUpdate(DocumentEvent event) { scheduleSearch(); }
            @Override public void changedUpdate(DocumentEvent event) { scheduleSearch(); }
        });
        // 「下」直达第一个搜索结果，保留搜索的纯键盘路径
        searchField.getInputMap().put(
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DOWN, 0),
                "launcher.firstResult");
        searchField.getActionMap().put("launcher.firstResult", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                searchDownListener.run();
            }
        });

        favoriteButton.setName("workbench.favorites");
        favoriteButton.setIcon(WorkbenchIcons.star(false, 16));
        favoriteButton.setFont(Tokens.fontBody());
        favoriteButton.setFocusPainted(true);
        favoriteButton.putClientProperty("JButton.buttonType", "toolBarButton");
        favoriteButton.setMargin(new Insets(2, 10, 2, 10));
        favoriteButton.setPreferredSize(new Dimension(92, Tokens.CONTROL_HEIGHT + 2));
        favoriteButton.setMaximumSize(new Dimension(92, Tokens.CONTROL_HEIGHT + 2));
        favoriteButton.addActionListener(event -> {
            favoriteButton.setIcon(WorkbenchIcons.star(favoriteButton.isSelected(), 16));
            favoriteListener.accept(favoriteButton.isSelected());
        });

        leading.add(expandNavButton);
        leading.add(backButton);
        leading.add(Box.createHorizontalStrut(Tokens.SPACE_SM));
        leading.add(contextLabel);
        leading.add(Box.createHorizontalStrut(Tokens.SPACE_MD));
        leading.add(searchField);
        leading.add(Box.createHorizontalStrut(Tokens.SPACE_SM));
        leading.add(favoriteButton);
        return leading;
    }

    private JComponent buildTrailing() {
        JPanel trailing = new JPanel(new FlowLayout(FlowLayout.RIGHT, Tokens.SPACE_SM, 0));
        trailing.setOpaque(false);

        themeButton.setName("workbench.themeToggle");
        themeButton.setFocusPainted(true);
        themeButton.putClientProperty("JButton.buttonType", "toolBarButton");
        themeButton.setMargin(new Insets(2, 6, 2, 6));
        themeButton.setPreferredSize(new Dimension(34, Tokens.CONTROL_HEIGHT));
        themeButton.addActionListener(event -> themeToggleListener.run());

        settingsButton.setName("workbench.appearance");
        settingsButton.setFocusPainted(true);
        settingsButton.putClientProperty("JButton.buttonType", "toolBarButton");
        settingsButton.setMargin(new Insets(2, 6, 2, 6));
        settingsButton.setPreferredSize(new Dimension(34, Tokens.CONTROL_HEIGHT));
        settingsButton.addActionListener(event -> showAppearanceMenu());

        languageBox.setName("workbench.language");
        languageBox.setFont(Tokens.fontBody());
        languageBox.setPreferredSize(new Dimension(108, Tokens.CONTROL_HEIGHT));
        languageBox.setMaximumRowCount(4);
        languageBox.addItem(I18n.get("language.zh"));
        languageBox.addItem("English");
        languageBox.addActionListener(event -> {
            String selected = (String) languageBox.getSelectedItem();
            localeListener.accept("English".equals(selected) ? "en_US" : "zh_CN");
        });

        trailing.add(themeButton);
        trailing.add(settingsButton);
        trailing.add(languageBox);
        return trailing;
    }

    // ---------------------------------------------------------------- 外观菜单

    /**
     * 「主题与皮肤」菜单：三档皮肤直接切换，其余 50 多套主题收进子菜单。
     *
     * <p>效果图的主入口是三选一，但项目原有 54 套主题不能因此丢掉，
     * 所以做成「三档快捷 + 全部主题」两层。</p>
     */
    public void showAppearanceMenu() {
        JPopupMenu menu = new JPopupMenu();
        JLabel heading = new JLabel(I18n.get("launcher.appearance.title"));
        heading.setFont(Tokens.fontCaption());
        heading.setForeground(Tokens.mutedForeground());
        heading.setBorder(new javax.swing.border.EmptyBorder(6, 10, 4, 10));
        menu.add(heading);

        ButtonGroup group = new ButtonGroup();
        for (Skin skin : Skin.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(
                    skin.label() + " (" + skin.labelEn() + ")");
            item.setFont(Tokens.fontBody());
            item.setIcon(appearanceIcon(skin));
            item.setSelected(ThemeManager.skin() == skin);
            item.addActionListener(event -> {
                skinListener.accept(skin);
                refreshThemeIndicators();
            });
            group.add(item);
            menu.add(item);
        }

        menu.addSeparator();
        JMenuItem allThemes = new JMenuItem(I18n.get("launcher.appearance.more"));
        allThemes.setFont(Tokens.fontBody());
        allThemes.addActionListener(event -> showAllThemes());
        menu.add(allThemes);

        menu.show(settingsButton, 0, settingsButton.getHeight() + 4);
    }

    private void showAllThemes() {
        JPopupMenu menu = new JPopupMenu();
        menu.setLayout(new BorderLayout());
        JComboBox<String> box = new JComboBox<>(ThemeManager.names());
        box.setSelectedItem(ThemeManager.current().name);
        box.setFont(Tokens.fontBody());
        box.setPreferredSize(new Dimension(280, Tokens.CONTROL_HEIGHT));
        box.addActionListener(event -> {
            themeListener.accept((String) box.getSelectedItem());
            menu.setVisible(false);
        });
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(new javax.swing.border.EmptyBorder(6, 6, 6, 6));
        wrapper.add(box, BorderLayout.CENTER);
        menu.add(wrapper, BorderLayout.CENTER);
        menu.show(settingsButton, 0, settingsButton.getHeight() + 4);
        box.requestFocusInWindow();
    }

    private static javax.swing.Icon appearanceIcon(Skin skin) {
        return switch (skin) {
            case DARK -> WorkbenchIcons.moon(14);
            case LIGHT -> WorkbenchIcons.sun(14);
            case EYE_CARE -> WorkbenchIcons.palette(14);
        };
    }

    // ---------------------------------------------------------------- 外部接口

    public void setQueryListener(Consumer<String> listener) {
        queryListener = listener == null ? ignored -> { } : listener;
    }

    public void setFavoriteListener(Consumer<Boolean> listener) {
        favoriteListener = listener == null ? ignored -> { } : listener;
    }

    public void setBackListener(Runnable listener) {
        backListener = listener == null ? () -> { } : listener;
    }

    public void setThemeToggleListener(Runnable listener) {
        themeToggleListener = listener == null ? () -> { } : listener;
    }

    /** 侧栏折叠后，展开入口在顶栏最左侧 */
    public void setExpandNavListener(Runnable listener) {
        expandNavListener = listener == null ? () -> { } : listener;
    }

    public void setSearchDownListener(Runnable listener) {
        searchDownListener = listener == null ? () -> { } : listener;
    }

    public void setNavigationExpanded(boolean expanded) {
        expandNavButton.setVisible(!expanded);
        revalidate();
        repaint();
    }

    public void setSkinListener(Consumer<Skin> listener) {
        skinListener = listener == null ? ignored -> { } : listener;
    }

    public void setThemeListener(Consumer<String> listener) {
        themeListener = listener == null ? ignored -> { } : listener;
    }

    public void setLocaleListener(Consumer<String> listener) {
        localeListener = listener == null ? ignored -> { } : listener;
    }

    public JTextField getSearchField() {
        return searchField;
    }

    public JComboBox<String> getLanguageBox() {
        return languageBox;
    }

    public void focusSearch() {
        searchField.requestFocusInWindow();
        searchField.selectAll();
    }

    public void setQuery(String value) {
        if (!searchField.getText().equals(value)) {
            searchField.setText(value);
        }
    }

    /** 收藏按钮状态与启动器同步：进入收藏视图时按钮应处于按下态 */
    public void setFavoriteSelected(boolean selected) {
        favoriteButton.setSelected(selected);
        favoriteButton.setIcon(WorkbenchIcons.star(selected, 16));
    }

    public boolean isFavoriteSelected() {
        return favoriteButton.isSelected();
    }

    /** 工具页显示返回按钮与当前工具名；启动器视图隐藏它们 */
    public void setContext(String title, boolean showBack) {
        contextLabel.setText(title == null ? "" : title);
        contextLabel.setToolTipText(title);
        contextLabel.setVisible(showBack && title != null && !title.isEmpty());
        backButton.setVisible(showBack);
        revalidate();
        repaint();
    }

    /** 主题切换后刷新太阳/月亮图标与提示 */
    public void refreshThemeIndicators() {
        boolean dark = ThemeManager.skin().isDark();
        themeButton.setIcon(dark ? WorkbenchIcons.sun(16) : WorkbenchIcons.moon(16));
        themeButton.setToolTipText(dark
                ? I18n.get("launcher.theme.toLight")
                : I18n.get("launcher.theme.toDark"));
        themeButton.getAccessibleContext().setAccessibleName(themeButton.getToolTipText());
        settingsButton.setIcon(WorkbenchIcons.gear(16));
        settingsButton.setToolTipText(I18n.get("launcher.appearance.title"));
        settingsButton.getAccessibleContext().setAccessibleName(I18n.get("launcher.appearance.title"));
        contextLabel.setFont(Tokens.fontBodyStrong());
        contextLabel.setForeground(Tokens.foreground());
        searchField.setFont(Tokens.fontBody());
        favoriteButton.setFont(Tokens.fontBody());
        repaint();
    }

    /** 语言切换后刷新文案，保留当前搜索词与收藏状态 */
    public void refreshLabels() {
        searchField.putClientProperty("JTextField.placeholderText", I18n.get("top.search.placeholder"));
        searchField.getAccessibleContext().setAccessibleName(I18n.get("nav.search.accessible"));
        backButton.setToolTipText(I18n.get("launcher.back"));
        backButton.getAccessibleContext().setAccessibleName(I18n.get("launcher.back"));
        expandNavButton.setToolTipText(I18n.get("nav.expand"));
        expandNavButton.getAccessibleContext().setAccessibleName(I18n.get("nav.expand"));
        favoriteButton.setText(I18n.get("launcher.favorites.button"));
        favoriteButton.setToolTipText(I18n.get("launcher.favorites.tip"));
        favoriteButton.getAccessibleContext().setAccessibleName(favoriteButton.getText());
        refreshThemeIndicators();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Tokens.chromeBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
        } finally {
            g.dispose();
        }
        super.paintComponent(graphics);
    }

    private void scheduleSearch() {
        if (!composing) {
            searchDebounce.restart();
        }
    }

    /**
     * 超宽时用省略号收尾的标签。
     *
     * <p>{@link JLabel} 本身不做截断，宽度被压窄时会把文字硬裁掉半个字；顶栏标题
     * 又必须限宽，所以这里自己按像素宽度重算一遍。</p>
     */
    private static final class EllipsisLabel extends JLabel {

        @Override
        protected void paintComponent(Graphics graphics) {
            String text = getText();
            if (text == null || text.isEmpty() || getWidth() <= 0) {
                super.paintComponent(graphics);
                return;
            }
            FontMetrics metrics = graphics.getFontMetrics(getFont());
            int available = getWidth() - getInsets().left - getInsets().right;
            if (metrics.stringWidth(text) <= available) {
                super.paintComponent(graphics);
                return;
            }
            String ellipsis = "…";
            int budget = available - metrics.stringWidth(ellipsis);
            int end = text.length();
            while (end > 0 && metrics.stringWidth(text.substring(0, end)) > budget) {
                end--;
            }
            String shown = text.substring(0, Math.max(0, end)).trim() + ellipsis;

            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setFont(getFont());
                g.setColor(getForeground());
                int baseline = getInsets().top
                        + (getHeight() - getInsets().top - getInsets().bottom
                        + metrics.getAscent() - metrics.getDescent()) / 2;
                g.drawString(shown, getInsets().left, baseline);
            } finally {
                g.dispose();
            }
        }
    }
}
