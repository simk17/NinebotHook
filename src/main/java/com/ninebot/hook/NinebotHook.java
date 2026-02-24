package com.ninebot.hook;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Proxy;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 九号LSPosed插件 - v41 终极修复版
 * 1. 修正配置文件名为 theme_config (与 Manifest 一致)
 * 2. 强化延迟加载逻辑，解决 ClassNotFound
 * 3. 恢复内存层配置篡改，确保主题显示
 */
public class NinebotHook implements IXposedHookLoadPackage {

    private static final int HOOK_LOG_VERSION = 41; 
    private static String V(String msg) { return msg + " | 插件v" + HOOK_LOG_VERSION; }

    private static final String TARGET_PACKAGE = "cn.ninebot.ninebot";
    private static final String MODULE_PACKAGE = "com.ninebot.hook";
    
    // 修正：必须与 HookConfig 中的 PREF_NAME 保持一致
    private static final XSharedPreferences sPrefs = new XSharedPreferences(MODULE_PACKAGE, "theme_config");

    static {
        sPrefs.makeWorldReadable();
    }

    private static boolean isThemeHackEnabled() {
        sPrefs.reload();
        return sPrefs.getBoolean("enable_theme_hack", true);
    }

    private static String getRemoteServerUrl() {
        sPrefs.reload();
        return sPrefs.getString("server_url", "");
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !TARGET_PACKAGE.equals(lpparam.packageName)) return;

        // 1. 初始化
        String serverUrl = getRemoteServerUrl();
        if (!serverUrl.isEmpty()) {
            ReportHelper.setCustomServerUrl(serverUrl);
        }

        ReportHelper.reportSync("启动", V("handleLoadPackage 开始 | 进程=" + lpparam.processName));

        // 2. 配置读取检查日志
        try {
            sPrefs.reload();
            boolean canReadServer = !getRemoteServerUrl().isEmpty();
            ReportHelper.reportSync("配置检查", V("读取测试 -> 主题开关: " + isThemeHackEnabled() + " | 服务器配置: " + (canReadServer ? "正常" : "未设置/无法读取")));
        } catch (Throwable t) {
            ReportHelper.reportSync("配置检查", V("异常: " + t.getMessage()));
        }

        ClassLoader loader = lpparam.classLoader;

        // 3. 注入
        hookDebuggerAndReport();
        hookApplicationAttach(loader);
        hookWatermark(loader);
        
