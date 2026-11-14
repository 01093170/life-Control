package com.liferpg.control;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

/**
 * 常驻前台服务：按秒统计受管应用的在前台时长，从剩余时间里扣。
 * 用光之后由无障碍服务盖拦截页（不再走 root 强杀）。
 *
 * 通知只保留系统要求的那一条，并且是 IMPORTANCE_MIN：不在状态栏显示额度数字。
 */
public class GuardService extends Service {

    public static final String CHANNEL_ID = "guard_v2";   // 新 id：旧的 LOW 通道无法降级，换一个才能拿到 MIN
    private static final String LEGACY_CHANNEL_ID = "reward_guard";
    public static final int NOTI_ID = 4242;

    private static final long CHARGE_CHUNK_MS = 10000L;

    private Handler handler;
    private long lastTickAt = 0L;
    private long accMs = 0L;

    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, GuardService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception ignored) {
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            try {
                tick();
            } catch (Exception ignored) {
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createChannel();
        enterForeground("专注守护运行中");
        handler.postDelayed(ticker, 1000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        enterForeground("专注守护运行中");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        flushAcc();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /* ---------------- 主循环 ---------------- */

    private void tick() {
        Wallet w = Wallet.get(this);
        long now = SystemClock.elapsedRealtime();
        long dt = lastTickAt == 0L ? 1000L : now - lastTickAt;
        lastTickAt = now;
        if (dt <= 0 || dt > 8000L) dt = 1000L;

        String pkg = GuardState.fgPackage;
        boolean managed = w.isManaged(pkg);
        boolean counting = managed && isScreenOn() && w.guardOn() && w.enforce();

        if (counting) {
            accMs += dt;
            if (accMs >= CHARGE_CHUNK_MS || w.balance() <= 0) flushAcc();
        } else {
            flushAcc();
        }

        // 拦截只交给无障碍服务：它知道当前前台是谁，也能自己加覆盖窗。
        AppWatcher watcher = GuardState.watcher();
        if (watcher != null) watcher.syncNow();
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

    /* ---------------- 通知 ---------------- */

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "专注守护",
                NotificationManager.IMPORTANCE_MIN);   // 最小声量：不响、不震，状态栏不占额度信息
        ch.setShowBadge(false);
        ch.enableVibration(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
        try {
            nm.deleteNotificationChannel(LEGACY_CHANNEL_ID);   // 旧通道带着额度文案，一并清掉
        } catch (Exception ignored) {
        }
    }

    private void enterForeground(String text) {
        Notification n = buildNotification("意", text);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(this, NOTI_ID, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                ServiceCompat.startForeground(this, NOTI_ID, n, 0);
            }
        } catch (Exception e) {
            try {
                startForeground(NOTI_ID, n);
            } catch (Exception ignored) {
            }
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, flag);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_guard)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setContentIntent(pi)
                .build();
    }
}
