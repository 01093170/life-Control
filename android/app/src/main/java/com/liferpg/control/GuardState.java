package com.liferpg.control;

import java.lang.ref.WeakReference;

/** 无障碍服务与前台服务之间的共享内存状态（同一进程）。 */
public class GuardState {

    /** 当前前台包名（由无障碍服务写入）。 */
    public static volatile String fgPackage = "";

    /** 前台包名的更新时刻（elapsedRealtime）。 */
    public static volatile long fgSince = 0L;

    /** 上次强杀时刻，用于冷却。 */
    public static volatile long lastKillAt = 0L;

    public static volatile boolean accessibilityConnected = false;

    /** root 检测结果缓存（由 RootShell 异步回填）。 */
    public static volatile boolean rootReady = false;
    public static volatile boolean rootChecked = false;

    public static volatile WeakReference<AppWatcher> watcher = null;

    public static void reportForeground(String pkg) {
        if (pkg == null || pkg.equals(fgPackage)) return;
        fgPackage = pkg;
        fgSince = android.os.SystemClock.elapsedRealtime();
    }

    public static AppWatcher watcher() {
        WeakReference<AppWatcher> r = watcher;
        return r == null ? null : r.get();
    }
}
