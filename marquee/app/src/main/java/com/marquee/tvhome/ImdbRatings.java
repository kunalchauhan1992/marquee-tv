package com.marquee.tvhome;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * IMDb's own ratings, from the dataset IMDb publishes daily for personal, non-commercial use
 * (https://developer.imdb.com/non-commercial-datasets/, title.ratings.tsv.gz, about 8 MB).
 *
 * Downloaded at most once a day and turned into a sorted index on disk: a count, then every title's number
 * ("tt0110912" as 110912), then its rating times ten, one byte each. About 8 MB for 1.6 million titles; a lookup is a
 * binary search in the file, so nothing large stays in memory.
 */
final class ImdbRatings {
    private static final String URL_RATINGS = "https://datasets.imdbws.com/title.ratings.tsv.gz";
    private static final long DAY = 24L * 3_600_000L;

    private final File index;
    private final AtomicBoolean refreshing = new AtomicBoolean();

    ImdbRatings(Context context) {
        index = new File(context.getFilesDir(), "imdb-ratings.bin");
    }

    /** Rating as "5.8", or null when the title isn't in IMDb's file (or the file isn't downloaded yet). */
    String get(String imdbId) {
        if (imdbId == null || !imdbId.startsWith("tt") || !index.exists()) return null;
        int id;
        try {
            id = Integer.parseInt(imdbId.substring(2));
        } catch (NumberFormatException e) {
            return null;
        }
        try (RandomAccessFile f = new RandomAccessFile(index, "r")) {
            int n = f.readInt();
            int lo = 0, hi = n - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                f.seek(4 + 4L * mid);
                int v = f.readInt();
                if (v < id) lo = mid + 1;
                else if (v > id) hi = mid - 1;
                else {
                    f.seek(4 + 4L * n + mid);
                    int r = f.readUnsignedByte();
                    return String.format(Locale.US, "%.1f", r / 10.0);
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    boolean stale() {
        return !index.exists() || System.currentTimeMillis() - index.lastModified() > DAY;
    }

    /** Downloads and rebuilds the index if it's over a day old; runs on the caller's (background) thread. */
    void refreshIfStale() {
        if (!stale() || !refreshing.compareAndSet(false, true)) return;
        long start = System.currentTimeMillis();
        try {
            build();
            Log.i("Marquee", "IMDb ratings updated in " + (System.currentTimeMillis() - start) / 1000 + " s");
        } catch (IOException | OutOfMemoryError e) {
            Log.w("Marquee", "IMDb ratings not updated: " + e);
        } finally {
            refreshing.set(false);
        }
    }

    private void build() throws IOException {
        int[] ids = new int[1 << 21];
        byte[] ratings = new byte[1 << 21];
        int n = 0;
        HttpURLConnection c = (HttpURLConnection) new URL(URL_RATINGS).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        try (InputStream raw = c.getInputStream();
             BufferedReader in = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw, 65536), StandardCharsets.UTF_8), 65536)) {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            in.readLine();   // tconst, averageRating, numVotes
            for (String line; (line = in.readLine()) != null; ) {
                int t1 = line.indexOf('\t');
                int t2 = line.indexOf('\t', t1 + 1);
                if (t1 < 3 || t2 < 0 || !line.startsWith("tt")) continue;
                int id;
                int r;
                try {
                    id = Integer.parseInt(line.substring(2, t1));
                    r = Math.round(Float.parseFloat(line.substring(t1 + 1, t2)) * 10);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (n == ids.length) {
                    ids = Arrays.copyOf(ids, n * 3 / 2);
                    ratings = Arrays.copyOf(ratings, n * 3 / 2);
                }
                ids[n] = id;
                ratings[n] = (byte) r;
                n++;
            }
        } finally {
            c.disconnect();
        }
        if (n < 100_000) throw new IOException("only " + n + " ratings");
        // The file is ordered as text ("tt10000000" before "tt9999999"); order by number for the binary search.
        long[] pairs = new long[n];
        for (int i = 0; i < n; i++) pairs[i] = ((long) ids[i] << 8) | (ratings[i] & 0xFF);
        ids = null;
        ratings = null;
        Arrays.sort(pairs);
        File tmp = new File(index.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 65536))) {
            out.writeInt(n);
            for (long p : pairs) out.writeInt((int) (p >>> 8));
            for (long p : pairs) out.writeByte((int) (p & 0xFF));
        }
        if (!tmp.renameTo(index)) throw new IOException("rename failed");
    }
}
