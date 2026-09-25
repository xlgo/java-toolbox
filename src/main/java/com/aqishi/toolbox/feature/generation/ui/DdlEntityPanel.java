package com.aqishi.toolbox.feature.generation.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.catalog.ToolDescriptor;
import com.aqishi.toolbox.feature.generation.domain.DdlParseResult;
import com.aqishi.toolbox.feature.generation.domain.DdlParser;
import com.aqishi.toolbox.feature.generation.domain.EntityOptions;
import com.aqishi.toolbox.feature.generation.domain.EntityOptions.Annotations;
import com.aqishi.toolbox.feature.generation.domain.EntityOptions.JpaNamespace;
import com.aqishi.toolbox.feature.generation.domain.EntityOptions.Style;
import com.aqishi.toolbox.feature.generation.domain.JavaEntityGenerator;
import com.aqishi.toolbox.feature.generation.domain.JavaEntityGenerator.FileKind;
import com.aqishi.toolbox.feature.generation.domain.JavaEntityGenerator.GeneratedFile;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.Errors;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * DDL 转 Java 实体面板：左侧粘贴建表语句并调整选项，右侧实时预览每张表生成的源码。
 *
 * <p>输入与选项的任何变化都经过一个短延时再重新生成，连续敲键时只算最后一次。
 * 解析与生成放在后台线程：粘贴一整份带 INSERT 的库导出时，分词可能要几百毫秒，
 * 放在事件线程会让输入框卡顿。每次生成带一个序号，过时的结果直接丢弃。</p>
 */
public class DdlEntityPanel extends ToolPanel implements ManagedResourceOwner {

    private static final int DEBOUNCE_MS = 350;

    private static final Style[] STYLES = {Style.LOMBOK, Style.PLAIN, Style.RECORD};

    /** 示例 DDL：MySQL 导出风格，覆盖自增主键、注释、索引、表选项与联合主键 */
    static final String SAMPLE_DDL = "-- MySQL sample\n"
            + "CREATE TABLE `t_user` (\n"
            + "  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'Primary key',\n"
            + "  `user_name` varchar(64) NOT NULL COMMENT 'Login name',\n"
            + "  `email` varchar(128) DEFAULT NULL COMMENT 'Email address',\n"
            + "  `status` enum('active','locked','deleted') NOT NULL DEFAULT 'active',\n"
            + "  `balance` decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT 'Account balance',\n"
            + "  `is_admin` tinyint(1) NOT NULL DEFAULT '0',\n"
            + "  `birthday` date DEFAULT NULL,\n"
            + "  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,\n"
            + "  `updated_at` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,\n"
            + "  PRIMARY KEY (`id`),\n"
            + "  UNIQUE KEY `uk_user_name` (`user_name`),\n"
            + "  KEY `idx_created_at` (`created_at`)\n"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User account';\n"
            + "\n"
            + "CREATE TABLE `t_user_role` (\n"
            + "  `user_id` bigint unsigned NOT NULL,\n"
            + "  `role_code` varchar(32) NOT NULL COMMENT 'Role code',\n"
            + "  `granted_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,\n"
            + "  PRIMARY KEY (`user_id`, `role_code`)\n"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User to role mapping';\n";

    private final DdlParser parser;
    private final JavaEntityGenerator generator;

    private JTextArea ddlArea;
    private JTextField packageField;
    private JTextField prefixField;
    private JTextField suffixField;
    private JComboBox<String> styleCombo;
    private JComboBox<String> annotationCombo;
    private JCheckBox builderCheck;
    private JCheckBox noArgsCheck;
    private JCheckBox allArgsCheck;
    private JCheckBox toStringCheck;
    private JCheckBox alwaysColumnCheck;
    private JCheckBox serializableCheck;
    private JCheckBox javadocCheck;
    private JCheckBox legacyDateCheck;
    private JCheckBox bigIntegerCheck;
    private JCheckBox xmlCheck;

