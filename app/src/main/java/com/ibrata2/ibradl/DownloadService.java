package com.ibrata2.ibradl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Keeps the app alive while yt-dlp downloads, with a progress notification. */
public final class DownloadService extends Service {
    private static final String CHANNEL = "downloads";
    private static final int ID = 4343;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!Yt.busy()) { finish(); return; }
            try { getSystemService(NotificationManager.class).notify(ID, build()); } catch (Throwable ignored) { }
            h.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "التحميلات", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(ID, build());
        } catch (Throwable ignored) { }
        h.removeCallbacks(tick);
        h.postDelayed(tick, 1000);
        return START_NOT_STICKY;
    }

    private Notification build() {
        Yt.Job j = Yt.current();
        String title = j != null ? j.song.title : "IBRA DL — التحميلات";
        int p = j != null ? Math.round(j.progress) : 0;
        boolean merging = j != null && "merging".equals(j.status);
        Intent open = new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_DOWNLOADS)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(merging ? "جاري التحويل…" : p + "%")
                .setProgress(100, p, merging || j == null)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build();
    }

    private void finish() {
        h.removeCallbacks(tick);
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() { h.removeCallbacks(tick); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
