package com.liferpg.control;

import android.graphics.Color;
import android.os.Bundle;
import android.webkit.WebView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private WebView web;
    private int topDp = 0;
    private int bottomDp = 0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            web = getBridge().getWebView();
            web.setBackgroundColor(Color.parseColor("#EEF4FA"));
            web.addJavascriptInterface(new NativeBridge(this), "RewardNative");

            // Android 15+ 强制 edge-to-edge，把状态栏/导航栏高度回传给前端 CSS 变量
            ViewCompat.setOnApplyWindowInsetsListener(web, (v, insets) -> {
                Insets bars = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                float density = getResources().getDisplayMetrics().density;
                applySafeArea(Math.round(bars.top / density), Math.round(bars.bottom / density));
                return insets;
            });
            ViewCompat.requestApplyInsets(web);
            web.postDelayed(new Runnable() {
                @Override
                public void run() {
                    applySafeArea(topDp, bottomDp);
                }
            }, 1500);

            if (Wallet.get(this).guardOn()) GuardService.start(this);
            ensureNotificationPermission();
        } catch (Exception ignored) {
        }
    }

    private void applySafeArea(int top, int bottom) {
        topDp = top;
        bottomDp = bottom;
        if (web == null) return;
        String js = "try{var r=document.documentElement;"
                + "r.style.setProperty('--sat','" + top + "px');"
                + "r.style.setProperty('--sab','" + bottom + "px');}catch(e){}";
        web.evaluateJavascript(js, null);
    }

    private void ensureNotificationPermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                boolean granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
                if (!granted) {
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 系统返回键先问前端要不要接管（技能详情 → 回列表），前端说没接管才退出。 */
    @Override
    public void onBackPressed() {
        if (web == null) {
            exitApp();
            return;
        }
        web.evaluateJavascript(
                "(function(){try{return !!(window.__androidBack&&window.__androidBack());}catch(e){return false;}})()",
                value -> {
                    if (!"true".equals(value)) runOnUiThread(this::exitApp);
                });
    }

    private void exitApp() {
        super.onBackPressed();
    }
}
