package com.liferpg.control;

import android.accessibilityservice.AccessibilityService;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 只做一件事：确认"现在前台是谁"，并在剩余时间用光时盖住受管应用。
 *
 * 修 B站/QQ 拦截页闪一下就消失：
 * 旧逻辑把「最后一个 TYPE_WINDOW_STATE_CHANGED 事件的包名」当成当前前台。
 * 这类 App 页面刷新时会拉起分享 / 登录 / 权限 / 广告 / 小程序等瞬时窗口，
 * 事件包名一变就立刻 hide() 拦截页；而这些窗口消失时系统**不会**为原来的
 * Activity 补发窗口事件，于是前台记录永久停留在那个包，守护服务每秒都判定
 * "不在受管应用里"，拦截页再也不会回来 —— 表现就是闪一下、然后能继续滑动。
 *
 * 现在：
 * 1) getWindows() 主动查询真实前台（不再靠事件流猜），守护服务每秒一次；
 * 2) 非全屏窗口事件一律不参与"换应用"判定；
 * 3) 锁住之后是粘性的：真实前台短时间变成别的包只进宽限期，不收拦截页；
 * 4) 只有明确离开（HOME / 桌面 / 回到本 App）或宽限期超时才真正放行。
 *
 * 不读取屏幕上的文字和输入内容，只取窗口所属包名，也不需要 root。
 */
public class AppWatcher extends AccessibilityService {

    private static final String TAG = "YiGuard";

    /** 真实前台连续多久不是受管应用，才认定真的离开了。用于吞掉瞬时弹窗。 */
    private static final long LEAVE_GRACE_MS = 2500L;
    private static final long CHARGE_CHUNK_MS = 10000L;

    /* 按秒扣费与拦截裁决都放在无障碍服务里：它由系统绑定，不需要前台服务，
       也就不需要那条「专注守护运行中」的常驻通知。 */
    private Handler handler;
    private long lastTickAt = 0L;
    private long accMs = 0L;

