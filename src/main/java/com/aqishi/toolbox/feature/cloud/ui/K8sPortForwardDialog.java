package com.aqishi.toolbox.feature.cloud.ui;

import com.aqishi.toolbox.feature.cloud.application.*;
import com.aqishi.toolbox.ui.kit.*;
import com.aqishi.toolbox.util.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;

/** Modeless session manager bound to one connection snapshot. Closing it or disconnecting stops all children. */
final class K8sPortForwardDialog implements AutoCloseable {
    private final JDialog dialog;
    private final java.util.List<KubectlPortForward> sessions=new ArrayList<>();
    private final DefaultListModel<String> model=new DefaultListModel<>();
    private final JList<String> list=new JList<>(model);
    private final JTextArea log=Fields.output(10,65);
    private final Timer timer;
    private boolean closed;
    K8sPortForwardDialog(Component parent,PortForwardConfig.Credentials credentials,String initialNs,String initialKind,String initialName) {
        dialog=new JDialog(SwingUtilities.getWindowAncestor(parent),I18n.get("k8s.forward.title"),Dialog.ModalityType.MODELESS);
        JTextField executable=Fields.text("kubectl"); JTextField namespace=Fields.text(initialNs);
        JComboBox<String> kind=new JComboBox<>(new String[]{"pod","service","deployment"}); kind.setSelectedItem(initialKind);
        JTextField name=Fields.text(initialName); JSpinner local=Fields.spinner(0,0,65535,1); JTextField remote=Fields.text("8080");
        FormGrid form=new FormGrid(); form.row(I18n.get("k8s.forward.executable"),executable); form.row("Namespace",namespace);
        form.row(I18n.get("k8s.forward.resource"),kind,name); form.row(I18n.get("k8s.forward.local"),local); form.row(I18n.get("k8s.forward.remote"),remote);
        JButton start=Buttons.primary(I18n.get("k8s.forward.start")); JButton stop=Buttons.danger(I18n.get("k8s.forward.stop"));
        start.addActionListener(e -> {
            try {
                local.commitEdit();
                var target=new KubectlPortForward.Target(namespace.getText().trim(),(String)kind.getSelectedItem(),name.getText().trim(),(Integer)local.getValue(),remote.getText().trim());
                var session=new KubectlPortForward(executable.getText().trim(),target); sessions.add(session); session.start(credentials);
                refresh(); list.setSelectedIndex(sessions.size()-1);
            } catch(Exception error) { UIUtils.error(dialog,Errors.describeRoot(error)); }
        });
        stop.addActionListener(e -> { int i=list.getSelectedIndex(); if(i>=0)sessions.get(i).close(); refresh(); });
        JButton copy=Buttons.secondary(I18n.get("k8s.forward.copy")); copy.addActionListener(e -> {
            int i=list.getSelectedIndex(); if(i>=0) { var snapshot=sessions.get(i).snapshot(); if(snapshot.state()==KubectlPortForward.State.RUNNING) UIUtils.copyToClipboard("127.0.0.1:"+snapshot.localPort()); }
        });
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT)); actions.add(start);actions.add(stop);actions.add(copy);
        JPanel top=new JPanel(new BorderLayout(0,6)); top.add(Fields.caption(I18n.get("k8s.forward.hint")),BorderLayout.NORTH); top.add(form);top.add(actions,BorderLayout.SOUTH);
        JPanel root=Layouts.page(); root.add(top,BorderLayout.NORTH); root.add(Layouts.splitVertical(new JScrollPane(list),new JScrollPane(log),0.4));
        dialog.setContentPane(root);dialog.setSize(850,670);dialog.setLocationRelativeTo(parent);dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter(){@Override public void windowClosed(WindowEvent e){close();}});
        timer=new Timer(300,e->refresh());timer.start();
    }
    void show(){dialog.setVisible(true);}
    boolean isClosed(){return closed;}
    private void refresh(){
        if(closed)return;
        int selected=list.getSelectedIndex(); model.clear();
        for(var session:sessions){var s=session.snapshot();var t=session.target();model.addElement(t.namespace()+" / "+t.kind()+"/"+t.name()+"  127.0.0.1:"+s.localPort()+" → "+t.remotePort()+"  "+I18n.get("k8s.forward.state."+s.state().name()));}
        if(selected>=0 && selected<model.size()){list.setSelectedIndex(selected);log.setText(sessions.get(selected).snapshot().log());}
    }
    @Override public void close(){
        if(closed)return;closed=true;timer.stop();sessions.forEach(KubectlPortForward::close);sessions.clear();dialog.dispose();
    }
}
