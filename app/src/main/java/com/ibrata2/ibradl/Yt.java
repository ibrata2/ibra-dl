package com.ibrata2.ibradl;

import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** yt-dlp engine: YouTube search, stream links for playback, and the download queue. */
final class Yt {

    static final int NOT_READY = 0, PREPARING = 1, READY = 2, FAILED = -1;
    static volatile int state = NOT_READY;
    static volatile String stateError = "";
    static volatile int version;   // bumped whenever the downloads list changes

    private static final Object initLock = new Object();
    private static final ExecutorService work = Executors.newFixedThreadPool(2);
    private static final ExecutorService queue = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());

    interface Done<T> { void run(T value, String error); }

    // ------------------------------------------------------------------ engine
    static void prepare(Context ctx) {
        if (state == READY || state == PREPARING) return;
        Context app = ctx.getApplicationContext();
        state = PREPARING;
        work.execute(() -> ensure(app));
    }

    /**
     * yt-dlp lives in one zip file. Updating replaces that file, so nothing may run yt-dlp
     * while it is being replaced: every call holds the read lock, an update holds the write lock.
     */
    private static final java.util.concurrent.locks.ReentrantReadWriteLock rw = new java.util.concurrent.locks.ReentrantReadWriteLock();
    static void beginUse() { rw.readLock().lock(); }
    static void endUse() { rw.readLock().unlock(); }

    /** Blocks until yt-dlp is ready (used by background threads). */
    static boolean ensure(Context ctx) {
        synchronized (initLock) {
            if (state == READY) return true;
            state = PREPARING;
            Context app = ctx.getApplicationContext();
            try {
                YoutubeDL.getInstance().init(app);
                FFmpeg.getInstance().init(app);
            } catch (Throwable e) {
                stateError = String.valueOf(e.getMessage());
                state = FAILED;
                return false;
            }
            // First run of the day: bring yt-dlp up to date before anything uses it.
            Store st = new Store(app);
            if (System.currentTimeMillis() - st.num("ytdlpCheck", 0) > 24L * 3600_000L) {
                update(app);
                st.put("ytdlpCheck", System.currentTimeMillis());
            }
            state = READY;
        }
        return true;
    }

    private static YoutubeDL.UpdateStatus update(Context app) {
        rw.writeLock().lock();
        try {
            android.util.Log.i("IBRADL", "updating yt-dlp");
            YoutubeDL.UpdateStatus s = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel._STABLE);
            android.util.Log.i("IBRADL", "yt-dlp update: " + s + " " + YoutubeDL.getInstance().versionName(app));
            return s;
        } catch (Throwable e) {
            android.util.Log.e("IBRADL", "yt-dlp update failed", e);
            return null;
        } finally {
            rw.writeLock().unlock();
        }
    }

    static void updateEngine(Context ctx, Done<String> done) {
        Context app = ctx.getApplicationContext();
        work.execute(() -> {
            try {
                if (!ensure(app)) throw new RuntimeException(stateError);
                YoutubeDL.UpdateStatus s = update(app);
                if (s == null) throw new RuntimeException("تأكد من الإنترنت");
                new Store(app).put("ytdlpCheck", System.currentTimeMillis());
                String v = YoutubeDL.getInstance().versionName(app);
                UI.post(() -> done.run((s == YoutubeDL.UpdateStatus.DONE ? "تم التحديث ✓ " : "محدّث ✓ ") + (v == null ? "" : v), null));
            } catch (Throwable e) {
                UI.post(() -> done.run(null, String.valueOf(e.getMessage())));
            }
        });
    }

    static String engineVersion(Context ctx) {
        try { String v = YoutubeDL.getInstance().versionName(ctx.getApplicationContext()); return v == null ? "" : v; }
        catch (Throwable e) { return ""; }
    }

    // ------------------------------------------------------------------ search
    static void search(Context ctx, String query, Done<List<Song>> done) {
        Context app = ctx.getApplicationContext();
        work.execute(() -> {
            try {
                if (!ensure(app)) throw new RuntimeException(stateError);
                YoutubeDLRequest r = new YoutubeDLRequest("ytsearch30:" + query);
                r.addOption("--flat-playlist");
                r.addOption("-J");
                r.addOption("--no-warnings");
                YoutubeDLResponse res;
                beginUse();
                try { res = YoutubeDL.getInstance().execute(r, "q" + System.nanoTime(), (p, eta, line) -> kotlin.Unit.INSTANCE); }
                finally { endUse(); }
                List<Song> out = parseEntries(res.getOut());
                UI.post(() -> done.run(out, null));
            } catch (Throwable e) {
                String m = shortError(String.valueOf(e.getMessage()));
                UI.post(() -> done.run(null, m));
            }
        });
    }

    /** Info for one link (shared from YouTube, or pasted). Playlists come back as several songs. */
    static void link(Context ctx, String url, Done<List<Song>> done) {
        Context app = ctx.getApplicationContext();
        work.execute(() -> {
            try {
                if (!ensure(app)) throw new RuntimeException(stateError);
                YoutubeDLRequest r = new YoutubeDLRequest(url);
                r.addOption("--flat-playlist");
                r.addOption("-J");
                r.addOption("--no-warnings");
                YoutubeDLResponse res;
                beginUse();
                try { res = YoutubeDL.getInstance().execute(r, "q" + System.nanoTime(), (p, eta, line) -> kotlin.Unit.INSTANCE); }
                finally { endUse(); }
                String out = res.getOut();
                List<Song> songs = parseEntries(out);
                if (songs.isEmpty()) {
                    JSONObject o = new JSONObject(out.substring(out.indexOf('{')));
                    Song s = entry(o);
                    if (s != null) songs.add(s);
                }
                UI.post(() -> done.run(songs, null));
            } catch (Throwable e) {
                String m = shortError(String.valueOf(e.getMessage()));
                UI.post(() -> done.run(null, m));
            }
        });
    }

    private static List<Song> parseEntries(String out) throws Exception {
        List<Song> list = new ArrayList<>();
        int b = out.indexOf('{');
        if (b < 0) return list;
        JSONObject root = new JSONObject(out.substring(b));
        JSONArray entries = root.optJSONArray("entries");
        if (entries == null) return list;
        for (int i = 0; i < entries.length(); i++) {
            Song s = entry(entries.optJSONObject(i));
            if (s != null) list.add(s);
        }
        return list;
    }

    private static Song entry(JSONObject e) {
        if (e == null) return null;
        String id = e.optString("id");
        if (id.length() != 11) return null;   // channels / playlists in results
        String ch = e.optString("channel", e.optString("uploader", ""));
        if ("null".equals(ch)) ch = "";
        long d = (long) (e.optDouble("duration", 0) * 1000);
        return Song.youtube(id, e.optString("title"), ch, d);
    }

    // ------------------------------------------------------------------ streaming
    static final class Stream {
        final String url;
        final Map<String, String> headers;
        final long at = System.currentTimeMillis();
        Stream(String url, Map<String, String> headers) { this.url = url; this.headers = headers; }
    }

    private static final Map<String, Stream> streams = Collections.synchronizedMap(new HashMap<>());

    /** Returns a direct audio link for a YouTube video (blocking; call off the main thread). Cached ~3h. */
    static Stream resolve(Context ctx, String videoId) throws Exception {
        Stream s = streams.get(videoId);
        if (s != null && System.currentTimeMillis() - s.at < 3 * 3600_000L) return s;
        File local = downloadedFile(videoId);
        if (local != null) return new Stream(android.net.Uri.fromFile(local).toString(), new HashMap<>());
        android.util.Log.i("IBRADL", "resolving " + videoId);
        if (!ensure(ctx)) throw new RuntimeException(stateError);
        YoutubeDLRequest r = new YoutubeDLRequest("https://www.youtube.com/watch?v=" + videoId);
        r.addOption("--no-playlist");
        r.addOption("--no-warnings");
        r.addOption("-f", "bestaudio[ext=m4a]/bestaudio/best");
        VideoInfo vi;
        beginUse();
        try { vi = YoutubeDL.getInstance().getInfo(r); } finally { endUse(); }
        String url = vi.getUrl();
        if (url == null || url.isEmpty()) throw new RuntimeException("no stream");
        Map<String, String> h = vi.getHttpHeaders() == null ? new HashMap<>() : new HashMap<>(vi.getHttpHeaders());
        s = new Stream(url, h);
        streams.put(videoId, s);
        return s;
    }

    static void forget(String videoId) { streams.remove(videoId); }

    // ------------------------------------------------------------------ downloads
    static final class Choice {
        final String label, kind;   // kind: mp3 | m4a | mp4
        final int value;            // kbps for mp3, height for mp4
        Choice(String label, String kind, int value) { this.label = label; this.kind = kind; this.value = value; }
    }

    static final Choice[] CHOICES = {
            new Choice("MP3 · 320k (أعلى جودة)", "mp3", 320),
            new Choice("MP3 · 192k", "mp3", 192),
            new Choice("MP3 · 128k (حجم صغير)", "mp3", 128),
            new Choice("M4A · الجودة الأصلية", "m4a", 0),
            new Choice("فيديو MP4 · 720p", "mp4", 720),
            new Choice("فيديو MP4 · 360p", "mp4", 360),
    };

    static final class Job {
        final int id;
        final Song song;
        final Choice choice;
        volatile String status = "queued";   // queued | running | merging | done | failed | canceled
        volatile float progress;
        volatile long eta;
        volatile String file = "", error = "";
        Job(int id, Song s, Choice c) { this.id = id; this.song = s; this.choice = c; }
        boolean active() { return "queued".equals(status) || "running".equals(status) || "merging".equals(status); }
    }

    static final List<Job> jobs = Collections.synchronizedList(new ArrayList<>());
    private static int nextId = 1;

    static File outDir() {
        File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "IBRA DL");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Files are named "Title [videoId].ext" so we can tell which YouTube song they came from. */
    static File downloadedFile(String videoId) {
        File[] all = outDir().listFiles();
        if (all == null || videoId.isEmpty()) return null;
        for (File f : all) {
            String n = f.getName();
            if (n.contains("[" + videoId + "]") && isAudio(n)) return f;
        }
        return null;
    }

    private static java.util.Set<String> idCache = new java.util.HashSet<>();
    private static long idCacheAt;
    private static int idCacheVer = -1;

    /** YouTube ids of songs already in the download folder (cached a few seconds; cheap to call per row). */
    static synchronized boolean isDownloaded(String videoId) {
        if (idCacheVer != version || System.currentTimeMillis() - idCacheAt > 8000) {
            java.util.Set<String> ids = new java.util.HashSet<>();
            File[] all = outDir().listFiles();
            java.util.regex.Pattern pat = java.util.regex.Pattern.compile("\\[([A-Za-z0-9_-]{11})]\\.[A-Za-z0-9]+$");
            if (all != null) for (File f : all) {
                java.util.regex.Matcher m = pat.matcher(f.getName());
                if (m.find() && isAudio(f.getName())) ids.add(m.group(1));
            }
            idCache = ids;
            idCacheAt = System.currentTimeMillis();
            idCacheVer = version;
        }
        return idCache.contains(videoId);
    }

    static boolean isAudio(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".opus") || n.endsWith(".ogg")
                || n.endsWith(".flac") || n.endsWith(".wav") || n.endsWith(".aac") || n.endsWith(".webm");
    }

    static Job enqueue(Context ctx, Song s, Choice c) {
        Job j;
        synchronized (jobs) { j = new Job(nextId++, s, c); jobs.add(0, j); }
        version++;
        Context app = ctx.getApplicationContext();
        try {
            Intent svc = new Intent(app, DownloadService.class);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(svc); else app.startService(svc);
        } catch (Throwable ignored) { }
        queue.execute(() -> run(app, j));
        return j;
    }

    private static void run(Context app, Job j) {
        if ("canceled".equals(j.status)) return;
        j.status = "running";
        version++;
        File dir = outDir();
        long started = System.currentTimeMillis() - 2000;
        try {
            if (!ensure(app)) throw new RuntimeException(stateError);
            YoutubeDLRequest r = new YoutubeDLRequest(j.song.watchUrl());
            r.addOption("--no-playlist");
            r.addOption("--no-mtime");
            r.addOption("--windows-filenames");
            r.addOption("--embed-metadata");
            r.addOption("-P", "temp:" + app.getCacheDir().getAbsolutePath());
            String tag = "mp4".equals(j.choice.kind) ? " (" + j.choice.value + "p)" : "";
            r.addOption("-o", dir.getAbsolutePath() + "/%(title).110B" + tag + " [%(id)s].%(ext)s");
            Choice c = j.choice;
            if ("mp3".equals(c.kind)) {
                r.addOption("-f", "bestaudio/best");
                r.addOption("-x");
                r.addOption("--audio-format", "mp3");
                r.addOption("--audio-quality", c.value + "K");
                r.addOption("--embed-thumbnail");
                r.addOption("--convert-thumbnails", "jpg");
            } else if ("m4a".equals(c.kind)) {
                r.addOption("-f", "bestaudio[ext=m4a]/bestaudio/best");
                r.addOption("-x");
                r.addOption("--audio-format", "m4a");
            } else {
                r.addOption("-f", "bv*+ba/b");
                r.addOption("-S", "res:" + c.value + ",vcodec:h264,acodec:m4a");
                r.addOption("--merge-output-format", "mp4");
            }
            beginUse();
            try {
            YoutubeDL.getInstance().execute(r, "job" + j.id, (p, eta, line) -> {
                if (p != null && p >= 0) j.progress = p;
                if (eta != null) j.eta = eta;
                if (line != null && (line.startsWith("[Merger]") || line.startsWith("[ExtractAudio]")
                        || line.startsWith("[Metadata]") || line.startsWith("[EmbedThumbnail]"))) {
                    if (!"merging".equals(j.status)) { j.status = "merging"; version++; }
                }
                return kotlin.Unit.INSTANCE;
            });
            } finally { endUse(); }
            File f = newest(dir, started);
            j.file = f != null ? f.getAbsolutePath() : "";
            j.progress = 100;
            j.status = "done";
            if (f != null) MediaScannerConnection.scanFile(app, new String[]{f.getAbsolutePath()}, null, null);
        } catch (YoutubeDL.CanceledException e) {
            j.status = "canceled";
        } catch (Throwable e) {
            if (!"canceled".equals(j.status)) {
                j.status = "failed";
                j.error = shortError(String.valueOf(e.getMessage()));
            }
        }
        version++;
    }

    private static File newest(File dir, long after) {
        File best = null;
        File[] all = dir.listFiles();
        if (all == null) return null;
        for (File f : all) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (!f.isFile() || n.endsWith(".part") || n.endsWith(".ytdl") || n.contains(".temp.") || n.endsWith(".jpg") || n.endsWith(".webp")) continue;
            if (f.lastModified() < after) continue;
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }

    static String shortError(String m) {
        if (m == null) return "";
        String[] lines = m.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) if (lines[i].contains("ERROR")) return lines[i].replace("ERROR:", "").trim();
        return m.length() > 220 ? m.substring(m.length() - 220) : m;
    }

    static void cancel(Job j) {
        j.status = "canceled";
        version++;
        try { YoutubeDL.getInstance().destroyProcessById("job" + j.id); } catch (Throwable ignored) { }
    }

    static void clearFinished() {
        synchronized (jobs) { for (int i = jobs.size() - 1; i >= 0; i--) if (!jobs.get(i).active()) jobs.remove(i); }
        version++;
    }

    static boolean busy() {
        synchronized (jobs) { for (Job j : jobs) if (j.active()) return true; }
        return false;
    }

    static Job current() {
        synchronized (jobs) { for (Job j : jobs) if ("running".equals(j.status) || "merging".equals(j.status)) return j; }
        return null;
    }

    static String extractUrl(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://\\S+").matcher(text);
        return m.find() ? m.group() : null;
    }

    private Yt() {}
}
