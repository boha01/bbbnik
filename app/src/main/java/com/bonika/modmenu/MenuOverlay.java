package com.bonika.modmenu;

import android.app.Service;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.*;
import android.text.style.*;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;

/**
 * MenuOverlay v4 — boha xvip (long-press drag added)
 * Black / red redesign matching bonika's reference image:
 * near-black panel, left sidebar (Home / Aim / Visual / Misc / Config),
 * red toggles and sliders, his circular logo in the header, status footer.
 * Dragging binds to the LIVE LayoutParams with a touch threshold (v1 bug fix kept),
 * minimize hides smoothly to a floating bubble (nothing is removed),
 * X / "Remove Menu Completely" removes BOTH views and stops FloatingService.
 * Bottom-right grip resizes the panel live (width + height, clamped, saved as menuW/menuH).
 * All NativeBridge calls and prefs keys from v2 are preserved unchanged.
 * Logo: reads drawable "boha_logo" if present, otherwise falls back to a red "B" circle.
 */
public class MenuOverlay {
    static final int BG = 0xF50A0A0C, PANEL = 0xFF111114, SIDEBAR = 0xFF0D0D10,
            CARD = 0xFF17171B, STROKE = 0xFF26262C, TRACK = 0xFF2A2A30,
            RED = 0xFFE8112D, RED2 = 0xFFFF1E3C, TEXT = 0xFFFFFFFF,
            MUTED = 0xFF9A9AA3, GREEN = 0xFF3BFF7A;

    private final Context ctx;
    private final WindowManager wm;
    private final SharedPreferences prefs;
    private final float density;
    private WindowManager.LayoutParams params, bubbleParams;
    private FrameLayout root;
    private LinearLayout body;
    private View bubble;
    private boolean haptic = true, bubbleAdded = false, removing = false;

    public MenuOverlay(Context c) {
        ctx = c; wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        prefs = c.getSharedPreferences("modmenu", Context.MODE_PRIVATE);
        density = c.getResources().getDisplayMetrics().density;
        haptic = prefs.getBoolean("haptic", true);
        buildRoot();
        buildBubble();
    }

