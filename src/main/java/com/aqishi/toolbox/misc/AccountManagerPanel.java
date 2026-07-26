package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.VaultAccessPanel;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;
import com.aqishi.toolbox.vault.PasswordAccount;
import com.aqishi.toolbox.vault.SecureClipboard;
import com.aqishi.toolbox.vault.VaultCrypto;
import com.aqishi.toolbox.vault.VaultException;
import com.aqishi.toolbox.vault.VaultListener;
import com.aqishi.toolbox.vault.VaultService;
import com.aqishi.toolbox.vault.VaultState;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

/** Password records UI backed exclusively by the shared encrypted vault. */
public final class AccountManagerPanel extends ToolPanel {
    private final VaultService service;
    private final SecureClipboard clipboard;
    private final List<PasswordAccount> accounts = new ArrayList<>();
    private final VaultListener listener = this::onVaultStateChanged;

    private JTable table;
    private DefaultTableModel model;
    private TableRowSorter<DefaultTableModel> sorter;
    private JTextField search;
    private JComboBox<String> category;
    private JButton edit;
    private JButton delete;
    private JButton copyUser;
    private JButton copyPassword;
    private JButton copyUrl;
    private JButton visit;
    private boolean updatingCategory;

    public AccountManagerPanel(VaultService service, SecureClipboard clipboard) {
        super("crypto", "account.manager",
                "密码管理", "账号密码", "密码簿", "Password Manager", "Account", "Keeper");
        this.service = Objects.requireNonNull(service, "service");
        this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
    }

    @Override
    protected JComponent build() {
        JPanel content = new JPanel(new BorderLayout(0, UIUtils.SPACE_SM));
        content.setBorder(UIUtils.CONTENT_PADDING);
        content.add(buildToolbar(), BorderLayout.NORTH);
        content.add(buildTable(), BorderLayout.CENTER);
        service.addListener(listener);
        refreshFromService();
        return new VaultAccessPanel(service, content);
    }

