package com.liferpg.control;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * root 命令执行器。
 * 每次命令单开一个 su 进程，Magisk 授权一次后后续不再弹窗。
 */
public class RootShell {

    public interface Callback {
        void onResult(boolean ok, String output);
    }

    /** 检测 root 是否可用（异步）。 */
    public static void checkRoot(final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String out = exec("id", 4000);
                boolean ok = out != null && out.contains("uid=0");
                if (cb != null) cb.onResult(ok, out == null ? "" : out);
            }
        }, "root-check").start();
    }

    /** 强制停止某个应用（异步）。 */
    public static void forceStop(final String pkg, final Callback cb) {
        if (pkg == null || pkg.length() == 0) {
            if (cb != null) cb.onResult(false, "empty pkg");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                String out = exec("am force-stop " + pkg, 5000);
                if (cb != null) cb.onResult(out != null, out == null ? "" : out);
            }
        }, "root-kill").start();
    }

    /**
     * 执行一条 su 命令，返回合并后的输出；超时或失败返回 null。
     */
    public static String exec(String cmd, int timeoutMs) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            final Process proc = p;
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader br = new BufferedReader(
                                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line).append('\n');
                    } catch (Exception ignored) {
                    }
                }
            }, "root-reader");
            reader.setDaemon(true);
            reader.start();

            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                try {
                    int code = p.exitValue();
                    reader.join(500);
                    return sb.toString();
                } catch (IllegalThreadStateException running) {
                    try {
                        Thread.sleep(80);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
            // 超时（多半是 Magisk 弹窗没人点）
            try {
                OutputStream os = p.getOutputStream();
                os.write("exit\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (Exception ignored) {
            }
            p.destroy();
            return null;
        } catch (Exception e) {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception ignored) {
                }
            }
            return null;
        }
    }
}