        // 核心逻辑：直接尝试 + 监听加载
        installHooks(loader);
    }

    private void installHooks(ClassLoader loader) {
        // 尝试一次性注入
        tryHookAll(loader);

        // 监听后续类加载
        XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || param.getResult() == null) return;
                Class<?> clazz = (Class<?>) param.getResult();
                String name = clazz.getName();
                if (name.contains("ninebot")) {
                    tryHookAll(loader);
                }
            }
        });
    }

    private synchronized void tryHookAll(ClassLoader loader) {
        tryHookRetrofit(loader);
        tryHookDecrypt(loader);
        tryHookMemoryConfig(loader);
        tryHookThemeUI(loader);
        tryHookEntry(loader);
    }

    // 1. 网络抓包
    private boolean hookedRetrofit = false;
    private void tryHookRetrofit(ClassLoader loader) {
        if (hookedRetrofit) return;
        try {
            Class<?> clazz = XposedHelpers.findClassIfExists("cn.ninebot.lib.network.core.RetrofitStrategy", loader);
            if (clazz == null) return;
            XposedBridge.hookAllMethods(clazz, "customizeOkHttpClient2", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    injectInterceptor(param, loader);
                }
            });
            hookedRetrofit = true;
            ReportHelper.reportSync("注入成功", V("已挂载 Http 抓包拦截器"));
        } catch (Throwable ignored) {}
    }

    // 2. 数据解密篡改
    private boolean hookedDecrypt = false;
    private void tryHookDecrypt(ClassLoader loader) {
        if (hookedDecrypt) return;
        try {
            Class<?> clazz = XposedHelpers.findClassIfExists("cn.ninebot.library.network.encrypt.netease.NeteaseDecrypt", loader);
            if (clazz == null) return;
            XposedBridge.hookAllMethods(clazz, "decodeContent", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!isThemeHackEnabled()) return;
                    String decrypted = (String) param.getResult();
                    if (decrypted != null && decrypted.contains("themeShow")) {
                        String modified = decrypted.replaceAll("\"themeShow\":\\s*[0-9]+", "\"themeShow\":1");
                        param.setResult(modified);
                        ReportHelper.report("主题破解", V("JSON 数据解密篡改成功"));
                    }
                }
            });
            hookedDecrypt = true;
            ReportHelper.reportSync("注入成功", V("已挂载 NeteaseDecrypt 解密层"));
        } catch (Throwable ignored) {}
    }

    // 3. 内存配置篡改 (核心：确保主题在内存中也为 1)
    private boolean hookedMemory = false;
    private void tryHookMemoryConfig(ClassLoader loader) {
        if (hookedMemory) return;
        try {
            Class<?> clazz = XposedHelpers.findClassIfExists("cn.ninebot.device.topguide.TopGuideConfigRepository", loader);
            if (clazz == null) return;
            XposedBridge.hookAllMethods(clazz, "getTopGuideTabConfig", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!isThemeHackEnabled()) return;
                    Object config = param.getResult();
                    if (config != null) {
                        XposedHelpers.setIntField(config, "themeShow", 1);
                        param.setResult(config);
                        ReportHelper.report("主题破解", V("内存配置 TopGuideTabConfig.themeShow 已设为 1"));
                    }
                }
            });
            hookedMemory = true;
            ReportHelper.reportSync("注入成功", V("已挂载 TopGuideConfigRepository 内存层"));
        } catch (Throwable ignored) {}
    }

    // 4. UI 注入
    private boolean hookedUI = false;
    private void tryHookThemeUI(ClassLoader loader) {
        if (hookedUI) return;
        try {
            Class<?> clazz = XposedHelpers.findClassIfExists("cn.ninebot.device.topguide.TopGuideDeviceDetailViewModel", loader);
            if (clazz == null) return;
            XposedBridge.hookAllMethods(clazz, "updateTabList", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isThemeHackEnabled()) return;
                    List tabs = (List) param.args[0];
                    try {
                        Class<?> tabClass = XposedHelpers.findClass("cn.ninebot.device.topguide.TopGuideTab", loader);
                        Object themeTab = XposedHelpers.getStaticObjectField(tabClass, "Theme");
                        if (!tabs.contains(themeTab)) {
                            tabs.add(themeTab);
                            ReportHelper.report("主题破解", V("UI 列表已强制插入 Theme Tab"));
                        }
                    } catch (Throwable ignored) {}
                }
            });
            hookedUI = true;
        } catch (Throwable ignored) {}
    }

    // 5. 入口开启
    private boolean hookedEntry = false;
    private void tryHookEntry(ClassLoader loader) {
        if (hookedEntry) return;
        try {
            Class<?> clazz = XposedHelpers.findClassIfExists("cn.ninebot.device.topguide.TopGuideDeviceDetailFragmentKt", loader);
            if (clazz == null) return;
            XposedBridge.hookAllMethods(clazz, "isUseTopGuideMode", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isThemeHackEnabled()) param.setResult(true);
                }
            });
            hookedEntry = true;
        } catch (Throwable ignored) {}
    }

    private void injectInterceptor(XC_MethodHook.MethodHookParam param, ClassLoader loader) {
        try {
            Object builder = param.args[0];
            Class<?> interceptorClass = XposedHelpers.findClass("okhttp3.Interceptor", loader);
            Class<?> responseBodyClass = XposedHelpers.findClass("okhttp3.ResponseBody", loader);
            Object logger = Proxy.newProxyInstance(loader, new Class[]{interceptorClass}, (proxy, method, args) -> {
                if (!"intercept".equals(method.getName())) return method.invoke(proxy, args);
                Object chain = args[0];
                Object request = XposedHelpers.callMethod(chain, "request");
                String url = String.valueOf(XposedHelpers.callMethod(request, "url"));
                Object response = XposedHelpers.callMethod(chain, "proceed", request);
                Object body = XposedHelpers.callMethod(response, "body");
                if (body != null) {
                    String content = (String) XposedHelpers.callMethod(body, "string");
                    ReportHelper.report("抓包", "【URL】" + url + "\n【Body】" + content);
                    Object contentType = XposedHelpers.callMethod(body, "contentType");
                    Object newBody = XposedHelpers.callStaticMethod(responseBodyClass, "create", contentType, content);
                    Object respBuilder = XposedHelpers.callMethod(response, "newBuilder");
                    XposedHelpers.callMethod(respBuilder, "body", newBody);
                    response = XposedHelpers.callMethod(respBuilder, "build");
                }
                return response;
            });
            XposedHelpers.callMethod(builder, "addInterceptor", logger);
        } catch (Throwable ignored) {}
    }

    private void hookDebuggerAndReport() {
        try {
            XposedBridge.hookAllMethods(Debug.class, "isDebuggerConnected", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(false);
                }
            });
        } catch (Throwable ignored) {}
    }

    private void hookApplicationAttach(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", classLoader, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Context ctx = (Context) param.args[0];
                    if (ctx != null && TARGET_PACKAGE.equals(ctx.getPackageName())) {
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            Toast.makeText(ctx, "九号LSPosed注入成功 v" + HOOK_LOG_VERSION, Toast.LENGTH_SHORT).show();
                        }, 2000);
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    private void hookWatermark(ClassLoader classLoader) {
        try {
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final Activity activity = (Activity) param.thisObject;
                    if (activity.getPackageName().equals(TARGET_PACKAGE)) {
                        new Handler(Looper.getMainLooper()).post(() -> addWatermarkToActivity(activity));
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    private void addWatermarkToActivity(Activity activity) {
        try {
            ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
            if (root.findViewWithTag("ninebot_hook_watermark") != null) return;
            TextView tv = new TextView(activity);
            tv.setTag("ninebot_hook_watermark");
            tv.setText("Hook v" + HOOK_LOG_VERSION);
            tv.setAlpha(0.5f);
            tv.setTextColor(Color.RED);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.topMargin = dp(activity, 10);
            root.addView(tv, lp);
        } catch (Throwable ignored) {}
    }

    private int dp(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
