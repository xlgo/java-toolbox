package com.aqishi.toolbox.feature.codec.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.catalog.ToolDescriptor;
import com.aqishi.toolbox.feature.codec.domain.EscapeFormat;
import com.aqishi.toolbox.feature.codec.domain.NamingConverter;
import com.aqishi.toolbox.feature.codec.domain.NamingStyle;
import com.aqishi.toolbox.feature.codec.domain.TextEscaper;
import com.aqishi.toolbox.feature.codec.domain.UnescapeException;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.ActionBar;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 文本转义/反转义与标识符命名风格转换面板。
 *
 * <p>两个页签：「转义」覆盖 Java/JSON/JS/HTML/XML/CSV/SQL/正则/Properties 等字面量格式；
 * 「命名风格」把一行一个的标识符在 camelCase、snake_case 等风格之间互转。</p>
 */
public class TextEscapePanel extends ToolPanel implements ManagedResourceOwner {

    /** 实时模式的防抖间隔：足够短以显得「实时」，又能把连续键入合并成一次转换。 */
    private static final int LIVE_DELAY_MS = 250;

    /** 一次最多高亮的问题数，避免对超大输入逐个添加高亮拖慢编辑器。 */
    private static final int MAX_HIGHLIGHTS = 500;

    private static final EscapeFormat[] FORMATS = EscapeFormat.values();
    private static final char[] CSV_DELIMITERS = {',', ';', '\t', '|'};
    private static final NamingStyle[] STYLES = NamingStyle.values();

    private enum Direction { ESCAPE, UNESCAPE }

    // ---------- 转义页签 ----------
    private JComboBox<String> formatCombo;
    private JCheckBox nonAsciiCheck;
    private JCheckBox scriptSafeCheck;
    private JCheckBox mysqlCheck;
    private JCheckBox sqlQuotedCheck;
    private JCheckBox propertiesKeyCheck;
    private JCheckBox lenientCheck;
    private JCheckBox liveCheck;
    private JLabel delimiterLabel;
    private JComboBox<String> delimiterCombo;
    private JLabel hintLabel;
    private JTextArea inputArea;
    private JTextArea outputArea;
    private JLabel statusLabel;
    private Timer liveTimer;
    private Direction lastDirection = Direction.ESCAPE;
    private final List<Object> highlightTags = new ArrayList<>();

    /**
     * 「转义非 ASCII」按格式分别记忆：Properties 默认要开（传统 .properties 按 ISO-8859-1 读取），
     * 其余格式默认关；切换格式时不能把用户在另一种格式里的选择带过来。
     */
    private final Map<EscapeFormat, Boolean> nonAsciiByFormat = new EnumMap<>(EscapeFormat.class);
    /** 切换格式时同步复选框状态，期间不应触发实时转换或写回记忆。 */
    private boolean syncingOptions;

    // ---------- 命名风格页签 ----------
    private JTextArea namingInput;
    private DefaultTableModel styleModel;
    private JTable styleTable;
    private JCheckBox acronymCheck;
    private JComboBox<String> batchStyleCombo;
    private JTextArea batchOutput;
    private JLabel namingStatus;
    private Timer namingTimer;

    public TextEscapePanel() {
        this(ToolCatalog.TEXT_ESCAPE);
    }

    public TextEscapePanel(ToolDescriptor descriptor) {
        super(Objects.requireNonNull(descriptor, "descriptor"));
        nonAsciiByFormat.put(EscapeFormat.PROPERTIES, Boolean.TRUE);
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(I18n.get("tool.textescape.tab.escape"), buildEscapeTab());
        tabs.addTab(I18n.get("tool.textescape.tab.naming"), buildNamingTab());
        root.add(tabs, BorderLayout.CENTER);
        applyFormatOptions();
        refreshNaming();
        return root;
    }

