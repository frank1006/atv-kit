package io.github.sumyabazar1.weather;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The last reading, written whenever the page or FetchJob reads the weather. In a few words ("18° Partly cloudy") with
 * its WMO code and day or night, for the Clean Your TV header (NowProvider), which so never goes online itself; and
 * whole (Open-Meteo's reply), for the page to open on (last).
 */
final class Now {
    static final String AUTHORITY = "io.github.sumyabazar1.weather.now";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/now");
    private static final String PREFS = "now";
    /** As long as Home shows a reading: after that it is old news, and the page waits for a new one. */
    static final long KEEP_MS = 3 * 60 * 60_000L;

    private Now() { }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Words in the page's unit (options.xml temperature_unit), then tells the header. */
    static void save(Context ctx, Forecast.Report r) {
        boolean fahrenheit = "f".equalsIgnoreCase(ctx.getString(R.string.temperature_unit).trim());
        String temp = Forecast.degrees(r.tempC, fahrenheit) + "°";
        String text = temp + " " + ctx.getString(Forecast.sky(r.code, r.day));
        prefs(ctx).edit().putString("text", text).putString("temp", temp).putInt("code", r.code).putBoolean("day", r.day)
                .putLong("at", r.at)
                .putString("reply", r.reply.toString()).putInt("aqi", r.aqi).putString("models", String.join(",", r.models))
                .apply();
        ctx.getContentResolver().notifyChange(URI, null);
    }

    /**
     * The whole last reading, for the page to open on instead of "Loading" while it reads a new one; null when there
     * is none, or it is older than {@link #KEEP_MS}.
     */
    static Forecast.Report last(Context ctx) {
        SharedPreferences now = prefs(ctx);
        String reply = now.getString("reply", null);
        long at = now.getLong("at", 0);
        if (reply == null || System.currentTimeMillis() - at > KEEP_MS) return null;
        try {
            return Forecast.parse(new JSONObject(reply), now.getInt("aqi", -1), now.getString("models", "").split(","), at);
        } catch (JSONException e) {
            return null;
        }
    }
}
