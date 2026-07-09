package com.aqishi.toolbox;

import com.aqishi.toolbox.ui.MainFrame;
import com.aqishi.toolbox.ui.ThemeManager;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;
import java.awt.*;

/**
 * 工具箱启动入口。
 * <p>运行：{@code java -jar java-toolbox.jar} 或直接运行此类。</p>
 */
public final class Main {

    // 全局启动进度接收器，用于 MainFrame 初始化工具组件时实时回调
    public static java.util.function.BiConsumer<Integer, String> startupProgressUpdater;

    public static void main(String[] args) {
        // 1. 瞬间呈现启动闪屏进度窗（直接使用系统默认外观渲染，确保极速弹出）
        SplashWindow splash = new SplashWindow();
        splash.setVisible(true);

        // 设置启动进度关联
        startupProgressUpdater = splash::setProgress;

        try {
            splash.setProgress(10, "正在初始化语言国际化组件...");
            I18n.init();

            splash.setProgress(25, "正在装载系统主题外观配置...");
            ThemeManager.setupDefault();

            // 在 Swing 事件派发线程中构建 UI 组件，并在其中实时更新工具组件的真实载入进度
            final MainFrame[] frameHolder = new MainFrame[1];
            EventQueue.invokeAndWait(() -> {
                frameHolder[0] = new MainFrame();
            });

            splash.setProgress(100, "环境准备就绪，正在打开应用...");
            Thread.sleep(100); // 最后的极短平滑过渡过渡

            EventQueue.invokeLater(() -> {
                splash.dispose();
                frameHolder[0].setVisible(true);
            });

        } catch (Exception ex) {
            ex.printStackTrace();
            // 异常兜底：若闪屏出现异常，确保直接正常拉起主界面
            EventQueue.invokeLater(() -> {
                splash.dispose();
                MainFrame frame = new MainFrame();
                frame.setVisible(true);
            });
        }
    }

    private Main() {
    }

    /**
     * 启动进度闪屏窗口
     */
    private static class SplashWindow extends JWindow {
        private final JProgressBar progressBar;
        private final JLabel statusLabel;

        public SplashWindow() {
            setSize(450, 260);
            setLocationRelativeTo(null);
            
            JPanel content = new JPanel(new BorderLayout());
            content.setBorder(BorderFactory.createLineBorder(new Color(60, 63, 65), 1));
            content.setBackground(new Color(43, 43, 43)); // 经典暗色背景

            // 头部标题与描述
            JPanel titlePanel = new JPanel(new GridLayout(2, 1, 6, 6));
            titlePanel.setBackground(new Color(43, 43, 43));
            titlePanel.setBorder(BorderFactory.createEmptyBorder(40, 20, 10, 20));

            JLabel titleLabel = new JLabel("Java Toolbox");
            titleLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 28));
            titleLabel.setForeground(new Color(240, 240, 240));
            titleLabel.setHorizontalAlignment(SwingConstants.CENTER);

            JLabel subLabel = new JLabel("开发者一站式多功能工作台");
            subLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
            subLabel.setForeground(new Color(160, 160, 160));
            subLabel.setHorizontalAlignment(SwingConstants.CENTER);

            titlePanel.add(titleLabel);
            titlePanel.add(subLabel);

            // 底部进度条与状态标签
            JPanel progressPanel = new JPanel(new BorderLayout(8, 8));
            progressPanel.setBackground(new Color(43, 43, 43));
            progressPanel.setBorder(BorderFactory.createEmptyBorder(10, 40, 40, 40));

            statusLabel = new JLabel("正在准备运行环境...");
            statusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            statusLabel.setForeground(new Color(140, 140, 140));

            progressBar = new JProgressBar(0, 100);
            progressBar.setStringPainted(false);
            progressBar.setValue(0);
            progressBar.setPreferredSize(new Dimension(370, 6));
            progressBar.setForeground(new Color(64, 158, 255)); // 科技感天蓝色
            progressBar.setBackground(new Color(60, 63, 65));
            progressBar.setBorder(BorderFactory.createEmptyBorder());

            progressPanel.add(statusLabel, BorderLayout.NORTH);
            progressPanel.add(progressBar, BorderLayout.CENTER);

            content.add(titlePanel, BorderLayout.CENTER);
            content.add(progressPanel, BorderLayout.SOUTH);

            setContentPane(content);
        }

        public void setProgress(int val, String message) {
            Runnable r = () -> {
                progressBar.setValue(val);
                statusLabel.setText(message);
                progressBar.paintImmediately(0, 0, progressBar.getWidth(), progressBar.getHeight());
                statusLabel.paintImmediately(0, 0, statusLabel.getWidth(), statusLabel.getHeight());
            };
            if (SwingUtilities.isEventDispatchThread()) {
                r.run();
            } else {
                SwingUtilities.invokeLater(r);
            }
        }
    }
}