    private JComboBox<String> fileCombo;
    private JTextArea codeArea;
    private JTextArea warningArea;
    private JScrollPane warningScroll;
    private JLabel statusLabel;
    private JButton copyBtn;
    private JButton copyAllBtn;
    private JButton saveBtn;

    private Timer debounce;
    /** 生成序号：只有最新一次的结果会被展示 */
    private int generation;
    private List<GeneratedFile> files = List.of();
    /** 重建文件下拉框时抑制选择事件 */
    private boolean updatingFiles;
    private File lastDirectory;

    public DdlEntityPanel() {
        this(ToolCatalog.DDL_ENTITY, new DdlParser(), new JavaEntityGenerator());
    }

    public DdlEntityPanel(ToolDescriptor descriptor, DdlParser parser, JavaEntityGenerator generator) {
        super(Objects.requireNonNull(descriptor, "descriptor"));
        this.parser = Objects.requireNonNull(parser, "parser");
        this.generator = Objects.requireNonNull(generator, "generator");
    }

    @Override
    protected JComponent build() {
        debounce = new Timer(DEBOUNCE_MS, event -> regenerate());
        debounce.setRepeats(false);

        JPanel root = Layouts.page();
        JSplitPane left = Layouts.splitVertical(buildInputCard(), buildOptionsCard(), 0.6, 0.55);
        // 两侧都给一个保底宽度，窄窗口下分隔条不会把某一侧压成一条缝
        left.setMinimumSize(new Dimension(300, 0));
        root.add(Layouts.splitHorizontal(left, buildOutputCard(), 0.45, 0.45), BorderLayout.CENTER);

        syncOptionStates();
        ddlArea.setText(SAMPLE_DDL);
        ddlArea.setCaretPosition(0);
        // 文本变化已排队了一次防抖；首屏不必等待，直接生成
        debounce.stop();
        regenerate();
        return root;
    }

    // ==========================================
    // 布局
    // ==========================================

    private Card buildInputCard() {
        ddlArea = Fields.area(14, 40);
        ddlArea.setLineWrap(false);
        ddlArea.putClientProperty("JTextField.placeholderText", I18n.get("tool.ddlentity.placeholder.ddl"));
        ddlArea.getDocument().addDocumentListener(new DocumentChange());

        JButton sampleBtn = Buttons.snug(I18n.get("tool.ddlentity.btn.sample"));
        JButton clearBtn = Buttons.snug(I18n.get("tool.ddlentity.btn.clear"));
        sampleBtn.addActionListener(event -> {
            ddlArea.setText(SAMPLE_DDL);
            ddlArea.setCaretPosition(0);
        });
        clearBtn.addActionListener(event -> ddlArea.setText(""));

        Card card = Card.flush(I18n.get("tool.ddlentity.card.ddl"));
        card.setContent(Fields.scroll(ddlArea));
        card.addHeaderAction(clearBtn);
        card.addHeaderAction(sampleBtn);
        return card;
    }

