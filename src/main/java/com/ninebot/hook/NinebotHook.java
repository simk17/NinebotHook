package com.ninebot.hook;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import com.ninebot.hook.module.ModuleManager;

public class NinebotHook implements IXposedHookLoadPackage {

    private static final int HOOK_LOG_VERSION = 70; 
    private static String V(String msg) { return msg + " | 插件v" + HOOK_LOG_VERSION; }

    private static final String TARGET_PACKAGE = "cn.ninebot.ninebot";
    private static final String MODULE_PACKAGE = "com.ninebot.hook";

    private static final XSharedPreferences sPrefs = new XSharedPreferences(MODULE_PACKAGE, "theme_config");

    static {
        sPrefs.makeWorldReadable();
    }

    private ModuleManager moduleManager;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !TARGET_PACKAGE.equals(lpparam.packageName)) return;

        XposedBridge.log("NinebotHook: handleLoadPackage 被调用 包=" + lpparam.packageName + " 进程=" + lpparam.processName);
        
        ReportHelper.reportSync("启动", V("handleLoadPackage 开始 | 进程=" + lpparam.processName));

        ClassLoader loader = lpparam.classLoader;

        // 创建模块管理器并安装所有模块
        moduleManager = new ModuleManager(sPrefs);
        moduleManager.installAll(lpparam);
        
        // 监听后续类加载（与原始代码逻辑完全一致）
        XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || param.getResult() == null) return;
                Class<?> clazz = (Class<?>) param.getResult();
                String name = clazz.getName();
                try {
                    if (name.contains("ninebot")) {
                        Log.i("NinebotHook/类加载", name);
                    }
                    // 投屏相关类：用 param.thisObject（与原始代码一致）
                    if (name.contains("ninebot") && (name.contains("capture") || name.contains("Capture") 
                            || name.contains("screencast") || name.contains("ScreenCast")
                            || name.contains("CodecCapture") || name.contains("Mpeg2Capture")
                            || name.contains("AbstractCapture")
                            || name.contains("ScreenCastRequest") || name.contains("ScreenCastHelper"))) {
                        ClassLoader targetLoader = (ClassLoader) param.thisObject;
                        Log.w("NinebotHook/投屏", "检测到投屏类加载: " + name + " loader=" + targetLoader);
                        if (moduleManager != null) {
                            moduleManager.retryAll(targetLoader);
                        }
                        return;
                    }
                    // DeviceApplication：用 param.thisObject（与原始代码一致）
                    if ("cn.ninebot.device.DeviceApplication".equals(name)) {
                        ClassLoader targetLoader = (ClassLoader) param.thisObject;
                        Log.w("NinebotHook/投屏", "检测到 DeviceApplication 加载");
                        if (moduleManager != null) {
                            moduleManager.retryAll(targetLoader);
                        }
                        return;
                    }
                    // 普通 ninebot 类：用外层 loader（与原始代码 tryHookAll(loader) 一致）
                    if (name.contains("ninebot")) {
                        if (moduleManager != null) {
                            moduleManager.retryAll(loader);
                        }
                    }
                    // OkHttp：用外层 loader（与原始代码一致）
                    if (name != null && name.contains("okhttp3")) {
                        if (moduleManager != null) {
                            moduleManager.retryAll(loader);
                        }
                    }
                } catch (Throwable t) {
                    Log.w("NinebotHook/类加载", "loadClass 回调异常", t);
                }
            }
        });
        
        // 直接尝试 hook ScreenCastRequest（与原始代码一致）
        // 这个在 ScreenCastModule.retryInstall 中处理
        
        int installedCount = moduleManager.getInstalledModuleCount();
        ReportHelper.reportSync("启动", V("模块安装完成，已安装 " + installedCount + " 个模块"));
        XposedBridge.log("NinebotHook: 模块安装完成，已安装 " + installedCount + " 个模块");
    }

}