    // ==========================================
    // 转义页签
    // ==========================================
    private JComponent buildEscapeTab() {
        JPanel tab = new JPanel(new BorderLayout(0, Tokens.SPACE_MD));
        tab.setOpaque(false);
        tab.setBorder(BorderFactory.createEmptyBorder(Tokens.SPACE_MD, 0, 0, 0));
        tab.add(buildSettingsCard(), BorderLayout.NORTH);
        tab.add(Layouts.splitHorizontal(buildInputCard(), buildOutputCard(), 0.5, 0.5),
                BorderLayout.CENTER);

        liveTimer = new Timer(LIVE_DELAY_MS, event -> runLive());
        liveTimer.setRepeats(false);
        inputArea.getDocument().addDocumentListener(onChange(() -> {
            if (liveCheck.isSelected()) {
                liveTimer.restart();
            }
        }));
        return tab;
    }

    private Card buildSettingsCard() {
        String[] formatLabels = new String[FORMATS.length];
        for (int i = 0; i < FORMATS.length; i++) {
            formatLabels[i] = I18n.get("tool.textescape.format." + formatKey(FORMATS[i]));
        }
        formatCombo = Fields.combo(formatLabels, 220);

        nonAsciiCheck = Fields.check(I18n.get("tool.textescape.option.nonAscii"), false);
        scriptSafeCheck = Fields.check(I18n.get("tool.textescape.option.scriptSafe"), false);
        scriptSafeCheck.setToolTipText(I18n.get("tool.textescape.option.scriptSafe.tip"));
        mysqlCheck = Fields.check(I18n.get("tool.textescape.option.mysql"), false);
        sqlQuotedCheck = Fields.check(I18n.get("tool.textescape.option.sqlQuoted"), false);
        propertiesKeyCheck = Fields.check(I18n.get("tool.textescape.option.propertiesKey"), false);
        propertiesKeyCheck.setToolTipText(I18n.get("tool.textescape.option.propertiesKey.tip"));
        lenientCheck = Fields.check(I18n.get("tool.textescape.option.lenient"), false);
        lenientCheck.setToolTipText(I18n.get("tool.textescape.option.lenient.tip"));
        liveCheck = Fields.check(I18n.get("tool.textescape.option.live"), false);

        delimiterLabel = new JLabel(I18n.get("tool.textescape.option.delimiter"));
        delimiterLabel.setFont(Tokens.fontBody());
        delimiterCombo = Fields.combo(new String[]{
                I18n.get("tool.textescape.delimiter.comma"),
                I18n.get("tool.textescape.delimiter.semicolon"),
                I18n.get("tool.textescape.delimiter.tab"),
                I18n.get("tool.textescape.delimiter.pipe")}, 120);

        JPanel options = Layouts.wrapRow(nonAsciiCheck, scriptSafeCheck, mysqlCheck, sqlQuotedCheck,
                propertiesKeyCheck, delimiterLabel, delimiterCombo, lenientCheck, liveCheck);

        hintLabel = Fields.caption("");

        FormGrid form = new FormGrid();
        form.rowCompact(I18n.get("tool.textescape.label.format"), formatCombo);
        form.row(I18n.get("tool.textescape.label.options"), options);
        form.row("", hintLabel);

        Card card = Card.titled(I18n.get("tool.textescape.card.settings"));
        card.setContent(form);

        formatCombo.addActionListener(event -> {
            applyFormatOptions();
            scheduleIfLive();
        });
        nonAsciiCheck.addActionListener(event -> {
            if (!syncingOptions) {
                nonAsciiByFormat.put(selectedFormat(), nonAsciiCheck.isSelected());
                scheduleIfLive();
            }
        });
        for (AbstractButton option : new AbstractButton[]{scriptSafeCheck, mysqlCheck, sqlQuotedCheck,
                propertiesKeyCheck, lenientCheck}) {
            option.addActionListener(event -> scheduleIfLive());
        }
        delimiterCombo.addActionListener(event -> scheduleIfLive());
        liveCheck.addActionListener(event -> {
            if (liveCheck.isSelected()) {
                runLive();
            } else {
                liveTimer.stop();
            }
        });
        return card;
    }

