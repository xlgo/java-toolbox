package com.aqishi.toolbox.feature.data.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.data.application.MyBatisLogSql;
import com.aqishi.toolbox.feature.data.application.QueryResultExporter;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.*;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;

public final class MyBatisLogPanel extends ToolPanel implements ManagedResourceOwner {
    private TextWorkbench workbench;

    public MyBatisLogPanel() {
        super(ToolCatalog.MYBATIS_SQL);
    }

    @Override
    protected JComponent build() {
        JComboBox<QueryResultExporter.Dialect> dialect =
                new JComboBox<>(QueryResultExporter.Dialect.values());
        FormGrid form = new FormGrid();
        form.row(I18n.get("data.export.dialect"), dialect);
        form.fullRow(Fields.note(I18n.get("mybatis.hint")));
        workbench =
                new TextWorkbench(
                        form,
                        () -> {
                            String source = workbench.input.getText();
                            var selected = (QueryResultExporter.Dialect) dialect.getSelectedItem();
                            return () -> new MyBatisLogSql().restore(source, selected);
                        },
                        "==> Preparing: SELECT * FROM users WHERE name = ? AND age >= ? AND"
                            + " disabled = ?\n"
                            + "==> Parameters: O'Reilly(String), 18(Integer), false(Boolean)");
        JPanel root = Layouts.page();
        root.add(workbench);
        return root;
    }

    @Override
    public void closeResources() {
        if (workbench != null) workbench.closeResources();
    }
}
