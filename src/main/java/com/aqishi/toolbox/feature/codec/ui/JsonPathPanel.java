package com.aqishi.toolbox.feature.codec.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.catalog.ToolDescriptor;
import com.aqishi.toolbox.util.JsonFormatter;
import com.aqishi.toolbox.feature.codec.domain.JsonPathService;
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
import java.util.Map;
import java.util.Objects;

/**
 * JSONPath 提取与查询面板。
 * <p>支持基于 JSONPath 表达式的值提取、条件切片、结构过滤以及路径列表追踪。</p>
 */
public class JsonPathPanel extends ToolPanel {

    private final JsonPathService service;

    public JsonPathPanel() {
        this(ToolCatalog.JSONPATH_TESTER, new JsonPathService());
    }

    public JsonPathPanel(JsonPathService service) {
        this(ToolCatalog.JSONPATH_TESTER, service);
    }

    public JsonPathPanel(ToolDescriptor descriptor, JsonPathService service) {
        super(Objects.requireNonNull(descriptor, "descriptor"));
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // ===== 顶部：JSONPath 配置卡片 =====
        JTextField pathField = Fields.mono("$.store.book[*].author");
        pathField.putClientProperty("JTextField.placeholderText", "输入 JSONPath 表达式，如 $.store.book[*].author");

        Map<String, String> commonExprMap = JsonPathService.getCommonExpressions();
        String[] exprItems = new String[commonExprMap.size() + 1];
        exprItems[0] = I18n.get("tool.jsonpath.common.placeholder", "-- 常用表达式速选 --");
        int idx = 1;
        String[] exprKeys = new String[commonExprMap.size() + 1];
        exprKeys[0] = "";
        for (Map.Entry<String, String> entry : commonExprMap.entrySet()) {
            exprItems[idx] = entry.getValue();
            exprKeys[idx] = entry.getKey();
            idx++;
        }
        JComboBox<String> commonCombo = Fields.combo(exprItems);

        JCheckBox prettyCheck = Fields.check(I18n.get("tool.jsonpath.opt.pretty", "美化输出"), true);
        JCheckBox asPathCheck = Fields.check(I18n.get("tool.jsonpath.opt.as_path", "路径模式"), false);

        JButton executeBtn = Buttons.primary(I18n.get("tool.jsonpath.btn.execute", "执行提取"));

        JPanel optionsBar = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_MD, 0));
        optionsBar.setOpaque(false);
        optionsBar.add(prettyCheck);
        optionsBar.add(asPathCheck);
        optionsBar.add(executeBtn);

        FormGrid form = new FormGrid();
        form.row(I18n.get("tool.jsonpath.label.expression", "表达式"), pathField, commonCombo);
        form.row(I18n.get("tool.jsonpath.label.options", "选项"), optionsBar);

        Card configCard = Card.titled(I18n.get("tool.jsonpath.card.config", "JSONPath 配置"));
        configCard.setContent(form);

        // ===== 中间：双栏对比区（左：原始 JSON，右：提取结果） =====
        JTextArea jsonArea = Fields.area(12, 30);
        jsonArea.setText(JsonPathService.SAMPLE_JSON);
        Card jsonCard = Card.flush(I18n.get("tool.jsonpath.card.input", "原始 JSON"));
        jsonCard.setContent(Fields.scroll(jsonArea));

        JButton sampleBtn = Buttons.snug(I18n.get("tool.jsonpath.btn.sample", "加载示例"));
        JButton formatBtn = Buttons.snug(I18n.get("tool.jsonpath.btn.format", "格式化"));
        JButton clearBtn = Buttons.danger(I18n.get("tool.jsonpath.btn.clear", "清空"));
        jsonCard.addHeaderAction(sampleBtn);
        jsonCard.addHeaderAction(formatBtn);
        jsonCard.addHeaderAction(clearBtn);

        JTextArea resultArea = Fields.output(12, 30);
        Card resultCard = Card.flush(I18n.get("tool.jsonpath.card.output", "提取结果"));
        resultCard.setContent(Fields.scroll(resultArea));

        JLabel statusLabel = Fields.caption(I18n.get("tool.jsonpath.status.ready", "就绪"));
        JButton copyBtn = Buttons.snug(I18n.get("tool.jsonpath.btn.copy", "复制结果"));
        resultCard.addHeaderAction(statusLabel);
        resultCard.addHeaderAction(copyBtn);

        JSplitPane splitPane = Layouts.splitHorizontal(jsonCard, resultCard, 0.5, 0.5);

        root.add(configCard, BorderLayout.NORTH);
        root.add(splitPane, BorderLayout.CENTER);

        // ===== 行为绑定 =====
        Runnable doEvaluate = () -> {
            String json = jsonArea.getText();
            String expr = pathField.getText();
            boolean asPath = asPathCheck.isSelected();
            boolean pretty = prettyCheck.isSelected();

            JsonPathService.EvaluationResult result = service.evaluate(json, expr, asPath, pretty);
            if (result.isSuccess()) {
                resultArea.setText(result.getOutput());
                statusLabel.setText(I18n.get("tool.jsonpath.status.matched", "匹配项: {0} | 耗时: {1}ms",
                        result.getMatchCount(), result.getElapsedMs()));
                statusLabel.setForeground(Tokens.mutedForeground());
            } else {
                resultArea.setText(result.getErrorMessage());
                statusLabel.setText(I18n.get("tool.jsonpath.status.error", "执行失败"));
                statusLabel.setForeground(Tokens.danger());
            }
        };

        executeBtn.addActionListener(e -> doEvaluate.run());
        pathField.addActionListener(e -> doEvaluate.run());
        prettyCheck.addActionListener(e -> doEvaluate.run());
        asPathCheck.addActionListener(e -> doEvaluate.run());

        commonCombo.addActionListener(e -> {
            int selected = commonCombo.getSelectedIndex();
            if (selected > 0 && selected < exprKeys.length) {
                pathField.setText(exprKeys[selected]);
                doEvaluate.run();
            }
        });

        sampleBtn.addActionListener(e -> {
            jsonArea.setText(JsonPathService.SAMPLE_JSON);
            pathField.setText("$.store.book[*].author");
            doEvaluate.run();
        });

        formatBtn.addActionListener(e -> {
            String text = jsonArea.getText();
            if (text != null && !text.trim().isEmpty()) {
                try {
                    jsonArea.setText(JsonFormatter.pretty(text));
                } catch (Exception ex) {
                    statusLabel.setText(I18n.get("tool.jsonpath.status.invalid_json", "JSON 格式无效"));
                    statusLabel.setForeground(Tokens.danger());
                }
            }
        });

        clearBtn.addActionListener(e -> {
            jsonArea.setText("");
            resultArea.setText("");
            statusLabel.setText(I18n.get("tool.jsonpath.status.cleared", "已清空"));
            statusLabel.setForeground(Tokens.mutedForeground());
        });

        copyBtn.addActionListener(e -> {
            String out = resultArea.getText();
            if (out != null && !out.isEmpty()) {
                UIUtils.copyToClipboard(out);
                statusLabel.setText(I18n.get("tool.jsonpath.status.copied", "结果已复制"));
                statusLabel.setForeground(Tokens.success());
            }
        });

        // 初始执行一次样例
        doEvaluate.run();

        return root;
    }
}
