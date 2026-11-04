package io.github.sumyabazar1.weather;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.SharedPreferences;

/** One reading for the header, asked for by NowProvider once the network is up; then nothing until asked again. */
public final class FetchJob extends JobService {
    private Thread thread;

    @Override
    public boolean onStartJob(JobParameters params) {
        SharedPreferences place = getSharedPreferences(WeatherActivity.PREFS, MODE_PRIVATE);
        double lat = Double.longBitsToDouble(place.getLong("lat", 0));
        double lon = Double.longBitsToDouble(place.getLong("lon", 0));
        thread = new Thread(() -> {
            try {
                // As many days as the page shows, so it can open on this reading in full.
                int days = Forecast.forecastDays(getResources().getInteger(R.integer.forecast_days));
                Now.save(this, Forecast.fetch(lat, lon, days, Forecast.models(this, place)));
            } catch (Exception ignored) {
                // Offline or Open-Meteo down: the header keeps the last reading, and asks again after RETRY_MS.
            }
            jobFinished(params, false);
        });
        thread.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (thread != null) thread.interrupt();
        return false;
    }
}
