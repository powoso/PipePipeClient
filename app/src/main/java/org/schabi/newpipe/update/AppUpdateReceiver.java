package org.schabi.newpipe.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

public final class AppUpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(@NonNull final Context context, final Intent intent) {
        if (intent == null) {
            return;
        }
        AppUpdateManager.handleBroadcast(context, intent);
    }
}
