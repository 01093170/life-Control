package com.liferpg.control;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;

import org.json.JSONObject;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 银行/支付短信 → 交易记录 → PayQueue。
 *
 * 刻意做得很轻：只有静态注册的广播接收器，没有常驻服务、没有轮询、没有定时器。
 * 短信到达才醒一次，跑完（几十微秒）立刻释放。分类与落库全在前端做，
 * 这里只管"这条短信是不是一笔支出、多少钱、哪个商户"。
 */
public class SmsReceiver extends BroadcastReceiver {

    /* 金额：先认"人民币/¥ 35.00"，再认"35.00元"；跳过余额、额度、积分这些干扰数字 */
    private static final Pattern AMT_CNY = Pattern.compile("(?:人民币|RMB|CNY|￥|¥)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)");
    private static final Pattern AMT_YUAN = Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*元");
    /* 商户：在「星巴克」消费 / 商户：星巴克 */
    private static final Pattern MER_IN  = Pattern.compile("在\\s*([^，,。；;\\s]{2,24}?)\\s*(?:消费|支付|付款|购买|用餐|加油)");
    private static final Pattern MER_TAG = Pattern.compile("商[户家][名称]*[:：]?\\s*([^，,。；;\\s]{2,24})");
    /* 日期：先认带年份的，再认「11月13日」这种（中国银行常用） */
    private static final Pattern DATE_FULL = Pattern.compile("(20[0-9]{2})[-/年]([0-9]{1,2})[-/月]([0-9]{1,2})");
    private static final Pattern DATE_MD   = Pattern.compile("([0-9]{1,2})月([0-9]{1,2})日");
    /* 来源标签：【中国银行】→ 中国银行；网上支付 / 快捷支付 / ATM → 渠道 */
    private static final Pattern BANK_TAG = Pattern.compile("【([^】]{2,12})】");
    private static final Pattern CHANNEL  = Pattern.compile(
            "(网上支付|快捷支付|网上银行|手机银行|云闪付|支付宝|微信支付|财付通|银联|ATM|POS|扫码)");

