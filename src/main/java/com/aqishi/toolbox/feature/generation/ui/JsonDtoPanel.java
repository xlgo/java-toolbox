package com.aqishi.toolbox.feature.generation.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.generation.domain.JsonDtoGenerator;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.*;
import com.aqishi.toolbox.util.I18n;

import java.util.*;

import javax.swing.*;

public final class JsonDtoPanel extends ToolPanel implements ManagedResourceOwner {
    private TextWorkbench workbench;

    public JsonDtoPanel() {
        super(ToolCatalog.JSON_DTO);
    }

    @Override
    protected JComponent build() {
        JTextField name = Fields.text("ResponseDto"),
                pkg = Fields.text("com.example.dto"),
                rename = Fields.text("");
        JComboBox<JsonDtoGenerator.Style> style = new JComboBox<>(JsonDtoGenerator.Style.values());
        JCheckBox jackson = Fields.check(I18n.get("dto.jackson"), true);
        FormGrid form = new FormGrid();
        form.row(I18n.get("dto.class"), name, style);
        form.row(I18n.get("dto.package"), pkg, jackson);
        form.row(I18n.get("dto.rename"), rename);
        form.fullRow(Fields.note(I18n.get("dto.hint")));
        workbench =
                new TextWorkbench(
                        form,
                        () -> {
                            String source = workbench.input.getText();
                            Map<String, String> names = new LinkedHashMap<>();
                            for (String line : rename.getText().split(";")) {
                                if (line.isBlank()) continue;
                                int eq = line.indexOf('=');
                                if (eq <= 0)
                                    throw new IllegalArgumentException(
                                            I18n.get("dto.renameInvalid"));
                                names.put(
                                        line.substring(0, eq).trim(),
                                        line.substring(eq + 1).trim());
                            }
                            var options =
                                    new JsonDtoGenerator.Options(
                                            pkg.getText().trim(),
                                            name.getText().trim(),
                                            (JsonDtoGenerator.Style) style.getSelectedItem(),
                                            jackson.isSelected(),
                                            names);
                            return () -> new JsonDtoGenerator().generate(source, options);
                        },
                        "{\"request_id\":\"r-1\",\"users\":[{\"id\":1,\"name\":\"Alice\"},{\"id\":2147483648,\"active\":true}],\"total\":2}");
        JPanel root = Layouts.page();
        root.add(workbench);
        return root;
    }

    @Override
    public void closeResources() {
        if (workbench != null) workbench.closeResources();
    }
}
