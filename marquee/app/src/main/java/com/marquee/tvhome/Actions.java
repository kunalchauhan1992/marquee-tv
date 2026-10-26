package com.marquee.tvhome;

import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.tv.TvContract;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Everything that leaves Marquee: apps, "Watch now", trailers, inputs, Settings. */
final class Actions {
    private Actions() {}

    static final String LIVE_TV_PKG = "com.realtek.tv";

    static boolean launchApp(Context context, String pkg) {
        PackageManager pm = context.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) {
            Toast.makeText(context, context.getString(R.string.app_missing, pkg), Toast.LENGTH_SHORT).show();
            return false;
        }
        return start(context, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    /**
     * Opens the title in the service's app as closely as that app allows (measured on TV 2): JioHotstar and ZEE5
     * answer a search, YouTube, Netflix and Prime Video answer their own web links; anything else opens the app.
     */
    static void watch(Context context, Services.Service s, Title t) {
        String q = Uri.encode(t.name);
        List<Intent> tries = new ArrayList<>();
        if (s == Services.YOUTUBE) {
            tries.add(view("https://www.youtube.com/results?search_query=" + q, s.pkg));
        } else if (s == Services.NETFLIX) {
            tries.add(view("https://www.netflix.com/search?q=" + q, s.pkg));
        } else if (s == Services.PRIME) {
            tries.add(view("https://app.primevideo.com/search?phrase=" + q, s.pkg));
        }
        tries.add(new Intent(Intent.ACTION_SEARCH).setPackage(s.pkg).putExtra(SearchManager.QUERY, t.name));
        PackageManager pm = context.getPackageManager();
        for (Intent i : tries) {
            if (i.resolveActivity(pm) != null && start(context, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return;
        }
        launchApp(context, s.pkg);
    }

    static void trailer(Context context, String youtubeKey) {
        if (youtubeKey == null) {
            Toast.makeText(context, R.string.no_trailer, Toast.LENGTH_SHORT).show();
            return;
        }
        // In a window over Marquee; Back returns to where it was opened from.
        TrailerActivity.open(context, youtubeKey);
    }

    /** HDMI and AV through the TV input framework, sent to the Live TV app first, as the TV's own menu does. */
    static void openInput(Context context, String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, TvContract.Channels.CONTENT_ITEM_TYPE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Services.installed(context, LIVE_TV_PKG) && start(context, new Intent(i).setPackage(LIVE_TV_PKG))) return;
        start(context, i);
    }

    static void openSettings(Context context) {
        start(context, new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static Intent view(String url, String pkg) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg);
    }

    static boolean start(Context context, Intent i) {
        try {
            context.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}
