package com.bonika.modmenu;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.*;
import android.widget.*;

/**
 * 02 - MenuOverlay: Cyberpunk floating UI
 * BG #08090D | Primary #A855F7 | Secondary #20D9F3 | Text #F5F7FF
 * Draggable (top bar) + Minimizable (icon) + Collapsible + Opacity + Tabs
 */
public class MenuOverlay {
    static final int BG = 0xE608090D, PURPLE = 0xFFA855F7, CYAN = 0xFF20D9F3, TEXT = 0xFFF5F7FF, MUTED = 0xFF9AA0B5;

    private final Context ctx;
    private final WindowManager wm;
    private final SharedPreferences prefs;
    private WindowManager.LayoutParams params;
    private LinearLayout root, body;
    private ImageView collapsedIcon;
    private boolean haptic = true;

    public MenuOverlay(Context c) {
        ctx = c; wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        prefs = c.getSharedPreferences("modmenu", Context.MODE_PRIVATE);
        haptic = prefs.getBoolean("haptic", true);
        buildRoot();
        buildCollapsedIcon();
    }

    // ---------- window ----------
    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                           : WindowManager.LayoutParams.TYPE_PHONE;
    }
    public void show() {
        params = new WindowManager.LayoutParams(900, WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START; params.x = 60; params.y = 120;
        applyOpacity();
        wm.addView(root, params);
    }
    public void hide() { try { wm.removeView(root); } catch (Exception ignored) {} try { wm.removeView(collapsedIcon); } catch (Exception ignored) {} }
    private void applyOpacity() {
        float o = prefs.getFloat("opacity", 1f);
        if (root != null) root.setAlpha(o);
    }
    private GradientDrawable panel() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(BG); d.setCornerRadius(36);
        d.setStroke(3, PURPLE);
        return d;
    }
    private void click(View v) { if (haptic) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); }

    // ---------- root / top bar / tabs ----------
    private void buildRoot() {
        root = new LinearLayout(ctx); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(panel()); root.setPadding(28, 20, 28, 24);

        LinearLayout top = new LinearLayout(ctx); top.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = label("◆ MOD MENU  PRO", 19, CYAN); title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        TextView min = btn("—"); TextView close = btn("✕");
        min.setOnClickListener(v -> { click(v); collapse(true); });
        close.setOnClickListener(v -> { click(v); collapse(true); }); // إغلاق = تصغير، الخدمة تبقى حية
        top.addView(title); top.addView(min); top.addView(close);
        root.addView(top); makeDraggable(top);

        LinearLayout tabs = new LinearLayout(ctx); tabs.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(tabs);

        body = new LinearLayout(ctx); body.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(ctx); scroll.addView(body);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(-1, 950));
        root.addView(scroll);

        String[] names = {"PLAYER", "COMBAT", "VEHICLE", "VISUALS", "SETTINGS"};
        LinearLayout[] pages = {pagePlayer(), pageCombat(), pageVehicle(), pageVisuals(), pageSettings()};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = label(names[i], 13, i == 0 ? CYAN : MUTED);
            t.setPadding(18, 14, 18, 14);
            t.setOnClickListener(v -> { click(v); for (LinearLayout p : pages) p.setVisibility(View.GONE); pages[idx].setVisibility(View.VISIBLE); });
            tabs.addView(t); body.addView(pages[i]);
            if (i > 0) pages[i].setVisibility(View.GONE);
        }
    }
    private void buildCollapsedIcon() {
        collapsedIcon = new ImageView(ctx);
        collapsedIcon.setImageResource(android.R.drawable.ic_menu_compass);
        collapsedIcon.setColorFilter(PURPLE); collapsedIcon.setPadding(18, 18, 18, 18);
        GradientDrawable d = new GradientDrawable(); d.setShape(GradientDrawable.OVAL); d.setColor(BG); d.setStroke(3, CYAN);
        collapsedIcon.setBackground(d); collapsedIcon.setVisibility(View.GONE);
        collapsedIcon.setOnClickListener(v -> collapse(false));
    }
    private void collapse(boolean hide) {
        if (hide) {
            if (collapsedIcon.getParent() == null) {
                WindowManager.LayoutParams p = new WindowManager.LayoutParams(150, 150, overlayType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
                p.gravity = Gravity.TOP | Gravity.START; p.x = 40; p.y = 200;
                wm.addView(collapsedIcon, p); makeDraggable(collapsedIcon, p);
            }
            collapsedIcon.setVisibility(prefs.getBoolean("stealth", false) ? View.GONE : View.VISIBLE);
            root.setVisibility(View.GONE);
        } else { collapsedIcon.setVisibility(View.GONE); root.setVisibility(View.VISIBLE); }
    }

    // ---------- widgets ----------
    private TextView label(String s, int sp, int color) { TextView t = new TextView(ctx); t.setText(s); t.setTextSize(sp); t.setTextColor(color); return t; }
    private TextView btn(String s) { TextView t = label(s, 18, TEXT); t.setPadding(22, 6, 22, 6); return t; }
    private LinearLayout page() { LinearLayout l = new LinearLayout(ctx); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(6, 16, 6, 6); return l; }

    private interface BoolCb { void run(boolean v); }
    private interface FloatCb { void run(float v); }

    private void toggle(LinearLayout p, String key, String name, boolean def, BoolCb cb) {
        LinearLayout r = new LinearLayout(ctx); r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(0, 12, 0, 12);
        TextView t = label(name, 15, TEXT); t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(ctx); boolean val = prefs.getBoolean(key, def); sw.setChecked(val);
        if (val) cb.run(true);
        sw.setOnCheckedChangeListener((b, v) -> { prefs.edit().putBoolean(key, v).apply(); click(b); cb.run(v); });
        r.addView(t); r.addView(sw); p.addView(r);
    }
    private void slider(LinearLayout p, String key, String name, float min, float max, float def, String unit, FloatCb cb) {
        TextView t = label(name + ": " + def + unit, 15, TEXT); p.addView(t);
        SeekBar s = new SeekBar(ctx); s.setMax(100);
        float saved = prefs.getFloat(key, def); s.setProgress((int) ((saved - min) / (max - min) * 100)); cb.run(saved);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int pr, boolean u) {
                float v = min + (max - min) * pr / 100f; float r = Math.round(v * 10) / 10f;
                t.setText(name + ": " + r + unit); prefs.edit().putFloat(key, r).apply(); cb.run(r);
            } public void onStartTrackingTouch(SeekBar sb) {} public void onStopTrackingTouch(SeekBar sb) {}
        }); p.addView(s);
    }

    // ---------- A. PLAYER ----------
    private LinearLayout pagePlayer() { LinearLayout p = page();
        toggle(p, "esp", "Player ESP (Box/Name/Health/Dist)", false, NativeBridge::setEspEnabled);
        slider(p, "espDist", "ESP Distance", 50, 1000, 500, "m", NativeBridge::setEspDistance);
        toggle(p, "skeleton", "Skeleton ESP (Bones)", false, NativeBridge::setSkeletonEsp);
        toggle(p, "npcs", "Show NPCs", true, NativeBridge::setShowNpcs);
        toggle(p, "vehiclesEsp", "Show Vehicles", false, NativeBridge::setShowVehicles);
        return p; }

    // ---------- B. COMBAT ----------
    private LinearLayout pageCombat() { LinearLayout p = page();
        toggle(p, "god", "God Mode (No Damage)", false, NativeBridge::setGodMode);
        toggle(p, "ammo", "Infinite Ammo", false, NativeBridge::setInfiniteAmmo);
        toggle(p, "rapid", "Rapid Fire", false, NativeBridge::setRapidFire);
        toggle(p, "recoil", "No Recoil", false, NativeBridge::setNoRecoil);
        return p; }

    // ---------- C. VEHICLE ----------
    private LinearLayout pageVehicle() { LinearLayout p = page();
        toggle(p, "vehSpeedOn", "Speed Hack", false, NativeBridge::setVehicleSpeedEnabled);
        slider(p, "vehSpeed", "Speed Multiplier", 1, 10, 1, "x", NativeBridge::setVehicleSpeedMultiplier);
        toggle(p, "brake", "Instant Brake", false, NativeBridge::setInstantBrake);
        Button b = new Button(ctx); b.setText("Teleport to Waypoint"); b.setTextColor(TEXT);
        b.setOnClickListener(v -> { click(v); NativeBridge.teleportToWaypoint(); });
        p.addView(b); return p; }

    // ---------- D. VISUALS ----------
    private LinearLayout pageVisuals() { LinearLayout p = page();
        slider(p, "fov", "Camera FOV", 40, 120, 60, "°", NativeBridge::setFov);
        toggle(p, "night", "Night Vision", false, NativeBridge::setNightVision);
        toggle(p, "fly", "Free Fly Camera", false, NativeBridge::setFreeFly);
        p.addView(label("ESP Box Color", 15, TEXT));
        LinearLayout colors = new LinearLayout(ctx); colors.setOrientation(LinearLayout.HORIZONTAL);
        int[] palette = {0xFFA855F7, 0xFF20D9F3, 0xFFFF3B5C, 0xFF3BFF7A, 0xFFFFD93B, 0xFFFFFFFF};
        for (int c : palette) {
            View sw = new View(ctx); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(72, 72); lp.setMargins(10, 10, 10, 10);
            sw.setLayoutParams(lp); GradientDrawable d = new GradientDrawable(); d.setShape(GradientDrawable.OVAL); d.setColor(c); sw.setBackground(d);
            sw.setOnClickListener(v -> { click(v); prefs.edit().putInt("espColor", c).apply(); NativeBridge.setEspBoxColor(c); });
            colors.addView(sw);
        }
        NativeBridge.setEspBoxColor(prefs.getInt("espColor", PURPLE));
        p.addView(colors); return p; }

    // ---------- E. SETTINGS ----------
    private LinearLayout pageSettings() { LinearLayout p = page();
        slider(p, "opacity", "Menu Opacity", 0.3f, 1f, 1f, "", v -> applyOpacity());
        toggle(p, "haptic", "Haptic Feedback", true, v -> haptic = v);
        toggle(p, "stealth", "Hide Menu Button (Stealth)", false, v -> {});
        Button save = new Button(ctx); save.setText("Save Settings");
        save.setOnClickListener(v -> { click(v); prefs.edit().apply(); Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show(); });
        Button def = new Button(ctx); def.setText("Load Defaults");
        def.setOnClickListener(v -> { click(v); prefs.edit().clear().apply(); Toast.makeText(ctx, "Defaults loaded - restart menu", Toast.LENGTH_SHORT).show(); });
        TextView pos = label("Player Pos: (waiting for hook…)", 12, MUTED);
        Button chk = new Button(ctx); chk.setText("Refresh Position / Hook Status");
        chk.setOnClickListener(v -> { float[] xyz = NativeBridge.getPlayerPosition();
            pos.setText(NativeBridge.isHooked() && xyz != null
                ? String.format("Player Pos: %.2f, %.2f, %.2f", xyz[0], xyz[1], xyz[2])
                : "Player Pos: hook not ready yet"); });
        p.addView(save); p.addView(def); p.addView(chk); p.addView(pos); return p; }

    // ---------- drag ----------
    private void makeDraggable(View v) { makeDraggable(v, params); }
    private void makeDraggable(View v, WindowManager.LayoutParams pr) {
        v.setOnTouchListener(new View.OnTouchListener() { int sx, sy; float tx, ty;
            public boolean onTouch(View view, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) { sx = pr.x; sy = pr.y; tx = e.getRawX(); ty = e.getRawY(); return true; }
                if (e.getAction() == MotionEvent.ACTION_MOVE) { pr.x = sx + (int)(e.getRawX()-tx); pr.y = sy + (int)(e.getRawY()-ty); try { wm.updateViewLayout(v == collapsedIcon ? collapsedIcon : root, pr); } catch (Exception ignored) {} return true; }
                return false; } });
    }
}
