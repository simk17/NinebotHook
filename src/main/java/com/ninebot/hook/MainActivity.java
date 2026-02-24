package com.ninebot.hook;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.Manifest;
import android.os.Handler;
import android.provider.Settings;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String NINEBOT_PACKAGE = "cn.ninebot.ninebot";

    private static final int REQUEST_STORAGE = 10001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ReportHelper.setContext(getApplicationContext());
        requestStorageIfNeeded();

        try {
            int pid = android.os.Process.myPid();
            ReportHelper.reportSync("应用", "九号LSPosed插件已打开 | PID=" + pid + " | 当前为模块应用进程（非九号）");
        } catch (Throwable t) { }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);

        String version = "0.01";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) { }
        
        TextView tv = new TextView(this);
        tv.setText("九号LSPosed插件 v" + version);
        tv.setTextSize(20);
        tv.setPadding(0, 0, 0, 32);
        layout.addView(tv);

        // --- 主题破解开关 ---
        LinearLayout themeRow = new LinearLayout(this);
        themeRow.setOrientation(LinearLayout.HORIZONTAL);
        themeRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        themeRow.setPadding(0, 16, 0, 16);
        
        TextView themeLabel = new TextView(this);
        themeLabel.setText("启用主题破解 (themeShow=1)");
        themeLabel.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        
        Switch themeSwitch = new Switch(this);
        themeSwitch.setChecked(HookConfig.isThemeHackEnabled(this));
        themeSwitch.setOnCheckedChangeListener((v, isChecked) -> {
            HookConfig.setThemeHackEnabled(this, isChecked);
            Toast.makeText(this, isChecked ? "已启用主题破解" : "已禁用主题破解", Toast.LENGTH_SHORT).show();
        });
        
        themeRow.addView(themeLabel);
        themeRow.addView(themeSwitch);
        layout.addView(themeRow);
        
        // --- 服务器配置 ---
        TextView hint = new TextView(this);
        hint.setText("\nWeb 日志服务器 IP：");
        layout.addView(hint);

        EditText editUrl = new EditText(this);
        editUrl.setHint("192.168.1.100:8765");
        editUrl.setText(HookConfig.getServerUrl(this));
        layout.addView(editUrl);

        Button btnSave = new Button(this);
        btnSave.setText("保存设置");
        btnSave.setOnClickListener(v -> {
            String s = editUrl.getText().toString().trim();
            HookConfig.setServerUrl(this, s);
            Toast.makeText(this, "设置已保存，下次触发Hook生效", Toast.LENGTH_SHORT).show();
        });
        layout.addView(btnSave);

        // --- 快捷操作区 ---
        int dp = (int) getResources().getDisplayMetrics().density;
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setPadding(0, 32 * dp, 0, 0);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, -2, 1f);
        
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
        layout.addView(btnRow);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
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
