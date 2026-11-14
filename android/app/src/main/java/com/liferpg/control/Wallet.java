package com.liferpg.control;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 娱乐额度钱包。
 * 数据落在 filesDir/reward-guard/wallet.json，原生侧与 WebView 侧共用同一份。
 * 单独存文件（不塞 localStorage）是为了将来可以换成 Magisk 常驻守护脚本读写同一份数据。
 */
public class Wallet {

    private static final long DEFAULT_REWARD_MS = 30L * 60L * 1000L;
    private static Wallet sInstance;

    private final Context app;
    private final File dir;
    private final File file;
    private JSONObject data;

    private Wallet(Context ctx) {
        app = ctx.getApplicationContext();
        dir = new File(app.getFilesDir(), "reward-guard");
        if (!dir.exists()) dir.mkdirs();
        file = new File(dir, "wallet.json");
        data = read();
    }

    public static synchronized Wallet get(Context ctx) {
        if (sInstance == null) sInstance = new Wallet(ctx);
        return sInstance;
    }

    private JSONObject read() {
        JSONObject o = new JSONObject();
        try {
            if (file.exists() && file.length() > 0) {
                byte[] buf = new byte[(int) file.length()];
                FileInputStream in = new FileInputStream(file);
                int n = in.read(buf);
                in.close();
                if (n > 0) o = new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
        return normalize(o);
    }

    private JSONObject normalize(JSONObject o) {
        try {
            if (!o.has("balanceMs")) o.put("balanceMs", 0L);
            if (!o.has("rewardMinutes")) o.put("rewardMinutes", (int) (DEFAULT_REWARD_MS / 60000L));
            if (!o.has("enforce")) o.put("enforce", true);
            if (!o.has("guardOn")) o.put("guardOn", true);
            if (!o.has("apps")) o.put("apps", new JSONArray());
            if (!o.has("earnedMs")) o.put("earnedMs", 0L);
            if (!o.has("usedMs")) o.put("usedMs", 0L);
        } catch (Exception ignored) {
        }
        return o;
    }

    private void flush() {
        try {
            File tmp = new File(dir, "wallet.json.tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(data.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
            if (file.exists() && !file.delete()) {
                // 删不掉就直接覆盖写
                FileOutputStream direct = new FileOutputStream(file);
                direct.write(data.toString().getBytes(StandardCharsets.UTF_8));
                direct.flush();
                direct.close();
                return;
            }
            if (!tmp.renameTo(file)) {
                FileOutputStream direct = new FileOutputStream(file);
                direct.write(data.toString().getBytes(StandardCharsets.UTF_8));
                direct.flush();
                direct.close();
            }
        } catch (Exception ignored) {
        }
    }

    /* ---------------- 读取 ---------------- */

    public synchronized long balance() {
        return data.optLong("balanceMs", 0L);
    }

    public synchronized int rewardMinutes() {
        return data.optInt("rewardMinutes", 30);
    }

    public synchronized boolean enforce() {
        return data.optBoolean("enforce", true);
    }

    public synchronized boolean guardOn() {
        return data.optBoolean("guardOn", true);
    }

    public synchronized JSONArray apps() {
        JSONArray a = data.optJSONArray("apps");
        return a == null ? new JSONArray() : a;
    }

    public synchronized boolean isManaged(String pkg) {
        if (pkg == null || pkg.length() == 0) return false;
        JSONArray a = apps();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && pkg.equals(o.optString("pkg"))) return true;
        }
        return false;
    }

    /* ---------------- 写入 ---------------- */

    /** 打卡发额度，返回新的余额（毫秒）。 */
    public synchronized long addReward() {
        long add = Math.max(1, rewardMinutes()) * 60000L;
        long bal = balance() + add;
        try {
            data.put("balanceMs", bal);
            data.put("earnedMs", data.optLong("earnedMs", 0L) + add);
        } catch (Exception ignored) {
        }
        flush();
        return bal;
    }

    /** 手动加时长（分钟，可为负）。 */
    public synchronized long addMinutes(int minutes) {
        long bal = Math.max(0L, balance() + (long) minutes * 60000L);
        try {
            data.put("balanceMs", bal);
        } catch (Exception ignored) {
        }
        flush();
        return bal;
    }

    /** 消耗时长，返回扣完后的余额。 */
    public synchronized long charge(long ms) {
        if (ms <= 0) return balance();
        long bal = balance();
        long real = Math.min(bal, ms);
        bal -= real;
        try {
            data.put("balanceMs", bal);
            data.put("usedMs", data.optLong("usedMs", 0L) + real);
        } catch (Exception ignored) {
        }
        flush();
        return bal;
    }

    public synchronized void setRewardMinutes(int m) {
        try {
            data.put("rewardMinutes", Math.max(1, Math.min(600, m)));
        } catch (Exception ignored) {
        }
        flush();
    }

    public synchronized void setEnforce(boolean b) {
        try {
            data.put("enforce", b);
        } catch (Exception ignored) {
        }
        flush();
    }

    public synchronized void setGuardOn(boolean b) {
        try {
            data.put("guardOn", b);
        } catch (Exception ignored) {
        }
        flush();
    }

    public synchronized void addApp(String pkg, String label) {
        pkg = pkg == null ? "" : pkg.trim();
        if (pkg.length() == 0 || isManaged(pkg)) return;
        try {
            JSONObject o = new JSONObject();
            o.put("pkg", pkg);
            o.put("label", label == null ? "" : label);
            apps().put(o);
        } catch (Exception ignored) {
        }
        flush();
    }

    public synchronized void removeApp(String pkg) {
        JSONArray a = apps();
        JSONArray out = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            if (pkg != null && pkg.equals(o.optString("pkg"))) continue;
            out.put(o);
        }
        try {
            data.put("apps", out);
        } catch (Exception ignored) {
        }
        flush();
    }

    public synchronized void reset() {
        try {
            data.put("balanceMs", 0L);
            data.put("earnedMs", 0L);
            data.put("usedMs", 0L);
        } catch (Exception ignored) {
        }
        flush();
    }

    /** 给前端用的快照。 */
    public synchronized JSONObject snapshot() {
        JSONObject o = new JSONObject();
        try {
            o.put("balanceMs", balance());
            o.put("rewardMinutes", rewardMinutes());
            o.put("enforce", enforce());
            o.put("guardOn", guardOn());
            o.put("earnedMs", data.optLong("earnedMs", 0L));
            o.put("usedMs", data.optLong("usedMs", 0L));
            o.put("apps", apps());
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 用包名反查应用名，查不到返回空串。 */
    public String labelOf(String pkg) {
        try {
            PackageManager pm = app.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(info);
            return cs == null ? "" : cs.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
