package com.liferpg.control;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * 通知栏里的「还剩多少娱乐时间」。
 *
 * 上一版是在状态栏下面挂一条无障碍覆盖层（StatusStrip），会压在别的应用上，
 * 用户觉得碍眼。现在改成一条常驻通知：只在下拉通知栏里出现，不占屏幕、
 * 不挡任何界面，也就不用再维护一个漂浮的 WindowManager 视图。
 *
 * 省电：
 * 1) 没用前台服务，通知由无障碍服务所在的进程发，服务死了就取消；
 * 2) 频道是 IMPORTANCE_LOW + silent，不响铃、不亮屏、不弹横幅；
 * 3) 文字只在「分钟数变了」时才 notify 一次，其余时候直接返回。
 */
public class ShadeNotify {

    private static final String TAG = "YiGuard";
    private static final String CHANNEL = "yi_balance";
    private static final int ID = 4207;

    private static String sShown = "";
    private static boolean sPosted = false;
    private static boolean sCancelled = false;

    /** 通知栏里那条：剩 42 分钟 / 剩 1 小时 5 分 / 时间到。 */
    public static void sync(Context ctx, Wallet w) {
        if (ctx == null || w == null || !w.notifyShade()) {
            cancel(ctx);
            return;
        }
        if (!granted(ctx)) {          // 没给通知权限，刷了也看不见，干脆不刷
            sPosted = false;
            sShown = "";
            return;
        }
        final long bal = w.balance();
        final String txt = label(bal);
        if (sPosted && txt.equals(sShown)) return;

        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "剩余时间", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("通知栏里显示还剩多少娱乐时间");
                ch.setShowBadge(false);
                ch.setSound(null, null);
                ch.enableLights(false);
                ch.enableVibration(false);
                ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
                nm.createNotificationChannel(ch);
            }

            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(ctx, CHANNEL)
                    : new Notification.Builder(ctx);
            b.setSmallIcon(R.drawable.ic_guard)
                    .setContentTitle(txt)
                    .setContentText(bal <= 0L ? "去坐一段专注换回来" : "点开回到「意」· 用光会拦住受管应用")
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setVisibility(Notification.VISIBILITY_SECRET)
                    .setContentIntent(openIntent(ctx));
            /* 通道本身就是 IMPORTANCE_LOW + 无声，这里再兜一层：
               老系统没有通道，只能靠 defaults=0 + 低优先级做到不响不震。 */
            b.setDefaults(0).setSound(null);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                b.setPriority(Notification.PRIORITY_LOW);
            }

            nm.notify(ID, b.build());
            sCancelled = false;
            sPosted = true;
            sShown = txt;
        } catch (Exception e) {
            sPosted = false;
            sShown = "";
        }
    }

    /** 关掉开关 / 无障碍被关掉 / 服务销毁时调用；重复调用只做一次真正取消。 */
    public static void cancel(Context ctx) {
        if (ctx == null || sCancelled) return;
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(ID);
        } catch (Exception ignored) {
        }
        sCancelled = true;
        sPosted = false;
        sShown = "";
    }

    /** 开关刚被点开时催一次，不用等下一个 tick。 */
    public static void refresh(Context ctx, Wallet w) {
        sShown = "";          // 强制重发一次，权限刚授予时也能立刻出现
        sPosted = false;
        sync(ctx, w);
    }

    private static PendingIntent openIntent(Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(ctx, 0, i, flags);
    }

    /** Android 13 起通知要用户授权；没授权就什么都发不出去。 */
    public static boolean granted(Context ctx) {
        if (Build.VERSION.SDK_INT < 33) return true;
        try {
            return ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    /** 只显示「还剩多少」，不显示「已经用了多少」，扫一眼就能读完。 */
    public static String label(long ms) {
        if (ms <= 0L) return "时间到 · 去坐一段";
        long min = ms / 60000L;
        if (min <= 0L) return "剩不足 1 分钟";
        if (min >= 60L) {
            long h = min / 60L, m = min % 60L;
            return m == 0L ? ("剩 " + h + " 小时") : ("剩 " + h + " 小时 " + m + " 分");
        }
        return "剩 " + min + " 分钟";
    }
}
