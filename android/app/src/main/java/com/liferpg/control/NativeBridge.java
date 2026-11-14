package com.liferpg.control;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * WebView 与原生之间的唯一通道（window.RewardNative）。
 * 所有方法都返回 JSON 字符串，JS 侧自己 JSON.parse。
 */
public class NativeBridge {

    private final MainActivity act;

    public NativeBridge(MainActivity activity) {
        this.act = activity;
    }

    /* ---------------- 基础 ---------------- */

    @JavascriptInterface
    public String ping() {
        return "pong";
    }

    private Wallet w() {
        return Wallet.get(act);
    }

    private String fail(String msg) {
        try {
            return new JSONObject().put("ok", false).put("error", msg).toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    private String wrap(JSONObject o) {
        try {
            o.put("ok", true);
            o.put("status", statusJson());
            return o.toString();
        } catch (Exception e) {
            return fail("serialize");
        }
    }

    /* ---------------- 状态 ---------------- */

    @JavascriptInterface
    public String getState() {
        try {
            return wrap(w().snapshot());
        } catch (Exception e) {
            return fail("state");
        }
    }

    @JavascriptInterface
    public String status() {
        try {
            return wrap(new JSONObject());
        } catch (Exception e) {
            return fail("status");
        }
    }

    private JSONObject statusJson() {
        JSONObject s = new JSONObject();
        try {
            s.put("accessibility", a11yEnabled());
            s.put("sms", smsGranted());
            s.put("screenOn", true);
        } catch (Exception ignored) {
        }
        return s;
    }

    /* ---------------- 短信自动记账 ---------------- */

    private boolean smsGranted() {
        try {
            return act.checkSelfPermission(android.Manifest.permission.RECEIVE_SMS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    /** 取走短信解析出的交易队列（取完即清空），返回 JSON 数组。 */
    @JavascriptInterface
    public String drainPayments() {
        try {
            return PayQueue.drain(act).toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 请求短信读取权限（只用于识别银行消费短信，不读取验证码以外的内容）。 */
    @JavascriptInterface
    public void requestSms() {
        try {
            if (smsGranted()) return;
            act.requestPermissions(new String[]{android.Manifest.permission.RECEIVE_SMS}, 9101);
        } catch (Exception ignored) {
        }
    }

    private boolean a11yEnabled() {
        try {
            String flat = Settings.Secure.getString(act.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (flat == null) return false;
            String pkg = act.getPackageName();
            String[] targets = {
                    pkg + "/" + AppWatcher.class.getName(),
                    pkg + "/" + pkg + ".AppWatcher",
                    pkg + "/.AppWatcher"
            };
            for (String t : targets) {
                if (flat.contains(t)) return true;
            }
            return GuardState.accessibilityConnected;
        } catch (Exception e) {
            return false;
        }
    }

    /* ---------------- 额度 ---------------- */

    @JavascriptInterface
    public String checkIn() {
        try {
            long bal = w().addReward();
            JSONObject o = new JSONObject();
            o.put("balanceMs", bal);
            o.put("addedMinutes", w().rewardMinutes());
            return wrap(o);
        } catch (Exception e) {
            return fail("checkin");
        }
    }

    @JavascriptInterface
    public String addMinutes(int minutes) {
        try {
            long bal = w().addMinutes(minutes);
            JSONObject o = new JSONObject();
            o.put("balanceMs", bal);
            return wrap(o);
        } catch (Exception e) {
            return fail("addMinutes");
        }
    }

    @JavascriptInterface
    public String setRewardMinutes(int m) {
        w().setRewardMinutes(m);
        return getState();
    }

    @JavascriptInterface
    public String setEnforce(boolean b) {
        w().setEnforce(b);
        return getState();
    }

    @JavascriptInterface
    public String setGuardOn(boolean b) {
        w().setGuardOn(b);
        if (b) GuardService.start(act);
        return getState();
    }

    @JavascriptInterface
    public String resetBalance() {
        w().reset();
        return getState();
    }

    /* ---------------- 受管应用 ---------------- */

    @JavascriptInterface
    public String addApp(String pkg, String label) {
        if (pkg == null || pkg.trim().length() == 0) return fail("empty");
        String p = pkg.trim();
        String real = w().labelOf(p);
        boolean exists = real.length() > 0 || isInstalled(p);
        if (!exists) return fail("not_installed");
        w().addApp(p, real.length() > 0 ? real : label);
        return getState();
    }

    private boolean isInstalled(String pkg) {
        try {
            act.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public String removeApp(String pkg) {
        w().removeApp(pkg);
        return getState();
    }

    @JavascriptInterface
    public String labelOf(String pkg) {
        try {
            return new JSONObject().put("ok", true).put("label", w().labelOf(pkg)).toString();
        } catch (Exception e) {
            return fail("label");
        }
    }

    /** 桌面可启动的应用列表，供设置页直接挑选，省得手打包名。 */
    @JavascriptInterface
    public String listApps() {
        try {
            PackageManager pm = act.getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN, null);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(main, 0);

            Set<String> seen = new HashSet<String>();
            List<String[]> rows = new ArrayList<String[]>();
            for (ResolveInfo ri : list) {
                if (ri == null || ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || pkg.equals(act.getPackageName())) continue;
                if (!seen.add(pkg)) continue;
                CharSequence lb = ri.loadLabel(pm);
                rows.add(new String[]{pkg, lb == null ? pkg : lb.toString()});
            }
            Collections.sort(rows, new Comparator<String[]>() {
                @Override
                public int compare(String[] a, String[] b) {
                    return a[1].compareToIgnoreCase(b[1]);
                }
            });

            JSONArray arr = new JSONArray();
            for (String[] r : rows) {
                JSONObject o = new JSONObject();
                o.put("pkg", r[0]);
                o.put("label", r[1]);
                arr.put(o);
            }
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("apps", arr);
            return out.toString();
        } catch (Exception e) {
            return fail("list");
        }
    }

    /* ---------------- 权限 / 跳转 ---------------- */

    @JavascriptInterface
    public void openAccessibilitySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void openOverlaySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + act.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void openBatterySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void openAutoStartSettings() {
        String[][] tries = {
                {"miui.intent.action.OP_AUTO_START", ""},
                {"miui.intent.action.APP_PERM_EDITOR", ""},
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"}
        };
        for (String[] t : tries) {
            try {
                Intent i;
                if (t[1].length() == 0) {
                    i = new Intent(t[0]);
                } else {
                    i = new Intent();
                    i.setComponent(new ComponentName(t[0], t[1]));
                }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + act.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void startGuard() {
        GuardService.start(act);
        AppWatcher w = GuardState.watcher();
        if (w != null) w.syncNow();
    }

    @JavascriptInterface
    public String getPackageName_() {
        return act.getPackageName();
    }
}
