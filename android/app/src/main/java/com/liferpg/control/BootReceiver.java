package com.liferpg.control;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机后系统会自动重新绑定已启用的无障碍服务，守护循环随之恢复。
 * 以前这里启动前台服务，会带出一条「专注守护运行中」的常驻通知；现在不需要了。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        // AppWatcher.onServiceConnected() 会启动每秒的扣费与拦截裁决，这里无需做事。
    }
}
