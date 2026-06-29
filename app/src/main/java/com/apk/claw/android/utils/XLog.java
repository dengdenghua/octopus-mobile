package com.apk.claw.android.utils;

import android.util.Log;

public class XLog {
    private static boolean DEBUG = true;

    public static void setDEBUG(boolean debug) {
        DEBUG = debug;
    }

    private static String redact(String msg) {
        if (msg == null) return null;
        return SecretRedactor.redact(msg);
    }

    public static void i(String tag, String msg) {
        if (DEBUG && msg != null) Log.i(tag, redact(msg));
    }

    public static void i(String tag, String msg, Throwable tr) {
        if (DEBUG) Log.i(tag, redact(msg), tr);
    }

    public static void d(String tag, String msg) {
        if (DEBUG && msg != null) Log.d(tag, redact(msg));
    }

    public static void d(String tag, String msg, Throwable tr) {
        if (DEBUG) Log.d(tag, redact(msg), tr);
    }

    public static void e(String tag, String msg) {
        if (msg != null) Log.e(tag, redact(msg));
    }

    public static void e(String tag, String msg, Throwable tr) {
        Log.e(tag, redact(msg), tr);
    }

    public static void e(String tag, Throwable tr) {
        Log.e(tag, "", tr);
    }

    public static void w(String tag, String msg) {
        if (DEBUG && msg != null) Log.w(tag, redact(msg));
    }

    public static void w(String tag, String msg, Throwable tr) {
        if (DEBUG) Log.w(tag, redact(msg), tr);
    }

    public static void w(String tag, Throwable tr) {
        if (DEBUG) Log.w(tag, tr);
    }

    public static void v(String tag, String msg) {
        if (DEBUG && msg != null) Log.v(tag, redact(msg));
    }

    public static void v(String tag, String msg, Throwable tr) {
        if (DEBUG) Log.v(tag, redact(msg), tr);
    }

    public static void wtf(String tag, String msg) {
        if (DEBUG) Log.wtf(tag, redact(msg));
    }

    public static void wtf(String tag, String msg, Throwable tr) {
        if (DEBUG) Log.wtf(tag, redact(msg), tr);
    }

    public static void wtf(String tag, Throwable tr) {
        if (DEBUG) Log.wtf(tag, tr);
    }
}
