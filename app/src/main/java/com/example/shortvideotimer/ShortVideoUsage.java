package com.example.shortvideotimer;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 核心：统计「今天」各短视频 App 的前台使用时长。
 *
 * === 为什么不用 UsageStatsManager.queryUsageStats() ===
 * 它返回的是按「完整时间桶」聚合的数据，桶的边界通常不是自然日 0 点，
 * 当天数据经常是空的或者不准。所以这里读原始事件流 queryEvents()，
 * 自己把时间一段段拼起来。
 *
 * === 算法（状态机） ===
 * 维护「哪些 Activity 正在前台」的集合，然后：
 *   有目标 App 进入前台 -> 开始计时
 *   它离开前台         -> 结算这一段
 * 内部用「包名 -> (Activity类名 -> 重数)」而不是一个简单的计数器，
 * 因为一个 App 可能同时有多个 Activity 在前台（App 内部切页面）。
 * 用类名做键，重复的 RESUMED 事件不会把计数搞乱。
 *
 * === 踩过的坑（别改回去） ===
 * 1. 绝对不能用「屏幕亮否」当闸门去过滤 RESUMED 事件。
 *    小米等带息屏显示(AOD)的机型会多发 SCREEN_NON_INTERACTIVE，
 *    一旦配对不上，闸门就会永久卡在 false，之后所有记录全部丢失，
 *    表现就是「一直显示不到 1 分钟」。
 *    解决：把 ACTIVITY_RESUMED 当作「屏幕一定亮着」的证据来纠正状态。
 * 2. ACTIVITY_DESTROYED / END_OF_DAY / CONTINUE_PREVIOUS_DAY 在官方
 *    SDK 里标了 @hide，拿不到，只能用数字字面量。
 */
public final class ShortVideoUsage {

    /** ACTIVITY_DESTROYED 被官方标为 @hide，公开 SDK 拿不到，只能写死它的值 */
    private static final int EVENT_ACTIVITY_DESTROYED = 24;

    /**
     * 包名 -> 中文名。
     * 想加别的 App，在这里加一行就行；删掉一行就不会再统计它。
     */
    private static final Map<String, String> APPS = new LinkedHashMap<>();

    static {
        APPS.put("com.ss.android.ugc.aweme", "抖音");
        APPS.put("com.ss.android.ugc.aweme.lite", "抖音极速版");
        APPS.put("com.smile.gifmaker", "快手");
        APPS.put("com.kuaishou.nebula", "快手极速版");
        APPS.put("com.tencent.weishi", "微视");
        APPS.put("com.xingin.xhs", "小红书");
        APPS.put("tv.danmaku.bili", "哔哩哔哩");
        APPS.put("com.zhiliaoapp.musically", "TikTok");
        APPS.put("com.ss.android.ugc.trill", "TikTok（海外）");
        APPS.put("com.google.android.youtube", "YouTube");
        APPS.put("com.instagram.android", "Instagram");
    }

    private ShortVideoUsage() {
        // 工具类，不需要被 new 出来
    }

    /** 一条统计结果：某个 App 今天用了多久 */
    public static class Item {
        public final String packageName;
        public final long foregroundMs;   // 前台时长，单位毫秒

        Item(String packageName, long foregroundMs) {
            this.packageName = packageName;
            this.foregroundMs = foregroundMs;
        }
    }
    /** 一次查询的完整结果：给用户看的时长 + 给开发者看的诊断信息 */
    public static class Result {
        public final List<Item> items;
        public final long totalMs;
        public final String debug;

        Result(List<Item> items, long totalMs, String debug) {
            this.items = items;
            this.totalMs = totalMs;
            this.debug = debug;
        }
    }
    /** 把包名翻译成中文名；没登记过的就直接显示包名 */
    public static String displayName(String packageName) {
        String name = APPS.get(packageName);
        return name != null ? name : packageName;
    }

    /** 把毫秒变成人话，例如 "1 小时 23 分钟" */
    public static String formatDuration(long ms) {
        long minutes = ms / 60000L;
        if (minutes <= 0) {
            return ms > 0 ? "不到 1 分钟" : "0 分钟";
        }
        long hours = minutes / 60;
        long restMinutes = minutes % 60;
        if (hours > 0) {
            return restMinutes > 0
                    ? hours + " 小时 " + restMinutes + " 分钟"
                    : hours + " 小时";
        }
        return restMinutes + " 分钟";
    }