    private Card buildInputCard() {
        inputArea = Fields.area(12, 30);
        inputArea.putClientProperty("JTextField.placeholderText",
                I18n.get("tool.textescape.placeholder.input"));

        JButton escapeBtn = Buttons.primary(I18n.get("tool.textescape.btn.escape"));
        JButton unescapeBtn = Buttons.secondary(I18n.get("tool.textescape.btn.unescape"));
        JButton clearBtn = Buttons.snug(I18n.get("tool.textescape.btn.clear"));

        statusLabel = Fields.caption(I18n.get("tool.textescape.status.ready"));
        ActionBar statusBar = new ActionBar();
        statusBar.left(statusLabel);

        Card card = Card.flush(I18n.get("tool.textescape.card.input"));
        card.setContent(Fields.scroll(inputArea));
        card.setFooter(statusBar);
        card.addHeaderAction(clearBtn);
        card.addHeaderAction(unescapeBtn);
        card.addHeaderAction(escapeBtn);

        escapeBtn.addActionListener(event -> run(Direction.ESCAPE, true));
        unescapeBtn.addActionListener(event -> run(Direction.UNESCAPE, true));
        clearBtn.addActionListener(event -> {
            inputArea.setText("");
            outputArea.setText("");
            clearHighlights();
            // 清空本身会触发文档事件；实时模式下不需要再对空输入跑一遍
            liveTimer.stop();
            setStatus(I18n.get("tool.textescape.status.ready"), Tokens.mutedForeground());
        });
        return card;
    }

    private Card buildOutputCard() {
        outputArea = Fields.output(12, 30);

        JButton swapBtn = Buttons.snug(I18n.get("tool.textescape.btn.swap"));
        swapBtn.setToolTipText(I18n.get("tool.textescape.btn.swap.tip"));
        JButton copyBtn = Buttons.snug(I18n.get("tool.textescape.btn.copy"));

        Card card = Card.flush(I18n.get("tool.textescape.card.output"));
        card.setContent(Fields.scroll(outputArea));
        card.addHeaderAction(swapBtn);
        card.addHeaderAction(copyBtn);

        swapBtn.addActionListener(event -> swap());
        copyBtn.addActionListener(event -> {
            String text = outputArea.getText();
            if (text != null && !text.isEmpty()) {
                UIUtils.copyToClipboard(text);
                setStatus(I18n.get("tool.textescape.status.copied"), Tokens.success());
            }
        });
        return card;
    }

    /** 按所选格式显示相关选项，并恢复该格式记忆的「转义非 ASCII」状态。 */
    private void applyFormatOptions() {
        EscapeFormat format = selectedFormat();
        syncingOptions = true;
        try {
            nonAsciiCheck.setSelected(nonAsciiByFormat.getOrDefault(format, Boolean.FALSE));
        } finally {
            syncingOptions = false;
        }
        nonAsciiCheck.setVisible(format == EscapeFormat.JAVA || format == EscapeFormat.JSON
                || format == EscapeFormat.JAVASCRIPT || format == EscapeFormat.HTML
                || format == EscapeFormat.XML || format == EscapeFormat.PROPERTIES);
        scriptSafeCheck.setVisible(format == EscapeFormat.JSON || format == EscapeFormat.JAVASCRIPT);
        mysqlCheck.setVisible(format == EscapeFormat.SQL);
        sqlQuotedCheck.setVisible(format == EscapeFormat.SQL);
        propertiesKeyCheck.setVisible(format == EscapeFormat.PROPERTIES);
        delimiterLabel.setVisible(format == EscapeFormat.CSV);
        delimiterCombo.setVisible(format == EscapeFormat.CSV);
        hintLabel.setText(I18n.get("tool.textescape.hint." + formatKey(format)));
        JComponent optionsRow = (JComponent) nonAsciiCheck.getParent();
        if (optionsRow != null) {
            optionsRow.revalidate();
            optionsRow.repaint();
        }
    }

