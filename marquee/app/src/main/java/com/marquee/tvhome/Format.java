package com.marquee.tvhome;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The one-line facts under a title. */
final class Format {
    private Format() {}

    static String meta(Context context, Title t, Map<Integer, String> genreNames, Tmdb.Details d) {
        List<String> parts = new ArrayList<>();
        if (!t.year().isEmpty()) parts.add(t.year());
        List<String> genres = new ArrayList<>();
        if (d != null && !d.genres.isEmpty()) {
            genres.addAll(d.genres.subList(0, Math.min(2, d.genres.size())));
        } else if (genreNames != null) {
            for (int id : t.genreIds) {
                String g = genreNames.get(id);
                if (g != null) genres.add(g);
                if (genres.size() == 2) break;
            }
        }
        if (!genres.isEmpty()) parts.add(String.join(", ", genres));
        if (d != null && d.runtime > 0) parts.add(context.getString(R.string.runtime, d.runtime / 60, d.runtime % 60));
        if (d != null && d.seasons > 0) {
            parts.add(d.seasons == 1 ? context.getString(R.string.one_season) : context.getString(R.string.season_count, d.seasons));
        }
        // IMDb's rating only; a title IMDb hasn't rated (or OMDb can't reach) shows no rating at all.
        if (d != null && d.imdbRating != null) parts.add("IMDb " + d.imdbRating);
        String lang = language(t.language);
        if (!lang.isEmpty()) parts.add(lang);
        if (d != null && !d.certification.isEmpty()) parts.add(d.certification);
        return String.join("  ·  ", parts);
    }

    static String language(String code) {
        if (code == null || code.isEmpty()) return "";
        String name = new Locale(code).getDisplayLanguage(Locale.ENGLISH);
        return name.isEmpty() || name.equalsIgnoreCase(code) ? code.toUpperCase(Locale.ROOT) : name;
    }
}