    /** 今天 00:00:00.000 的时间戳 */
    public static long todayStart() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    /** 统计今天 0 点到现在 */
    public static List<Item> queryToday(Context context) {
        return query(context, todayStart(), System.currentTimeMillis());
    }

    /** 统计今天 0 点到现在 */
    public static Result queryToday(Context context) {
        return query(context, todayStart(), System.currentTimeMillis());
    }

    /**
     * 统计 [startMs, endMs) 区间内的前台时长。
     *
     * 会遍历几万条事件，不要在界面线程里调用，要放到子线程
     * （MainActivity 里已经这么做了）。
     */
    public static Result query(Context context, long startMs, long endMs) {
        UsageStatsManager usm =
                (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);

        Map<String, Long> totalMs = new HashMap<>();
        // 包名 -> (Activity 类名 -> 该 Activity 在前台的重数)
        Map<String, Map<String, Integer>> resumed = new HashMap<>();
        // 包名 -> 最近一次进入前台的时刻（多窗口时用它决定算谁的）
        Map<String, Long> lastResumeAt = new HashMap<>();

        String activePkg = null;      // 当前正在计时的目标 App
        long activeSince = startMs;   // 这段计时的起点

        boolean screenOn = true;
        int eventCount = 0;
        int resumedCount = 0;
        int pausedCount = 0;
        int stoppedCount = 0;

        if (usm != null) {
            UsageEvents events = usm.queryEvents(startMs, endMs);
            UsageEvents.Event event = new UsageEvents.Event();

            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(event);
                eventCount++;

                String pkg = event.getPackageName();
                String cls = event.getClassName();
                if (cls == null) {
                    cls = "";
                }
                boolean isTarget = pkg != null && APPS.containsKey(pkg);
                long ts = event.getTimeStamp();

                switch (event.getEventType()) {

                    case UsageEvents.Event.ACTIVITY_RESUMED:
                        // 关键修复：只要有 Activity 回到前台，屏幕就必然是亮的。
                        // 这样即使系统漏发了 SCREEN_INTERACTIVE，状态也能自动纠正，
                        // 不会像以前那样永久卡住、把之后所有记录都丢掉。
                        screenOn = true;
                        if (isTarget) {
                            resumedCount++;
                            increase(resumed, pkg, cls);
                            lastResumeAt.put(pkg, ts);
                        }
                        break;

                    case UsageEvents.Event.ACTIVITY_PAUSED:
                        if (isTarget) {
                            pausedCount++;
                            decrease(resumed, pkg, cls);
                        }
                        break;

                    case UsageEvents.Event.ACTIVITY_STOPPED:
                        if (isTarget) {
                            stoppedCount++;
                            decrease(resumed, pkg, cls);
                        }
                        break;

                    case EVENT_ACTIVITY_DESTROYED:
                        if (isTarget) {
                            decrease(resumed, pkg, cls);
                        }
                        break;

                    case UsageEvents.Event.SCREEN_NON_INTERACTIVE:
                        // 息屏：安卓会暂停所有 Activity，所以直接清空前台集合，
                        // 避免残留状态被误算。亮屏后系统会重新发 RESUMED。
                        screenOn = false;
                        resumed.clear();
                        lastResumeAt.clear();
                        break;

                    case UsageEvents.Event.SCREEN_INTERACTIVE:
                        screenOn = true;
                        break;

                    case UsageEvents.Event.DEVICE_SHUTDOWN:
                    case UsageEvents.Event.DEVICE_STARTUP:
                        // 关机/重启：之前所有「没配对的开始」都不可信，全部丢掉
                        resumed.clear();
                        lastResumeAt.clear();
                        screenOn = (event.getEventType() == UsageEvents.Event.DEVICE_STARTUP);
                        break;

                    default:
                        // 其他事件（通知、切输入法、锁屏……）这里不关心
                        break;
                }

                // 每次事件之后重新判断：现在到底是哪个目标 App 在前台？
                // 一旦切换，就把上一段结算掉。
                String next = pickActive(screenOn, resumed, lastResumeAt);
                boolean changed = (next == null) ? (activePkg != null) : !next.equals(activePkg);
                if (changed) {
                    if (activePkg != null && ts > activeSince) {
                        add(totalMs, activePkg, ts - activeSince);
                    }
                    activePkg = next;
                    activeSince = ts;
                }
            }
        }

