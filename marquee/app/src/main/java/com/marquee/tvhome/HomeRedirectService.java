package com.marquee.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/**
 * Whenever Google TV's home screen comes up (the Home button, the end of an app, a cold boot), Marquee replaces it.
 * Only window changes from that one package are delivered here, and no keys are filtered, so held volume keys keep
 * repeating. "Open Google TV home" in Marquee's settings pauses this for a few minutes.
 */
public final class HomeRedirectService extends AccessibilityService {
    private static final String STOCK = "com.google.android.apps.tv.launcherx";
    static volatile long allowStockUntil;

    @Override
    protected void onServiceConnected() {
        // At boot, and after an update or TV Manager's "close apps", Google TV's home is often already on screen when
        // this service binds, so no window change arrives. Only that case is redirected: never an app being watched.
        android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
        boolean stockOnScreen = root != null && STOCK.contentEquals(root.getPackageName() == null ? "" : root.getPackageName());
        if (stockOnScreen || (root == null && SystemClock.elapsedRealtime() < 5 * 60_000L)) goHome();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (!STOCK.contentEquals(e.getPackageName() == null ? "" : e.getPackageName())) return;
        CharSequence cls = e.getClassName();
        if (cls == null || !cls.toString().endsWith("HomeActivity")) return;
        if (SystemClock.elapsedRealtime() < allowStockUntil) return;
        goHome();
    }

    private void goHome() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClassName(this, MainActivity.class.getName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        Actions.start(this, i);
    }

    @Override
    public void onInterrupt() {
    }
}
