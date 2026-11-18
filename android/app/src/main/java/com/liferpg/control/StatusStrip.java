package com.liferpg.control;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 状态栏下面那条「还剩多少分钟」的小计时条（可开关）。
 *
 * 只用 TYPE_ACCESSIBILITY_OVERLAY：跟拦截页一样由无障碍服务自己加窗，不需要悬浮窗权限，
 * 也不需要前台服务或常驻通知 —— 关掉无障碍，它自然就没了。
 *
 * 省电：这里不做任何定时器，文字由 AppWatcher 的那一秒一次 tick 顺手带过来；
 * 只有「分钟数变了」才 setText，其余时候直接返回，不触发重排。
 */
public class StatusStrip {

    private static final String TAG = "YiGuard";

    private static View sView;
    private static WindowManager sWm;
    private static TextView sText;
    private static String sShown = "";

    /** 用自己的 View 子类，无障碍事件的 className 里带上 StatusStrip 便于排查。 */
    public static class StripView extends FrameLayout {
        public StripView(Context c) { super(c); }
    }

    public static boolean isShowing() {
        return sView != null;
    }

    /**
     * 根据当前设置与余额把条子摆对：该出现就出现、该消失就消失、文字变了才改。
     * 幂等，可以每秒调。
     */
    public static void sync(Context ctx, Wallet w) {
        if (ctx == null || w == null || !w.statusBar()) {
            hide();
            return;
        }
        /* 回到本 App 时收起来：页面里本来就有大号的剩余时间，别去压住顶栏那颗齿轮 */
        if (ctx.getPackageName().equals(GuardState.fgPackage)) {
            hide();
            return;
        }
        final String txt = label(w.balance());
        if (sView != null && sView.isAttachedToWindow()) {
            if (!txt.equals(sShown) && sText != null) {
                sText.setText(txt);
                sShown = txt;
            }
            return;
        }
        hide();
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;

            StripView root = new StripView(ctx.getApplicationContext());
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#E60F172A"));
            bg.setCornerRadius(dp(ctx, 99));
            root.setBackground(bg);
            root.setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4));

            TextView t = new TextView(ctx);
            t.setText(txt);
            t.setTextColor(Color.parseColor("#F1F5F9"));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            t.setIncludeFontPadding(false);
            t.setGravity(Gravity.CENTER);
            root.addView(t, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = dp(ctx, 8);
            lp.y = statusBarHeight(ctx) + dp(ctx, 6);
            wm.addView(root, lp);

            sWm = wm;
            sView = root;
            sText = t;
            sShown = txt;
            Log.d(TAG, "strip shown: " + txt);
        } catch (Exception e) {
            Log.d(TAG, "strip show failed: " + e);
            sView = null;
            sWm = null;
            sText = null;
            sShown = "";
        }
    }

    public static void hide() {
        if (sView == null && sWm == null) return;   // 幂等：每秒都会调
        if (sView != null && sWm != null) {
            try {
                sWm.removeView(sView);
                Log.d(TAG, "strip hidden");
            } catch (Exception ignored) {
            }
        }
        sView = null;
        sWm = null;
        sText = null;
        sShown = "";
    }

    /** 只显示「还剩多少」，不显示「已经用了多少」，抬眼一秒就能读完。 */
    static String label(long ms) {
        if (ms <= 0L) return "时间到 · 去坐一段";
        long min = ms / 60000L;
        if (min <= 0L) return "剩不足 1 分钟";
        if (min >= 60L) {
            long h = min / 60L, m = min % 60L;
            return m == 0L ? ("剩 " + h + " 小时") : ("剩 " + h + " 小时 " + m + " 分");
        }
        return "剩 " + min + " 分钟";
    }

    private static int statusBarHeight(Context ctx) {
        try {
            int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return ctx.getResources().getDimensionPixelSize(id);
        } catch (Exception ignored) {
        }
        return dp(ctx, 24);
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
