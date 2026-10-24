package com.phonemic;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
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
        /** 电脑端的路由模式变了（本地或远程切换都会通知，手机界面跟着同步） */
        void onRouteMode(int routeMode);
        /**
         * 手机端发起的模式切换有结果了：ok=true 电脑已确认，ok=false 等了太久、已放弃。
         * 只给界面用（弹提示 / 把选项还原），前台服务不用管。
         */
        void onRouteResult(int routeMode, boolean ok);
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
    private int sendKeySeq = 0;

    private Thread audioThread;
    private Thread netThread;
    private AudioRecord rec;

    // 播放电脑传来的声音（模式③：手机当耳机）
    private AudioTrack track;
    private Thread playThread;
    private final ArrayBlockingQueue<byte[]> playQ = new ArrayBlockingQueue<byte[]>(30);

    /** 电脑端当前的路由模式（由心跳应答带回，电脑是唯一事实来源） */
    private volatile int routeMode = Proto.ROUTE_MIC;

    /** 手机端点了某个模式、还没被电脑确认（0 = 没有待生效的请求） */
    private volatile int pendingRoute = 0;
    private int pendingTries = 0;
    /** 待生效请求最多跟着心跳重发这么多次（约 6 秒），还没确认就放弃并还原界面 */
    private static final int PENDING_MAX_TRIES = 6;

    private volatile long lastAckAt = 0;
    /** 上次真的把心跳发出去的时刻：用来区分「电脑掉线」和「我们自己被系统冻住」 */
    private volatile long lastHbSentAt = 0;
    /** 连续这么久没收到应答才判掉线（原来是 6 秒，息屏被冻几秒就会误判） */
    private static final long DROP_MS = 10000;

    // 录音时长：给界面显示「语音 3.2″」那种气泡用
    private volatile long talkStartMs = 0;
    private volatile long lastTalkMs = 0;

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
        startPlayback();
        setState(ST_SEARCHING);
    }

    public void stop() {
        running = false;
        talking = false;
        wantTalk = false;
        releaseRecorder();
        stopPlayback();
        try { if (sock != null) sock.close(); } catch (Exception ignored) { }
        sock = null;
        pcAddr = null;
        pcIp = "";
        pcName = "";
        pendingRoute = 0;
        pendingTries = 0;
        lastAckAt = 0;
        lastHbSentAt = 0;
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
        l.onRouteMode(routeMode);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public int getState() { return state; }

    public boolean isTalking() { return talking; }

    public boolean isRunning() { return running; }

    /** 已经找到电脑了吗（比 state 更可靠，state 会在重连时抖动） */
    public boolean hasPeer() { return pcAddr != null; }

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
            talkStartMs = System.currentTimeMillis();
            sendCtrl(Proto.T_PTT_DOWN);
        } else {
            if (talkStartMs > 0) lastTalkMs = System.currentTimeMillis() - talkStartMs;
            talkStartMs = 0;
            releaseRecorder();
            sendCtrl(Proto.T_PTT_UP);
            postLevel(0f);
        }
        setState(on ? ST_TALKING : ST_CONNECTED);
    }

    /** 这一轮已经录了多久（毫秒）；没在录就返回上一次的时长 */
    public long getTalkMillis() {
        if (talking && talkStartMs > 0) return System.currentTimeMillis() - talkStartMs;
        return lastTalkMs;
    }

    public boolean isRecordingNow() { return talking; }

    public void clearTalkDuration() { lastTalkMs = 0; }

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
                // 收包缓冲必须比「一帧下行音频 + 包头」大：963 字节。
                // 开小了 UDP 会把整包截断 —— 表现就是手机里只有滋滋的电流声、听不出音乐。
                byte[] buf = new byte[Proto.AUDIO_HEADER + Proto.FRAME_BYTES + 64];
                long lastDiscover = 0, lastHb = 0, lastPtt = 0;

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
                                lastHbSentAt = now;
                                // 有待生效的模式就搭着心跳一起重发，直到电脑的应答确认。
                                // 单包 UDP 会丢，而「切换模式」是一次性指令，丢了就永远不生效。
                                if (pendingRoute != 0) {
                                    sendModeSet(pendingRoute);
                                    if (++pendingTries >= PENDING_MAX_TRIES) failPending();
                                }
                            }
                            // 说话期间反复重发「开始说话」。
                            // 单包 UDP 一旦丢了，电脑就不知道该按键 —— 这是之前按键不触发的根因。
                            // 电脑端按键是幂等的，重复收到不会出问题。
                            if (talking && now - lastPtt > 400) {
                                lastPtt = now;
                                sendCtrl(Proto.T_PTT_DOWN);
                            }
                            // 掉线判定：必须「我们一直在正常发心跳」＋「电脑 10 秒没应答」才算真掉线。
                            // 息屏之后系统会把进程冻住几秒（国产 ROM 的省电策略更狠），
                            // 冻醒那一刻心跳和应答的时间戳是一起变旧的 —— 那不是电脑掉线。
                            // 只看「多久没收到应答」就会把这种冻醒误判成掉线，
                            // 表现就是每隔二三十秒自己把自己踢下线、重新搜一次电脑。
                            if (lastAckAt > 0 && now - lastAckAt > DROP_MS && now - lastHbSentAt < 2000) {
                                Log.i(TAG, "电脑 10 秒没应答，判定掉线");
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
                            setState(ST_CONNECTED);

                            // 之前点过「开始」但当时没连上（或断线前正在说）→ 现在补上
                            if (wantTalk) {
                                talking = true;
                                talkStartMs = System.currentTimeMillis();
                                sendCtrl(Proto.T_PTT_DOWN);
                                setState(ST_TALKING);
                            }
                            // 没连上时点过模式切换 → 现在补发，别让用户白点一下
                            if (pendingRoute != 0) {
                                pendingTries = 0;
                                sendModeSet(pendingRoute);
                            }
                        } else if (n >= 1 && buf[0] == Proto.T_ACK) {
                            lastAckAt = System.currentTimeMillis();
                            // 应答第二字节是电脑端当前的路由模式：跟着它同步界面，
                            // 这样在电脑上手切模式，手机这边也会跟着变
                            if (n >= 2) onAckMode(buf[1] & 0xFF);
                        } else if (n > Proto.AUDIO_HEADER && buf[0] == Proto.T_AUDIO_DOWN) {
                            enqueueDown(buf, n);
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

    // ==================== 发送键（让电脑按回车 / Shift+回车）====================

    /**
     * 点「发送」：让电脑按一次发送键，把语音转出来的文字发出去。
     *
     * 这里连发 3 次是**必须的** —— 单包 UDP 会丢（按键不触发那个问题就是这么来的），
     * 但「发送」不能靠周期性重发兜底，否则会连发好几条消息。
     * 所以三个包带**同一个 seq**，电脑端按 seq 去重：只会按一次。
     */
    public boolean sendKeyNow() {
        if (pcAddr == null) return false;
        final int s = (++sendKeySeq) & 0xFFFF;
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 3; i++) {
                    sendSendKey(s);
                    if (i < 2) sleep(40);
                }
            }
        }, "send-key").start();
        return true;
    }

    private void sendSendKey(int s) {
        try {
            int key = ctx != null ? Prefs.getSendKey(ctx) : Proto.SEND_ENTER;
            byte[] pkt = new byte[]{
                    Proto.T_SEND_KEY,
                    (byte) ((s >> 8) & 0xFF), (byte) (s & 0xFF),
                    (byte) key};
            sendTo(pkt, pkt.length, pcAddr);
        } catch (Exception ignored) { }
    }

    private void sendTo(byte[] data, int len, InetAddress addr) throws Exception {
        DatagramSocket s = sock;
        if (s == null || addr == null) return;
        s.send(new DatagramPacket(data, len, addr, Proto.PORT));
    }

    // ==================== 播放电脑的声音（模式③）====================

    /** 手机端显示用的路由模式（来自电脑的心跳应答） */
    public int getRouteMode() { return routeMode; }

    /**
     * 手机端请求切换电脑的路由模式（0x14）。
     *
     * 两个可靠性设计：
     * 1. 单包 UDP 会丢，所以把请求记成 pendingRoute，搭着每秒的心跳重发，
     *    直到心跳应答带回同样的模式才算确认（电脑端 SetMode 幂等，重复发无副作用）；
     * 2. 没连上电脑时也不丢用户的操作 —— 记下来，等连上的那一刻自动补发。
     *
     * @return true 已经发出去了；false 还没连上（请求已记下，连上后自动生效）
     */
    public boolean requestRouteMode(int m) {
        if (m < 1 || m > 3) return false;
        pendingRoute = m;
        pendingTries = 0;
        if (pcAddr == null) return false;
        sendModeSet(m);
        return true;
    }

    private void sendModeSet(int m) {
        try {
            sendTo(new byte[]{Proto.T_MODE_SET, (byte) m}, 2, pcAddr);
        } catch (Exception ignored) { }
    }

    /** 心跳应答带回的模式：要么确认了手机的请求，要么是电脑那边自己切的 */
    private void onAckMode(int m) {
        if (m < 1 || m > 3) return;
        if (pendingRoute != 0) {
            // 请求还在路上：先记下电脑的真实模式，但不通知界面 ——
            // 否则用户刚点完就被打回旧值，看着像「点了没反应」
            routeMode = m;
            if (m == pendingRoute) {
                pendingRoute = 0;
                pendingTries = 0;
                postRouteResult(m, true);
            }
            return;
        }
        updateRouteMode(m);
    }

    /** 重发了好几轮电脑都没认，放弃并把结果告诉界面（让它把选项还原） */
    private void failPending() {
        final int want = pendingRoute;
        pendingRoute = 0;
        pendingTries = 0;
        postRouteResult(want, false);
    }

    private void postRouteResult(final int m, final boolean ok) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) l.onRouteResult(m, ok);
            }
        });
    }

    private void updateRouteMode(int m) {
        if (m < 1 || m > 3 || m == routeMode) return;
        routeMode = m;
        ui.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) l.onRouteMode(routeMode);
            }
        });
    }

    private void enqueueDown(byte[] buf, int n) {
        int len = n - Proto.AUDIO_HEADER;
        if (len <= 0 || len > Proto.FRAME_BYTES) return;   // 长度不对就整包丢掉，别把噪音混进去
        byte[] pcm = new byte[len];
        System.arraycopy(buf, Proto.AUDIO_HEADER, pcm, 0, len);
        if (!playQ.offer(pcm)) {          // 满了就丢最旧的，宁可断一下也不要越积越多
            playQ.poll();
            playQ.offer(pcm);
        }
    }

    private void startPlayback() {
        if (playThread != null) return;
        int min = AudioTrack.getMinBufferSize(Proto.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) { Log.e(TAG, "设备不支持 48k 播放"); return; }
        try {
            track = new AudioTrack(AudioManager.STREAM_MUSIC, Proto.SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, Proto.FRAME_BYTES * 8), AudioTrack.MODE_STREAM);
            track.play();
        } catch (Exception e) {
            Log.e(TAG, "创建播放器失败", e);
            track = null;
            return;
        }

        playThread = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean primed = false;
                while (running) {
                    try {
                        byte[] data = playQ.take();

                        // UDP 到达时间有抖动，收到一帧就立刻播会一卡一卡。
                        // 先攒几帧再起播；队列被播空了就重新攒。
                        if (!primed) {
                            long t0 = System.currentTimeMillis();
                            while (playQ.size() < 5 && running
                                    && System.currentTimeMillis() - t0 < 500) sleep(2);
                            primed = true;
                        }

                        AudioTrack t = track;
                        if (t != null) t.write(data, 0, data.length);
                        if (playQ.isEmpty()) primed = false;
                    } catch (InterruptedException ie) {
                        break;
                    } catch (Exception ignored) { }
                }
            }
        }, "playback");
        playThread.start();
    }

    private void stopPlayback() {
        playQ.clear();
        if (playThread != null) { playThread.interrupt(); playThread = null; }
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try { t.stop(); } catch (Exception ignored) { }
            try { t.release(); } catch (Exception ignored) { }
        }
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
