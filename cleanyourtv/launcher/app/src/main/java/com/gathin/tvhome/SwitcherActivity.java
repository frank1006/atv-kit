package com.gathin.tvhome;

import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The recent-apps panel, opened by the "switcher" action word in app_keys (e.g. KEYCODE_ALL_APPS:switcher): the apps
 * used since the TV started as a row of banners over a dark wash, under Home's big title. OK opens one, Up
 * closes it (the card flies up, like a swipe on a phone), and Close all, Down from any card, closes them all. The same
 * button again, or Back, leaves.
 *
 * The panel is see-through (Theme.TvHome.Switcher): the app it opens over stays visible and is only paused, so a video
 * or game under it keeps running. Over Home it shows the wallpaper instead, as Home's title and tiles would show through,
 * and so it does once the app under it is closed: the panel turns opaque and Android stops that app right away.
 *
 * Closing is ActivityManager.killBackgroundProcesses, which leaves visible and unstopped apps alone: the app under the
 * panel is closed once the panel has covered it and Android has stopped it (closeOnceStopped). An app that runs a foreground service (a media server, a cast receiver) survives it; the panel
 * still drops it from the row until it is used again. Needs usage access, granted once with
 * {@code appops set com.gathin.tvhome GET_USAGE_STATS allow}.
 */
public class SwitcherActivity extends Activity {
    private static final String CLOSED_PREFS = "switcher_closed";
    /** The boot the closes in CLOSED_PREFS belong to; no package name starts with "#". */
    private static final String CLOSED_BOOT = "#boot";
    private static final int LIMIT = 12;
    /**
     * Once Home is in front, how long the apps closed on a visit are tried again, and how often (see onStop). Android
     * stops the app the panel was over within its 10 s idle timeout, usually about 3 s on this box.
     */
    private static final long CLOSE_WITHIN_MS = 12_000;
    private static final long CLOSE_RETRY_MS = 500;

    // One motion language: things arrive decelerating and leave accelerating, as on Home.
    private static final Interpolator ARRIVE = new DecelerateInterpolator(1.6f);
    private static final Interpolator LEAVE = new AccelerateInterpolator(1.4f);
    private static final int ENTER_MS = 320;
    private static final int STAGGER_MS = 40;
    /** The memory bar glides to its new share after a close. */
    private static final int MEMORY_MS = 400;
    /** Android frees a closed app's memory over a moment: read it again once that has settled. */
    private static final int MEMORY_SETTLE_MS = 1500;
    private static final int FOCUS_MS = 200;
    private static final int FLY_MS = 200;
    private static final int SLIDE_MS = 240;
    private static final int EXIT_MS = 180;
    /** How long "No apps open" stays before the panel leaves on its own. */
    private static final int EMPTY_HOLD_MS = 900;

