package com.bonika.modmenu;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** يُستدعى من أكتيفيتي اللعبة (انظر الـ Smali تحت). آمن للاستدعاء المتكرر. */
public class Loader {
    private static boolean started = false;
    public static void start(Context ctx) {
        if (started || ctx == null) return; started = true;
        try { System.loadLibrary("modmenu"); } catch (Throwable ignored) {}
        Intent i = new Intent(ctx, FloatingService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }
}
