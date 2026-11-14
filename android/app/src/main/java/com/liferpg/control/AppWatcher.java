package com.liferpg.control;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

/**
 * 只做一件事：告诉系统"现在前台是谁"，并在剩余时间用光时盖住受管应用。
 * 不读屏幕内容（canRetrieveWindowContent=false），只用 activity 切换事件，几乎不耗电。
 *
 * 只认 TYPE_WINDOW_STATE_CHANGED：TYPE_WINDOWS_CHANGED 会把输入法、Toast、系统弹窗
 * 甚至桌面残留窗口的包名当成"前台变了"，导致拦截页被误判成"切走了"而反复闪现／消失。
 */
public class AppWatcher extends AccessibilityService {

    private final Set<String> imePackages = new HashSet<String>();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        GuardState.watcher = new WeakReference<AppWatcher>(this);
        GuardState.accessibilityConnected = true;
        loadImePackages();
        GuardService.start(this);
        // 服务刚连上时，如果前台已经是受管应用且没时间了，立刻补上拦截页
        postSync();
    }

    /** 把已安装的输入法包名缓存起来，输入法弹窗不能算作"切走了"。 */
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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        CharSequence cs = event.getPackageName();
        if (cs == null) return;
        String pkg = cs.toString();
        if (pkg.length() == 0) return;

        // 回到自己 App：收掉拦截页
        if (pkg.equals(getPackageName())) {
            BlockOverlay.hide();
            GuardState.reportForeground(pkg);
            return;
        }
        if (isNoise(pkg)) return;

        GuardState.reportForeground(pkg);
        syncBlock(pkg);
    }

    /** 按当前前台与余额决定盖还是不盖；幂等，重复调用不会重建窗口。 */
    private void syncBlock(String pkg) {
        try {
            Wallet w = Wallet.get(this);
            boolean want = w.guardOn() && w.enforce() && w.isManaged(pkg) && w.balance() <= 0L;
            if (want) BlockOverlay.show(this, w.labelOf(pkg));
            else BlockOverlay.hide();
        } catch (Exception ignored) {
        }
    }

    private void postSync() {
        try {
            new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    String p = GuardState.fgPackage;
                    if (p != null && p.length() > 0 && !p.equals(getPackageName()) && !isNoise(p)) {
                        syncBlock(p);
                    }
                }
            }, 600);
        } catch (Exception ignored) {
        }
    }

    /** 守护服务每秒调用，余额从 0 变正（或暂停守护）时把拦截页收掉。 */
    public void syncNow() {
        String p = GuardState.fgPackage;
        if (p != null && p.length() > 0 && !p.equals(getPackageName()) && !isNoise(p)) syncBlock(p);
        else BlockOverlay.hide();
    }

    public void showBlock(String label) {
        BlockOverlay.show(this, label);
    }

    public void hideBlock() {
        BlockOverlay.hide();
    }

    /** 输入法、系统 UI 这类窗口不该覆盖"当前前台 App"。 */
    private boolean isNoise(String pkg) {
        if ("com.android.systemui".equals(pkg)) return true;
        if ("android".equals(pkg)) return true;
        if (imePackages.contains(pkg)) return true;
        String p = pkg.toLowerCase();
        if (p.contains("inputmethod")) return true;
        if (p.startsWith("com.android.permissioncontroller")) return true;
        return false;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        GuardState.accessibilityConnected = false;
        BlockOverlay.hide();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        GuardState.accessibilityConnected = false;
        GuardState.watcher = null;
        BlockOverlay.hide();
        super.onDestroy();
    }
}
