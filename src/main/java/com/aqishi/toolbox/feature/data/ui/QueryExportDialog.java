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
        QueryExportOptions form = new QueryExportOptions(result);
        Options chosen;
        while (true) {
            if(JOptionPane.showConfirmDialog(parent,form,I18n.get("data.export.title"),JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)!=JOptionPane.OK_OPTION)return;
            try { chosen = form.options(); break; }
            catch (IllegalArgumentException invalid) { UIUtils.error(parent, invalid.getMessage()); }
        }
        final Options options = chosen;
        Format selected=options.format(); JFileChooser chooser=new JFileChooser();
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(selected.name(), selected.name().toLowerCase(java.util.Locale.ROOT)));
        chooser.setSelectedFile(new java.io.File("query."+selected.name().toLowerCase(java.util.Locale.ROOT)));
        if(chooser.showSaveDialog(parent)!=JFileChooser.APPROVE_OPTION)return;
        Path target=chooser.getSelectedFile().toPath();
        if(Files.exists(target) && JOptionPane.showConfirmDialog(parent,I18n.get("data.export.overwrite",target.getFileName()),I18n.get("data.export.title"),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        new SwingWorker<Void,Void>() {
            protected Void doInBackground() throws Exception { new QueryResultExporter().export(result,target,options); return null; }
            protected void done() { try { get(); UIUtils.info(parent,I18n.get("data.export.saved",target.toAbsolutePath())); }
                catch(Exception error){ UIUtils.error(parent,Errors.describeRoot(error)); } }
        }.execute();
    }
}
