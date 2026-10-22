package com.phonemic;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 核心：发现电脑 + 采集音频 + 发送。
 *
 * 单例，Activity 和前台服务共用同一个实例（同进程，静态最省事）。
 * 对外只暴露很少的几个方法，状态变化通过 Listener 通知。
 */
public final class Core {

    private static final String TAG = "phoneMic";

    public static final int ST_IDLE = 0;        // 没启动
    public static final int ST_SEARCHING = 1;   // 正在找电脑
    public static final int ST_CONNECTED = 2;   // 已连上，待命
    public static final int ST_TALKING = 3;     // 正在说话

    public interface Listener {
        void onState(int state, String pcName, String pcIp);
        void onLevel(float level);
    }

    private static Core instance;

    public static synchronized Core get() {
        if (instance == null) instance = new Core();
        return instance;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();

    private Context ctx;
    private DatagramSocket sock;
    private InetAddress pcAddr;
    private volatile String pcName = "";
    private volatile String pcIp = "";
    private volatile int state = ST_IDLE;
    private volatile boolean talking = false;
    /** 用户点了「开始」但当时还没连上电脑 —— 先记下来，连上后自动生效 */
    private volatile boolean wantTalk = false;
    private volatile boolean running = false;
    private int mode = Proto.MODE_HOLD;
    private int seq = 0;

    private Thread audioThread;
    private Thread netThread;
    private AudioRecord rec;
    private long lastStateNotify = 0;
    private volatile long lastAckAt = 0;

    private Core() { }

    // ==================== 生命周期 ====================

    public void start(Context context) {
        if (running) return;
        ctx = context.getApplicationContext();
        mode = Prefs.getMode(ctx);
        try {
            sock = new DatagramSocket();
            sock.setBroadcast(true);
        } catch (Exception e) {
            Log.e(TAG, "建 socket 失败", e);
            return;
        }
        running = true;
        startNet();
        startAudio();
        setState(ST_SEARCHING);
    }

    public void stop() {
        running = false;
        talking = false;
        wantTalk = false;
        releaseRecorder();
        try { if (sock != null) sock.close(); } catch (Exception ignored) { }
        sock = null;
        pcAddr = null;
        pcIp = "";
        pcName = "";
        if (audioThread != null) { audioThread.interrupt(); audioThread = null; }
        if (netThread != null) { netThread.interrupt(); netThread = null; }
        setState(ST_IDLE);
    }

    /** 用户点「重新搜索」，清掉当前连接重新找 */
    public void research() {
        pcAddr = null;
        pcIp = "";
        pcName = "";
        if (running) setState(ST_SEARCHING);
    }

    // ==================== 对外状态 ====================

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
        l.onState(state, pcName, pcIp);
        l.onLevel(0f);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public int getState() { return state; }

    public boolean isTalking() { return talking; }

    public boolean isRunning() { return running; }

    public String getPcIp() { return pcIp; }

    public String getPcName() { return pcName; }

    public int getMode() { return mode; }

    public void setMode(int m) {
        mode = m;
        if (ctx != null) Prefs.setMode(ctx, m);
    }

    /**
     * 开始 / 停止说话。
     *
     * 没连上电脑时**不再直接丢弃**：把意图记下来（wantTalk），
     * 等连上的那一刻自动开始。否则用户点「开始」没反应，以为程序坏了。
     */
    public void setTalking(boolean on) {
        wantTalk = on;
        if (pcAddr == null) return;
        if (talking == on) return;
        talking = on;
        if (on) {
            sendCtrl(Proto.T_PTT_DOWN);
        } else {
            releaseRecorder();
            sendCtrl(Proto.T_PTT_UP);
            postLevel(0f);
        }
        setState(on ? ST_TALKING : ST_CONNECTED);
    }

    public boolean isWantTalk() { return wantTalk; }

    // ==================== 设备发现 ====================

    /**
     * 网络主循环：没连上就广播找电脑；连上后每秒发心跳并等待电脑应答。
     * 3 秒收不到应答就判定电脑掉线，回到搜索状态 —— 否则电脑关了手机上还显示「已连接」。
     */
    private void startNet() {
        netThread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] req = new byte[]{Proto.T_DISCOVER};
                byte[] buf = new byte[256];
                long lastDiscover = 0, lastHb = 0;

                while (running) {
                    try {
                        long now = System.currentTimeMillis();

                        if (pcAddr == null) {
                            if (now - lastDiscover > 1200) {
                                lastDiscover = now;
                                try {
                                    sendTo(req, req.length, InetAddress.getByName("255.255.255.255"));
                                    InetAddress sub = subnetBroadcast();
                                    if (sub != null) sendTo(req, req.length, sub);
                                } catch (Exception ignored) { }
                            }
                        } else {
                            if (now - lastHb > 1000) {
                                lastHb = now;
                                sendHeartbeat();
                            }
                            // 电脑 6 秒没应答才判定掉线。原来 3 秒太激进，
                            // 电脑那边一忙（比如正在开音频流）就会误判断线，导致反复重连。
                            if (lastAckAt > 0 && now - lastAckAt > 6000) {
                                Log.i(TAG, "电脑 6 秒没应答，判定掉线");
                                dropConnection();
                                continue;
                            }
                        }

                        DatagramSocket s = sock;
                        if (s == null) { sleep(100); continue; }
                        s.setSoTimeout(200);
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        s.receive(p);
                        int n = p.getLength();

                        if (n >= 2 && buf[0] == Proto.T_DISCOVER_ACK && pcAddr == null) {
                            int len = Math.min(buf[1] & 0xFF, n - 2);
                            pcName = new String(buf, 2, len, "UTF-8");
                            pcAddr = p.getAddress();
                            pcIp = pcAddr.getHostAddress();
                            lastAckAt = System.currentTimeMillis();
                            Log.i(TAG, "找到电脑 " + pcName + " @ " + pcIp);
                            if (ctx != null) Prefs.setLastIp(ctx, pcIp);
                            setState(ST_CONNECTED);

                            // 之前点过「开始」但当时没连上（或断线前正在说）→ 现在补上
                            if (wantTalk) {
                                talking = true;
                                sendCtrl(Proto.T_PTT_DOWN);
                                setState(ST_TALKING);
                            }
                        } else if (n >= 1 && buf[0] == Proto.T_ACK) {
                            lastAckAt = System.currentTimeMillis();
                        }
                    } catch (java.net.SocketTimeoutException te) {
                        // 正常，继续循环
                    } catch (Exception e) {
                        if (!running) break;
                        sleep(100);
                    }
                }
            }
        }, "net");
        netThread.start();
    }

    /** 断开当前电脑，回到搜索状态 */
    private void dropConnection() {
        boolean was = talking;
        talking = false;
        if (was) releaseRecorder();
        pcAddr = null;
        pcIp = "";
        pcName = "";
        lastAckAt = 0;
        setState(ST_SEARCHING);
        postLevel(0f);
    }

    /** 由本机 IP 推一个 /24 广播地址（很多路由器不放行 255.255.255.255） */
    private InetAddress subnetBroadcast() {
        try {
            String ip = localIp();
            if (ip == null) return null;
            int dot = ip.lastIndexOf('.');
            if (dot < 0) return null;
            return InetAddress.getByName(ip.substring(0, dot) + ".255");
        } catch (Exception e) {
            return null;
        }
    }

    static String localIp() {
        try {
            for (java.util.Enumeration<java.net.NetworkInterface> e =
                 java.net.NetworkInterface.getNetworkInterfaces(); e.hasMoreElements(); ) {
                java.net.NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.util.Enumeration<InetAddress> a = ni.getInetAddresses(); a.hasMoreElements(); ) {
                    InetAddress addr = a.nextElement();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        String s = addr.getHostAddress();
                        if (s != null && !s.startsWith("169.254.")) return s;
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ==================== 音频采集 ====================

    private void startAudio() {
        audioThread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[Proto.FRAME_BYTES];
                while (running) {
                    try {
                        if (pcAddr == null) { sleep(150); continue; }
                        if (talking) {
                            if (!ensureRecorder()) { sleep(120); continue; }
                            int n = rec.read(buf, 0, buf.length);
                            if (n > 0) {
                                sendAudio(buf, n);
                                postLevel(levelOf(buf, n));
                            }
                        } else {
                            releaseRecorder();
                            sleep(60);
                        }
                    } catch (Exception e) {
                        // 读音频偶发错误（设备被抢占等），记一下继续跑，别中断整条链路
                        Log.w(TAG, "音频循环异常", e);
                        sleep(60);
                    }
                }
                releaseRecorder();
            }
        }, "audio");
        audioThread.start();
    }

    private boolean ensureRecorder() {
        if (rec != null) return true;
        int min = AudioRecord.getMinBufferSize(Proto.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            Log.e(TAG, "设备不支持 48k 单声道录音");
            return false;
        }
        try {
            AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.MIC, Proto.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, Proto.FRAME_BYTES * 4));
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                return false;
            }
            r.startRecording();
            rec = r;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "开麦失败", e);
            return false;
        }
    }

    /** 松开就真的关掉麦克风（状态栏录音图标会消失），这是隐私要求 */
    private void releaseRecorder() {
        AudioRecord r = rec;
        rec = null;
        if (r == null) return;
        try { r.stop(); } catch (Exception ignored) { }
        try { r.release(); } catch (Exception ignored) { }
    }

    // ==================== 发送 ====================

    private void sendAudio(byte[] pcm, int len) {
        try {
            byte[] pkt = new byte[3 + len];
            pkt[0] = Proto.T_AUDIO;
            pkt[1] = (byte) ((seq >> 8) & 0xFF);
            pkt[2] = (byte) (seq & 0xFF);
            System.arraycopy(pcm, 0, pkt, 3, len);
            seq = (seq + 1) & 0xFFFF;
            sendTo(pkt, pkt.length, pcAddr);
        } catch (Exception ignored) {
            // UDP 丢一两个包无所谓，别中断循环
        }
    }

    private void sendHeartbeat() {
        try {
            byte[] pkt = new byte[]{Proto.T_HEARTBEAT, (byte) mode};
            sendTo(pkt, pkt.length, pcAddr);
        } catch (Exception ignored) { }
    }

    private void sendCtrl(byte type) {
        try {
            byte[] pkt = new byte[]{type};
            sendTo(pkt, pkt.length, pcAddr);
        } catch (Exception ignored) { }
    }

    private void sendTo(byte[] data, int len, InetAddress addr) throws Exception {
        DatagramSocket s = sock;
        if (s == null || addr == null) return;
        s.send(new DatagramPacket(data, len, addr, Proto.PORT));
    }

    // ==================== 电平 ====================

    /** 计算 RMS 并放大，让电平条看起来明显一些 */
    private static float levelOf(byte[] pcm, int len) {
        long sum = 0;
        int n = len / 2;
        for (int i = 0; i < n; i++) {
            int lo = pcm[i * 2] & 0xFF;
            int hi = pcm[i * 2 + 1];
            int v = (hi << 8) | lo;          // 小端 16bit
            sum += (long) v * v;
        }
        if (n == 0) return 0f;
        float rms = (float) Math.sqrt((double) sum / n);
        float lv = rms / 32768f * 3f;
        return lv > 1f ? 1f : lv;
    }

    private void postLevel(final float level) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) l.onLevel(level);
            }
        });
    }

    // ==================== 状态通知 ====================

    private void setState(int s) {
        if (state == s) return;
        state = s;
        lastStateNotify = System.currentTimeMillis();
        ui.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) l.onState(state, pcName, pcIp);
            }
        });
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
