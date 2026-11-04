package io.github.sumyabazar1.weather;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.TextAppearanceSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The weather for one place, full screen, in the Clean Your TV launcher's look: now, its details, and today and the
 * days ahead (options.xml) as a row of cards. It opens at once on the last reading (Now.last) and reads Open-Meteo
 * while it is on screen (every {@link Forecast#REFRESH_MS}, or {@link Forecast#RETRY_MS} after a failure), showing
 * the new reading in place. Out of sight it reads nothing, unless the launcher's header asks (NowProvider). The place
 * is chosen once with a search and kept in the app's preferences.
 */
public final class WeatherActivity extends Activity {
    static final String PREFS = "place";
    // The launcher's recent-apps motion: things rise into place one after another and the focused card grows; the
    // window fades in and out (styles.xml, WeatherWindow). ViewPropertyAnimator follows the TV's animation scale, so
    // Developer options can turn it off.
    private static final Interpolator ARRIVE = new DecelerateInterpolator(1.6f);
    private static final int ENTER_MS = 320;
    private static final int STAGGER_MS = 40;
    private static final int FOCUS_MS = 200;
    /** The glow's move to another day's warmth. */
    private static final int GLOW_MS = 400;
    /** How far below its place a section starts its rise; the pills and the bottom row rise half as far. */
    private static final int RISE_DP = 24;
    /** How far the words under a focused card move down, as the recent apps' names do. */
    private static final int NAME_DROP_DP = 6;
    /** As the recent apps' cards, which have the same size and gaps. */
    private static final float FOCUS_SCALE = 1.1f;
    /** The words under a card at rest, as the recent apps' names. */
    private static final float REST_NAME_ALPHA = 0.55f;
    /** If the window's opening animation never reports its end, the page still rises after this. */
    private static final int OPENING_MS = 700;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::refresh;
    private SharedPreferences place;
    private View content;
    private TextView message;
    private boolean shown;
    /** The window has finished opening: the entrance runs then, not over the window's own animation. */
    private boolean opened;
    private boolean entrancePending;
    private final Runnable openedAnyway = this::onEnterAnimationComplete;
    /** The reading on the page, for the glow of the day focused. */
    private Forecast.Report report;
    /** The window's background: the near-black, and the warm and cool glows over it (Forecast.warmGlow). */
    private Drawable warmGlow;
    private Drawable coolGlow;
    /** The warmth on screen, -1 before the first; the glow moves from it to the next, wherever it is. */
    private float shownWarmth = -1f;
    private ValueAnimator glowing;
    /** options.xml: days after today, and the extra pills (humidity, wind). */
    private int forecastDays;
    private boolean showHumidity;
    private boolean showWind;
    /** options.xml: degrees Fahrenheit (and mph) instead of Celsius, and the other unit in the "Feels like" pill. */
    private boolean fahrenheit;
    private boolean bothUnits;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_weather);
        warmGlow = Forecast.warmGlow(getResources());
        coolGlow = Forecast.coolGlow(getResources());
        warmGlow.setAlpha(0);
        coolGlow.setAlpha(0);
        getWindow().setBackgroundDrawable(new LayerDrawable(new Drawable[]{
                new ColorDrawable(Forecast.BASE), coolGlow, warmGlow}));
        place = getSharedPreferences(PREFS, MODE_PRIVATE);
        forecastDays = Forecast.forecastDays(getResources().getInteger(R.integer.forecast_days));
        showHumidity = getResources().getBoolean(R.bool.show_humidity);
        showWind = getResources().getBoolean(R.bool.show_wind);
        fahrenheit = "f".equalsIgnoreCase(getString(R.string.temperature_unit).trim());
        bothUnits = getResources().getBoolean(R.bool.show_both_units);
        content = findViewById(R.id.content);
        message = findViewById(R.id.message);
        View changeCity = findViewById(R.id.changeCity);
        changeCity.setOnClickListener(v -> pickCity());
        // The launcher's top-bar pills grow a little with focus.
        changeCity.setOnFocusChangeListener((v, focused) -> grow(v, focused ? 1.04f : 1f));
        changeCity.requestFocus();
        if (!place.contains("name")) {
            message.setText(R.string.pick_first);
            pickCity();
        } else {
            // The last reading at once, with its own "Updated" time; onResume reads a new one and shows it in place.
            Forecast.Report last = Now.last(this);
            if (last != null) show(last);
        }
    }

    @Override
    public void onEnterAnimationComplete() {
        super.onEnterAnimationComplete();
        main.removeCallbacks(openedAnyway);
        if (opened) return;
        opened = true;
        if (entrancePending) {
            entrancePending = false;
            content.setAlpha(1f);
            findViewById(R.id.bottomBar).setAlpha(1f);
            enter();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (place.contains("name")) refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);   // nothing runs while the page is not on screen
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (glowing != null) glowing.cancel();
        io.shutdownNow();
    }

    private void refresh() {
        main.removeCallbacks(tick);
        double lat = Double.longBitsToDouble(place.getLong("lat", 0));
        double lon = Double.longBitsToDouble(place.getLong("lon", 0));
        io.execute(() -> {
            Forecast.Report report;
            try {
                report = Forecast.fetch(lat, lon, forecastDays, Forecast.models(this, place));
            } catch (Exception e) {
                report = null;
            }
            if (report != null) Now.save(this, report);
            Forecast.Report result = report;
            main.post(() -> {
                if (isDestroyed()) return;
                if (result != null) show(result);
                else if (!shown) message.setText(R.string.offline);
                main.postDelayed(tick, result != null ? Forecast.REFRESH_MS : Forecast.RETRY_MS);
            });
        });
    }

    private void show(Forecast.Report r) {
        boolean first = !shown;
        shown = true;
        report = r;
        setText(findViewById(R.id.place), place.getString("name", ""));
        setText(findViewById(R.id.updated), getString(R.string.updated, DateFormat.getTimeFormat(this).format(new Date(r.at))));
        setText(findViewById(R.id.temp), Forecast.degrees(r.tempC, fahrenheit) + "°");
        TextView sky = findViewById(R.id.sky);
        setText(sky, getString(Forecast.sky(r.code, r.day)));
        int symbol = Forecast.icon(r.code, r.day);
        if (!Integer.valueOf(symbol).equals(sky.getTag())) {
            sky.setTag(symbol);
            Drawable skyIcon = getDrawable(symbol);
            int skySize = getResources().getDimensionPixelSize(R.dimen.sky_icon);
            skyIcon.setBounds(0, 0, skySize, skySize);
            sky.setCompoundDrawablesRelative(skyIcon, null, null, null);
        }
        TextView today = findViewById(R.id.today);
        if (r.today != null) {
            setText(today, getString(R.string.today_high_low, Forecast.degrees(r.today.maxC, fahrenheit),
                    Forecast.degrees(r.today.minC, fahrenheit)));
            today.setVisibility(View.VISIBLE);
        } else {
            today.setVisibility(View.GONE);
        }

        showDetails(r);
        showDays(r);
        message.setVisibility(View.GONE);
        content.setVisibility(View.VISIBLE);
        // The first time in, the week has the focus, as Home's tiles do; a refresh leaves it where it is.
        LinearLayout days = findViewById(R.id.days);
        if (first && days.getChildCount() > 0) days.getChildAt(0).findViewById(R.id.card).requestFocus();
        // The glow of the day focused (the first time in, today's, at once), or of now when none is.
        View focused = days.findFocus();
        int at = focused == null ? -1 : days.indexOfChild((View) focused.getParent());
        glowTo(at >= 0 ? dayWarmth(at) : Forecast.warmth(r), !first);
        if (first) {
            if (opened) {
                enter();
            } else {
                // Shown on the last reading before the window is even up: wait, hidden, and rise once it is.
                entrancePending = true;
                content.setAlpha(0f);
                findViewById(R.id.bottomBar).setAlpha(0f);
                main.postDelayed(openedAnyway, OPENING_MS);
            }
        }
    }

    /**
     * Feels like, humidity, wind and air quality, a pill each (item_detail.xml). A new reading with the same words
     * leaves the pills as they are, so nothing is laid out again.
     */
    private void showDetails(Forecast.Report r) {
        List<String> words = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();
        String feels = getString(R.string.feels_like, Forecast.degrees(r.feelsC, fahrenheit));
        if (bothUnits) {
            feels = getString(fahrenheit ? R.string.other_unit_c : R.string.other_unit_f,
                    Forecast.degrees(r.tempC, !fahrenheit)) + " · " + feels;
        }
        words.add(feels);
        icons.add(R.drawable.ic_feels);
        if (showHumidity) {
            words.add(getString(R.string.humidity, r.humidity));
            icons.add(R.drawable.ic_humidity);
        }
        if (showWind) {
            words.add(fahrenheit ? getString(R.string.wind_mph, Forecast.mph(r.windKmh)) : getString(R.string.wind, r.windKmh));
            icons.add(R.drawable.ic_wind);
        }
        if (r.aqi >= 0) {
            words.add(getString(R.string.aqi_line, r.aqi, getString(Forecast.aqiLabel(r.aqi))));
            icons.add(R.drawable.aqi_dot);
        }
        LinearLayout details = findViewById(R.id.details);
        boolean same = details.getChildCount() == words.size();
        for (int i = 0; same && i < words.size(); i++) {
            same = words.get(i).contentEquals(((TextView) details.getChildAt(i)).getText());
        }
        if (same) return;
        details.removeAllViews();
        int iconSize = getResources().getDimensionPixelSize(R.dimen.detail_icon);
        for (int i = 0; i < words.size(); i++) {
            TextView pill = (TextView) getLayoutInflater().inflate(R.layout.item_detail, details, false);
            pill.setText(words.get(i));
            Drawable icon = getDrawable(icons.get(i));
            if (icons.get(i) == R.drawable.aqi_dot) {
                // The dot takes the band's colour in place of the words' (the pill's tint is what colours its icon).
                pill.setCompoundDrawableTintList(ColorStateList.valueOf(Forecast.aqiColour(r.aqi) | 0xFF000000));
                icon.setBounds(0, 0, icon.getIntrinsicWidth(), icon.getIntrinsicHeight());
            } else {
                icon.setBounds(0, 0, iconSize, iconSize);
            }
            pill.setCompoundDrawablesRelative(icon, null, null, null);
            details.addView(pill);
        }
    }

    /**
     * Today, then the days ahead, as glass cards of one width: name, icon, high and low, and a bar of the day's low to
     * high on one scale for them all, today's with a dot at the temperature now; under each, the sky in words. They
     * do not fit on screen: the row scrolls, as the launcher's recent apps do.
     */
    private void showDays(Forecast.Report r) {
        List<Forecast.Day> list = new ArrayList<>();
        if (r.today != null) list.add(r.today);
        list.addAll(r.days);
        LinearLayout days = findViewById(R.id.days);
        // A new reading updates the cards in place: rebuilding them all a second after the page opened (on the last
        // reading) made the entrance's frames late. Only a change in the number of days adds or removes cards.
        while (days.getChildCount() > list.size()) days.removeViewAt(days.getChildCount() - 1);
        if (list.isEmpty()) return;
        // One scale for every bar, which takes in the temperature now too: it can be outside today's low and high.
        double[] range = Forecast.range(r);
        for (int i = 0; i < list.size(); i++) {
            View item = days.getChildAt(i);
            if (item == null) {
                item = getLayoutInflater().inflate(R.layout.item_day, days, false);
                View sky = item.findViewById(R.id.daySky);
                View card = item.findViewById(R.id.card);
                card.setNextFocusDownId(R.id.changeCity);
                // As a recent-apps tile: the card grows, and the words under it light up and move down a little.
                card.setOnFocusChangeListener((v, has) -> {
                    grow(v, has ? FOCUS_SCALE : 1f);
                    if (has && report != null) glowTo(dayWarmth(days.indexOfChild((View) v.getParent())), true);
                    sky.animate().alpha(has ? 1f : REST_NAME_ALPHA).translationY(has ? dp(NAME_DROP_DP) : 0f)
                            .setStartDelay(0).setDuration(FOCUS_MS).setInterpolator(ARRIVE).start();
                });
                days.addView(item);
            }
            boolean isToday = r.today != null && i == 0;
            bindDay(item, list.get(i), isToday ? getString(R.string.today) : null, isToday ? r.tempC : Double.NaN,
                    range);
        }
    }

    /**
     * The warmth of the day at this place in the row, 0 the week's coldest to 1 its warmest, for the glow: today's is
     * the temperature now (the dot), another day's the middle of its bar.
     */
    private float dayWarmth(int at) {
        Forecast.Report r = report;
        boolean todayFirst = r.today != null;
        if (todayFirst && at == 0) return Forecast.warmth(r);
        int index = at - (todayFirst ? 1 : 0);
        if (index < 0 || index >= r.days.size()) return Forecast.warmth(r);
        Forecast.Day d = r.days.get(index);
        return Forecast.position(Forecast.range(r), (d.minC + d.maxC) / 2);
    }

    /**
     * The glow to this warmth: at once (fade false, or the first time), or moving there from the warmth on screen,
     * so a new focus mid-move carries on from where it is rather than jumping.
     */
    private void glowTo(float warmth, boolean fade) {
        if (glowing != null) glowing.cancel();
        if (!fade || shownWarmth < 0) {
            setGlow(warmth);
            return;
        }
        if (Math.abs(warmth - shownWarmth) < 0.005f) return;
        glowing = ValueAnimator.ofFloat(shownWarmth, warmth);
        glowing.setDuration(GLOW_MS);
        glowing.setInterpolator(ARRIVE);
        glowing.addUpdateListener(a -> setGlow((float) a.getAnimatedValue()));
        glowing.start();
    }

    private void setGlow(float warmth) {
        shownWarmth = warmth;
        boolean day = report.day;
        warmGlow.setAlpha(Math.round(255 * Forecast.warmStrength(warmth, day)));
        coolGlow.setAlpha(Math.round(255 * Forecast.coolStrength(warmth, day)));
    }

    /**
     * label replaces the day's name (Today); nowC, NaN on the days ahead, places the dot at the temperature now. range
     * is the scale every bar is drawn on (Forecast.range).
     */
    private void bindDay(View item, Forecast.Day d, String label, double nowC, double[] range) {
        View card = item.findViewById(R.id.card);
        String name = label != null ? label : d.date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.getDefault());
        TextView top = card.findViewById(R.id.dayName);
        setText(top, name);
        int symbol = Forecast.icon(d.code, true);
        if (!Integer.valueOf(symbol).equals(top.getTag())) {
            top.setTag(symbol);
            Drawable icon = getDrawable(symbol);
            int size = getResources().getDimensionPixelSize(R.dimen.day_icon);
            icon.setBounds(0, 0, size, size);
            top.setCompoundDrawablesRelative(null, null, icon, null);
        }
        setText(item.findViewById(R.id.daySky), getString(Forecast.sky(d.code, true)));
        SpannableStringBuilder temps = new SpannableStringBuilder(Forecast.degrees(d.maxC, fahrenheit) + "°  ");
        int lowAt = temps.length();
        temps.append(String.valueOf(Forecast.degrees(d.minC, fahrenheit))).append('°');
        // The low a step quieter than the high, in the card's own dimmer colour (it turns dark on focus too).
        temps.setSpan(new TextAppearanceSpan(null, 0, 0, getColorStateList(R.color.card_text_dim), null),
                lowAt, temps.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        setText(card.findViewById(R.id.dayTemps), temps);
        float from = Forecast.position(range, d.minC), to = Forecast.position(range, d.maxC);
        // A day with almost no range still shows a sliver of colour.
        boolean moved = weigh(card.findViewById(R.id.rangeBefore), from);
        moved |= weigh(card.findViewById(R.id.rangeFill), Math.max(to - from, 0.04f));
        moved |= weigh(card.findViewById(R.id.rangeAfter), 1 - to);
        View nowRow = card.findViewById(R.id.nowRow);
        if (Double.isNaN(nowC)) {
            nowRow.setVisibility(View.GONE);
        } else {
            float at = Forecast.position(range, nowC);
            moved |= weigh(card.findViewById(R.id.nowBefore), at);
            moved |= weigh(card.findViewById(R.id.nowAfter), 1 - at);
            nowRow.setVisibility(View.VISIBLE);
        }
        if (moved) {
            // Both rows: a view laid out with the same size as before keeps its last layout otherwise.
            card.findViewById(R.id.dayRange).requestLayout();
            nowRow.requestLayout();
        }
        String said = name + ", " + getString(Forecast.sky(d.code, true)) + ", " + getString(R.string.high_low,
                Forecast.degrees(d.maxC, fahrenheit), Forecast.degrees(d.minC, fahrenheit));
        if (!Double.isNaN(nowC)) said += ", " + Forecast.degrees(nowC, fahrenheit) + "°";
        card.setContentDescription(said);
    }

    /** Setting the same text would still lay the card out again. */
    private static void setText(TextView view, CharSequence text) {
        if (!text.toString().contentEquals(view.getText())) view.setText(text);
    }

    /**
     * The first reading: the place and time, then now, each pill, each day and Change city rise into place one after
     * another, as the recent apps' title, line and cards do.
     */
    private void enter() {
        float from = dp(RISE_DP);
        long delay = 0;
        rise((View) findViewById(R.id.place).getParent(), from / 2, delay);
        rise((View) findViewById(R.id.temp).getParent(), from, delay += STAGGER_MS);
        ViewGroup details = findViewById(R.id.details);
        for (int i = 0; i < details.getChildCount(); i++) rise(details.getChildAt(i), from / 2, delay += STAGGER_MS);
        // Each card and its words rise on their own, not the day as one: a view rises in a layer of its own bounds,
        // and the focused card, grown 1.1, would be cut square by its day's.
        ViewGroup days = findViewById(R.id.days);
        for (int i = 0; i < days.getChildCount(); i++) {
            delay += STAGGER_MS;
            View card = days.getChildAt(i).findViewById(R.id.card);
            rise(card, from, delay);
            // The words end where focus puts them: the focused day's lit and lower, as its focus change would.
            boolean focused = card.isFocused();
            rise(days.getChildAt(i).findViewById(R.id.daySky), from, delay, focused ? 1f : REST_NAME_ALPHA,
                    focused ? dp(NAME_DROP_DP) : 0f);
        }
        delay += STAGGER_MS;
        rise(findViewById(R.id.changeCity), from / 2, delay);
        rise(findViewById(R.id.credit), from / 2, delay);
    }

    private float dp(int dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private static void rise(View v, float from, long delay) {
        rise(v, from, delay, 1f, 0f);
    }

    /** v rises from this far below its resting place (alpha, y), fading in to that alpha. */
    private static void rise(View v, float from, long delay, float alpha, float y) {
        v.setAlpha(0f);
        v.setTranslationY(y + from);
        v.animate().alpha(alpha).translationY(y).setStartDelay(delay).setDuration(ENTER_MS).setInterpolator(ARRIVE)
                .withLayer().start();
    }

    private static void grow(View v, float scale) {
        v.animate().scaleX(scale).scaleY(scale).setStartDelay(0).setDuration(FOCUS_MS).setInterpolator(ARRIVE).start();
    }

    /** True when the weight changed. */
    private static boolean weigh(View v, float weight) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        float w = Math.max(weight, 0f);
        if (lp.weight == w) return false;
        lp.weight = w;
        return true;
    }

    /** Type a city, pick it from the matches, keep it. Back leaves the current place as it was. */
    private void pickCity() {
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint(R.string.pick_hint);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(name, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.pick_title)
                .setView(box)
                .setPositiveButton(R.string.pick_search, (d, w) -> search(name.getText().toString()))
                .create();
        name.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            dialog.dismiss();
            search(name.getText().toString());
            return true;
        });
        dialog.show();
        name.requestFocus();
    }

    private void search(String typed) {
        if (typed.trim().isEmpty()) {
            pickCity();
            return;
        }
        io.execute(() -> {
            List<Forecast.Place> found;
            try {
                found = Forecast.search(typed);
            } catch (Exception e) {
                found = null;
            }
            List<Forecast.Place> places = found;
            main.post(() -> {
                if (isDestroyed()) return;
                if (places == null) {
                    Toast.makeText(this, R.string.offline, Toast.LENGTH_LONG).show();
                } else if (places.isEmpty()) {
                    Toast.makeText(this, getString(R.string.pick_none, typed.trim()), Toast.LENGTH_LONG).show();
                    pickCity();
                } else {
                    choose(places);
                }
            });
        });
    }

    private void choose(List<Forecast.Place> places) {
        CharSequence[] rows = new CharSequence[places.size()];
        for (int i = 0; i < rows.length; i++) {
            Forecast.Place p = places.get(i);
            rows[i] = p.detail.isEmpty() ? p.name : p.name + " · " + p.detail;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.pick_title)
                .setItems(rows, (d, which) -> {
                    Forecast.Place p = places.get(which);
                    place.edit()
                            .putString("name", p.name)
                            .putLong("lat", Double.doubleToRawLongBits(p.latitude))
                            .putLong("lon", Double.doubleToRawLongBits(p.longitude))
                            .putString("country", p.country)
                            .apply();
                    message.setText(R.string.loading);
                    refresh();
                })
                .show();
    }
}
