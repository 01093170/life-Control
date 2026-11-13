package com.liferpg.control;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机自启：MIUI 上还要在「自启动」里放行，否则这里收不到广播。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            try {
                if (Wallet.get(context).guardOn()) GuardService.start(context);
            } catch (Exception ignored) {
            }
        }
    }
}
