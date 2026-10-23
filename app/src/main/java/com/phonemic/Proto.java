package com.phonemic;

/**
 * phoneMic 协议常量（与 Windows 端一致）。
 *
 * 全部走 UDP 47810：电脑监听，手机主动发。
 * 第一个字节是类型，10ms 一帧裸 PCM（48kHz/16bit/单声道，960 字节）。
 */
public final class Proto {
    public static final int PORT = 47810;

    public static final byte T_AUDIO = 0x01;        // [01][seq:u16][PCM]
    public static final byte T_PTT_DOWN = 0x10;     // 说话开始
    public static final byte T_PTT_UP = 0x11;       // 说话结束
    public static final byte T_HEARTBEAT = 0x12;    // [12][mode] 每秒
    public static final byte T_ACK = 0x22;          // 电脑对心跳的应答 [22]
    public static final byte T_AUDIO_DOWN = 0x02;   // 电脑→手机 的音频（手机当耳机时）
    public static final byte T_DISCOVER = 0x20;     // 广播找电脑
    public static final byte T_DISCOVER_ACK = 0x21; // [21][nameLen][name]

    /**
     * 「发送键」：[13][seq:u16][key:u8]
     * 手机上点「发送」，电脑上按一次这里指定的键（比如回车把语音转的文字发出去）。
     * 带 seq 是因为要重发防丢包，电脑端按 seq 去重，绝不会多按一次。
     */
    public static final byte T_SEND_KEY = 0x13;
    public static final int SEND_ENTER = 0;         // 回车
    public static final int SEND_SHIFT_ENTER = 1;   // Shift + 回车

    public static final int SAMPLE_RATE = 48000;
    public static final int FRAME_BYTES = 960;      // 10ms 单声道 16bit
    public static final int AUDIO_HEADER = 3;       // 音频包头：类型1 + 序号2

    public static final int MODE_HOLD = 0;          // 长按说话
    public static final int MODE_TOGGLE = 1;        // 点按常开

    private Proto() { }
}
