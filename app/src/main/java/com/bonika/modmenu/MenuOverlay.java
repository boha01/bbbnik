package com.bonika.modmenu;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Build;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;

/**
 * MenuOverlay v2 — BOHA XVIP
 * Premium dark cyberpunk: black panel, neon purple #A855F7 + cyan #20D9F3,
 * rounded corners, glowing border, custom switches/sliders, 5 tabs.
 * Fixes v1: drag now binds to the live LayoutParams (v1 captured null -> menu froze),
 * header drag uses a movement threshold so buttons still click,
 * minimize/close HIDE smoothly to a floating bubble (nothing is removed).
 * All prefs keys and NativeBridge calls are identical to v1.
 */
public class MenuOverlay {
    static final int BG = 0xF208090D, CARD = 0xFF12141C, TRACK = 0xFF2A2E3A,
            PURPLE = 0xFFA855F7, CYAN = 0xFF20D9F3, TEXT = 0xFFF5F7FF, MUTED = 0xFF9AA0B5, GREEN = 0xFF3BFF7A;

    private final Context ctx;
    private final WindowManager wm;
    private final SharedPreferences prefs;
    private final float density;
    private WindowManager.LayoutParams params, bubbleParams;
    private LinearLayout root, body;
    private TextView bubble;
    private boolean haptic = true, bubbleAdded = false;

    public MenuOverlay(Context c) {
        ctx = c; wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        prefs = c.getSharedPreferences("modmenu", Context.MODE_PRIVATE);
        density = c.getResources().getDisplayMetrics().density;
        haptic = prefs.getBoolean("haptic", true);
        buildRoot();
        buildBubble();
    }

    private int dp(float v) { return (int) (v * density + 0.5f); }

