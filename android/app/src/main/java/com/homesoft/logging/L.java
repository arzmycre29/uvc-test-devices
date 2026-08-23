package com.homesoft.logging;

import android.util.Log;

public class L {
    private static final String TAG = "UvcKernel";

    public static void a(String tag, String msg) {
        Log.d(tag != null ? tag : TAG, msg != null ? msg : "");
    }

    public static void b(String tag, String msg, Throwable th) {
        Log.e(tag != null ? tag : TAG, msg != null ? msg : "", th);
    }

    public static void c(String tag, String msg, Exception exc) {
        Log.w(tag != null ? tag : TAG, msg != null ? msg : "", exc);
    }

    public static void i(String tag, String msg) {
        Log.i(tag != null ? tag : TAG, msg != null ? msg : "");
    }
}