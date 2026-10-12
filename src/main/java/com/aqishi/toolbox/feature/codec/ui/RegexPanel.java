package com.aqishi.toolbox.feature.codec.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.codec.domain.RegexMatchService;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.infra.concurrency.DaemonThreads;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 正则表达式测试面板：实时匹配高亮、分组捕获、匹配计数。
 */
public class RegexPanel extends ToolPanel implements ManagedResourceOwner {
    private final ExecutorService executor = DaemonThreads.single("regex-match");
    private final AtomicLong generation = new AtomicLong();
    private volatile Future<?> task;
    private Timer debounce;
    private volatile boolean closed;

    public RegexPanel() {
        super(ToolCatalog.REGEX_TESTER);
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // ===== 配置卡片：正则与开关 =====
        // 本工具是输入即匹配，没有「执行」按钮，所以配置卡不设标题栏动作
        JTextField pattern = Fields.mono("\\d+");

        JCheckBox global = Fields.check("全局", true);
        JCheckBox multiline = Fields.check("多行", false);
        JCheckBox caseIns = Fields.check("忽略大小写", false);
        // flags 只装 AbstractButton：下方按 getComponents() 统一挂监听依赖这一点
        JPanel flags = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_MD, 0));
        flags.setOpaque(false);
        flags.add(global);
        flags.add(multiline);
        flags.add(caseIns);

        // 开关是正则本身的修饰符，作为行尾控件跟输入框同行：
        // 配置区因此只占一行，把省下的高度全部让给下方的文本区
        FormGrid form = new FormGrid();
        form.row("正则", pattern, flags);

        Card config = Card.titled("正则表达式");
        config.setContent(form);

        // ===== 文本区：上下分隔，两侧都铺满各自的卡片 =====
        // 行数只作为首选高度的下限；取小值可让窄窗口下的短样本文本不出现多余滚动条
        JTextArea input = Fields.area(3, 40);
        input.setText("订单A123，金额45.6元，日期2024-01-01；订单B456，金额78.9元。");
        Card inputCard = Card.flush("待匹配文本");
        inputCard.setContent(Fields.scroll(input));

        JTextArea out = Fields.output(6, 40);
        Card outCard = Card.flush("匹配结果（高亮用【】标记）");
        outCard.setContent(Fields.scroll(out));

        // 结果通常比原文长（附带分组明细），把多余空间偏向下半区
        JSplitPane split = Layouts.splitVertical(inputCard, outCard, 0.4);

        root.add(config, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);

        Runnable doMatch = () -> {
            if (closed) return;
            long ticket = generation.get();
            String p = pattern.getText();
            String text = input.getText();
            int opts = (multiline.isSelected() ? Pattern.MULTILINE : 0)
                    | (caseIns.isSelected() ? Pattern.CASE_INSENSITIVE : 0);
            boolean matchAll = global.isSelected();
            task = executor.submit(() -> {
                String result;
                try { result = new RegexMatchService().match(p, text, opts, matchAll); }
                catch (CancellationException cancelled) { return; }
                catch (Exception error) { result = I18n.get("regex.failed", error.getMessage()); }
                String output = result;
                SwingUtilities.invokeLater(() -> {
                    if (!closed && ticket == generation.get()) out.setText(output);
                });
            });
        };

        debounce = new Timer(200, e -> doMatch.run());
        debounce.setRepeats(false);
        Runnable schedule = () -> {
            generation.incrementAndGet();
            if (task != null) task.cancel(true);
            if (!closed) debounce.restart();
        };

        pattern.addActionListener(e -> schedule.run());
        for (Component c : flags.getComponents()) ((AbstractButton) c).addItemListener(e -> schedule.run());
        input.getDocument().addDocumentListener(new SimpleDocListener(schedule));
        pattern.getDocument().addDocumentListener(new SimpleDocListener(schedule));
        schedule.run();

        return root;
    }

    @Override public void closeResources() {
        closed = true;
        generation.incrementAndGet();
        if (debounce != null) debounce.stop();
        if (task != null) task.cancel(true);
        DaemonThreads.shutdownQuietly(executor);
    }

    /** 文档变化即触发，简易适配器 */
    private static class SimpleDocListener implements javax.swing.event.DocumentListener {
        private final Runnable r;
        SimpleDocListener(Runnable r) { this.r = r; }
        public void insertUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        public void removeUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        public void changedUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
    }
}
