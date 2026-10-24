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

    /** 是否播放电脑传来的声音（手机当耳机） */
    static boolean getPlayRemote(Context c) {
        return sp(c).getBoolean("playRemote", true);
    }

    static void setPlayRemote(Context c, boolean on) {
        sp(c).edit().putBoolean("playRemote", on).apply();
    }

    /** 发送键：Proto.SEND_ENTER / SEND_SHIFT_ENTER */
    static int getSendKey(Context c) {
        return sp(c).getInt("sendKey", Proto.SEND_ENTER);
    }

    static void setSendKey(Context c, int v) {
        sp(c).edit().putInt("sendKey", v).apply();
    }

    private Prefs() { }
}