    private Card buildOptionsCard() {
        packageField = Fields.mono("");
        packageField.putClientProperty("JTextField.placeholderText", "com.example.entity");
        prefixField = Fields.mono("t_,sys_");
        prefixField.setToolTipText(I18n.get("tool.ddlentity.label.prefix.tip"));
        suffixField = Fields.mono("");
        suffixField.putClientProperty("JTextField.placeholderText", "DO / Entity");

        styleCombo = Fields.combo(new String[]{
                I18n.get("tool.ddlentity.style.lombok"),
                I18n.get("tool.ddlentity.style.plain"),
                I18n.get("tool.ddlentity.style.record")});
        annotationCombo = Fields.combo(new String[]{
                I18n.get("tool.ddlentity.annotation.none"),
                I18n.get("tool.ddlentity.annotation.jpaJakarta"),
                I18n.get("tool.ddlentity.annotation.jpaJavax"),
                I18n.get("tool.ddlentity.annotation.mybatisPlus")});

        // 注解名本身就是代码，不翻译
        builderCheck = Fields.check("@Builder", false);
        noArgsCheck = Fields.check("@NoArgsConstructor", false);
        allArgsCheck = Fields.check("@AllArgsConstructor", false);
        toStringCheck = Fields.check(I18n.get("tool.ddlentity.check.toString"), true);
        alwaysColumnCheck = Fields.check(I18n.get("tool.ddlentity.check.alwaysColumn"), false);
        serializableCheck = Fields.check(I18n.get("tool.ddlentity.check.serializable"), false);
        javadocCheck = Fields.check(I18n.get("tool.ddlentity.check.javadoc"), true);
        legacyDateCheck = Fields.check(I18n.get("tool.ddlentity.check.legacyDate"), false);
        bigIntegerCheck = Fields.check(I18n.get("tool.ddlentity.check.bigInteger"), false);
        xmlCheck = Fields.check(I18n.get("tool.ddlentity.check.mybatisXml"), false);

        FormGrid form = new FormGrid();
        form.row(I18n.get("tool.ddlentity.label.package"), packageField);
        form.row(I18n.get("tool.ddlentity.label.prefix"), prefixField);
        form.row(I18n.get("tool.ddlentity.label.suffix"), suffixField);
        form.row(I18n.get("tool.ddlentity.label.style"), styleCombo);
        form.row(I18n.get("tool.ddlentity.label.styleOptions"),
                Layouts.wrapRow(builderCheck, noArgsCheck, allArgsCheck, toStringCheck));
        form.row(I18n.get("tool.ddlentity.label.annotations"), annotationCombo);
        form.row("", Layouts.wrapRow(alwaysColumnCheck));
        form.row(I18n.get("tool.ddlentity.label.extras"),
                Layouts.wrapRow(serializableCheck, javadocCheck, legacyDateCheck, bigIntegerCheck, xmlCheck));
        form.caption(I18n.get("tool.ddlentity.caption.options"));
        form.glue();

        for (JTextField field : List.of(packageField, prefixField, suffixField)) {
            field.getDocument().addDocumentListener(new DocumentChange());
        }
        styleCombo.addActionListener(event -> {
            syncOptionStates();
            scheduleRegenerate();
        });
        annotationCombo.addActionListener(event -> {
            syncOptionStates();
            scheduleRegenerate();
        });
        for (JCheckBox box : List.of(builderCheck, noArgsCheck, allArgsCheck, toStringCheck, alwaysColumnCheck,
                serializableCheck, javadocCheck, legacyDateCheck, bigIntegerCheck, xmlCheck)) {
            box.addActionListener(event -> scheduleRegenerate());
        }

        Card card = Card.titled(I18n.get("tool.ddlentity.card.options"));
        card.setContent(Fields.scrollVertical(form));
        return card;
    }

