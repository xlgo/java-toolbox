package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.catalog.ToolDescriptor;
import com.aqishi.toolbox.feature.network.application.HttpBenchRunner;
import com.aqishi.toolbox.feature.network.domain.BenchPlan;
import com.aqishi.toolbox.feature.network.domain.BenchRequest;
import com.aqishi.toolbox.feature.network.domain.BenchResult;
import com.aqishi.toolbox.feature.network.domain.BenchSnapshot;
import com.aqishi.toolbox.feature.network.domain.BenchTargetHost;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.Errors;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;

/**
 * HTTP 压测面板（ab / wrk 风格）。
 *
 * <p>界面只通过 Swing {@link Timer} 每 500 ms 轮询一次 {@link HttpBenchRunner#snapshot()}，
 * 所有控件更新都在 EDT 上；执行器的工作线程从不碰界面。</p>
 *
 * <p>目标不是本机、内网或链路本地地址时，开跑前要求用户确认有权测试该目标——
 * 判定需要一次 DNS 解析，放在后台线程里做，避免界面卡住。</p>
 */
public class HttpBenchPanel extends ToolPanel implements ManagedResourceOwner {

    private static final int POLL_MILLIS = 500;

    private BenchRequestForm requestForm;
    private BenchLoadForm loadForm;
    private BenchResultView resultView;
    private JButton startBtn;
    private JButton stopBtn;
    private JButton copyBtn;
    private JProgressBar progressBar;
    private JLabel statusLabel;
    private JLabel noteLabel;
    private Timer pollTimer;

    private HttpBenchRunner runner;
    private BenchResult lastResult;
    private SwingWorker<BenchTargetHost, Void> preflight;

    public HttpBenchPanel() {
        this(ToolCatalog.HTTP_BENCH);
    }

    public HttpBenchPanel(ToolDescriptor descriptor) {
        super(Objects.requireNonNull(descriptor, "descriptor"));
    }

    @Override
    protected JComponent build() {
        requestForm = new BenchRequestForm();
        loadForm = new BenchLoadForm();
        resultView = new BenchResultView();

        JPanel left = new JPanel(new BorderLayout(0, Tokens.SPACE_LG));
        left.setOpaque(false);
        left.add(requestForm.card(), BorderLayout.CENTER);
        left.add(loadForm.card(), BorderLayout.SOUTH);

        startBtn = Buttons.primary(I18n.get("tool.httpbench.btn.start"));
        stopBtn = Buttons.danger(I18n.get("tool.httpbench.btn.stop"));
        copyBtn = Buttons.secondary(I18n.get("tool.httpbench.btn.copyReport"));
        startBtn.addActionListener(event -> requestStart());
        stopBtn.addActionListener(event -> stop());
        copyBtn.addActionListener(event -> copyReport());
        stopBtn.setEnabled(false);
        copyBtn.setEnabled(false);

        progressBar = new JProgressBar(0, 1000);
        progressBar.setStringPainted(false);
        statusLabel = Fields.caption(I18n.get("tool.httpbench.status.ready"));
        noteLabel = Fields.caption(" ");

        JPanel statusRow = Layouts.box(Tokens.SPACE_MD, 0);
        statusRow.add(progressBar, BorderLayout.CENTER);
        statusRow.add(statusLabel, BorderLayout.EAST);
        JPanel top = new JPanel(new BorderLayout(0, Tokens.SPACE_XS));
        top.setOpaque(false);
        top.add(statusRow, BorderLayout.NORTH);
        top.add(noteLabel, BorderLayout.SOUTH);

        JPanel liveContent = Layouts.box(0, Tokens.SPACE_MD);
        liveContent.add(top, BorderLayout.NORTH);
        liveContent.add(resultView.component(), BorderLayout.CENTER);

        Card liveCard = Card.titled(I18n.get("tool.httpbench.card.results"));
        liveCard.setContent(liveContent);
        liveCard.addHeaderAction(copyBtn);
        liveCard.addHeaderAction(stopBtn);
        liveCard.addHeaderAction(startBtn);

        pollTimer = new Timer(POLL_MILLIS, event -> poll());
        pollTimer.setRepeats(true);

        JPanel root = Layouts.page();
        root.add(Layouts.splitHorizontal(left, liveCard, 0.4, 0.42), BorderLayout.CENTER);
        return root;
    }

    // ------------------------------------------------------------------ 开始

    private void requestStart() {
        if (runner != null && !runner.isFinished()) {
            return;
        }
        BenchPlan plan;
        try {
            BenchRequest request = requestForm.toRequest();
            plan = loadForm.toPlan(request);
        } catch (IllegalArgumentException invalid) {
            setStatus(I18n.get("tool.httpbench.status.invalid", invalid.getMessage()), Tokens.danger());
            return;
        }
        startBtn.setEnabled(false);
        setStatus(I18n.get("tool.httpbench.status.checkingTarget"), Tokens.mutedForeground());
        String host = plan.request().host();
        SwingWorker<BenchTargetHost, Void> worker = new SwingWorker<>() {
            @Override
            protected BenchTargetHost doInBackground() {
                return BenchTargetHost.of(host);
            }

            @Override
            protected void done() {
                if (isCancelled() || preflight != this) {
                    return;
                }
                preflight = null;
                BenchTargetHost target;
                try {
                    target = get();
                } catch (Exception error) {
                    Errors.ignored("target classification failed, treating as public", error);
                    target = BenchTargetHost.of("");
                }
                if (target.requiresAuthorizationWarning() && !UIUtils.confirm(getView(),
                        I18n.get("tool.httpbench.confirm.public", host),
                        I18n.get("tool.httpbench.confirm.title"))) {
                    startBtn.setEnabled(true);
                    setStatus(I18n.get("tool.httpbench.status.ready"), Tokens.mutedForeground());
                    return;
                }
                launch(plan);
            }
        };
        preflight = worker;
        worker.execute();
    }

