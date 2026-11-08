package dev.kvazar.epg;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.LongSparseArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Channels and programs out of TvContract, read on one background thread and published to the main thread as
 * immutable snapshots. Programs are cached per channel for the time range asked so far; the grid asks for the
 * visible rows and window only, so the cache stays small on a set with hundreds of channels.
 */
final class EpgRepository {
    private static final String TAG = Tuner.TAG;
    /** How much to load around what the grid asks for, so a step right or left is usually a cache hit. */
    private static final long PAD_MS = 2 * 60 * 60 * 1000L;
    /** Program inserts by the tuner arrive in bursts; one reload per burst is enough. */
    private static final long OBSERVER_DEBOUNCE_MS = 8_000L;
    private static final int MAX_CACHED_CHANNELS = 80;

    interface Listener {
        void onChannels(List<Channel> channels);
        void onPrograms();
        void onError(String message);
        /** Progress or result of a schedule download; null when idle. */
        void onSyncStatus(String message);
    }

    /** A downloaded schedule older than this is refreshed when the guide opens. */
    private static final long SYNC_STALE_MS = 20 * 3600_000L;
    private volatile boolean syncing;

    private static EpgRepository instance;

    static synchronized EpgRepository get(Context ctx) {
        if (instance == null) instance = new EpgRepository(ctx.getApplicationContext());
        return instance;
    }

    private static final class Cache {
        List<Program> list = Collections.emptyList();
        long from = Long.MAX_VALUE;
        long to = Long.MIN_VALUE;

        boolean covers(long f, long t) {
            return from <= f && to >= t;
        }
    }

    private final Context ctx;
    private final Handler bg;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final LongSparseArray<Cache> cache = new LongSparseArray<>();
    private final Set<Long> keep = new HashSet<>();
    private List<Channel> channels = Collections.emptyList();
    private Listener listener;
    private boolean observing;

    private final Runnable invalidate = new Runnable() {
        @Override public void run() {
            synchronized (lock) {
                cache.clear();
            }
            Log.i(TAG, "program cache cleared after a provider change");
            main.post(() -> { if (listener != null) listener.onPrograms(); });
        }
    };

    private final ContentObserver programObserver = new ContentObserver(null) {
        @Override public void onChange(boolean selfChange, Uri uri) {
            bg.removeCallbacks(invalidate);
            bg.postDelayed(invalidate, OBSERVER_DEBOUNCE_MS);
        }
    };

    private final Runnable reloadChannels = this::loadChannelsNow;

    private final ContentObserver channelObserver = new ContentObserver(null) {
        @Override public void onChange(boolean selfChange, Uri uri) {
            bg.removeCallbacks(reloadChannels);
            bg.postDelayed(reloadChannels, OBSERVER_DEBOUNCE_MS);
        }
    };

    private EpgRepository(Context ctx) {
        this.ctx = ctx;
        HandlerThread worker = new HandlerThread("epg-io");
        worker.start();
        bg = new Handler(worker.getLooper());
    }

    void setListener(Listener l) {
        listener = l;
    }

    List<Channel> channels() {
        return channels;
    }

    /** The cached programs of a channel, or null while nothing was loaded for it yet. Safe to iterate: never mutated. */
    List<Program> programsFor(long channelId) {
        synchronized (lock) {
            Cache c = cache.get(channelId);
            return c == null || c.from == Long.MAX_VALUE ? null : c.list;
        }
    }

    void start() {
        if (!observing) {
            observing = true;
            try {
                ctx.getContentResolver().registerContentObserver(TvContract.Programs.CONTENT_URI, true, programObserver);
                ctx.getContentResolver().registerContentObserver(TvContract.Channels.CONTENT_URI, true, channelObserver);
            } catch (SecurityException e) {
                Log.w(TAG, "cannot observe the TV provider: " + e);
            }
        }
        bg.post(this::loadChannelsNow);
    }

    void stop() {
        if (observing) {
            observing = false;
            ctx.getContentResolver().unregisterContentObserver(programObserver);
            ctx.getContentResolver().unregisterContentObserver(channelObserver);
        }
        bg.removeCallbacksAndMessages(null);
    }

    /** Loads what the grid shows: these channels between from and to, with padding, skipping what the cache holds. */
    void ensure(List<Channel> visible, long from, long to) {
        final List<Long> ids = new ArrayList<>(visible.size());
        for (Channel c : visible) ids.add(c.id);
        bg.post(() -> {
            boolean changed = false;
            for (long id : ids) {
                Cache c;
                synchronized (lock) {
                    c = cache.get(id);
                }
                if (c != null && c.covers(from, to)) continue;
                long f = from - PAD_MS, t = to + PAD_MS;
                if (c != null && c.from != Long.MAX_VALUE) {
                    // Extend the loaded interval instead of re-reading it: one contiguous range per channel.
                    f = Math.min(f, c.from);
                    t = Math.max(t, c.to);
                }
                List<Program> fresh = queryPrograms(id, f, t);
                if (fresh == null) continue;
                Cache n = new Cache();
                n.list = fresh;
                n.from = f;
                n.to = t;
                synchronized (lock) {
                    cache.put(id, n);
                }
                changed = true;
            }
            trimCache(ids);
            if (changed) main.post(() -> { if (listener != null) listener.onPrograms(); });
        });
    }

