package com.marquee.tvhome;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps HomeRedirectService on. A force stop (TV Manager's "close apps") takes an app's accessibility service off
 * the enabled list; with WRITE_SECURE_SETTINGS granted over ADB, Marquee writes itself back when it next starts.
 * The old launcher's Home helper is taken off at the same time: two helpers would race for the Home screen.
 */
final class HomeHelper {
    private HomeHelper() {}

    static final String SERVICE = "com.marquee.tvhome/com.marquee.tvhome.HomeRedirectService";
    private static final String SERVICE_SHORT = "com.marquee.tvhome/.HomeRedirectService";
    private static final String OLD_HELPER = "com.gathin.tvhome/";

    static boolean enabled(Context context) {
        String list = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return list != null && (list.contains(SERVICE) || list.contains(SERVICE_SHORT))
                && Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
    }

    static void ensure(Context context) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return;
        String list = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        List<String> keep = new ArrayList<>();
        boolean ours = false;
        if (list != null) for (String s : list.split(":")) {
            if (s.isEmpty() || s.startsWith(OLD_HELPER)) continue;
            if (s.equals(SERVICE) || s.equals(SERVICE_SHORT)) ours = true;
            keep.add(s);
        }
        if (!ours) keep.add(SERVICE);
        String want = String.join(":", keep);
        try {
            if (!want.equals(list)) Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, want);
            if (Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
        } catch (SecurityException ignored) {
            // The grant was revoked; Settings shows the helper as off and says how to turn it on.
        }
    }
}
