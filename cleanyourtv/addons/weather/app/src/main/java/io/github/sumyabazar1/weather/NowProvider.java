package io.github.sumyabazar1.weather;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * The last reading for the Clean Your TV header (action com.gathin.tvhome.action.HEADER_WEATHER): one row, "text"
 * ("7° Partly cloudy"), "temp" ("7°"), "at" (when it was read, ms), "code" (the WMO weather code) and "day" (1 by day, 0 at night). Nothing else: no
 * place, no coordinates. A reading older than {@link Forecast#HEADER_REFRESH_MS} asks FetchJob for a new one, at most
 * every {@link Forecast#RETRY_MS}, so the weather is only read while someone is looking at Home, never on a timer.
 */
public final class NowProvider extends ContentProvider {
    static final int JOB_ID = 1;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        SharedPreferences now = Now.prefs(getContext());
        long at = now.getLong("at", 0);
        long time = System.currentTimeMillis();
        boolean placed = getContext().getSharedPreferences(WeatherActivity.PREFS, 0).contains("name");
        if (placed && time - at > Forecast.HEADER_REFRESH_MS && time - now.getLong("tried", 0) > Forecast.RETRY_MS) {
            now.edit().putLong("tried", time).apply();
            JobScheduler jobs = getContext().getSystemService(JobScheduler.class);
            if (jobs != null) {
                jobs.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(getContext(), FetchJob.class))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
            }
        }
        MatrixCursor row = new MatrixCursor(new String[]{"text", "temp", "at", "code", "day"});
        String text = now.getString("text", null);
        if (text != null) row.addRow(new Object[]{text, now.getString("temp", null), at, now.getInt("code", -1),
                now.getBoolean("day", true) ? 1 : 0});
        return row;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