    private static final float FOCUS_SCALE = 1.1f;
    /** How far a closed card rises as it fades: a short lift, not a flight across the screen. */
    private static final float FLY_DP = 48f;
    private static final float CORNER_DP = 14f;
    private static final float REST_NAME_ALPHA = 0.55f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Reading the apps and their artwork, and closing them: binder calls kept off the frames being drawn. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Closes apps that were on screen when closed, once Android has stopped them (closeOnceStopped). */
    private final ExecutorService closer = Executors.newSingleThreadExecutor();
    private View content;
    private LinearLayout row;
    private TextView hint;
    private View closeAll;
    /** The card Up from Close all goes back to. */
    private View lastCard;
    private float density;
    /** The dark wash over the app underneath. */
    private View scrim;
    /**
     * The wallpaper on an opaque base: shown when the panel opens over Home, and once the app under the panel is
     * closed; null until then.
     */
    private ImageView backdrop;
    /** Home's first wallpaper, for the backdrop; null without one. */
    private File wallpaper;
    /** The app the panel opened over, still on screen under it; null over Home or once it is closed. */
    private String underneath;
    /**
     * The app the panel opened over was closed: leaving goes Home, or Android would bring that app back (and restart
     * it). Otherwise leaving returns to it, whatever else was closed.
     */
    private boolean closedUnderneath;
    private boolean animations = true;
    private boolean leaving;
    /** show_memory: the free-memory pill in the bottom corner; null without it. */
    private View memory;
    private TextView memoryFree;
    private TextView memoryTotal;
    private View memoryBar;
    /** The free share the bar shows; below 0 until the memory is first read. */
    private float memoryShare = -1f;
    private final Runnable rereadMemory = () -> worker.execute(this::readMemory);
    /**
     * The apps closed on this visit. One that was still on screen when it was closed (the app the panel was over) is
     * closed again once it is not.
     */
    private final Map<String, Long> closed = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_switcher);
        density = getResources().getDisplayMetrics().density;
        content = findViewById(R.id.content);
        row = findViewById(R.id.row);
        hint = findViewById(R.id.hint);
        closeAll = findViewById(R.id.closeAll);

        Config cfg = null;
        try {
            cfg = Config.load(this);
            animations = cfg.animations && !cfg.reducedMotion;
        } catch (RuntimeException ignored) {
        }
        scrim = findViewById(R.id.scrim);
        if (cfg != null && cfg.showMemory) setUpMemory();
        if (cfg != null && !cfg.wallpapers.isEmpty()) wallpaper = cfg.wallpapers.get(0);
        if (MainActivity.isHomeShown()) showBackdrop();
        fade(1f, ENTER_MS, ARRIVE);
        setUpCloseAll();
        if (animations) row.setLayoutTransition(closingGapTransition());

        // The title is up on the first frame; the apps follow as soon as they are read, instead of a black screen
        // while the usage stats and the banners load.
        enterHeader();
        Map<String, String> labels = cfg == null ? Collections.emptyMap() : cfg.appLabels;
        worker.execute(() -> {
            boolean access = hasUsageAccess();
            List<App> apps = access ? recentApps(labels) : Collections.emptyList();
            if (memory != null) readMemory();
            handler.post(() -> {
                if (!isDestroyed() && !leaving) show(apps, access);
            });
        });
    }

    /** The same button again leaves, as it would leave the system's recents. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        leave();
    }

    @Override
    public void onBackPressed() {
        leave();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Home is in front now: whatever was closed and still on screen until now can be closed for good.
        if (!closed.isEmpty()) closeOnceStopped(new HashMap<>(closed));
        // Home or an app on top: nothing of the panel stays behind for the next press.
        if (!isFinishing()) finish();
    }

    /**
     * Closes apps that were still on screen when they were closed. Android stops such an app some time after it is
     * covered, and only then can it be closed; Android 9 tells a launcher nothing about other apps stopping, so keep
     * trying until Android's idle timeout has passed, leaving alone any app opened again meanwhile. On its own
     * thread, so the panel's worker stays free.
     */
    private void closeOnceStopped(Map<String, Long> apps) {
        ActivityManager am = getSystemService(ActivityManager.class);
        UsageStatsManager usm = getSystemService(UsageStatsManager.class);
        if (am == null || usm == null) return;
        closer.execute(() -> {
            long until = SystemClock.elapsedRealtime() + CLOSE_WITHIN_MS;
            while (!apps.isEmpty() && SystemClock.elapsedRealtime() < until) {
                apps.keySet().removeAll(openedSince(usm, apps));
                for (String pkg : apps.keySet()) am.killBackgroundProcesses(pkg);
                SystemClock.sleep(CLOSE_RETRY_MS);
            }
        });
    }

    /** The packages among {@code closedAt} brought to the front since they were closed. */
    private static Set<String> openedSince(UsageStatsManager usm, Map<String, Long> closedAt) {
        Set<String> opened = new HashSet<>();
        UsageEvents events = usm.queryEvents(Collections.min(closedAt.values()), System.currentTimeMillis());
        UsageEvents.Event e = new UsageEvents.Event();
        while (events != null && events.getNextEvent(e)) {
            Long closedTime = closedAt.get(e.getPackageName());
            if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND && closedTime != null
                    && e.getTimeStamp() > closedTime) {
                opened.add(e.getPackageName());
            }
        }
        return opened;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        worker.shutdown();
        closer.shutdown();
    }

    // ---- which apps --------------------------------------------------------------------------------

    private boolean hasUsageAccess() {
        AppOpsManager ops = getSystemService(AppOpsManager.class);
        return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                == AppOpsManager.MODE_ALLOWED;
    }

    /** One app of the row, read off the main thread. */
    private static final class App {
        final String pkg;
        final CharSequence label;
        /** The banner, or null for an icon card. */
        final Drawable banner;
        final Drawable icon;

        App(String pkg, CharSequence label, Drawable banner, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.banner = banner;
            this.icon = icon;
        }
    }

    private List<App> recentApps(Map<String, String> labels) {
        PackageManager pm = getPackageManager();
        List<App> apps = new ArrayList<>();
        for (String pkg : recentPackages()) {
            Intent open = launchIntent(pm, pkg);
            ResolveInfo ri = open == null ? null : pm.resolveActivity(open, 0);
            if (ri == null) continue;
            // Home's loader, so the same artwork as the app's tile.
            Drawable banner = AppAdapter.loadBanner(this, pm, ri);
            String label = labels.get(pkg);
            apps.add(new App(pkg, label != null ? label : ri.loadLabel(pm), banner, banner == null ? ri.loadIcon(pm) : null));
        }
        return apps;
    }

    private List<String> recentPackages() {
        PackageManager pm = getPackageManager();
        Map<String, Long> lastUsed = lastUsedSinceBoot();
        Set<String> launchable = new HashSet<>();
        for (String pkg : lastUsed.keySet()) {
            if (launchIntent(pm, pkg) != null) launchable.add(pkg);
        }
        long boot = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        return RecentApps.order(lastUsed, closedAt(), boot, launchable, getPackageName(), LIMIT);
    }

    /**
     * Package to the last time it was in front. Without internet the TV has no network time and runs at the epoch,
     * while the stats kept from earlier boots carry real dates: so every stored day is asked for, not a window
     * around a clock that may be decades off, and times after now (an earlier boot with a real clock) are dropped
     * here, before they could hide this boot's use of the same app. RecentApps drops the rest from before boot.
     */
    private Map<String, Long> lastUsedSinceBoot() {
        Map<String, Long> used = new HashMap<>();
        UsageStatsManager usm = getSystemService(UsageStatsManager.class);
        if (usm == null) return used;
        long now = System.currentTimeMillis();
        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, 0, Long.MAX_VALUE);
        if (stats == null) return used;
        for (UsageStats s : stats) {
            if (s.getLastTimeUsed() <= 0 || s.getLastTimeUsed() > now || s.getTotalTimeInForeground() <= 0) continue;
            Long seen = used.get(s.getPackageName());
            if (seen == null || s.getLastTimeUsed() > seen) used.put(s.getPackageName(), s.getLastTimeUsed());
        }
        return used;
    }

    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent open = pm.getLeanbackLaunchIntentForPackage(pkg);
        return open != null ? open : pm.getLaunchIntentForPackage(pkg);
    }

    private SharedPreferences closedPrefs() {
        return getSharedPreferences(CLOSED_PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Package to the time it was closed from the panel, this boot only. Closes are compared by wall clock, which
     * without network time restarts at the epoch on every boot: a close from an earlier boot would then look newer
     * than any use since and hide that app until the clock caught up.
     */
    private Map<String, Long> closedAt() {
        Map<String, Long> closed = new HashMap<>();
        SharedPreferences prefs = closedPrefs();
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (prefs.getInt(CLOSED_BOOT, boot) != boot || !prefs.contains(CLOSED_BOOT)) {
            prefs.edit().clear().putInt(CLOSED_BOOT, boot).apply();
            return closed;
        }
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getValue() instanceof Long) closed.put(e.getKey(), (Long) e.getValue());
        }
        return closed;
    }

    // ---- views -------------------------------------------------------------------------------------

    private void show(List<App> apps, boolean access) {
        for (App app : apps) row.addView(card(app));
        if (row.getChildCount() == 0) {
            showEmpty(access ? R.string.switcher_empty : R.string.switcher_no_access, false);
        } else {
            // Over an app, the first card is that app: leaving it for the panel made it the most recently used.
            if (backdrop == null) underneath = apps.get(0).pkg;
            hint.setText(R.string.switcher_hint);
            closeAll.setVisibility(View.VISIBLE);
            row.getChildAt(0).findViewById(R.id.card).requestFocus();
        }
        enterRow();
    }

    /** Fades the wash, and the wallpaper over Home, in or out. Without animations they are simply there. */
    private void fade(float to, long ms, Interpolator interpolator) {
        for (View v : backdrop == null ? new View[]{scrim} : new View[]{backdrop, scrim}) {
            if (!animations) continue;
            if (to > 0f) v.setAlpha(0f);
            v.animate().alpha(to).setDuration(ms).setInterpolator(interpolator).start();
        }
    }

    private void showBackdrop() {
        backdrop = findViewById(R.id.backdrop);
        backdrop.setVisibility(View.VISIBLE);
        if (wallpaper != null) loadWallpaper(wallpaper);
    }

    /**
     * The app under the panel was closed: the wallpaper fades in over it, then the window turns opaque, so Android
     * stops the app (its video or game ends now, not when the panel is left) and it can be closed while the panel
     * stays up.
     */
    private void coverUnderneath() {
        underneath = null;
        closedUnderneath = true;
        showBackdrop();
        if (!animations) {
            becomeOpaque();
            return;
        }
        backdrop.setAlpha(0f);
        backdrop.animate().alpha(1f).setDuration(FLY_MS).setInterpolator(ARRIVE).withEndAction(this::becomeOpaque).start();
    }

    /**
     * Turns the window opaque. Activity.setTranslucent from Android 11; before that the same call under its older
     * name, convertFromTranslucent, which Android 9 and 10 still let apps reach.
     */
    private void becomeOpaque() {
        if (isDestroyed()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setTranslucent(false);
            return;
        }
        try {
            Activity.class.getMethod("convertFromTranslucent").invoke(this);
        } catch (ReflectiveOperationException ignored) {
            // Stays see-through: the app is then closed once Home is in front (onStop).
        }
    }

    private void loadWallpaper(File file) {
        new Thread(() -> {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 2;   // dimmed to 30 % behind the row: half resolution is plenty
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), opts);
            if (bitmap == null) return;
            handler.post(() -> {
                if (!isDestroyed()) backdrop.setImageBitmap(bitmap);
            });
        }, "switcher-wallpaper").start();
    }

    /** One app: its banner (or icon) in a rounded card, and its name. */
    private View card(App app) {
        View item = getLayoutInflater().inflate(R.layout.item_switcher_card, row, false);
        View card = item.findViewById(R.id.card);
        TextView name = item.findViewById(R.id.name);
        // Fade each part directly instead of through an offscreen buffer: on Android 9 that buffer drops the
        // corners of a zoomed card, so the focused card showed square while the row came in or a card closed.
        item.forceHasOverlappingRendering(false);
        card.forceHasOverlappingRendering(false);
        String pkg = app.pkg;
        card.setTag(pkg);
        name.setText(app.label);
        if (app.banner != null) {
            ((ImageView) item.findViewById(R.id.banner)).setImageDrawable(new RoundedArtDrawable(app.banner, CORNER_DP * density));
        } else {
            ImageView icon = item.findViewById(R.id.icon);
            icon.setImageDrawable(app.icon);
            icon.setVisibility(View.VISIBLE);
        }
        Drawable ring = card.getForeground().mutate();
        ring.setAlpha(0);

        card.setOnFocusChangeListener((v, focused) -> {
            int ms = animations ? FOCUS_MS : 0;
            float scale = focused ? FOCUS_SCALE : 1f;
            // No elevation: a raised view loses its rounded corners on Android 9 whenever its row fades (entry,
            // closing), so focus is the zoom and the white ring only, as on Home.
            card.animate().scaleX(scale).scaleY(scale).setDuration(ms).setInterpolator(ARRIVE).start();
            ObjectAnimator.ofInt(ring, "alpha", ring.getAlpha(), focused ? 255 : 0).setDuration(ms).start();
            name.animate().alpha(focused ? 1f : REST_NAME_ALPHA).translationY(focused ? 6 * density : 0f)
                    .setDuration(ms).setInterpolator(ARRIVE).start();
            if (focused) lastCard = card;
        });
        card.setOnClickListener(v -> open(pkg));
        card.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_UP) return false;
            // On the press, not the release: Up from Close all lands focus here on its press, and acting on the
            // release would then close this card. A held Up closes one card, not one per repeat.
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) close(item);
            return true;
        });
        return item;
    }

    private void setUpCloseAll() {
        closeAll.setOnClickListener(v -> closeEverything());
        closeAll.setOnFocusChangeListener((v, focused) -> {
            float scale = focused ? 1.04f : 1f;
            v.animate().scaleX(scale).scaleY(scale).setDuration(animations ? 160 : 0).setInterpolator(ARRIVE).start();
        });
        closeAll.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_UP) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN && lastCard != null) lastCard.requestFocus();
            return true;
        });
    }

    // ---- memory ------------------------------------------------------------------------------------

    private void setUpMemory() {
        memory = findViewById(R.id.memory);
        memoryFree = findViewById(R.id.memoryFree);
        memoryTotal = findViewById(R.id.memoryTotal);
        memoryBar = findViewById(R.id.memoryBar);
        // The fill grows from the bar's start edge, the right one in Hebrew.
        memoryBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                v.setPivotX(v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? r - l : 0));
    }

    /** Reads the free and total memory, a binder call, so on the worker; the pill shows them on the next frame. */
    private void readMemory() {
        ActivityManager am = getSystemService(ActivityManager.class);
        if (am == null) return;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        if (info.totalMem <= 0) return;
        handler.post(() -> {
            if (!isDestroyed()) showMemory(info.availMem, info.totalMem);
        });
    }

    /** The first reading is simply there; a later one moves the bar to the new share. */
    private void showMemory(long free, long total) {
        setTextIfChanged(memoryFree, getString(R.string.switcher_memory_free, Formatter.formatShortFileSize(this, free)));
        setTextIfChanged(memoryTotal, getString(R.string.switcher_memory_total, Formatter.formatShortFileSize(this, total)));
        float share = Math.max(0f, Math.min(1f, (float) free / total));
        if (memoryShare < 0f || !animations) {
            memoryBar.setScaleX(share);
        } else {
            memoryBar.animate().scaleX(share).setDuration(MEMORY_MS).setInterpolator(ARRIVE).start();
        }
        memoryShare = share;
        memory.setVisibility(View.VISIBLE);
    }

    /** Setting the same text would still lay the pill out again. */
    private static void setTextIfChanged(TextView view, String text) {
        if (!text.contentEquals(view.getText())) view.setText(text);
    }

    /** When a card leaves the row, the ones after it slide over to close the gap. */
    private static LayoutTransition closingGapTransition() {
        LayoutTransition slide = new LayoutTransition();
        slide.disableTransitionType(LayoutTransition.APPEARING);
        slide.disableTransitionType(LayoutTransition.DISAPPEARING);
        slide.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
        slide.setDuration(LayoutTransition.CHANGE_DISAPPEARING, SLIDE_MS);
        slide.setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 0);
        slide.setInterpolator(LayoutTransition.CHANGE_DISAPPEARING, ARRIVE);
        return slide;
    }

    // ---- motion ------------------------------------------------------------------------------------

    /** The title rises into place on the first frame. */
    private void enterHeader() {
        if (animations) rise(findViewById(R.id.title), 24 * density, 0);
    }

    /**
     * Once the apps are read: the line under the title (how to use the row, or that nothing is running), then the
     * cards one after another, then Close all.
     */
    private void enterRow() {
        if (!animations) return;
        float from = 24 * density;
        rise(hint, from, 0);
        int i = 0;
        for (; i < row.getChildCount(); i++) rise(row.getChildAt(i), from, (long) (i + 1) * STAGGER_MS);
        if (closeAll.getVisibility() == View.VISIBLE) rise(closeAll, from / 2, (long) (i + 1) * STAGGER_MS);
        if (memory != null && memory.getVisibility() == View.VISIBLE) rise(memory, from / 2, (long) (i + 1) * STAGGER_MS);
    }

    private void rise(View v, float from, long delay) {
        v.setAlpha(0f);
        v.setTranslationY(from);
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(ENTER_MS).setInterpolator(ARRIVE).start();
    }

    /** A card lifts a little and fades, shrinking slightly, the way a swipe up dismisses an app on a phone. */
    private void flyAway(View item, long delay, Runnable then) {
        item.animate().translationY(-FLY_DP * density).alpha(0f).scaleX(0.94f).scaleY(0.94f).setStartDelay(delay)
                .setDuration(animations ? FLY_MS : 0).setInterpolator(LEAVE).withEndAction(then).start();
    }

    // ---- actions -----------------------------------------------------------------------------------

    private void open(String pkg) {
        if (leaving) return;
        Intent open = launchIntent(getPackageManager(), pkg);
        if (open == null) return;
        leaving = true;
        try {
            startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
        }
        finish();
        overridePendingTransition(0, 0);
    }

    /** Closes an app on the worker: the binder call would otherwise hold up the first frames of the animation. */
    private void kill(String pkg) {
        long now = System.currentTimeMillis();
        closed.put(pkg, now);
        worker.execute(() -> {
            ActivityManager am = getSystemService(ActivityManager.class);
            if (am != null) am.killBackgroundProcesses(pkg);
            closedPrefs().edit().putLong(pkg, now).apply();
            if (memory != null) {
                readMemory();
                handler.removeCallbacks(rereadMemory);
                handler.postDelayed(rereadMemory, MEMORY_SETTLE_MS);
            }
        });
        if (pkg.equals(underneath)) {
            coverUnderneath();
            Map<String, Long> app = new HashMap<>();
            app.put(pkg, now);
            closeOnceStopped(app);
        }
    }

    /**
     * Up on a card: it lifts and fades in place, then the row closes the gap and focus moves to the card that slid
     * into its place (or the one before). One thing moves at a time, so each frame stays light.
     */
    private void close(View item) {
        if (leaving || item.getTag() != null) return;
        item.setTag(Boolean.TRUE);   // closing: a second Up must not start it again
        kill((String) item.findViewById(R.id.card).getTag());
        flyAway(item, 0, () -> {
            View next = neighbour(item);
            if (next != null) next.findViewById(R.id.card).requestFocus();
            row.removeView(item);
            if (row.getChildCount() == 0) showEmpty(R.string.switcher_empty, true);
        });
    }

    /** The card focus moves to when this one closes: the next one, else the one before. */
    private View neighbour(View item) {
        int at = row.indexOfChild(item);
        for (int i = at + 1; i < row.getChildCount(); i++) if (row.getChildAt(i).getTag() == null) return row.getChildAt(i);
        for (int i = at - 1; i >= 0; i--) if (row.getChildAt(i).getTag() == null) return row.getChildAt(i);
        return null;
    }

    /** Close all: every card flies away in turn, then Home. */
    private void closeEverything() {
        if (leaving) return;
        leaving = true;
        int n = row.getChildCount();
        for (int i = 0; i < n; i++) {
            View item = row.getChildAt(i);
            if (item.getTag() != null) continue;
            item.setTag(Boolean.TRUE);
            kill((String) item.findViewById(R.id.card).getTag());
            flyAway(item, (long) i * STAGGER_MS, null);
        }
        closeAll.animate().alpha(0f).setDuration(animations ? EXIT_MS : 0).start();
        handler.postDelayed(this::exit, animations ? FLY_MS + (long) n * STAGGER_MS : 0);
    }

    /**
     * The row is empty: the line under the title says so instead of "OK opens · Up closes", and Close all fades out.
     * The row keeps its height, so nothing moves. After the last card closed, the panel then leaves on its own.
     */
    private void showEmpty(int message, boolean thenLeave) {
        closeAll.setFocusable(false);
        if (!animations || !thenLeave) {
            hint.setText(message);
            closeAll.setVisibility(View.INVISIBLE);
        } else {
            // Out with the old line, in with the new: a short cross-fade in place.
            hint.animate().alpha(0f).setDuration(EXIT_MS).setInterpolator(LEAVE).withEndAction(() -> {
                hint.setText(message);
                hint.animate().alpha(1f).setDuration(EXIT_MS).setInterpolator(ARRIVE).start();
            }).start();
            closeAll.animate().alpha(0f).setDuration(EXIT_MS)
                    .withEndAction(() -> closeAll.setVisibility(View.INVISIBLE)).start();
        }
        if (thenLeave) handler.postDelayed(this::leave, EMPTY_HOLD_MS);
    }

    /** Back, the button again, or an emptied row: fade out, then back to the app it was opened over, or Home. */
    private void leave() {
        if (leaving) return;
        leaving = true;
        if (!animations) {
            exit();
            return;
        }
        fade(0f, EXIT_MS, LEAVE);
        if (memory != null) memory.animate().alpha(0f).setDuration(EXIT_MS).setInterpolator(LEAVE).start();
        content.animate().alpha(0f).setDuration(EXIT_MS).setInterpolator(LEAVE).withEndAction(this::exit).start();
    }

    private void exit() {
        if (closedUnderneath) {
            try {
                startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setPackage(getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
            }
        }
        finish();
        overridePendingTransition(0, android.R.anim.fade_out);
    }
}