    /* 「支取」是 ATM 取现和转账支取，银行短信里很常见，不能漏 */
    private static final String[] OUT_WORDS  = {"消费", "支出", "支付", "付款", "扣款", "转出", "取现", "缴费", "代扣", "支取", "转账", "汇款"};
    private static final String[] IN_WORDS   = {"收入", "存入", "转入", "到账", "入账", "退款", "退货", "撤销", "冲正", "代发", "工资", "利息", "红包", "向您转账"};
    private static final String[] SKIP_WORDS = {"验证码", "动态码", "校验码", "积分"};

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (ctx == null || intent == null) return;
        Bundle b = intent.getExtras();
        if (b == null) return;
        Object[] pdus = (Object[]) b.get("pdus");
        if (pdus == null) return;
        String fmt = b.getString("format");
        for (Object p : pdus) {
            if (!(p instanceof byte[])) continue;
            String body;
            try {
                SmsMessage m = SmsMessage.createFromPdu((byte[]) p, fmt);
                body = m == null ? null : m.getMessageBody();
            } catch (Throwable t) {
                continue;
            }
            JSONObject tx = parse(body);
            if (tx != null) PayQueue.push(ctx, tx);
        }
    }

    /** 解析一条短信；不是支出就返回 null。纯字符串运算，不做 IO。 */
    static JSONObject parse(String body) {
        if (body == null) return null;
        String s = body.replace('\n', ' ').replace('\r', ' ').trim();
        if (s.length() < 6) return null;

        for (String w : SKIP_WORDS) {
            if (s.contains(w)) return null;
        }

        boolean isOut = false;
        for (String w : OUT_WORDS) {
            if (s.contains(w)) { isOut = true; break; }
        }
        if (!isOut) return null;

        for (String w : IN_WORDS) {
            if (s.contains(w)) return null;      // 收入 / 退款 / 冲正 一律不记
        }

        double amount = firstAmount(s);
        if (!(amount > 0)) return null;

        String merchant = pick(s, MER_IN);
        if (merchant == null || merchant.length() < 2) merchant = pick(s, MER_TAG);
        if (merchant != null) {
            merchant = merchant.trim();
            if (merchant.length() > 24) merchant = merchant.substring(0, 24);
        }

        try {
            JSONObject o = new JSONObject();
            o.put("id", digest(s));
            o.put("amount", amount);
            o.put("merchant", merchant == null ? "" : merchant);
            /* channel 只用来在流水里显示来源，绝不参与商户记忆匹配，
               否则所有中国银行短信会被一次手改锁死成同一类别 */
            o.put("channel", channel(s));
            o.put("date", pickDate(s));
            o.put("raw", s.length() > 160 ? s.substring(0, 160) : s);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** 来源：银行名 + 渠道，如「中国银行·网上支付」。没有商户时用它让流水不至于只有两个字。 */
    private static String channel(String s) {
        String bank = pick(s, BANK_TAG);
        String way = pick(s, CHANNEL);
        if (bank == null) bank = "";
        if (way == null) way = "";
        if (bank.length() > 0 && way.length() > 0) return bank + "·" + way;
        if (bank.length() > 0) return bank;
        return way;
    }

    private static double firstAmount(String s) {
        Matcher m = AMT_CNY.matcher(s);
        while (m.find()) {
            if (nearBalance(s, m.start())) continue;
            double v = toNum(m.group(1));
            if (v > 0) return v;
        }
        m = AMT_YUAN.matcher(s);
        while (m.find()) {
            if (nearBalance(s, m.start())) continue;
            double v = toNum(m.group(1));
            if (v > 0) return v;
        }
        return -1;
    }

    private static boolean nearBalance(String s, int idx) {
        int from = Math.max(0, idx - 8);
        String pre = s.substring(from, idx);
        return pre.contains("余额") || pre.contains("额度") || pre.contains("可用")
                || pre.contains("限额") || pre.contains("剩余");
    }

    private static double toNum(String v) {
        try {
            return Double.parseDouble(v.replace(",", ""));
        } catch (Exception e) {
            return -1;
        }
    }

    private static String pick(String s, Pattern p) {
        try {
            Matcher m = p.matcher(s);
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String pickDate(String s) {
        try {
            Matcher m = DATE_FULL.matcher(s);
            if (m.find()) {
                int y = Integer.parseInt(m.group(1));
                int mo = Integer.parseInt(m.group(2));
                int d = Integer.parseInt(m.group(3));
                if (mo >= 1 && mo <= 12 && d >= 1 && d <= 31) {
                    return String.format(Locale.US, "%04d-%02d-%02d", y, mo, d);
                }
            }
        } catch (Exception ignored) {
        }
        /* 「11月13日」不带年份：按当年补。若补出来比收到时间还晚一周以上，
           说明是跨年短信（1 月初收到 12 月 31 日的），退回上一年。 */
        try {
            Matcher m = DATE_MD.matcher(s);
            if (m.find()) {
                int mo = Integer.parseInt(m.group(1));
                int d = Integer.parseInt(m.group(2));
                if (mo >= 1 && mo <= 12 && d >= 1 && d <= 31) {
                    Calendar cal = Calendar.getInstance();
                    int y = cal.get(Calendar.YEAR);
                    cal.set(y, mo - 1, d, 0, 0, 0);
                    cal.set(Calendar.MILLISECOND, 0);
                    if (cal.getTimeInMillis() - System.currentTimeMillis() > 7L * 24 * 3600 * 1000) {
                        y -= 1;
                    }
                    return String.format(Locale.US, "%04d-%02d-%02d", y, mo, d);
                }
            }
        } catch (Exception ignored) {
        }
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    /** 同一条短信的指纹，用来去重（重复投递 / 双卡各收一条）。 */
    private static String digest(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format(Locale.US, "%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
