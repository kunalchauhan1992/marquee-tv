package com.marquee.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.transition.ChangeBounds;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Home: the menu rail, the hero for the focused title, and rows of apps and posters. */
public final class MainActivity extends Activity implements Tiles.Listener {
    enum Page { HOME, MOVIES, SHOWS, APPS, MY_LIST }

    private interface Loader {
        List<Title> load() throws IOException;
    }

    private static final long HERO_DELAY_MS = 220;
    private static final long ROTATE_MS = 9000;
    private static final int APPS_PER_ROW = 5;
    private static final int POSTERS_PER_ROW = 8;

    private App app;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private LinearLayout rail;
    private final List<View> railItems = new ArrayList<>();
    private RecyclerView rows;
    private Rows.Adapter rowsAdapter;
    private ImageView backdrop;
    private TextView heroBadge, heroTitle, heroMeta, heroOverview, btnWatch, btnTrailer, btnList;
    private ImageView wifi;

    private Page page = Page.HOME;
    private int generation;
    private View lastContent;
    private int lastContentRow;
    private boolean railExpanded;
    /** A page asked for focus on its first tile before its tiles had loaded; applied when they arrive. */
    private boolean pendingFocus;
    private int focusRetries;
    /** Whether the current page's rows were requested with a TMDB key; a key arriving later reloads them. */
    private boolean loadedWithKey;

    private Title hero;
    private Tmdb.Details heroDetails;
    private final List<Title> featured = new ArrayList<>();
    private int featuredIndex;
    private Map<String, Tiles.AppEntry> installedApps = Collections.emptyMap();

