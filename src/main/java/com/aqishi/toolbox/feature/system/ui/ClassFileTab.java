package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.feature.system.domain.ClassFileInfo;
import com.aqishi.toolbox.feature.system.domain.ClassFileParser;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * “Class 文件”页签：打开或拖入一个 .class，显示版本、访问标志、继承关系与字段/方法签名。
 */
final class ClassFileTab {

    private final ClassFileParser parser;
    private final ClassInfoView view = new ClassInfoView();
    private final Card card;
    private final JLabel statusLabel;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<SwingWorker<?, ?>> worker = new AtomicReference<>();
    private File lastDirectory;

    ClassFileTab(ClassFileParser parser) {
        this.parser = parser;
        card = Card.titled(I18n.get("tool.jarinspector.card.class"), I18n.get("tool.jarinspector.card.class.subtitle"));
        card.setContent(view.component());
        JButton openBtn = Buttons.primary(I18n.get("tool.jarinspector.btn.openClass"));
        JButton copyBtn = Buttons.snug(I18n.get("tool.jarinspector.btn.copy"));
        card.addHeaderAction(openBtn);
        card.addHeaderAction(copyBtn);
        statusLabel = Fields.caption(I18n.get("tool.jarinspector.status.dropClass"));
        card.setFooter(statusLabel);

        openBtn.addActionListener(event -> choose());
        copyBtn.addActionListener(event -> {
            if (view.current() != null) {
                UIUtils.copyToClipboard(view.summaryText());
                setStatus(I18n.get("tool.jarinspector.status.copied"), Tokens.success());
            }
        });
        JarUi.installDrop(card, files -> open(files.get(0)));
    }

    JComponent component() {
        return card;
    }

    ClassInfoView view() {
        return view;
    }

    private void choose() {
        JFileChooser chooser = new JFileChooser(lastDirectory);
        chooser.setDialogTitle(I18n.get("tool.jarinspector.btn.openClass"));
        chooser.setFileFilter(new FileNameExtensionFilter(I18n.get("tool.jarinspector.filter.class"), "class"));
        if (chooser.showOpenDialog(SwingUtilities.getWindowAncestor(card)) == JFileChooser.APPROVE_OPTION) {
            open(chooser.getSelectedFile());
        }
    }

    /** 后台读取并解析；过期结果（期间又打开了别的文件）直接丢弃。 */
    void open(File file) {
        lastDirectory = file.getParentFile();
        long ticket = generation.incrementAndGet();
        cancel();
        setStatus(I18n.get("tool.jarinspector.status.loading", file.getName()), Tokens.mutedForeground());
        SwingWorker<ClassFileInfo, Void> task = new SwingWorker<>() {
            @Override
            protected ClassFileInfo doInBackground() throws Exception {
                try (InputStream in = Files.newInputStream(file.toPath())) {
                    return parser.parse(in);
                }
            }

            @Override
            protected void done() {
                if (isCancelled() || ticket != generation.get()) {
                    return;
                }
                try {
                    ClassFileInfo info = get();
                    view.show(info);
                    setStatus(I18n.get("tool.jarinspector.status.classLoaded", file.getName(), info.className()),
                            Tokens.mutedForeground());
                } catch (ExecutionException error) {
                    String message = ClassText.describeError(error);
                    view.showMessage(message, true);
                    setStatus(message, Tokens.danger());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        worker.set(task);
        task.execute();
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
    }

    private void cancel() {
        SwingWorker<?, ?> previous = worker.getAndSet(null);
        if (previous != null && !previous.isDone()) {
            previous.cancel(true);
        }
    }

    void close() {
        generation.incrementAndGet();
        cancel();
    }
}
