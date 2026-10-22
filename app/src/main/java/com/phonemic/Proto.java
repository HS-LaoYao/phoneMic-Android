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
    public static final byte T_DISCOVER = 0x20;     // 广播找电脑
    public static final byte T_DISCOVER_ACK = 0x21; // [21][nameLen][name]

    public static final int SAMPLE_RATE = 48000;
    public static final int FRAME_BYTES = 960;      // 10ms 单声道 16bit

    public static final int MODE_HOLD = 0;          // 长按说话
    public static final int MODE_TOGGLE = 1;        // 点按常开

    private Proto() { }
}
