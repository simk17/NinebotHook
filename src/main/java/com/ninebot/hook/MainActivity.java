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
        requestStorageIfNeeded();

        try {
            // 保留：打开插件时的调试日志（logcat）
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

        // ---------- 5. 修改车辆型号 ----------
        root.addView(sectionTitle("5. 修改车辆型号"));
        root.addView(switchRow(
                "修改车辆型号实现仪表盘显示时速，默认参数116,为九号电动Dz110P内部编号.",
                HookConfig.isForceMotorDisplayEnabled(this),
                (checked) -> {
                    HookConfig.setForceMotorDisplayEnabled(this, checked);
                    Toast.makeText(this, checked ? "已开启：修改车辆型号" : "已关闭", Toast.LENGTH_SHORT).show();
                },
                cardPad
        ));
        TextView motorTypeHint = new TextView(this);
        motorTypeHint.setText("当前测试电摩型号为14101\n该值（建议填 116 或抓包「车辆信息」中确认的车型。乱填可能会触发内部开发功能：如 117 会触发「发起导航(体验版)」「仅支持高德自行车」等");
        motorTypeHint.setTextSize(BODY_SIZE_SP - 2);
        motorTypeHint.setTextColor(COLOR_SUBTITLE);
        motorTypeHint.setPadding(cardPad, dp(8), cardPad, dp(4));
        root.addView(motorTypeHint);
        EditText editMotorType = new EditText(this);
        editMotorType.setHint("116");
        editMotorType.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        editMotorType.setText(String.valueOf(HookConfig.getForceMotorVehicleType(this)));
        editMotorType.setPadding(cardPad, dp(4), cardPad, dp(8));
        editMotorType.setTextSize(BODY_SIZE_SP);
        root.addView(editMotorType);

        // ---------- 6. 投屏导航 ----------
        root.addView(sectionTitle("6. 投屏导航"));
        root.addView(switchRow(
                "地图导航时叠加（仅当导航设为「地图导航」时生效，与高德/百度等无关；当前为验证文案，可关）",
                HookConfig.isScreenCastOverlayEnabled(this),
                (checked) -> {
                    HookConfig.setScreenCastOverlayEnabled(this, checked);
                    Toast.makeText(this, checked ? "已开启投屏叠加" : "已关闭", Toast.LENGTH_SHORT).show();
                },
                cardPad
        ));

        // ---------- 7. 自定义投屏 ----------
        root.addView(sectionTitle("7. 自定义投屏"));
        root.addView(switchRow(
                "启用自定义投屏（完全接管投屏逻辑，使用自定义画面源）",
                HookConfig.isCustomScreenCastEnabled(this),
                (checked) -> {
                    HookConfig.setCustomScreenCastEnabled(this, checked);
                    Toast.makeText(this, checked ? "已启用自定义投屏" : "已禁用自定义投屏", Toast.LENGTH_SHORT).show();
                },
                cardPad
        ));
        
        // 分辨率配置
        LinearLayout resLayout = new LinearLayout(this);
        resLayout.setOrientation(LinearLayout.HORIZONTAL);
        resLayout.setPadding(cardPad, dp(8), cardPad, dp(8));
        
        EditText editWidth = new EditText(this);
        editWidth.setHint("宽度");
        editWidth.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        editWidth.setText(String.valueOf(HookConfig.getScreenCastWidth(this)));
        editWidth.setTextSize(BODY_SIZE_SP);
        editWidth.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        
        TextView xText = new TextView(this);
        xText.setText(" × ");
        xText.setTextSize(BODY_SIZE_SP);
        xText.setPadding(dp(8), 0, dp(8), 0);
        
        EditText editHeight = new EditText(this);
        editHeight.setHint("高度");
        editHeight.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        editHeight.setText(String.valueOf(HookConfig.getScreenCastHeight(this)));
        editHeight.setTextSize(BODY_SIZE_SP);
        editHeight.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        
        resLayout.addView(editWidth);
        resLayout.addView(xText);
        resLayout.addView(editHeight);
        root.addView(resLayout);
        
        // 码率帧率配置
        LinearLayout bitrateLayout = new LinearLayout(this);
        bitrateLayout.setOrientation(LinearLayout.HORIZONTAL);
        bitrateLayout.setPadding(cardPad, dp(8), cardPad, dp(8));
        
        EditText editBitrate = new EditText(this);
        editBitrate.setHint("码率 (bps)");
        editBitrate.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        editBitrate.setText(String.valueOf(HookConfig.getScreenCastBitrate(this)));
        editBitrate.setTextSize(BODY_SIZE_SP);
        editBitrate.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        
        TextView fpsText = new TextView(this);
        fpsText.setText(" FPS: ");
        fpsText.setTextSize(BODY_SIZE_SP);
        fpsText.setPadding(dp(8), 0, dp(8), 0);
        
        EditText editFps = new EditText(this);
        editFps.setHint("帧率");
        editFps.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        editFps.setText(String.valueOf(HookConfig.getScreenCastFramerate(this)));
        editFps.setTextSize(BODY_SIZE_SP);
        editFps.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        
        bitrateLayout.addView(editBitrate);
        bitrateLayout.addView(fpsText);
        bitrateLayout.addView(editFps);
        root.addView(bitrateLayout);

        // ---------- 保存设置 ----------
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
            try {
                int type = Integer.parseInt(editMotorType.getText().toString().trim());
                HookConfig.setForceMotorVehicleType(this, type);
            } catch (NumberFormatException ignored) { }
            
            // 保存自定义投屏配置
            try {
                int width = Integer.parseInt(editWidth.getText().toString().trim());
                int height = Integer.parseInt(editHeight.getText().toString().trim());
                int bitrate = Integer.parseInt(editBitrate.getText().toString().trim());
                int framerate = Integer.parseInt(editFps.getText().toString().trim());
                
                if (width > 0 && height > 0) {
                    HookConfig.setScreenCastWidth(this, width);
                    HookConfig.setScreenCastHeight(this, height);
                }
                if (bitrate > 0) {
                    HookConfig.setScreenCastBitrate(this, bitrate);
                }
                if (framerate > 0) {
                    HookConfig.setScreenCastFramerate(this, framerate);
                }
            } catch (NumberFormatException ignored) { }
            
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
