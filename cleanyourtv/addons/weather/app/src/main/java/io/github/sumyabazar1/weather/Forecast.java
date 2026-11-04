package io.github.sumyabazar1.weather;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Open-Meteo, which needs no key: the place search, the weather now and for the next days, and the US air
 * quality index. The only thing sent is the place name typed in the search, then that place's latitude and
 * longitude. Every call blocks, so WeatherActivity and FetchJob make them off the UI thread. With them, the tables
 * the page draws from: words, icons and colours for the codes, the week's scale, and the glow.
 */
final class Forecast {
    static final long REFRESH_MS = 30 * 60_000L;
    /** Home's header: Open-Meteo's "current" values are 15-minutely, so a newer reading than this is the same one. */
    static final long HEADER_REFRESH_MS = 15 * 60_000L;
    static final long RETRY_MS = 5 * 60_000L;
    private static final int TIMEOUT_MS = 8_000;
    /** Days after today the forecast row can show (options.xml, forecast_days). */
    static final int MIN_DAYS = 3, MAX_DAYS = 7;
    /**
     * DWD's ICON, the add-on's first model: worldwide, and where no national model is used. Open-Meteo's default
     * blend called light snow over Ulaanbaatar "overcast" while ICON, and Google, had snow falling.
     */
    static final String DEFAULT_MODEL = "icon_seamless";

    private Forecast() { }

    /** A place from the search: its name, region and country for the list, where it is, and its country code. */
    static final class Place {
        final String name;
        final String detail;
        final double latitude;
        final double longitude;
        /** ISO 3166-1 alpha-2 ("CA"), or empty when the search gave none. */
        final String country;

        Place(String name, String detail, double latitude, double longitude, String country) {
            this.name = name;
            this.detail = detail;
            this.latitude = latitude;
            this.longitude = longitude;
            this.country = country;
        }
    }

    /**
     * The weather now: degrees Celsius as read (air and feels-like; {@link #degrees} rounds them for the page), the
     * WMO weather code, day or night, the relative humidity in % and the wind speed in km/h.
     */
    static final class Report {
        final double tempC;
        final double feelsC;
        /** The WMO code, rain turned into snow where the air is cold enough for it (see {@link #snowIfCold}). */
        final int code;
        final boolean day;
        /** Today's high and low, from the same models as the days; null when no model gave them. */
        final Day today;
        /** The next days, tomorrow first. */
        final List<Day> days;
        /** US AQI, or -1 when the air quality read failed. */
        final int aqi;
        final int humidity;
        final int windKmh;
        /** When it was read, ms. */
        final long at;
        /** Open-Meteo's reply and the models asked, kept so the page can open on the last reading (Now.last). */
        final JSONObject reply;
        final String[] models;

        Report(double tempC, double feelsC, int code, boolean day, Day today, List<Day> days, int aqi, int humidity,
               int windKmh, long at, JSONObject reply, String[] models) {
            this.tempC = tempC;
            this.feelsC = feelsC;
            this.code = code;
            this.day = day;
            this.today = today;
            this.days = days;
            this.aqi = aqi;
            this.humidity = humidity;
            this.windKmh = windKmh;
            this.at = at;
            this.reply = reply;
            this.models = models;
        }
    }

    /** One forecast day: its date, the WMO code and the high and low in degrees Celsius as read. */
    static final class Day {
        final LocalDate date;
        final int code;
        final double maxC;
        final double minC;

        Day(LocalDate date, int code, double maxC, double minC) {
            this.date = date;
            this.code = code;
            this.maxC = maxC;
            this.minC = minC;
        }
    }

