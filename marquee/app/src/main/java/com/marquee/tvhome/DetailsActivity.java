package com.marquee.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** One title: what it is, where it streams in India, and buttons to watch it, its trailer and My list. */
public final class DetailsActivity extends Activity implements Tiles.Listener {
    private static final String EXTRA_TITLE = "title";

    private App app;
    private Title title;
    private Tmdb.Details details;
    private LinearLayout buttons;
    private TextView meta, genres, where, moreTitle;
    private Tiles.PosterAdapter more;

    static void open(Context context, Title t) {
        context.startActivity(new Intent(context, DetailsActivity.class).putExtra(EXTRA_TITLE, t.toJson().toString()));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = App.get(this);
        try {
            title = Title.from(new JSONObject(getIntent().getStringExtra(EXTRA_TITLE)), "movie");
        } catch (JSONException | NullPointerException e) {
            title = null;
        }
        if (title == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_details);
        ((TextView) findViewById(R.id.title)).setText(title.name);
        ((TextView) findViewById(R.id.overview)).setText(title.overview);
        meta = findViewById(R.id.meta);
        genres = findViewById(R.id.genres);
        where = findViewById(R.id.where);
        moreTitle = findViewById(R.id.moreTitle);
        buttons = findViewById(R.id.buttons);
        meta.setText(Format.meta(this, title, null, null));
        app.images.load(Tmdb.backdrop(title.backdrop), findViewById(R.id.backdrop), 1280);

        RecyclerView list = findViewById(R.id.more);
        list.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        list.setItemAnimator(null);
        more = new Tiles.PosterAdapter(this, this);
        list.setAdapter(more);

        showButtons();
        load();
    }

    private void load() {
        app.io.execute(() -> {
            Map<Integer, String> g = app.tmdb.genres();
            Tmdb.Details d = null;
            List<Title> similar = null;
            try {
                d = app.tmdb.details(title);
            } catch (IOException ignored) {
                // Buttons still offer the trailer search and My list.
            }
            try {
                similar = app.tmdb.similar(title, d);
            } catch (IOException ignored) {
                // No "More like this" row.
            }
            Tmdb.Details details = d;
            List<Title> sim = similar;
            app.main.post(() -> {
                if (isFinishing()) return;
                this.details = details;
                meta.setText(Format.meta(this, title, g, details));
                if (details != null && !details.genres.isEmpty()) genres.setText(String.join(", ", details.genres));
                where.setText(whereText(details));
                showButtons();
                if (sim != null && !sim.isEmpty()) {
                    moreTitle.setVisibility(View.VISIBLE);
                    more.setTitles(sim);
                }
            });
        });
    }

    private String whereText(Tmdb.Details d) {
        if (d == null || d.providerNames.isEmpty()) return getString(R.string.not_on_your_apps);
        return getString(R.string.on_service, String.join(" · ", d.providerNames));
    }

    /** One "Watch on …" button per installed app that streams it, then Trailer and My list. */
    private void showButtons() {
        View focused = getCurrentFocus();
        boolean hadFocus = focused != null && focused.getParent() == buttons;
        buttons.removeAllViews();
        if (details != null) {
            for (Services.Service s : details.services) {
                if (!s.installed(this)) continue;
                addButton(getString(R.string.watch_on, s.label), R.drawable.ic_play, v -> Actions.watch(this, s, title));
            }
        }
        addButton(getString(R.string.trailer), 0, v -> {
            if (details != null) Actions.trailer(this, details.trailerKey);
        });
        boolean in = new MyList(this).contains(title);
        addButton(getString(in ? R.string.in_list : R.string.add_to_list), in ? R.drawable.ic_check : R.drawable.ic_add, v -> {
            new MyList(this).toggle(title);
            showButtons();
            buttons.getChildAt(buttons.getChildCount() - 1).requestFocus();
        });
        if (hadFocus || getCurrentFocus() == null) buttons.getChildAt(0).requestFocus();
    }

    private void addButton(String text, int icon, View.OnClickListener click) {
        TextView b = (TextView) LayoutInflater.from(this).inflate(R.layout.item_button, buttons, false);
        b.setText(text);
        if (icon != 0) b.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        b.setOnClickListener(click);
        if (buttons.getChildCount() > 0) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
            lp.setMarginStart(Math.round(10 * getResources().getDisplayMetrics().density));
        }
        buttons.addView(b);
    }

    @Override
    public void onTileFocused(View tile, int position, Object item) {
    }

    @Override
    public void onTileClicked(Object item) {
        if (item instanceof Title) open(this, (Title) item);
    }

    @Override
    public boolean onTileKey(View tile, int position, int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return buttons.getChildAt(0).requestFocus();
        return keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
    }
}
