package dev.kvazar.epg;

/** One browsable tuner channel from TvContract. Immutable; the repository publishes fresh lists. */
final class Channel {
    final long id;
    final String inputId;
    final String number;
    final String name;
    /** Sort key from the display number: "12-3" sorts as 12003, "7" as 7000; non-numeric numbers go last. */
    final long sortKey;

    Channel(long id, String inputId, String number, String name) {
        this.id = id;
        this.inputId = inputId == null ? "" : inputId;
        this.number = number == null ? "" : number.trim();
        String n = name == null ? "" : name.trim();
        // DVB names on this set repeat the number ("01 ПЕРВЫЙ КАНАЛ" on channel 1): drop that prefix.
        if (!this.number.isEmpty()) {
            String[] prefixes = { this.number + " ", "0" + this.number + " ", this.number + "." };
            for (String p : prefixes) {
                if (n.length() > p.length() && n.startsWith(p)) { n = n.substring(p.length()).trim(); break; }
            }
        }
        this.name = n.isEmpty() ? ("Канал " + this.number) : n;
        this.sortKey = sortKey(this.number);
    }

    private static long sortKey(String number) {
        if (number.isEmpty()) return Long.MAX_VALUE;
        long major = 0, minor = 0;
        int i = 0, n = number.length();
        while (i < n && Character.isDigit(number.charAt(i))) { major = major * 10 + (number.charAt(i) - '0'); i++; }
        if (i == 0) return Long.MAX_VALUE;
        if (i < n && (number.charAt(i) == '-' || number.charAt(i) == '.')) {
            i++;
            while (i < n && Character.isDigit(number.charAt(i))) { minor = minor * 10 + (number.charAt(i) - '0'); i++; }
        }
        return Math.min(major, 999_999L) * 1000L + Math.min(minor, 999L);
    }
}
