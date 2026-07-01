package com.aqishi.toolbox;

import com.aqishi.toolbox.ui.MainFrame;
import com.aqishi.toolbox.ui.ThemeManager;
import com.aqishi.toolbox.util.I18n;

import java.awt.EventQueue;

/**
 * 工具箱启动入口。
 * <p>运行：{@code java -jar java-toolbox.jar} 或直接运行此类。</p>
 */
public final class Main {

    public static void main(String[] args) {
        // 初始化国际化配置
        I18n.init();
        // 安装 FlatLaf 持久化配置外观
        ThemeManager.setupDefault();

        EventQueue.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
        });
    }

    private Main() {
    }
}