    private void trimCache(List<Long> visible) {
        synchronized (lock) {
            if (cache.size() <= MAX_CACHED_CHANNELS) return;
            keep.clear();
            keep.addAll(visible);
            for (int i = cache.size() - 1; i >= 0; i--) {
                if (!keep.contains(cache.keyAt(i))) cache.removeAt(i);
                if (cache.size() <= MAX_CACHED_CHANNELS / 2) break;
            }
        }
    }

    /** Diagnostics to logcat: every channel row the provider shows us, with its program count for the next two days. */
    void debugDump() {
        bg.post(() -> {
            long now = System.currentTimeMillis();
            String[] projection = {
                    TvContract.Channels._ID, TvContract.Channels.COLUMN_INPUT_ID, TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                    TvContract.Channels.COLUMN_DISPLAY_NAME, TvContract.Channels.COLUMN_TYPE, TvContract.Channels.COLUMN_BROWSABLE,
                    TvContract.Channels.COLUMN_SERVICE_TYPE, TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID,
                    TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID, TvContract.Channels.COLUMN_SERVICE_ID, TvContract.Channels.COLUMN_SEARCHABLE,
            };
            try (Cursor c = ctx.getContentResolver().query(TvContract.Channels.CONTENT_URI, projection, null, null, null)) {
                if (c == null) { Log.i(TAG, "dump: null cursor"); return; }
                Log.i(TAG, "dump: " + c.getCount() + " channel rows in all");
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    int count = 0;
                    long first = 0, last = 0;
                    try (Cursor p = ctx.getContentResolver().query(TvContract.buildProgramsUriForChannel(id, now - 6 * 3600_000L, now + 48 * 3600_000L),
                            new String[] { TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS }, null, null, null)) {
                        if (p != null) {
                            count = p.getCount();
                            if (p.moveToFirst()) first = p.getLong(0);
                            if (p.moveToLast()) last = p.getLong(1);
                        }
                    } catch (RuntimeException e) {
                        Log.w(TAG, "dump programs " + id + ": " + e);
                    }
                    Log.i(TAG, "dump: id=" + id + " num=" + c.getString(2) + " name=" + c.getString(3) + " input=" + c.getString(1)
                            + " type=" + c.getString(4) + " browsable=" + c.getInt(5) + " searchable=" + c.getInt(10) + " svc=" + c.getString(6)
                            + " onid=" + c.getInt(7) + " tsid=" + c.getInt(8) + " sid=" + c.getInt(9)
                            + " programs48h=" + count + (count > 0 ? " first=" + first + " last=" + last : ""));
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "dump channels: " + e);
            }
            try (Cursor p = ctx.getContentResolver().query(TvContract.Programs.CONTENT_URI, new String[] { TvContract.Programs._ID }, null, null, null)) {
                Log.i(TAG, "dump: " + (p == null ? -1 : p.getCount()) + " program rows visible in all");
            } catch (RuntimeException e) {
                Log.w(TAG, "dump all programs: " + e);
            }
        });
    }

    boolean needsSync() {
        EpgDb db = EpgDb.get(ctx);
        long updated = db.updatedAt();
        int count = db.count();
        boolean stale = System.currentTimeMillis() - updated > SYNC_STALE_MS || count == 0;
        Log.i(TAG, "schedule store: " + count + " programs, updated " + updated + (stale ? ", refreshing" : ", fresh"));
        return stale;
    }

    boolean isSyncing() {
        return syncing;
    }

    /** What the info panel shows about the downloaded schedule: its source and time, or nothing before the first one. */
    String syncSummary() {
        EpgDb db = EpgDb.get(ctx);
        long at = db.updatedAt();
        String src = db.meta("source");
        if (at == 0 || src == null) return null;
        return src + " · " + TimeFmt.hm(at);
    }

    /**
     * Downloads the schedule on the calling thread (the sync job's thread, or the worker when called from the
     * guide), then drops the program cache so the grid reloads with the merged data. done runs on the main thread.
     */
    void syncNow(Runnable done) {
        if (syncing) return;
        syncing = true;
        main.post(() -> { if (listener != null) listener.onSyncStatus("Загрузка программы передач…"); });
        List<Channel> list = channels;
        if (list.isEmpty()) list = queryChannels(new String[1]);
        XmltvSync.Result r = XmltvSync.run(ctx, list);
        synchronized (lock) {
            cache.clear();
        }
        syncing = false;
        final String status = r.ok() ? null : "Программа не загружена: " + r.error;
        main.post(() -> {
            if (listener != null) {
                listener.onSyncStatus(status);
                listener.onPrograms();
            }
            if (done != null) done.run();
        });
    }

    /** syncNow from the guide: on the worker thread, so the UI never waits for the network. */
    void syncInBackground() {
        if (syncing) return;
        bg.post(() -> syncNow(null));
    }

    private void loadChannelsNow() {
        String[] error = new String[1];
        List<Channel> all = queryChannels(error);
        final List<Channel> published = Collections.unmodifiableList(all);
        final String err = error[0];
        main.post(() -> {
            channels = published;
            if (listener == null) return;
            if (err != null && published.isEmpty()) listener.onError(err);
            listener.onChannels(published);
        });
    }

    private List<Channel> queryChannels(String[] errorOut) {
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = tim == null ? Collections.emptyList() : tim.getTvInputList();
        List<String> tunerInputs = new ArrayList<>();
        for (TvInputInfo info : inputs) {
            if (info.getType() == TvInputInfo.TYPE_TUNER) tunerInputs.add(info.getId());
        }
        Log.i(TAG, "tuner inputs: " + tunerInputs);
        List<Channel> all = new ArrayList<>();
        String error = null;
        for (String inputId : tunerInputs) {
            Uri uri = TvContract.buildChannelsUriForInput(inputId).buildUpon()
                    .appendQueryParameter("browsable_only", "true").build();
            String[] projection = {
                    TvContract.Channels._ID, TvContract.Channels.COLUMN_INPUT_ID, TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                    TvContract.Channels.COLUMN_DISPLAY_NAME, TvContract.Channels.COLUMN_TYPE, TvContract.Channels.COLUMN_BROWSABLE,
            };
            try (Cursor c = ctx.getContentResolver().query(uri, projection, null, null, null)) {
                if (c == null) continue;
                while (c.moveToNext()) {
                    if (c.getInt(5) == 0) continue;
                    String type = c.getString(4);
                    if (TvContract.Channels.TYPE_PREVIEW.equals(type)) continue;
                    all.add(new Channel(c.getLong(0), c.getString(1), c.getString(2), c.getString(3)));
                }
            } catch (SecurityException e) {
                error = e.getMessage();
                Log.w(TAG, "channels refused: " + e);
            } catch (RuntimeException e) {
                error = e.toString();
                Log.w(TAG, "channels failed: " + e);
            }
        }
        Collections.sort(all, (a, b) -> {
            int k = Long.compare(a.sortKey, b.sortKey);
            return k != 0 ? k : a.name.compareToIgnoreCase(b.name);
        });
        Log.i(TAG, "channels: " + all.size() + (all.isEmpty() ? "" : ", first " + all.get(0).number + " " + all.get(0).name
                + ", last " + all.get(all.size() - 1).number + " " + all.get(all.size() - 1).name));
        errorOut[0] = error;
        return all;
    }

    private List<Program> queryPrograms(long channelId, long from, long to) {
        Uri uri = TvContract.buildProgramsUriForChannel(channelId, from, to);
        String[] projection = {
                TvContract.Programs._ID, TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS,
                TvContract.Programs.COLUMN_TITLE, TvContract.Programs.COLUMN_SHORT_DESCRIPTION, TvContract.Programs.COLUMN_LONG_DESCRIPTION,
                TvContract.Programs.COLUMN_CANONICAL_GENRE, TvContract.Programs.COLUMN_BROADCAST_GENRE, TvContract.Programs.COLUMN_EPISODE_TITLE,
        };
        List<Program> out = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(uri, projection, null, null, null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                long start = c.getLong(1), end = c.getLong(2);
                if (end <= start) continue;
                String title = c.getString(3);
                String episode = c.getString(8);
                if (episode != null && !episode.trim().isEmpty()) title = title + ". " + episode.trim();
                String desc = c.getString(5);
                if (desc == null || desc.trim().isEmpty()) desc = c.getString(4);
                out.add(new Program(c.getLong(0), channelId, start, end, title, desc, Genres.label(c.getString(6), c.getString(7))));
            }
        } catch (SecurityException e) {
            Log.w(TAG, "programs refused: " + e);
            return null;
        } catch (RuntimeException e) {
            Log.w(TAG, "programs failed: " + e);
            return null;
        }
        Collections.sort(out, (a, b) -> Long.compare(a.start, b.start));
        // Overlaps from a re-sent schedule: keep the later row, the tuner's newer one.
        for (int i = out.size() - 1; i > 0; i--) {
            if (out.get(i).start < out.get(i - 1).end) out.remove(i - 1);
        }
        // The downloaded schedule fills everything the tuner did not write. The tuner's rows win where they
        // overlap: their times come off the air, and their descriptions with them.
        List<Program> downloaded;
        try {
            downloaded = EpgDb.get(ctx).programs(channelId, from, to);
        } catch (RuntimeException e) {
            Log.w(TAG, "epg db read failed: " + e);
            downloaded = Collections.emptyList();
        }
        if (!downloaded.isEmpty()) {
            List<Program> merged = new ArrayList<>(out.size() + downloaded.size());
            merged.addAll(out);
            for (Program d : downloaded) {
                boolean clash = false;
                for (Program t : out) {
                    long overlap = Math.min(d.end, t.end) - Math.max(d.start, t.start);
                    if (overlap > 60_000L) { clash = true; break; }
                }
                if (!clash) merged.add(d);
            }
            Collections.sort(merged, (a, b) -> Long.compare(a.start, b.start));
            out = merged;
        }
        return Collections.unmodifiableList(out);
    }
}
