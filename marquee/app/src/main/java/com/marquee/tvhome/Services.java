package com.marquee.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;

import java.util.Locale;

/** The streaming services Marquee knows how to open, matched to TMDB's (JustWatch's) provider names. */
final class Services {
    private Services() {}

    static final class Service {
        final String label;
        final String pkg;
        /** Lower-case fragments of the provider names TMDB uses for this service. */
        final String[] names;

        Service(String label, String pkg, String... names) {
            this.label = label;
            this.pkg = pkg;
            this.names = names;
        }

        boolean installed(Context context) {
            return Services.installed(context, pkg);
        }
    }

    static final Service NETFLIX = new Service("Netflix", "com.netflix.ninja", "netflix");
    static final Service PRIME = new Service("Prime Video", "com.amazon.amazonvideo.livingroom", "amazon prime video", "prime video");
    static final Service HOTSTAR = new Service("JioHotstar", "in.startv.hotstar", "hotstar");
    static final Service YOUTUBE = new Service("YouTube", "com.google.android.youtube.tv", "youtube");
    static final Service ZEE5 = new Service("ZEE5", "com.graymatrix.did", "zee5");
    static final Service SONYLIV = new Service("Sony LIV", "com.sonyliv", "sony liv", "sonyliv");

    static final Service[] ALL = {NETFLIX, PRIME, HOTSTAR, YOUTUBE, ZEE5, SONYLIV};

    /** The service a TMDB provider name belongs to, or null if Marquee doesn't open it. */
    static Service forProvider(String providerName) {
        String n = providerName == null ? "" : providerName.toLowerCase(Locale.ROOT);
        // "Netflix basic with Ads", "Amazon Prime Video with Ads" and "YouTube Premium" still open the same app.
        for (Service s : ALL) for (String f : s.names) if (n.contains(f)) return s;
        return null;
    }

    static boolean installed(Context context, String pkg) {
        try {
            return context.getPackageManager().getApplicationInfo(pkg, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