    private TextEscaper.Options currentOptions() {
        return new TextEscaper.Options()
                .escapeNonAscii(nonAsciiCheck.isSelected())
                .scriptSafe(scriptSafeCheck.isSelected())
                .sqlMysql(mysqlCheck.isSelected())
                .sqlQuoted(sqlQuotedCheck.isSelected())
                .propertiesKey(propertiesKeyCheck.isSelected())
                .lenient(lenientCheck.isSelected())
                .csvDelimiter(CSV_DELIMITERS[Math.max(0, delimiterCombo.getSelectedIndex())]);
    }

    private void scheduleIfLive() {
        if (liveCheck != null && liveCheck.isSelected() && liveTimer != null) {
            liveTimer.restart();
        }
    }

    private void runLive() {
        run(lastDirection, false);
    }

    /**
     * 执行一次转换。
     *
     * @param explicit 由按钮触发时为 true：出错时会把焦点和选区移到出错位置；
     *                 实时模式下只做高亮，不能在用户打字时抢走光标
     */
    private void run(Direction direction, boolean explicit) {
        lastDirection = direction;
        EscapeFormat format = selectedFormat();
        String input = inputArea.getText();
        clearHighlights();
        try {
            TextEscaper.Result result = direction == Direction.ESCAPE
                    ? TextEscaper.escape(input, format, currentOptions())
                    : TextEscaper.unescape(input, format, currentOptions());
            outputArea.setText(result.text());
            outputArea.setCaretPosition(0);
            reportSuccess(direction, input, result);
        } catch (UnescapeException e) {
            outputArea.setText("");
            int[] position = lineAndColumn(input, e.getOffset());
            setStatus(I18n.get("tool.textescape.status.error", describe(e.getCode()),
                    String.valueOf(position[0]), String.valueOf(position[1]),
                    String.valueOf(e.getOffset())), Tokens.danger());
            int start = Math.min(e.getOffset(), input.length());
            int end = Math.min(e.getOffset() + e.getLength(), input.length());
            highlight(start, end, Tokens.danger());
            if (explicit) {
                inputArea.requestFocusInWindow();
                inputArea.select(start, end);
            }
        }
    }

    private void reportSuccess(Direction direction, String input, TextEscaper.Result result) {
        List<TextEscaper.Issue> issues = result.issues();
        if (issues.isEmpty()) {
            String key = direction == Direction.ESCAPE
                    ? "tool.textescape.status.escaped" : "tool.textescape.status.unescaped";
            setStatus(I18n.get(key, String.valueOf(input.length()), String.valueOf(result.text().length())),
                    Tokens.success());
            return;
        }
        TextEscaper.Issue first = issues.get(0);
        int[] position = lineAndColumn(input, first.offset());
        String key = direction == Direction.ESCAPE
                ? "tool.textescape.status.xmlReplaced" : "tool.textescape.status.lenientKept";
        setStatus(I18n.get(key, String.valueOf(issues.size()), String.valueOf(position[0]),
                String.valueOf(position[1]), describe(first.code())), Tokens.warning());
        for (int i = 0; i < issues.size() && i < MAX_HIGHLIGHTS; i++) {
            TextEscaper.Issue issue = issues.get(i);
            highlight(issue.offset(), Math.min(issue.offset() + issue.length(), input.length()),
                    Tokens.warning());
        }
    }

    /** 交换：输出挪到输入，方向随之反转——刚转义完的结果下一步通常是要验证能否转回去。 */
    private void swap() {
        String output = outputArea.getText();
        lastDirection = lastDirection == Direction.ESCAPE ? Direction.UNESCAPE : Direction.ESCAPE;
        outputArea.setText("");
        inputArea.setText(output);
        inputArea.setCaretPosition(0);
        if (!liveCheck.isSelected()) {
            clearHighlights();
            setStatus(I18n.get("tool.textescape.status.ready"), Tokens.mutedForeground());
        }
    }

