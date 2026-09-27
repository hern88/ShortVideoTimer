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
     * 一条统计规则。
     *
     * classPrefix 为 null  -> 这个 App 的**所有界面**都算（比如抖音整个都算）
     * classPrefix 有值      -> 只有类名以它开头的界面才算
     *
     * 第二种是为了把「大 App 里的某个功能」拆出来单独统计。
     * 例：微信视频号不是独立 App，但它的界面类名有固定前缀，
     *     所以可以用前缀把它从微信里挑出来，而聊天、朋友圈不会被算进去。
     */
    private static final class Rule {
        final String pkg;
        final String classPrefix;
        final String label;

        Rule(String pkg, String classPrefix, String label) {
            this.pkg = pkg;
            this.classPrefix = classPrefix;
            this.label = label;
        }
    }

    /** 所有统计规则，按声明顺序匹配，先命中的优先 */
    private static final List<Rule> RULES = new ArrayList<>();

    /** 包名 -> 该包的规则列表（加速匹配） */
    private static final Map<String, List<Rule>> RULES_BY_PKG = new LinkedHashMap<>();

    private static void rule(String pkg, String classPrefix, String label) {
        Rule r = new Rule(pkg, classPrefix, label);
        RULES.add(r);
        List<Rule> list = RULES_BY_PKG.get(pkg);
        if (list == null) {
            list = new ArrayList<>();
            RULES_BY_PKG.put(pkg, list);
        }
        list.add(r);
    }

    static {
        // ---- 独立 App：整个包都算 ----
        rule("com.ss.android.ugc.aweme", null, "抖音");
        rule("com.ss.android.ugc.aweme.lite", null, "抖音极速版");
        rule("com.smile.gifmaker", null, "快手");
        rule("com.kuaishou.nebula", null, "快手极速版");
        rule("com.tencent.weishi", null, "微视");
        rule("com.xingin.xhs", null, "小红书");
        rule("tv.danmaku.bili", null, "哔哩哔哩");
        rule("com.zhiliaoapp.musically", null, "TikTok");
        rule("com.ss.android.ugc.trill", null, "TikTok（海外）");
        rule("com.google.android.youtube", null, "YouTube");
        rule("com.instagram.android", null, "Instagram");

        // ---- 微信视频号：只算视频号，不算聊天和朋友圈 ----
        // 前缀来自实测（微信 8.0.78，Android 16）：
        //   plugin.finder.ui.FinderHomeAffinityUI      视频号主页（刷视频）
        //   plugin.finder.feed.ui.FinderProfileTimeLineUI  某人的视频号主页
        //   plugin.finder.feed.ui.FinderLiveVisitorAffinityUI  视频号直播
        // 微信以后如果改了类名，只要还保留 plugin.finder 这一段就依然有效。
        rule("com.tencent.mm", "com.tencent.mm.plugin.finder.", "微信视频号");
    }

    /**
     * 把「包名 + 界面类名」映射成统计标签。
     * 没有任何规则命中时返回 null，表示这个界面不算短视频。
     */
    private static String labelFor(String pkg, String cls) {
        List<Rule> list = RULES_BY_PKG.get(pkg);
        if (list == null) {
            return null;
        }
        for (Rule r : list) {
            if (r.classPrefix == null || cls.startsWith(r.classPrefix)) {
                return r.label;
            }
        }
        return null;
    }

    private ShortVideoUsage() {
        // 工具类，不需要被 new 出来
    }

    /** 一条统计结果：某个标签（App 或 App 里的功能）今天用了多久 */
    public static class Item {
        public final String label;        // 显示名，例如「抖音」「微信视频号」
        public final long foregroundMs;   // 前台时长，单位毫秒

        Item(String label, long foregroundMs) {
            this.label = label;
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

        String activeLabel = null;    // 当前正在计时的标签（App 名或功能名）
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
                // 这个包里有我们要统计的东西吗？（微信也算，因为它里面有视频号）
                boolean isCandidate = pkg != null && RULES_BY_PKG.containsKey(pkg);
                // 这个具体的界面算不算短视频？（微信的聊天界面就不算）
                boolean isCounted = isCandidate && labelFor(pkg, cls) != null;
                long ts = event.getTimeStamp();

                switch (event.getEventType()) {

                    case UsageEvents.Event.ACTIVITY_RESUMED:
                        // 关键修复：只要有 Activity 回到前台，屏幕就必然是亮的。
                        // 这样即使系统漏发了 SCREEN_INTERACTIVE，状态也能自动纠正，
                        // 不会像以前那样永久卡住、把之后所有记录都丢掉。
                        screenOn = true;
                        if (isCandidate) {
                            if (isCounted) {
                                resumedCount++;
                            }
                            increase(resumed, pkg, cls);
                            lastResumeAt.put(pkg, ts);
                        }
                        break;

                    case UsageEvents.Event.ACTIVITY_PAUSED:
                        if (isCandidate) {
                            if (isCounted) {
                                pausedCount++;
                            }
                            decrease(resumed, pkg, cls);
                        }
                        break;

                    case UsageEvents.Event.ACTIVITY_STOPPED:
                        if (isCandidate) {
                            if (isCounted) {
                                stoppedCount++;
                            }
                            decrease(resumed, pkg, cls);
                        }
                        break;

                    case EVENT_ACTIVITY_DESTROYED:
                        if (isCandidate) {
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

                // 每次事件之后重新判断：现在到底是哪个标签在前台？
                // 一旦切换，就把上一段结算掉。
                String next = pickActiveLabel(screenOn, resumed, lastResumeAt);
                boolean changed = (next == null) ? (activeLabel != null) : !next.equals(activeLabel);
                if (changed) {
                    if (activeLabel != null && ts > activeSince) {
                        add(totalMs, activeLabel, ts - activeSince);
                    }
                    activeLabel = next;
                    activeSince = ts;
                }
            }
        }

        // 到「此刻」为止还挂在前台的，把最后一段补上
        if (activeLabel != null && endMs > activeSince) {
            add(totalMs, activeLabel, endMs - activeSince);
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
        long wxMs = weChatTotalMs(context, startMs, endMs);
        StringBuilder dbg = new StringBuilder();
        dbg.append("【调试信息】\n");
        dbg.append("读取事件：").append(eventCount).append(" 条\n");
        dbg.append("命中界面：前台 ").append(resumedCount)
           .append(" 次 / 暂停 ").append(pausedCount)
           .append(" 次 / 停止 ").append(stoppedCount).append(" 次\n");
        dbg.append("事件累计：").append(sum / 1000).append(" 秒\n");
        dbg.append("系统统计：").append(sysMs / 1000).append(" 秒\n");
        dbg.append("微信整包：").append(wxMs / 1000).append(" 秒\n");
        dbg.append("屏幕状态：").append(screenOn ? "亮" : "灭").append("\n");
        dbg.append("未闭合会话：")
           .append(activeLabel == null ? "无" : activeLabel);

        return new Result(items, sum, dbg.toString());
    }

    /**
     * 选出「此刻正在前台的那个统计标签」。
     *
     * 注意返回的是**标签**而不是包名 —— 因为同一个包可能对应多个标签
     * （微信 -> 视频号 / 其他功能），必须看它当前是哪个界面在前台，
     * 才能决定这段时间算给谁。
     *
     * 息屏时返回 null；多窗口时取最近一次进入前台的那个。
     */
    private static String pickActiveLabel(
            boolean screenOn,
            Map<String, Map<String, Integer>> resumed,
            Map<String, Long> lastResumeAt) {

        if (!screenOn) {
            return null;
        }
        String bestLabel = null;
        long bestTs = -1L;

        for (Map.Entry<String, Map<String, Integer>> entry : resumed.entrySet()) {
            if (!hasAnyResumed(entry.getValue())) {
                continue;
            }
            String pkg = entry.getKey();
            Long t = lastResumeAt.get(pkg);
            long ts = (t == null ? 0L : t);
            if (ts < bestTs) {
                continue;
            }
            // 这个包现在有哪些界面在前台？把它们逐个映射成标签
            String label = null;
            for (Map.Entry<String, Integer> byClass : entry.getValue().entrySet()) {
                Integer count = byClass.getValue();
                if (count != null && count > 0) {
                    String l = labelFor(pkg, byClass.getKey());
                    if (l != null) {
                        label = l;
                        break;
                    }
                }
            }
            if (label != null) {
                bestLabel = label;
                bestTs = ts;
            }
        }
        return bestLabel;
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
     * 交叉验证用的系统统计值：只统计「整个 App 都算」的目标。
     *
     * 微信不能算进来 —— 系统只给整个微信的时长，
     * 而我们只统计其中的视频号，两者本来就不可比。
     *
     * 注意它按「完整时间桶」聚合，和我们自己算的不会完全一致，
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
                    if (stats != null && isWholeAppTarget(stats.getPackageName())) {
                        sum += stats.getTotalTimeInForeground();
                    }
                }
            }
        } catch (Exception ignored) {
            // 只是个诊断值，拿不到就算了
        }
        return sum;
    }

    /**
     * 微信整包的系统统计时长（诊断用）。
     * 用来判断：是「你本来就没怎么刷视频号」，还是「类名没匹配上漏算了」。
     */
    private static long weChatTotalMs(Context context, long startMs, long endMs) {
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
                    if (stats != null && "com.tencent.mm".equals(stats.getPackageName())) {
                        sum += stats.getTotalTimeInForeground();
                    }
                }
            }
        } catch (Exception ignored) {
            // 只是个诊断值，拿不到就算了
        }
        return sum;
    }

    /** 这个包是不是「整个 App 都算」的目标？微信这种只统计一部分的不算。 */
    private static boolean isWholeAppTarget(String pkg) {
        List<Rule> list = RULES_BY_PKG.get(pkg);
        if (list == null) {
            return false;
        }
        for (Rule r : list) {
            if (r.classPrefix == null) {
                return true;
            }
        }
        return false;
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
