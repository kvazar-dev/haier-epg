package dev.kvazar.epg;

/** One program row from TvContract. Times are UTC milliseconds. Immutable. */
final class Program {
    final long id;
    final long channelId;
    final long start;
    final long end;
    final String title;
    final String description;
    final String genre;

    Program(long id, long channelId, long start, long end, String title, String description, String genre) {
        this.id = id;
        this.channelId = channelId;
        this.start = start;
        this.end = end;
        this.title = title == null || title.trim().isEmpty() ? "Без названия" : title.trim();
        this.description = description == null ? "" : description.trim();
        this.genre = genre == null ? "" : genre;
    }

    boolean covers(long t) {
        return start <= t && t < end;
    }

    /** Key for the reminder store: a program is the same program when its channel and start match. */
    String key() {
        return channelId + ":" + start;
    }
}
