package com.phonemic;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 通知栏上的「停止」按钮。 */
public class StopReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Core.get().setTalking(false);
        MicService.stop(context);
    }
}
