package dev.kvazar.epg;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Clock strings in the TV's time zone. Main thread only (SimpleDateFormat is not thread-safe). */
final class TimeFmt {
    private static final SimpleDateFormat HM = new SimpleDateFormat("HH:mm", new Locale("ru"));
    private static final SimpleDateFormat DAY = new SimpleDateFormat("EEEE, d MMMM", new Locale("ru"));
    private static final Date DATE = new Date();

    private TimeFmt() {}

    static String hm(long ms) {
        DATE.setTime(ms);
        return HM.format(DATE);
    }

    static String range(long start, long end) {
        return hm(start) + " – " + hm(end);
    }

    /** "сегодня", "завтра" or the weekday and date, for a program on another day than now. */
    static String dayLabel(long ms, long now) {
        int d = dayDiff(now, ms);
        if (d == 0) return "сегодня";
        if (d == 1) return "завтра";
        if (d == -1) return "вчера";
        DATE.setTime(ms);
        return DAY.format(DATE);
    }

    private static int dayDiff(long from, long to) {
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(from);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(to);
        int ya = a.get(Calendar.YEAR), yb = b.get(Calendar.YEAR);
        int da = a.get(Calendar.DAY_OF_YEAR), db = b.get(Calendar.DAY_OF_YEAR);
        if (ya == yb) return db - da;
        return (int) ((startOfDay(b) - startOfDay(a)) / 86_400_000L);
    }

    private static long startOfDay(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
