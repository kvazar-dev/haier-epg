package dev.kvazar.epg;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** The downloaded schedule: one table of programs keyed by the TV's channel id, replaced whole at every sync. */
final class EpgDb extends SQLiteOpenHelper {
    private static EpgDb instance;

    static synchronized EpgDb get(Context ctx) {
        if (instance == null) instance = new EpgDb(ctx.getApplicationContext());
        return instance;
    }

    private EpgDb(Context ctx) {
        super(ctx, "epg.db", null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE prog (tvch INTEGER NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, title TEXT, descr TEXT, genre TEXT)");
        db.execSQL("CREATE INDEX prog_ch ON prog (tvch, start)");
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS prog");
        db.execSQL("DROP TABLE IF EXISTS meta");
        onCreate(db);
    }

    /** Swaps the whole table for the new rows in one transaction: readers see the old or the new set, never a mix. */
    void replaceAll(List<Program> rows, String source, long updatedAt) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("prog", null, null);
            ContentValues cv = new ContentValues();
            for (Program p : rows) {
                cv.clear();
                cv.put("tvch", p.channelId);
                cv.put("start", p.start);
                cv.put("end", p.end);
                cv.put("title", p.title);
                cv.put("descr", p.description);
                cv.put("genre", p.genre);
                db.insert("prog", null, cv);
            }
            putMeta(db, "source", source);
            putMeta(db, "updated", Long.toString(updatedAt));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void putMeta(SQLiteDatabase db, String k, String v) {
        ContentValues cv = new ContentValues();
        cv.put("k", k);
        cv.put("v", v);
        db.insertWithOnConflict("meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    String meta(String k) {
        try (Cursor c = getReadableDatabase().query("meta", new String[] { "v" }, "k=?", new String[] { k }, null, null, null)) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    long updatedAt() {
        String v = meta("updated");
        try {
            return v == null ? 0 : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    int count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM prog", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Programs of one TV channel overlapping [from, to), sorted by start. Negative ids are synthetic. */
    List<Program> programs(long channelId, long from, long to) {
        List<Program> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("prog", new String[] { "start", "end", "title", "descr", "genre" },
                "tvch=? AND end>? AND start<?", new String[] { Long.toString(channelId), Long.toString(from), Long.toString(to) },
                null, null, "start ASC")) {
            while (c.moveToNext()) {
                long start = c.getLong(0);
                out.add(new Program(-(start / 1000L), channelId, start, c.getLong(1), c.getString(2), c.getString(3), c.getString(4)));
            }
        }
        return out;
    }
}