    private Card buildOutputCard() {
        codeArea = Fields.output(20, 50);
        codeArea.setLineWrap(false);
        fileCombo = Fields.combo(new String[0], 220);
        fileCombo.addActionListener(event -> {
            if (!updatingFiles) {
                showSelectedFile();
            }
        });

        copyBtn = Buttons.snug(I18n.get("tool.ddlentity.btn.copy"));
        copyAllBtn = Buttons.snug(I18n.get("tool.ddlentity.btn.copyAll"));
        saveBtn = Buttons.primary(I18n.get("tool.ddlentity.btn.save"));
        copyBtn.addActionListener(event -> {
            String text = codeArea.getText();
            if (text != null && !text.isEmpty()) {
                UIUtils.copyToClipboard(text);
            }
        });
        copyAllBtn.addActionListener(event -> {
            if (!files.isEmpty()) {
                UIUtils.copyToClipboard(joinAll(files));
            }
        });
        saveBtn.addActionListener(event -> saveToDirectory());

        statusLabel = Fields.caption(I18n.get("tool.ddlentity.status.ready"));
        warningArea = Fields.output(4, 50);
        warningArea.setForeground(Tokens.warning());
        warningScroll = Fields.scroll(warningArea);
        warningScroll.setVisible(false);

        JPanel footer = Layouts.box(0, Tokens.SPACE_XS);
        footer.add(statusLabel, BorderLayout.NORTH);
        footer.add(warningScroll, BorderLayout.CENTER);

        // 文件选择与按钮放在内容区的可换行工具条里，而不是卡片标题栏：
        // 标题栏放不下四个控件，窄窗口下会把标题挤没，并把整张卡片的最小宽度撑得很大
        JPanel toolbar = Layouts.wrapRow(Tokens.SPACE_SM, Tokens.SPACE_XS, fileCombo, copyBtn, copyAllBtn, saveBtn);
        toolbar.setBorder(KitBorders.padding(Tokens.SPACE_SM, Tokens.CARD_PADDING, Tokens.SPACE_SM, Tokens.CARD_PADDING));
        JPanel body = Layouts.box();
        body.add(toolbar, BorderLayout.NORTH);
        body.add(Fields.scroll(codeArea), BorderLayout.CENTER);

        Card card = Card.flush(I18n.get("tool.ddlentity.card.output"));
        card.setContent(body);
        card.setFooter(footer);
        card.setMinimumSize(new Dimension(320, 0));
        return card;
    }

    /** 只在对应风格/注解下有意义的选项，其余时候置灰，避免用户以为勾了没生效 */
    private void syncOptionStates() {
        Style style = selectedStyle();
        boolean lombok = style == Style.LOMBOK;
        builderCheck.setEnabled(lombok);
        noArgsCheck.setEnabled(lombok);
        allArgsCheck.setEnabled(lombok);
        toStringCheck.setEnabled(style == Style.PLAIN);
        int annotation = annotationCombo.getSelectedIndex();
        alwaysColumnCheck.setEnabled((annotation == 1 || annotation == 2) && style != Style.RECORD);
    }

    // ==========================================
    // 生成
    // ==========================================

    private void scheduleRegenerate() {
        if (debounce != null) {
            debounce.restart();
        }
    }

    /** 解析 + 生成的结果，在后台线程算好后交给事件线程展示 */
    private record Outcome(DdlParseResult parse, JavaEntityGenerator.Result result) {
    }

    private Outcome compute(String ddl, EntityOptions options) {
        DdlParseResult parsed = parser.parse(ddl);
        return new Outcome(parsed, generator.generate(parsed.tables(), options));
    }

