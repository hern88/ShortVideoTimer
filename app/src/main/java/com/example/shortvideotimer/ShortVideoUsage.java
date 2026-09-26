package com.example.shortvideotimer;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 核心：统计「今天」各短视频 App 的前台使用时长。
 *
 * 为什么不用 UsageStatsManager.queryUsageStats()？
 *   它返回的是按「完整时间桶」聚合的数据，桶的边界通常不是自然日 0 点，
 *   所以当天的数据经常是空的或者不准 —— 这是新手最容易踩的坑。
 *
 * 所以这里改成读原始事件流 queryEvents()，自己把时间一段段拼起来：
 *   收到 ACTIVITY_RESUMED  -> 记下开始时间
 *   收到 ACTIVITY_PAUSED   -> 结束时间 - 开始时间，累加
 *   收到息屏事件           -> 把所有还在计时的都结算掉（息屏不算使用）
 */
public final class ShortVideoUsage {

    /**
     * 包名 -> 中文名。
     * 想加别的 App，在这里加一行就行；删掉一行就不会再统计它。
     */
    private static final Map<String, String> APPS = new HashMap<>();

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

    /**
     * 统计 [startMs, endMs) 区间内的前台时长。
     *
     * 这个方法会遍历几万条事件，不要在界面线程里调用，
     * 要放到子线程（MainActivity 里已经这么做了）。
     */
    public static List<Item> query(Context context, long startMs, long endMs) {
        UsageStatsManager usm =
                (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);

        Map<String, Long> totalMs = new HashMap<>();      // 每个 App 的累计时长
        Map<String, Integer> depth = new HashMap<>();     // 该 App 当前有几个界面在前台
        Map<String, Long> startAt = new HashMap<>();      // 该 App 本次开始计时的时刻
        boolean screenOn = true;

        if (usm != null) {
            UsageEvents events = usm.queryEvents(startMs, endMs);
            UsageEvents.Event event = new UsageEvents.Event();

            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(event);

                String pkg = event.getPackageName();
                boolean isTarget = pkg != null && APPS.containsKey(pkg);

                switch (event.getEventType()) {

                    case UsageEvents.Event.ACTIVITY_RESUMED:
                        // App 的一个界面来到前台。用计数器而不是直接覆盖开始时间，
                        // 是因为 App 内部切换界面时也会触发，那样会重复计算。
                        if (isTarget && screenOn) {
                            int d = depth.containsKey(pkg) ? depth.get(pkg) : 0;
                            if (d == 0) {
                                startAt.put(pkg, event.getTimeStamp());
                            }
                            depth.put(pkg, d + 1);
                        }
                        break;

                    case UsageEvents.Event.ACTIVITY_PAUSED:
                    case UsageEvents.Event.ACTIVITY_STOPPED:
                        // 界面离开前台。计数减到 0 才说明整个 App 退出了前台。
                        if (isTarget) {
                            int d = (depth.containsKey(pkg) ? depth.get(pkg) : 0) - 1;
                            if (d <= 0) {
                                depth.put(pkg, 0);
                                Long begin = startAt.remove(pkg);
                                if (begin != null) {
                                    add(totalMs, pkg, event.getTimeStamp() - begin);
                                }
                            } else {
                                depth.put(pkg, d);
                            }
                        }
                        break;

                    case UsageEvents.Event.SCREEN_NON_INTERACTIVE:
                        // 息屏了，把所有还在计时的 App 结算掉：息屏不算"使用"
                        screenOn = false;
                        for (Map.Entry<String, Long> entry : startAt.entrySet()) {
                            add(totalMs, entry.getKey(), event.getTimeStamp() - entry.getValue());
                        }
                        startAt.clear();
                        depth.clear();
                        break;

                    case UsageEvents.Event.SCREEN_INTERACTIVE:
                        // 亮屏了，后面的 RESUMED 事件会重新开始计时
                        screenOn = true;
                        break;

                    default:
                        // 其他事件（通知、切输入法、锁屏……）这里不关心
                        break;
                }
            }
        }

        // 统计到"此刻"为止：如果某个 App 现在还在前台，
        // 它没有 PAUSED 事件，需要手动补上从开始到 endMs 这一段。
        if (screenOn) {
            for (Map.Entry<String, Long> entry : startAt.entrySet()) {
                add(totalMs, entry.getKey(), endMs - entry.getValue());
            }
        }

        List<Item> result = new ArrayList<>();
        for (Map.Entry<String, Long> entry : totalMs.entrySet()) {
            result.add(new Item(entry.getKey(), entry.getValue()));
        }

        // 用时长的排前面
        Collections.sort(result, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return Long.compare(b.foregroundMs, a.foregroundMs);
            }
        });
        return result;
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
