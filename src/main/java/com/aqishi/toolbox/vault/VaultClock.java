package com.aqishi.toolbox.vault;

/**
 * 可替换的保险库时间源。
 *
 * <p>服务使用毫秒时间判断空闲锁定和状态时间点。生产环境使用系统时钟；
 * 其它实现应提供与系统时钟相同的毫秒时间语义，便于测试和恢复流程复现。</p>
 */
public interface VaultClock {
    long currentTimeMillis();

    static VaultClock system() {
        return System::currentTimeMillis;
    }
}
