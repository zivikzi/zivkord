package com.zvk.webviewsmoke;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

// Keeps a delete/scan run alive: a foreground notification (so Android won't kill
// or freeze the app) plus a partial wake lock (so the CPU keeps going if the
// screen dims). It makes NO network calls itself - the engine still runs in the
// WebView, network-locked to Discord. This service is purely keep-alive + status.
public class DeleteService extends Service {

    static final String CHANNEL = "zivkord_run";
    static final int NOTIF_ID = 1001;
    static final String ACTION_START = "com.zvk.webviewsmoke.START";
    static final String ACTION_UPDATE = "com.zvk.webviewsmoke.UPDATE";
    static final String ACTION_STOP = "com.zvk.webviewsmoke.STOP";
    static final String EXTRA_TEXT = "text";

    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL, "ZiVKord activity", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            releaseLock();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        String text = intent != null ? intent.getStringExtra(EXTRA_TEXT) : null;
        if (text == null) text = "Working...";

        Notification n = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
        if (ACTION_START.equals(action)) acquireLock();
        return START_NOT_STICKY;
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        int flag = (Build.VERSION.SDK_INT >= 23)
            ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flag);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
            ? new Notification.Builder(this, CHANNEL)
            : new Notification.Builder(this);
        return b.setContentTitle("ZiVKord")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build();
    }

    private void acquireLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zivkord:run");
        }
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(2 * 60 * 60 * 1000L); // 2h cap
    }

    private void releaseLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    @Override
    public void onDestroy() {
        releaseLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
