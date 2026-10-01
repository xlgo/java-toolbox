package com.aqishi.toolbox.ui.kit;

import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.util.*;

import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import javax.swing.*;

/**
 * Shared offline-tool UI: EDT snapshots, cancellable background computation, bounded files and
 * atomic output.
 */
public final class TextWorkbench extends JPanel implements ManagedResourceOwner {
    public final JTextArea input = Fields.area(12, 45);
    public final JTextArea output = Fields.output(12, 45);
    private final JButton run = Buttons.primary(I18n.get("devtools.run"));
    private final JButton cancel = Buttons.secondary(I18n.get("devtools.cancel"));
    private final JLabel status = Fields.caption(" ");
    private SwingWorker<String, Void> worker;

    public TextWorkbench(JComponent settings, Supplier<Callable<String>> task, String sample) {
        super(new BorderLayout(Tokens.SPACE_MD, Tokens.SPACE_MD));
        setOpaque(false);
        JPanel heading = Layouts.box(0, Tokens.SPACE_SM);
        heading.add(Fields.scrollVertical(settings), BorderLayout.CENTER);
        heading.setPreferredSize(
                new Dimension(0, Math.min(170, settings.getPreferredSize().height + 12)));
        JButton example = Buttons.snug(I18n.get("devtools.sample"));
        example.addActionListener(e -> input.setText(sample));
        JButton open = Buttons.snug(I18n.get("devtools.open"));
        open.addActionListener(e -> load());
        JButton copy = Buttons.snug(I18n.get("devtools.copy"));
        copy.addActionListener(e -> UIUtils.copyToClipboard(output.getText()));
        JButton save = Buttons.snug(I18n.get("devtools.save"));
        save.addActionListener(e -> saveOutput());
        Card source = Card.flush(I18n.get("devtools.input"));
        source.setContent(Fields.scroll(input));
        source.addHeaderAction(open);
        source.addHeaderAction(example);
        Card result = Card.flush(I18n.get("devtools.output"));
        result.setContent(Fields.scroll(output));
        result.addHeaderAction(copy);
        result.addHeaderAction(save);
        source.setMinimumSize(new Dimension(180, 100));
        result.setMinimumSize(new Dimension(180, 100));
        add(heading, BorderLayout.NORTH);
        add(Layouts.splitHorizontal(source, result, 0.5, 0.5));
        JPanel actions = Layouts.box();
        actions.add(status);
        actions.add(Layouts.wrapRow(cancel, run), BorderLayout.EAST);
        add(actions, BorderLayout.SOUTH);
        cancel.setEnabled(false);
        cancel.addActionListener(
                e -> {
                    if (worker != null) worker.cancel(true);
                });
        run.addActionListener(
                e -> {
                    try {
                        execute(task.get());
                    } catch (Exception error) {
                        status.setText(Errors.describeRoot(error));
                    }
                });
        input.setText(sample);
    }

    private void execute(Callable<String> action) {
        if (worker != null) return;
        run.setEnabled(false);
        cancel.setEnabled(true);
        status.setText(I18n.get("devtools.working"));
        worker =
                new SwingWorker<>() {
                    protected String doInBackground() throws Exception {
                        return action.call();
                    }

                    protected void done() {
                        try {
                            if (!isCancelled()) {
                                output.setText(get());
                                output.setCaretPosition(0);
                                status.setText(I18n.get("devtools.done"));
                            } else status.setText(I18n.get("devtools.cancelled"));
                        } catch (Exception error) {
                            status.setText(Errors.describeRoot(error));
                            status.setToolTipText(status.getText());
                        } finally {
                            worker = null;
                            run.setEnabled(true);
                            cancel.setEnabled(false);
                        }
                    }
                };
        worker.execute();
    }

    private void load() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        try {
            if (Files.size(path) > 2_000_000)
                throw new IllegalArgumentException(I18n.get("devtools.inputLimit"));
            input.setText(Files.readString(path));
        } catch (Exception error) {
            UIUtils.error(this, Errors.describeRoot(error));
        }
    }

    private void saveOutput() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        if (Files.exists(path)
                && !UIUtils.confirm(
                        this, I18n.get("devtools.overwrite"), I18n.get("devtools.save"))) return;
        try {
            new com.aqishi.toolbox.vault.AtomicFiles()
                    .write(path, output.getText().getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            UIUtils.error(this, Errors.describeRoot(error));
        }
    }

    @Override
    public void closeResources() {
        if (worker != null) worker.cancel(true);
    }
}
