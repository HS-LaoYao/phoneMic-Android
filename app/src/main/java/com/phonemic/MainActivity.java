package com.phonemic;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 主界面（单页）。
 * 只用系统控件，不引 AndroidX / Compose，APK 才能压到 100KB 以内。
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

    private static final int REQ_PERM = 100;

    private LinearLayout banner;
    private TextView tvBanner, tvBannerSub, tvPc, tvModeHint;
    private Button btnTalk;
    private ProgressBar level;
    private RadioButton rbHold, rbToggle;
    private RadioGroup rgMode;

    private boolean startingService = false;

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
        level = findViewById(R.id.level);
        rbHold = findViewById(R.id.rbHold);
        rbToggle = findViewById(R.id.rbToggle);
        rgMode = findViewById(R.id.rgMode);
        tvModeHint = findViewById(R.id.tvModeHint);

        setupMode();
        setupTalkButton();

        findViewById(R.id.btnSearch).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Core.get().research();
                toast("正在重新搜索电脑…");
            }
        });

        findViewById(R.id.btnStop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Core.get().setTalking(false);
                MicService.stop(MainActivity.this);
                toast("已停止");
            }
        });

        requestPermsIfNeeded();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Core.get().addListener(this);
        if (hasMic()) startServiceOnce();
        refreshModeHint();
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
        if (startingService) return;
        startingService = true;
        MicService.start(this);
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
                applyState(Core.get().getState());
            }
        });
    }

    private void refreshModeHint() {
        tvModeHint.setText(Core.get().getMode() == Proto.MODE_HOLD
                ? "按住按钮期间录音，松手立刻停止（麦克风真关闭）"
                : "点一下开始持续录音，再点一下停止");
    }

    // ==================== 说话按钮 ====================

    private void setupTalkButton() {
        // 长按模式：靠触摸事件
        btnTalk.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (Core.get().getMode() != Proto.MODE_HOLD) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (!connected()) { toast("还没连上电脑"); return true; }
                        Core.get().setTalking(true);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        Core.get().setTalking(false);
                        return true;
                }
                return false;
            }
        });

        // 点按模式：靠点击事件
        btnTalk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Core.get().getMode() != Proto.MODE_TOGGLE) return;
                if (!connected() && !Core.get().isTalking()) { toast("还没连上电脑"); return; }
                Core.get().setTalking(!Core.get().isTalking());
            }
        });
    }

    private boolean connected() {
        return Core.get().getState() == Core.ST_CONNECTED
                || Core.get().getState() == Core.ST_TALKING;
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

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
