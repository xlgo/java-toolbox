package com.aqishi.toolbox.feature.monitor.application;

import java.awt.image.BufferedImage;

/**
 * 一路视频的播放器（解码线程、外部进程、网络连接都归它管）。
 *
 * <p>由 {@link VideoWallPlayers} 按源创建、复用和释放：换布局时仍可见的源沿用原播放器，
 * 不再可见的源调用且只调用一次 {@link #close()}。</p>
 */
public interface VideoPlayer {

    /** 开始（或恢复）播放；已在播放时无操作。 */
    void start();

    /** 暂停播放，但保留资源以便再次 {@link #start()}。 */
    void stop();

    boolean isPlaying();

    /** 最近一帧画面；返回 null 时格子绘制占位画面。 */
    default BufferedImage frame() {
        return null;
    }

    /** 释放全部资源；之后播放器不可再用。 */
    void close();
}
