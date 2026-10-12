package com.aqishi.toolbox.feature.codec.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.codec.domain.TextDiffService;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.UIUtils;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.Errors;

import javax.swing.*;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 文本对比工具面板。
 */
public class TextDiffPanel extends ToolPanel implements ManagedResourceOwner {
    private final AtomicLong generation = new AtomicLong();
    private volatile SwingWorker<StyledDocument, Void> worker;
    private volatile boolean closed;

    public TextDiffPanel() {
        super(ToolCatalog.TEXT_DIFF);
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // ===== 上方：两份原文左右并排，等宽分隔 =====
        JTextArea leftArea = Fields.area(8, 20);
        leftArea.setText("Hello World\nJava is great\nWe love programming\nGood morning!");
        Card leftCard = Card.flush("原文本 (A)");
        leftCard.setContent(Fields.scroll(leftArea));

        JTextArea rightArea = Fields.area(8, 20);
        rightArea.setText("Hello World\nKotlin is great\nWe love programming\nGood evening!\nHave a nice day!");
        Card rightCard = Card.flush("修改后文本 (B)");
        rightCard.setContent(Fields.scroll(rightArea));

        JSplitPane inputSplit = Layouts.splitHorizontal(leftCard, rightCard, 0.5);

        // ===== 下方：对比结果 =====
        JTextPane diffPane = new JTextPane();
        diffPane.setFont(Tokens.fontMono());
        diffPane.setEditable(false);
        // JTextPane 自身没有内边距，补一层与 Fields.area 一致的留白，免得文字贴着卡片描边
        diffPane.setBorder(KitBorders.padding(Tokens.SPACE_SM));

        JCheckBox ignoreCaseCheck = Fields.check("忽略大小写", false);
        JCheckBox ignoreSpaceCheck = Fields.check("忽略空白", false);

        JButton compare = Buttons.primary("对比差异");
        JButton cancel = Buttons.secondary(I18n.get("devtools.cancel"));
        cancel.setEnabled(false);
        JButton clear = Buttons.danger("清空");
        Card resultCard = Card.flush("对比结果 ( + 新增, - 删除 )");
        resultCard.setContent(Fields.scroll(diffPane));
        
        resultCard.addHeaderAction(ignoreCaseCheck);
        resultCard.addHeaderAction(ignoreSpaceCheck);
        resultCard.addHeaderAction(clear);
        resultCard.addHeaderAction(cancel);
        resultCard.addHeaderAction(compare);

        JSplitPane mainSplit = Layouts.splitVertical(inputSplit, resultCard, 0.35);
        root.add(mainSplit, BorderLayout.CENTER);

        // 按钮事件
        compare.addActionListener(e -> {
            if (closed || worker != null) return;
            String before = leftArea.getText(), after = rightArea.getText();
            boolean ignoreCase = ignoreCaseCheck.isSelected(), ignoreSpace = ignoreSpaceCheck.isSelected();
            SimpleAttributeSet deleteStyle = attributes(Tokens.danger());
            SimpleAttributeSet insertStyle = attributes(Tokens.success());
            SimpleAttributeSet equalStyle = attributes(Tokens.foreground());
            long ticket = generation.incrementAndGet();
            compare.setEnabled(false);
            cancel.setEnabled(true);
            worker = new SwingWorker<>() {
                @Override protected StyledDocument doInBackground() throws Exception {
                    List<TextDiffService.Entry> entries = new TextDiffService().compare(before, after, ignoreCase, ignoreSpace);
                    // Construct an unattached document off the EDT, including its styled runs.
                    DefaultStyledDocument document = new DefaultStyledDocument();
                    StringBuilder run = new StringBuilder();
                    TextDiffService.Kind kind = null;
                    for (var entry : entries) {
                        if (isCancelled()) return document;
                        if (kind != null && kind != entry.kind()) {
                            document.insertString(document.getLength(), run.toString(),
                                    kind == TextDiffService.Kind.DELETE ? deleteStyle : kind == TextDiffService.Kind.INSERT ? insertStyle : equalStyle);
                            run.setLength(0);
                        }
                        kind = entry.kind();
                        run.append(kind == TextDiffService.Kind.DELETE ? "- " : kind == TextDiffService.Kind.INSERT ? "+ " : "  ")
                                .append(entry.line()).append('\n');
                    }
                    if (kind != null) document.insertString(document.getLength(), run.toString(),
                            kind == TextDiffService.Kind.DELETE ? deleteStyle : kind == TextDiffService.Kind.INSERT ? insertStyle : equalStyle);
                    return document;
                }
                @Override protected void done() {
                    if (closed || generation.get() != ticket) return;
                    try {
                        if (!isCancelled()) diffPane.setDocument(get());
                    } catch (Exception error) {
                        UIUtils.error(root, I18n.get("diff.failed", Errors.describeRoot(error)));
                    } finally {
                        worker = null;
                        compare.setEnabled(true);
                        cancel.setEnabled(false);
                    }
                }
            };
            worker.execute();
        });

        cancel.addActionListener(e -> { if (worker != null) worker.cancel(true); });
        clear.addActionListener(e -> {
            generation.incrementAndGet();
            if (worker != null) worker.cancel(true);
            worker = null;
            compare.setEnabled(true);
            cancel.setEnabled(false);
            leftArea.setText("");
            rightArea.setText("");
            diffPane.setText("");
        });

        // 默认触发比对
        compare.doClick();

        return root;
    }

    private static SimpleAttributeSet attributes(Color color) {
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        StyleConstants.setForeground(attributes, color);
        StyleConstants.setFontFamily(attributes, Tokens.fontMono().getFamily());
        return attributes;
    }

    @Override public void closeResources() {
        closed = true;
        generation.incrementAndGet();
        SwingWorker<?, ?> current = worker;
        if (current != null) current.cancel(true);
    }
}
