package com.ibrata2.ibradl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small thumbnail loader: YouTube thumbnails over HTTP, or cover art embedded in local files. */
final class Img {
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final ExecutorService pool = Executors.newFixedThreadPool(4);
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final Bitmap NONE = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8);

    /** @param fallback shown when there is no picture */
    static void load(Context ctx, ImageView v, Song s, android.graphics.drawable.Drawable fallback) {
        String key = s == null ? "" : (!s.thumb.isEmpty() ? s.thumb : s.id);
        v.setTag(key);
        Bitmap b = key.isEmpty() ? NONE : cache.get(key);
        if (b != null) { show(v, b, fallback); return; }
        v.setImageDrawable(fallback);
        Context app = ctx.getApplicationContext();
        pool.execute(() -> {
            Bitmap got = fetch(app, s);
            cache.put(key, got == null ? NONE : got);
            UI.post(() -> { if (key.equals(v.getTag())) show(v, got == null ? NONE : got, fallback); });
        });
    }

    private static void show(ImageView v, Bitmap b, android.graphics.drawable.Drawable fallback) {
        if (b == NONE) v.setImageDrawable(fallback); else v.setImageBitmap(b);
    }

    private static Bitmap fetch(Context app, Song s) {
        try {
            if (s.thumb.startsWith("http")) {
                HttpURLConnection c = (HttpURLConnection) new URL(s.thumb).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                try (InputStream in = c.getInputStream()) { return crop(BitmapFactory.decodeStream(in)); }
                finally { c.disconnect(); }
            }
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                if (s.id.startsWith("content://")) r.setDataSource(app, Uri.parse(s.id));
                else if (!s.path.isEmpty()) r.setDataSource(s.path);
                else if (s.id.startsWith("file://")) r.setDataSource(Uri.parse(s.id).getPath());
                else return null;
                byte[] pic = r.getEmbeddedPicture();
                if (pic == null) return null;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 2;
                return crop(BitmapFactory.decodeByteArray(pic, 0, pic.length, o));
            } finally {
                try { r.release(); } catch (Exception ignored) { }
            }
        } catch (Throwable e) {
            return null;
        }
    }

    /** Square centre crop (YouTube thumbnails are 4:3 with black bars). */
    private static Bitmap crop(Bitmap b) {
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight();
        int side = Math.min(w, h);
        if (w > h) {   // hqdefault: 480x360 with letterbox -> take the middle square of the 16:9 area
            int inner = Math.round(w * 9f / 16f);
            side = Math.min(side, inner);
        }
        Bitmap sq = Bitmap.createBitmap(b, (w - side) / 2, (h - side) / 2, side, side);
        int max = 480;
        return side > max ? Bitmap.createScaledBitmap(sq, max, max, true) : sq;
    }

    private Img() {}
}
