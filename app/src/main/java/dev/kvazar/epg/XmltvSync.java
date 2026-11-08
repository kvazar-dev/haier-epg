package dev.kvazar.epg;

import android.content.Context;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

/**
 * Downloads an XMLTV schedule from the first source that answers, keeps the programs of the TV's channels and
 * writes them to {@link EpgDb}. Streaming parse: the files hold hundreds of channels and a week of programs,
 * only a few thousand rows of which are ours.
 */
final class XmltvSync {
    private static final String TAG = Tuner.TAG;
    /**
     * Order of trust. teleguide.info: a week, 4.6 MB, descriptions on part of the channels. it999.ru: a week with
     * descriptions, 43 MB, regional naming that the matcher pairs for about half the channels. programtv.ru: three
     * days, 1 MB, no descriptions. iptvx.one LITE: one day ahead, 15 MB, no descriptions.
     */
    static final String[][] SOURCES = {
            { "teleguide.info", "https://www.teleguide.info/download/new3/xmltv.xml.gz" },
            { "programtv.ru", "http://programtv.ru/xmltv.xml.gz" },
            { "iptvx.one", "https://iptvx.one/EPG_LITE" },
            { "it999.ru", "https://epg.it999.ru/epg2.xml.gz" },
    };
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final long KEEP_PAST_MS = 6 * 3600_000L;
    private static final long KEEP_FUTURE_MS = 9 * 24 * 3600_000L;

    static final class Result {
        String source;
        int programs;
        int matched;
        String error;
        boolean ok() { return error == null; }
    }

    private XmltvSync() {}

    /**
     * Runs on the caller's thread. Sources are tried in order; each one adds the channels the earlier ones did not
     * name, so a feed that lacks a local channel is topped up by the next, and a feed that is down is skipped.
     */
    static Result run(Context ctx, List<Channel> channels) {
        Result r = new Result();
        if (channels == null || channels.isEmpty()) {
            r.error = "no channels";
            return r;
        }
        List<Channel> pending = new ArrayList<>(channels);
        List<Program> rows = new ArrayList<>();
        StringBuilder used = new StringBuilder();
        StringBuilder errors = new StringBuilder();
        for (String[] src : SOURCES) {
            if (pending.isEmpty() || Thread.currentThread().isInterrupted()) break;
            long t0 = System.currentTimeMillis();
            try {
                List<Program> part = new ArrayList<>();
                Set<Long> covered = fetch(src[1], pending, part);
                if (covered.isEmpty()) {
                    Log.i(TAG, "xmltv " + src[0] + ": none of the remaining " + pending.size() + " channels");
                    continue;
                }
                rows.addAll(part);
                for (int i = pending.size() - 1; i >= 0; i--) if (covered.contains(pending.get(i).id)) pending.remove(i);
                r.matched += covered.size();
                if (used.length() > 0) used.append(" + ");
                used.append(src[0]);
                Log.i(TAG, "xmltv " + src[0] + ": " + covered.size() + " channels, " + part.size() + " programs in "
                        + (System.currentTimeMillis() - t0) + " ms; " + pending.size() + " channels still without a feed");
            } catch (IOException | XmlPullParserException | RuntimeException e) {
                Log.w(TAG, "xmltv " + src[0] + " failed: " + e);
                if (errors.length() > 0) errors.append("; ");
                errors.append(src[0]).append(": ").append(e.getClass().getSimpleName());
            }
        }
        if (rows.isEmpty()) {
            r.error = errors.length() > 0 ? errors.toString() : "no programs for our channels";
            return r;
        }
        EpgDb.get(ctx).replaceAll(rows, used.toString(), System.currentTimeMillis());
        r.source = used.toString();
        r.programs = rows.size();
        return r;
    }

