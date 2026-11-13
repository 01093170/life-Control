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
 * 常驻前台服务：按秒统计受管应用的在前台时长，从娱乐额度里扣。
 * 额度归零 → root 强杀 + 弹拦截页。
 */
public class GuardService extends Service {

    public static final String CHANNEL_ID = "reward_guard";
    public static final int NOTI_ID = 4242;

    private static final long KILL_COOLDOWN_MS = 3000L;
    private static final long CHARGE_CHUNK_MS = 10000L;

    private Handler handler;
    private long lastTickAt = 0L;
    private long accMs = 0L;
    private long lastNotiAt = 0L;
    private String lastNotiLine = "";
    private long lastRootCheckAt = 0L;

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
        enterForeground("正在统计娱乐时长");
        handler.postDelayed(ticker, 1000);
        checkRootAsync();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        enterForeground("正在统计娱乐时长");
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
            if (accMs >= CHARGE_CHUNK_MS || w.balance() <= 0) {
                flushAcc();
            }
            if (w.balance() <= 0) enforce(pkg, w);
        } else {
            flushAcc();
        }

        updateNotification(w, pkg, managed);
        maybeRecheckRoot(now);
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

    /* ---------------- 拦截 ---------------- */

    private void enforce(String pkg, Wallet w) {
        long now = SystemClock.elapsedRealtime();
        if (now - GuardState.lastKillAt < KILL_COOLDOWN_MS) return;
        GuardState.lastKillAt = now;

        if (w.rootKill()) {
            if (GuardState.rootReady) {
                RootShell.forceStop(pkg, null);
            } else {
                // 没拿到 root 也先顶一次授权，拿到后下次生效
                checkRootAsync();
            }
        }

        AppWatcher watcher = GuardState.watcher();
        String label = w.labelOf(pkg);
        if (watcher != null) {
            watcher.showBlock(label);
        } else {
            BlockOverlay.show(this, label);
        }
    }

    private void checkRootAsync() {
        lastRootCheckAt = SystemClock.elapsedRealtime();
        RootShell.checkRoot(new RootShell.Callback() {
            @Override
            public void onResult(boolean ok, String output) {
                GuardState.rootReady = ok;
                GuardState.rootChecked = true;
            }
        });
    }

    private void maybeRecheckRoot(long now) {
        if (now - lastRootCheckAt > 5 * 60 * 1000L) checkRootAsync();
    }

    /* ---------------- 通知 ---------------- */

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "娱乐额度守护",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.enableVibration(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private void enterForeground(String text) {
        Notification n = buildNotification("娱乐额度", text);
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
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotification(Wallet w, String pkg, boolean managed) {
        long now = SystemClock.elapsedRealtime();
        long bal = w.balance();
        String line;
        if (!w.guardOn()) {
            line = "守护已暂停";
        } else if (!w.enforce()) {
            line = "只记录不拦截 · 剩余 " + fmt(bal);
        } else if (bal <= 0) {
            line = "额度已用完，去打卡";
        } else if (managed) {
            line = "扣除中 · 剩余 " + fmt(bal);
        } else {
            line = "剩余 " + fmt(bal);
        }
        if (line.equals(lastNotiLine) && now - lastNotiAt < 20000L) return;
        lastNotiLine = line;
        lastNotiAt = now;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTI_ID, buildNotification("娱乐额度", line));
        } catch (Exception ignored) {
        }
    }

    public static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long totalMin = ms / 60000L;
        long h = totalMin / 60;
        long m = totalMin % 60;
        if (h > 0) return h + " 小时 " + (m < 10 ? "0" : "") + m + " 分";
        return m + " 分钟";
    }
}
