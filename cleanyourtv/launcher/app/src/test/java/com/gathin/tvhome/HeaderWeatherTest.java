package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** header_weather_app: the icon for a weather code, and another app's words made safe for Home. */
public class HeaderWeatherTest {
    @Test
    public void clearAndPartlyCloudyHaveADayAndANightIcon() {
        assertEquals(R.drawable.ic_weather_clear_day, HeaderWeather.icon(0, true));
        assertEquals(R.drawable.ic_weather_clear_night, HeaderWeather.icon(1, false));
        assertEquals(R.drawable.ic_weather_partly_cloudy_day, HeaderWeather.icon(2, true));
        assertEquals(R.drawable.ic_weather_partly_cloudy_night, HeaderWeather.icon(2, false));
        assertEquals(R.drawable.ic_weather_cloud, HeaderWeather.icon(3, false));
    }

    @Test
    public void everyWmoGroupHasItsIcon() {
        assertEquals(R.drawable.ic_weather_foggy, HeaderWeather.icon(48, true));
        assertEquals(R.drawable.ic_weather_rainy, HeaderWeather.icon(51, true));    // drizzle
        assertEquals(R.drawable.ic_weather_rainy, HeaderWeather.icon(66, true));    // freezing rain
        assertEquals(R.drawable.ic_weather_rainy, HeaderWeather.icon(82, true));    // showers
        assertEquals(R.drawable.ic_weather_weather_snowy, HeaderWeather.icon(75, true));
        assertEquals(R.drawable.ic_weather_weather_snowy, HeaderWeather.icon(86, true));   // snow showers
        assertEquals(R.drawable.ic_weather_thunderstorm, HeaderWeather.icon(99, true));
    }

    @Test
    public void aCodeWmoDoesNotUseShowsTheWordsAlone() {
        assertEquals(0, HeaderWeather.icon(42, true));
        assertEquals(0, HeaderWeather.icon(-1, true));
    }

    @Test
    public void anotherAppsWordsAreCleanedAndShort() {
        assertEquals("7° Partly cloudy", HeaderWeather.clean("7° Partly cloudy"));
        // No line breaks or bidi overrides on Home, as for a card's label.
        assertEquals("7° Rain", HeaderWeather.clean("7°\nRain"));
        assertEquals(24, HeaderWeather.clean("12° A very long description of the sky indeed").length());
        assertNull(HeaderWeather.clean(""));
        assertNull(HeaderWeather.clean(null));
    }
}