        // 到「此刻」为止还挂在前台的，把最后一段补上
        if (activePkg != null && endMs > activeSince) {
            add(totalMs, activePkg, endMs - activeSince);
        }

        // ---- 汇总 ----
        List<Item> items = new ArrayList<>();
        long sum = 0;
        for (Map.Entry<String, Long> entry : totalMs.entrySet()) {
            items.add(new Item(entry.getKey(), entry.getValue()));
            sum += entry.getValue();
        }
        Collections.sort(items, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return Long.compare(b.foregroundMs, a.foregroundMs);
            }
        });

        // ---- 调试信息（定位问题用，稳定之后可以删掉）----
        long sysMs = systemStatsMs(context, startMs, endMs);
        StringBuilder dbg = new StringBuilder();
        dbg.append("【调试信息】\n");
        dbg.append("读取事件：").append(eventCount).append(" 条\n");
        dbg.append("目标App：前台 ").append(resumedCount)
           .append(" 次 / 暂停 ").append(pausedCount)
           .append(" 次 / 停止 ").append(stoppedCount).append(" 次\n");
        dbg.append("事件累计：").append(sum / 1000).append(" 秒\n");
        dbg.append("系统统计：").append(sysMs / 1000).append(" 秒\n");
        dbg.append("屏幕状态：").append(screenOn ? "亮" : "灭").append("\n");
        dbg.append("未闭合会话：")
           .append(activePkg == null ? "无" : displayName(activePkg));

        return new Result(items, sum, dbg.toString());
    }

    /**
     * 选出「此刻正在前台的目标 App」。
     * 息屏时返回 null；多窗口时取最近一次进入前台的那个。
     */
    private static String pickActive(
            boolean screenOn,
            Map<String, Map<String, Integer>> resumed,
            Map<String, Long> lastResumeAt) {

        if (!screenOn) {
            return null;
        }
        String best = null;
        long bestTs = -1L;
        for (Map.Entry<String, Map<String, Integer>> entry : resumed.entrySet()) {
            if (!hasAnyResumed(entry.getValue())) {
                continue;
            }
            Long t = lastResumeAt.get(entry.getKey());
            long ts = (t == null ? 0L : t);
            if (ts >= bestTs) {
                bestTs = ts;
                best = entry.getKey();
            }
        }
        return best;
    }

    /** 这个包里还有 Activity 处于前台吗？ */
    private static boolean hasAnyResumed(Map<String, Integer> byClass) {
        if (byClass == null) {
            return false;
        }
        for (Integer count : byClass.values()) {
            if (count != null && count > 0) {
                return true;
            }
        }
        return false;
    }

    private static void increase(
            Map<String, Map<String, Integer>> map, String pkg, String cls) {
        Map<String, Integer> byClass = map.get(pkg);
        if (byClass == null) {
            byClass = new HashMap<>();
            map.put(pkg, byClass);
        }
        Integer old = byClass.get(cls);
        byClass.put(cls, (old == null ? 0 : old) + 1);
    }

    private static void decrease(
            Map<String, Map<String, Integer>> map, String pkg, String cls) {
        Map<String, Integer> byClass = map.get(pkg);
        if (byClass == null) {
            return;
        }
        Integer old = byClass.get(cls);
        if (old == null) {
            return;
        }
        if (old <= 1) {
            byClass.remove(cls);
        } else {
            byClass.put(cls, old - 1);
        }
    }

    /**
     * 交叉验证用的系统统计值。
     * 它是按「完整时间桶」聚合的，和我们自己算的不会完全一致，
     * 只用来判断「到底是算法算错了，还是系统根本没记录」。
     */
    private static long systemStatsMs(Context context, long startMs, long endMs) {
        UsageStatsManager usm =
                (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            return 0L;
        }
        long sum = 0L;
        try {
            List<UsageStats> list =
                    usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startMs, endMs);
            if (list != null) {
                for (UsageStats stats : list) {
                    if (stats != null && APPS.containsKey(stats.getPackageName())) {
                        sum += stats.getTotalTimeInForeground();
                    }
                }
            }
        } catch (Exception ignored) {
            // 只是个诊断值，拿不到就算了
        }
        return sum;
    }

    /** 小工具：给某个 App 累加时长 */
    private static void add(Map<String, Long> map, String key, long delta) {
        if (delta <= 0) {
            return;
        }
        Long old = map.get(key);
        map.put(key, (old == null ? 0L : old) + delta);
    }
}