    private int dp(float v) { return (int) (v * density + 0.5f); }
    private int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ---------- window ----------
    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                           : WindowManager.LayoutParams.TYPE_PHONE;
    }
    public void show() {
        int w = prefs.getInt("menuW", dp(460));
        int h = prefs.getInt("menuH", dp(300));
        w = clamp(w, dp(320), dp(720)); h = clamp(h, dp(230), dp(540));
        params = new WindowManager.LayoutParams(w, h,
                overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = prefs.getInt("menuX", dp(16)); params.y = prefs.getInt("menuY", dp(48));
        wm.addView(root, params);
        applyOpacity();
        root.setAlpha(0f); root.setScaleX(0.92f); root.setScaleY(0.92f);
        root.animate().alpha(prefs.getFloat("opacity", 1f)).scaleX(1f).scaleY(1f)
                .setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
    }
    /** Full removal of both views. FloatingService.onDestroy calls this. */
    public void hide() {
        try { wm.removeView(root); } catch (Exception ignored) {}
        try { if (bubble != null) wm.removeView(bubble); } catch (Exception ignored) {}
        bubbleAdded = false;
    }
    /** X button / Config remove: fade out, remove everything, stop the service. */
    private void removeCompletely() {
        if (removing) return; removing = true;
        root.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(150)
                .withEndAction(() -> {
                    hide();
                    if (ctx instanceof Service) ((Service) ctx).stopSelf();
                    else try { ctx.stopService(new Intent(ctx, FloatingService.class)); } catch (Exception ignored) {}
                }).start();
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
    private GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable(); d.setShape(GradientDrawable.OVAL); d.setColor(color);
        return d;
    }
    private void click(View v) { if (haptic) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); }

    // ---------- text helpers ----------
    private TextView label(String s, float sp, int color) {
        TextView t = new TextView(ctx); t.setText(s); t.setTextSize(sp); t.setTextColor(color); return t;
    }
    /** "boha" in white bold + second word in red bold, like the reference logo text. */
    private TextView twoTone(String first, String second, float sp) {
        SpannableStringBuilder sb = new SpannableStringBuilder(first + second);
        sb.setSpan(new ForegroundColorSpan(Color.WHITE), 0, first.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, first.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(RED2), first.length(), sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), first.length(), sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView t = new TextView(ctx); t.setText(sb); t.setTextSize(sp); return t;
    }
    /** Circular logo: the boha_logo drawable if it exists, else a red/black "B" circle. */
    private View logoView(int sizeDp) {
        int resId = 0;
        try { resId = ctx.getResources().getIdentifier("boha_logo", "drawable", ctx.getPackageName()); }
        catch (Exception ignored) {}
        if (resId != 0) {
            ImageView iv = new ImageView(ctx);
            iv.setImageResource(resId); iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setClipToOutline(true);
            iv.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
            });
            iv.setBackground(oval(0xFF000000));
            return iv;
        }
        TextView t = label("B", 17, Color.WHITE); t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        GradientDrawable d = gradient(RED, 0xFF5A0A14, sizeDp / 2f); d.setShape(GradientDrawable.OVAL);
        d.setStroke(Math.max(1, dp(1)), RED2);
        t.setBackground(d);
        return t;
    }

    // ---------- root / header / sidebar / footer ----------
    private void buildRoot() {
        root = new FrameLayout(ctx);
        root.setBackground(round(BG, 16, RED, 1f));

        LinearLayout panel = new LinearLayout(ctx); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));

        // ----- header (drag handle) -----
        LinearLayout header = new LinearLayout(ctx); header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(0, 0, 0, dp(8));

        View logo = logoView(44);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        logoLp.setMargins(0, 0, dp(10), 0); logo.setLayoutParams(logoLp);
        header.addView(logo);

        LinearLayout titles = new LinearLayout(ctx); titles.setOrientation(LinearLayout.VERTICAL);
        titles.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        titles.addView(twoTone("boha", " xvip", 18));
        LinearLayout subRow = new LinearLayout(ctx); subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setGravity(Gravity.CENTER_VERTICAL); subRow.setPadding(0, dp(1), 0, 0);
        TextView mod = label("MOD MENU  ", 9, MUTED); mod.setTypeface(Typeface.DEFAULT_BOLD);
        TextView ver = label("v1.0.0", 9, RED2); ver.setTypeface(Typeface.DEFAULT_BOLD);
        subRow.addView(mod); subRow.addView(ver);
        titles.addView(subRow);
        header.addView(titles);

        // Online indicator, right of the title block
        View dot = new View(ctx);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dLp.setMargins(0, 0, dp(5), 0); dot.setLayoutParams(dLp); dot.setBackground(oval(GREEN));
        TextView online = label("Online", 10, GREEN);
        LinearLayout onlineRow = new LinearLayout(ctx); onlineRow.setOrientation(LinearLayout.HORIZONTAL);
        onlineRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams oLp = new LinearLayout.LayoutParams(-2, -2); oLp.setMargins(0, 0, dp(10), 0);
        onlineRow.setLayoutParams(oLp);
        onlineRow.addView(dot); onlineRow.addView(online);
        header.addView(onlineRow);

        TextView min = squareBtn("–", false);
        TextView close = squareBtn("✕", true);
        min.setOnClickListener(v -> { click(v); collapse(true); });      // تصغير = إخفاء سلس لفقاعة
        close.setOnClickListener(v -> { click(v); removeCompletely(); }); // X = نزع كامل + إيقاف الخدمة
        header.addView(min); header.addView(close);
        panel.addView(header);
        makeDraggable(header, false);

        View sep = new View(ctx);
        sep.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.7f))));
        sep.setBackgroundColor(STROKE); panel.addView(sep);

        // ----- middle: sidebar + content -----
        LinearLayout middle = new LinearLayout(ctx); middle.setOrientation(LinearLayout.HORIZONTAL);
        middle.setPadding(0, dp(8), 0, dp(8));
        middle.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        panel.addView(middle);

        LinearLayout sidebar = new LinearLayout(ctx); sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setBackground(round(SIDEBAR, 14, STROKE, 0.7f));
        sidebar.setPadding(dp(6), dp(8), dp(6), dp(8));
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(dp(86), -1);
        sbLp.setMargins(0, 0, dp(10), 0); sidebar.setLayoutParams(sbLp);
        middle.addView(sidebar);

        body = new LinearLayout(ctx); body.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(ctx); scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(body);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1f));
        middle.addView(scroll);

        LinearLayout[] pages = {pageHome(), pageAim(), pageVisual(), pageMisc(), pageConfig()};
        String[] names = {"Home", "Aim", "Visual", "Misc", "Config"};
        String[] icons = {"⌂", "⌖", "◉", "☰", "⚙"};
        LinearLayout[] sideRows = new LinearLayout[names.length];
        TextView[] sideIcons = new TextView[names.length];
        TextView[] sideLabels = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(ctx); item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER); item.setPadding(0, dp(9), 0, dp(9));
            LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(-1, -2);
            iLp.setMargins(0, 0, 0, dp(4)); item.setLayoutParams(iLp);
            TextView ic = label(icons[i], 17, MUTED); ic.setGravity(Gravity.CENTER);
            TextView lb = label(names[i], 11, MUTED); lb.setGravity(Gravity.CENTER);
            item.addView(ic); item.addView(lb);
            item.setOnClickListener(v -> {
                click(v);
                for (int j = 0; j < pages.length; j++) {
                    pages[j].setVisibility(j == idx ? View.VISIBLE : View.GONE);
                    styleSide(sideRows[j], sideIcons[j], sideLabels[j], j == idx);
                }
                pages[idx].setAlpha(0f); pages[idx].setTranslationX(dp(8));
                pages[idx].animate().alpha(1f).translationX(0).setDuration(160).start();
            });
            sideRows[i] = item; sideIcons[i] = ic; sideLabels[i] = lb;
            sidebar.addView(item);
            body.addView(pages[i]);
            if (i > 0) pages[i].setVisibility(View.GONE);
            styleSide(item, ic, lb, i == 0);
        }

        // sidebar bottom: crown + boha xvip
        View spacer = new View(ctx); spacer.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        sidebar.addView(spacer);
        TextView crown = label("♛", 15, RED2); crown.setGravity(Gravity.CENTER);
        sidebar.addView(crown);
        TextView brand = twoTone("boha", " xvip", 11); brand.setGravity(Gravity.CENTER);
        sidebar.addView(brand);

        // ----- footer: status + reset -----
        View sep2 = new View(ctx);
        sep2.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.7f))));
        sep2.setBackgroundColor(STROKE); panel.addView(sep2);

        LinearLayout footer = new LinearLayout(ctx); footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL); footer.setPadding(0, dp(8), dp(2), 0);
        TextView info = label("ⓘ", 14, RED2);
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(-2, -2);
        inLp.setMargins(0, 0, dp(6), 0); info.setLayoutParams(inLp);
        boolean hooked = false;
        try { hooked = NativeBridge.isHooked(); } catch (Throwable ignored) {}
        TextView status = label("Status : " + (hooked ? "Features Loaded" : "Waiting for hook…"), 11, MUTED);
        status.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        Button reset = new Button(ctx); reset.setText("🗑  Reset"); reset.setAllCaps(false); reset.setTextSize(11);
        reset.setTextColor(Color.WHITE); reset.setTypeface(Typeface.DEFAULT_BOLD);
        reset.setBackground(gradient(RED, RED2, 10));
        reset.setPadding(dp(12), 0, dp(12), 0);
        reset.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(30)));
        reset.setOnClickListener(v -> {
            click(v); prefs.edit().clear().apply();
            Toast.makeText(ctx, "Settings reset - restart menu", Toast.LENGTH_SHORT).show();
        });
        footer.addView(info); footer.addView(status); footer.addView(reset);
        panel.addView(footer);

        // ----- resize grip, bottom-right corner, overlaid on the panel -----
        TextView grip = label("◢", 15, RED2); grip.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams gLp = new FrameLayout.LayoutParams(dp(30), dp(30));
        gLp.gravity = Gravity.BOTTOM | Gravity.END;
        grip.setLayoutParams(gLp);
        root.addView(grip);
        makeResizable(grip);
    }
    private void styleSide(LinearLayout row, TextView icon, TextView label, boolean active) {
        if (active) {
            row.setBackground(gradient(RED, RED2, 12));
            icon.setTextColor(Color.WHITE); label.setTextColor(Color.WHITE);
            label.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            row.setBackground(null);
            icon.setTextColor(MUTED); label.setTextColor(MUTED);
            label.setTypeface(Typeface.DEFAULT);
        }
    }
    private TextView squareBtn(String s, boolean isClose) {
        TextView t = label(s, 14, isClose ? RED2 : TEXT); t.setGravity(Gravity.CENTER);
        if (isClose) t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(32));
        lp.setMargins(dp(6), 0, 0, 0); t.setLayoutParams(lp);
        t.setBackground(isClose ? round(0xFF2A0A10, 10, RED, 1f) : round(CARD, 10, STROKE, 0.7f));
        return t;
    }

    // ---------- bubble (hidden state) ----------
    private void buildBubble() {
        FrameLayout b = new FrameLayout(ctx);
        View inner = logoView(56);
        b.addView(inner, new FrameLayout.LayoutParams(-1, -1));
        if (!(inner instanceof ImageView)) b.setBackground(oval(0x00000000));
        bubble = b;
        bubble.setVisibility(View.GONE);
        bubble.setOnClickListener(v -> collapse(false));
    }
    private void collapse(boolean hideMenu) {
        if (hideMenu) {
            root.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(150)
                    .withEndAction(() -> { root.setVisibility(View.GONE); root.setScaleX(1f); root.setScaleY(1f); }).start();
            if (!bubbleAdded) {
                bubbleParams = new WindowManager.LayoutParams(dp(56), dp(56), overlayType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
                bubbleParams.gravity = Gravity.TOP | Gravity.START;
                bubbleParams.x = prefs.getInt("bubbleX", dp(14)); bubbleParams.y = prefs.getInt("bubbleY", dp(120));
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

    // ---------- drag: LONG-PRESS then move (v4, bonika's request) ----------
    // A normal tap does nothing to position (buttons / toggles / sliders keep working).
    // Press and HOLD ~400ms on the header (or on the bubble) -> drag activates with a
    // small haptic + scale pop, then moving the finger moves the whole window smoothly
    // by updating the LIVE LayoutParams. Releasing the finger ends the drag.
    private void makeDraggable(final View v, final boolean isBubble) {
        final Handler handler = new Handler(Looper.getMainLooper());
        v.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY; float touchX, touchY;
            boolean longPressed = false, cancelled = false;
            final Runnable activateDrag = () -> {
                if (cancelled) return;
                longPressed = true;
                if (haptic) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                View target = isBubble ? bubble : root;
                if (target != null) target.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).start();
            };
            public boolean onTouch(View view, MotionEvent e) {
                WindowManager.LayoutParams pr = isBubble ? bubbleParams : params;
                if (pr == null) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = pr.x; startY = pr.y; touchX = e.getRawX(); touchY = e.getRawY();
                        longPressed = false; cancelled = false;
                        handler.removeCallbacks(activateDrag);
                        handler.postDelayed(activateDrag, 400); // الضغط المطوّل
                        return false; // لا تستهلك: نقرة عادية لازم توصل للأزرار
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - touchX), dy = (int) (e.getRawY() - touchY);
                        if (!longPressed) {
                            // تحرّك كثير قبل ما يكمل الضغط المطوّل = تمرير عادي، ألغِ التفعيل
                            if (Math.abs(dx) + Math.abs(dy) > dp(10)) {
                                cancelled = true; handler.removeCallbacks(activateDrag);
                            }
                            return false;
                        }
                        pr.x = startX + dx; pr.y = startY + dy;
                        try { wm.updateViewLayout(isBubble ? bubble : root, pr); } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(activateDrag);
                        if (longPressed) {
                            View target = isBubble ? bubble : root;
                            if (target != null) target.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                            // احفظ آخر موقع عشان يرجع مثل ما تركته
                            try { prefs.edit().putInt(isBubble ? "bubbleX" : "menuX", pr.x)
                                    .putInt(isBubble ? "bubbleY" : "menuY", pr.y).apply(); } catch (Exception ignored) {}
                            return true; // سحب بالضغط المطوّل = استهلك اللمسة
                        }
                        return false;
                }
                return false;
            }
        });
    }

    // ---------- resize grip (bottom-right corner, live LayoutParams) ----------
    private void makeResizable(final View grip) {
        grip.setOnTouchListener(new View.OnTouchListener() {
            int startW, startH; float touchX, touchY;
            public boolean onTouch(View view, MotionEvent e) {
                if (params == null) return false;
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startW = params.width; startH = params.height;
                        touchX = e.getRawX(); touchY = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.width = clamp(startW + (int) (e.getRawX() - touchX), dp(320), dp(720));
                        params.height = clamp(startH + (int) (e.getRawY() - touchY), dp(230), dp(540));
                        try { wm.updateViewLayout(root, params); } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        prefs.edit().putInt("menuW", params.width).putInt("menuH", params.height).apply();
                        return true;
                }
                return false;
            }
        });
    }

    // ---------- widgets ----------
    private LinearLayout page() {
        LinearLayout l = new LinearLayout(ctx); l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(2), dp(2), dp(2), dp(2)); return l;
    }
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(ctx); c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 14, STROKE, 0.7f));
        c.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, 0, 0, dp(10));
        c.setLayoutParams(lp); return c;
    }
    private void sectionHead(LinearLayout p, String title, String subtitle) {
        TextView t = label(title, 15, TEXT); t.setTypeface(Typeface.DEFAULT_BOLD);
        TextView s = label(subtitle, 10, MUTED); s.setPadding(0, dp(2), 0, dp(10));
        p.addView(t); p.addView(s);
    }

    private interface BoolCb { void run(boolean v); }
    private interface FloatCb { void run(float v); }

    /** Feature row like the reference: red icon + bold name + muted description + red toggle. */
    private void featureToggle(LinearLayout p, String key, String icon, String name, String desc, boolean def, BoolCb cb) {
        LinearLayout card = card(); p.addView(card);
        LinearLayout r = new LinearLayout(ctx); r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, dp(7), 0, dp(7));
        if (icon != null && !icon.isEmpty()) {
            TextView ic = label(icon, 16, RED2); ic.setGravity(Gravity.CENTER);
            ic.setLayoutParams(new LinearLayout.LayoutParams(dp(30), -2));
            r.addView(ic);
        }
        LinearLayout texts = new LinearLayout(ctx); texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        TextView t = label(name, 13, TEXT); t.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(t);
        if (desc != null && !desc.isEmpty()) {
            TextView d = label(desc, 10, MUTED); d.setPadding(0, dp(1), 0, 0);
            texts.addView(d);
        }
        FrameLayout track = new FrameLayout(ctx);
        track.setLayoutParams(new LinearLayout.LayoutParams(dp(50), dp(28)));
        View thumb = new View(ctx);
        FrameLayout.LayoutParams thLp = new FrameLayout.LayoutParams(dp(22), dp(22));
        thLp.gravity = Gravity.CENTER_VERTICAL; thLp.setMargins(dp(3), 0, dp(3), 0);
        thumb.setLayoutParams(thLp);
        thumb.setBackground(oval(Color.WHITE));
        track.addView(thumb);
        r.addView(texts); r.addView(track); card.addView(r);

        final boolean[] state = {prefs.getBoolean(key, def)};
        Runnable paint = () -> {
            track.setBackground(round(state[0] ? RED : TRACK, 14, 0, 0));
            thumb.animate().translationX(state[0] ? dp(22) : 0).setDuration(150).start();
        };
        paint.run(); thumb.setTranslationX(state[0] ? dp(22) : 0);
        if (state[0]) cb.run(true);
        r.setOnClickListener(v -> {
            state[0] = !state[0];
            prefs.edit().putBoolean(key, state[0]).apply(); click(v); paint.run(); cb.run(state[0]);
        });
    }
    private void toggle(LinearLayout p, String key, String name, boolean def, BoolCb cb) {
        featureToggle(p, key, null, name, null, def, cb);
    }

    /** Slider row like the reference: name + description, value in a dark pill badge, red slider. */
    private void featureSlider(LinearLayout p, String key, String icon, String name, String desc,
                               float min, float max, float def, String unit, FloatCb cb) {
        LinearLayout card = card(); p.addView(card);
        LinearLayout head = new LinearLayout(ctx); head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        if (icon != null && !icon.isEmpty()) {
            TextView ic = label(icon, 16, RED2); ic.setGravity(Gravity.CENTER);
            ic.setLayoutParams(new LinearLayout.LayoutParams(dp(30), -2));
            head.addView(ic);
        }
        LinearLayout texts = new LinearLayout(ctx); texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        TextView t = label(name, 13, TEXT); t.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(t);
        if (desc != null && !desc.isEmpty()) {
            TextView d = label(desc, 10, MUTED); d.setPadding(0, dp(1), 0, 0);
            texts.addView(d);
        }
        float saved = prefs.getFloat(key, def);
        TextView val = label(saved + unit, 12, TEXT); val.setTypeface(Typeface.DEFAULT_BOLD);
        val.setGravity(Gravity.CENTER);
        val.setPadding(dp(10), dp(3), dp(10), dp(3));
        val.setBackground(round(0xFF0D0D10, 9, STROKE, 0.7f));
        head.addView(texts); head.addView(val); card.addView(head);
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
    private void slider(LinearLayout p, String key, String name, float min, float max, float def, String unit, FloatCb cb) {
        featureSlider(p, key, null, name, null, min, max, def, unit, cb);
    }
    private void styleSeek(SeekBar s) {
        GradientDrawable thumbD = new GradientDrawable(); thumbD.setShape(GradientDrawable.OVAL);
        thumbD.setColor(RED2); thumbD.setStroke(Math.max(1, dp(2)), Color.WHITE); thumbD.setSize(dp(18), dp(18));
        s.setThumb(thumbD);
        GradientDrawable trackD = round(TRACK, 4, 0, 0); trackD.setSize(-1, dp(6));
        GradientDrawable progD = gradient(RED, RED2, 4); progD.setSize(-1, dp(6));
        ClipDrawable clip = new ClipDrawable(progD, Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{trackD, clip});
        layers.setId(0, android.R.id.background); layers.setId(1, android.R.id.progress);
        s.setProgressDrawable(layers);
        s.setPadding(dp(4), dp(6), dp(4), dp(6));
    }
    private Button styledButton(String text, boolean primary) {
        Button b = new Button(ctx); b.setText(text); b.setAllCaps(false); b.setTextSize(13);
        b.setTextColor(primary ? Color.WHITE : TEXT);
        if (primary) b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(primary ? gradient(RED, RED2, 12) : round(CARD, 12, STROKE, 0.8f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(42)); lp.setMargins(0, dp(4), 0, dp(6));
        b.setLayoutParams(lp); return b;
    }
    private Button dangerButton(String text) {
        Button b = new Button(ctx); b.setText(text); b.setAllCaps(false); b.setTextSize(13);
        b.setTextColor(Color.WHITE); b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(round(0xFF2A0A10, 12, RED, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(42)); lp.setMargins(0, dp(4), 0, dp(6));
        b.setLayoutParams(lp); return b;
    }

    // ---------- HOME (reference page: Main Features) ----------
    private LinearLayout pageHome() { LinearLayout p = page();
        sectionHead(p, "Main Features", "Enable or disable the features you want");
        featureToggle(p, "recoil", "⚡", "No Recoil", "Remove recoil from weapons", false, NativeBridge::setNoRecoil);
        featureToggle(p, "vehSpeedOn", "➤", "Speed Hack", "Increase your speed", false, NativeBridge::setVehicleSpeedEnabled);
        featureToggle(p, "esp", "◉", "ESP", "Show enemies through walls", false, NativeBridge::setEspEnabled);
        featureToggle(p, "god", "✚", "God Mode", "No damage taken", false, NativeBridge::setGodMode);
        featureSlider(p, "vehSpeed", "↕", "Speed Multiplier", "Adjust vehicle speed multiplier", 1, 10, 1, "x", NativeBridge::setVehicleSpeedMultiplier);
        return p; }

    // ---------- AIM (combat features) ----------
    private LinearLayout pageAim() { LinearLayout p = page();
        sectionHead(p, "Aim / Combat", "Combat features");
        toggle(p, "god", "God Mode (No Damage)", false, NativeBridge::setGodMode);
        toggle(p, "ammo", "Infinite Ammo", false, NativeBridge::setInfiniteAmmo);
        toggle(p, "rapid", "Rapid Fire", false, NativeBridge::setRapidFire);
        toggle(p, "recoil", "No Recoil", false, NativeBridge::setNoRecoil);
        return p; }

    // ---------- VISUAL (player ESP + visuals features) ----------
    private LinearLayout pageVisual() { LinearLayout p = page();
        sectionHead(p, "Visual", "ESP and visual features");
        toggle(p, "esp", "Player ESP (Box/Name/Health/Dist)", false, NativeBridge::setEspEnabled);
        slider(p, "espDist", "ESP Distance", 50, 1000, 500, "m", NativeBridge::setEspDistance);
        toggle(p, "skeleton", "Skeleton ESP (Bones)", false, NativeBridge::setSkeletonEsp);
        toggle(p, "npcs", "Show NPCs", true, NativeBridge::setShowNpcs);
        toggle(p, "vehiclesEsp", "Show Vehicles", false, NativeBridge::setShowVehicles);
        slider(p, "fov", "Camera FOV", 40, 120, 60, "°", NativeBridge::setFov);
        toggle(p, "night", "Night Vision", false, NativeBridge::setNightVision);
        toggle(p, "fly", "Free Fly Camera", false, NativeBridge::setFreeFly);
        LinearLayout card = card(); p.addView(card);
        card.addView(label("ESP Box Color", 13, TEXT));
        LinearLayout colors = new LinearLayout(ctx); colors.setOrientation(LinearLayout.HORIZONTAL);
        int[] palette = {0xFFFF1E3C, 0xFF3BFF7A, 0xFFFFD93B, 0xFFFFFFFF, 0xFF3B82F6, 0xFFFF8A3B};
        int selected = prefs.getInt("espColor", RED2);
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

    // ---------- MISC (vehicle features) ----------
    private LinearLayout pageMisc() { LinearLayout p = page();
        sectionHead(p, "Misc", "Vehicle and misc features");
        toggle(p, "vehSpeedOn", "Speed Hack", false, NativeBridge::setVehicleSpeedEnabled);
        slider(p, "vehSpeed", "Speed Multiplier", 1, 10, 1, "x", NativeBridge::setVehicleSpeedMultiplier);
        toggle(p, "brake", "Instant Brake", false, NativeBridge::setInstantBrake);
        Button b = styledButton("Teleport to Waypoint", true);
        b.setOnClickListener(v -> { click(v); NativeBridge.teleportToWaypoint(); });
        p.addView(b); return p; }

    // ---------- CONFIG (settings + remove) ----------
    private LinearLayout pageConfig() { LinearLayout p = page();
        sectionHead(p, "Config", "Menu settings");
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
        Button remove = dangerButton("Remove Menu Completely");
        remove.setOnClickListener(v -> { click(v); removeCompletely(); });
        p.addView(save); p.addView(def); p.addView(chk); p.addView(pos); p.addView(remove); return p; }
}