    private void regenerate() {
        final int ticket = ++generation;
        final String ddl = ddlArea.getText();
        final EntityOptions options = readOptions();
        new SwingWorker<Outcome, Void>() {
            @Override
            protected Outcome doInBackground() {
                return compute(ddl, options);
            }

            @Override
            protected void done() {
                if (ticket != generation) {
                    return;
                }
                try {
                    show(get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    Errors.log("DDL entity generation failed", e.getCause());
                    setStatus(I18n.get("tool.ddlentity.status.failed", Errors.describeRoot(e)), Tokens.danger());
                }
            }
        }.execute();
    }

    /** 同步生成并展示；供测试与需要立即拿到结果的场合使用 */
    void regenerateNow() {
        ++generation;
        show(compute(ddlArea.getText(), readOptions()));
    }

    private EntityOptions readOptions() {
        int annotation = annotationCombo.getSelectedIndex();
        return EntityOptions.builder()
                .packageName(packageField.getText())
                .tablePrefixes(prefixField.getText())
                .classSuffix(suffixField.getText())
                .style(selectedStyle())
                .lombokBuilder(builderCheck.isSelected())
                .lombokNoArgsConstructor(noArgsCheck.isSelected())
                .lombokAllArgsConstructor(allArgsCheck.isSelected())
                .plainToString(toStringCheck.isSelected())
                .annotations(annotation == 1 || annotation == 2 ? Annotations.JPA
                        : annotation == 3 ? Annotations.MYBATIS_PLUS : Annotations.NONE)
                .jpaNamespace(annotation == 2 ? JpaNamespace.JAVAX : JpaNamespace.JAKARTA)
                .jpaAlwaysColumn(alwaysColumnCheck.isSelected())
                .serializable(serializableCheck.isSelected())
                .javadoc(javadocCheck.isSelected())
                .legacyDate(legacyDateCheck.isSelected())
                .unsignedBigintAsBigInteger(bigIntegerCheck.isSelected())
                .mybatisXml(xmlCheck.isSelected())
                .build();
    }

    private Style selectedStyle() {
        int index = styleCombo.getSelectedIndex();
        return index >= 0 && index < STYLES.length ? STYLES[index] : Style.LOMBOK;
    }

    private void show(Outcome outcome) {
        files = outcome.result().files();

        // 重新生成后尽量停留在用户正在看的那个文件上
        Object previous = fileCombo.getSelectedItem();
        updatingFiles = true;
        try {
            DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
            for (GeneratedFile file : files) {
                model.addElement(file.fileName());
            }
            fileCombo.setModel(model);
            if (previous != null && model.getIndexOf(previous) >= 0) {
                fileCombo.setSelectedItem(previous);
            } else if (model.getSize() > 0) {
                fileCombo.setSelectedIndex(0);
            }
        } finally {
            updatingFiles = false;
        }
        showSelectedFile();

        boolean hasFiles = !files.isEmpty();
        fileCombo.setEnabled(hasFiles);
        copyBtn.setEnabled(hasFiles);
        copyAllBtn.setEnabled(hasFiles);
        saveBtn.setEnabled(hasFiles);

        List<String> warnings = describeWarnings(outcome);
        warningArea.setText(String.join("\n", warnings));
        warningArea.setCaretPosition(0);
        boolean visibilityChanged = warningScroll.isVisible() != !warnings.isEmpty();
        warningScroll.setVisible(!warnings.isEmpty());
        if (visibilityChanged) {
            warningScroll.getParent().revalidate();
        }

        int tables = outcome.parse().tables().size();
        if (ddlArea.getText().isBlank()) {
            setStatus(I18n.get("tool.ddlentity.status.ready"), Tokens.mutedForeground());
        } else if (tables == 0) {
            setStatus(I18n.get("tool.ddlentity.status.noTable"), Tokens.danger());
        } else if (warnings.isEmpty()) {
            setStatus(I18n.get("tool.ddlentity.status.summary", tables, files.size()), Tokens.success());
        } else {
            setStatus(I18n.get("tool.ddlentity.status.summaryWarnings", tables, files.size(), warnings.size()),
                    Tokens.warning());
        }
    }

    private void showSelectedFile() {
        int index = fileCombo.getSelectedIndex();
        if (index >= 0 && index < files.size()) {
            codeArea.setText(files.get(index).content());
        } else {
            codeArea.setText("");
        }
        codeArea.setCaretPosition(0);
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
    }

    /** 解析告警带位置；生成告警按类别本地化。去重后按出现顺序展示 */
    private List<String> describeWarnings(Outcome outcome) {
        Set<String> lines = new LinkedHashSet<>();
        for (DdlParseResult.Warning warning : outcome.parse().warnings()) {
            lines.add(I18n.get("tool.ddlentity.warning.parse",
                    String.valueOf(warning.line()), String.valueOf(warning.column()), warning.message()));
        }
        for (JavaEntityGenerator.Warning warning : outcome.result().warnings()) {
            lines.add(describe(warning));
        }
        return new ArrayList<>(lines);
    }

    private static String describe(JavaEntityGenerator.Warning w) {
        String table = w.table() == null ? "" : w.table();
        String column = w.column() == null ? "" : w.column();
        String detail = w.detail() == null ? "" : w.detail();
        switch (w.code()) {
            case UNKNOWN_TYPE:
                return I18n.get("tool.ddlentity.warning.unknownType", table, column, detail);
            case UNSIGNED_BIGINT:
                return I18n.get("tool.ddlentity.warning.unsignedBigint", table, column);
            case COMPOSITE_KEY_JPA:
                return I18n.get("tool.ddlentity.warning.compositeJpa", table, detail);
            case COMPOSITE_KEY_MYBATIS_PLUS:
                return I18n.get("tool.ddlentity.warning.compositeMybatisPlus", table, detail);
            case NO_PRIMARY_KEY_JPA:
                return I18n.get("tool.ddlentity.warning.noPrimaryKey", table);
            case RECORD_WITH_JPA:
                return I18n.get("tool.ddlentity.warning.recordJpa");
            case INVALID_PACKAGE:
                return I18n.get("tool.ddlentity.warning.invalidPackage", detail);
            case DUPLICATE_CLASS:
                return I18n.get("tool.ddlentity.warning.duplicateClass", table, detail);
            case DUPLICATE_FIELD:
                return I18n.get("tool.ddlentity.warning.duplicateField", table, column, detail);
            default:
                return w.code().name();
        }
    }

    private static String joinAll(List<GeneratedFile> files) {
        StringBuilder out = new StringBuilder();
        for (GeneratedFile file : files) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(file.kind() == FileKind.JAVA
                    ? "// ===== " + file.fileName() + " =====\n"
                    : "<!-- ===== " + file.fileName() + " ===== -->\n");
            out.append(file.content());
        }
        return out.toString();
    }

