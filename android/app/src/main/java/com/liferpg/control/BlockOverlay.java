package com.liferpg.control;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 额度耗尽时盖在受管 App 上的全屏拦截页（纯代码构建，不依赖布局文件）。 */
public class BlockOverlay {

    private static View sView;
    private static WindowManager sWm;

    public static void show(Context ctx, String appLabel) {
        hide();
        try {
            sWm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (sWm == null) return;

            final Context app = ctx.getApplicationContext();
            FrameLayout root = new FrameLayout(ctx);
            root.setBackgroundColor(Color.parseColor("#0F172A"));

            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            int pad = dp(ctx, 28);
            box.setPadding(pad, pad, pad, pad);

            TextView tag = new TextView(ctx);
            tag.setText("娱乐额度");
            tag.setTextColor(Color.parseColor("#64748B"));
            tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tag.setLetterSpacing(0.2f);
            box.addView(tag);

            TextView title = new TextView(ctx);
            title.setText("额度已用完");
            title.setTextColor(Color.WHITE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tp.topMargin = dp(ctx, 10);
            box.addView(title, tp);

            TextView desc = new TextView(ctx);
            desc.setText((appLabel == null || appLabel.length() == 0 ? "该应用" : appLabel)
                    + " 已暂停使用\n去技能页打卡，攒够额度再来");
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
                    windowType(ctx),
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    android.graphics.PixelFormat.OPAQUE);
            sWm.addView(root, lp);
            sView = root;
        } catch (Exception e) {
            sView = null;
        }
    }

    public static void hide() {
        if (sView != null && sWm != null) {
            try {
                sWm.removeView(sView);
            } catch (Exception ignored) {
            }
        }
        sView = null;
    }

    public static boolean isShowing() {
        return sView != null;
    }

    private static int windowType(Context ctx) {
        boolean overlayOk = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                overlayOk = Settings.canDrawOverlays(ctx);
            } catch (Exception e) {
                overlayOk = false;
            }
        }
        if (overlayOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
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
