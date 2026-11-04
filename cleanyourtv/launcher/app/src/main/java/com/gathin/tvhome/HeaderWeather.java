package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.view.View;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * header_weather_app: the weather from the app the owner named, read from its content provider for {@link #ACTION}.
 * After the date, an icon and a few words ("18° Partly cloudy"); with header_weather_by_buttons, the icon and
 * temperature alone as that app's top-bar button, which takes the place of its tile as top_bar_apps do. The launcher
 * never goes online: it reads the provider on the TV when Home comes to the front, every {@link #RECHECK_MS} while
 * it stays there, and when the app says a new reading is in. A reading older than {@link #FRESH_MS} is left out
 * rather than shown as if it were now.
 */
final class HeaderWeather {
    static final String ACTION = "com.gathin.tvhome.action.HEADER_WEATHER";
    static final long FRESH_MS = 3 * 60 * 60_000L;
    /**
     * While Home stays in front, ask again this often: the app's readings are 15-minutely, and asking is what lets
     * it fetch a new one. Out of sight (an app in front), nothing is asked; coming back asks at once.
     */
    static final long RECHECK_MS = 15 * 60_000L;
    /** As long as a card's label: the words share the date's line. */
    private static final int MAX_CHARS = 24;

    private final Context ctx;
    private final DateClock date;
    /** header_weather_by_buttons: the weather goes here instead of after the date; null otherwise. */
    private final TextView chip;
    /** The weather app the owner named (config.xml, header_weather_app). */
    private final String app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Provider queries are binder calls into another app, which may have to start: never on a frame. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ContentObserver observer = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            Uri uri = watched;
            if (uri != null) worker.execute(() -> read(uri));
        }
    };
    private final Runnable recheck = new Runnable() {
        @Override
        public void run() {
            Uri uri = watched;
            if (uri == null) return;
            worker.execute(() -> read(uri));
            handler.postDelayed(this, RECHECK_MS);
        }
    };
    /** Home is in front: between start() and stop(). */
    private boolean active;
    /** The app is installed and offers a reading: the button shows, with a reading or without. */
    private volatile boolean offered;
    /** The provider being watched while Home is in front; null otherwise. */
    private Uri watched;
    /** The reading after the date now (its words and icon); null when there is none. */
    private String shown;

    HeaderWeather(Context ctx, DateClock date, TextView chip, String app) {
        this.ctx = ctx;
        this.date = date;
        this.chip = chip;
        this.app = app;
    }

    /** Home in front: find the app's provider, read it, and watch it for a new reading. */
    void start() {
        stop();
        active = true;
        worker.execute(() -> {
            Uri uri = find();
            handler.post(() -> {
                if (!active) return;
                if (uri == null) {
                    show(null, null, 0);
                    return;
                }
                watched = uri;
                handler.postDelayed(recheck, RECHECK_MS);
                try {
                    ctx.getContentResolver().registerContentObserver(uri, false, observer);
                } catch (RuntimeException ignored) {
                    // The app went away between the lookup and here: read() below shows nothing.
                }
            });
            if (uri != null) read(uri);
        });
    }

    /** Home left the front: nothing is watched until it is back. */
    void stop() {
        active = false;
        handler.removeCallbacks(recheck);
        if (watched == null) return;
        watched = null;
        ctx.getContentResolver().unregisterContentObserver(observer);
    }

    /** The weather app, for the button's OK. */
    String app() {
        return app;
    }

    void close() {
        stop();
        worker.shutdownNow();
    }

    private Uri find() {
        offered = false;
        Intent offer = new Intent(ACTION).setPackage(app);
        for (ResolveInfo ri : ctx.getPackageManager().queryIntentContentProviders(offer, 0)) {
            ProviderInfo p = ri.providerInfo;
            if (p == null || !p.exported || p.authority == null) continue;
            offered = true;
            return Uri.parse("content://" + p.authority.split(";")[0] + "/now");
        }
        return null;
    }

    private void read(Uri uri) {
        String text = null;
        String temp = null;
        int icon = 0;
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int t = c.getColumnIndex("text"), at = c.getColumnIndex("at");
                int code = c.getColumnIndex("code"), day = c.getColumnIndex("day"), deg = c.getColumnIndex("temp");
                long age = at < 0 ? Long.MAX_VALUE : System.currentTimeMillis() - c.getLong(at);
                if (t >= 0 && age >= 0 && age <= FRESH_MS) {
                    text = clean(c.getString(t));
                    if (deg >= 0) temp = clean(c.getString(deg));
                    if (code >= 0) icon = icon(c.getInt(code), day < 0 || c.getInt(day) != 0);
                }
            }
        } catch (RuntimeException ignored) {
            // The app was removed or failed: no weather rather than a crash on Home.
        }
        String words = text, degrees = temp;
        int symbol = icon;
        handler.post(() -> {
            if (uri.equals(watched)) show(words, degrees, symbol);
        });
    }

    /** Another app's words on Home: the card prompt's cleaning (no bidi overrides or line breaks), and short. */
    static String clean(String s) {
        String c = Cards.cleanLabel(s, "");
        if (c.length() > MAX_CHARS) c = c.substring(0, MAX_CHARS).trim();
        return c.isEmpty() ? null : c;
    }

    /**
     * The icon for a WMO weather code (the codes the Weather add-on reads from Open-Meteo); 0 for a code it does not
     * know, which then shows the words alone.
     */
    static int icon(int code, boolean day) {
        if (code == 0 || code == 1) return day ? R.drawable.ic_weather_clear_day : R.drawable.ic_weather_clear_night;
        if (code == 2) return day ? R.drawable.ic_weather_partly_cloudy_day : R.drawable.ic_weather_partly_cloudy_night;
        if (code == 3) return R.drawable.ic_weather_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_weather_foggy;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_weather_weather_snowy;
        if (code >= 95 && code <= 99) return R.drawable.ic_weather_thunderstorm;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return R.drawable.ic_weather_rainy;
        return 0;
    }

    /**
     * After the date on its line: a dot, the icon in the date's colour, the words. By the buttons: the icon and the
     * temperature alone (the words when the app gives no temperature of its own).
     */
    private void show(String text, String temp, int icon) {
        String key = text == null ? null : icon + "|" + temp + "|" + text;
        if (key == null ? shown == null : key.equals(shown)) return;
        shown = key;
        if (chip != null) {
            showChip(text == null ? null : temp != null ? temp : text, icon);
            return;
        }
        if (text == null) {
            date.setSuffix(null);
            return;
        }
        SpannableStringBuilder tail = new SpannableStringBuilder("  ·  ");
        // Before Android 9 all caps drops spans, which would leave a stray box where the icon was: words only.
        Drawable d = icon == 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.P ? null : ctx.getDrawable(icon);
        if (d != null) {
            d = d.mutate();
            d.setTint(date.getCurrentTextColor());
            int at = tail.length();
            tail.append('\uFFFC');
            tail.setSpan(new IconSpan(d, Math.round(date.getTextSize() * 1.35f)), at, at + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tail.append(' ');
        }
        tail.append(text);
        date.setSuffix(tail);
    }

    private void showChip(String text, int icon) {
        if (text == null) {
            // No reading yet, or none for 3 hours: the button stays, as it is the app's way in, without a number.
            if (!offered) {
                chip.setVisibility(View.GONE);
                return;
            }
            text = "--°";
            icon = R.drawable.ic_weather_cloud;
        }
        // The button's own drawableTint colours it, white at rest and dark while focused, like the other pills.
        Drawable d = icon == 0 ? null : ctx.getDrawable(icon);
        if (d != null) {
            int size = Math.round(22 * ctx.getResources().getDisplayMetrics().density);
            d.setBounds(0, 0, size, size);
        }
        chip.setCompoundDrawablesRelative(d, null, null, null);
        chip.setText(text);
        chip.setContentDescription(text);
        chip.setVisibility(View.VISIBLE);
    }

    /** An icon centred on the capitals' height, so it sits level with "18°" rather than on the baseline. */
    private static final class IconSpan extends ReplacementSpan {
        private final Drawable icon;
        private final int size;
        private final Rect caps = new Rect();

        IconSpan(Drawable icon, int size) {
            this.icon = icon;
            this.size = size;
            icon.setBounds(0, 0, size, size);
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            return size;
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom,
                         Paint paint) {
            paint.getTextBounds("H", 0, 1, caps);
            canvas.save();
            canvas.translate(x, y - caps.height() / 2f - size / 2f);
            icon.draw(canvas);
            canvas.restore();
        }
    }
}
