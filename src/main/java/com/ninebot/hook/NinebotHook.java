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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final int HOOK_LOG_VERSION = 42; 
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

    // 解包日志最大长度（避免 URL 过长）
    private static final int DECRYPT_LOG_MAX = 3500;
    /** 缓存推荐页返回的 themeList 数组 JSON，用于注入「我的主题库」空列表 */
    private static volatile String cachedThemeListArray = null;
    /** 缓存「当前设备资源」要注入的 APP 皮肤项（resourceType:3），用于首页显示主题背景 */
    private static volatile String cachedAppSkinResourceItem = null;
    /** 缓存主题资源 downLoadUrl，用于 device/resource 响应注入 data.bgAnimation（AnimResourceBean.url），原生据此拉取首页背景 */
    private static volatile String cachedBgAnimationUrl = null;

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
                    if (decrypted == null) return;
                    String modified = decrypted;
                    boolean changed = false;
                    // 1. 云控入口：themeShow 控制是否显示主题 Tab
                    if (modified.contains("themeShow")) {
                        modified = modified.replaceAll("\"themeShow\":\\s*[0-9]+", "\"themeShow\":1");
                        changed = true;
                    }
                    // 2. 付费/已拥有：仅当响应疑似主题相关时才改（含 batch-show-config / getEraseResourceInfo 等）
                    boolean looksTheme = modified.contains("theme") || modified.contains("themeId") || modified.contains("skin")
                            || modified.contains("showEntranceList") || modified.contains("eraseResource") || modified.contains("EraseResource");
                    if (looksTheme) {
                        if (modified.contains("owned")) {
                            String next = modified.replaceAll("\"owned\":\\s*0", "\"owned\":1");
                            if (!next.equals(modified)) { modified = next; changed = true; }
                        }
                        // 推荐页 themeList 里为数字：isPurchased:0 → 1
                        if (modified.contains("isPurchased")) {
                            String next = modified.replaceAll("\"isPurchased\":\\s*false", "\"isPurchased\":true")
                                    .replaceAll("\"isPurchased\":\\s*0", "\"isPurchased\":1");
                            if (!next.equals(modified)) { modified = next; changed = true; }
                        }
                        if (modified.contains("is_purchased")) {
                            String next = modified.replaceAll("\"is_purchased\":\\s*0", "\"is_purchased\":1");
                            if (!next.equals(modified)) { modified = next; changed = true; }
                        }
                        if (modified.contains("\"paid\"")) {
                            String next = modified.replaceAll("\"paid\":\\s*0", "\"paid\":1").replaceAll("\"paid\":\\s*false", "\"paid\":true");
                            if (!next.equals(modified)) { modified = next; changed = true; }
                        }
                        if (modified.contains("hasPurchased")) {
                            String next = modified.replaceAll("\"hasPurchased\":\\s*false", "\"hasPurchased\":true");
                            if (!next.equals(modified)) { modified = next; changed = true; }
                        }
                        if (modified.contains("purchased")) {
                            String next = modified.replaceAll("\"purchased\":\\s*0", "\"purchased\":1").replaceAll("\"purchased\":\\s*false", "\"purchased\":true");
                            if (!next.equals(modified)) { modified = next; changed = true; }
                        }
                        // 常见：purchaseStatus 0=未购买 1或2=已购买，统一改为 1
                        if (modified.contains("purchaseStatus")) {
                            String next = modified.replaceAll("\"purchaseStatus\":\\s*[0-9]+", "\"purchaseStatus\":1");
                            if (!next.equals(modified)) { modified = next; changed = true; }
                        }
                    }
                    // 缓存推荐页的 themeList 数组（含 hasNextPage 的即推荐/列表接口），用于注入「我的主题库」
                    if (looksTheme && modified.contains("themeList") && modified.contains("hasNextPage")) {
                        int idx = modified.indexOf("\"themeList\":");
                        if (idx >= 0) {
                            int arrStart = modified.indexOf("[", idx);
                            if (arrStart >= 0 && arrStart + 1 < modified.length()) {
                                if (modified.charAt(arrStart + 1) != ']') {
                                    int depth = 0;
                                    int arrEnd = arrStart;
                                    for (int i = arrStart; i < modified.length(); i++) {
                                        char ch = modified.charAt(i);
                                        if (ch == '[') depth++;
                                        else if (ch == ']') {
                                            depth--;
                                            if (depth == 0) { arrEnd = i; break; }
                                        }
                                    }
                                    if (arrEnd > arrStart) {
                                        cachedThemeListArray = modified.substring(arrStart, arrEnd + 1);
                                    }
                                }
                            }
                        }
                    }
                    // 主题详情（含 resourceType:3 APP皮肤）时缓存一条「当前设备资源」格式的 APP 皮肤项，供 device/resource 注入
                    if (looksTheme && modified.contains("resourceType\":3") && modified.contains("resourceTypeName\":\"APP皮肤\"")
                            && modified.contains("themeId\"") && modified.contains("themeName\"")) {
                        try {
                            Matcher themeIdM = Pattern.compile("\"themeId\":\"([^\"]*)\"").matcher(modified);
                            Matcher themeNameM = Pattern.compile("\"themeName\":\"([^\"]*)\"").matcher(modified);
                            int idx3 = modified.indexOf("resourceType\":3");
                            if (idx3 >= 0 && themeIdM.find() && themeNameM.find()) {
                                String themeId = themeIdM.group(1);
                                String themeName = themeNameM.group(1);
                                String seg = modified.substring(idx3, Math.min(modified.length(), idx3 + 1200));
                                String rn = null, thumb = null, ver = null, code = null;
                                Matcher rnM = Pattern.compile("\"resourceName\":\"([^\"]*)\"").matcher(seg);
                                if (rnM.find()) rn = rnM.group(1);
                                Matcher thumbM = Pattern.compile("\"thumbnailUrl\":\"([^\"]*)\"").matcher(seg);
                                if (thumbM.find()) thumb = thumbM.group(1);
                                Matcher verM = Pattern.compile("\"resourceVersion\":\"([^\"]*)\"").matcher(seg);
                                if (verM.find()) ver = verM.group(1);
                                Matcher codeM = Pattern.compile("\"resourceCode\":\"([^\"]*)\"").matcher(seg);
                                if (codeM.find()) code = codeM.group(1);
                                if (rn != null && themeId != null && themeName != null) {
                                    String esc = "\\\"";
                                    String item = "{\"resourceRecordId\":null,\"resourceType\":3,\"resourceName\":\"" + rn.replace("\"", esc) + "\",\"thumbnailUrl\":\"" + (thumb != null ? thumb.replace("\"", esc) : "") + "\",\"resourceVersion\":\"" + (ver != null ? ver : "") + "\",\"resourceCode\":\"" + (code != null ? code : "") + "\",\"fileKey\":null,\"fileSize\":null,\"newVersionStatus\":null,\"resourceFrom\":null,\"instrumentTypeId\":\"5\",\"instrumentTypeName\":\"7寸仪表\",\"themeId\":\"" + themeId + "\",\"themeName\":\"" + themeName.replace("\"", esc) + "\"}";
                                    cachedAppSkinResourceItem = item;
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                    // 主题资源下载接口（resourceType:3 + themeRecordId + downLoadUrl，无 resourceTypeName）也缓存一条，供 device/resource 注入
                    if (looksTheme && modified.contains("resourceType\":3") && modified.contains("themeRecordId\"") && modified.contains("downLoadUrl\"")) {
                        try {
                            int idx3 = modified.indexOf("resourceType\":3");
                            if (idx3 >= 0) {
                                String seg = modified.substring(idx3, Math.min(modified.length(), idx3 + 1400));
                                String themeRecordId = null, rn = null, ver = null, code = null, thumb = null;
                                Matcher trM = Pattern.compile("\"themeRecordId\":\"([^\"]*)\"").matcher(seg);
                                if (trM.find()) themeRecordId = trM.group(1);
                                Matcher rnM = Pattern.compile("\"resourceName\":\"([^\"]*)\"").matcher(seg);
                                if (rnM.find()) rn = rnM.group(1);
                                Matcher verM = Pattern.compile("\"resourceVersion\":\"([^\"]*)\"").matcher(seg);
                                if (verM.find()) ver = verM.group(1);
                                Matcher codeM = Pattern.compile("\"resourceCode\":\"([^\"]*)\"").matcher(seg);
                                if (codeM.find()) code = codeM.group(1);
                                Matcher thumbM = Pattern.compile("\"thumbnailUrl\":\"([^\"]*)\"").matcher(seg);
                                if (thumbM.find()) thumb = thumbM.group(1);
                                if (themeRecordId != null && rn != null) {
                                    String esc = "\\\"";
                                    String item = "{\"resourceRecordId\":null,\"resourceType\":3,\"resourceName\":\"" + rn.replace("\"", esc) + "\",\"thumbnailUrl\":\"" + (thumb != null ? thumb.replace("\"", esc) : "") + "\",\"resourceVersion\":\"" + (ver != null ? ver : "") + "\",\"resourceCode\":\"" + (code != null ? code : "") + "\",\"fileKey\":null,\"fileSize\":null,\"newVersionStatus\":null,\"resourceFrom\":null,\"instrumentTypeId\":\"5\",\"instrumentTypeName\":\"7寸仪表\",\"themeId\":\"" + themeRecordId + "\",\"themeName\":\"" + rn.replace("\"", esc) + "\"}";
                                    cachedAppSkinResourceItem = item;
                                }
                                Matcher urlM = Pattern.compile("\"downLoadUrl\":\"([^\"]*)\"").matcher(seg);
                                if (urlM.find() && urlM.group(1) != null && !urlM.group(1).isEmpty()) {
                                    cachedBgAnimationUrl = urlM.group(1);
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                    // 「我的主题库」空列表：用缓存的推荐 themeList 注入，使「我的主题库」显示与推荐一致
                    if (looksTheme && cachedThemeListArray != null && !cachedThemeListArray.isEmpty()) {
                        if (modified.contains("\"themeList\":[]")) {
                            modified = modified.replace("\"themeList\":[]", "\"themeList\":" + cachedThemeListArray);
                            changed = true;
                            ReportHelper.report("主题破解", V("我的主题库空列表已注入推荐列表"));
                        } else if (modified.contains("\"themeList\": []")) {
                            modified = modified.replace("\"themeList\": []", "\"themeList\": " + cachedThemeListArray);
                            changed = true;
                            ReportHelper.report("主题破解", V("我的主题库空列表已注入推荐列表"));
                        }
                    }
                    // APP 皮肤 72057「order not found」→ 篡改为成功，绕过订单校验
                    if (modified.contains("72057") && modified.contains("order not found")) {
                        modified = modified.replace("\"code\":72057", "\"code\":1")
                                .replace("\"desc\":\"order not found\"", "\"desc\":\"成功\"");
                        changed = true;
                        ReportHelper.report("主题破解", V("72057 order not found 已篡改为成功，APP 皮肤应用"));
                    }
                    // device/resource「当前设备资源」：原生依赖 data.bgAnimation（AnimResourceBean），无则首页不换背景。注入 bgAnimation.url（用缓存的 downLoadUrl）
                    // 服务端实际返回为双大括号 "data":{{"resourceList":，需同时匹配单/双两种格式
                    if (looksTheme && cachedBgAnimationUrl != null && modified.contains("resourceList")
                            && modified.contains("\"themeId\":null") && !modified.contains("\"bgAnimation\"")) {
                        String escUrl = cachedBgAnimationUrl.replace("\\", "\\\\").replace("\"", "\\\"");
                        String bg = "\"bgAnimation\":{\"url\":\"" + escUrl + "\",\"zoom\":1,\"key\":null,\"iv\":null},";
                        String needle1 = "\"data\":{\"resourceList\":";
                        String needle2 = "\"data\":{{\"resourceList\":";
                        if (modified.contains(needle1)) {
                            modified = modified.replace(needle1, "\"data\":{" + bg + "\"resourceList\":");
                            changed = true;
                            ReportHelper.report("主题破解", V("device/resource 已注入 data.bgAnimation，首页应显示主题背景"));
                        } else if (modified.contains(needle2)) {
                            modified = modified.replace(needle2, "\"data\":{{" + bg + "\"resourceList\":");
                            changed = true;
                            ReportHelper.report("主题破解", V("device/resource 已注入 data.bgAnimation，首页应显示主题背景"));
                        }
                    }
                    // device/resource：同时注入 resourceList 中的 APP 皮肤项（与上条一起生效）
                    if (looksTheme && cachedAppSkinResourceItem != null && modified.contains("resourceList")
                            && modified.contains("resourceType\":2") && modified.contains("\"themeId\":null")
                            && !modified.contains("resourceType\":3")) {
                        String needle = "\"themeName\":null}}]";
                        if (modified.contains(needle)) {
                            String repl = "\"themeName\":null}}\","; // 以逗号结尾，无多余引号，避免 JSON 无效
                            modified = modified.replace(needle, repl.substring(0, repl.length() - 1) + cachedAppSkinResourceItem + "\"]}");
                            changed = true;
                            ReportHelper.report("主题破解", V("device/resource 已注入 APP 皮肤项，首页应显示主题背景"));
                        }
                    }
                    // 主题相关响应：输出解包内容到日志，便于找云控参数（最新/人气、仪表APP、单价、划线价等）
                    if (looksTheme) {
                        String toLog = decrypted.length() > DECRYPT_LOG_MAX
                                ? decrypted.substring(0, DECRYPT_LOG_MAX) + "\n...（截断，共 " + decrypted.length() + " 字）"
                                : decrypted;
                        ReportHelper.report("解包", "【主题相关】\n" + toLog);
                        // 疑似「我的主题库」空列表：便于进「我的」时在抓包中定位该接口
                        if (modified.contains("themeList") && (modified.contains("\"themeList\":[]") || modified.contains("\"themeList\": []"))) {
                            ReportHelper.report("解包-空主题列表", "【疑似我的主题库空列表】\n" + (decrypted.length() > 1200 ? decrypted.substring(0, 1200) + "..." : decrypted));
                        }
                    }
                    // APP 皮肤失败 72057：任意解包内容含 72057 时单独打日志，便于定位「下载并应用失败」对应的接口
                    if (decrypted.contains("72057")) {
                        String errLog = decrypted.length() > DECRYPT_LOG_MAX
                                ? decrypted.substring(0, DECRYPT_LOG_MAX) + "\n...（截断，共 " + decrypted.length() + " 字）"
                                : decrypted;
                        ReportHelper.report("解包-含72057", "【疑似 APP 皮肤/应用失败接口】\n" + errLog);
                    }
                    if (changed) {
                        param.setResult(modified);
                        ReportHelper.report("主题破解", V("JSON 解密篡改(themeShow/owned/paid 等)"));
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
                    ReportHelper.report("抓包", "【URL】" + url + " 抓取到加密包");
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