    private static Set<Long> fetch(String url, List<Channel> channels, List<Program> out) throws IOException, XmlPullParserException {
        HttpURLConnection conn = open(url);
        try {
            // HttpURLConnection follows redirects only within one scheme; a feed that moved from http to https
            // or to another host answers 301/302/307/308 with a Location, which is followed here, a few hops.
            for (int hop = 0; hop < 4; hop++) {
                int code = conn.getResponseCode();
                if (code / 100 == 3) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isEmpty()) throw new IOException("HTTP " + code + " without Location");
                    URL next = new URL(new URL(url), location);
                    conn.disconnect();
                    url = next.toString();
                    conn = open(url);
                    continue;
                }
                if (code != 200) throw new IOException("HTTP " + code);
                break;
            }
            InputStream raw = new BufferedInputStream(conn.getInputStream(), 64 * 1024);
            raw.mark(4);
            int b1 = raw.read(), b2 = raw.read();
            raw.reset();
            InputStream in = (b1 == 0x1f && b2 == 0x8b) ? new GZIPInputStream(raw, 64 * 1024) : raw;
            return parse(in, channels, out);
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent", "haier-epg/0.2 (Android TV)");
        conn.setRequestProperty("Accept-Encoding", "identity");
        return conn;
    }

    private static Set<Long> parse(InputStream in, List<Channel> channels, List<Program> out) throws IOException, XmlPullParserException {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(in, null);
        Map<String, List<String>> names = new HashMap<>();
        Map<String, Long> xidToTv = null;
        long now = System.currentTimeMillis();
        long minStart = now - KEEP_PAST_MS, maxStart = now + KEEP_FUTURE_MS;
        Set<Long> matched = new HashSet<>();

        String curChannel = null;
        List<String> curNames = null;
        String progChannel = null;
        long progStart = 0, progEnd = 0;
        String title = null, desc = null, genre = null;
        String text = null;
        int depth = 0;
        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String tag = p.getName();
                depth++;
                if ("channel".equals(tag)) {
                    curChannel = p.getAttributeValue(null, "id");
                    curNames = new ArrayList<>(2);
                } else if ("programme".equals(tag)) {
                    if (xidToTv == null) {
                        xidToTv = buildMapping(channels, names);
                        if (xidToTv.isEmpty()) return matched;
                    }
                    progChannel = p.getAttributeValue(null, "channel");
                    if (xidToTv.containsKey(progChannel)) {
                        progStart = parseTime(p.getAttributeValue(null, "start"));
                        progEnd = parseTime(p.getAttributeValue(null, "stop"));
                    } else {
                        progChannel = null;
                    }
                    title = null; desc = null; genre = null;
                }
                text = null;
            } else if (event == XmlPullParser.TEXT) {
                text = p.getText();
            } else if (event == XmlPullParser.END_TAG) {
                String tag = p.getName();
                depth--;
                if (curChannel != null) {
                    if ("display-name".equals(tag) && text != null) curNames.add(text.trim());
                    else if ("channel".equals(tag)) {
                        if (!curNames.isEmpty()) names.put(curChannel, curNames);
                        curChannel = null;
                    }
                } else if (progChannel != null) {
                    if ("title".equals(tag) && title == null) title = text;
                    else if ("desc".equals(tag) && desc == null) desc = text;
                    else if ("category".equals(tag) && genre == null) genre = text;
                    else if ("programme".equals(tag)) {
                        if (progStart > 0 && progEnd > progStart && progStart >= minStart && progStart <= maxStart) {
                            Long tv = xidToTv.get(progChannel);
                            if (tv != null) {
                                out.add(new Program(-(progStart / 1000L), tv, progStart, progEnd, title, desc, genre == null ? "" : genre.trim()));
                                matched.add(tv);
                            }
                        }
                        progChannel = null;
                    }
                }
                text = null;
            }
            event = p.next();
        }
        return matched;
    }

    /** xmltv id -> TV channel id, for every TV channel the feed names. */
    private static Map<String, Long> buildMapping(List<Channel> channels, Map<String, List<String>> names) {
        Map<String, Long> map = new HashMap<>();
        for (Channel ch : channels) {
            String xid = ChannelMatcher.match(ch.name, names);
            if (xid != null && !map.containsKey(xid)) map.put(xid, ch.id);
        }
        return map;
    }

    /** "20261108050000 +0300" -> UTC ms; without an offset the TV's zone is assumed. 0 on garbage. */
    static long parseTime(String s) {
        if (s == null || s.length() < 12) return 0;
        try {
            int year = Integer.parseInt(s.substring(0, 4));
            int month = Integer.parseInt(s.substring(4, 6));
            int day = Integer.parseInt(s.substring(6, 8));
            int hour = Integer.parseInt(s.substring(8, 10));
            int min = Integer.parseInt(s.substring(10, 12));
            int sec = s.length() >= 14 && Character.isDigit(s.charAt(12)) ? Integer.parseInt(s.substring(12, 14)) : 0;
            long offsetMs;
            int sp = s.indexOf(' ');
            if (sp > 0 && s.length() >= sp + 6) {
                int sign = s.charAt(sp + 1) == '-' ? -1 : 1;
                int oh = Integer.parseInt(s.substring(sp + 2, sp + 4));
                int om = Integer.parseInt(s.substring(sp + 4, sp + 6));
                offsetMs = sign * (oh * 60L + om) * 60_000L;
            } else {
                offsetMs = TimeZone.getDefault().getOffset(System.currentTimeMillis());
            }
            // Days since the epoch for a proleptic Gregorian date, without Calendar's allocations.
            long days = daysFromCivil(year, month, day);
            return (days * 86_400L + hour * 3600L + min * 60L + sec) * 1000L - offsetMs;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static long daysFromCivil(int y, int m, int d) {
        y -= m <= 2 ? 1 : 0;
        long era = (y >= 0 ? y : y - 399) / 400;
        long yoe = y - era * 400;
        long doy = (153L * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146_097L + doe - 719_468L;
    }
}
