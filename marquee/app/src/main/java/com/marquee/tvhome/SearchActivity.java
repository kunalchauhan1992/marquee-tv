package com.marquee.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.util.List;

/** Search movies and shows by name with the on-screen keyboard; results as a poster grid. */
public final class SearchActivity extends Activity implements Tiles.Listener {
    private static final int COLUMNS = 8;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private App app;
    private EditText query;
    private TextView status;
    private RecyclerView results;
    private Tiles.PosterAdapter adapter;
    private int request;
    private final Runnable run = this::search;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = App.get(this);
        setContentView(R.layout.activity_search);
        query = findViewById(R.id.query);
        status = findViewById(R.id.status);
        results = findViewById(R.id.results);
        results.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        results.setItemAnimator(null);
        adapter = new Tiles.PosterAdapter(this, this);
        adapter.setTitles(java.util.Collections.emptyList());
        results.setAdapter(adapter);
        query.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                handler.removeCallbacks(run);
                handler.postDelayed(run, 450);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        query.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                handler.removeCallbacks(run);
                search();
                if (results.getChildCount() > 0) results.getChildAt(0).requestFocus();
                return true;
            }
            return false;
        });
        query.requestFocus();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void search() {
        String q = query.getText().toString().trim();
        int id = ++request;
        if (q.length() < 2) {
            adapter.setTitles(java.util.Collections.emptyList());
            status.setText("");
            return;
        }
        app.io.execute(() -> {
            List<Title> found = null;
            String message = null;
            try {
                found = app.tmdb.search(q);
                if (found.isEmpty()) message = getString(R.string.no_results);
            } catch (Tmdb.NoKeyException e) {
                message = getString(R.string.no_key);
            } catch (IOException e) {
                message = getString(R.string.offline);
            }
            List<Title> list = found;
            String msg = message;
            app.main.post(() -> {
                if (id != request || isFinishing()) return;
                status.setText(msg == null ? "" : msg);
                adapter.setTitles(list == null ? java.util.Collections.emptyList() : list);
            });
        });
    }

    @Override
    public void onTileFocused(View tile, int position, Object item) {
    }

    @Override
    public void onTileClicked(Object item) {
        if (item instanceof Title) DetailsActivity.open(this, (Title) item);
    }

    @Override
    public boolean onTileKey(View tile, int position, int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && position < COLUMNS) return query.requestFocus();
        return false;
    }
}
