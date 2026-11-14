package com.liferpg.control;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 剩余时间用光时盖在受管 App 上的全屏拦截页（纯代码构建，不依赖布局文件）。
 *
 * 只用 TYPE_ACCESSIBILITY_OVERLAY：由无障碍服务自己加窗，不需要 root、也不需要悬浮窗权限。
 * show() 是幂等的——同一个应用已经在盖着就直接返回，避免守护服务每秒 tick 都拆窗重建造成闪烁。
 */
public class BlockOverlay {

    private static final String TAG = "YiGuard";
    private static View sView;
    private static WindowManager sWm;
    private static String sLabel;

    public static boolean isShowing() {
        return sView != null;
    }

    public static void show(Context ctx, String appLabel) {
        final String label = appLabel == null ? "" : appLabel;
        // 已经盖着同一个应用就不重建；但窗口若被系统摘掉了必须重建，否则会"看得见却挡不住"。
        if (sView != null && sView.isAttachedToWindow() && label.equals(sLabel)) return;
        hide();
        try {
            sWm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (sWm == null) return;

            final Context app = ctx.getApplicationContext();
            FrameLayout root = new FrameLayout(ctx);
            root.setBackgroundColor(Color.parseColor("#0F172A"));
            // 明确吃掉落在拦截页上的所有触摸，避免某些 ROM 把事件透传给下面的 App。
            root.setClickable(true);
            root.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    return true;
                }
            });

            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            int pad = dp(ctx, 28);
            box.setPadding(pad, pad, pad, pad);

            TextView tag = new TextView(ctx);
            tag.setText("意 · 专注守护");
            tag.setTextColor(Color.parseColor("#64748B"));
            tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tag.setLetterSpacing(0.2f);
            box.addView(tag);

            TextView title = new TextView(ctx);
            title.setText("时间用完了");
            title.setTextColor(Color.WHITE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tp.topMargin = dp(ctx, 10);
            box.addView(title, tp);

            TextView desc = new TextView(ctx);
            desc.setText((label.length() == 0 ? "该应用" : label)
                    + " 先放一放\n去技能页走完一段专注，换回来的时间再来");
            desc.setTextColor(Color.parseColor("#94A3B8"));
            desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            desc.setGravity(Gravity.CENTER);
            desc.setLineSpacing(dp(ctx, 6), 1f);
            LinearLayout.LayoutParams dp1 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dp1.topMargin = dp(ctx, 12);
            box.addView(desc, dp1);

            TextView btnGo = makeButton(ctx, "去打卡", "#2563EB", Color.WHITE);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(ctx, 220), dp(ctx, 50));
            bp.topMargin = dp(ctx, 34);
            btnGo.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hide();
                    try {
                        Intent i = new Intent(app, MainActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        app.startActivity(i);
                    } catch (Exception ignored) {
                    }
                }
            });
            box.addView(btnGo, bp);

            TextView btnHome = makeButton(ctx, "回桌面", "#1E293B", Color.parseColor("#CBD5E1"));
            LinearLayout.LayoutParams bp2 = new LinearLayout.LayoutParams(dp(ctx, 220), dp(ctx, 50));
            bp2.topMargin = dp(ctx, 12);
            btnHome.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hide();
                    try {
                        Intent home = new Intent(Intent.ACTION_MAIN);
                        home.addCategory(Intent.CATEGORY_HOME);
                        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        app.startActivity(home);
                    } catch (Exception ignored) {
                    }
                }
            });
            box.addView(btnHome, bp2);

            root.addView(box, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    android.graphics.PixelFormat.OPAQUE);
            sWm.addView(root, lp);
            sView = root;
            sLabel = label;
            Log.d(TAG, "overlay shown: " + label);
        } catch (Exception e) {
            Log.d(TAG, "overlay show failed: " + e);
            sView = null;
            sLabel = null;
        }
    }

    public static void hide() {
        if (sView == null && sWm == null) return;   // 幂等：每秒 tick 都调，别刷日志
        if (sView != null && sWm != null) {
            try {
                sWm.removeView(sView);
                Log.d(TAG, "overlay hidden");
            } catch (Exception ignored) {
            }
        }
        sView = null;
        sLabel = null;
        sWm = null;
    }

    private static TextView makeButton(Context ctx, String text, String bg, int fg) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextColor(fg);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(bg));
        g.setCornerRadius(dp(ctx, 14));
        b.setBackground(g);
        b.setClickable(true);
        return b;
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
