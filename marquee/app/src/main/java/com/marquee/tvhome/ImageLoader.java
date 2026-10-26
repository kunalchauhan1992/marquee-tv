package com.marquee.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;

/**
 * Posters and backdrops: a memory cache bounded to an eighth of the app's heap, a disk cache trimmed to 150 MB,
 * and 16-bit bitmaps decoded at the size they are drawn. A recycled view never shows a late image: each request is
 * checked against the view's current URL before it is set.
 */
final class ImageLoader {
    private static final long DISK_LIMIT = 150L * 1024 * 1024;

    private final File dir;
    private final ExecutorService io;
    private final Handler main;
    private final LruCache<String, Bitmap> memory;

    ImageLoader(Context context, ExecutorService io, Handler main) {
        this.io = io;
        this.main = main;
        dir = new File(context.getCacheDir(), "img");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        // 16 MB holds about 50 posters plus a few backdrops: one screen and its neighbours. Posters come back from
        // the disk cache in a few milliseconds, so a bigger memory cache only takes RAM from the streaming apps.
        int bytes = (int) Math.min(Runtime.getRuntime().maxMemory() / 8, 16L * 1024 * 1024);
        memory = new LruCache<String, Bitmap>(bytes) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    /** Loads url into view, decoded for a width of about targetWidthPx; null clears the view. */
    void load(String url, ImageView view, int targetWidthPx) {
        view.setTag(R.id.image_url, url);
        if (url == null) {
            view.setImageDrawable(null);
            return;
        }
        Bitmap hit = memory.get(url);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        view.setImageDrawable(null);
        io.execute(() -> {
            Bitmap b = fetch(url, targetWidthPx);
            main.post(() -> {
                if (b != null) memory.put(url, b);
                if (url.equals(view.getTag(R.id.image_url))) {
                    view.setImageBitmap(b);
                    if (b != null) {
                        view.setAlpha(0f);
                        view.animate().alpha(1f).setDuration(180).start();
                    }
                }
            });
        });
    }

    private Bitmap fetch(String url, int targetWidth) {
        File file = new File(dir, Tmdb.sha1(url));
        try {
            if (!file.exists()) download(url, file);
            //noinspection ResultOfMethodCallIgnored
            file.setLastModified(System.currentTimeMillis());
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            if (bounds.outWidth <= 0) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                return null;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), opts);
        } catch (IOException | OutOfMemoryError e) {
            return null;
        }
    }

    private static void download(String url, File to) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        File tmp = new File(to.getPath() + ".part");
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
            if (c.getResponseCode() != 200) throw new IOException("image HTTP " + c.getResponseCode());
            byte[] b = new byte[32768];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        } finally {
            c.disconnect();
        }
        if (!tmp.renameTo(to)) throw new IOException("rename failed");
    }

    /** Oldest files first until the disk cache is under its limit. */
    void trimDisk() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= DISK_LIMIT) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File f : files) {
            if (total <= DISK_LIMIT * 3 / 4) break;
            total -= f.length();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    void clearMemory() {
        memory.evictAll();
    }
}
