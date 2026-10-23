package com.phonemic;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 检查更新 + 下载新安装包。
 *
 * 版本信息统一从**码云**的发行版读（Gitee 是主仓库，GitHub 只是 CI 镜像）。
 * 下载交给系统 DownloadManager，装完自动弹安装界面 ——
 * 这样不用引 AndroidX 的 FileProvider，也能在 Android 7+ 上拿到合法的 content:// 权限。
 */
public final class Updater {

    private static final String API =
            "https://gitee.com/api/v5/repos/heishanyao_admin/phoneMic-Android/releases/latest";

    private static final Handler ui = new Handler(Looper.getMainLooper());

    public interface Callback {
        /** version 为远端版本号（已去掉开头的 v）；url 为空表示没找到安装包 */
        void onDone(String version, String url, String err);
    }

    private Updater() { }

    public static void check(final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String ver = "", url = "", err = null;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestProperty("User-Agent", "phoneMic");
                    String body = readAll(c.getInputStream());
                    c.disconnect();

                    JSONObject jo = new JSONObject(body);
                    ver = jo.optString("tag_name", "");
                    JSONArray assets = jo.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.getJSONObject(i);
                            if (a.optString("name", "").endsWith(".apk")) {
                                url = a.optString("browser_download_url", "");
                                if (url.isEmpty()) url = a.optString("download_url", "");
                                break;
                            }
                        }
                    }
                    if (ver.isEmpty()) err = "没读到发行版信息";
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String v = ver, u = url, e2 = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() { cb.onDone(v, u, e2); }
                });
            }
        }, "updater").start();
    }

    /** 版本号比较：1.0.10 > 1.0.2（按段拆开比数字，别用字符串比） */
    public static boolean isNewer(String remote, String current) {
        String[] a = split(remote), b = split(current);
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? num(a[i]) : 0;
            int y = i < b.length ? num(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    /** 下完自动弹安装界面 */
    public static void download(Context ctx, String url, String version) {
        final Context app = ctx.getApplicationContext();
        DownloadManager dm;
        try {
            dm = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
        } catch (Exception e) {
            return;
        }
        if (dm == null) return;

        DownloadManager.Request rq = new DownloadManager.Request(Uri.parse(url));
        rq.setTitle("phoneMic " + version);
        rq.setMimeType("application/vnd.android.package-archive");
        rq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        rq.setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS,
                "phoneMic-" + version + ".apk");

        final long id;
        try {
            id = dm.enqueue(rq);
        } catch (Exception e) {
            return;
        }

        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return;
                try { c.unregisterReceiver(this); } catch (Exception ignored) { }
                try {
                    Uri uri = ((DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE))
                            .getUriForDownloadedFile(id);
                    if (uri == null) return;
                    Intent it = new Intent(Intent.ACTION_VIEW);
                    it.setDataAndType(uri, "application/vnd.android.package-archive");
                    it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(it);
                } catch (Exception ignored) { }
            }
        }, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }

    private static String[] split(String v) {
        String s = v == null ? "" : v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        return s.split("\\.");
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }
}
