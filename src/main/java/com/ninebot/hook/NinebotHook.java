package com.ninebot.hook;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 九号出行LSPosed插件
 */
public class NinebotHook implements IXposedHookLoadPackage {

    private static final int HOOK_LOG_VERSION = 49; 
    private static String V(String msg) { return msg + " | 插件v" + HOOK_LOG_VERSION; }

    private static final String TARGET_PACKAGE = "cn.ninebot.ninebot";
    private static final String MODULE_PACKAGE = "com.ninebot.hook";

    // 修正：必须与 HookConfig 中的 PREF_NAME 保持一致
    private static final XSharedPreferences sPrefs = new XSharedPreferences(MODULE_PACKAGE, "theme_config");

    static {
        sPrefs.makeWorldReadable();
    }

    /** 1. 强制开启内测主题功能（themeShow、内存、Tab、入口） */
    private static boolean isThemeShowEnabled() {
        sPrefs.reload();
        if (sPrefs.contains("enable_theme_show")) return sPrefs.getBoolean("enable_theme_show", true);
        return sPrefs.getBoolean("enable_theme_hack", true);
    }
    /** 2. 破解主题费用（已拥有/72057/device/resource/我的主题库） */
    private static boolean isThemeCrackPaidEnabled() {
        sPrefs.reload();
        if (sPrefs.contains("enable_theme_crack_paid")) return sPrefs.getBoolean("enable_theme_crack_paid", true);
        return sPrefs.getBoolean("enable_theme_hack", true);
    }
    /** 3. 其他：抓包、水印、反调试 */
    private static boolean isOtherEnabled() {
        sPrefs.reload();
        if (sPrefs.contains("enable_other")) return sPrefs.getBoolean("enable_other", true);
        return true;
    }
    /** 4. 修改车辆型号：强制 getVehicleType() 返回指定 type（默认 116 = Dz110P） */
    private static boolean isForceMotorDisplayEnabled() {
        sPrefs.reload();
        return sPrefs.getBoolean("enable_force_motor_display", false);
    }
    private static int getForceMotorVehicleType() {
        sPrefs.reload();
        return sPrefs.getInt("force_motor_vehicle_type", 116);
    }
    /** 5. 投屏导航叠加（单独开关，与「其他」无关） */
    private static boolean isScreenCastOverlayEnabled() {
        sPrefs.reload();
        return sPrefs.getBoolean("enable_screen_cast_overlay", false);
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
            ReportHelper.reportSync("配置检查", V("主题显示=" + isThemeShowEnabled() + " 破解费用=" + isThemeCrackPaidEnabled() + " 其他=" + isOtherEnabled() + " 车辆型号=" + isForceMotorDisplayEnabled() + " | 服务器=" + (canReadServer ? "正常" : "未设置")));
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

        // 监听后续类加载（MCP 确认：CaptureClient 在 classes17.dex，首屏不会加载）
        XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || param.getResult() == null) return;
                Class<?> clazz = (Class<?>) param.getResult();
                String name = clazz.getName();
                try {
                    // 源码：DeviceApplication.init() 内会引用 CaptureClient.INSTANCE，init 执行时 CaptureClient 已加载
                    if ("cn.ninebot.device.DeviceApplication".equals(name)) {
                        ClassLoader targetLoader = (ClassLoader) param.thisObject;
                        hookDeviceApplicationInitThenScreenCast(clazz, targetLoader);
                        return;
                    }
                    if (name.contains("ninebot") && (name.contains("capture") || name.contains("Capture") || name.contains("screencast") || name.contains("ScreenCast"))) {
                        ClassLoader targetLoader = (ClassLoader) param.thisObject;
                        tryHookAll(targetLoader);
                    } else if (name.contains("ninebot")) {
                        tryHookAll(loader);
                    }
                } catch (Throwable t) {
                    Log.w(TAG_SCREEN_CAST, "loadClass 回调异常", t);
                }
            }
        });
    }

    /** MCP 确认：DeviceApplication.init(Context) 中会调用 CaptureClient.INSTANCE.setNotificationCreator，init 执行后 CaptureClient 已加载，用 context 的 ClassLoader 挂投屏 hook */
    private boolean hookedDeviceApplicationInit = false;
    private void hookDeviceApplicationInitThenScreenCast(Class<?> deviceAppClass, ClassLoader loader) {
        if (hookedDeviceApplicationInit) return;
        try {
            XposedBridge.hookAllMethods(deviceAppClass, "init", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 1 || !(param.args[0] instanceof Context)) return;
                    Context ctx = (Context) param.args[0];
                    ClassLoader appLoader = ctx.getClass().getClassLoader();
                    Log.w(TAG_SCREEN_CAST, "DeviceApplication.init 已执行，用 Application ClassLoader 挂投屏 hook");
                    tryHookScreenCastOverlay(appLoader);
                }
            });
            hookedDeviceApplicationInit = true;
            Log.w(TAG_SCREEN_CAST, "已挂载 DeviceApplication.init 回调，等待 init 执行后安装投屏 hook");
        } catch (Throwable t) {
            Log.e(TAG_SCREEN_CAST, "hook DeviceApplication.init 失败", t);
        }
    }

    private synchronized void tryHookAll(ClassLoader loader) {
        tryHookRetrofit(loader);
        tryHookDecrypt(loader);
        tryHookMemoryConfig(loader);
        tryHookThemeUI(loader);
        tryHookEntry(loader);
        tryHookDashboardAndHomeCards(loader);
        tryHookDeviceBeanVehicleType(loader);
        tryHookTrackDetailDataObjects(loader);
        tryHookScreenCastOverlay(loader);
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
                    if (isOtherEnabled()) injectInterceptor(param, loader);
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
                    String decrypted = (String) param.getResult();
                    if (decrypted == null) return;
                    // 抓包-车辆信息：开启「其他」时，解密后若含 vehicle_type 则明文上报，便于确认 vehicleType 位数与车辆编号
                    if (isOtherEnabled() && decrypted.contains("vehicle_type")) {
                        reportVehicleInfoFromDecrypted(decrypted);
                    }
                    if (!isThemeShowEnabled() && !isThemeCrackPaidEnabled()) return;
                    String modified = decrypted;
                    boolean changed = false;
                    // 1. 云控入口：themeShow 控制是否显示主题 Tab（仅「主题显示」开关）
                    if (isThemeShowEnabled() && modified.contains("themeShow")) {
                        modified = modified.replaceAll("\"themeShow\":\\s*[0-9]+", "\"themeShow\":1");
                        changed = true;
                    }
                    // 2. 付费/已拥有 等（仅「破解费用」开关）
                    boolean looksTheme = modified.contains("theme") || modified.contains("themeId") || modified.contains("skin")
                            || modified.contains("showEntranceList") || modified.contains("eraseResource") || modified.contains("EraseResource");
                    if (isThemeCrackPaidEnabled() && looksTheme) {
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
                    // 主题相关响应：输出解包内容到日志（任一主题开关开启时）
                    if ((isThemeShowEnabled() || isThemeCrackPaidEnabled()) && looksTheme) {
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
                    if (!isThemeShowEnabled()) return;
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
                    if (!isThemeShowEnabled()) return;
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

    /**
     * 仪表盘/首页卡片四层 hook：
     * A) DeviceBeanExtKt.getConfig → forceType DynamicDeviceConfig
     * B) createDynamicDeviceDetailPage → Motor Fragment
     * B2) DynamicPageViewModel.<init> → 替换 deviceConfig
     * C) NavigationViewController / RecentTrailViewHolder → serverId=116
     */
    private boolean hookedGetConfig = false;
    private boolean hookedCreateDetailPage = false;
    private boolean hookedDynamicPageVM = false;
    private boolean hookedHomeNavCardCtor = false;
    private boolean hookedHomeTrailCardCtor = false;
    private void tryHookDashboardAndHomeCards(ClassLoader loader) {
        try {
            // A) hook DeviceBeanExtKt.getConfig → 返回 forceType 配置
            Class<?> extKt = XposedHelpers.findClassIfExists(
                    "cn.ninebot.device.dynamic.DeviceBeanExtKt", loader);
            if (extKt != null && !hookedGetConfig) {
                XposedBridge.hookAllMethods(extKt, "getConfig", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isForceMotorDisplayEnabled()) return;
                        if (param.args == null || param.args.length < 1 || param.args[0] == null) return;
                        try {
                            int forceType = getForceMotorVehicleType();
                            Class<?> dcmClass = XposedHelpers.findClass(
                                    "cn.ninebot.library.bluetooth.dynamic.DynamicConfigManager",
                                    param.args[0].getClass().getClassLoader());
                            Object dcmInstance = XposedHelpers.getStaticObjectField(dcmClass, "instance");
                            if (dcmInstance != null) {
                                Object config = XposedHelpers.callMethod(
                                        dcmInstance, "getDeviceConfigWithServerId", forceType);
                                param.setResult(config);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                hookedGetConfig = true;
                ReportHelper.report("车型判定", "HOOK_OK DeviceBeanExtKt.getConfig");
            }

            // B) hook createDynamicDeviceDetailPage → 用 forceType config 创建 Fragment
            Class<?> fragKt = XposedHelpers.findClassIfExists(
                    "cn.ninebot.device.dynamic.detail.DynamicDeviceDetailFragmentKt", loader);
            if (fragKt != null && !hookedCreateDetailPage) {
                XposedBridge.hookAllMethods(fragKt, "createDynamicDeviceDetailPage", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isForceMotorDisplayEnabled()) return;
                        if (param.args == null || param.args.length < 1 || param.args[0] == null) return;
                        try {
                            Object bean = param.args[0];
                            int forceType = getForceMotorVehicleType();
                            Class<?> dcmClass = XposedHelpers.findClass(
                                    "cn.ninebot.library.bluetooth.dynamic.DynamicConfigManager",
                                    bean.getClass().getClassLoader());
                            Object dcmInstance = XposedHelpers.getStaticObjectField(dcmClass, "instance");
                            if (dcmInstance == null) return;
                            Object config = XposedHelpers.callMethod(
                                    dcmInstance, "getDeviceConfigWithServerId", forceType);
                            if (config == null) return;
                            String modelName = (String) XposedHelpers.callMethod(config, "getModelIgnoreCase");
                            Class<?> modelsClass = XposedHelpers.findClass(
                                    "cn.ninebot.device.dynamic.config.DynamicDeviceModels", loader);
                            Object companion = XposedHelpers.getStaticObjectField(modelsClass, "Companion");
                            Object model = XposedHelpers.callMethod(companion, "findByName", modelName);
                            Object fragment = XposedHelpers.callMethod(model, "createDetailUi", bean);
                            param.setResult(fragment);
                            ReportHelper.report("车型判定",
                                    "DASHBOARD_FRAGMENT_FORCE model=" + modelName + " type=" + forceType);
                        } catch (Throwable t) {
                            ReportHelper.report("车型判定",
                                    "DASHBOARD_FRAGMENT_FORCE 异常: " + t.getMessage());
                        }
                    }
                });
                hookedCreateDetailPage = true;
                ReportHelper.report("车型判定", "HOOK_OK createDynamicDeviceDetailPage");
            }

            // B2) hook DynamicPageViewModel：白名单——仅当 configName 为仪表盘所用时才替换；其余（更多设置/仪表设置/骑行模式等）保持 116
            Class<?> dpvmClass = XposedHelpers.findClassIfExists(
                    "cn.ninebot.device.dynamic.DynamicPageViewModel", loader);
            if (dpvmClass != null && !hookedDynamicPageVM) {
                final java.util.Set<String> dashboardConfigWhitelist = new java.util.HashSet<>();
                dashboardConfigWhitelist.add("device_ui_dashboard");
                dashboardConfigWhitelist.add("device_ui_detail");
                XposedBridge.hookAllConstructors(dpvmClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isForceMotorDisplayEnabled()) return;
                        String configName = (param.args != null && param.args.length >= 4 && param.args[3] instanceof String)
                                ? (String) param.args[3] : null;
                        boolean isDashboard = (configName == null || configName.isEmpty())
                                || dashboardConfigWhitelist.contains(configName);
                        if (!isDashboard) {
                            ReportHelper.report("车型判定", "DPVM_SKIP 保持116 configName=" + configName);
                            return;
                        }
                        try {
                            int forceType = getForceMotorVehicleType();
                            ClassLoader cl = param.thisObject.getClass().getClassLoader();
                            Class<?> dcmClass = XposedHelpers.findClass(
                                    "cn.ninebot.library.bluetooth.dynamic.DynamicConfigManager", cl);
                            Object dcmInstance = XposedHelpers.getStaticObjectField(dcmClass, "instance");
                            if (dcmInstance == null) return;
                            Object forceConfig = XposedHelpers.callMethod(
                                    dcmInstance, "getDeviceConfigWithServerId", forceType);
                            if (forceConfig == null) return;
                            XposedHelpers.setObjectField(param.thisObject, "deviceConfig", forceConfig);
                            ReportHelper.report("车型判定",
                                    "DPVM_CONFIG_FORCE configName=" + configName + " -> " + forceType);
                        } catch (Throwable t) {
                            ReportHelper.report("车型判定",
                                    "DPVM_CONFIG_FORCE 异常: " + t.getMessage());
                        }
                    }
                });
                hookedDynamicPageVM = true;
                ReportHelper.report("车型判定", "HOOK_OK DynamicPageViewModel.<init>");
            }

            // C) 首页卡片构造函数 → serverId=116
            Class<?> navCard = XposedHelpers.findClassIfExists(
                    "cn.ninebot.device.dynamic.viewHolder.NavigationViewController", loader);
            if (navCard != null && !hookedHomeNavCardCtor) {
                XposedBridge.hookAllConstructors(navCard, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isForceMotorDisplayEnabled()) return;
                        if (param.args != null && param.args.length >= 3 && param.args[2] instanceof Integer) {
                            param.args[2] = 116;
                            ReportHelper.report("车型判定", "HOME_NAV_CARD_FORCE serverId -> 116");
                        }
                    }
                });
                hookedHomeNavCardCtor = true;
                ReportHelper.report("车型判定", "HOOK_OK NavigationViewController.<init>");
            }

            Class<?> trailCard = XposedHelpers.findClassIfExists(
                    "cn.ninebot.device.dynamic.viewHolder.RecentTrailViewHolder", loader);
            if (trailCard != null && !hookedHomeTrailCardCtor) {
                XposedBridge.hookAllConstructors(trailCard, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isForceMotorDisplayEnabled()) return;
                        if (param.args != null && param.args.length >= 3 && param.args[2] instanceof Integer) {
                            param.args[2] = 116;
                            ReportHelper.report("车型判定", "HOME_TRAIL_CARD_FORCE serverId -> 116");
                        }
                    }
                });
                hookedHomeTrailCardCtor = true;
                ReportHelper.report("车型判定", "HOOK_OK RecentTrailViewHolder.<init>");
            }

            if (hookedGetConfig && hookedCreateDetailPage && hookedDynamicPageVM && hookedHomeNavCardCtor && hookedHomeTrailCardCtor) {
                ReportHelper.reportSync("注入成功", V("已挂载仪表盘四层hook+首页卡片修复"));
            }
        } catch (Throwable t) {
            Log.e("NinebotHook/车型", "tryHookDashboardAndHomeCards 失败", t);
        }
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
                    if (isThemeShowEnabled()) param.setResult(true);
                }
            });
            hookedEntry = true;
        } catch (Throwable ignored) {}
    }

    private static int vehicleTypeLogCount = 0;
    private static final int VEHICLE_TYPE_LOG_LIMIT = 5;

    private static void logVehicleTypeDecision(String decision, int realType, int retType) {
        if (vehicleTypeLogCount >= VEHICLE_TYPE_LOG_LIMIT) return;
        vehicleTypeLogCount++;
        String msg = decision + " real=" + realType + " ret=" + retType;
        Log.w("NinebotHook/车型", msg);
        // 同步到 Web 报表，便于不看 logcat 时定位“谁把类型改了”
        ReportHelper.report("车型判定", msg);
    }

    private boolean hookedDeviceBeanVehicleType = false;
    private void tryHookDeviceBeanVehicleType(ClassLoader loader) {
        if (hookedDeviceBeanVehicleType) return;
        try {
            Class<?> clazz = XposedHelpers.findClassIfExists("cn.ninebot.device.bean.DeviceBean", loader);
            if (clazz == null) return;
            XposedBridge.hookAllMethods(clazz, "getVehicleType", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!isForceMotorDisplayEnabled()) return;
                    int realType = 116;
                    try {
                        Object ori = param.getResult();
                        if (ori instanceof Integer) realType = (Integer) ori;
                    } catch (Throwable ignored) {}
                    try {
                        int fieldType = XposedHelpers.getIntField(param.thisObject, "vehicleType");
                        if (realType <= 0 && fieldType > 0) realType = fieldType;
                    } catch (Throwable ignored) {}
                    if (realType <= 0) realType = 116;
                    // V44 策略：一律返回 116（身份归属正确）
                    // 仪表盘展示由 getConfig hook + TopGuide hook 独立处理
                    param.setResult(Integer.valueOf(116));
                    logVehicleTypeDecision("FORCE_116", realType, 116);
                }
            });
            hookedDeviceBeanVehicleType = true;
            ReportHelper.reportSync("注入成功", V("已挂载 getVehicleType → 116"));
        } catch (Throwable ignored) {}
    }


    private static String rewriteTrackDetailType(String json, int displayType) {
        if (json == null || json.isEmpty()) return json;
        String changed = json
                .replaceAll("\"vehicleType\"\\s*:\\s*-?\\d+", "\"vehicleType\":" + displayType)
                .replaceAll("\"vehicle_type\"\\s*:\\s*-?\\d+", "\"vehicle_type\":" + displayType)
                .replaceAll("\"hide_speed\"\\s*:\\s*true", "\"hide_speed\":false");
        // 若详情数据里没有类型字段，则补一个展示字段，不影响原里程/轨迹/时间数据
        if (!changed.contains("\"vehicleType\"") && changed.startsWith("{") && changed.endsWith("}")) {
            changed = changed.substring(0, changed.length() - 1) + ",\"vehicleType\":" + displayType + ",\"hide_speed\":false}";
        }
        return changed;
    }

    /** 轨迹详情/分享：直接改数据对象，不改身份归属（116） */
    private boolean hookedTrackResultCopy = false;
    private boolean hookedTrackRnToRNPage = false;
    private boolean hookedTrackPlayTrackNavigate = false;
    private boolean hookedTrackDetailDataObjectsReported = false;
    private void tryHookTrackDetailDataObjects(ClassLoader loader) {
        try {
            // 1) TrackResult.copy() -> RN 详情前的对象副本，直接写入展示 type
            Class<?> trackResultClazz = XposedHelpers.findClassIfExists("com.ninebot.track.TrackResult", loader);
            if (trackResultClazz != null && !hookedTrackResultCopy) {
                XposedBridge.hookAllMethods(trackResultClazz, "copy", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isForceMotorDisplayEnabled()) return;
                        Object copyObj = param.getResult();
                        if (copyObj == null) return;
                        int t = getForceMotorVehicleType();
                        try { XposedHelpers.callMethod(copyObj, "setVehicleType", t); } catch (Throwable ignored) {}
                        ReportHelper.report("轨迹详情", "TrackResult.copy 已写入展示 type=" + t);
                    }
                });
                hookedTrackResultCopy = true;
                ReportHelper.report("轨迹详情", "HOOK_OK TrackResult.copy");
            }

            // 2) RN 通用路由：module=Track 时，扫描 params 中所有 JSON 字符串并改展示字段
            Class<?> rnClazz = XposedHelpers.findClassIfExists("cn.ninebot.react.RNProviderImpl", loader);
            if (rnClazz != null && !hookedTrackRnToRNPage) {
                XposedBridge.hookAllMethods(rnClazz, "toRNPage", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isForceMotorDisplayEnabled()) return;
                        if (param.args == null || param.args.length < 2) return;
                        if (!(param.args[0] instanceof String) || !(param.args[1] instanceof Map)) return;
                        String module = (String) param.args[0];
                        if (!"Track".equals(module)) return;
                        Map map = (Map) param.args[1];
                        int t = getForceMotorVehicleType();
                        String route = String.valueOf(map.get("route"));
                        ReportHelper.report("轨迹详情", "toRNPage Track route=" + route + " keys=" + map.keySet());
                        for (Object k : map.keySet().toArray()) {
                            Object v = map.get(k);
                            if (v instanceof String) {
                                String s = (String) v;
                                if (s.startsWith("{") && s.endsWith("}")) {
                                    String changed = rewriteTrackDetailType(s, t);
                                    if (!changed.equals(s)) {
                                        map.put(k, changed);
                                        ReportHelper.report("轨迹详情", "toRNPage Track 已改 JSON key=" + k);
                                    }
                                }
                            }
                        }
                    }
                });
                XposedBridge.hookAllMethods(rnClazz, "toOtherTrackDetail", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isForceMotorDisplayEnabled()) return;
                        if (param.args == null || param.args.length < 1 || !(param.args[0] instanceof String)) return;
                        String raw = (String) param.args[0];
                        String changed = rewriteTrackDetailType(raw, getForceMotorVehicleType());
                        if (!raw.equals(changed)) param.args[0] = changed;
                    }
                });
                hookedTrackRnToRNPage = true;
                ReportHelper.report("轨迹详情", "HOOK_OK RNProviderImpl.toRNPage+toOtherTrackDetail");
            }

            // 3) DevicePage.PLAY_TRACK.navigate：设备侧详情对象 MotorTrackDetail 直改 hideSpeed=false
            Class<?> devicePageClazz = XposedHelpers.findClassIfExists("cn.ninebot.device.DevicePage", loader);
            if (devicePageClazz != null) {
                Object playTrackObj = null;
                try { playTrackObj = XposedHelpers.getStaticObjectField(devicePageClazz, "PLAY_TRACK"); } catch (Throwable ignored) {}
                if (playTrackObj != null && !hookedTrackPlayTrackNavigate) {
                    Class<?> playTrackClazz = playTrackObj.getClass();
                    XposedBridge.hookAllMethods(playTrackClazz, "navigate", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isForceMotorDisplayEnabled()) return;
                            if (param.args == null || param.args.length < 2) return;
                            Object parameters = param.args[1];
                            if (!(parameters instanceof String)) return;
                            String raw = (String) parameters;
                            int t = getForceMotorVehicleType();
                            String changed = rewriteTrackDetailType(raw, t);
                            if (!raw.equals(changed)) {
                                param.args[1] = changed;
                                ReportHelper.report("轨迹详情", "PLAY_TRACK.navigate 已改参数 JSON");
                            }
                        }
                    });
                    hookedTrackPlayTrackNavigate = true;
                    ReportHelper.report("轨迹详情", "HOOK_OK DevicePage.PLAY_TRACK.navigate");
                }
            }
            if (!hookedTrackDetailDataObjectsReported
                    && hookedTrackResultCopy
                    && hookedTrackRnToRNPage
                    && hookedTrackPlayTrackNavigate) {
                hookedTrackDetailDataObjectsReported = true;
                ReportHelper.reportSync("注入成功", V("已挂载轨迹详情/分享数据对象层改写"));
            }
        } catch (Throwable t) {
            Log.e("NinebotHook/轨迹详情", "tryHookTrackDetailDataObjects 失败", t);
        }
    }

    /** 从解密后的 JSON 中提取车辆信息并上报，便于在 Web 日志中看到 vehicle_type、车辆编号等（vehicleType 为整数，通常 1～2 位） */
    private static final int VEHICLE_INFO_MAX_DEVICES = 8;
    private static final int VEHICLE_INFO_MAX_LEN = 2800;
    private void reportVehicleInfoFromDecrypted(String decrypted) {
        try {
            Matcher vtMatcher = Pattern.compile("\"vehicle_type\"\\s*:\\s*(-?\\d+)").matcher(decrypted);
            int count = 0;
            int start = 0;
            while (vtMatcher.find(start) && count < VEHICLE_INFO_MAX_DEVICES) {
                String vehicleTypeVal = vtMatcher.group(1);
                int pos = vtMatcher.start();
                int windowStart = Math.max(0, pos - 400);
                int windowEnd = Math.min(decrypted.length(), pos + 1200);
                String window = decrypted.substring(windowStart, windowEnd);
                String wnumber = firstGroup(Pattern.compile("\"wnumber\"\\s*:\\s*\"([^\"]*)\"").matcher(window));
                String devId = firstGroup(Pattern.compile("\"dev_id\"\\s*:\\s*\"([^\"]*)\"").matcher(window));
                String deviceName = firstGroup(Pattern.compile("\"device_name\"\\s*:\\s*\"([^\"]*)\"").matcher(window));
                String vehicleNameZh = firstGroup(Pattern.compile("\"vehicle_name_zh\"\\s*:\\s*\"([^\"]*)\"").matcher(window));
                String andMac = firstGroup(Pattern.compile("\"and_mac\"\\s*:\\s*\"([^\"]*)\"").matcher(window));
                StringBuilder one = new StringBuilder();
                one.append("vehicle_type=").append(vehicleTypeVal);
                if (wnumber != null && !wnumber.isEmpty()) one.append(" wnumber=").append(wnumber);
                if (devId != null && !devId.isEmpty()) one.append(" dev_id=").append(devId);
                if (deviceName != null && !deviceName.isEmpty()) one.append(" device_name=").append(deviceName);
                if (vehicleNameZh != null && !vehicleNameZh.isEmpty()) one.append(" vehicle_name_zh=").append(vehicleNameZh);
                if (andMac != null && !andMac.isEmpty()) one.append(" mac=").append(andMac);
                String msg = one.length() > VEHICLE_INFO_MAX_LEN ? one.substring(0, VEHICLE_INFO_MAX_LEN) + "…" : one.toString();
                ReportHelper.report("车辆信息", msg);
                count++;
                start = vtMatcher.end();
            }
        } catch (Throwable ignored) {}
    }

    private static String firstGroup(Matcher m) {
        return m.find() ? m.group(1) : null;
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
                Object response;
                try {
                    response = XposedHelpers.callMethod(chain, "proceed", request);
                } catch (Throwable t) {
                    // XposedHelpers 会把受检异常包装成 InvocationTargetError；解包后抛回给 OkHttp，避免崩溃
                    Throwable cause = t.getCause();
                    if (cause != null) throw cause;
                    throw t;
                }
                Object body = XposedHelpers.callMethod(response, "body");
                            if (body != null) {
                                try {
                                    String content = (String) XposedHelpers.callMethod(body, "string");
                        ReportHelper.report("抓包", "【URL】" + url + " 抓取到加密包");
                                    Object contentType = XposedHelpers.callMethod(body, "contentType");
                        Object newBody = XposedHelpers.callStaticMethod(responseBodyClass, "create", contentType, content);
                                    Object respBuilder = XposedHelpers.callMethod(response, "newBuilder");
                                    XposedHelpers.callMethod(respBuilder, "body", newBody);
                                    response = XposedHelpers.callMethod(respBuilder, "build");
                                } catch (Throwable t) {
                        // 某些请求在取消/重置时读取 body 会抛错；保持原响应返回，避免影响业务流程
                        ReportHelper.report("抓包", "【URL】" + url + " 读取响应失败: " + t.getClass().getSimpleName());
                                }
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
                    if (isOtherEnabled()) param.setResult(false);
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
                            Toast.makeText(ctx, "九号出行LSPosed插件 注入成功 v" + HOOK_LOG_VERSION, Toast.LENGTH_SHORT).show();
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
                    if (!isOtherEnabled()) return;
                    final Activity activity = (Activity) param.thisObject;
                    if (activity.getPackageName().equals(TARGET_PACKAGE)) {
                        new Handler(Looper.getMainLooper()).post(() -> addWatermarkToActivity(activity));
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    /**
     * 投屏导航：在传入的 view 上包一层并叠加测试层。用满屏 1mm 间距网格线测试分辨率/位置是否被绘制。
     */
    private static final int FALLBACK_CAPTURE_WIDTH = 800;
    private static final int FALLBACK_CAPTURE_HEIGHT = 480;

    /** 满屏 1mm 间距分割线，用于测试车机是否绘制我们叠加的 View、分辨率与位置 */
    private static final class Grid1mmOverlayView extends View {
        private final float mmPx;
        private final Paint linePaint;

        Grid1mmOverlayView(Context ctx) {
            super(ctx);
            mmPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 1, ctx.getResources().getDisplayMetrics());
            linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            linePaint.setColor(Color.WHITE);
            linePaint.setStrokeWidth(1f);
            linePaint.setStyle(Paint.Style.STROKE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0 || mmPx <= 0) return;
            for (float x = 0; x <= w; x += mmPx) {
                canvas.drawLine(x, 0, x, h, linePaint);
            }
            for (float y = 0; y <= h; y += mmPx) {
                canvas.drawLine(0, y, w, y, linePaint);
            }
        }
    }

    /** 包装原 view，上层叠加满屏 1mm 网格线（测试用） */
    private static View wrapCaptureViewWithOverlay(View original, int width, int height) {
        if (width <= 0) width = FALLBACK_CAPTURE_WIDTH;
        if (height <= 0) height = FALLBACK_CAPTURE_HEIGHT;
        Context ctx = original.getContext();
        FrameLayout wrapper = new FrameLayout(ctx);
        wrapper.setLayoutParams(new FrameLayout.LayoutParams(width, height));
        wrapper.addView(original, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View gridOverlay = new Grid1mmOverlayView(ctx);
        wrapper.addView(gridOverlay, new FrameLayout.LayoutParams(width, height));
        try {
            wrapper.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            wrapper.layout(0, 0, width, height);
        } catch (Throwable ignored) {}
        return wrapper;
    }

    private static final String TAG_SCREEN_CAST = "NinebotHook/投屏";

    private boolean hookedScreenCastOverlay = false;
    private void tryHookScreenCastOverlay(ClassLoader loader) {
        if (hookedScreenCastOverlay) return;
        Log.i("NinebotHook", "[投屏] tryHookScreenCastOverlay 被调用 loader=" + (loader != null ? loader.getClass().getName() : "null"));
        Log.w(TAG_SCREEN_CAST, "tryHookScreenCastOverlay 被调用 loader=" + (loader != null ? loader.getClass().getName() : "null"));
        try {
            // 入口：真正被编码投屏的是 createCapture 传入的 view，必须在此处包装
            Class<?> captureClient = XposedHelpers.findClassIfExists("cn.ninebot.capture.CaptureClient", loader);
            if (captureClient == null) {
                Log.w(TAG_SCREEN_CAST, "CaptureClient 未找到(cn.ninebot.capture.CaptureClient)，投屏 hook 未安装。若你的是其它版本请用 JADX 查实际类名。");
                return;
            }
            XposedBridge.hookAllMethods(captureClient, "createCapture", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isScreenCastOverlayEnabled()) return;
                    if (param.args == null || param.args.length < 5) return;
                    View original = param.args[2] instanceof View ? (View) param.args[2] : null;
                    if (original == null) return;
                    int w = param.args[3] instanceof Integer ? (Integer) param.args[3] : 0;
                    int h = param.args[4] instanceof Integer ? (Integer) param.args[4] : 0;
                    if (w <= 0 || h <= 0) {
                        ViewGroup.LayoutParams lp = original.getLayoutParams();
                        if (lp != null) { w = lp.width; h = lp.height; }
                    }
                    param.args[2] = wrapCaptureViewWithOverlay(original, w, h);
                    String msg = "createCapture 已调用 w=" + w + " h=" + h + " 已包装叠加";
                    Log.w(TAG_SCREEN_CAST, msg);
                    ReportHelper.reportSync("投屏", msg);
                }
            });
            Log.w(TAG_SCREEN_CAST, "createCapture 已挂载，开始导航后应出现本条及「createCapture 已调用」");
            // 备用：replaceView 也强制替换为仅提示语
            Class<?> wrapperClazz = XposedHelpers.findClassIfExists("cn.ninebot.capture.CaptureClient$CaptureControllerWrapper", loader);
            if (wrapperClazz != null) {
                Log.w(TAG_SCREEN_CAST, "replaceView 已挂载");
                XposedBridge.hookAllMethods(wrapperClazz, "replaceView", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isScreenCastOverlayEnabled()) return;
                        View original = param.args[0] instanceof View ? (View) param.args[0] : null;
                        if (original == null) return;
                        ViewGroup.LayoutParams lp = original.getLayoutParams();
                        int w = lp != null ? lp.width : 0;
                        int h = lp != null ? lp.height : 0;
                        param.args[0] = wrapCaptureViewWithOverlay(original, w, h);
                    }
                });
            }
            // Service 侧：createCodecCapture/createMpeg2Capture 为 native 实现，在此替换 view 确保进入 native 的是我们的 View
            Class<?> captureService = XposedHelpers.findClassIfExists("cn.ninebot.capture.CaptureService", loader);
            if (captureService != null) {
                Log.w(TAG_SCREEN_CAST, "CaptureService 已找到，挂载 createCodecCapture/createMpeg2Capture");
                for (String methodName : new String[]{"createCodecCapture", "createMpeg2Capture"}) {
                    try {
                        XposedBridge.hookAllMethods(captureService, methodName, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (!isScreenCastOverlayEnabled()) return;
                                if (param.args == null || param.args.length < 3) return;
                                View original = param.args[0] instanceof View ? (View) param.args[0] : null;
                                if (original == null) return;
                                int w = param.args[1] instanceof Integer ? (Integer) param.args[1] : 0;
                                int h = param.args[2] instanceof Integer ? (Integer) param.args[2] : 0;
                                param.args[0] = wrapCaptureViewWithOverlay(original, w, h);
                            }
                        });
                    } catch (Throwable t) { /* method may not exist */ }
                }
            }
            hookedScreenCastOverlay = true;
            Log.w(TAG_SCREEN_CAST, "投屏 hook 全部挂载完成，请开始「地图导航」投屏后查看是否出现 createCapture 已调用");
            ReportHelper.reportSync("注入成功", V("已挂载投屏 强制替换为仅提示语（Client+Service）"));
        } catch (Throwable t) {
            Log.e(TAG_SCREEN_CAST, "投屏 hook 安装失败", t);
        }
    }

    private void addWatermarkToActivity(Activity activity) {
        try {
            ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
            if (root.findViewWithTag("ninebot_hook_watermark") != null) return;
            TextView tv = new TextView(activity);
            tv.setTag("ninebot_hook_watermark");
            tv.setText("Hook成功 当前插件版本 " + HOOK_LOG_VERSION + "\n请注意：截图前请关闭 Hook 以防官方封堵插件");
            tv.setAlpha(0.5f); // 50% 透明，不挡视线
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tv.setGravity(Gravity.CENTER);
            tv.setShadowLayer(4f, 0f, 0f, Color.BLACK);
            tv.setBackgroundColor(0x80000000); // 50% 透明黑底
            tv.setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12));
            tv.setClickable(false);
            tv.setFocusable(false); // 触摸穿透，不挡下方按钮
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
            lp.gravity = Gravity.CENTER;
            root.addView(tv, lp);
        } catch (Throwable ignored) {}
    }

    private int dp(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
