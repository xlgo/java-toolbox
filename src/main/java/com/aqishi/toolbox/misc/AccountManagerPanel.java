package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.VaultAccessPanel;
import com.aqishi.toolbox.ui.kit.ActionBar;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
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
    /** 密码列的掩码：详情区与表格用同一份，明文永远不进入界面 */
    private static final String MASK = "********";
    /** 未选中任何账号时详情区显示的占位符 */
    private static final String NO_VALUE = "—";

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
    private JLabel detailName;
    private JLabel detailUsername;
    private JLabel detailPassword;
    private JLabel detailUrl;
    private boolean updatingCategory;

    public AccountManagerPanel(VaultService service, SecureClipboard clipboard) {
        super("crypto", "account.manager",
                "密码管理", "账号密码", "密码簿", "Password Manager", "Account", "Keeper");
        this.service = Objects.requireNonNull(service, "service");
        this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();
        // 左表格右详情：账号条数多时列表要占大头，右侧只承担「看选中项 + 取值」两件事
        root.add(Layouts.splitHorizontal(buildListCard(), buildDetailCard(), 0.7),
                BorderLayout.CENTER);
        service.addListener(listener);
        refreshFromService();
        return new VaultAccessPanel(service, root);
    }

    /** 账号列表卡片：标题右侧放增删改，卡片内顶部放搜索与分类过滤，表格铺满剩余空间 */
    private JComponent buildListCard() {
        search = Fields.text("", "搜索名称、账号或网址");
        search.getAccessibleContext().setAccessibleName("搜索账号");
        category = Fields.combo(new String[0], 128);

        ActionBar filters = new ActionBar();
        filters.left(Fields.label("搜索："));
        filters.left(search);
        filters.right(Fields.label("分类："));
        filters.right(category);
        JPanel filterRow = Layouts.box();
        filterRow.add(filters, BorderLayout.CENTER);
        // 过滤条属于列表卡片的一部分，用细线跟表格分开；卡片是 flush 型，内边距得自己补
        filterRow.setBorder(BorderFactory.createCompoundBorder(
                KitBorders.lineSubtle(0, 0, 1, 0),
                KitBorders.padding(Tokens.SPACE_SM, Tokens.CARD_PADDING,
                        Tokens.SPACE_SM, Tokens.CARD_PADDING)));

        model = new DefaultTableModel(
                new Object[]{"名称", "账号", "密码", "网址"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        table = new JTable(model);
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateActions();
                service.touch();
            }
        });

        JPanel body = Layouts.box();
        body.add(filterRow, BorderLayout.NORTH);
        body.add(Fields.scroll(table), BorderLayout.CENTER);

        JButton add = Buttons.primary("添加");
        edit = Buttons.secondary("编辑");
        delete = Buttons.danger("删除");
        add.addActionListener(event -> showEditor(null, -1));
        edit.addActionListener(event -> {
            int row = selectedModelRow();
            if (row >= 0) showEditor(accounts.get(row), row);
        });
        delete.addActionListener(event -> deleteSelected());

        Card card = Card.flush("账号列表");
        card.setContent(body);
        // addHeaderAction 从左往右排，主操作最后添加才落在最右侧
        card.addHeaderAction(delete);
        card.addHeaderAction(edit);
        card.addHeaderAction(add);

        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent event) { applyFilter(); }
        });
        category.addActionListener(event -> {
            if (!updatingCategory) applyFilter();
        });
        return card;
    }

    /**
     * 账号详情卡片：只读展示选中行，密码位始终是掩码。
     *
     * <p>取值动作全部走剪贴板（{@link SecureClipboard}），所以四个按钮排成 2×2，
     * 分栏被拖窄时也不会被裁掉。</p>
     */
    private JComponent buildDetailCard() {
        detailName = detailValue();
        detailUsername = detailValue();
        detailPassword = detailValue();
        detailUrl = detailValue();

        FormGrid form = new FormGrid();
        form.row("名称：", detailName);
        form.row("账号：", detailUsername);
        form.row("密码：", detailPassword);
        form.row("网址：", detailUrl);

        copyUser = Buttons.secondary("复制账号");
        copyPassword = Buttons.secondary("复制密码");
        copyUrl = Buttons.secondary("复制网址");
        visit = Buttons.secondary("打开网址");
        copyUser.addActionListener(event -> copySelected(1));
        copyPassword.addActionListener(event -> copySelected(2));
        copyUrl.addActionListener(event -> copySelected(3));
        visit.addActionListener(event -> visitSelected());
        JPanel actions = Layouts.rows(Tokens.SPACE_SM,
                Layouts.columns(Tokens.SPACE_SM, copyUser, copyPassword),
                Layouts.columns(Tokens.SPACE_SM, copyUrl, visit));

        // 表单和取值按钮一起贴顶：分栏把卡片拉高时空白落在下方，
        // 按钮不会被吊到卡片底部、离开它所描述的字段
        JPanel body = Layouts.box();
        body.add(Layouts.stack(Tokens.SPACE_LG, form, actions), BorderLayout.NORTH);

        Card card = Card.titled("账号详情", "只读预览，密码不显示明文");
        card.setContent(body);
        updateActions();
        return card;
    }

    private static JLabel detailValue() {
        JLabel label = new JLabel(NO_VALUE);
        label.setFont(Tokens.fontBody());
        label.setForeground(Tokens.foreground());
        return label;
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
                    MASK, account.getUrl()});
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
        JPanel form = Layouts.box(0, Tokens.SPACE_LG);
        form.setOpaque(true);
        form.setBorder(KitBorders.padding(Tokens.SPACE_LG));
        JTextField name = new JTextField(account == null ? "" : account.getName(), 22);
        JTextField username = new JTextField(account == null ? "" : account.getUsername(), 22);
        JPasswordField password = new JPasswordField(account == null ? "" : account.getPassword(), 22);
        JTextField url = new JTextField(account == null ? "" : account.getUrl(), 22);
        FormGrid fields = new FormGrid();
        fields.row("名称：", name);
        fields.row("账号：", username);
        fields.row("密码：", password);
        fields.row("网址：", url);
        form.add(fields, BorderLayout.CENTER);
        JButton save = Buttons.primary("保存");
        ActionBar actions = new ActionBar();
        actions.right(save);
        form.add(actions, BorderLayout.SOUTH);
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
        int row = selectedModelRow();
        boolean selected = row >= 0 && row < accounts.size();
        for (JButton button : Arrays.asList(
                edit, delete, copyUser, copyPassword, copyUrl, visit)) {
            if (button != null) button.setEnabled(selected);
        }
        updateDetail(selected ? accounts.get(row) : null);
    }

    /** 详情区跟随选中行刷新；密码位固定为掩码，明文只经由 SecureClipboard 流出 */
    private void updateDetail(PasswordAccount account) {
        if (detailName == null) return;
        detailName.setText(account == null ? NO_VALUE : account.getName());
        detailUsername.setText(account == null ? NO_VALUE : account.getUsername());
        detailPassword.setText(account == null ? NO_VALUE : MASK);
        detailUrl.setText(account == null ? NO_VALUE : account.getUrl());
    }

    private void showSaveError(Throwable error) {
        Throwable cause = error instanceof CompletionException ? error.getCause() : error;
        UIUtils.error(getView(), cause == null
                ? I18n.get("vault.error.generic") : I18n.get("vault.error.generic"));
    }
}
