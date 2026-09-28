package com.aqishi.toolbox.feature.monitor.ui;

import com.aqishi.toolbox.feature.monitor.application.VideoPlayer;
import com.aqishi.toolbox.feature.monitor.domain.VideoSource;

import javax.swing.Timer;

/**
 * 默认播放器：工具箱没有内置解码器，格子绘制带名称、地址与时间戳的预览画面。
 * 播放期间每秒通知一次重绘（刷新时间戳）；{@link #close()} 停掉定时器。
 */
final class PreviewVideoPlayer implements VideoPlayer {

    private final Timer ticker;
    private boolean closed;

    PreviewVideoPlayer(VideoSource source, Runnable frameListener) {
        ticker = new Timer(1000, e -> frameListener.run());
        ticker.setRepeats(true);
    }

    @Override
    public void start() {
        if (!closed) ticker.start();
    }

    @Override
    public void stop() {
        ticker.stop();
    }

    @Override
    public boolean isPlaying() {
        return !closed && ticker.isRunning();
    }

    @Override
    public void close() {
        closed = true;
        ticker.stop();
    }
}