    // ==========================================
    // 保存
    // ==========================================

    private void saveToDirectory() {
        if (files.isEmpty()) {
            return;
        }
        JFileChooser chooser = new JFileChooser(lastDirectory);
        chooser.setDialogTitle(I18n.get("tool.ddlentity.dialog.chooseDir"));
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showSaveDialog(getView()) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return;
        }
        File directory = chooser.getSelectedFile();
        lastDirectory = directory;
        Path dir = directory.toPath();

        List<String> existing = new ArrayList<>();
        for (GeneratedFile file : files) {
            if (Files.exists(dir.resolve(file.fileName()))) {
                existing.add(file.fileName());
            }
        }
        if (!existing.isEmpty() && !UIUtils.confirm(getView(),
                I18n.get("tool.ddlentity.confirm.overwrite", existing.size(), String.join("\n", existing)),
                I18n.get("tool.ddlentity.confirm.overwrite.title"))) {
            return;
        }
        try {
            Files.createDirectories(dir);
            for (GeneratedFile file : files) {
                Files.writeString(dir.resolve(file.fileName()), file.content(), StandardCharsets.UTF_8);
            }
            UIUtils.info(getView(), I18n.get("tool.ddlentity.info.saved", files.size(), dir.toString()));
        } catch (IOException | RuntimeException e) {
            Errors.report(getView(), I18n.get("tool.ddlentity.error.save"), e);
        }
    }

    // ==========================================
    // 生命周期
    // ==========================================

    @Override
    public void closeResources() {
        Timer running = debounce;
        debounce = null;
        if (running != null) {
            running.stop();
        }
        // 让仍在后台计算的结果作废
        generation++;
    }

    /** 测试用：当前代码区内容 */
    String codeText() {
        return codeArea.getText();
    }

    /** 测试用：当前可选的文件名 */
    List<String> fileNames() {
        List<String> names = new ArrayList<>();
        for (GeneratedFile file : files) {
            names.add(file.fileName());
        }
        return names;
    }

    /** 测试用：选择代码风格 */
    void selectStyle(int index) {
        styleCombo.setSelectedIndex(index);
    }

    /** 任意文本变化都排队一次防抖生成 */
    private final class DocumentChange implements DocumentListener {
        @Override
        public void insertUpdate(DocumentEvent e) {
            scheduleRegenerate();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            scheduleRegenerate();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            scheduleRegenerate();
        }
    }
}
