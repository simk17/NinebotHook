package com.ninebot.hook.module;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class ModuleManager {

    public ModuleManager(XSharedPreferences prefs) {
    }

    public void installAll(XC_LoadPackage.LoadPackageParam lpparam) {
    }

    public void retryAll(ClassLoader loader) {
    }

    public int getInstalledModuleCount() {
        return 0;
    }
}