    private JComponent buildToolbar() {
        JPanel toolbar = new JPanel(new BorderLayout(UIUtils.SPACE_SM, 0));
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, UIUtils.SPACE_XS, 0));
        search = new JTextField(18);
        search.putClientProperty("JTextField.placeholderText", "搜索名称、账号或网址");
        search.getAccessibleContext().setAccessibleName("搜索账号");
        category = new JComboBox<>();
        category.setPreferredSize(new java.awt.Dimension(128, 32));
        filters.add(new JLabel("搜索："));
        filters.add(search);
        filters.add(new JLabel("分类："));
        filters.add(category);
        toolbar.add(filters, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, UIUtils.SPACE_XS, 0));
        JButton add = UIUtils.button("添加", 72);
        edit = UIUtils.button("编辑", 72);
        delete = UIUtils.button("删除", 72);
        add.addActionListener(event -> showEditor(null, -1));
        edit.addActionListener(event -> {
            int row = selectedModelRow();
            if (row >= 0) showEditor(accounts.get(row), row);
        });
        delete.addActionListener(event -> deleteSelected());
        actions.add(add);
        actions.add(edit);
        actions.add(delete);
        toolbar.add(actions, BorderLayout.EAST);

        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent event) { applyFilter(); }
        });
        category.addActionListener(event -> {
            if (!updatingCategory) applyFilter();
        });
        return toolbar;
    }

    private JComponent buildTable() {
        model = new DefaultTableModel(
                new Object[]{"名称", "账号", "密码", "网址"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        table = new JTable(model);
        table.setRowHeight(28);
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateActions();
                service.touch();
            }
        });

        JPanel wrapper = new JPanel(new BorderLayout(0, UIUtils.SPACE_XS));
        wrapper.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, UIUtils.SPACE_XS, 0));
        copyUser = UIUtils.button("复制账号", 92);
        copyPassword = UIUtils.button("复制密码", 92);
        copyUrl = UIUtils.button("复制网址", 92);
        visit = UIUtils.button("打开网址", 92);
        copyUser.addActionListener(event -> copySelected(1));
        copyPassword.addActionListener(event -> copySelected(2));
        copyUrl.addActionListener(event -> copySelected(3));
        visit.addActionListener(event -> visitSelected());
        actions.add(copyUser);
        actions.add(copyPassword);
        actions.add(copyUrl);
        actions.add(visit);
        wrapper.add(actions, BorderLayout.SOUTH);
        updateActions();
        return wrapper;
    }

    private void onVaultStateChanged(VaultState state) {
        Runnable refresh = this::refreshFromService;
        if (SwingUtilities.isEventDispatchThread()) refresh.run();
        else SwingUtilities.invokeLater(refresh);
    }

    private void refreshFromService() {
        accounts.clear();
        if (service.getState() == VaultState.UNLOCKED) {
            accounts.addAll(service.getPasswordAccounts());
        }
        if (model == null) return;
        model.setRowCount(0);
        for (PasswordAccount account : accounts) {
            model.addRow(new Object[]{account.getName(), account.getUsername(),
                    "********", account.getUrl()});
        }
        refreshCategories();
        updateActions();
    }

    private void refreshCategories() {
        if (category == null) return;
        Object selected = category.getSelectedItem();
        TreeSet<String> names = new TreeSet<>();
        for (PasswordAccount account : accounts) names.add(account.getName());
        DefaultComboBoxModel<String> categories = new DefaultComboBoxModel<>();
        categories.addElement("全部");
        for (String name : names) categories.addElement(name);
        updatingCategory = true;
        category.setModel(categories);
        category.setSelectedItem(selected == null ? "全部" : selected);
        if (category.getSelectedIndex() < 0) category.setSelectedIndex(0);
        updatingCategory = false;
        applyFilter();
    }

    private void applyFilter() {
        if (sorter == null) return;
        List<RowFilter<Object, Object>> filters = new ArrayList<>();
        String query = search == null ? "" : search.getText().trim();
        if (!query.isEmpty()) filters.add(RowFilter.regexFilter(
                "(?i)" + Pattern.quote(query), 0, 1, 3));
        Object selected = category == null ? null : category.getSelectedItem();
        if (selected != null && !"全部".equals(selected)) {
            filters.add(RowFilter.regexFilter("^" + Pattern.quote(selected.toString()) + "$", 0));
        }
        sorter.setRowFilter(filters.isEmpty() ? null : RowFilter.andFilter(filters));
        service.touch();
    }

    private void showEditor(PasswordAccount account, int index) {
        service.touch();
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(getView()),
                account == null ? "添加账号" : "编辑账号", Dialog.ModalityType.APPLICATION_MODAL);
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        JTextField name = new JTextField(account == null ? "" : account.getName(), 22);
        JTextField username = new JTextField(account == null ? "" : account.getUsername(), 22);
        JPasswordField password = new JPasswordField(account == null ? "" : account.getPassword(), 22);
        JTextField url = new JTextField(account == null ? "" : account.getUrl(), 22);
        addField(form, 0, "名称：", name);
        addField(form, 1, "账号：", username);
        addField(form, 2, "密码：", password);
        addField(form, 3, "网址：", url);
        JButton save = new JButton("保存");
        GridBagConstraints c = constraints(0, 4);
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.CENTER;
        form.add(save, c);
        save.addActionListener(event -> {
            String label = name.getText().trim();
            if (label.isEmpty()) {
                UIUtils.error(name, "名称不能为空");
                return;
            }
            char[] passwordChars = password.getPassword();
            String passwordValue = new String(passwordChars);
            VaultCrypto.wipe(passwordChars);
            PasswordAccount changed = new PasswordAccount(
                    label, username.getText().trim(), passwordValue, url.getText().trim());
            List<PasswordAccount> copy = new ArrayList<>(service.getPasswordAccounts());
            if (index < 0) copy.add(changed); else copy.set(index, changed);
            save.setEnabled(false);
            service.replacePasswordAccounts(copy).whenComplete((ignored, error) ->
                    SwingUtilities.invokeLater(() -> {
                        refreshFromService();
                        if (error == null) dialog.dispose();
                        else {
                            save.setEnabled(true);
                            showSaveError(error);
                        }
                    }));
        });
        dialog.setContentPane(form);
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(getView());
        dialog.setVisible(true);
    }

    private void deleteSelected() {
        int row = selectedModelRow();
        if (row < 0) return;
        PasswordAccount account = accounts.get(row);
        if (JOptionPane.showConfirmDialog(getView(),
                "确定删除账号 [" + account.getName() + "]？", "确认删除",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                != JOptionPane.YES_OPTION) return;
        List<PasswordAccount> copy = new ArrayList<>(service.getPasswordAccounts());
        copy.remove(row);
        service.replacePasswordAccounts(copy).whenComplete((ignored, error) ->
                SwingUtilities.invokeLater(() -> {
                    refreshFromService();
                    if (error != null) showSaveError(error);
                }));
    }

    private void copySelected(int column) {
        int row = selectedModelRow();
        if (row < 0) return;
        PasswordAccount account = accounts.get(row);
        String value = column == 1 ? account.getUsername()
                : column == 2 ? account.getPassword() : account.getUrl();
        try {
            clipboard.copySensitive(value);
            service.touch();
        } catch (VaultException error) {
            UIUtils.error(getView(), I18n.get("vault.clipboard.error"));
        }
    }

    private void visitSelected() {
        int row = selectedModelRow();
        if (row < 0) return;
        String url = accounts.get(row).getUrl().trim();
        if (url.isEmpty()) return;
        try {
            URI uri = new URI(url.contains("://") ? url : "https://" + url);
            Desktop.getDesktop().browse(uri);
            service.touch();
        } catch (Exception error) {
            UIUtils.error(getView(), "无法打开网址");
        }
    }

    private int selectedModelRow() {
        if (table == null || table.getSelectedRow() < 0) return -1;
        return table.convertRowIndexToModel(table.getSelectedRow());
    }

    private void updateActions() {
        boolean selected = selectedModelRow() >= 0;
        for (JButton button : Arrays.asList(
                edit, delete, copyUser, copyPassword, copyUrl, visit)) {
            if (button != null) button.setEnabled(selected);
        }
    }

    private void showSaveError(Throwable error) {
        Throwable cause = error instanceof CompletionException ? error.getCause() : error;
        UIUtils.error(getView(), cause == null
                ? I18n.get("vault.error.generic") : I18n.get("vault.error.generic"));
    }

    private static void addField(JPanel form, int row, String label, JComponent field) {
        GridBagConstraints left = constraints(0, row);
        left.weightx = 0;
        form.add(new JLabel(label), left);
        GridBagConstraints right = constraints(1, row);
        right.weightx = 1;
        form.add(field, right);
    }

    private static GridBagConstraints constraints(int column, int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = column;
        c.gridy = row;
        c.insets = new Insets(5, 6, 5, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        return c;
    }
}