    private ConnectivityManager cm;
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
            runOnUiThread(() -> showNetwork(caps));
        }

        @Override
        public void onLost(Network n) {
            runOnUiThread(() -> showNetwork(null));
        }
    };

    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            handler.postDelayed(this, ROTATE_MS);
            if (featured.size() < 2 || page != Page.HOME || lastContentRow != 0 || !hasWindowFocus()) return;
            featuredIndex = (featuredIndex + 1) % Math.min(featured.size(), 8);
            showHero(featured.get(featuredIndex));
        }
    };
    private Runnable pendingHero;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = App.get(this);
        setContentView(R.layout.activity_main);
        rail = findViewById(R.id.rail);
        rows = findViewById(R.id.rows);
        backdrop = findViewById(R.id.backdrop);
        heroBadge = findViewById(R.id.heroBadge);
        heroTitle = findViewById(R.id.heroTitle);
        heroMeta = findViewById(R.id.heroMeta);
        heroOverview = findViewById(R.id.heroOverview);
        btnWatch = findViewById(R.id.btnWatch);
        btnTrailer = findViewById(R.id.btnTrailer);
        btnList = findViewById(R.id.btnList);
        wifi = findViewById(R.id.wifi);

        rowsAdapter = new Rows.Adapter();
        // Rows scroll only through onTileFocused (row title at the top). RecyclerView's own scroll-to-focus would
        // fight it and push the row's title out of view.
        LinearLayoutManager lm = new LinearLayoutManager(this) {
            @Override
            public boolean requestChildRectangleOnScreen(RecyclerView parent, View child, android.graphics.Rect rect,
                                                         boolean immediate, boolean focusedChildVisible) {
                return false;
            }
        };
        lm.setItemPrefetchEnabled(true);
        rows.setLayoutManager(lm);
        rows.setItemAnimator(null);
        rows.setAdapter(rowsAdapter);
        rows.setFocusable(false);

        buildRail();
        wireHero();
        findViewById(R.id.btnSearch).setOnClickListener(v -> startActivity(new Intent(this, SearchActivity.class)));
        findViewById(R.id.btnSearch).setOnKeyListener((v, k, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) return btnWatch.requestFocus();
            if (k == KeyEvent.KEYCODE_DPAD_LEFT) return focusRail();
            return k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_UP;
        });
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener(
                (oldFocus, newFocus) -> setRailExpanded(newFocus != null && isInside(rail, newFocus)));

        cm = getSystemService(ConnectivityManager.class);
        try {
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException ignored) {
            // Too many callbacks on a busy system; the icon simply stays as it is.
        }
        showNetwork(cm.getNetworkCapabilities(cm.getActiveNetwork()));
        // Start on the first of your apps, not on the menu.
        showPage(Page.HOME, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        HomeHelper.ensure(this);
        if (app.tmdb.imdb.stale()) app.io.execute(app.tmdb.imdb::refreshIfStale);
        if (!failedRows.isEmpty()) handler.post(retryFailed);
        if (!loadedWithKey && app.prefs.hasKey() && page != Page.APPS && page != Page.MY_LIST) {
            showPage(page, true);
            return;
        }
        refreshApps();
        if (page == Page.HOME || page == Page.MY_LIST) refreshMyList();
        handler.removeCallbacks(rotate);
        handler.postDelayed(rotate, ROTATE_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(rotate);
    }

    /** Off screen (an app is playing): hand the image memory back; it reloads from disk when Home returns. */
    @Override
    protected void onStop() {
        super.onStop();
        app.images.clearMemory();
    }

    @Override
    protected void onDestroy() {
        try {
            cm.unregisterNetworkCallback(networkCallback);
        } catch (RuntimeException ignored) {
            // Never registered.
        }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while Marquee is already up: back to the start of Home, like any TV launcher.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            if (page != Page.HOME || (!loadedWithKey && app.prefs.hasKey())) {
                showPage(Page.HOME, true);
            } else {
                // Back to the start of Home; once the window has its focus back, or the view focus is lost again.
                lastContent = null;
                pendingFocus = true;
                focusRetries = 0;
                rows.scrollToPosition(0);
                applyPendingFocus();
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (page != Page.HOME) showPage(Page.HOME, true);
        else focusRow(0, 0, true);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) app.images.clearMemory();
    }

    // ---- rail -------------------------------------------------------------------------------------------------

    private void buildRail() {
        addRailItem(R.drawable.ic_home, R.string.nav_home, () -> showPage(Page.HOME, true));
        addRailItem(R.drawable.ic_movie, R.string.nav_movies, () -> showPage(Page.MOVIES, true));
        addRailItem(R.drawable.ic_shows, R.string.nav_shows, () -> showPage(Page.SHOWS, true));
        addRailItem(R.drawable.ic_apps, R.string.nav_apps, () -> showPage(Page.APPS, true));
        addRailItem(R.drawable.ic_bookmark, R.string.nav_my_list, () -> showPage(Page.MY_LIST, true));
        addRailItem(R.drawable.ic_input, R.string.nav_inputs, this::showInputs);
        addRailItem(R.drawable.ic_settings, R.string.nav_settings, () -> startActivity(new Intent(this, SettingsActivity.class)));
    }

    private void addRailItem(int icon, int label, Runnable action) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_rail, rail, false);
        ((ImageView) v.findViewById(R.id.railIcon)).setImageResource(icon);
        ((TextView) v.findViewById(R.id.railLabel)).setText(label);
        v.setContentDescription(getString(label));
        v.setOnClickListener(x -> action.run());
        int index = railItems.size();
        v.setOnKeyListener((x, k, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            pendingFocus = false;   // the menu is in use: don't pull focus away from it when tiles load
            if (k == KeyEvent.KEYCODE_DPAD_RIGHT) return focusContent();
            if (k == KeyEvent.KEYCODE_DPAD_LEFT) return true;
            if (k == KeyEvent.KEYCODE_DPAD_UP && index == 0) return true;
            return k == KeyEvent.KEYCODE_DPAD_DOWN && index == railItems.size() - 1;
        });
        railItems.add(v);
        rail.addView(v);
    }

    private boolean focusRail() {
        return railItems.get(page.ordinal()).requestFocus();
    }

    private void setRailExpanded(boolean expanded) {
        if (expanded == railExpanded) return;
        railExpanded = expanded;
        ChangeBounds t = new ChangeBounds();
        t.setDuration(140);
        TransitionManager.beginDelayedTransition((ViewGroup) rail.getParent(), t);
        ViewGroup.LayoutParams lp = rail.getLayoutParams();
        lp.width = Math.round((expanded ? 196 : 64) * getResources().getDisplayMetrics().density);
        rail.setLayoutParams(lp);
        for (View v : railItems) v.findViewById(R.id.railLabel).setVisibility(expanded ? View.VISIBLE : View.GONE);
    }

    private static boolean isInside(View parent, View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) if (p == parent) return true;
        return false;
    }

    // ---- pages ------------------------------------------------------------------------------------------------

    private void showPage(Page p, boolean focus) {
        page = p;
        generation++;
        failedRows.clear();
        loadedWithKey = app.prefs.hasKey();
        lastContent = null;
        lastContentRow = 0;
        for (int i = 0; i < railItems.size(); i++) railItems.get(i).setActivated(i == p.ordinal());

        List<Rows.Row> list = new ArrayList<>();
        Tmdb t = app.tmdb;
        switch (p) {
            // India first: Indian titles (made in India), newest and most popular, all streaming on your apps. The
            // worldwide list stays, further down, for international hits on the same apps.
            case HOME:
                list.add(Rows.Row.apps(getString(R.string.row_your_apps), this));
                list.add(posterRow(R.string.row_trending_india, () -> t.indian("all", 120), true));
                list.add(posterRow(R.string.row_new_india, () -> t.newIndian("movie", 60), false));
                list.add(posterRow(R.string.row_indian_shows, () -> t.indian("tv", 365), false));
                list.add(posterRow(R.string.row_hindi_movies, () -> t.discover("movie",
                        "with_original_language=hi&sort_by=popularity.desc" + t.onYourApps()), false));
                list.add(posterRow(R.string.row_trending, t::trending, false));
                break;
            case MOVIES:
                list.add(posterRow(R.string.row_trending_india_movies, () -> t.indian("movie", 120), true));
                list.add(posterRow(R.string.row_new_india, () -> t.newIndian("movie", 60), false));
                list.add(posterRow(R.string.row_hindi_movies, () -> t.discover("movie",
                        "with_original_language=hi&sort_by=popularity.desc" + t.onYourApps()), false));
                list.add(posterRow(R.string.row_top_rated_india, () -> t.discover("movie",
                        "with_origin_country=IN&with_original_language=hi&sort_by=vote_average.desc&vote_count.gte=150" + t.onYourApps()), false));
                list.add(genreRow(R.string.row_action, "movie", 28));
                list.add(genreRow(R.string.row_comedy, "movie", 35));
                list.add(genreRow(R.string.row_thriller, "movie", 53));
                list.add(genreRow(R.string.row_romance, "movie", 10749));
                list.add(genreRow(R.string.row_family, "movie", 10751));
                list.add(posterRow(R.string.row_popular_movies, () -> t.trendingMovies(), false));
                break;
            case SHOWS:
                list.add(posterRow(R.string.row_indian_shows, () -> t.indian("tv", 365), true));
                list.add(posterRow(R.string.row_new_india_shows, () -> t.newIndian("tv", 60), false));
                list.add(posterRow(R.string.row_hindi_shows, () -> t.discover("tv",
                        "with_original_language=hi&sort_by=popularity.desc" + t.onYourApps()), false));
                list.add(posterRow(R.string.row_top_rated_india_shows, () -> t.discover("tv",
                        "with_origin_country=IN&with_original_language=hi&sort_by=vote_average.desc&vote_count.gte=50" + t.onYourApps()), false));
                list.add(genreRow(R.string.row_crime, "tv", 80));
                list.add(genreRow(R.string.row_drama, "tv", 18));
                list.add(genreRow(R.string.row_comedy, "tv", 35));
                list.add(genreRow(R.string.row_reality, "tv", 10764));
                list.add(posterRow(R.string.row_top_shows, () -> t.trendingShows(), false));
                break;
            case APPS:
            case MY_LIST:
                break;
        }
        rowsAdapter.setRows(list);
        rows.scrollToPosition(0);
        if (p == Page.HOME) refreshMyList();
        if (p == Page.APPS || p == Page.HOME) refreshApps();
        if (p == Page.MY_LIST) refreshMyList();
        pendingFocus = focus;
        if (focus) applyPendingFocus();
    }

    /** Focuses the first tile once one exists; rows load asynchronously, so this runs again as each one arrives. */
    private void applyPendingFocus() {
        if (!pendingFocus) return;
        rows.post(() -> {
            // Wait for the first row's own tiles (your apps on Home) rather than falling through to a later row.
            RecyclerView.ViewHolder first = rows.findViewHolderForAdapterPosition(0);
            RecyclerView firstList = first == null ? null : ((Rows.Adapter.Holder) first).list;
            boolean ready = firstList != null && (firstList.getVisibility() != View.VISIBLE
                    || (firstList.getChildCount() > 0 && firstList.getChildAt(0).isFocusable()));
            if (!ready) {
                // The row was just filled but not laid out yet: try again shortly (up to ~3 s).
                if (focusRetries++ < 30) rows.postDelayed(this::applyPendingFocus, 100);
                return;
            }
            focusRetries = 0;
            if (pendingFocus && focusRow(0, 0, true) && getCurrentFocus() != null && !isInside(rail, getCurrentFocus())) {
                pendingFocus = false;
            }
        });
    }

    /** Regional languages, each in its own row, at the bottom of every page (the main rows are Hindi). */
    private void addRegionalRows(List<Rows.Row> list) {
        Tmdb t = app.tmdb;
        list.add(posterRow(R.string.row_tamil, () -> t.regional("ta"), false));
        list.add(posterRow(R.string.row_telugu, () -> t.regional("te"), false));
        list.add(posterRow(R.string.row_malayalam, () -> t.regional("ml"), false));
        list.add(posterRow(R.string.row_kannada, () -> t.regional("kn"), false));
        list.add(posterRow(R.string.row_more_regional, () -> t.regional("bn|mr|pa|gu"), false));
    }

    private Rows.Row genreRow(int title, String type, int genre) {
        return posterRow(title, () -> app.tmdb.discover(type, "with_genres=" + genre + "&with_origin_country=IN&with_original_language=hi"
                + "&sort_by=popularity.desc" + app.tmdb.onYourApps()), false);
    }

    private Rows.Row posterRow(int title, Loader loader, boolean feedsHero) {
        Rows.Row row = Rows.Row.posters(this, getString(title), this);
        loadRow(row, loader, feedsHero, generation);
        return row;
    }

    /** Rows that couldn't load (no internet yet, a TMDB hiccup), with how to load them again. */
    private final Map<Rows.Row, Runnable> failedRows = new java.util.LinkedHashMap<>();
    private final Runnable retryFailed = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(this);
            if (failedRows.isEmpty()) return;
            List<Runnable> again = new ArrayList<>(failedRows.values());
            failedRows.clear();
            for (Runnable r : again) r.run();
            // Still failing after this attempt: loadRow puts the row back and schedules the next try.
        }
    };

    private void loadRow(Rows.Row row, Loader loader, boolean feedsHero, int gen) {
        app.io.execute(() -> {
            List<Title> titles = null;
            String message = null;
            try {
                titles = loader.load();
                if (titles.isEmpty()) message = getString(R.string.no_results);
            } catch (Tmdb.NoKeyException e) {
                message = getString(R.string.no_key);
            } catch (IOException e) {
                message = getString(online() ? R.string.row_failed : R.string.offline);
            }
            List<Title> result = titles;
            String msg = message;
            boolean retry = result == null && !(msg != null && msg.equals(getString(R.string.no_key)));
            app.main.post(() -> {
                if (gen != generation) return;
                if (retry) {
                    failedRows.put(row, () -> loadRow(row, loader, feedsHero, gen));
                    handler.removeCallbacks(retryFailed);
                    handler.postDelayed(retryFailed, 30_000);
                    if (row.posters.titles() != null && !row.posters.titles().isEmpty()) return;  // keep what it showed
                }
                // Only a row whose message appears or goes is rebound; tiles update in place, keeping focus.
                boolean messageChanged = (row.message == null) != (msg == null);
                row.message = msg;
                row.posters.setTitles(result == null ? Collections.emptyList() : result);
                if (messageChanged) rowsAdapter.changed(row);
                applyPendingFocus();
                if (feedsHero && result != null && !result.isEmpty() && featured.isEmpty()) {
                    featured.addAll(result);
                    if (hero == null) showHero(result.get(0));
                }
            });
        });
    }

    /** Your apps on Home, every app on the Apps page; refreshed on resume so installs and removals show up. */
    private void refreshApps() {
        int gen = generation;
        app.io.execute(() -> {
            Map<String, Tiles.AppEntry> all = Tiles.loadApps(this, app.prefs.hidden());
            List<Tiles.AppEntry> dock = new ArrayList<>();
            for (String pkg : app.prefs.dock()) if (all.containsKey(pkg)) dock.add(all.get(pkg));
            app.main.post(() -> {
                if (gen != generation) return;
                installedApps = all;
                List<Rows.Row> list = rowsAdapter.rows();
                if (page == Page.HOME && !list.isEmpty() && list.get(0).apps != null) {
                    list.get(0).apps.setApps(dock);
                    applyPendingFocus();
                } else if (page == Page.APPS) {
                    List<Tiles.AppEntry> apps = new ArrayList<>(all.values());
                    List<Rows.Row> appRows = new ArrayList<>();
                    for (int i = 0; i < apps.size(); i += APPS_PER_ROW) {
                        Rows.Row r = Rows.Row.apps(i == 0 ? getString(R.string.row_all_apps) : "", this);
                        r.apps.setApps(apps.subList(i, Math.min(apps.size(), i + APPS_PER_ROW)));
                        appRows.add(r);
                    }
                    boolean hadFocus = lastContent != null;
                    rowsAdapter.setRows(appRows);
                    if (hadFocus) rows.post(() -> focusRow(lastContentRow, 0, false));
                    applyPendingFocus();
                }
            });
        });
    }

    private void refreshMyList() {
        List<Title> saved = new MyList(this).all();
        List<Rows.Row> list = new ArrayList<>(rowsAdapter.rows());
        if (page == Page.HOME) {
            Rows.Row existing = null;
            for (Rows.Row r : list) if (getString(R.string.row_my_list).equals(r.title)) existing = r;
            if (saved.isEmpty()) {
                if (existing != null) {
                    list.remove(existing);
                    rowsAdapter.setRows(list);
                }
                return;
            }
            if (existing == null) {
                existing = Rows.Row.posters(this, getString(R.string.row_my_list), this);
                list.add(Math.min(2, list.size()), existing);
                rowsAdapter.setRows(list);
            }
            existing.posters.setTitles(saved);
        } else if (page == Page.MY_LIST) {
            List<Rows.Row> out = new ArrayList<>();
            if (saved.isEmpty()) {
                Rows.Row r = Rows.Row.posters(this, getString(R.string.row_my_list), this);
                r.message = getString(R.string.my_list_empty);
                r.posters.setTitles(Collections.emptyList());
                out.add(r);
            }
            for (int i = 0; i < saved.size(); i += POSTERS_PER_ROW) {
                Rows.Row r = Rows.Row.posters(this, i == 0 ? getString(R.string.row_my_list) : "", this);
                r.posters.setTitles(saved.subList(i, Math.min(saved.size(), i + POSTERS_PER_ROW)));
                out.add(r);
            }
            rowsAdapter.setRows(out);
        }
    }

    // ---- focus between rows -------------------------------------------------------------------------------------

    @Override
    public void onTileFocused(View tile, int position, Object item) {
        pendingFocus = false;
        lastContent = tile;
        int row = rowOf(tile);
        lastContentRow = row;
        View rowView = rows.getLayoutManager() == null ? null : rows.getLayoutManager().findViewByPosition(row);
        if (rowView != null) rows.smoothScrollBy(0, rowView.getTop() - rows.getPaddingTop());
        if (item instanceof Title) {
            Title t = (Title) item;
            if (pendingHero != null) handler.removeCallbacks(pendingHero);
            pendingHero = () -> showHero(t);
            handler.postDelayed(pendingHero, HERO_DELAY_MS);
        }
    }

    @Override
    public void onTileClicked(Object item) {
        if (item instanceof Title) DetailsActivity.open(this, (Title) item);
        else if (item instanceof Tiles.AppEntry) Actions.launchApp(this, ((Tiles.AppEntry) item).pkg);
    }

    @Override
    public boolean onTileKey(View tile, int position, int keyCode) {
        int row = rowOf(tile);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return position == 0 && focusRail();
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                RecyclerView.Adapter<?> a = tile.getParent() instanceof RecyclerView ? ((RecyclerView) tile.getParent()).getAdapter() : null;
                return a != null && position == a.getItemCount() - 1;
            }
            case KeyEvent.KEYCODE_DPAD_UP:
                if (row <= 0) return hero != null ? btnWatch.requestFocus() : findViewById(R.id.btnSearch).requestFocus();
                return focusRow(row - 1, centerX(tile), false) || true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return focusRow(row + 1, centerX(tile), false) || true;
            default:
                return false;
        }
    }

    private int rowOf(View tile) {
        View list = (View) tile.getParent();
        View rowView = list == null ? null : (View) list.getParent();
        RecyclerView.ViewHolder vh = rowView == null ? null : rows.findContainingViewHolder(rowView);
        return vh == null ? 0 : vh.getBindingAdapterPosition();
    }

    private static int centerX(View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return loc[0] + v.getWidth() / 2;
    }

    /** Focuses the tile in row target nearest to screen x; skips message rows; false past the last row. */
    private boolean focusRow(int target, int x, boolean first) {
        int count = rowsAdapter.getItemCount();
        if (target < 0 || target >= count) return false;
        RecyclerView.ViewHolder vh = rows.findViewHolderForAdapterPosition(target);
        if (vh == null) {
            rows.scrollToPosition(target);
            rows.post(() -> {
                if (rows.findViewHolderForAdapterPosition(target) != null) focusRow(target, x, first);
            });
            return true;
        }
        RecyclerView list = ((Rows.Adapter.Holder) vh).list;
        if (list.getVisibility() != View.VISIBLE || list.getChildCount() == 0 || !list.getChildAt(0).isFocusable()) {
            int next = target + (target >= lastContentRow ? 1 : -1);
            return next != target && focusRow(next, x, first);
        }
        View best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            if (!c.isFocusable()) continue;
            int d = first ? i : Math.abs(centerX(c) - x);
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return best != null && best.requestFocus();
    }

    private boolean focusContent() {
        if (lastContent != null && lastContent.isAttachedToWindow() && lastContent.isShown() && lastContent.requestFocus()) return true;
        return focusRow(lastContent == null ? 0 : lastContentRow, 0, true) || focusRow(0, 0, true);
    }

    // ---- hero -------------------------------------------------------------------------------------------------

    private void wireHero() {
        View.OnKeyListener keys = (v, k, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) return focusContent();
            if (k == KeyEvent.KEYCODE_DPAD_UP) return findViewById(R.id.btnSearch).requestFocus();
            if (k == KeyEvent.KEYCODE_DPAD_LEFT && v == btnWatch) return focusRail();
            return k == KeyEvent.KEYCODE_DPAD_RIGHT && v == btnList;
        };
        btnWatch.setOnKeyListener(keys);
        btnTrailer.setOnKeyListener(keys);
        btnList.setOnKeyListener(keys);
        btnWatch.setOnClickListener(v -> {
            if (hero == null) return;
            Tmdb.Details d = heroDetails;
            Services.Service s = d == null ? null : firstInstalled(d);
            if (s != null) Actions.watch(this, s, hero);
            else DetailsActivity.open(this, hero);
        });
        btnTrailer.setOnClickListener(v -> {
            if (hero == null) return;
            if (heroDetails != null) {
                Actions.trailer(this, heroDetails.trailerKey);
                return;
            }
            Title t = hero;
            app.io.execute(() -> {
                String key = null;
                try {
                    key = app.tmdb.details(t).trailerKey;
                } catch (IOException ignored) {
                    // Falls through to "no trailer".
                }
                String k = key;
                app.main.post(() -> Actions.trailer(this, k));
            });
        });
        btnList.setOnClickListener(v -> {
            if (hero == null) return;
            boolean in = new MyList(this).toggle(hero);
            showListState(in);
            refreshMyList();
        });
        btnWatch.setVisibility(View.INVISIBLE);
        btnTrailer.setVisibility(View.INVISIBLE);
        btnList.setVisibility(View.INVISIBLE);
    }

    private Services.Service firstInstalled(Tmdb.Details d) {
        for (Services.Service s : d.services) if (s.installed(this)) return s;
        return null;
    }

    private void showHero(Title t) {
        hero = t;
        heroDetails = null;
        heroTitle.setText(t.name);
        heroBadge.setText("");
        heroOverview.setText(t.overview);
        heroMeta.setText(Format.meta(this, t, null, null));
        app.images.load(Tmdb.backdrop(t.backdrop), backdrop, 1280);
        btnWatch.setVisibility(View.VISIBLE);
        btnTrailer.setVisibility(View.VISIBLE);
        btnList.setVisibility(View.VISIBLE);
        showListState(new MyList(this).contains(t));
        app.io.execute(() -> {
            Map<Integer, String> genres = app.tmdb.genres();
            Tmdb.Details d = null;
            try {
                d = app.tmdb.details(t);
            } catch (IOException ignored) {
                // The hero keeps what the row already knew.
            }
            Tmdb.Details details = d;
            app.main.post(() -> {
                if (hero != t) return;
                heroDetails = details;
                heroMeta.setText(Format.meta(this, t, genres, details));
                Services.Service s = details == null ? null : firstInstalled(details);
                heroBadge.setText(s == null ? "" : getString(R.string.on_service, s.label));
            });
        });
    }

    private void showListState(boolean inList) {
        btnList.setText(inList ? R.string.in_list : R.string.add_to_list);
        btnList.setCompoundDrawablesRelativeWithIntrinsicBounds(inList ? R.drawable.ic_check : R.drawable.ic_add, 0, 0, 0);
    }

    // ---- inputs -----------------------------------------------------------------------------------------------

    private void showInputs() {
        Dialog dialog = new Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.dialog_bg);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText(R.string.inputs_title);
        title.setTextSize(18);
        title.setPadding(pad / 2, 0, 0, pad / 2);
        box.addView(title);

        TvInputManager tim = getSystemService(TvInputManager.class);
        List<TvInputInfo> inputs = tim == null ? Collections.emptyList() : tim.getTvInputList();
        int added = 0;
        for (TvInputInfo info : inputs) {
            if (!info.isPassthroughInput() || info.getParentId() != null || info.isHidden(this)) continue;
            CharSequence label = info.loadCustomLabel(this);
            if (label == null || label.length() == 0) label = info.loadLabel(this);
            String id = info.getId();
            int icon = info.getType() == TvInputInfo.TYPE_HDMI ? R.drawable.ic_input : R.drawable.ic_av;
            box.addView(listItem(String.valueOf(label), icon, () -> {
                dialog.dismiss();
                Actions.openInput(this, id);
            }));
            added++;
        }
        if (Services.installed(this, Actions.LIVE_TV_PKG)) {
            box.addView(listItem(getString(R.string.live_tv), R.drawable.ic_livetv, () -> {
                dialog.dismiss();
                Actions.launchApp(this, Actions.LIVE_TV_PKG);
            }));
            added++;
        }
        if (added == 0) {
            TextView none = new TextView(this);
            none.setText(R.string.no_inputs);
            box.addView(none);
        }
        dialog.setContentView(box);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setGravity(Gravity.START | Gravity.TOP);
            w.getAttributes().x = Math.round(76 * getResources().getDisplayMetrics().density);
            w.getAttributes().y = Math.round(200 * getResources().getDisplayMetrics().density);
            w.setLayout(Math.round(300 * getResources().getDisplayMetrics().density), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private View listItem(String text, int icon, Runnable action) {
        TextView v = (TextView) LayoutInflater.from(this).inflate(R.layout.item_list, null, false);
        v.setText(text);
        v.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        v.setOnClickListener(x -> action.run());
        return v;
    }

    // ---- network ------------------------------------------------------------------------------------------------

    private boolean online() {
        NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void showNetwork(NetworkCapabilities caps) {
        boolean ethernet = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        wifi.setImageResource(ethernet ? R.drawable.ic_ethernet : R.drawable.ic_wifi);
        boolean validated = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        int color = caps == null ? R.color.text_3 : validated ? R.color.online : R.color.warn;
        wifi.setColorFilter(getColor(color));
        // The internet just came up (after boot, Wi-Fi and secure DNS take a few seconds): load what failed.
        if (validated && !failedRows.isEmpty()) {
            handler.removeCallbacks(retryFailed);
            handler.postDelayed(retryFailed, 1500);
        }
    }
}
