package com.phonemic;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * 主界面（单页）。
 * 只用系统控件，不引 AndroidX / Compose，APK 才能压到 100KB 以内。
 *
 * 布局分三块：顶栏（标题 + 三点菜单）／中间可滚动的设置／底部固定的「聊天输入框」。
 * 底部那条是刻意做成微信样子的 —— 左边长按说话、松手显示「语音 3.2″」，右边发送。
 */
public class MainActivity extends Activity implements Core.Listener {

    // 配色
    private static final int C_BG = 0xFF12161A;
    private static final int C_TEXT = 0xFFE6EDF3;
    private static final int C_SUB = 0xFF8B98A5;
    private static final int C_IDLE = 0xFF232B33;
    private static final int C_GREEN = 0xFF1E7F4B;
    private static final int C_RED = 0xFFC0392B;
    private static final int C_AMBER = 0xFF8A6D1F;
    private static final int C_BTN_IDLE = 0xFF2A333D;
    private static final int C_CARD = 0xFF1B222A;
    private static final int C_ACCENT = 0xFF3FA7FF;

    private static final int REQ_PERM = 100;

    private LinearLayout banner;
    private TextView tvBanner, tvBannerSub, tvPc, tvModeHint, tvSendKey;
    private Button btnTalk, btnVoice, btnSend;
    private ProgressBar level;
    private RadioButton rbHold, rbToggle;
    private RadioGroup rgMode;

    /** 用户希望服务是开着的吗（点过停止就是 false，回到前台时不再自动拉起） */
    private boolean serviceWanted = true;

    /** 松手后是不是要显示「语音 x.x″」气泡 */
    private boolean showVoiceBubble = false;

