package com.phonemic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 前台服务：让 App 切到后台或息屏后依然能收音频、发音频。
 * 没有它，点按常开模式一锁屏就会被系统掐掉。
 */
public class MicService extends Service implements Core.Listener {

    private static final String CHANNEL = "phonemic";
    private static final int NOTI_ID = 1001;

    public static void start(Context c) {
        Intent i = new Intent(c, MicService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, MicService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "麦克风服务",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("保持与电脑的连接");
            nm.createNotificationChannel(ch);
        }
        Core.get().addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat(buildNotification("正在启动…"));
        Core.get().start(this);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Core.get().removeListener(this);
        Core.get().stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ==================== 状态回调 ====================

    @Override
    public void onState(int state, String pcName, String pcIp) {
        String text;
        switch (state) {
            case Core.ST_TALKING: text = "● 正在录音并发送"; break;
            case Core.ST_CONNECTED: text = "已连接 " + pcName + "，待命"; break;
            case Core.ST_SEARCHING: text = "正在搜索电脑…"; break;
            default: text = "已停止"; break;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTI_ID, buildNotification(text));
    }

    @Override
    public void onLevel(float level) { }

    // ==================== 通知 ====================

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTI_ID, n);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, immutable());

        Intent stop = new Intent(this, StopReceiver.class);
        PendingIntent stopPi = PendingIntent.getBroadcast(this, 1, stop, immutable());

        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("phoneMic")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止", stopPi).build())
                .build();
    }

    private static int immutable() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0;
    }
}
