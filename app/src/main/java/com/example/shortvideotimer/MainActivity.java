package com.example.shortvideotimer;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/**
 * MVP 唯一的一个界面。
 *
 * 它只做三件事：
 *   1. 检查有没有「使用情况访问」权限，没有就引导用户去开
 *   2. 调 ShortVideoUsage 算出今天各个短视频 App 用了多久
 *   3. 把数字显示出来
 *
 * 没有登录、没有联网、没有排行榜、没有数据库 —— 这些以后按需再加。
 */
public class MainActivity extends AppCompatActivity {

    private TextView tvTotal;    // 大数字：今日总时长
    private TextView tvDetail;   // 卡片里的分项明细
    private TextView tvHint;     // 授权引导文字
    private Button btnGrant;     // 「去开启权限」按钮
    private Button btnRefresh;   // 「刷新」按钮

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvTotal = findViewById(R.id.tv_total);
        tvDetail = findViewById(R.id.tv_detail);
        tvHint = findViewById(R.id.tv_hint);
        btnGrant = findViewById(R.id.btn_grant);
        btnRefresh = findViewById(R.id.btn_refresh);

        btnGrant.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 跳到系统设置页，让用户自己打开开关
                startActivity(UsageAccess.settingsIntent());
            }
        });

        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次回到这个界面都重新算一遍。
        // 这样用户从设置页授权完返回时，数字会自动出现，不用手动刷新。
        refresh();
    }

    private void refresh() {
        if (!UsageAccess.hasPermission(this)) {
            showNeedPermission();
            return;
        }

        btnGrant.setVisibility(View.GONE);
        tvHint.setVisibility(View.GONE);
        tvTotal.setText("…");
        tvDetail.setText(R.string.placeholder_loading);

        // 遍历一整天的事件有点耗时，必须放到后台线程，
        // 算完再切回界面线程更新 UI（安卓不允许在子线程改界面）。
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<ShortVideoUsage.Item> items =
                        ShortVideoUsage.queryToday(MainActivity.this);

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showResult(items);
                    }
                });
            }
        }).start();
    }

    /** 还没有权限时显示的内容 */
    private void showNeedPermission() {
        tvTotal.setText(R.string.placeholder_dash);
        tvDetail.setText(R.string.detail_no_permission);

        tvHint.setVisibility(View.VISIBLE);
        tvHint.setText(R.string.hint_need_permission);

        btnGrant.setVisibility(View.VISIBLE);
    }

    /** 有权限时，把统计结果画到界面上 */
    private void showResult(List<ShortVideoUsage.Item> items) {
        long totalMs = 0;
        StringBuilder builder = new StringBuilder();

        for (ShortVideoUsage.Item item : items) {
            totalMs += item.foregroundMs;
            builder.append(ShortVideoUsage.displayName(item.packageName))
                   .append("    ")
                   .append(ShortVideoUsage.formatDuration(item.foregroundMs))
                   .append('\n');
        }

        tvTotal.setText(ShortVideoUsage.formatDuration(totalMs));

        if (builder.length() == 0) {
            tvDetail.setText(R.string.detail_no_usage);
        } else {
            tvDetail.setText(builder.toString().trim());
        }
    }
}
