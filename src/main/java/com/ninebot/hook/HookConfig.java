package com.ninebot.hook;

import android.content.Context;
import android.content.SharedPreferences;

public class HookConfig {

    // 必须与 AndroidManifest.xml 中的 xposedsharedprefs 保持一致
    private static final String PREF_NAME = "theme_config"; 

    public static final String KEY_SERVER_URL = "server_url";
    public static final String KEY_ENABLE_THEME_HACK = "enable_theme_hack";

    private static SharedPreferences getPrefs(Context context) {
        // 使用 MODE_WORLD_READABLE 并在清单文件中声明
        return context.getSharedPreferences(PREF_NAME, Context.MODE_WORLD_READABLE);
    }

    public static String getServerUrl(Context context) {
        return getPrefs(context).getString(KEY_SERVER_URL, "");
    }

    public static boolean setServerUrl(Context context, String url) {
        return getPrefs(context).edit().putString(KEY_SERVER_URL, url).commit();
    }

    public static boolean isThemeHackEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_ENABLE_THEME_HACK, true);
    }

    public static void setThemeHackEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_ENABLE_THEME_HACK, enabled).apply();
    }
}