    /** 真正开跑：包可见，测试可跳过目标确认直接调用。 */
    void launch(BenchPlan plan) {
        HttpBenchRunner next = new HttpBenchRunner(plan);
        try {
            next.start();
        } catch (RuntimeException error) {
            startBtn.setEnabled(true);
            setStatus(I18n.get("tool.httpbench.status.invalid", Errors.describeRoot(error)), Tokens.danger());
            return;
        }
        runner = next;
        lastResult = null;
        resultView.reset();
        progressBar.setValue(0);
        noteLabel.setText(plan.request().droppedHeaders().isEmpty() ? " "
                : I18n.get("tool.httpbench.status.droppedHeaders",
                String.join(", ", plan.request().droppedHeaders())));
        setEditing(false);
        setStatus(I18n.get("tool.httpbench.status.starting"), Tokens.mutedForeground());
        pollTimer.start();
    }

    // ------------------------------------------------------------------ 轮询与结束

    private void poll() {
        HttpBenchRunner current = runner;
        if (current == null) {
            pollTimer.stop();
            return;
        }
        if (current.isFinished()) {
            BenchResult result = current.completion().getNow(null);
            if (result != null) {
                pollTimer.stop();
                applyResult(result);
                return;
            }
        }
        applySnapshot(current.snapshot());
    }

    /** 把一份进行中的快照画到界面上，只能在 EDT 调用。 */
    void applySnapshot(BenchSnapshot snapshot) {
        resultView.showSnapshot(snapshot);
        progressBar.setValue((int) Math.round(snapshot.progress() * 1000));
        if (runner != null && runner.isCancelled()) {
            setStatus(I18n.get("tool.httpbench.status.stopping"), Tokens.warning());
        } else if (snapshot.phase() == BenchSnapshot.Phase.WARMUP) {
            setStatus(I18n.get("tool.httpbench.status.warmup", String.valueOf(snapshot.warmupCompleted())),
                    Tokens.mutedForeground());
        } else if (snapshot.phase() == BenchSnapshot.Phase.RUNNING) {
            setStatus(I18n.get("tool.httpbench.status.running", String.valueOf(snapshot.inFlight())),
                    Tokens.accent());
        }
    }

    /** 展示最终结果并恢复可编辑状态，只能在 EDT 调用。 */
    void applyResult(BenchResult result) {
        lastResult = result;
        resultView.showResult(result);
        progressBar.setValue(result.outcome() == BenchResult.Outcome.COMPLETED ? 1000
                : (int) Math.round(result.snapshot().progress() * 1000));
        switch (result.outcome()) {
            case CANCELLED:
                setStatus(I18n.get("tool.httpbench.status.cancelled"), Tokens.warning());
                break;
            case FAILED:
                setStatus(I18n.get("tool.httpbench.status.failed", result.failure()), Tokens.danger());
                break;
            default:
                setStatus(I18n.get("tool.httpbench.status.completed",
                        String.valueOf(result.snapshot().completed()),
                        BenchFormat.rate(result.snapshot().requestsPerSecond())), Tokens.success());
                break;
        }
        setEditing(true);
        copyBtn.setEnabled(true);
    }

    private void stop() {
        HttpBenchRunner current = runner;
        if (current != null && !current.isFinished()) {
            current.cancel();
            setStatus(I18n.get("tool.httpbench.status.stopping"), Tokens.warning());
        }
    }

    private void copyReport() {
        if (lastResult != null) {
            UIUtils.copyToClipboard(BenchFormat.report(lastResult));
            setStatus(I18n.get("tool.httpbench.status.copied"), Tokens.success());
        }
    }

    private void setEditing(boolean editable) {
        requestForm.setEditable(editable);
        loadForm.setEditable(editable);
        startBtn.setEnabled(editable);
        stopBtn.setEnabled(!editable);
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
    }

    // ------------------------------------------------------------------ 测试可见

    BenchRequestForm requestForm() {
        return requestForm;
    }

    BenchLoadForm loadForm() {
        return loadForm;
    }

    BenchResultView resultView() {
        return resultView;
    }

    String statusText() {
        return statusLabel.getText();
    }

    HttpBenchRunner runner() {
        return runner;
    }

    BenchResult lastResult() {
        return lastResult;
    }

    boolean isPolling() {
        return pollTimer != null && pollTimer.isRunning();
    }

    @Override
    public void closeResources() {
        if (pollTimer != null) {
            pollTimer.stop();
        }
        SwingWorker<BenchTargetHost, Void> pending = preflight;
        preflight = null;
        if (pending != null) {
            pending.cancel(true);
        }
        HttpBenchRunner current = runner;
        if (current != null) {
            current.cancel();
        }
    }
}
