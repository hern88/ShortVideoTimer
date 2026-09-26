package com.example.shortvideotimer;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;

/**
 * 负责「使用情况访问权限」的检查和跳转。
 *
 * 为什么需要这个权限？
 *   安卓出于隐私保护，不允许普通 App 随便读取别的 App 的使用记录。
 *   所以必须由用户本人去系统设置里手动打开开关，App 不能自己申请。
 */
public final class UsageAccess {

    private UsageAccess() {
        // 工具类，不需要被 new 出来
    }

    /**
     * 检查用户是否已经授予「使用情况访问」权限。
     *
     * 注意：这个权限不能用 requestPermissions() 弹窗申请，
     *       它是"特殊权限"，只能通过 AppOpsManager 检查。
     *
     * @return true 表示已授权，可以读取使用记录
     */
    public static boolean hasPermission(Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) {
            return false;
        }

        int mode;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10（API 29）以后用这个新方法
            mode = appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.getPackageName());
        } else {
            // 老版本用 checkOpNoThrow（已废弃，但老系统上只能用它）
            mode = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.getPackageName());
        }

        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * 生成跳转到系统「使用情况访问」设置页的 Intent。
     * 用户在这个页面里手动打开本 App 的开关。
     */
    public static Intent settingsIntent() {
        return new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
    }
}
