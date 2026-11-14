package com.liferpg.control;

import java.lang.ref.WeakReference;

/** 无障碍服务与桥接层共用的运行状态（同一进程，没有前台服务）。 */
public class GuardState {

    /** 最近一次全屏窗口事件的包名；只作为兜底，不再是拦截判定的唯一依据。 */
    public static volatile String fgPackage = "";

    /** 前台包名的更新时刻（elapsedRealtime）。 */
    public static volatile long fgSince = 0L;

    public static volatile boolean accessibilityConnected = false;

    public static volatile WeakReference<AppWatcher> watcher = null;

    /** 正在被锁住的受管应用。非空时，瞬时窗口事件不得把拦截页收掉。 */
    public static volatile String blockedPkg = "";

    /** 最近一次"真实前台"确认到受管应用的时刻（elapsedRealtime）。 */
    public static volatile long lastConfirmedAt = 0L;

    /** 真实前台第一次变成"非受管应用"的时刻；持续超过宽限期才真正放行。 */
    public static volatile long leaveSince = 0L;

    /** 用户明确离开（按 HOME / 回桌面 / 回到本 App），跳过宽限期立即放行。 */
    public static volatile boolean explicitLeave = false;

    public static void reportForeground(String pkg) {
        if (pkg == null || pkg.equals(fgPackage)) return;
        fgPackage = pkg;
        fgSince = android.os.SystemClock.elapsedRealtime();
    }

    /** 清空粘性锁。只有确认用户真的离开受管应用时才调用。 */
    public static void clearBlock() {
        blockedPkg = "";
        leaveSince = 0L;
        explicitLeave = false;
    }

    public static AppWatcher watcher() {
        WeakReference<AppWatcher> r = watcher;
        return r == null ? null : r.get();
    }
}