    private void highlight(int start, int end, Color color) {
        if (end <= start) {
            // 出错位置在文本末尾（例如结尾的孤立反斜杠已被截断）时退而高亮最后一个字符
            start = Math.max(0, end - 1);
        }
        if (end <= start) {
            return;
        }
        Color fill = Tokens.blend(inputArea.getBackground(), color, 0.35f);
        try {
            highlightTags.add(inputArea.getHighlighter().addHighlight(start, end,
                    new DefaultHighlighter.DefaultHighlightPainter(fill)));
        } catch (BadLocationException ignored) {
            // 文本在防抖期间被改短时位置会越界，此时高亮已无意义
        }
    }

    /** 只移除本面板加的高亮；选区本身也是一条高亮，不能一并清掉。 */
    private void clearHighlights() {
        Highlighter highlighter = inputArea.getHighlighter();
        for (Object tag : highlightTags) {
            highlighter.removeHighlight(tag);
        }
        highlightTags.clear();
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
        statusLabel.setToolTipText(text);
    }

    private EscapeFormat selectedFormat() {
        int index = formatCombo.getSelectedIndex();
        return FORMATS[index < 0 ? 0 : index];
    }

    private static String describe(UnescapeException.Code code) {
        // 错误码 BAD_ESCAPE 对应键 tool.textescape.error.badEscape，顺手用上本工具自己的命名转换
        return I18n.get("tool.textescape.error." + NamingConverter.convert(code.name(), NamingStyle.CAMEL, false));
    }

    private static String formatKey(EscapeFormat format) {
        return format.name().toLowerCase(Locale.ROOT);
    }

    /** 返回 1 起算的行号和列号。 */
    static int[] lineAndColumn(String text, int offset) {
        int line = 1;
        int lineStart = 0;
        int limit = Math.min(offset, text.length());
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new int[]{line, offset - lineStart + 1};
    }