    private final Handler ui = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_BG);

        banner = findViewById(R.id.banner);
        tvBanner = findViewById(R.id.tvBanner);
        tvBannerSub = findViewById(R.id.tvBannerSub);
        tvPc = findViewById(R.id.tvPc);
        btnTalk = findViewById(R.id.btnTalk);
        btnVoice = findViewById(R.id.btnVoice);
        btnSend = findViewById(R.id.btnSend);
        tvSendKey = findViewById(R.id.tvSendKey);
        level = findViewById(R.id.level);
        rbHold = findViewById(R.id.rbHold);
        rbToggle = findViewById(R.id.rbToggle);
        rgMode = findViewById(R.id.rgMode);
        tvModeHint = findViewById(R.id.tvModeHint);

        setupMode();
        setupTalkButton();
        setupVoiceBar();
        setupSend();
        setupMenu();

        // 「播放电脑的声音」开关 —— 不绑的话它就只是个摆设，改了也不生效
        android.widget.CheckBox chk = findViewById(R.id.chkRemote);
        chk.setChecked(Prefs.getPlayRemote(this));
        chk.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                Core.get().setPlayRemote(on);
            }
        });

        findViewById(R.id.btnSearch).setBackground(round(C_CARD, 10));
        findViewById(R.id.btnSearch).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Core.get().research();
                toast("正在重新搜索电脑…");
            }
        });

        requestPermsIfNeeded();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Core.get().addListener(this);
        if (hasMic() && serviceWanted) startServiceOnce();
        refreshModeHint();
        applyState(Core.get().getState());
        refreshSendKeyLabel();
    }

    @Override
    protected void onStop() {
        Core.get().removeListener(this);
        super.onStop();
    }

    // ==================== 权限 ====================

    private boolean hasMic() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermsIfNeeded() {
        if (hasMic()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS}, REQ_PERM);
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_PERM) return;
        if (!hasMic()) {
            toast("没有麦克风权限，无法工作。请到系统设置里允许「麦克风」。");
            return;
        }
        startServiceOnce();
        Core.get().addListener(this);
    }

    private void startServiceOnce() {
        // 用 Core 的真实运行状态判断，而不是一个一次性的布尔：
        // 否则用户点过「停止服务」后再回到 App，服务就再也起不来了。
        if (!Core.get().isRunning()) MicService.start(this);
    }

    // ==================== 模式 ====================

    private void setupMode() {
        boolean hold = Core.get().getMode() == Proto.MODE_HOLD;
        rbHold.setChecked(hold);
        rbToggle.setChecked(!hold);
        rgMode.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                int m = checkedId == R.id.rbHold ? Proto.MODE_HOLD : Proto.MODE_TOGGLE;
                if (Core.get().isTalking()) Core.get().setTalking(false);  // 切模式先停
                Core.get().setMode(m);
                refreshModeHint();
                refreshVoiceBar();
                applyState(Core.get().getState());
            }
        });
    }

    private void refreshModeHint() {
        tvModeHint.setText(Core.get().getMode() == Proto.MODE_HOLD
                ? "按住按钮期间录音，松手立刻停止（麦克风真关闭）"
                : "点一下开始持续录音，再点一下停止");
    }

    // ==================== 底部输入条：左边长按说话 ====================

    private void setupVoiceBar() {
        btnVoice.setBackground(round(C_CARD, 24));
        attachTalkHandlers(btnVoice);
        refreshVoiceBar();
        ui.postDelayed(ticker, 200);
    }

    /** 录音中实时刷新「录音中 x.x″」 */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshVoiceBar();
            ui.postDelayed(this, 200);
        }
    };

    private void startRecord() {
        showVoiceBubble = false;
        Core.get().clearTalkDuration();
        Core.get().setTalking(true);
        refreshVoiceBar();
    }

    private void stopRecord() {
        Core.get().setTalking(false);
        showVoiceBubble = true;          // 松手后像微信一样留一个「语音 x.x″」气泡
        refreshVoiceBar();
    }

    private void refreshVoiceBar() {
        if (btnVoice == null) return;
        boolean hold = Core.get().getMode() == Proto.MODE_HOLD;

        if (Core.get().isRecordingNow()) {
            btnVoice.setText("录音中 " + secs(Core.get().getTalkMillis()) + "″");
            btnVoice.setTextColor(C_TEXT);
            btnVoice.setBackground(round(C_BTN_IDLE, 24));
        } else if (showVoiceBubble && Core.get().getTalkMillis() > 0) {
            btnVoice.setText("语音 " + secs(Core.get().getTalkMillis()) + "″");
            btnVoice.setTextColor(C_TEXT);
            btnVoice.setBackground(round(C_CARD, 24));
        } else {
            btnVoice.setText(hold ? "按住 说话" : "点一下 开始录音");
            btnVoice.setTextColor(C_SUB);
            btnVoice.setBackground(round(C_CARD, 24));
        }
    }

    /** 长按期间一秒一秒往上走：1″、2″、3″…（不足 1 秒也算 1 秒，别停在 0） */
    private static String secs(long ms) {
        long s = ms / 1000;
        if (s < 1) s = 1;
        return String.valueOf(s);
    }

    // ==================== 底部输入条：右边发送 ====================

    private void setupSend() {
        btnSend.setBackground(round(C_BTN_IDLE, 24));
        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!connected()) { toast("还没连上电脑"); return; }
                if (!Core.get().sendKeyNow()) { toast("还没连上电脑"); return; }
                toast("已发送：" + sendKeyName());
            }
        });

        // 发送键旁边的小箭头：点开选「回车」还是「Shift + 回车」
        TextView caret = findViewById(R.id.btnSendCfg);
        caret.setBackground(round(C_BTN_IDLE, 20));
        caret.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showSendKeyMenu(v); }
        });

        refreshSendKeyLabel();
    }

    private void showSendKeyMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        String[] names = getResources().getStringArray(R.array.send_keys);
        for (int i = 0; i < names.length; i++) pm.getMenu().add(0, i, 0, names[i]);
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                Prefs.setSendKey(MainActivity.this, item.getItemId());
                refreshSendKeyLabel();
                toast("发送键已改为：" + sendKeyName());
                return true;
            }
        });
        pm.show();
    }

    private void refreshSendKeyLabel() {
        if (tvSendKey != null) tvSendKey.setText("发送键：" + sendKeyName());
    }

    private String sendKeyName() {
        String[] names = getResources().getStringArray(R.array.send_keys);
        int i = Prefs.getSendKey(this);
        return i >= 0 && i < names.length ? names[i] : names[0];
    }

    // ==================== 顶栏三点菜单 ====================

    private void setupMenu() {
        findViewById(R.id.btnMenu).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showMenu(v); }
        });
    }

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, serviceWanted ? R.string.menu_stop : R.string.menu_start);
        pm.getMenu().add(0, 2, 0, R.string.menu_update);
        pm.getMenu().add(0, 3, 0, R.string.menu_about);
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1: toggleService(); return true;
                    case 2: checkUpdate(); return true;
                    case 3: showAbout(); return true;
                }
                return false;
            }
        });
        pm.show();
    }

    private void toggleService() {
        if (serviceWanted) {
            serviceWanted = false;
            Core.get().setTalking(false);
            MicService.stop(MainActivity.this);
            toast("已停止");
        } else {
            serviceWanted = true;
            MicService.start(MainActivity.this);
            toast("已启动，正在搜索电脑…");
        }
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_body, BuildConfig.VERSION_NAME))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void checkUpdate() {
        toast(getString(R.string.checking_update));
        Updater.check(new Updater.Callback() {
            @Override
            public void onDone(String version, String url, String err) {
                String cur = BuildConfig.VERSION_NAME;
                if (err != null) { toast(getString(R.string.update_fail, err)); return; }
                if (Updater.isNewer(version, cur)) {
                    toast(getString(R.string.update_found, version));
                    Updater.download(MainActivity.this, url, version);
                } else {
                    toast(getString(R.string.update_none, cur));
                }
            }
        });
    }

    // ==================== 大圆按钮 ====================

    private void setupTalkButton() {
        attachTalkHandlers(btnTalk);
    }

    /** 大圆按钮和输入条左边的长按钮，行为完全一样 —— 同一套触摸/点击逻辑，别写两遍 */
    private void attachTalkHandlers(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent e) {
                if (Core.get().getMode() != Proto.MODE_HOLD) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (!connected()) { toast("还没连上电脑"); return true; }
                        startRecord();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        stopRecord();
                        return true;
                }
                return false;
            }
        });

        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (Core.get().getMode() != Proto.MODE_TOGGLE) return;
                if (!connected() && !Core.get().isTalking()) {
                    Core.get().setTalking(true);
                    toast("还没连上电脑，连上后会自动开始录音");
                    return;
                }
                if (Core.get().isTalking()) stopRecord();
                else startRecord();
            }
        });
    }

    private boolean connected() {
        // 用「有没有找到电脑」判断，不要用 state ——
        // state 在重连时会短暂变成 SEARCHING，那时长按会被误判为「还没连上」而直接忽略。
        return Core.get().hasPeer();
    }

    // ==================== 状态刷新 ====================

    @Override
    public void onState(int state, String pcName, String pcIp) {
        applyState(state);
    }

    @Override
    public void onLevel(float lv) {
        level.setProgress((int) (lv * 100));
    }

    private void applyState(int state) {
        boolean hold = Core.get().getMode() == Proto.MODE_HOLD;
        int color;
        String main, sub;

        switch (state) {
            case Core.ST_TALKING:
                color = C_RED;
                main = "● 正在录音";
                sub = "声音正在发送到 " + (Core.get().getPcName().isEmpty() ? "电脑" : Core.get().getPcName());
                break;
            case Core.ST_CONNECTED:
                color = C_GREEN;
                main = "已连接 · 待命";
                sub = hold ? "按住下面的按钮说话" : "点一下下面的按钮开始说话";
                break;
            case Core.ST_SEARCHING:
                color = C_AMBER;
                main = "正在搜索电脑";
                sub = "确认电脑上的 phoneMic 已打开，且在同一 WiFi";
                break;
            default:
                color = C_IDLE;
                main = "未连接";
                sub = "等待服务启动…";
                break;
        }

        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(16));
        d.setColor(color);
        banner.setBackground(d);

        tvBanner.setText(main);
        tvBannerSub.setText(sub);

        boolean on = state == Core.ST_TALKING;
        GradientDrawable bd = new GradientDrawable();
        bd.setShape(GradientDrawable.OVAL);
        bd.setColor(on ? C_RED : C_BTN_IDLE);
        bd.setStroke((int) dp(2), on ? 0xFFFF8A80 : 0xFF3A4552);
        btnTalk.setBackground(bd);
        btnTalk.setTextColor(0xFFFFFFFF);
        btnTalk.setText(buttonText(state, hold));

        if (!on) level.setProgress(0);

        String ip = Core.get().getPcIp();
        tvPc.setText(ip.isEmpty() ? "未找到" : ip + "   " + Core.get().getPcName());
        tvPc.setTextColor(ip.isEmpty() ? C_SUB : C_TEXT);

        refreshVoiceBar();
    }

    private String buttonText(int state, boolean hold) {
        if (state == Core.ST_TALKING) return hold ? "松手\n停止" : "录音中\n点一下停";
        if (state == Core.ST_CONNECTED) return hold ? "按住\n说话" : "点一下\n开始";
        if (state == Core.ST_SEARCHING) return "搜索中…";
        return "未连接";
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** 圆角矩形背景 */
    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(radiusDp));
        d.setColor(color);
        return d;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