    // ---------- window ----------
    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                           : WindowManager.LayoutParams.TYPE_PHONE;
    }
    public void show() {
        params = new WindowManager.LayoutParams(dp(340), WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START; params.x = dp(16); params.y = dp(48);
        applyOpacity();
        wm.addView(root, params);
        root.setAlpha(0f); root.setScaleX(0.92f); root.setScaleY(0.92f);
        root.animate().alpha(prefs.getFloat("opacity", 1f)).scaleX(1f).scaleY(1f)
                .setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
    }
    public void hide() {
        try { wm.removeView(root); } catch (Exception ignored) {}
        try { wm.removeView(bubble); } catch (Exception ignored) {}
        bubbleAdded = false;
    }
    private void applyOpacity() {
        float o = prefs.getFloat("opacity", 1f);
        if (root != null && root.getVisibility() == View.VISIBLE) root.setAlpha(o);
    }
    private GradientDrawable round(int color, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) d.setStroke(Math.max(1, dp(strokeDp)), strokeColor);
        return d;
    }
    private GradientDrawable gradient(int from, int to, float radiusDp) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{from, to});
        d.setCornerRadius(dp(radiusDp));
        return d;
    }
    private void click(View v) { if (haptic) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); }

    // ---------- root / header / tabs ----------
    private void buildRoot() {
        root = new LinearLayout(ctx); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(round(BG, 24, 0xCCA855F7, 1.2f));
        root.setPadding(dp(14), dp(12), dp(14), dp(14));
        root.setClipToOutline(false);

        // header (drag handle)
        LinearLayout header = new LinearLayout(ctx); header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(0, 0, 0, dp(10));

        TextView logo = label("B", 17, Color.WHITE); logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(gradient(PURPLE, CYAN, 12));
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        logoLp.setMargins(0, 0, dp(10), 0); logo.setLayoutParams(logoLp);
        header.addView(logo);

        LinearLayout titles = new LinearLayout(ctx); titles.setOrientation(LinearLayout.VERTICAL);
        titles.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout nameRow = new LinearLayout(ctx); nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = label("BOHA XVIP", 18, TEXT); name.setTypeface(Typeface.DEFAULT_BOLD);
        TextView badge = label("v1.0", 9, CYAN); badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setPadding(dp(7), dp(2), dp(7), dp(2));
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-2, -2); bLp.setMargins(dp(8), 0, 0, 0);
        badge.setLayoutParams(bLp); badge.setBackground(round(0x3320D9F3, 8, CYAN, 0.7f));
        nameRow.addView(name); nameRow.addView(badge);
        LinearLayout subRow = new LinearLayout(ctx); subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setGravity(Gravity.CENTER_VERTICAL); subRow.setPadding(0, dp(2), 0, 0);
        View dot = new View(ctx); LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dLp.setMargins(0, 0, dp(5), 0); dot.setLayoutParams(dLp);
        GradientDrawable dd = new GradientDrawable(); dd.setShape(GradientDrawable.OVAL); dd.setColor(GREEN); dot.setBackground(dd);
        TextView sub = label("CONTROL PANEL · ONLINE", 9, MUTED);
        subRow.addView(dot); subRow.addView(sub);
        titles.addView(nameRow); titles.addView(subRow);
        header.addView(titles);

        TextView min = roundBtn("–"); TextView close = roundBtn("✕");
        min.setOnClickListener(v -> { click(v); collapse(true); });
        close.setOnClickListener(v -> { click(v); collapse(true); }); // إغلاق = إخفاء سلس، الخدمة تبقى حية
        header.addView(min); header.addView(close);
        root.addView(header);
        makeDraggable(header, false);

        View sep = new View(ctx); sep.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.7f))));
        sep.setBackgroundColor(0x33A855F7); root.addView(sep);

        // tab bar
        LinearLayout tabs = new LinearLayout(ctx); tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(0, dp(10), 0, dp(8)); root.addView(tabs);

        body = new LinearLayout(ctx); body.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(ctx); scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(body);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(360)));
        root.addView(scroll);

        String[] names = {"PLAYER", "COMBAT", "VEHICLE", "VISUALS", "SETTINGS"};
        String[] icons = {"⬢", "✦", "➤", "◐", "⚙"};
        LinearLayout[] pages = {pagePlayer(), pageCombat(), pageVehicle(), pageVisuals(), pageSettings()};
        TextView[] tabViews = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = label(icons[i] + "\n" + names[i], 10, MUTED);
            t.setGravity(Gravity.CENTER); t.setPadding(0, dp(7), 0, dp(7));
            t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            t.setOnClickListener(v -> {
                click(v);
                for (int j = 0; j < pages.length; j++) {
                    pages[j].setVisibility(j == idx ? View.VISIBLE : View.GONE);
                    styleTab(tabViews[j], j == idx);
                }
                pages[idx].setAlpha(0f); pages[idx].setTranslationX(dp(8));
                pages[idx].animate().alpha(1f).translationX(0).setDuration(160).start();
            });
            tabViews[i] = t; tabs.addView(t); body.addView(pages[i]);
            if (i > 0) pages[i].setVisibility(View.GONE);
            styleTab(t, i == 0);
        }
    }
    private void styleTab(TextView t, boolean active) {
        if (active) { t.setBackground(gradient(PURPLE, 0xFF7C3AED, 12)); t.setTextColor(Color.WHITE); t.setTypeface(Typeface.DEFAULT_BOLD); }
        else { t.setBackground(null); t.setTextColor(MUTED); t.setTypeface(Typeface.DEFAULT); }
    }
    private TextView roundBtn(String s) {
        TextView t = label(s, 14, TEXT); t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
        lp.setMargins(dp(6), 0, 0, 0); t.setLayoutParams(lp);
        t.setBackground(round(CARD, 10, TRACK, 0.7f));
        return t;
    }

    // ---------- bubble (hidden state) ----------
    private void buildBubble() {
        bubble = label("B", 18, Color.WHITE); bubble.setTypeface(Typeface.DEFAULT_BOLD);
        bubble.setGravity(Gravity.CENTER);
        GradientDrawable d = gradient(PURPLE, CYAN, 28); d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(28)); d.setStroke(Math.max(1, dp(1)), CYAN);
        bubble.setBackground(d); bubble.setVisibility(View.GONE);
        bubble.setOnClickListener(v -> collapse(false));
    }
    private void collapse(boolean hideMenu) {
        if (hideMenu) {
            root.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(150)
                    .withEndAction(() -> { root.setVisibility(View.GONE); root.setScaleX(1f); root.setScaleY(1f); }).start();
            if (!bubbleAdded) {
                bubbleParams = new WindowManager.LayoutParams(dp(56), dp(56), overlayType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
                bubbleParams.gravity = Gravity.TOP | Gravity.START; bubbleParams.x = dp(14); bubbleParams.y = dp(120);
                wm.addView(bubble, bubbleParams); bubbleAdded = true;
                makeDraggable(bubble, true);
            }
            boolean stealth = prefs.getBoolean("stealth", false);
            bubble.setVisibility(stealth ? View.GONE : View.VISIBLE);
            if (!stealth) { bubble.setScaleX(0.6f); bubble.setScaleY(0.6f); bubble.setAlpha(0f);
                bubble.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start(); }
        } else {
            bubble.setVisibility(View.GONE);
            root.setVisibility(View.VISIBLE);
            root.setAlpha(0f); root.setScaleX(0.94f); root.setScaleY(0.94f);
            root.animate().alpha(prefs.getFloat("opacity", 1f)).scaleX(1f).scaleY(1f).setDuration(180)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    // ---------- drag (live params + threshold, clicks still work) ----------
    private void makeDraggable(final View v, final boolean isBubble) {
        v.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY; float touchX, touchY; boolean moved;
            public boolean onTouch(View view, MotionEvent e) {
                WindowManager.LayoutParams pr = isBubble ? bubbleParams : params;
                if (pr == null) return false;
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = pr.x; startY = pr.y; touchX = e.getRawX(); touchY = e.getRawY(); moved = false;
                        return false; // لا تستهلك اللمسة: الأزرار داخل الهيدر لازم تستقبل نقراتها
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - touchX), dy = (int) (e.getRawY() - touchY);
                        if (!moved && Math.abs(dx) + Math.abs(dy) > dp(6)) moved = true;
                        if (moved) {
                            pr.x = startX + dx; pr.y = startY + dy;
                            try { wm.updateViewLayout(isBubble ? bubble : root, pr); } catch (Exception ignored) {}
                            return true;
                        }
                        return false;
                    case MotionEvent.ACTION_UP:
                        return moved; // سحب = استهلك، نقرة نظيفة = اتركها للنقر
                }
                return false;
            }
        });
    }

    // ---------- widgets ----------
    private TextView label(String s, int sp, int color) { TextView t = new TextView(ctx); t.setText(s); t.setTextSize(sp); t.setTextColor(color); return t; }
    private LinearLayout page() {
        LinearLayout l = new LinearLayout(ctx); l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(2), dp(8), dp(2), dp(2)); return l;
    }
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(ctx); c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 14, TRACK, 0.7f));
        c.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, 0, 0, dp(10));
        c.setLayoutParams(lp); return c;
    }

    private interface BoolCb { void run(boolean v); }
    private interface FloatCb { void run(float v); }

    private void toggle(LinearLayout p, String key, String name, boolean def, BoolCb cb) {
        LinearLayout card = card(); p.addView(card);
        LinearLayout r = new LinearLayout(ctx); r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, dp(8), 0, dp(8));
        TextView t = label(name, 14, TEXT); t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        FrameLayout track = new FrameLayout(ctx);
        track.setLayoutParams(new LinearLayout.LayoutParams(dp(50), dp(28)));
        View thumb = new View(ctx);
        FrameLayout.LayoutParams thLp = new FrameLayout.LayoutParams(dp(22), dp(22));
        thLp.gravity = Gravity.CENTER_VERTICAL; thLp.setMargins(dp(3), 0, dp(3), 0);
        thumb.setLayoutParams(thLp);
        GradientDrawable th = new GradientDrawable(); th.setShape(GradientDrawable.OVAL); th.setColor(Color.WHITE);
        thumb.setBackground(th);
        track.addView(thumb); r.addView(t); r.addView(track); card.addView(r);

        final boolean[] state = {prefs.getBoolean(key, def)};
        Runnable paint = () -> {
            track.setBackground(round(state[0] ? PURPLE : TRACK, 14, 0, 0));
            thumb.animate().translationX(state[0] ? dp(22) : 0).setDuration(150).start();
        };
        paint.run(); thumb.setTranslationX(state[0] ? dp(22) : 0);
        if (state[0]) cb.run(true);
        r.setOnClickListener(v -> {
            state[0] = !state[0];
            prefs.edit().putBoolean(key, state[0]).apply(); click(v); paint.run(); cb.run(state[0]);
        });
    }
    private void slider(LinearLayout p, String key, String name, float min, float max, float def, String unit, FloatCb cb) {
        LinearLayout card = card(); p.addView(card);
        LinearLayout head = new LinearLayout(ctx); head.setOrientation(LinearLayout.HORIZONTAL);
        TextView t = label(name, 14, TEXT); t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        float saved = prefs.getFloat(key, def);
        TextView val = label(saved + unit, 13, CYAN); val.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(t); head.addView(val); card.addView(head);
        SeekBar s = new SeekBar(ctx); s.setMax(100);
        styleSeek(s);
        s.setProgress((int) ((saved - min) / (max - min) * 100)); cb.run(saved);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int pr, boolean u) {
                float v = min + (max - min) * pr / 100f; float r = Math.round(v * 10) / 10f;
                val.setText(r + unit); prefs.edit().putFloat(key, r).apply(); cb.run(r);
            } public void onStartTrackingTouch(SeekBar sb) {} public void onStopTrackingTouch(SeekBar sb) {}
        }); card.addView(s);
    }
    private void styleSeek(SeekBar s) {
        GradientDrawable thumbD = new GradientDrawable(); thumbD.setShape(GradientDrawable.OVAL);
        thumbD.setColor(CYAN); thumbD.setStroke(Math.max(1, dp(2)), PURPLE); thumbD.setSize(dp(18), dp(18));
        s.setThumb(thumbD);
        GradientDrawable trackD = round(TRACK, 4, 0, 0); trackD.setSize(-1, dp(6));
        GradientDrawable progD = gradient(PURPLE, CYAN, 4); progD.setSize(-1, dp(6));
        ClipDrawable clip = new ClipDrawable(progD, Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{trackD, clip});
        layers.setId(0, android.R.id.background); layers.setId(1, android.R.id.progress);
        s.setProgressDrawable(layers);
        s.setPadding(dp(4), dp(6), dp(4), dp(6));
    }
    private Button styledButton(String text, boolean primary) {
        Button b = new Button(ctx); b.setText(text); b.setAllCaps(false); b.setTextSize(13);
        b.setTextColor(primary ? Color.WHITE : TEXT);
        b.setBackground(primary ? gradient(PURPLE, 0xFF7C3AED, 12) : round(CARD, 12, PURPLE, 0.8f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(42)); lp.setMargins(0, dp(4), 0, dp(6));
        b.setLayoutParams(lp); return b;
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
        Button b = styledButton("Teleport to Waypoint", true);
        b.setOnClickListener(v -> { click(v); NativeBridge.teleportToWaypoint(); });
        p.addView(b); return p; }

    // ---------- D. VISUALS ----------
    private LinearLayout pageVisuals() { LinearLayout p = page();
        slider(p, "fov", "Camera FOV", 40, 120, 60, "°", NativeBridge::setFov);
        toggle(p, "night", "Night Vision", false, NativeBridge::setNightVision);
        toggle(p, "fly", "Free Fly Camera", false, NativeBridge::setFreeFly);
        LinearLayout card = card(); p.addView(card);
        card.addView(label("ESP Box Color", 14, TEXT));
        LinearLayout colors = new LinearLayout(ctx); colors.setOrientation(LinearLayout.HORIZONTAL);
        int[] palette = {0xFFA855F7, 0xFF20D9F3, 0xFFFF3B5C, 0xFF3BFF7A, 0xFFFFD93B, 0xFFFFFFFF};
        int selected = prefs.getInt("espColor", PURPLE);
        for (int c : palette) {
            View sw = new View(ctx); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
            lp.setMargins(dp(5), dp(10), dp(5), dp(4)); sw.setLayoutParams(lp);
            GradientDrawable d = new GradientDrawable(); d.setShape(GradientDrawable.OVAL); d.setColor(c);
            if (c == selected) d.setStroke(Math.max(1, dp(2)), Color.WHITE);
            sw.setBackground(d);
            sw.setOnClickListener(v -> { click(v); prefs.edit().putInt("espColor", c).apply(); NativeBridge.setEspBoxColor(c); });
            colors.addView(sw);
        }
        NativeBridge.setEspBoxColor(selected);
        card.addView(colors); return p; }

    // ---------- E. SETTINGS ----------
    private LinearLayout pageSettings() { LinearLayout p = page();
        slider(p, "opacity", "Menu Opacity", 0.3f, 1f, 1f, "", v -> { if (root != null) root.setAlpha(v); });
        toggle(p, "haptic", "Haptic Feedback", true, v -> haptic = v);
        toggle(p, "stealth", "Hide Menu Button (Stealth)", false, v -> {});
        Button save = styledButton("Save Settings", true);
        save.setOnClickListener(v -> { click(v); prefs.edit().apply(); Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show(); });
        Button def = styledButton("Load Defaults", false);
        def.setOnClickListener(v -> { click(v); prefs.edit().clear().apply(); Toast.makeText(ctx, "Defaults loaded - restart menu", Toast.LENGTH_SHORT).show(); });
        TextView pos = label("Player Pos: (waiting for hook…)", 12, MUTED);
        Button chk = styledButton("Refresh Position / Hook Status", false);
        chk.setOnClickListener(v -> { float[] xyz = NativeBridge.getPlayerPosition();
            pos.setText(NativeBridge.isHooked() && xyz != null
                ? String.format("Player Pos: %.2f, %.2f, %.2f", xyz[0], xyz[1], xyz[2])
                : "Player Pos: hook not ready yet"); });
        p.addView(save); p.addView(def); p.addView(chk); p.addView(pos); return p; }
}
