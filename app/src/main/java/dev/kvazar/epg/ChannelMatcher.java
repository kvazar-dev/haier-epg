package dev.kvazar.epg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pairs the TV's channel names with XMLTV channel names: exact match after normalisation, or one of a short
 * alias table. Never a substring match (it paired "Россия-К" with "О!" in a test), and never a time-shifted
 * copy ("НТВ (+2)", "Первый канал +1"): its times are wrong for this set. "(+0)" and "HD" suffixes are fine.
 */
final class ChannelMatcher {
    private static final Pattern SHIFTED = Pattern.compile("[+-]\\s?[1-9]\\d*|орбита|дубль", Pattern.CASE_INSENSITIVE);
    private static final Pattern STRIP = Pattern.compile("\\(\\s*\\+0\\s*\\)|\\+0\\b|\\bhd\\b|\\(\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Map<String, String[]> ALIASES = new HashMap<>();

    static {
        alias("россия к", "культура", "россия культура");
        alias("россия 1", "россия1", "россия");
        alias("россия 24", "вести 24", "россия24");
        alias("матч", "матч тв");
        alias("пятый канал", "5 канал", "петербург 5 канал", "пятый");
        alias("первый канал", "первый", "1 канал");
        alias("тв центр", "твц", "тв центр москва");
        alias("рен тв", "рен");
        alias("спас", "спас тв");
        alias("тв3", "тв 3");
        alias("пятница", "пятница!");
        alias("муз тв", "музтв");
        alias("суббота", "супер");
        alias("мир", "мир тв", "мир 24");
        alias("матч страна", "матч наш спорт");
        alias("тнт4", "тнт 4");
        alias("отр", "общественное телевидение россии");
        alias("музыка первого", "музыка");
    }

    private static void alias(String canonical, String... others) {
        ALIASES.put(canonical, others);
    }

    private ChannelMatcher() {}

    /** Lower case, no "ё", no leading number, no punctuation, single spaces. */
    static String norm(String s) {
        if (s == null) return "";
        String n = s.toLowerCase(Locale.ROOT).replace('ё', 'е');
        n = STRIP.matcher(n).replaceAll(" ");
        n = n.replaceAll("^\\d+\\s+", "");
        n = n.replace('-', ' ').replace('–', ' ').replace("!", "").replace(".", "").replace("\"", "").replace("«", "").replace("»", "");
        n = n.replaceAll("\\s+", " ").trim();
        return n;
    }

    /** The first "/" part of a time-shared channel ("ТНТ4 / Авто Плюс"), normalised. */
    static String tvKey(String tvName) {
        String first = tvName.contains("/") ? tvName.substring(0, tvName.indexOf('/')) : tvName;
        return norm(first);
    }

    /** The XMLTV channel id for a TV channel name, or null. names: xmltv id -> its display names. */
    static String match(String tvName, Map<String, List<String>> names) {
        String key = tvKey(tvName);
        if (key.isEmpty() || key.startsWith("радио") || key.matches("\\d+")) return null;
        List<String> wanted = new ArrayList<>();
        wanted.add(key);
        String[] al = ALIASES.get(key);
        if (al != null) for (String a : al) wanted.add(norm(a));
        for (Map.Entry<String, String[]> e : ALIASES.entrySet()) {
            for (String a : e.getValue()) if (norm(a).equals(key) && !wanted.contains(e.getKey())) wanted.add(e.getKey());
        }
        for (Map.Entry<String, List<String>> e : names.entrySet()) {
            for (String display : e.getValue()) {
                if (display == null) continue;
                String lower = display.toLowerCase(Locale.ROOT);
                if (SHIFTED.matcher(lower).find()) continue;
                // A parenthesised city is a regional variant with its own schedule; "(+0)" was stripped by norm.
                String d = norm(display);
                if (lower.contains("(") && !lower.contains("(+0") && !lower.contains("()")) continue;
                for (String w : wanted) if (d.equals(w)) return e.getKey();
            }
        }
        return null;
    }
}
