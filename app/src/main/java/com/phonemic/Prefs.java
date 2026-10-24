package com.phonemic;

import android.content.Context;
import android.content.SharedPreferences;

/** 极简配置存储。 */
final class Prefs {
    private static final String NAME = "phonemic";

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static int getMode(Context c) {
        return sp(c).getInt("mode", Proto.MODE_HOLD);
    }

    static void setMode(Context c, int mode) {
        sp(c).edit().putInt("mode", mode).apply();
    }

    /** 发送键：Proto.SEND_ENTER / SEND_SHIFT_ENTER */
    static int getSendKey(Context c) {
        return sp(c).getInt("sendKey", Proto.SEND_ENTER);
    }

    static void setSendKey(Context c, int v) {
        sp(c).edit().putInt("sendKey", v).apply();
    }

    /** 电池优化白名单提醒过一次就够了，别每次开 App 都弹 */
    static boolean getAskedBattery(Context c) {
        return sp(c).getBoolean("askedBattery", false);
    }

    static void setAskedBattery(Context c, boolean v) {
        sp(c).edit().putBoolean("askedBattery", v).apply();
    }

    private Prefs() { }
}
