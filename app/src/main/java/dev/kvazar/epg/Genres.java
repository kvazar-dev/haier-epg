package dev.kvazar.epg;

import android.media.tv.TvContract;

/** Russian labels for the canonical genres TvContract knows; a broadcast genre string is shown as is. */
final class Genres {
    private Genres() {}

    static String label(String canonical, String broadcast) {
        if (canonical != null && !canonical.isEmpty()) {
            String[] parts;
            try {
                parts = TvContract.Programs.Genres.decode(canonical);
            } catch (RuntimeException e) {
                parts = new String[0];
            }
            StringBuilder sb = new StringBuilder();
            for (String p : parts) {
                String ru = ru(p);
                if (ru == null) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(ru);
                if (sb.length() > 40) break;
            }
            if (sb.length() > 0) return sb.toString();
        }
        if (broadcast != null && !broadcast.isEmpty()) {
            String[] parts;
            try {
                parts = TvContract.Programs.Genres.decode(broadcast);
            } catch (RuntimeException e) {
                parts = new String[] { broadcast };
            }
            return parts.length == 0 ? "" : parts[0];
        }
        return "";
    }

    private static String ru(String genre) {
        switch (genre) {
            case TvContract.Programs.Genres.FAMILY_KIDS: return "Детям";
            case TvContract.Programs.Genres.SPORTS: return "Спорт";
            case TvContract.Programs.Genres.SHOPPING: return "Покупки";
            case TvContract.Programs.Genres.MOVIES: return "Кино";
            case TvContract.Programs.Genres.COMEDY: return "Комедия";
            case TvContract.Programs.Genres.TRAVEL: return "Путешествия";
            case TvContract.Programs.Genres.DRAMA: return "Драма";
            case TvContract.Programs.Genres.EDUCATION: return "Образование";
            case TvContract.Programs.Genres.ANIMAL_WILDLIFE: return "Природа";
            case TvContract.Programs.Genres.NEWS: return "Новости";
            case TvContract.Programs.Genres.GAMING: return "Игры";
            case TvContract.Programs.Genres.ARTS: return "Искусство";
            case TvContract.Programs.Genres.ENTERTAINMENT: return "Развлечения";
            case TvContract.Programs.Genres.LIFE_STYLE: return "Стиль жизни";
            case TvContract.Programs.Genres.MUSIC: return "Музыка";
            case TvContract.Programs.Genres.PREMIER: return "Премьера";
            case TvContract.Programs.Genres.TECH_SCIENCE: return "Наука";
            default: return null;
        }
    }
}
