package com.marquee.tvhome;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-wide singletons: one small thread pool for network and disk, the TMDB client and the image loader. */
public final class App extends Application {
    ExecutorService io;
    Handler main;
    Prefs prefs;
    Tmdb tmdb;
    ImageLoader images;

    @Override
    public void onCreate() {
        super.onCreate();
        io = Executors.newFixedThreadPool(4);
        main = new Handler(Looper.getMainLooper());
        prefs = new Prefs(this);
        tmdb = new Tmdb(this, prefs);
        images = new ImageLoader(this, io, main);
        io.execute(images::trimDisk);
        // IMDb's ratings file, at most once a day, in the background (the trailer process has no use for it).
        if (!getPackageName().equals(currentProcessName())) return;
        io.execute(tmdb.imdb::refreshIfStale);
    }

    private static String currentProcessName() {
        return android.os.Build.VERSION.SDK_INT >= 28 ? Application.getProcessName() : "";
    }

    static App get(Context context) {
        return (App) context.getApplicationContext();
    }
}
