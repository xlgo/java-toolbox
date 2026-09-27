package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.Errors;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;

/**
 * 发送文件：选择文件后在后台按块流式送入会话队列，显示进度，可取消。
 */
final class SocketSendFileBar extends JPanel {

    /** 文件发送的去向，由会话视图实现。 */
    interface Target {
        boolean isReady();

        /** 块大小：TCP 用大块，UDP 必须小于单个数据报上限。 */
        int chunkSize();

        /** 阻塞入队（后台线程调用）。 */
        void sendAwait(byte[] chunk) throws Exception;

        void report(String message, boolean error);
    }

    private final Target target;
    private final JButton fileBtn;
    private final JButton cancelBtn;
    private final JProgressBar progress;
    private SocketFileSender sender;

    SocketSendFileBar(Target target) {
        super(new BorderLayout(Tokens.SPACE_SM, 0));
        setOpaque(false);
        this.target = target;
        fileBtn = Buttons.snug(I18n.get("tool.socketdebug.file.send"));
        cancelBtn = Buttons.snug(I18n.get("tool.socketdebug.file.cancel"));
        cancelBtn.setEnabled(false);
        progress = new JProgressBar(0, 1000);
        progress.setStringPainted(true);
        progress.setString("");

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_SM, 0));
        buttons.setOpaque(false);
        buttons.add(fileBtn);
        buttons.add(cancelBtn);
        add(buttons, BorderLayout.WEST);
        add(progress, BorderLayout.CENTER);

        fileBtn.addActionListener(e -> chooseAndSend());
        cancelBtn.addActionListener(e -> cancel());
    }

    private void chooseAndSend() {
        if (!target.isReady()) {
            target.report(SocketUiText.error(com.aqishi.toolbox.feature.network.domain.SocketError.NOT_CONNECTED), true);
            return;
        }
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (file != null) {
            send(file.toPath());
        }
    }

    /** 开始发送文件（EDT 调用）。 */
    void send(Path file) {
        cancel();
        SocketFileSender created = new SocketFileSender();
        sender = created;
        fileBtn.setEnabled(false);
        cancelBtn.setEnabled(true);
        progress.setValue(0);
        progress.setString(file.getFileName().toString());
        target.report(I18n.get("tool.socketdebug.file.started", file.getFileName().toString()), false);
        created.start(file, target.chunkSize(), target::sendAwait, new SocketFileSender.Listener() {
            @Override
            public void onProgress(long sent, long total) {
                if (sender == created) {
                    showProgress(sent, total);
                }
            }

            @Override
            public void onFinished(long sent, long total, boolean cancelled, Exception error) {
                if (sender == created) {
                    sender = null;
                    fileBtn.setEnabled(true);
                    cancelBtn.setEnabled(false);
                }
                showProgress(sent, total);
                if (error != null) {
                    target.report(I18n.get("tool.socketdebug.file.failed", Errors.describe(error)), true);
                } else if (cancelled) {
                    target.report(I18n.get("tool.socketdebug.file.cancelled", SocketUiText.bytes(sent)), false);
                } else {
                    target.report(I18n.get("tool.socketdebug.file.done", SocketUiText.bytes(sent)), false);
                }
            }
        });
    }

    private void showProgress(long sent, long total) {
        progress.setValue(total <= 0 ? 1000 : (int) Math.min(1000, sent * 1000 / total));
        progress.setString(SocketUiText.bytes(sent) + " / " + SocketUiText.bytes(total));
    }

    /** 取消进行中的文件发送；断开或关闭时调用。 */
    void cancel() {
        SocketFileSender current = sender;
        if (current != null) {
            current.cancel();
        }
    }

    boolean isSending() {
        SocketFileSender current = sender;
        return current != null && current.isRunning();
    }
}
