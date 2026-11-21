package com.bonika.modmenu;

import android.app.*;
import android.content.Intent;
import android.os.*;

/** Floating Service - يعيش طول ما اللعبة شغالة، ينشئ الـ Overlay. */
public class FloatingService extends Service {
    private MenuOverlay overlay;
    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("modmenu", "ModMenu",
                    NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
            startForeground(1337, new Notification.Builder(this, "modmenu")
                    .setContentTitle("Mod Menu").setSmallIcon(android.R.drawable.ic_menu_compass).build());
        }
        overlay = new MenuOverlay(this);
        overlay.show();
    }
    @Override public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }
    @Override public void onDestroy() { if (overlay != null) overlay.hide(); super.onDestroy(); }
    @Override public IBinder onBind(Intent i) { return null; }
}
