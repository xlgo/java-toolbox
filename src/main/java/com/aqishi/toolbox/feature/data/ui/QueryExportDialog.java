package com.aqishi.toolbox.feature.data.ui;

import com.aqishi.toolbox.feature.data.application.QueryResultExporter;
import com.aqishi.toolbox.feature.data.application.QueryResultExporter.*;
import com.aqishi.toolbox.feature.data.domain.QueryResult;
import com.aqishi.toolbox.ui.kit.*;
import com.aqishi.toolbox.util.*;
import javax.swing.*;
import java.awt.Component;
import java.nio.file.*;

/** File export uses the immutable last result, so switching connections cannot change its contents. */
final class QueryExportDialog {
    private QueryExportDialog() { }
    static void show(Component parent, QueryResult result) {
        if(result==null || result.isUpdate()) { UIUtils.info(parent,I18n.get("data.export.noResult")); return; }
        JComboBox<Format> format=new JComboBox<>(Format.values()); JComboBox<Dialect> dialect=new JComboBox<>(Dialect.values());
        JTextField table=Fields.text("exported_data"); JCheckBox guard=Fields.check(I18n.get("data.export.csvGuard"),true);
        FormGrid form=new FormGrid(); form.row(I18n.get("data.export.format"),format); form.row(I18n.get("data.export.table"),table);
        form.row(I18n.get("data.export.dialect"),dialect); form.row("CSV",guard);
        form.row("",Fields.caption(I18n.get("data.export.scope",result.getRows().size())));
        form.row("",Fields.caption(I18n.get("data.export.encoding")));
        if(result.getWarning()!=null) form.row("",Fields.caption(result.getWarning()));
        Runnable sync=()->{ boolean sql=format.getSelectedItem()==Format.SQL; table.setEnabled(sql); dialect.setEnabled(sql); guard.setEnabled(format.getSelectedItem()==Format.CSV); };
        format.addActionListener(e->sync.run()); sync.run();
        if(JOptionPane.showConfirmDialog(parent,form,I18n.get("data.export.title"),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        Format selected=(Format)format.getSelectedItem(); JFileChooser chooser=new JFileChooser();
        chooser.setSelectedFile(new java.io.File("query."+selected.name().toLowerCase(java.util.Locale.ROOT)));
        if(chooser.showSaveDialog(parent)!=JFileChooser.APPROVE_OPTION)return;
        Path target=chooser.getSelectedFile().toPath();
        if(Files.exists(target) && JOptionPane.showConfirmDialog(parent,I18n.get("data.export.overwrite",target.getFileName()),I18n.get("data.export.title"),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        Options options=new Options(selected,table.getText(),(Dialect)dialect.getSelectedItem(),guard.isSelected());
        new SwingWorker<Void,Void>() {
            protected Void doInBackground() throws Exception { new QueryResultExporter().export(result,target,options); return null; }
            protected void done() { try { get(); UIUtils.info(parent,I18n.get("data.export.saved",target.toAbsolutePath())); }
                catch(Exception error){ UIUtils.error(parent,Errors.describeRoot(error)); } }
        }.execute();
    }
}