    // ==========================================
    // 命名风格页签
    // ==========================================
    private JComponent buildNamingTab() {
        JPanel tab = new JPanel(new BorderLayout(0, Tokens.SPACE_MD));
        tab.setOpaque(false);
        tab.setBorder(BorderFactory.createEmptyBorder(Tokens.SPACE_MD, 0, 0, 0));

        namingInput = Fields.area(8, 24);
        namingInput.setLineWrap(false);
        namingInput.setText("getHTTPResponseCode\nuser_id\nIPv6Address\nversion2-name");
        acronymCheck = Fields.check(I18n.get("tool.textescape.naming.acronyms"), false);
        acronymCheck.setToolTipText(I18n.get("tool.textescape.naming.acronyms.tip"));

        Card inputCard = Card.flush(I18n.get("tool.textescape.naming.card.input"));
        inputCard.setContent(Fields.scroll(namingInput));
        inputCard.addHeaderAction(acronymCheck);

        styleModel = new DefaultTableModel(new Object[]{
                I18n.get("tool.textescape.naming.col.style"),
                I18n.get("tool.textescape.naming.col.value")}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        styleTable = new JTable(styleModel);
        styleTable.setRowHeight(Tokens.TABLE_ROW_HEIGHT);
        styleTable.setFillsViewportHeight(true);
        styleTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        styleTable.getColumnModel().getColumn(0).setPreferredWidth(170);
        styleTable.getColumnModel().getColumn(1).setPreferredWidth(260);
        styleTable.setToolTipText(I18n.get("tool.textescape.naming.table.tip"));
        styleTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    copySelectedStyle();
                }
            }
        });

        namingStatus = Fields.caption(I18n.get("tool.textescape.naming.status.firstLine"));
        JButton copyStyleBtn = Buttons.snug(I18n.get("tool.textescape.btn.copy"));
        copyStyleBtn.addActionListener(event -> copySelectedStyle());
        Card tableCard = Card.flush(I18n.get("tool.textescape.naming.card.styles"));
        tableCard.setContent(Fields.scroll(styleTable));
        tableCard.setFooter(namingStatus);
        tableCard.addHeaderAction(copyStyleBtn);

        String[] styleLabels = new String[STYLES.length];
        for (int i = 0; i < STYLES.length; i++) {
            styleLabels[i] = STYLES[i].getSample();
        }
        batchStyleCombo = Fields.combo(styleLabels, 220);
        batchOutput = Fields.output(8, 24);
        batchOutput.setLineWrap(false);
        JButton copyBatchBtn = Buttons.snug(I18n.get("tool.textescape.btn.copy"));
        copyBatchBtn.addActionListener(event -> {
            String text = batchOutput.getText();
            if (text != null && !text.isEmpty()) {
                UIUtils.copyToClipboard(text);
            }
        });
        Card batchCard = Card.flush(I18n.get("tool.textescape.naming.card.batch"));
        batchCard.setContent(Fields.scroll(batchOutput));
        batchCard.addHeaderAction(Fields.label(I18n.get("tool.textescape.naming.convertAllTo")));
        batchCard.addHeaderAction(batchStyleCombo);
        batchCard.addHeaderAction(copyBatchBtn);

        JSplitPane top = Layouts.splitHorizontal(inputCard, tableCard, 0.4, 0.4);
        tab.add(Layouts.splitVertical(top, batchCard, 0.6, 0.6), BorderLayout.CENTER);

        namingTimer = new Timer(LIVE_DELAY_MS / 2, event -> refreshNaming());
        namingTimer.setRepeats(false);
        namingInput.getDocument().addDocumentListener(onChange(namingTimer::restart));
        acronymCheck.addActionListener(event -> refreshNaming());
        batchStyleCombo.addActionListener(event -> refreshNaming());
        return tab;
    }

    private void refreshNaming() {
        boolean acronyms = acronymCheck.isSelected();
        String[] lines = namingInput.getText().split("\\R", -1);

        String first = null;
        for (String line : lines) {
            if (!line.trim().isEmpty()) {
                first = line.trim();
                break;
            }
        }
        int selected = styleTable.getSelectedRow();
        styleModel.setRowCount(0);
        if (first != null) {
            for (NamingStyle style : STYLES) {
                styleModel.addRow(new Object[]{styleLabel(style), NamingConverter.convert(first, style, acronyms)});
            }
            if (selected >= 0 && selected < styleModel.getRowCount()) {
                styleTable.setRowSelectionInterval(selected, selected);
            }
            namingStatus.setText(I18n.get("tool.textescape.naming.status.source", first));
        } else {
            namingStatus.setText(I18n.get("tool.textescape.naming.status.firstLine"));
        }

        NamingStyle target = STYLES[Math.max(0, batchStyleCombo.getSelectedIndex())];
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            // 空行原样保留为空行，输出与输入逐行对齐，方便整列粘贴回表格或代码
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                out.append(NamingConverter.convert(line, target, acronyms));
            }
        }
        batchOutput.setText(out.toString());
        batchOutput.setCaretPosition(0);
    }

    private static String styleLabel(NamingStyle style) {
        String key = "tool.textescape.naming.style." + NamingConverter.convert(style.name(), NamingStyle.CAMEL, false);
        return style.getSample() + "  " + I18n.get(key);
    }

    private void copySelectedStyle() {
        int row = styleTable.getSelectedRow();
        if (row < 0) {
            return;
        }
        Object value = styleModel.getValueAt(styleTable.convertRowIndexToModel(row), 1);
        if (value != null) {
            UIUtils.copyToClipboard(value.toString());
            namingStatus.setText(I18n.get("tool.textescape.naming.status.copied", value));
        }
    }

    // ==========================================
    // 生命周期与工具
    // ==========================================
    @Override
    public void closeResources() {
        stop(liveTimer);
        stop(namingTimer);
    }

    private static void stop(Timer timer) {
        if (timer != null && timer.isRunning()) {
            timer.stop();
        }
    }

    private static DocumentListener onChange(Runnable action) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                action.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                action.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                action.run();
            }
        };
    }
}
