package com.ibrata2.ibradl;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Playlists, favourites, history and the last play queue, kept as JSON in SharedPreferences. */
final class Store {
    static final String FAV = "fav";

    static final class Playlist {
        final String id;
        String name;
        final List<Song> songs = new ArrayList<>();
        Playlist(String id, String name) { this.id = id; this.name = name; }
    }

    private final SharedPreferences sp;

    Store(Context c) { sp = c.getApplicationContext().getSharedPreferences("ibradl", Context.MODE_PRIVATE); }

    // ------------------------------------------------------------ playlists
    synchronized List<Playlist> playlists() {
        List<Playlist> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString("playlists", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Playlist p = new Playlist(o.getString("id"), o.optString("name"));
                JSONArray s = o.optJSONArray("songs");
                if (s != null) for (int k = 0; k < s.length(); k++) { Song x = Song.from(s.optJSONObject(k)); if (x != null) p.songs.add(x); }
                out.add(p);
            }
        } catch (Exception ignored) { }
        boolean hasFav = false;
        for (Playlist p : out) if (FAV.equals(p.id)) hasFav = true;
        if (!hasFav) out.add(0, new Playlist(FAV, "المفضلة ❤"));
        return out;
    }

    synchronized void save(List<Playlist> list) {
        JSONArray a = new JSONArray();
        try {
            for (Playlist p : list) {
                JSONArray s = new JSONArray();
                for (Song x : p.songs) s.put(x.json());
                a.put(new JSONObject().put("id", p.id).put("name", p.name).put("songs", s));
            }
        } catch (Exception ignored) { }
        sp.edit().putString("playlists", a.toString()).apply();
    }

    Playlist get(String id) {
        for (Playlist p : playlists()) if (p.id.equals(id)) return p;
        return null;
    }

    Playlist create(String name) {
        List<Playlist> all = playlists();
        Playlist p = new Playlist("p" + System.currentTimeMillis(), name);
        all.add(p);
        save(all);
        return p;
    }

    void rename(String id, String name) {
        List<Playlist> all = playlists();
        for (Playlist p : all) if (p.id.equals(id)) p.name = name;
        save(all);
    }

    void delete(String id) {
        List<Playlist> all = playlists();
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).id.equals(id) && !FAV.equals(id)) all.remove(i);
        save(all);
    }

    /** @return false if the song was already in the list */
    boolean add(String id, Song s) {
        List<Playlist> all = playlists();
        for (Playlist p : all) if (p.id.equals(id)) {
            if (p.songs.contains(s)) return false;
            p.songs.add(s);
        }
        save(all);
        return true;
    }

    void remove(String id, Song s) {
        List<Playlist> all = playlists();
        for (Playlist p : all) if (p.id.equals(id)) p.songs.remove(s);
        save(all);
    }

    void move(String id, int from, int to) {
        List<Playlist> all = playlists();
        for (Playlist p : all) if (p.id.equals(id) && from >= 0 && from < p.songs.size() && to >= 0 && to < p.songs.size()) {
            Song s = p.songs.remove(from);
            p.songs.add(to, s);
        }
        save(all);
    }

    boolean isFav(Song s) {
        Playlist f = get(FAV);
        return f != null && s != null && f.songs.contains(s);
    }

    /** Toggles favourite; returns the new state. */
    boolean toggleFav(Song s) {
        if (isFav(s)) { remove(FAV, s); return false; }
        add(FAV, s);
        return true;
    }

    // ------------------------------------------------------------ history
    synchronized void addRecent(Song s) {
        if (s == null) return;
        List<Song> r = recent();
        r.remove(s);
        r.add(0, s);
        while (r.size() > 60) r.remove(r.size() - 1);
        sp.edit().putString("recent", arr(r).toString()).apply();
    }

    synchronized List<Song> recent() { return list(sp.getString("recent", "[]")); }

    void clearRecent() { sp.edit().remove("recent").apply(); }

    // ------------------------------------------------------------ last session
    synchronized void saveSession(List<Song> queue, int index, long pos, int repeat, boolean shuffle) {
        sp.edit().putString("q", arr(queue).toString()).putInt("qi", index).putLong("qp", pos)
                .putInt("repeat", repeat).putBoolean("shuffle", shuffle).apply();
    }

    List<Song> sessionQueue() { return list(sp.getString("q", "[]")); }
    int sessionIndex() { return sp.getInt("qi", 0); }
    long sessionPos() { return sp.getLong("qp", 0); }
    int repeat() { return sp.getInt("repeat", 0); }
    boolean shuffle() { return sp.getBoolean("shuffle", false); }

    // ------------------------------------------------------------ misc settings
    String str(String k, String d) { return sp.getString(k, d); }
    void put(String k, String v) { sp.edit().putString(k, v).apply(); }
    long num(String k, long d) { return sp.getLong(k, d); }
    void put(String k, long v) { sp.edit().putLong(k, v).apply(); }

    private static JSONArray arr(List<Song> l) {
        JSONArray a = new JSONArray();
        for (Song s : l) a.put(s.json());
        return a;
    }

    private static List<Song> list(String json) {
        List<Song> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) { Song s = Song.from(a.optJSONObject(i)); if (s != null) out.add(s); }
        } catch (Exception ignored) { }
        return out;
    }
}
