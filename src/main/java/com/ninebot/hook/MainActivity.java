package com.ninebot.hook;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String NINEBOT_PACKAGE = "cn.ninebot.ninebot";

    private static final int PADDING_DP = 20;
    private static final int SECTION_SPACING_DP = 24;
    private static final int CARD_PADDING_DP = 16;
    private static final int CARD_RADIUS_DP = 12;
    private static final int TITLE_SIZE_SP = 18;
    private static final int BODY_SIZE_SP = 15;
    private static final int COLOR_PRIMARY = 0xFF2196F3;
    private static final int COLOR_CARD_BG = 0xFFF5F5F5;
    private static final int COLOR_SUBTITLE = 0xFF757575;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ReportHelper.setContext(getApplicationContext());
        requestStorageIfNeeded();

        try {
            int pid = android.os.Process.myPid();
            ReportHelper.reportSync("应用", "九号出行LSPosed插件已打开 | PID=" + pid);
        } catch (Throwable t) { }

        int pad = dp(PADDING_DP);
        int sectionSp = dp(SECTION_SPACING_DP);
        int cardPad = dp(CARD_PADDING_DP);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.WHITE);

        String version = "0.01";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) { }

        TextView title = new TextView(this);
        title.setText("九号出行LSPosed插件");
        title.setTextSize(TITLE_SIZE_SP + 4);
        title.setTextColor(COLOR_PRIMARY);
        title.setPadding(0, 0, 0, dp(4));
        root.addView(title);

        TextView versionTv = new TextView(this);
        versionTv.setText("v" + version);
        versionTv.setTextSize(BODY_SIZE_SP);
        versionTv.setTextColor(COLOR_SUBTITLE);
        versionTv.setPadding(0, 0, 0, sectionSp);
        root.addView(versionTv);

        // ---------- 1. 强制开启主题功能 ----------
        root.addView(sectionTitle("1. 强制开启主题功能"));
        root.addView(switchRow(
                "开启内测主题入口（themeShow=1、主题 Tab、入口）",
                HookConfig.isThemeShowEnabled(this),
                (checked) -> {
                    HookConfig.setThemeShowEnabled(this, checked);
                    Toast.makeText(this, checked ? "已开启主题功能" : "已关闭主题功能", Toast.LENGTH_SHORT).show();
                },
                cardPad
        ));

        // ---------- 2. 破解主题费用 ----------
        root.addView(sectionTitle("2. 破解主题费用"));
        root.addView(switchRow(
                "在 1 基础上：已拥有/我的主题库/付费主题等",
                HookConfig.isThemeCrackPaidEnabled(this),
                (checked) -> {
                    HookConfig.setThemeCrackPaidEnabled(this, checked);
                    Toast.makeText(this, checked ? "已开启破解主题费用" : "已关闭", Toast.LENGTH_SHORT).show();
                },
                cardPad
        ));

        // ---------- 3. 其他 ----------
        root.addView(sectionTitle("3. 其他"));
        root.addView(switchRow(
                "抓包、水印、反调试等",
                HookConfig.isOtherEnabled(this),
                (checked) -> {
                    HookConfig.setOtherEnabled(this, checked);
                    Toast.makeText(this, checked ? "已开启其他功能" : "已关闭", Toast.LENGTH_SHORT).show();
                },
                cardPad
        ));

        // ---------- 4. 快捷操作 ----------
        root.addView(sectionTitle("4. 快捷操作"));
        LinearLayout btnCard = cardWrap(horizontalButtonRow(cardPad), cardPad);
        root.addView(btnCard);

        // ---------- 服务器配置 ----------
        root.addView(sectionTitle("Web 日志服务器"));
        TextView hint = new TextView(this);
        hint.setText("IP:端口（如 192.168.1.100:8765）");
        hint.setTextSize(BODY_SIZE_SP - 2);
        hint.setTextColor(COLOR_SUBTITLE);
        hint.setPadding(cardPad, cardPad, cardPad, dp(4));
        root.addView(hint);

        EditText editUrl = new EditText(this);
        editUrl.setHint("192.168.1.100:8765");
        editUrl.setText(HookConfig.getServerUrl(this));
        editUrl.setPadding(cardPad, dp(8), cardPad, dp(8));
        editUrl.setTextSize(BODY_SIZE_SP);
        root.addView(editUrl);

        Button btnSave = new Button(this);
        btnSave.setText("保存设置");
        btnSave.setTextColor(Color.WHITE);
        btnSave.setAllCaps(false);
        try {
            btnSave.setBackgroundColor(COLOR_PRIMARY);
        } catch (Throwable ignored) { }
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(-1, dp(48));
        saveLp.topMargin = dp(12);
        btnSave.setLayoutParams(saveLp);
        btnSave.setOnClickListener(v -> {
            String s = editUrl.getText().toString().trim();
            HookConfig.setServerUrl(this, s);
            Toast.makeText(this, "设置已保存，下次 Hook 生效", Toast.LENGTH_SHORT).show();
        });
        root.addView(btnSave);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    private TextView sectionTitle(CharSequence text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TITLE_SIZE_SP);
        tv.setTextColor(Color.BLACK);
        tv.setPadding(0, dp(SECTION_SPACING_DP), 0, dp(8));
        return tv;
    }

    private LinearLayout switchRow(CharSequence label, boolean checked, OnSwitchChanged listener, int cardPad) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(cardPad, dp(12), cardPad, dp(12));
        row.setBackgroundColor(COLOR_CARD_BG);

        TextView labelTv = new TextView(this);
        labelTv.setText(label);
        labelTv.setTextSize(BODY_SIZE_SP);
        labelTv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((v, isChecked) -> listener.onChanged(isChecked));

        row.addView(labelTv);
        row.addView(sw);
        return row;
    }

    private LinearLayout horizontalButtonRow(int cardPad) {
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(cardPad, dp(12), cardPad, dp(12));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, -2, 1f);
        btnLp.leftMargin = dp(4);
        btnLp.rightMargin = dp(4);

        Button btnStop = new Button(this);
        btnStop.setText("停止九号");
        btnStop.setLayoutParams(btnLp);
        btnStop.setOnClickListener(v -> runSuForceStop(false));

        Button btnRestart = new Button(this);
        btnRestart.setText("重启九号");
        btnRestart.setLayoutParams(btnLp);
        btnRestart.setOnClickListener(v -> runSuForceStop(true));

        Button btnLaunch = new Button(this);
        btnLaunch.setText("启动九号");
        btnLaunch.setLayoutParams(btnLp);
        btnLaunch.setOnClickListener(v -> {
            Intent intent = getPackageManager().getLaunchIntentForPackage(NINEBOT_PACKAGE);
            if (intent != null) {
                startActivity(intent);
                Toast.makeText(this, "正在启动九号出行...", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "未找到九号出行 APP", Toast.LENGTH_SHORT).show();
            }
        });

        btnRow.addView(btnStop);
        btnRow.addView(btnRestart);
        btnRow.addView(btnLaunch);
        return btnRow;
    }

    private LinearLayout cardWrap(android.view.View content, int cardPad) {
        LinearLayout card = new LinearLayout(this);
        card.setBackgroundColor(COLOR_CARD_BG);
        card.addView(content);
        return card;
    }

    private interface OnSwitchChanged {
        void onChanged(boolean checked);
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void requestStorageIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Throwable ignored) { }
            }
        }
    }

    private void runSuForceStop(boolean thenLaunch) {
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "am force-stop " + NINEBOT_PACKAGE});
                p.waitFor();
                if (thenLaunch) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        Intent intent = getPackageManager().getLaunchIntentForPackage(NINEBOT_PACKAGE);
                        if (intent != null) startActivity(intent);
                    }, 500);
                }
            } catch (Throwable ignored) { }
        }).start();
    }
}
