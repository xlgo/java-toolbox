package com.aqishi.toolbox.vault;

/**
 * 保险库状态变化监听器。
 *
 * <p>回调由触发状态变化的线程同步执行；Swing 调用方应自行切回 EDT。
 * 监听器不应在回调中阻塞或重新进入保险库服务。</p>
 */
public interface VaultListener {
    void onStateChanged(VaultState state);
}
