package com.marquee.tvhome;

import android.content.Context;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Home's vertical list of rows; each row is a titled horizontal list of tiles or a one-line message. */
final class Rows {
    private Rows() {}

    static final class Row {
        final String title;
        final Tiles.PosterAdapter posters;
        final Tiles.AppsAdapter apps;
        String message;
        Parcelable scroll;

        private Row(String title, Tiles.PosterAdapter posters, Tiles.AppsAdapter apps) {
            this.title = title;
            this.posters = posters;
            this.apps = apps;
        }

        static Row posters(Context context, String title, Tiles.Listener l) {
            return new Row(title, new Tiles.PosterAdapter(context, l), null);
        }

        static Row apps(String title, Tiles.Listener l) {
            return new Row(title, null, new Tiles.AppsAdapter(l));
        }

        RecyclerView.Adapter<?> adapter() {
            return posters != null ? posters : apps;
        }
    }

    static final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        private final List<Row> rows = new ArrayList<>();
        private final RecyclerView.RecycledViewPool pool = new RecyclerView.RecycledViewPool();

        Adapter() {
            pool.setMaxRecycledViews(0, 40);
        }

        void setRows(List<Row> newRows) {
            rows.clear();
            rows.addAll(newRows);
            notifyDataSetChanged();
        }

        List<Row> rows() {
            return rows;
        }

        Row row(int position) {
            return position >= 0 && position < rows.size() ? rows.get(position) : null;
        }

        void changed(Row r) {
            int i = rows.indexOf(r);
            if (i >= 0) notifyItemChanged(i);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Holder h = new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_row, parent, false));
            LinearLayoutManager lm = new LinearLayoutManager(parent.getContext(), LinearLayoutManager.HORIZONTAL, false);
            lm.setInitialPrefetchItemCount(8);
            h.list.setLayoutManager(lm);
            h.list.setItemAnimator(null);
            h.list.setRecycledViewPool(pool);
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Row r = rows.get(position);
            h.row = r;
            h.title.setText(r.title);
            h.title.setVisibility(r.title == null || r.title.isEmpty() ? View.GONE : View.VISIBLE);
            boolean message = r.message != null;
            h.message.setVisibility(message ? View.VISIBLE : View.GONE);
            h.message.setText(message ? r.message : "");
            h.list.setVisibility(message ? View.GONE : View.VISIBLE);
            if (h.list.getAdapter() != r.adapter()) h.list.swapAdapter(r.adapter(), false);
            if (r.scroll != null && h.list.getLayoutManager() != null) h.list.getLayoutManager().onRestoreInstanceState(r.scroll);
        }

        @Override
        public void onViewRecycled(@NonNull Holder h) {
            if (h.row != null && h.list.getLayoutManager() != null) h.row.scroll = h.list.getLayoutManager().onSaveInstanceState();
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView message;
            final RecyclerView list;
            Row row;

            Holder(View v) {
                super(v);
                title = v.findViewById(R.id.rowTitle);
                message = v.findViewById(R.id.rowMessage);
                list = v.findViewById(R.id.rowList);
            }
        }
    }
}
