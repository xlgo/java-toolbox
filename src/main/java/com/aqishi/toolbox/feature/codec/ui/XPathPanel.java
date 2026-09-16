package com.aqishi.toolbox.feature.codec.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.catalog.ToolDescriptor;
import com.aqishi.toolbox.feature.codec.domain.XPathService;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * XPath 查询与 XSLT 转换面板。
 */
public class XPathPanel extends ToolPanel {

    private final XPathService service;

    private JTextField expressionField;
    private JComboBox<String> commonCombo;
    private JComboBox<String> modeCombo;
    private JCheckBox namespaceCheck;
    private JTextArea xmlArea;
    private JTextArea resultArea;
    private JLabel queryStatusLabel;

    private JTextArea xsltXmlArea;
    private JTextArea xsltArea;
    private JTextArea xsltResultArea;
    private JCheckBox indentCheck;
    private JLabel transformStatusLabel;

    private List<String> commonKeys = new ArrayList<>();

    public XPathPanel() {
        this(ToolCatalog.XPATH_TOOL, new XPathService());
    }

    public XPathPanel(XPathService service) {
        this(ToolCatalog.XPATH_TOOL, service);
    }

    public XPathPanel(ToolDescriptor descriptor, XPathService service) {
        super(Objects.requireNonNull(descriptor, "descriptor"));
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(I18n.get("tool.xpath.tab.query"), buildQueryTab());
        tabs.addTab(I18n.get("tool.xpath.tab.transform"), buildTransformTab());
        root.add(tabs, BorderLayout.CENTER);
        return root;
    }

    // ==========================================
    // Tab 1: XPath 查询
    // ==========================================
    private JComponent buildQueryTab() {
        JPanel panel = new JPanel(new BorderLayout(0, Tokens.SPACE_MD));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(
                Tokens.SPACE_SM, Tokens.SPACE_SM, Tokens.SPACE_SM, Tokens.SPACE_SM));

        expressionField = Fields.mono("//item[@price>100]/@sku");
        expressionField.putClientProperty("JTextField.placeholderText",
                I18n.get("tool.xpath.placeholder.expression"));

        Map<String, String> expressions = XPathService.commonExpressions();
        String[] items = new String[expressions.size() + 1];
        items[0] = I18n.get("tool.xpath.common.placeholder");
        commonKeys = new ArrayList<>();
        commonKeys.add("");
        int index = 1;
        for (Map.Entry<String, String> entry : expressions.entrySet()) {
            items[index++] = entry.getKey() + "  —  " + I18n.get(entry.getValue());
            commonKeys.add(entry.getKey());
        }
        commonCombo = Fields.combo(items);

        modeCombo = Fields.combo(new String[]{
                I18n.get("tool.xpath.mode.auto"),
                I18n.get("tool.xpath.mode.nodeset"),
                I18n.get("tool.xpath.mode.string"),
                I18n.get("tool.xpath.mode.number"),
                I18n.get("tool.xpath.mode.boolean")});

        namespaceCheck = Fields.check(I18n.get("tool.xpath.opt.namespaceAware"), false);
        namespaceCheck.setToolTipText(I18n.get("tool.xpath.opt.namespaceAware.tip"));

        JButton executeBtn = Buttons.primary(I18n.get("tool.xpath.btn.execute"));
        JButton namespacesBtn = Buttons.secondary(I18n.get("tool.xpath.btn.namespaces"));

