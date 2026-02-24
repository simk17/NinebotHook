package com.ninebot.hook;

import android.content.Context;
import android.content.SharedPreferences;

public class HookConfig {

    // 必须与 AndroidManifest.xml 中的 xposedsharedprefs 保持一致
    private static final String PREF_NAME = "theme_config";

    public static final String KEY_SERVER_URL = "server_url";
    /** 旧单开关，兼容：为 true 时等价于 主题显示+破解费用 都开 */
    public static final String KEY_ENABLE_THEME_HACK = "enable_theme_hack";

    /** 1. 强制开启内测主题功能（themeShow=1、内存配置、主题 Tab、入口） */
    public static final String KEY_THEME_SHOW = "enable_theme_show";
    /** 2. 在 1 基础上：破解主题费用（已拥有/72057/device/resource/我的主题库注入） */
    public static final String KEY_THEME_CRACK_PAID = "enable_theme_crack_paid";
    /** 3. 其他：抓包、水印、反调试等 */
    public static final String KEY_OTHER = "enable_other";

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_WORLD_READABLE);
    }

    public static String getServerUrl(Context context) {
        return getPrefs(context).getString(KEY_SERVER_URL, "");
    }

    public static boolean setServerUrl(Context context, String url) {
        return getPrefs(context).edit().putString(KEY_SERVER_URL, url).commit();
    }

    /** 兼容旧版：若从未设置过新开关，用 enable_theme_hack 作为 显示+破解 的默认 */
    public static boolean isThemeHackEnabled(Context context) {
        SharedPreferences p = getPrefs(context);
        if (!p.contains(KEY_THEME_SHOW) && !p.contains(KEY_THEME_CRACK_PAID)) {
            return p.getBoolean(KEY_ENABLE_THEME_HACK, true);
        }
        return isThemeShowEnabled(context) || isThemeCrackPaidEnabled(context);
    }

    public static void setThemeHackEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_ENABLE_THEME_HACK, enabled).apply();
        if (!getPrefs(context).contains(KEY_THEME_SHOW)) {
            setThemeShowEnabled(context, enabled);
        }
        if (!getPrefs(context).contains(KEY_THEME_CRACK_PAID)) {
            setThemeCrackPaidEnabled(context, enabled);
        }
    }

    public static boolean isThemeShowEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_THEME_SHOW, true);
    }

    public static void setThemeShowEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_THEME_SHOW, enabled).apply();
    }

    public static boolean isThemeCrackPaidEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_THEME_CRACK_PAID, true);
    }

    public static void setThemeCrackPaidEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_THEME_CRACK_PAID, enabled).apply();
    }

    public static boolean isOtherEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_OTHER, true);
    }

    public static void setOtherEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_OTHER, enabled).apply();
    }
}
