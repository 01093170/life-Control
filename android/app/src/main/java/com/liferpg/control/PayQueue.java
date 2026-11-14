package com.liferpg.control;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 短信交易的落地队列。
 * 短信广播可能比 WebView 活得久（App 被杀、没打开），所以先落盘成 JSON 数组，
 * 前端回到前台时一次取走并清空。上限 100 条，超出丢最旧的，避免无限增长。
 */
public class PayQueue {

    private static final int MAX = 100;
    private static final String DIR = "pay-guard";
    private static final String NAME = "pay_queue.json";

    private static File file(Context ctx) {
        File d = new File(ctx.getApplicationContext().getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return new File(d, NAME);
    }

    private static JSONArray read(Context ctx) {
        JSONArray arr = new JSONArray();
        try {
            File f = file(ctx);
            if (!f.exists() || f.length() == 0) return arr;
            byte[] buf = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int n = in.read(buf);
            in.close();
            if (n > 0) arr = new JSONArray(new String(buf, 0, n, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        return arr;
    }

    private static void write(Context ctx, JSONArray arr) {
        try {
            FileOutputStream out = new FileOutputStream(file(ctx));
            out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
        } catch (Exception ignored) {
        }
    }

    /** 入队。同 id 视为同一条，覆盖而不重复。 */
    public static synchronized void push(Context ctx, JSONObject tx) {
        if (tx == null) return;
        JSONArray old = read(ctx);
        JSONArray out = new JSONArray();
        String id = tx.optString("id", "");
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (o == null) continue;
            if (id.length() > 0 && id.equals(o.optString("id", ""))) continue;
            out.put(o);
        }
        out.put(tx);
        while (out.length() > MAX) out.remove(0);
        write(ctx, out);
    }

    /** 取走全部并清空。 */
    public static synchronized JSONArray drain(Context ctx) {
        JSONArray arr = read(ctx);
        write(ctx, new JSONArray());
        return arr;
    }

    public static synchronized int size(Context ctx) {
        return read(ctx).length();
    }
}
