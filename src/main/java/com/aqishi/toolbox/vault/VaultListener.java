package com.aqishi.toolbox.vault;

/**
 * 保险库状态变化监听器。
 *
 * <p>回调不在触发状态变化的线程上执行，而是交给 {@code VaultService} 构造时传入的
 * {@code eventExecutor} 异步投递；应用里由 {@code VaultBootstrap} 传入
 * {@code SwingUtilities::invokeLater}，所以回调已经运行在 EDT 上，无需再切线程。
 * 测试或其他宿主换用别的执行器时，以该执行器的线程为准。</p>
 *
 * <p>监听器不应在回调中阻塞或重新进入保险库服务。</p>
 */
public interface VaultListener {
    void onStateChanged(VaultState state);
}
