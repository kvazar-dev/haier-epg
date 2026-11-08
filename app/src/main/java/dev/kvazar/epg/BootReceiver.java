package dev.kvazar.epg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Alarms do not survive a reboot or a clock change; arm the pending reminders again. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        Reminders.rescheduleAll(ctx);
    }
}
