package com.marquee.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Settings that arrive from the PC (SetupReceiver) and survive restarts. */
final class Prefs {
    /** The dock until the PC sends one (--es dock): Netflix, Prime Video, YouTube, JioHotstar, Live TV last. */
    static final List<String> DEFAULT_DOCK = Collections.unmodifiableList(Arrays.asList(
            "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.google.android.youtube.tv",
            "in.startv.hotstar", "com.realtek.tv"));
    /** Never shown as tiles: launchers, and the Acer apps the firmware turns back on at every boot. */
    static final List<String> DEFAULT_HIDDEN = Collections.unmodifiableList(Arrays.asList(
            "com.marquee.tvhome", "com.gathin.tvhome", "com.spocky.projengmenu",
            "com.apps.biz.mt", "com.apps.biz.ds", "com.mk.tv.gallery", "com.mk.tv.caption",
            "com.google.android.youtube.tvmusic", "com.realtek.airplay2", "com.apps.function.audiolink",
            "com.creative.fastscreen.tv", "com.apps.scast", "com.google.android.play.games"));

    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences("marquee", Context.MODE_PRIVATE);
    }

    String tmdbKey() {
        return sp.getString("tmdb_key", "");
    }

    boolean hasKey() {
        return tmdbKey().length() >= 16;
    }

    void setTmdbKey(String key) {
        sp.edit().putString("tmdb_key", key == null ? "" : key.trim()).apply();
    }

    /** OMDb key, for IMDb ratings of titles newer than IMDb's daily file; empty shows no rating for those. */
    String omdbKey() {
        return sp.getString("omdb_key", "");
    }

    void setOmdbKey(String key) {
        sp.edit().putString("omdb_key", key == null ? "" : key.trim()).apply();
    }

    List<String> dock() {
        return listOr("dock", DEFAULT_DOCK);
    }

    void setDock(String commaList) {
        sp.edit().putString("dock", commaList).apply();
    }

    List<String> hidden() {
        return listOr("hidden", DEFAULT_HIDDEN);
    }

    void setHidden(String commaList) {
        sp.edit().putString("hidden", commaList).apply();
    }

    private List<String> listOr(String name, List<String> fallback) {
        String raw = sp.getString(name, "").trim();
        if (raw.isEmpty()) return fallback;
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out.isEmpty() ? fallback : out;
    }

    SharedPreferences raw() {
        return sp;
    }
}
