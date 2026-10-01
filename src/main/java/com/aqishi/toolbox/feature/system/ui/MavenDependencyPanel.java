package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.system.domain.MavenDependencyTree;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.*;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;

public final class MavenDependencyPanel extends ToolPanel implements ManagedResourceOwner {
    private TextWorkbench workbench;

    public MavenDependencyPanel() {
        super(ToolCatalog.MAVEN_TREE);
    }

    @Override
    protected JComponent build() {
        JTextField filter = Fields.text("");
        FormGrid options = new FormGrid();
        options.row(I18n.get("maven.filter"), filter);
        options.fullRow(Fields.note(I18n.get("maven.hint")));
        workbench =
                new TextWorkbench(
                        options,
                        () -> {
                            String input = workbench.input.getText(), search = filter.getText();
                            return () -> {
                                var service = new MavenDependencyTree();
                                return service.report(service.parse(input), search);
                            };
                        },
                        "com.example:app:jar:1.0\n"
                            + "+- com.example:client:jar:2.0:compile\n"
                            + "|  \\-"
                            + " com.fasterxml.jackson.core:jackson-databind:jar:2.15.2:compile\n"
                            + "\\- com.example:legacy:jar:1.0:compile\n"
                            + "   \\-"
                            + " (com.fasterxml.jackson.core:jackson-databind:jar:2.13.0:compile -"
                            + " omitted for conflict with 2.15.2)");
        JPanel root = Layouts.page();
        root.add(workbench);
        return root;
    }

    @Override
    public void closeResources() {
        if (workbench != null) workbench.closeResources();
    }
}
