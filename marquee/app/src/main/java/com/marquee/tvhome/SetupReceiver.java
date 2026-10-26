package com.marquee.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Setup from the PC over ADB (the manifest only lets the shell and the system send here):
 *   am broadcast -a com.marquee.tvhome.SETUP -n com.marquee.tvhome/.SetupReceiver --es key KEY
 *   ... --es dock pkg1,pkg2   --es hidden pkg1,pkg2   --ez refresh true
 * The key is never logged.
 */
public final class SetupReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        App app = App.get(context);
        String key = intent.getStringExtra("key");
        if (key != null) {
            app.prefs.setTmdbKey(key);
            app.tmdb.clearCache();
            Log.i("Marquee", "TMDB key " + (app.prefs.hasKey() ? "saved" : "cleared"));
        }
        String omdb = intent.getStringExtra("omdb");
        if (omdb != null) {
            app.prefs.setOmdbKey(omdb);
            Log.i("Marquee", "OMDb key " + (omdb.trim().isEmpty() ? "cleared" : "saved"));
        }
        String dock = intent.getStringExtra("dock");
        if (dock != null) app.prefs.setDock(dock);
        String hidden = intent.getStringExtra("hidden");
        if (hidden != null) app.prefs.setHidden(hidden);
        if (intent.getBooleanExtra("refresh", false)) app.tmdb.clearCache();
        setResultData("ok");
    }
}
