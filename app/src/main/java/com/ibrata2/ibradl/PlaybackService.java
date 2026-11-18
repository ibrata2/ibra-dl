package com.ibrata2.ibradl;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.CommandButton;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the player and keeps playing in the background with a media notification
 * (play/pause, next, previous, repeat, shuffle) after the app is closed.
 */
@OptIn(markerClass = UnstableApi.class)
public final class PlaybackService extends MediaSessionService {

    static final String CMD_REPEAT = "ibradl.REPEAT";
    static final String CMD_SHUFFLE = "ibradl.SHUFFLE";
    static final String CMD_SLEEP = "ibradl.SLEEP";      // extra "min" (0 = cancel)
    static final String KEY_SLEEP_AT = "sleepAt";

    private ExoPlayer player;
    private MediaSession session;
    private Store store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int retries;
    static volatile long sleepAt;   // wall-clock ms, 0 = off

    private final Runnable saver = new Runnable() {
        @Override public void run() { saveState(); handler.postDelayed(this, 5000); }
    };
    private final Runnable sleeper = () -> {
        sleepAt = 0;
        if (player != null) player.pause();
    };

    @Override public void onCreate() {
        super.onCreate();
        store = new Store(this);
        Yt.prepare(this);

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000).setReadTimeoutMs(20000)
                .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36");
        DefaultDataSource.Factory base = new DefaultDataSource.Factory(this, http);
        // "ytdl://ID" -> a fresh YouTube audio link, fetched just before the song starts.
        ResolvingDataSource.Factory resolving = new ResolvingDataSource.Factory(base, new ResolvingDataSource.Resolver() {
            @NonNull @Override public DataSpec resolveDataSpec(@NonNull DataSpec spec) throws IOException {
                Uri u = spec.uri;
                if (!"ytdl".equals(u.getScheme())) return spec;
                String id = u.toString().substring(Song.YT.length());   // keep case: YouTube ids are case-sensitive
                try {
                    Yt.Stream s = Yt.resolve(PlaybackService.this, id);
                    return spec.withUri(Uri.parse(s.url)).withAdditionalHeaders(s.headers);
                } catch (Exception e) {
                    throw new IOException("تعذر جلب رابط الأغنية: " + Yt.shortError(e.getMessage()), e);
                }
            }
        });

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(resolving))
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
                .build();
        player.setRepeatMode(store.repeat());
        player.setShuffleModeEnabled(store.shuffle());
        player.addListener(new Player.Listener() {
            @Override public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
                retries = 0;
                if (item != null) store.addRecent(Song.of(item));
                saveState();
            }
            @Override public void onRepeatModeChanged(int mode) { refreshButtons(); saveState(); }
            @Override public void onShuffleModeEnabledChanged(boolean on) { refreshButtons(); saveState(); }
            @Override public void onIsPlayingChanged(boolean playing) { saveState(); }
            @Override public void onPlayerError(@NonNull PlaybackException error) {
                // Expired / refused YouTube link: fetch a new one and try again once.
                MediaItem cur = player.getCurrentMediaItem();
                if (cur != null && cur.mediaId.startsWith(Song.YT) && retries < 1) {
                    retries++;
                    Yt.forget(cur.mediaId.substring(Song.YT.length()));
                    long pos = player.getCurrentPosition();
                    player.prepare();
                    player.seekTo(pos);
                    player.play();
                } else if (player.hasNextMediaItem()) {
                    retries = 0;
                    player.seekToNextMediaItem();
                    player.prepare();
                    player.play();
                }
            }
        });

        restore();

        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        session = new MediaSession.Builder(this, player)
                .setSessionActivity(pi)
                .setCallback(new Callback())
                .setCustomLayout(buttons())
                .build();
        handler.postDelayed(saver, 5000);
        if (sleepAt > System.currentTimeMillis()) handler.postDelayed(sleeper, sleepAt - System.currentTimeMillis());
    }

    /** Puts the last queue back (paused) so the user can carry on where they left off. */
    private void restore() {
        List<Song> q = store.sessionQueue();
        if (q.isEmpty()) return;
        List<MediaItem> items = new ArrayList<>();
        for (Song s : q) items.add(s.item());
        int idx = Math.max(0, Math.min(store.sessionIndex(), items.size() - 1));
        player.setMediaItems(items, idx, store.sessionPos());
    }

    private ImmutableList<CommandButton> buttons() {
        int rm = player == null ? Player.REPEAT_MODE_OFF : player.getRepeatMode();
        boolean sh = player != null && player.getShuffleModeEnabled();
        CommandButton repeat = new CommandButton.Builder(rm == Player.REPEAT_MODE_ONE ? CommandButton.ICON_REPEAT_ONE
                : rm == Player.REPEAT_MODE_ALL ? CommandButton.ICON_REPEAT_ALL : CommandButton.ICON_REPEAT_OFF)
                .setDisplayName(rm == Player.REPEAT_MODE_ONE ? "تكرار الأغنية" : rm == Player.REPEAT_MODE_ALL ? "تكرار القائمة" : "بدون تكرار")
                .setSessionCommand(new SessionCommand(CMD_REPEAT, Bundle.EMPTY))
                .build();
        CommandButton shuffle = new CommandButton.Builder(sh ? CommandButton.ICON_SHUFFLE_ON : CommandButton.ICON_SHUFFLE_OFF)
                .setDisplayName(sh ? "عشوائي: يعمل" : "عشوائي: متوقف")
                .setSessionCommand(new SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
                .build();
        return ImmutableList.of(repeat, shuffle);
    }

    private void refreshButtons() {
        if (session != null) session.setCustomLayout(buttons());
    }

    @Nullable @Override public MediaSession onGetSession(@NonNull MediaSession.ControllerInfo info) { return session; }

    @Override public void onTaskRemoved(@Nullable Intent rootIntent) {
        saveState();
        // Keep playing after the app is swiped away; stop only if nothing is playing.
        if (player == null || !player.getPlayWhenReady() || player.getMediaItemCount() == 0) stopSelf();
    }

    @Override public void onDestroy() {
        saveState();
        handler.removeCallbacksAndMessages(null);
        if (session != null) { session.release(); session = null; }
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }

    private void saveState() {
        if (player == null) return;
        List<Song> q = new ArrayList<>();
        int n = Math.min(player.getMediaItemCount(), 1000);
        for (int i = 0; i < n; i++) q.add(Song.of(player.getMediaItemAt(i)));
        store.saveSession(q, player.getCurrentMediaItemIndex(), Math.max(0, player.getCurrentPosition()),
                player.getRepeatMode(), player.getShuffleModeEnabled());
    }

    private final class Callback implements MediaSession.Callback {
        @NonNull @Override public MediaSession.ConnectionResult onConnect(@NonNull MediaSession s, @NonNull MediaSession.ControllerInfo c) {
            SessionCommands cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(new SessionCommand(CMD_REPEAT, Bundle.EMPTY))
                    .add(new SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
                    .add(new SessionCommand(CMD_SLEEP, Bundle.EMPTY))
                    .build();
            return new MediaSession.ConnectionResult.AcceptedResultBuilder(s)
                    .setAvailableSessionCommands(cmds)
                    .setCustomLayout(buttons())
                    .build();
        }

        @NonNull @Override public ListenableFuture<SessionResult> onCustomCommand(@NonNull MediaSession s, @NonNull MediaSession.ControllerInfo c,
                @NonNull SessionCommand cmd, @NonNull Bundle args) {
            switch (cmd.customAction) {
                case CMD_REPEAT: {
                    int m = player.getRepeatMode();
                    player.setRepeatMode(m == Player.REPEAT_MODE_OFF ? Player.REPEAT_MODE_ALL
                            : m == Player.REPEAT_MODE_ALL ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
                    break;
                }
                case CMD_SHUFFLE:
                    player.setShuffleModeEnabled(!player.getShuffleModeEnabled());
                    break;
                case CMD_SLEEP: {
                    int min = args.getInt("min", 0);
                    handler.removeCallbacks(sleeper);
                    if (min > 0) {
                        sleepAt = System.currentTimeMillis() + min * 60_000L;
                        handler.postDelayed(sleeper, min * 60_000L);
                    } else sleepAt = 0;
                    break;
                }
                default: break;
            }
            return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
        }

        @NonNull @Override public ListenableFuture<List<MediaItem>> onAddMediaItems(@NonNull MediaSession s,
                @NonNull MediaSession.ControllerInfo c, @NonNull List<MediaItem> items) {
            List<MediaItem> out = new ArrayList<>();
            for (MediaItem m : items) out.add(m.localConfiguration != null ? m : Song.of(m).item());
            return Futures.immediateFuture(out);
        }

        /** Bluetooth / lock-screen "play" after the app was closed: continue the last queue. */
        @NonNull @Override public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onPlaybackResumption(
                @NonNull MediaSession s, @NonNull MediaSession.ControllerInfo c) {
            List<MediaItem> items = new ArrayList<>();
            for (Song x : store.sessionQueue()) items.add(x.item());
            int idx = items.isEmpty() ? 0 : Math.max(0, Math.min(store.sessionIndex(), items.size() - 1));
            return Futures.immediateFuture(new MediaSession.MediaItemsWithStartPosition(items, idx, store.sessionPos()));
        }
    }
}
