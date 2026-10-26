package com.marquee.tvhome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** One movie or show as TMDB lists it. */
final class Title {
    final int id;
    /** "movie" or "tv". */
    final String type;
    final String name;
    final String overview;
    final String date;
    final String poster;
    final String backdrop;
    final double vote;
    final String language;
    final int[] genreIds;

    private Title(int id, String type, String name, String overview, String date, String poster, String backdrop,
                  double vote, String language, int[] genreIds) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.overview = overview;
        this.date = date;
        this.poster = poster;
        this.backdrop = backdrop;
        this.vote = vote;
        this.language = language;
        this.genreIds = genreIds;
    }

    /** A title from a TMDB result, or null for people and incomplete entries. */
    static Title from(JSONObject o, String fallbackType) {
        String type = o.optString("media_type", fallbackType);
        if (!"movie".equals(type) && !"tv".equals(type)) return null;
        int id = o.optInt("id", 0);
        String name = o.optString("movie".equals(type) ? "title" : "name", "");
        if (id == 0 || name.isEmpty()) return null;
        JSONArray g = o.optJSONArray("genre_ids");
        int[] genres = new int[g == null ? 0 : g.length()];
        for (int i = 0; i < genres.length; i++) genres[i] = g.optInt(i);
        return new Title(id, type, name, o.optString("overview", ""),
                o.optString("movie".equals(type) ? "release_date" : "first_air_date", ""),
                nullable(o, "poster_path"), nullable(o, "backdrop_path"), o.optDouble("vote_average", 0),
                o.optString("original_language", ""), genres);
    }

    private static String nullable(JSONObject o, String key) {
        return o.isNull(key) ? null : o.optString(key, null);
    }

    String year() {
        return date != null && date.length() >= 4 ? date.substring(0, 4) : "";
    }

    String key() {
        return type + ":" + id;
    }

    /** Enough to show the tile and open details again (My list). */
    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id).put("media_type", type).put("movie".equals(type) ? "title" : "name", name)
                    .put("overview", overview).put("movie".equals(type) ? "release_date" : "first_air_date", date)
                    .put("poster_path", poster == null ? JSONObject.NULL : poster)
                    .put("backdrop_path", backdrop == null ? JSONObject.NULL : backdrop)
                    .put("vote_average", vote).put("original_language", language);
            JSONArray g = new JSONArray();
            for (int id : genreIds) g.put(id);
            o.put("genre_ids", g);
        } catch (JSONException ignored) {
            // put() only throws for NaN numbers, which TMDB never sends.
        }
        return o;
    }
}
