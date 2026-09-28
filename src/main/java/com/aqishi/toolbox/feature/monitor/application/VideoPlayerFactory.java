package com.aqishi.toolbox.feature.monitor.application;

import com.aqishi.toolbox.feature.monitor.domain.VideoSource;

/** 按视频源创建播放器；测试里换成不连网络的假实现。 */
@FunctionalInterface
public interface VideoPlayerFactory {

    /**
     * @param source        要播放的源（地址里的凭据已还原）
     * @param frameListener 有新画面需要重绘时调用，可在任意线程
     */
    VideoPlayer create(VideoSource source, Runnable frameListener);
}
