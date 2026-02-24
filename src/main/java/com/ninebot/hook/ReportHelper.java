package com.ninebot.hook;

import android.content.Context;
import android.util.Log;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 状态打 logcat 并推送到 Web。服务器地址从 HookConfig 读文件 /sdcard/jiuhao-hook/server
 * （模块 MainActivity 保存）；九号进程读该文件时依赖存储可访问性，读不到则用默认地址。
 */
public class ReportHelper {
    private static final String TAG = "NinebotHook";
    /** 未配置或读不到文件时使用；请改为你电脑的 IP:8765 或在本模块里保存服务器地址 */
    private static final String DEFAULT_BASE_URL = "http://192.168.50.60:8765";
    private static final Executor executor = Executors.newSingleThreadExecutor();

    private static Context sContext;
    private static String cachedBaseUrl;
    private static boolean configRead;

    /** 设置 Context，供 getBaseUrl 读服务器地址。需在首次 report 前调用。 */
    public static void setContext(Context context) {
        sContext = context != null ? context.getApplicationContext() : null;
        configRead = false;
    }

    /** 直接设置服务器地址，通常由 Hook 类从 XSharedPreferences 读取配置后注入 */
    public static void setCustomServerUrl(String url) {
        if (url != null && !url.isEmpty()) {
            if (!url.startsWith("http")) url = "http://" + url;
            url = url.trim();
            while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            cachedBaseUrl = url;
            configRead = true;
        }
    }

    private static String getBaseUrl() {
        if (configRead) return cachedBaseUrl != null ? cachedBaseUrl : DEFAULT_BASE_URL;
        configRead = true;
        if (sContext != null) {
            String url = HookConfig.getServerUrl(sContext);
            if (url != null && !url.isEmpty()) {
                if (!url.startsWith("http")) url = "http://" + url;
                url = url.trim();
                while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
                cachedBaseUrl = url;
                return cachedBaseUrl;
            }
        }
        return DEFAULT_BASE_URL;
    }

    /** 打 logcat 并异步推送到 Web */
    public static void report(String tag, String msg) {
        Log.i(TAG, "[" + tag + "] " + msg);
        final String base = getBaseUrl();
        executor.execute(() -> doPush(base, tag, msg));
    }

    /** 同步推送到 Web */
    public static void reportSync(String tag, String msg) {
        Log.i(TAG, "[" + tag + "] " + msg);
        try {
            doPush(getBaseUrl(), tag, msg);
        } catch (Throwable t) {
            Log.w(TAG, "[reportSync] push fail: " + t.getMessage());
        }
    }

    private static void doPush(String base, String tag, String msg) {
        try {
            String urlStr = base + "/report?tag=" + java.net.URLEncoder.encode(tag, "UTF-8")
                    + "&msg=" + java.net.URLEncoder.encode(msg, "UTF-8");
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.getResponseCode();
            conn.disconnect();
        } catch (Throwable t) {
            Log.w(TAG, "[report] push fail: " + t.getMessage());
        }
    }
}
