package dev.kvazar.epg;

import android.content.Context;
import android.content.SharedPreferences;

/** The two numbers the guide remembers: the channel last tuned through it and the row it was on. */
final class Prefs {
    private static final String FILE = "guide";
    private static final String LAST_TUNED = "last_tuned";
    private static final String LAST_SELECTED = "last_selected";

    private Prefs() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static long lastTuned(Context ctx) {
        return sp(ctx).getLong(LAST_TUNED, -1L);
    }

    static void setLastTuned(Context ctx, long channelId) {
        sp(ctx).edit().putLong(LAST_TUNED, channelId).putLong(LAST_SELECTED, channelId).apply();
    }

    static long lastSelected(Context ctx) {
        return sp(ctx).getLong(LAST_SELECTED, -1L);
    }

    static void setLastSelected(Context ctx, long channelId) {
        sp(ctx).edit().putLong(LAST_SELECTED, channelId).apply();
    }
}
