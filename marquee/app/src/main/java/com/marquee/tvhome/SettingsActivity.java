package com.marquee.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Marquee's own settings: the TV's Settings, refreshing posters, Google TV's home, and status lines. */
public final class SettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        LinearLayout items = findViewById(R.id.items);
        add(items, R.string.settings_tv, R.drawable.ic_settings, () -> Actions.openSettings(this));
        add(items, R.string.settings_refresh, R.drawable.ic_movie, () -> {
            App app = App.get(this);
            app.io.execute(app.tmdb::clearCache);
            app.images.clearMemory();
            Toast.makeText(this, R.string.settings_refreshed, Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
        });
        add(items, R.string.settings_stock_home, R.drawable.ic_home, () -> {
            // Google TV's own home, with its recommendations, for ten minutes; Home brings Marquee back after that.
            HomeRedirectService.allowStockUntil = SystemClock.elapsedRealtime() + 10 * 60_000L;
            Actions.start(this, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setPackage("com.google.android.apps.tv.launcherx").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        items.getChildAt(0).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        App app = App.get(this);
        String version = "";
        try {
            PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = p.versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // Our own package is always there.
        }
        ((TextView) findViewById(R.id.status)).setText(String.join("\n",
                getString(app.prefs.hasKey() ? R.string.settings_key_ok : R.string.settings_key_missing),
                getString(HomeHelper.enabled(this) ? R.string.settings_home_ok : R.string.settings_home_off),
                getString(R.string.settings_version, version)));
    }

    private void add(LinearLayout parent, int text, int icon, Runnable action) {
        TextView v = (TextView) LayoutInflater.from(this).inflate(R.layout.item_list, parent, false);
        v.setText(text);
        v.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        v.setOnClickListener(x -> action.run());
        parent.addView(v);
    }
}
