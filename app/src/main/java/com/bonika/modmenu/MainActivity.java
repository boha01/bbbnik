package com.bonika.modmenu;

import android.app.*;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.Toast;

/**
 * MainActivity — نقطة الدخول عند التشغيل كمطبيق مستقل.
 */
public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow display over other apps", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        startMenu();
        finish();
    }
    public static void startMenu(android.content.Context ctx) {
        Intent i = new Intent(ctx, FloatingService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }
    private void startMenu() { startMenu(this); }
}
