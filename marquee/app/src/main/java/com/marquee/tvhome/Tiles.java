package com.marquee.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Poster and app tiles, shared by Home, details, search and My list. */
final class Tiles {
    private Tiles() {}

    /** What a row tells its screen about focus, clicks and arrow keys on its tiles. */
    interface Listener {
        void onTileFocused(View tile, int position, Object item);

        void onTileClicked(Object item);

        /** True when the screen handled the key (moving between rows, to the menu…). */
        boolean onTileKey(View tile, int position, int keyCode);
    }

    // ---- posters ----------------------------------------------------------------------------------------------

    static final class PosterAdapter extends RecyclerView.Adapter<PosterAdapter.Holder> {
        private static final int SKELETONS = 8;
        private final Listener listener;
        private final ImageLoader images;
        private final int widthPx;
        /** null while loading: grey placeholder tiles keep the row's height. */
        private List<Title> titles;

        PosterAdapter(Context context, Listener listener) {
            this.listener = listener;
            images = App.get(context).images;
            widthPx = Math.round(96 * context.getResources().getDisplayMetrics().density * 1.1f);
            setHasStableIds(true);
        }

        void setTitles(List<Title> titles) {
            this.titles = titles;
            notifyDataSetChanged();
        }

        List<Title> titles() {
            return titles;
        }

        @Override
        public int getItemCount() {
            return titles == null ? SKELETONS : titles.size();
        }

        @Override
        public long getItemId(int position) {
            return titles == null ? -1 - position : titles.get(position).key().hashCode();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_poster, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            if (titles == null) {
                h.itemView.setFocusable(false);
                h.fallback.setText("");
                images.load(null, h.image, widthPx);
                h.title = null;
                return;
            }
            Title t = titles.get(position);
            h.title = t;
            h.itemView.setFocusable(true);
            h.fallback.setText(t.name);
            h.itemView.setContentDescription(t.name);
            images.load(Tmdb.poster(t.poster), h.image, widthPx);
        }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView fallback;
            final ImageView image;
            Title title;

            Holder(View v) {
                super(v);
                fallback = v.findViewById(R.id.posterFallback);
                image = v.findViewById(R.id.posterImage);
                // Rounded corners: the poster is clipped to the tile's rounded background (tile_bg).
                v.findViewById(R.id.card).setClipToOutline(true);
                v.setOnFocusChangeListener((view, has) -> {
                    if (has && title != null) listener.onTileFocused(view, getBindingAdapterPosition(), title);
                });
                v.setOnClickListener(view -> {
                    if (title != null) listener.onTileClicked(title);
                });
                v.setOnKeyListener((view, keyCode, e) -> e.getAction() == KeyEvent.ACTION_DOWN
                        && listener.onTileKey(view, getBindingAdapterPosition(), keyCode));
            }
        }
    }

    // ---- apps -------------------------------------------------------------------------------------------------

    static final class AppEntry {
        final String pkg;
        final String label;
        final Drawable banner;
        final boolean isBanner;

        AppEntry(String pkg, String label, Drawable banner, boolean isBanner) {
            this.pkg = pkg;
            this.label = label;
            this.banner = banner;
            this.isBanner = isBanner;
        }
    }

    /** Every TV app with a launcher entry, by name, minus the hidden ones. */
    static Map<String, AppEntry> loadApps(Context context, List<String> hidden) {
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> found = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        List<AppEntry> list = new ArrayList<>();
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (hidden.contains(pkg)) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            boolean isBanner = banner != null;
            if (banner == null) banner = ri.activityInfo.loadIcon(pm);
            list.add(new AppEntry(pkg, String.valueOf(ri.loadLabel(pm)), banner, isBanner));
        }
        Collator c = Collator.getInstance();
        list.sort((a, b) -> c.compare(a.label, b.label));
        Map<String, AppEntry> out = new LinkedHashMap<>();
        for (AppEntry a : list) if (!out.containsKey(a.pkg)) out.put(a.pkg, a);
        return out;
    }

    static final class AppsAdapter extends RecyclerView.Adapter<AppsAdapter.Holder> {
        private final Listener listener;
        private List<AppEntry> apps = new ArrayList<>();

        AppsAdapter(Listener listener) {
            this.listener = listener;
            // Stable ids keep the focused tile's view (and its focus) through an update of the row.
            setHasStableIds(true);
        }

        /** Rebinds only when the apps actually changed: Home reloads this list on every resume. */
        void setApps(List<AppEntry> apps) {
            if (samePackages(this.apps, apps)) {
                this.apps = apps;
                return;
            }
            this.apps = apps;
            notifyDataSetChanged();
        }

        private static boolean samePackages(List<AppEntry> a, List<AppEntry> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!a.get(i).pkg.equals(b.get(i).pkg)) return false;
            return !a.isEmpty();
        }

        @Override
        public long getItemId(int position) {
            return apps.get(position).pkg.hashCode();
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }

        /** Posters are type 0; rows share one pool, so app tiles must never be handed to a poster row. */
        @Override
        public int getItemViewType(int position) {
            return 1;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            AppEntry a = apps.get(position);
            h.app = a;
            h.itemView.setContentDescription(a.label);
            h.image.setImageDrawable(a.banner);
            h.image.setScaleType(a.isBanner ? ImageView.ScaleType.FIT_XY : ImageView.ScaleType.FIT_CENTER);
            int pad = a.isBanner ? 0 : Math.round(18 * h.itemView.getResources().getDisplayMetrics().density);
            h.image.setPadding(pad, pad, pad, pad);
            h.label.setVisibility(a.banner == null ? View.VISIBLE : View.GONE);
            h.label.setText(a.label);
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView image;
            final TextView label;
            AppEntry app;

            Holder(View v) {
                super(v);
                image = v.findViewById(R.id.appImage);
                label = v.findViewById(R.id.appLabel);
                v.findViewById(R.id.card).setClipToOutline(true);
                v.setOnFocusChangeListener((view, has) -> {
                    if (has && app != null) listener.onTileFocused(view, getBindingAdapterPosition(), app);
                });
                v.setOnClickListener(view -> {
                    if (app != null) listener.onTileClicked(app);
                });
                v.setOnKeyListener((view, keyCode, e) -> e.getAction() == KeyEvent.ACTION_DOWN
                        && listener.onTileKey(view, getBindingAdapterPosition(), keyCode));
            }
        }
    }
}
