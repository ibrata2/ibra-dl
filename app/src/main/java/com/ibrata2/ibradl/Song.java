package com.ibrata2.ibradl;

import android.net.Uri;
import android.os.Bundle;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

import org.json.JSONObject;

/**
 * One track. {@link #id} is also the playable URI:
 *  - "ytdl://VIDEO_ID" for YouTube (resolved to a stream link when it starts playing)
 *  - "content://…" or "file://…" for songs on the phone
 */
final class Song {
    static final String YT = "ytdl://";

    final String id;
    String title, artist, thumb, path;
    long duration;   // ms

    Song(String id, String title, String artist, String thumb, String path, long duration) {
        this.id = id;
        this.title = title == null || title.isEmpty() ? "—" : title;
        this.artist = artist == null ? "" : artist;
        this.thumb = thumb == null ? "" : thumb;
        this.path = path == null ? "" : path;
        this.duration = duration;
    }

    static Song youtube(String videoId, String title, String channel, long durationMs) {
        return new Song(YT + videoId, title, channel, "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg", "", durationMs);
    }

    /** A song from another site (SoundCloud, Audiomack…): the key after ytdl:// is the page link. */
    static Song web(String url, String title, String uploader, String thumb, long durationMs) {
        return new Song(YT + url, title, uploader, thumb, "", durationMs);
    }

    /** Online song handled by yt-dlp (YouTube or any supported site). */
    boolean isYoutube() { return id.startsWith(YT); }

    String videoId() { return isYoutube() ? id.substring(YT.length()) : ""; }

    String watchUrl() {
        String v = videoId();
        return v.startsWith("http") ? v : "https://www.youtube.com/watch?v=" + v;
    }

    JSONObject json() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id).put("t", title).put("a", artist).put("th", thumb).put("p", path).put("d", duration);
        } catch (Exception ignored) { }
        return o;
    }

    static Song from(JSONObject o) {
        if (o == null || o.optString("id").isEmpty()) return null;
        return new Song(o.optString("id"), o.optString("t"), o.optString("a"), o.optString("th"), o.optString("p"), o.optLong("d"));
    }

    MediaItem item() {
        Bundle ex = new Bundle();
        ex.putString("song", json().toString());
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .setTitle(title).setDisplayTitle(title).setArtist(artist)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setIsPlayable(true).setIsBrowsable(false).setExtras(ex);
        if (thumb.startsWith("http")) md.setArtworkUri(Uri.parse(thumb));
        return new MediaItem.Builder().setMediaId(id).setUri(Uri.parse(id)).setMediaMetadata(md.build()).build();
    }

    static Song of(MediaItem m) {
        if (m == null) return null;
        Bundle ex = m.mediaMetadata.extras;
        if (ex != null && ex.getString("song") != null) {
            try { Song s = from(new JSONObject(ex.getString("song"))); if (s != null) return s; } catch (Exception ignored) { }
        }
        String t = m.mediaMetadata.title == null ? m.mediaId : m.mediaMetadata.title.toString();
        String a = m.mediaMetadata.artist == null ? "" : m.mediaMetadata.artist.toString();
        return new Song(m.mediaId, t, a, "", "", 0);
    }

    @Override public boolean equals(Object o) { return o instanceof Song && ((Song) o).id.equals(id); }
    @Override public int hashCode() { return id.hashCode(); }
}
