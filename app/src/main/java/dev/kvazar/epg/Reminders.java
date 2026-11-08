package dev.kvazar.epg;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Scheduled channel switches: a small JSON list in SharedPreferences plus one exact alarm each. setAlarmClock is
 * exact on every Android version without a permission prompt and wakes the device; the receiver then shows the
 * prompt LEAD_MS before the program starts.
 */
final class Reminders {
    static final long LEAD_MS = 20_000L;
    /** A reminder whose start is this far in the past is stale (the TV was off) and is dropped. */
    static final long GRACE_MS = 10 * 60 * 1000L;
    private static final String FILE = "reminders";
    private static final String KEY = "list";
    static final String EXTRA_CHANNEL = "channel";
    static final String EXTRA_START = "start";
    static final String EXTRA_END = "end";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_CHANNEL_NAME = "channel_name";
    static final String EXTRA_CHANNEL_NUMBER = "channel_number";

    static final class Reminder {
        final long channelId, start, end;
        final String title, channelName, channelNumber;

        Reminder(long channelId, long start, long end, String title, String channelName, String channelNumber) {
            this.channelId = channelId;
            this.start = start;
            this.end = end;
            this.title = title;
            this.channelName = channelName;
            this.channelNumber = channelNumber;
        }

        String key() {
            return channelId + ":" + start;
        }
    }

    private Reminders() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static synchronized List<Reminder> all(Context ctx) {
        List<Reminder> out = new ArrayList<>();
        String raw = sp(ctx).getString(KEY, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Reminder(o.getLong("c"), o.getLong("s"), o.optLong("e", 0), o.optString("t", ""),
                        o.optString("n", ""), o.optString("num", "")));
            }
        } catch (JSONException e) {
            Log.w(Tuner.TAG, "reminder store unreadable, starting empty: " + e);
        }
        return out;
    }

    static Set<String> keys(Context ctx) {
        Set<String> s = new HashSet<>();
        for (Reminder r : all(ctx)) s.add(r.key());
        return s;
    }

    static boolean isScheduled(Context ctx, Program p) {
        return keys(ctx).contains(p.key());
    }

    private static synchronized void save(Context ctx, List<Reminder> list) {
        JSONArray arr = new JSONArray();
        for (Reminder r : list) {
            try {
                arr.put(new JSONObject().put("c", r.channelId).put("s", r.start).put("e", r.end).put("t", r.title)
                        .put("n", r.channelName).put("num", r.channelNumber));
            } catch (JSONException ignored) {
            }
        }
        sp(ctx).edit().putString(KEY, arr.toString()).apply();
    }

    static void add(Context ctx, Channel ch, Program p) {
        List<Reminder> list = all(ctx);
        for (Reminder r : list) if (r.key().equals(p.key())) return;
        Reminder r = new Reminder(ch.id, p.start, p.end, p.title, ch.name, ch.number);
        list.add(r);
        save(ctx, list);
        schedule(ctx, r);
    }

    static void remove(Context ctx, long channelId, long start) {
        List<Reminder> list = all(ctx);
        List<Reminder> kept = new ArrayList<>();
        for (Reminder r : list) {
            if (r.channelId == channelId && r.start == start) cancel(ctx, r); else kept.add(r);
        }
        save(ctx, kept);
    }

    /** After boot or a clock change: drop what is past, arm the rest again. */
    static void rescheduleAll(Context ctx) {
        long now = System.currentTimeMillis();
        List<Reminder> all = all(ctx);
        List<Reminder> kept = new ArrayList<>();
        for (Reminder r : all) {
            if (r.start < now - GRACE_MS) continue;
            kept.add(r);
            schedule(ctx, r);
        }
        if (kept.size() != all.size()) save(ctx, kept);
        Log.i(Tuner.TAG, "reminders: " + kept.size() + " armed, " + (all.size() - kept.size()) + " expired");
    }

    private static PendingIntent pending(Context ctx, Reminder r) {
        Intent i = new Intent(ctx, ReminderReceiver.class)
                .setAction("dev.kvazar.epg.REMIND")
                .putExtra(EXTRA_CHANNEL, r.channelId)
                .putExtra(EXTRA_START, r.start)
                .putExtra(EXTRA_END, r.end)
                .putExtra(EXTRA_TITLE, r.title)
                .putExtra(EXTRA_CHANNEL_NAME, r.channelName)
                .putExtra(EXTRA_CHANNEL_NUMBER, r.channelNumber);
        int code = (int) (r.channelId * 31 + r.start / 60_000L);
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void schedule(Context ctx, Reminder r) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long at = Math.max(System.currentTimeMillis() + 1_000L, r.start - LEAD_MS);
        PendingIntent show = PendingIntent.getActivity(ctx, 0, new Intent(ctx, GuideActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), pending(ctx, r));
        } catch (SecurityException e) {
            Log.w(Tuner.TAG, "exact alarm refused, using an inexact one: " + e);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, r));
        }
        Log.i(Tuner.TAG, "reminder armed: channel " + r.channelId + " (" + r.channelNumber + ") " + r.title + " at " + r.start
                + ", alarm at " + at);
    }

    private static void cancel(Context ctx, Reminder r) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(ctx, r));
    }
}
