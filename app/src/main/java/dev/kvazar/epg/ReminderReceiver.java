package dev.kvazar.epg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

/** The alarm fired: take the reminder off the list and put the prompt on screen. */
public final class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        long channelId = intent.getLongExtra(Reminders.EXTRA_CHANNEL, -1);
        long start = intent.getLongExtra(Reminders.EXTRA_START, 0);
        if (channelId < 0) return;
        boolean known = Reminders.keys(ctx).contains(channelId + ":" + start);
        Reminders.remove(ctx, channelId, start);
        if (!known) {
            Log.i(Tuner.TAG, "stale reminder ignored for channel " + channelId);
            return;
        }
        // A set in standby shows nothing: ask for the screen, then let the prompt decide. Deprecated flags, but the
        // only ones that light a display from a receiver.
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP
                    | PowerManager.ON_AFTER_RELEASE, "epg:reminder");
            wl.acquire(30_000L);
        }
        Intent prompt = new Intent(ctx, PromptActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtras(intent);
        try {
            ctx.startActivity(prompt);
        } catch (RuntimeException e) {
            Log.w(Tuner.TAG, "prompt could not start: " + e);
        }
    }
}