        JPanel optionsBar = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_MD, 0));
        optionsBar.setOpaque(false);
        optionsBar.add(modeCombo);
        optionsBar.add(namespaceCheck);
        optionsBar.add(executeBtn);
        optionsBar.add(namespacesBtn);

        FormGrid form = new FormGrid();
        form.row(I18n.get("tool.xpath.label.expression"), expressionField, commonCombo);
        form.row(I18n.get("tool.xpath.label.options"), optionsBar);

        Card configCard = Card.titled(I18n.get("tool.xpath.card.config"));
        configCard.setContent(form);

        xmlArea = Fields.area(12, 30);
        xmlArea.setText(XPathService.SAMPLE_XML);
        Card xmlCard = Card.flush(I18n.get("tool.xpath.card.input"));
        xmlCard.setContent(Fields.scroll(xmlArea));

        JButton sampleBtn = Buttons.snug(I18n.get("tool.xpath.btn.sample"));
        JButton clearBtn = Buttons.danger(I18n.get("tool.xpath.btn.clear"));
        xmlCard.addHeaderAction(sampleBtn);
        xmlCard.addHeaderAction(clearBtn);

        resultArea = Fields.output(12, 30);
        Card resultCard = Card.flush(I18n.get("tool.xpath.card.output"));
        resultCard.setContent(Fields.scroll(resultArea));

        queryStatusLabel = Fields.caption(I18n.get("tool.xpath.status.ready"));
        JButton copyBtn = Buttons.snug(I18n.get("tool.xpath.btn.copy"));
        resultCard.addHeaderAction(queryStatusLabel);
        resultCard.addHeaderAction(copyBtn);

        panel.add(configCard, BorderLayout.NORTH);
        panel.add(Layouts.splitHorizontal(xmlCard, resultCard, 0.5, 0.5), BorderLayout.CENTER);

        executeBtn.addActionListener(event -> evaluate());
        expressionField.addActionListener(event -> evaluate());
        modeCombo.addActionListener(event -> evaluate());
        namespaceCheck.addActionListener(event -> evaluate());
        commonCombo.addActionListener(event -> {
            int selected = commonCombo.getSelectedIndex();
            if (selected > 0 && selected < commonKeys.size()) {
                expressionField.setText(commonKeys.get(selected));
                evaluate();
            }
        });
        namespacesBtn.addActionListener(event -> showNamespaces());
        sampleBtn.addActionListener(event -> {
            xmlArea.setText(XPathService.SAMPLE_XML);
            expressionField.setText("//item[@price>100]/@sku");
            evaluate();
        });
        clearBtn.addActionListener(event -> {
            xmlArea.setText("");
            resultArea.setText("");
            setStatus(queryStatusLabel, I18n.get("tool.xpath.status.cleared"), Tokens.mutedForeground());
        });
        copyBtn.addActionListener(event -> copy(resultArea, queryStatusLabel));

        evaluate();
        return panel;
    }

    private void evaluate() {
        XPathService.ResultMode mode = switch (modeCombo.getSelectedIndex()) {
            case 1 -> XPathService.ResultMode.NODESET;
            case 2 -> XPathService.ResultMode.STRING;
            case 3 -> XPathService.ResultMode.NUMBER;
            case 4 -> XPathService.ResultMode.BOOLEAN;
            default -> XPathService.ResultMode.AUTO;
        };
        XPathService.QueryResult result = service.query(
                xmlArea.getText(), expressionField.getText(), mode, namespaceCheck.isSelected());
        if (result.isSuccess()) {
            resultArea.setText(result.getOutput());
            resultArea.setCaretPosition(0);
            setStatus(queryStatusLabel, I18n.get("tool.xpath.status.matched",
                            result.getMatchCount(), result.getElapsedMs()),
                    result.getMatchCount() == 0 ? Tokens.warning() : Tokens.mutedForeground());
        } else {
            resultArea.setText(localizeError(result.getErrorMessage()));
            setStatus(queryStatusLabel, I18n.get("tool.xpath.status.error"), Tokens.danger());
        }
    }

    private void showNamespaces() {
        Map<String, String> namespaces = service.discoverNamespaces(xmlArea.getText());
        if (namespaces.isEmpty()) {
            UIUtils.info(null, I18n.get("tool.xpath.ns.none"));
            return;
        }
        StringBuilder text = new StringBuilder(I18n.get("tool.xpath.ns.header")).append('\n');
        for (Map.Entry<String, String> entry : namespaces.entrySet()) {
            text.append('\n').append(entry.getKey()).append(" = ").append(entry.getValue());
        }
        UIUtils.info(null, text.toString());
    }

    // ==========================================
    // Tab 2: XSLT 转换
    // ==========================================
    private JComponent buildTransformTab() {
        JPanel panel = new JPanel(new BorderLayout(0, Tokens.SPACE_MD));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(
                Tokens.SPACE_SM, Tokens.SPACE_SM, Tokens.SPACE_SM, Tokens.SPACE_SM));

        xsltXmlArea = Fields.area(8, 30);
        xsltXmlArea.setText(XPathService.SAMPLE_XML);
        Card xmlCard = Card.flush(I18n.get("tool.xpath.card.input"));
        xmlCard.setContent(Fields.scroll(xsltXmlArea));

        xsltArea = Fields.area(8, 30);
        xsltArea.setText(XPathService.SAMPLE_XSLT);
        Card stylesheetCard = Card.flush(I18n.get("tool.xpath.card.stylesheet"));
        stylesheetCard.setContent(Fields.scroll(xsltArea));

        JButton transformBtn = Buttons.primary(I18n.get("tool.xpath.btn.transform"));
        JButton sampleBtn = Buttons.snug(I18n.get("tool.xpath.btn.sample"));
        indentCheck = Fields.check(I18n.get("tool.xpath.opt.indent"), true);
        stylesheetCard.addHeaderAction(indentCheck);
        stylesheetCard.addHeaderAction(sampleBtn);
        stylesheetCard.addHeaderAction(transformBtn);

        xsltResultArea = Fields.output(10, 30);
        Card resultCard = Card.flush(I18n.get("tool.xpath.card.transformOutput"));
        resultCard.setContent(Fields.scroll(xsltResultArea));

        transformStatusLabel = Fields.caption(I18n.get("tool.xpath.status.ready"));
        JButton copyBtn = Buttons.snug(I18n.get("tool.xpath.btn.copy"));
        resultCard.addHeaderAction(transformStatusLabel);
        resultCard.addHeaderAction(copyBtn);

        JSplitPane sources = Layouts.splitVertical(xmlCard, stylesheetCard, 0.5, 0.5);
        panel.add(Layouts.splitHorizontal(sources, resultCard, 0.5, 0.5), BorderLayout.CENTER);

        transformBtn.addActionListener(event -> transform());
        indentCheck.addActionListener(event -> transform());
        sampleBtn.addActionListener(event -> {
            xsltXmlArea.setText(XPathService.SAMPLE_XML);
            xsltArea.setText(XPathService.SAMPLE_XSLT);
            transform();
        });
        copyBtn.addActionListener(event -> copy(xsltResultArea, transformStatusLabel));
        return panel;
    }

    private void transform() {
        XPathService.TransformResult result = service.transform(
                xsltXmlArea.getText(), xsltArea.getText(), indentCheck.isSelected());
        if (result.isSuccess()) {
            xsltResultArea.setText(result.getOutput());
            xsltResultArea.setCaretPosition(0);
            setStatus(transformStatusLabel,
                    I18n.get("tool.xpath.status.transformed", result.getElapsedMs()),
                    Tokens.success());
        } else {
            xsltResultArea.setText(localizeError(result.getErrorMessage()));
            setStatus(transformStatusLabel, I18n.get("tool.xpath.status.error"), Tokens.danger());
        }
    }

    // ==========================================
    // 公共
    // ==========================================
    private void copy(JTextArea source, JLabel status) {
        String text = source.getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        UIUtils.copyToClipboard(text);
        setStatus(status, I18n.get("tool.xpath.status.copied"), Tokens.success());
    }

    private void setStatus(JLabel label, String text, Color color) {
        label.setText(text);
        label.setForeground(color);
    }

    /** 领域层用稳定错误码表示「输入还没填」，其余情况原样透出解析器的描述。 */
    private String localizeError(String raw) {
        if (raw == null) {
            return "";
        }
        switch (raw) {
            case "empty.xml":
                return I18n.get("tool.xpath.error.emptyXml");
            case "empty.expression":
                return I18n.get("tool.xpath.error.emptyExpression");
            case "empty.stylesheet":
                return I18n.get("tool.xpath.error.emptyStylesheet");
            default:
                return raw;
        }
    }
}
