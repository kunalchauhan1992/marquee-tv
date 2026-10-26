package com.marquee.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** Titles saved with "My list", newest first, kept on the TV only. */
final class MyList {
    private static final int MAX = 200;
    private final SharedPreferences sp;

    MyList(Context context) {
        sp = App.get(context).prefs.raw();
    }

    List<Title> all() {
        List<Title> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString("my_list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                Title t = Title.from(a.getJSONObject(i), "movie");
                if (t != null) out.add(t);
            }
        } catch (JSONException e) {
            sp.edit().remove("my_list").apply();
        }
        return out;
    }

    boolean contains(Title t) {
        for (Title s : all()) if (s.key().equals(t.key())) return true;
        return false;
    }

    /** Adds or removes the title; returns true when it is now in the list. */
    boolean toggle(Title t) {
        List<Title> items = all();
        boolean removed = items.removeIf(s -> s.key().equals(t.key()));
        if (!removed) items.add(0, t);
        JSONArray a = new JSONArray();
        for (int i = 0; i < Math.min(items.size(), MAX); i++) a.put(items.get(i).toJson());
        sp.edit().putString("my_list", a.toString()).apply();
        return !removed;
    }
}
