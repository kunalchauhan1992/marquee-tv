package com.marquee.tvhome;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * TMDB v3 over HTTPS, cached on disk. A response younger than its time-to-live is served from disk; when the TV is
 * offline an older copy is served instead, so Home still shows posters. The API key is never part of a cache name.
 */
final class Tmdb {
    static final String IMG = "https://image.tmdb.org/t/p/";
    private static final String API = "https://api.themoviedb.org/3/";
    private static final long HOUR = 3_600_000L;
    static final String REGION = "IN";
    /** TMDB keywords "stand-up comedy", "stand-up specials", "comedy special". */
    private static final List<Integer> STAND_UP = java.util.Arrays.asList(9716, 356038, 362573);

    private final File dir;
    private final Prefs prefs;
    final ImdbRatings imdb;
    private volatile String providerIds;
    private volatile Map<Integer, String> genres;

    Tmdb(Context context, Prefs prefs) {
        this.prefs = prefs;
        imdb = new ImdbRatings(context);
        dir = new File(context.getCacheDir(), "tmdb");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    static final class NoKeyException extends IOException {
        NoKeyException() {
            super("no TMDB key");
        }
    }

    static String poster(String path) {
        return path == null ? null : IMG + "w342" + path;
    }

    static String backdrop(String path) {
        return path == null ? null : IMG + "w1280" + path;
    }

    void clearCache() {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
        providerIds = null;
        genres = null;
    }

    // ---- rows -----------------------------------------------------------------------------------------------

    /** Trending this week (movies and shows), keeping only titles streaming on your services in India. */
    List<Title> trending() throws IOException {
        return trendingOnYourApps("all");
    }

    /**
     * TMDB's weekly trending list (3 pages, 60 titles), then each title's Indian providers (cached a day) decide
     * whether it stays. Checked 8 at a time so the first load stays quick.
     */
    private List<Title> trendingOnYourApps(String kind) throws IOException {
        List<Title> candidates = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            candidates.addAll(titles(get("trending/" + kind + "/week", "language=en-US&page=" + page, 6 * HOUR),
                    "all".equals(kind) ? null : kind));
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<Boolean>> checks = new ArrayList<>();
            for (Title t : candidates) checks.add(pool.submit(() -> onYourAppsInIndia(t)));
            List<Title> out = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                try {
                    if (checks.get(i).get() && !REGIONAL.contains(candidates.get(i).language)) out.add(candidates.get(i));
                } catch (Exception ignored) {
                    // One title that can't be checked is left out.
                }
            }
            return out;
        } finally {
            pool.shutdown();
        }
    }

    private boolean onYourAppsInIndia(Title t) throws IOException {
        JSONObject r = get(t.type + "/" + t.id + "/watch/providers", "", 24 * HOUR).optJSONObject("results");
        JSONObject in = r == null ? null : r.optJSONObject(REGION);
        if (in == null) return false;
        for (String kind : new String[]{"flatrate", "free", "ads"}) {
            JSONArray a = in.optJSONArray(kind);
            for (int i = 0; a != null && i < a.length(); i++) {
                if (Services.forProvider(a.optJSONObject(i).optString("provider_name")) != null) return true;
            }
        }
        return false;
    }

    /** Recent movies streaming in India on the services whose apps are on this TV. */
    List<Title> newOnYourApps() throws IOException {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        return discover("movie", "sort_by=primary_release_date.desc&primary_release_date.lte=" + today
                + "&vote_count.gte=15" + onYourApps());
    }

    List<Title> discover(String type, String query) throws IOException {
        return withoutRegional(titles(get("discover/" + type, "language=en-US&include_adult=false&" + query, 6 * HOUR), type));
    }

    /**
     * Indian regional languages, left out of every row: no free source says which of them have a Hindi dub, so
     * none are recommended. Search still finds them by name.
     */
    private static final java.util.Set<String> REGIONAL = new java.util.HashSet<>(java.util.Arrays.asList(
            "ta", "te", "ml", "kn", "bn", "mr", "pa", "gu", "or", "as", "bh", "kok", "sa", "ur"));

    static List<Title> withoutRegional(List<Title> titles) {
        titles.removeIf(t -> REGIONAL.contains(t.language));
        return titles;
    }

    /** Shows trending this week on your services. */
    List<Title> trendingShows() throws IOException {
        return trendingOnYourApps("tv");
    }

    /**
     * Indian titles (made in India) released in the last `days`, most popular first, on your services. "all" mixes
     * movies and shows. Popularity on TMDB is a rolling daily score, so this follows what people watch now.
     */
    List<Title> indian(String kind, int days) throws IOException {
        if (!"all".equals(kind)) return discover(kind, indianQuery(kind, days, "sort_by=popularity.desc"));
        List<Title> movies = discover("movie", indianQuery("movie", days, "sort_by=popularity.desc"));
        List<Title> shows = discover("tv", indianQuery("tv", days, "sort_by=popularity.desc"));
        List<Title> out = new ArrayList<>();
        for (int i = 0; i < Math.max(movies.size(), shows.size()); i++) {
            if (i < movies.size()) out.add(movies.get(i));
            if (i < shows.size()) out.add(shows.get(i));
        }
        return out;
    }

    /** Indian releases from the last `days`, newest first (and with some votes, so not empty placeholders). */
    List<Title> newIndian(String kind, int days) throws IOException {
        String date = "movie".equals(kind) ? "primary_release_date" : "first_air_date";
        return discover(kind, indianQuery(kind, days, "sort_by=" + date + ".desc&vote_count.gte=2"));
    }

    private String indianQuery(String kind, int days, String sort) throws IOException {
        String date = "movie".equals(kind) ? "primary_release_date" : "first_air_date";
        String since = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(System.currentTimeMillis() - days * 24 * HOUR));
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        // For shows, also count ones still running (a new season) by matching recent air dates.
        String window = "tv".equals(kind) ? "&air_date.gte=" + since + "&air_date.lte=" + today
                : "&" + date + ".gte=" + since + "&" + date + ".lte=" + today;
        // Main India rows are Hindi; regional languages have their own rows (regional()).
        return "with_origin_country=IN&with_original_language=hi&" + sort + window + onYourApps();
    }

    /**
     * Popular titles in one or more regional languages ("ta", "te|kn", ...), movies and shows together, made or
     * re-released in the last two years, on your services.
     */
    List<Title> regional(String languages) throws IOException {
        String since = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(System.currentTimeMillis() - 730L * 24 * HOUR));
        List<Title> movies = discover("movie", "with_original_language=" + languages
                + "&sort_by=popularity.desc&primary_release_date.gte=" + since + onYourApps());
        List<Title> shows = discover("tv", "with_original_language=" + languages
                + "&sort_by=popularity.desc&air_date.gte=" + since + onYourApps());
        List<Title> out = new ArrayList<>();
        for (int i = 0; i < Math.max(movies.size(), shows.size()); i++) {
            if (i < movies.size()) out.add(movies.get(i));
            if (i < shows.size()) out.add(shows.get(i));
        }
        return out;
    }

    /** Shows whose first episode aired in the last 45 days, most popular first, on your services. */
    List<Title> newShows() throws IOException {
        String since = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(System.currentTimeMillis() - 45L * 24 * HOUR));
        return discover("tv", "sort_by=popularity.desc&first_air_date.gte=" + since + onYourApps());
    }

    /** Movies trending this week on your services. */
    List<Title> trendingMovies() throws IOException {
        return trendingOnYourApps("movie");
    }

    /**
     * "More like this", Hindi-first and only what you can watch. Related titles come from, in order: TMDB's
     * recommendations and similar titles for this exact title, titles sharing its keywords ("stand-up specials"),
     * and (movies) other titles with its two lead performers. All must be in the same original language and stream
     * in India on your apps; those on the same app as this title come first, then your other apps. Only if that
     * leaves fewer than 8 is the row topped up with popular titles sharing its first two genres, same app first.
     */
    List<Title> similar(Title t, Details d) throws IOException {
        if (REGIONAL.contains(t.language)) return new ArrayList<>();
        List<Services.Service> all = new ArrayList<>(java.util.Arrays.asList(Services.ALL));
        List<Services.Service> same = d == null || d.services.isEmpty() ? all : d.services;
        String sameIds = providerIdsFor(same), allIds = providerIdsFor(all);

        // 1. TMDB's picks, each checked for where it streams.
        List<Title> candidates = new ArrayList<>();
        for (String list : new String[]{"recommendations", "similar"}) {
            for (int page = 1; page <= 2; page++) {
                try {
                    candidates.addAll(titles(get(t.type + "/" + t.id + "/" + list, "language=en-US&page=" + page, 24 * HOUR), t.type));
                } catch (IOException ignored) {
                    // One list missing still leaves the others.
                }
            }
        }
        Set<Integer> seen = new java.util.HashSet<>();
        seen.add(t.id);
        List<Title> sameLanguage = new ArrayList<>();
        for (Title c : candidates) if (c.language.equals(t.language) && seen.add(c.id)) sameLanguage.add(c);
        List<Title> onSame = new ArrayList<>(), onOther = new ArrayList<>();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        try {
            List<java.util.concurrent.Future<List<Services.Service>>> checks = new ArrayList<>();
            for (Title c : sameLanguage) checks.add(pool.submit(() -> streamsOn(c)));
            for (int i = 0; i < sameLanguage.size(); i++) {
                try {
                    List<Services.Service> on = checks.get(i).get();
                    if (!java.util.Collections.disjoint(on, same)) onSame.add(sameLanguage.get(i));
                    else if (!java.util.Collections.disjoint(on, all)) onOther.add(sameLanguage.get(i));
                } catch (Exception ignored) {
                    // Can't confirm where it streams: left out.
                }
            }
        } finally {
            pool.shutdown();
        }

        // 2. Same keywords, 3. same lead performers, both also in its main genre (so "sequel" alone doesn't match
        // Stree 2 with Krrish 3). TMDB filters these by app itself.
        // Cartoons and kids' shows only for cartoons and kids' shows (Kota Factory's "india" keyword finds Mighty Little Bheem).
        List<Integer> kidsGenres = new ArrayList<>(java.util.Arrays.asList(16, 10762));
        for (int g : t.genreIds) kidsGenres.remove(Integer.valueOf(g));
        String notKids = kidsGenres.isEmpty() ? "" : "&without_genres=" + join(kidsGenres, ",");
        String lang = "&with_original_language=" + t.language + notKids + (t.genreIds.length > 0 ? "&with_genres=" + t.genreIds[0] : "");
        List<String> related = new ArrayList<>();
        if (d != null && !d.keywordIds.isEmpty()) {
            List<Integer> kws = new ArrayList<>(d.keywordIds.subList(0, Math.min(6, d.keywordIds.size())));
            // TMDB tags stand-up specials with any of these; one means all three.
            if (!java.util.Collections.disjoint(kws, STAND_UP)) for (Integer k : STAND_UP) if (!kws.contains(k)) kws.add(k);
            related.add("with_keywords=" + join(kws, "|") + lang);
        }
        if (d != null && !d.leadCastIds.isEmpty()) related.add("with_cast=" + join(d.leadCastIds, "|") + lang);
        for (String q : related) {
            addNew(onSame, seen, discoverOn(t.type, q, sameIds));
            addNew(onOther, seen, discoverOn(t.type, q, allIds));
        }
        List<Title> kept = new ArrayList<>(onSame);
        kept.addAll(onOther);

        // 4. Same genres, only to top a short row up to 12.
        if (kept.size() < 8 && t.genreIds.length > 0) {
            List<Integer> genres = new ArrayList<>();
            for (int i = 0; i < Math.min(2, t.genreIds.length); i++) genres.add(t.genreIds[i]);
            String q = "with_genres=" + join(genres, ",") + "&with_original_language=" + t.language + notKids;
            List<Title> fill = discoverOn(t.type, q, sameIds);
            fill.addAll(discoverOn(t.type, q, allIds));
            for (Title x : fill) if (kept.size() < 12 && seen.add(x.id)) kept.add(x);
        }
        if (kept.size() > 20) kept = new ArrayList<>(kept.subList(0, 20));
        return kept;
    }

    /** Popular titles matching query that stream in India on these provider ids; empty when ids is. */
    private List<Title> discoverOn(String type, String query, String ids) {
        if (ids.isEmpty()) return new ArrayList<>();
        try {
            return discover(type, "sort_by=popularity.desc&" + query + "&watch_region=" + REGION
                    + "&with_watch_monetization_types=flatrate|free|ads&with_watch_providers=" + ids);
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private static void addNew(List<Title> to, Set<Integer> seen, List<Title> from) {
        for (Title x : from) if (seen.add(x.id)) to.add(x);
    }

    private static String join(List<Integer> values, String separator) {
        StringBuilder b = new StringBuilder();
        for (Integer v : values) b.append(b.length() == 0 ? "" : separator).append(v);
        return b.toString();
    }

    /** Your apps that stream the title in India (subscription, free or with ads). */
    private List<Services.Service> streamsOn(Title t) throws IOException {
        List<Services.Service> on = new ArrayList<>();
        JSONObject r = get(t.type + "/" + t.id + "/watch/providers", "", 24 * HOUR).optJSONObject("results");
        JSONObject in = r == null ? null : r.optJSONObject(REGION);
        for (String kind : new String[]{"flatrate", "free", "ads"}) {
            JSONArray a = in == null ? null : in.optJSONArray(kind);
            for (int i = 0; a != null && i < a.length(); i++) {
                Services.Service s = Services.forProvider(a.optJSONObject(i).optString("provider_name"));
                if (s != null && !on.contains(s)) on.add(s);
            }
        }
        return on;
    }

    /** TMDB provider ids ("119|2336") for these services in India. */
    private String providerIdsFor(List<Services.Service> services) throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        for (String type : new String[]{"movie", "tv"}) {
            JSONArray results = get("watch/providers/" + type, "watch_region=" + REGION, 7 * 24 * HOUR).optJSONArray("results");
            for (int i = 0; results != null && i < results.length(); i++) {
                JSONObject p = results.optJSONObject(i);
                if (p != null && services.contains(Services.forProvider(p.optString("provider_name")))) ids.add(String.valueOf(p.optInt("provider_id")));
            }
        }
        return String.join("|", ids);
    }

    List<Title> search(String q) throws IOException {
        return titles(get("search/multi", "language=en-US&include_adult=false&query=" + Uri.encode(q), HOUR), null);
    }

    /**
     * "&watch_region=IN&with_watch_providers=8|119|..." for your services (Netflix, YouTube, Prime Video, JioHotstar,
     * ZEE5, Sony LIV). Every row uses it, so nothing is recommended that can't be watched on them; if the provider list
     * can't be read, the row fails rather than showing unfiltered titles.
     */
    String onYourApps() throws IOException {
        String ids = providerIds;
        if (ids == null) {
            Set<String> found = new LinkedHashSet<>();
            for (String type : new String[]{"movie", "tv"}) {
                JSONArray results = get("watch/providers/" + type, "watch_region=" + REGION, 7 * 24 * HOUR).optJSONArray("results");
                for (int i = 0; results != null && i < results.length(); i++) {
                    JSONObject p = results.optJSONObject(i);
                    if (p != null && Services.forProvider(p.optString("provider_name")) != null) found.add(String.valueOf(p.optInt("provider_id")));
                }
            }
            if (found.isEmpty()) throw new IOException("no streaming providers for " + REGION);
            ids = String.join("|", found);
            providerIds = ids;
        }
        // Subscription, free and free-with-ads: what the apps play without renting or buying.
        return "&watch_region=" + REGION + "&with_watch_monetization_types=flatrate|free|ads&with_watch_providers=" + ids;
    }

    /** Genre id to name, movies and shows together. */
    Map<Integer, String> genres() {
        Map<Integer, String> g = genres;
        if (g != null) return g;
        g = new HashMap<>();
        for (String type : new String[]{"movie", "tv"}) {
            try {
                JSONArray a = get("genre/" + type + "/list", "language=en-US", 7 * 24 * HOUR).optJSONArray("genres");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null) g.put(o.optInt("id"), o.optString("name"));
                }
            } catch (IOException ignored) {
                // Genres are decoration; a title still shows without them.
            }
        }
        if (!g.isEmpty()) genres = g;
        return g;
    }

    // ---- details --------------------------------------------------------------------------------------------

    static final class Details {
        final Title title;
        final List<String> genres = new ArrayList<>();
        int runtime;
        int seasons;
        String certification = "";
        String trailerKey;
        String imdbId;
        /** IMDb's rating as OMDb reports it ("7.4"), or null. */
        String imdbRating;
        /** Services streaming it in India whose apps Marquee can open, best first. */
        final List<Services.Service> services = new ArrayList<>();
        /** Every flat-rate provider name in India, for the "where to watch" line. */
        final List<String> providerNames = new ArrayList<>();
        /** TMDB keyword ids ("stand-up specials", "heist"), for "More like this". */
        final List<Integer> keywordIds = new ArrayList<>();
        /** The first two people billed (movies only; TMDB can't discover shows by cast). */
        final List<Integer> leadCastIds = new ArrayList<>();

        Details(Title title) {
            this.title = title;
        }
    }

    Details details(Title t) throws IOException {
        String extra = "movie".equals(t.type) ? "release_dates" : "content_ratings";
        JSONObject o = get(t.type + "/" + t.id, "language=en-US&append_to_response=videos,watch/providers,external_ids,keywords,credits," + extra, 24 * HOUR);
        Details d = new Details(t);
        JSONObject kw = o.optJSONObject("keywords");
        JSONArray kws = kw == null ? null : kw.has("keywords") ? kw.optJSONArray("keywords") : kw.optJSONArray("results");
        for (int i = 0; kws != null && i < kws.length(); i++) d.keywordIds.add(kws.optJSONObject(i).optInt("id"));
        JSONArray cast = o.optJSONObject("credits") == null ? null : o.optJSONObject("credits").optJSONArray("cast");
        for (int i = 0; "movie".equals(t.type) && cast != null && i < Math.min(2, cast.length()); i++) {
            d.leadCastIds.add(cast.optJSONObject(i).optInt("id"));
        }
        String imdb = o.optString("imdb_id", "");
        if (imdb.isEmpty() && o.optJSONObject("external_ids") != null) imdb = o.optJSONObject("external_ids").optString("imdb_id", "");
        d.imdbId = imdb.startsWith("tt") ? imdb : null;
        if (d.imdbId == null) android.util.Log.i("Marquee", "No IMDb id for " + t.key());
        d.imdbRating = imdbRating(d.imdbId);
        JSONArray g = o.optJSONArray("genres");
        for (int i = 0; g != null && i < g.length(); i++) d.genres.add(g.optJSONObject(i).optString("name"));
        d.runtime = o.optInt("runtime", 0);
        d.seasons = o.optInt("number_of_seasons", 0);
        d.trailerKey = trailer(o.optJSONObject("videos"));
        d.certification = certification(o, t.type);
        JSONObject region = o.optJSONObject("watch/providers") == null ? null
                : o.optJSONObject("watch/providers").optJSONObject("results");
        region = region == null ? null : region.optJSONObject(REGION);
        JSONArray flat = region == null ? null : region.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length(); i++) {
            String name = flat.optJSONObject(i).optString("provider_name");
            d.providerNames.add(name);
            Services.Service s = Services.forProvider(name);
            if (s != null && !d.services.contains(s)) d.services.add(s);
        }
        return d;
    }

    /**
     * IMDb's rating: IMDb's own daily file first, then OMDb (cached a week) for titles not in it yet; null for titles
     * IMDb hasn't rated or when neither can be read. Never throws, and never falls back to TMDB's rating.
     */
    String imdbRating(String imdbId) {
        if (imdbId == null) return null;
        // IMDb's own daily file first; OMDb (which lags on new releases) only for titles not in it yet.
        String fromImdb = imdb.get(imdbId);
        if (fromImdb != null) return fromImdb;
        String key = prefs.omdbKey();
        if (key.isEmpty()) return null;
        File cache = new File(dir, sha1("omdb:" + imdbId) + ".json");
        // A rating is kept a week; "not rated yet" only 12 hours, since OMDb fills in new releases after IMDb does.
        JSONObject o = cache.exists() ? read(cache) : null;
        if (o != null) {
            String cached = o.optString("imdbRating", "");
            long ttl = cached.isEmpty() || "N/A".equals(cached) ? 12 * HOUR : 7 * 24 * HOUR;
            if (System.currentTimeMillis() - cache.lastModified() >= ttl) o = null;
        }
        if (o == null) {
            try {
                String body = fetch("https://www.omdbapi.com/?i=" + Uri.encode(imdbId) + "&apikey=" + Uri.encode(key));
                o = new JSONObject(body);
                if (!"True".equalsIgnoreCase(o.optString("Response"))) {
                    android.util.Log.w("Marquee", "OMDb: " + o.optString("Error", "no rating") + " (" + imdbId + ")");
                    return null;
                }
                try (FileOutputStream out = new FileOutputStream(cache)) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException | JSONException e) {
                // The message names the HTTP status, never the URL (which holds the key).
                android.util.Log.w("Marquee", "OMDb: " + (e instanceof IOException ? e.getMessage() : "bad reply") + " (" + imdbId + ")");
                o = cache.exists() ? read(cache) : null;
                if (o == null) return null;
            }
        }
        String r = o.optString("imdbRating", "");
        if (r.isEmpty() || "N/A".equals(r)) return null;
        return r;
    }

    private static String trailer(JSONObject videos) {
        JSONArray a = videos == null ? null : videos.optJSONArray("results");
        String fallback = null;
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject v = a.optJSONObject(i);
            if (v == null || !"YouTube".equals(v.optString("site"))) continue;
            String type = v.optString("type");
            if ("Trailer".equals(type) && v.optBoolean("official", true)) return v.optString("key");
            if (fallback == null && ("Trailer".equals(type) || "Teaser".equals(type))) fallback = v.optString("key");
        }
        return fallback;
    }

    private static String certification(JSONObject o, String type) {
        if ("movie".equals(type)) {
            JSONArray a = o.optJSONObject("release_dates") == null ? null : o.optJSONObject("release_dates").optJSONArray("results");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject r = a.optJSONObject(i);
                if (!REGION.equals(r.optString("iso_3166_1"))) continue;
                JSONArray dates = r.optJSONArray("release_dates");
                for (int j = 0; dates != null && j < dates.length(); j++) {
                    String c = dates.optJSONObject(j).optString("certification");
                    if (!c.isEmpty()) return c;
                }
            }
        } else {
            JSONArray a = o.optJSONObject("content_ratings") == null ? null : o.optJSONObject("content_ratings").optJSONArray("results");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject r = a.optJSONObject(i);
                if (REGION.equals(r.optString("iso_3166_1"))) return r.optString("rating");
            }
        }
        return "";
    }

    // ---- transport ------------------------------------------------------------------------------------------

    private static List<Title> titles(JSONObject page, String type) {
        List<Title> out = new ArrayList<>();
        JSONArray a = page.optJSONArray("results");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            Title t = o == null ? null : Title.from(o, type);
            if (t != null && t.poster != null) out.add(t);
        }
        return out;
    }

    private JSONObject get(String path, String query, long ttl) throws IOException {
        File cache = new File(dir, sha1(path + "?" + query) + ".json");
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < ttl) {
            JSONObject cached = read(cache);
            if (cached != null) return cached;
        }
        if (!prefs.hasKey()) {
            JSONObject stale = cache.exists() ? read(cache) : null;
            if (stale != null) return stale;
            throw new NoKeyException();
        }
        try {
            String body = fetch(API + path + "?api_key=" + Uri.encode(prefs.tmdbKey()) + "&" + query);
            JSONObject json = new JSONObject(body);
            File tmp = new File(dir, cache.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(cache);
            return json;
        } catch (IOException | JSONException e) {
            JSONObject stale = cache.exists() ? read(cache) : null;
            if (stale != null) return stale;
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    private static String fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
                return buf.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject read(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
            return new JSONObject(buf.toString("UTF-8"));
        } catch (IOException | JSONException e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
    }

    static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format(Locale.ROOT, "%02x", x));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
