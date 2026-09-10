package com.aqishi.toolbox.feature.codec.ui;

import com.aqishi.toolbox.feature.codec.domain.FormatConversionService;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

/**
 * JSON, XML, YAML, CSV, Properties, INI and TOML format conversion panel.
 */
public class FormatConvertPanel extends ToolPanel {

    private static final String[] FORMATS = {"JSON", "XML", "YAML", "CSV", "Properties", "INI", "TOML"};

    private final FormatConversionService conversionService = new FormatConversionService();

    public FormatConvertPanel() {
        super("format", "format.convert",
                "JSON", "XML", "YAML", "CSV", "Properties", "INI", "TOML",
                "格式互转", "数据转换", "序列化", "语法校验");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        JComboBox<String> fromCombo = Fields.combo(FORMATS, 160);
        JComboBox<String> toCombo = Fields.combo(
                new String[]{"YAML", "JSON", "XML", "CSV", "Properties", "INI", "TOML"}, 160);
        JButton convert = Buttons.primary("转换");
        JButton validate = Buttons.secondary("校验语法");
        JButton copy = Buttons.ghost("复制结果");
        JButton clear = Buttons.ghost("清空");

        FormGrid form = new FormGrid();
        form.row("源格式:", keepWidth(fromCombo));
        form.row("目标格式:", keepWidth(toCombo));

        Card config = Card.titled("格式互转");
        config.setContent(form);
        config.addHeaderAction(clear);
        config.addHeaderAction(validate);
        config.addHeaderAction(convert);

        JTextArea input = Fields.area(8, 40);
        input.setText("{\n  \"name\": \"java-toolbox\",\n  \"version\": \"1.2.0\",\n  \"author\": {\n    \"name\": \"aqishi\"\n  }\n}");
        JTextArea output = Fields.output(10, 40);

        Card inputCard = Card.flush("输入");
        inputCard.setContent(Fields.scroll(input));

        Card outputCard = Card.flush("输出");
        outputCard.setContent(Fields.scroll(output));
        outputCard.addHeaderAction(copy);

        root.add(config, BorderLayout.NORTH);
        root.add(Layouts.splitHorizontal(inputCard, outputCard, 0.5), BorderLayout.CENTER);

        convert.addActionListener(e -> convert(input, output, fromCombo, toCombo));
        validate.addActionListener(e -> validate(input, output, fromCombo));
        copy.addActionListener(e -> UIUtils.copyToClipboard(output.getText()));
        clear.addActionListener(e -> {
            input.setText("");
            output.setText("");
        });

        convert.doClick();
        return root;
    }

    private void convert(JTextArea input, JTextArea output, JComboBox<String> source, JComboBox<String> target) {
        String raw = input.getText();
        if (raw.trim().isEmpty()) {
            output.setText("");
            return;
        }
        FormatConversionService.ConversionResult result = conversionService.convert(raw,
                (String) source.getSelectedItem(), (String) target.getSelectedItem());
        output.setText(result.isSuccess() ? result.getOutput() : "转换失败:\n" + result.getErrorMessage());
    }

    private void validate(JTextArea input, JTextArea output, JComboBox<String> source) {
        FormatConversionService.ValidationResult result = conversionService.validate(input.getText(),
                (String) source.getSelectedItem());
        output.setText(result.isValid() ? "语法校验通过" : "语法校验失败:\n" + result.getErrorMessage());
    }

    /** Keeps short format selectors left-aligned in the form's expanding input column. */
    private static JComponent keepWidth(JComponent field) {
        JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        wrapper.setOpaque(false);
        wrapper.add(field);
        return wrapper;
    }
}