    private final Set<String> imePackages = new HashSet<String>();
    private final Set<String> launcherPackages = new HashSet<String>();
    private String lastDecision = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        GuardState.watcher = new WeakReference<AppWatcher>(this);
        GuardState.accessibilityConnected = true;
        loadImePackages();
        loadLauncherPackages();
        startTicker();
        cleanupLegacyNotificationChannels();
        Log.d(TAG, "service connected");
        postSync();
    }

    /** 旧版前台服务留下的通知类别，覆盖升级不会自动消失，主动删掉。 */
    private void cleanupLegacyNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.deleteNotificationChannel("guard_v2");
            nm.deleteNotificationChannel("reward_guard");
        } catch (Exception ignored) {
        }
    }

    private void startTicker() {
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, 1000);
    }

    private void stopTicker() {
        if (handler != null) handler.removeCallbacks(ticker);
        flushAcc();
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            try {
                tick();
            } catch (Exception ignored) {
            }
            if (handler != null) handler.postDelayed(this, 1000);
        }
    };

    /** 每秒：按前台时长扣额度，并让拦截裁决跟着最新状态走。 */
    private void tick() {
        Wallet w = Wallet.get(this);
        long now = SystemClock.elapsedRealtime();
        long dt = lastTickAt == 0L ? 1000L : now - lastTickAt;
        lastTickAt = now;
        if (dt <= 0 || dt > 8000L) dt = 1000L;

        String pkg = GuardState.fgPackage;
        boolean counting = w.isManaged(pkg) && isScreenOn() && w.guardOn() && w.enforce();
        if (counting) {
            accMs += dt;
            if (accMs >= CHARGE_CHUNK_MS || w.balance() <= 0) flushAcc();
        } else {
            flushAcc();
        }
        syncNow();
    }

    private void flushAcc() {
        if (accMs <= 0) return;
        Wallet.get(this).charge(accMs);
        accMs = 0L;
    }

    private boolean isScreenOn() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Exception e) {
            return true;
        }
    }

    private void loadImePackages() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm == null) return;
            for (InputMethodInfo info : imm.getInputMethodList()) {
                if (info != null) imePackages.add(info.getPackageName());
            }
        } catch (Exception ignored) {
        }
    }

    /** 记住所有桌面：回到桌面是明确的离开信号，不必等宽限期。 */
    private void loadLauncherPackages() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> list = getPackageManager().queryIntentActivities(home, 0);
            if (list == null) return;
            for (ResolveInfo ri : list) {
                if (ri != null && ri.activityInfo != null && ri.activityInfo.packageName != null) {
                    launcherPackages.add(ri.activityInfo.packageName);
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        CharSequence cs = event.getPackageName();
        if (cs == null) return;
        final String pkg = cs.toString();
        if (pkg.length() == 0) return;

        boolean full = event.isFullScreen();
        CharSequence cn = event.getClassName();
        final String cls = cn == null ? "" : cn.toString();
        Log.d(TAG, "event pkg=" + pkg + " class=" + cls + " full=" + full);

        // 拦截页本身也是本包的窗口（TYPE_ACCESSIBILITY_OVERLAY），一加窗系统就会回送
        // TYPE_WINDOW_STATE_CHANGED。旧逻辑把它当成「用户回到本 App」，于是刚盖上就被
        // 自己关掉、守护服务每秒再盖一次 —— 这正是「闪一下然后又能继续滑」的真凶。
        // 只有真正全屏的 MainActivity 才算离开；其余本包窗口一律忽略并保持拦截页。
        if (pkg.equals(getPackageName())) {
            if (!full || cls.contains("BlockOverlay")) {
                String keep = GuardState.blockedPkg;
                if (keep.length() > 0) reassert(keep);
                return;
            }
            GuardState.reportForeground(pkg);
            GuardState.explicitLeave = true;
            release("back to self");
            return;
        }
        if (isLauncher(pkg)) {
            GuardState.reportForeground(pkg);
            GuardState.explicitLeave = true;
            release("home / launcher");
            return;
        }
        if (isNoise(pkg)) return;

        // 事件记忆只在拿不到权威前台时兜底用，不再是拦截判定的依据。
        GuardState.reportForeground(pkg);

        if (!full) {
            // Dialog / IME / Toast / 权限弹窗 / 应用内浮层：不代表换了应用。
            String b = GuardState.blockedPkg;
            if (b.length() > 0 && b.equals(pkg)) reassert(b);
            return;
        }
        syncNow();
    }

    /**
     * 主动问系统"最上面的应用窗口是谁"。
     * 需要 canRetrieveWindowContent + flagRetrieveInteractiveWindows（见 xml 配置）。
     * 只取包名，绝不读取节点文字。
     */
    private String trueForeground() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            AccessibilityWindowInfo best = null;
            if (ws != null) {
                for (AccessibilityWindowInfo wi : ws) {
                    if (wi == null) continue;
                    if (wi.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                    if (!wi.isActive()) continue;
                    if (best == null || wi.getLayer() > best.getLayer()) best = wi;
                }
            }
            if (best != null) {
                AccessibilityNodeInfo root = best.getRoot();
                if (root != null && root.getPackageName() != null) {
                    return root.getPackageName().toString();
                }
            }
            AccessibilityNodeInfo active = getRootInActiveWindow();
            if (active != null && active.getPackageName() != null) {
                return active.getPackageName().toString();
            }
        } catch (Exception e) {
            Log.d(TAG, "trueForeground unavailable: " + e);
        }
        return "";
    }

    /**
     * 守护服务每秒调用一次；NativeBridge.startGuard() 也会调。
     * 所有 show / hide 决策都收敛到这里，事件回调只负责快速触发。
     */
    public void syncNow() {
        try {
            Wallet w = Wallet.get(this);
            boolean on = w.guardOn() && w.enforce() && w.balance() <= 0L;
            if (!on) {
                release("guard off / balance available");
                return;
            }

            long now = SystemClock.elapsedRealtime();
            String auth = trueForeground();
            String ev = GuardState.fgPackage;
            String b = GuardState.blockedPkg;
            String probe = auth.length() > 0 ? auth : ev;

            if (probe.equals(getPackageName()) || isLauncher(probe)) {
                // 某些 ROM 的 getRootInActiveWindow() 会把我们自己的覆盖层当成活动窗口；
                // 只要还锁着、且没有明确的 HOME / 回本 App 事件，就按瞬时窗口处理，别自己放行。
                if (b.length() > 0 && !GuardState.explicitLeave) {
                    hold(probe, now);
                    return;
                }
                release("home / self");
                return;
            }

            if (auth.length() > 0) {
                if (isNoise(auth)) {
                    hold(auth, now);
                    return;
                }
                if (w.isManaged(auth)) {
                    block(auth, now, w);
                    return;
                }
                if (b.length() == 0) {
                    release("not managed: " + auth);
                    return;
                }
                if (GuardState.explicitLeave) {
                    release("explicit leave");
                    return;
                }
                hold(auth, now);
                return;
            }

            // 系统不给窗口内容：退回事件记忆 + 粘性锁。
            if (ev.length() > 0 && !ev.equals(b) && !isNoise(ev)) {
                if (GuardState.explicitLeave) {
                    release("explicit leave");
                    return;
                }
                hold(ev, now);
                return;
            }
            if (b.length() > 0) {
                reassert(b);
                logDecision("HOLD " + b + " (no window info)");
                return;
            }
            release("no foreground info");
        } catch (Exception e) {
            Log.d(TAG, "syncNow error: " + e);
        }
    }

    private void block(String pkg, long now, Wallet w) {
        GuardState.blockedPkg = pkg;
        GuardState.lastConfirmedAt = now;
        GuardState.leaveSince = 0L;
        GuardState.explicitLeave = false;
        GuardState.reportForeground(pkg);
        BlockOverlay.show(this, w.labelOf(pkg));
        logDecision("BLOCK " + pkg);
    }

    /** 宽限期内保持拦截页；持续不是受管应用超过宽限期才收掉。 */
    private void hold(String fg, long now) {
        if (GuardState.leaveSince == 0L) GuardState.leaveSince = now;
        long span = now - GuardState.leaveSince;
        if (span >= LEAVE_GRACE_MS) {
            release("left for good -> " + fg);
            return;
        }
        Wallet w = Wallet.get(this);
        String b = GuardState.blockedPkg;
        if (b.length() > 0 && w.isManaged(b)) {
            reassert(b);
            logDecision("HOLD " + b + " (fg=" + fg + ")");
        }
    }

    /** 幂等地重申拦截页；被系统摘掉时 show() 会重建。 */
    private void reassert(String pkg) {
        try {
            Wallet w = Wallet.get(this);
            if (w.guardOn() && w.enforce() && w.balance() <= 0L && w.isManaged(pkg)) {
                BlockOverlay.show(this, w.labelOf(pkg));
            }
        } catch (Exception ignored) {
        }
    }

    private void release(String why) {
        GuardState.clearBlock();
        BlockOverlay.hide();
        logDecision("RELEASE " + why);
    }

    /** 状态不变时不重复刷日志。 */
    private void logDecision(String s) {
        if (s.equals(lastDecision)) return;
        lastDecision = s;
        Log.d(TAG, s);
    }

    private void postSync() {
        try {
            new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    syncNow();
                }
            }, 600);
        } catch (Exception ignored) {
        }
    }

    public void showBlock(String label) {
        BlockOverlay.show(this, label);
    }

    public void hideBlock() {
        GuardState.clearBlock();
        BlockOverlay.hide();
    }

    /** 输入法、系统 UI、分享/权限选择器：这些窗口不该代表"前台 App 换了"。 */
    private boolean isNoise(String pkg) {
        if (pkg == null) return true;
        if ("com.android.systemui".equals(pkg)) return true;
        if ("android".equals(pkg)) return true;
        if (imePackages.contains(pkg)) return true;
        String p = pkg.toLowerCase();
        if (p.contains("inputmethod")) return true;
        if (p.startsWith("com.android.permissioncontroller")) return true;
        if (p.startsWith("com.google.android.permissioncontroller")) return true;
        if ("com.android.packageinstaller".equals(p)) return true;
        if ("com.android.intentresolver".equals(p)) return true;
        if ("com.miui.securitycenter".equals(p)) return true;
        return false;
    }

    private boolean isLauncher(String pkg) {
        if (pkg == null) return false;
        if (launcherPackages.contains(pkg)) return true;
        String p = pkg.toLowerCase();
        return p.contains("launcher") || p.equals("com.miui.home");
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopTicker();
        GuardState.accessibilityConnected = false;
        GuardState.clearBlock();
        BlockOverlay.hide();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        stopTicker();
        GuardState.accessibilityConnected = false;
        GuardState.watcher = null;
        GuardState.clearBlock();
        BlockOverlay.hide();
        super.onDestroy();
    }
}
