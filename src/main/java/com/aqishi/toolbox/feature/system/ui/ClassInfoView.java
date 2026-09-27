package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.feature.system.domain.ClassFileInfo;
import com.aqishi.toolbox.feature.system.domain.JvmDescriptors;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * 一个 class 文件的展示区：版本解释、摘要文本、字段表与方法表。
 * “Class 文件”页签与“JAR”页签的条目详情共用。
 */
final class ClassInfoView {

    private final JPanel panel;
    private final JLabel explainLabel;
    private final JTextArea summaryArea;
    private final JarUi.RowsModel<ClassFileInfo.Member> fieldModel;
    private final JarUi.RowsModel<ClassFileInfo.Member> methodModel;
    private final JTabbedPane memberTabs;
    private ClassFileInfo current;

    ClassInfoView() {
        explainLabel = Fields.note(I18n.get("tool.jarinspector.class.empty"));
        summaryArea = JarUi.detailArea();

        fieldModel = new JarUi.RowsModel<ClassFileInfo.Member>()
                .column(I18n.get("tool.jarinspector.column.access"), String.class,
                        member -> String.join(" ", ClassFileInfo.fieldAccessNames(member.accessFlags())))
                .column(I18n.get("tool.jarinspector.column.name"), String.class, ClassFileInfo.Member::name)
                .column(I18n.get("tool.jarinspector.column.type"), String.class, ClassFileInfo.Member::renderedType)
                .column(I18n.get("tool.jarinspector.column.descriptor"), String.class,
                        ClassFileInfo.Member::descriptor);
        methodModel = new JarUi.RowsModel<ClassFileInfo.Member>()
                .column(I18n.get("tool.jarinspector.column.access"), String.class,
                        member -> String.join(" ", ClassFileInfo.methodAccessNames(member.accessFlags())))
                .column(I18n.get("tool.jarinspector.column.name"), String.class, ClassFileInfo.Member::name)
                .column(I18n.get("tool.jarinspector.column.signature"), String.class,
                        member -> JvmDescriptors.method(member.descriptor()))
                .column(I18n.get("tool.jarinspector.column.descriptor"), String.class,
                        ClassFileInfo.Member::descriptor);

        memberTabs = new JTabbedPane();
        memberTabs.addTab(I18n.get("tool.jarinspector.tab.fields"), Fields.scrollBoxed(JarUi.table(fieldModel)));
        memberTabs.addTab(I18n.get("tool.jarinspector.tab.methods"), Fields.scrollBoxed(JarUi.table(methodModel)));

        panel = Layouts.box(0, Tokens.SPACE_SM);
        panel.add(explainLabel, BorderLayout.NORTH);
        panel.add(Layouts.splitVertical(Fields.scrollBoxed(summaryArea), memberTabs, 0.45, 0.45),
                BorderLayout.CENTER);
    }

    JComponent component() {
        return panel;
    }

    ClassFileInfo current() {
        return current;
    }

    String summaryText() {
        return summaryArea.getText();
    }

    int fieldRows() {
        return fieldModel.getRowCount();
    }

    int methodRows() {
        return methodModel.getRowCount();
    }

    void show(ClassFileInfo info) {
        current = info;
        explainLabel.setText(ClassText.explain(info));
        explainLabel.setForeground(info.preview() ? Tokens.warning() : Tokens.foreground());
        summaryArea.setText(ClassText.summary(info));
        summaryArea.setCaretPosition(0);
        fieldModel.setRows(info.fields());
        methodModel.setRows(info.methods());
        memberTabs.setTitleAt(0, I18n.get("tool.jarinspector.tab.fields") + " (" + info.fields().size() + ")");
        memberTabs.setTitleAt(1, I18n.get("tool.jarinspector.tab.methods") + " (" + info.methods().size() + ")");
    }

    void showMessage(String message, boolean error) {
        current = null;
        explainLabel.setText(message);
        explainLabel.setForeground(error ? Tokens.danger() : Tokens.mutedForeground());
        summaryArea.setText("");
        fieldModel.setRows(List.of());
        methodModel.setRows(List.of());
        memberTabs.setTitleAt(0, I18n.get("tool.jarinspector.tab.fields"));
        memberTabs.setTitleAt(1, I18n.get("tool.jarinspector.tab.methods"));
    }
}