    /** Up to five places matching what was typed, best match first. Empty when nothing matches. */
    static List<Place> search(String name) throws IOException, JSONException {
        JSONObject reply = get("https://geocoding-api.open-meteo.com/v1/search?count=5&format=json&name="
                + URLEncoder.encode(name.trim(), "UTF-8") + "&language=" + Locale.getDefault().getLanguage());
        List<Place> out = new ArrayList<>();
        JSONArray results = reply.optJSONArray("results");
        if (results == null) return out;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            StringBuilder detail = new StringBuilder(r.optString("admin1"));
            String country = r.optString("country");
            if (!country.isEmpty()) detail.append(detail.length() > 0 ? ", " : "").append(country);
            out.add(new Place(r.getString("name"), detail.toString(), r.getDouble("latitude"), r.getDouble("longitude"),
                    r.optString("country_code")));
        }
        return out;
    }

    /** Days after today to show: the option, kept between {@link #MIN_DAYS} and {@link #MAX_DAYS}. */
    static int forecastDays(int wanted) {
        return Math.max(MIN_DAYS, Math.min(MAX_DAYS, wanted));
    }

    /**
     * The national weather service's own model for a country, where Open-Meteo has one that reads the weather now
     * and the next days: finer over its country than ICON's worldwide grid (in a Canadian city Environment Canada's GEM
     * read 4.6 °C, as phones did, while ICON read 7.5 °C). Null for the rest of the world, which keeps ICON.
     * Tried for each: KMA (Korea) and BOM (Australia) gave no values, Italy's model too few days.
     */
    static String nationalModel(String country) {
        if (country == null) return null;
        switch (country.toUpperCase(Locale.ROOT)) {
            case "CA": return "gem_seamless";             // Environment Canada
            case "US": return "gfs_seamless";             // NOAA, with HRRR
            case "FR": return "meteofrance_seamless";     // Météo-France
            case "GB": case "IE": return "ukmo_seamless"; // Met Office
            case "NO": case "SE": case "FI": return "metno_seamless";   // MET Norway, MET Nordic
            case "DK": return "dmi_seamless";             // DMI
            case "NL": case "BE": case "LU": return "knmi_seamless";    // KNMI
            case "CH": return "meteoswiss_icon_ch2";      // MeteoSwiss
            case "JP": return "jma_seamless";             // JMA
            case "CN": return "cma_grapes_global";        // CMA
            default: return null;
        }
    }

    /**
     * The models to ask for, best first (options.xml, weather_model): "auto" puts the country's national model, if
     * any, ahead of ICON; any other value is that one Open-Meteo model alone.
     */
    static String[] models(String option, String country) {
        String chosen = option == null ? "" : option.trim();
        if (!chosen.isEmpty() && !"auto".equalsIgnoreCase(chosen)) return new String[]{chosen};
        String national = nationalModel(country);
        return national == null ? new String[]{DEFAULT_MODEL} : new String[]{national, DEFAULT_MODEL};
    }

    /**
     * The models for the kept place. A place kept before the country was stored gets it once from the search, by
     * its name and the nearest match; "" when none is found, so it is not looked up again. Blocks: off the UI thread.
     */
    static String[] models(Context ctx, SharedPreferences place) {
        if (!place.contains("country") && place.contains("name")) {
            try {
                double lat = Double.longBitsToDouble(place.getLong("lat", 0));
                double lon = Double.longBitsToDouble(place.getLong("lon", 0));
                String country = "";
                double best = 0.5;   // degrees, about 50 km: the same place, not a namesake elsewhere
                for (Place p : search(place.getString("name", ""))) {
                    double far = Math.hypot(p.latitude - lat, p.longitude - lon);
                    if (far < best) {
                        best = far;
                        country = p.country;
                    }
                }
                place.edit().putString("country", country).apply();
            } catch (IOException | JSONException e) {
                // Offline: ICON for now; the country is looked up again with the next reading.
            }
        }
        return models(ctx.getString(R.string.weather_model), place.getString("country", null));
    }

    /**
     * The weather and air quality at a place, with this many days after today, from these models, best first:
     * the weather now from the first, each day from the first that has it (a national model may reach fewer days).
     * The air quality is a separate service: if it fails, aqi is -1.
     */
    static Report fetch(double latitude, double longitude, int days, String[] models)
            throws IOException, JSONException {
        String where = String.format(Locale.ROOT, "latitude=%.4f&longitude=%.4f", latitude, longitude);
        JSONObject reply = get("https://api.open-meteo.com/v1/forecast?" + where + "&models=" + String.join(",", models)
                + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,is_day,wind_speed_10m"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days="
                + (forecastDays(days) + 1));
        return parse(reply, aqiOrUnknown(where), models, System.currentTimeMillis());
    }

    /** A report from Open-Meteo's reply, read now or kept from before (Now.last). */
    static Report parse(JSONObject reply, int aqi, String[] models, long at) throws JSONException {
        JSONObject current = reply.getJSONObject("current");
        double temp = current.getDouble("temperature_2m");
        double humidity = current.getDouble("relative_humidity_2m");
        int code = snowIfCold(current.getInt("weather_code"), temp, humidity);
        JSONObject daily = reply.getJSONObject("daily");
        return new Report(temp, current.getDouble("apparent_temperature"),
                code, current.getInt("is_day") == 1,
                day(daily, models, 0), days(daily, models), aqi,
                (int) Math.round(humidity), (int) Math.round(current.optDouble("wind_speed_10m", 0)), at, reply, models);
    }

    /**
     * The models type precipitation by the air temperature, and around freezing they call rain what reaches the
     * ground as snow (ICON and GFS: "light rain" at 1 °C in a Ulaanbaatar blizzard). The wet-bulb temperature,
     * which counts how dry the air is, decides better: at 1 °C or below, rain and drizzle become snow of the same
     * strength. Freezing rain and drizzle stay, as they are what falls.
     */
    static int snowIfCold(int code, double tempC, double humidity) {
        if (wetBulb(tempC, humidity) > 1.0) return code;
        switch (code) {
            case 51: case 53: case 61: case 80: return 71;
            case 55: case 63: case 81: return 73;
            case 65: case 82: return 75;
            default: return code;
        }
    }

    /** Stull (2011), from the air temperature in °C and the relative humidity in %; within 1 °C at sea level. */
    static double wetBulb(double t, double rh) {
        return t * Math.atan(0.151977 * Math.sqrt(rh + 8.313659)) + Math.atan(t + rh) - Math.atan(rh - 1.676331)
                + 0.00391838 * Math.pow(rh, 1.5) * Math.atan(0.023101 * rh) - 4.686035;
    }

    /** Tomorrow and the days after it; today (index 0) is the current reading, and its high and low {@link #day}. */
    private static List<Day> days(JSONObject daily, String[] models) throws JSONException {
        List<Day> out = new ArrayList<>();
        for (int i = 1; i < daily.getJSONArray("time").length(); i++) {
            Day day = day(daily, models, i);
            // With forecast_days 7 the last day can come back without values: stop there instead of failing the page.
            if (day == null) break;
            out.add(day);
        }
        return out;
    }

    /**
     * Day i, whole from the first model that has it, or null. With more than one model each value comes back per
     * model ("weather_code_gem_seamless"); a national model may reach fewer days than ICON.
     */
    private static Day day(JSONObject daily, String[] models, int i) throws JSONException {
        JSONArray dates = daily.getJSONArray("time");
        for (String model : models) {
            String suffix = models.length > 1 ? "_" + model : "";
            JSONArray codes = daily.optJSONArray("weather_code" + suffix);
            JSONArray max = daily.optJSONArray("temperature_2m_max" + suffix);
            JSONArray min = daily.optJSONArray("temperature_2m_min" + suffix);
            if (codes == null || max == null || min == null || codes.isNull(i) || max.isNull(i) || min.isNull(i)) continue;
            // A day whose high stays near freezing gets the same rain-to-snow correction, with the high and
            // saturated air, which is the warm end of the day's precipitation.
            return new Day(LocalDate.parse(dates.getString(i)), snowIfCold(codes.getInt(i), max.getDouble(i), 100),
                    max.getDouble(i), min.getDouble(i));
        }
        return null;
    }

    private static int aqiOrUnknown(String where) {
        try {
            JSONObject current = get("https://air-quality-api.open-meteo.com/v1/air-quality?" + where
                    + "&current=us_aqi").getJSONObject("current");
            return current.isNull("us_aqi") ? -1 : current.getInt("us_aqi");
        } catch (IOException | JSONException e) {
            return -1;
        }
    }

    private static JSONObject get(String url) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) != -1; ) out.write(buffer, 0, n);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }

    /** Whole degrees for the page: Celsius as read, or Fahrenheit converted before rounding (options.xml temperature_unit). */
    static int degrees(double celsius, boolean fahrenheit) {
        return (int) Math.round(fahrenheit ? celsius * 9 / 5 + 32 : celsius);
    }

    /** Whole miles per hour from km/h. */
    static int mph(int kmh) {
        return (int) Math.round(kmh / 1.609344);
    }

    // ---- words and colours -------------------------------------------------------------------------

    /** The words for a WMO weather code, as fine as the code goes ("Light snow", not "Snow"). */
    static int sky(int code, boolean day) {
        switch (code) {
            case 0: return day ? R.string.sky_sunny : R.string.sky_clear;
            case 1: return day ? R.string.sky_mostly_sunny : R.string.sky_mostly_clear;
            case 2: return R.string.sky_partly_cloudy;
            case 3: return R.string.sky_cloudy;
            case 45: case 48: return R.string.sky_fog;
            case 51: case 53: case 55: return R.string.sky_drizzle;
            case 56: case 57: return R.string.sky_freezing_drizzle;
            case 61: return R.string.sky_light_rain;
            case 63: return R.string.sky_rain;
            case 65: return R.string.sky_heavy_rain;
            case 66: case 67: return R.string.sky_freezing_rain;
            case 71: return R.string.sky_light_snow;
            case 73: return R.string.sky_snow;
            case 75: return R.string.sky_heavy_snow;
            case 77: return R.string.sky_snow_grains;
            case 80: case 81: case 82: return R.string.sky_showers;
            case 85: case 86: return R.string.sky_snow_showers;
            default: return code >= 95 ? R.string.sky_thunderstorm : R.string.sky_cloudy;
        }
    }

    /** US AQI bands: upper bound, words, pill colour. */
    private static final int[] AQI_MAX = {50, 100, 150, 200, 300, Integer.MAX_VALUE};
    private static final int[] AQI_LABEL = {R.string.aqi_good, R.string.aqi_moderate, R.string.aqi_sensitive,
            R.string.aqi_unhealthy, R.string.aqi_very_unhealthy, R.string.aqi_hazardous};
    private static final int[] AQI_COLOUR = {0xE643A047, 0xE6FBC02D, 0xE6FB8C00, 0xE6E53935, 0xE68E24AA, 0xE67E0023};

    static int aqiBand(int aqi) {
        int band = 0;
        while (aqi > AQI_MAX[band]) band++;
        return band;
    }

    static int aqiLabel(int aqi) {
        return AQI_LABEL[aqiBand(aqi)];
    }

    static int aqiColour(int aqi) {
        return AQI_COLOUR[aqiBand(aqi)];
    }


    /** The icon for a WMO code, as the launcher's weather pill draws it. */
    static int icon(int code, boolean day) {
        if (code == 0 || code == 1) return day ? R.drawable.ic_weather_clear_day : R.drawable.ic_weather_clear_night;
        if (code == 2) return day ? R.drawable.ic_weather_partly_cloudy_day : R.drawable.ic_weather_partly_cloudy_night;
        if (code == 45 || code == 48) return R.drawable.ic_weather_foggy;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_weather_weather_snowy;
        if (code >= 95 && code <= 99) return R.drawable.ic_weather_thunderstorm;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return R.drawable.ic_weather_rainy;
        return R.drawable.ic_weather_cloud;
    }

    /** The screen's near-black, the launcher's own. */
    static final int BASE = 0xFF07090F;
    /** The days' bars run from COOL to WARM (range_fill.xml), and so does the page's glow. */
    static final int COOL = 0xFF8FB8FF, WARM = 0xFFFFD27A;
    /** The glow is drawn this small and stretched: soft gradients lose nothing, and it is drawn once. */
    private static final int GLOW_W = 480, GLOW_H = 270;

    /**
     * The coldest and warmest of today, the days ahead and the temperature now, in °C as read: the scale of the
     * days' bars and of the page's warmth.
     */
    static double[] range(Report r) {
        double low = r.tempC, high = r.tempC;
        if (r.today != null) {
            low = Math.min(low, r.today.minC);
            high = Math.max(high, r.today.maxC);
        }
        for (Day d : r.days) {
            low = Math.min(low, d.minC);
            high = Math.max(high, d.maxC);
        }
        return new double[]{low, high};
    }

    /** Where a temperature (°C) sits on that scale, 0 the coldest to 1 the warmest; a scale under 1° counts as 1°. */
    static float position(double[] range, double celsius) {
        return (float) Math.max(0, Math.min(1, (celsius - range[0]) / Math.max(range[1] - range[0], 1)));
    }

    /** Where the temperature now sits on that scale: the dot on today's bar, and the glow while today is focused. */
    static float warmth(Report r) {
        return position(range(r), r.tempC);
    }

    /**
     * The launcher's ambient glow in the bars' colours, warm light from the top right and cool from the bottom left:
     * one layer for each, at full strength, drawn once into a small bitmap the window stretches. The page sets their
     * strengths (alpha) for the warmth of the day focused, so a change animates from whatever is on screen.
     */
    static Drawable warmGlow(Resources res) {
        return glowLayer(res, WARM, 0.82f, 0.08f, 0.80f);
    }

    static Drawable coolGlow(Resources res) {
        return glowLayer(res, COOL, 0.12f, 0.98f, 0.75f);
    }

    /** Each glow's strength, 0 to 1, for this warmth (0 the week's coldest, 1 its warmest): dimmer at night. */
    static float warmStrength(float warmth, boolean day) {
        return (0.16f + 0.40f * warmth) * (day ? 1f : 0.75f);
    }

    static float coolStrength(float warmth, boolean day) {
        return warmStrength(1 - warmth, day);
    }

    /** A soft round light of this colour, centred at (x, y), radius r, all as screen fractions; strength is alpha. */
    private static Drawable glowLayer(Resources res, int colour, float x, float y, float r) {
        Bitmap bitmap = Bitmap.createBitmap(GLOW_W, GLOW_H, Bitmap.Config.ARGB_8888);
        int rgb = colour & 0x00FFFFFF;
        // Full at the centre, 40% at mid-radius, nothing at the edge: a soft falloff rather than a cone.
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        paint.setShader(new RadialGradient(x * GLOW_W, y * GLOW_H, r * GLOW_W,
                new int[]{rgb | 0xFF000000, rgb | 0x66000000, rgb}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(bitmap).drawRect(0, 0, GLOW_W, GLOW_H, paint);
        BitmapDrawable drawable = new BitmapDrawable(res, bitmap);
        drawable.setFilterBitmap(true);
        return drawable;
    }
}
