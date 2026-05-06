package com.ninebot.hook;

import android.util.Log;

public class ReportHelper {
    private static final String TAG = "NinebotHook";

    public static void report(String tag, String msg) {
        Log.i(TAG, "[" + tag + "] " + msg);
    }

    public static void reportSync(String tag, String msg) {
        Log.i(TAG, "[" + tag + "] " + msg);
    }
}
