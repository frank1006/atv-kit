package io.github.sumyabazar1.weather;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** The tables that turn Open-Meteo's numbers into words and colours, and the models and scales the page uses. */
public class ForecastTest {
    @Test
    public void aqiBandsFollowTheUsScale() {
        assertEquals(0, Forecast.aqiBand(0));
        assertEquals(0, Forecast.aqiBand(50));
        assertEquals(1, Forecast.aqiBand(51));
        assertEquals(1, Forecast.aqiBand(100));
        assertEquals(2, Forecast.aqiBand(150));
        assertEquals(3, Forecast.aqiBand(151));
        assertEquals(4, Forecast.aqiBand(300));
        assertEquals(5, Forecast.aqiBand(500));
    }

    @Test
    public void skyWordsAreAsFineAsTheCode() {
        assertEquals(R.string.sky_sunny, Forecast.sky(0, true));
        assertEquals(R.string.sky_clear, Forecast.sky(0, false));
        assertEquals(R.string.sky_partly_cloudy, Forecast.sky(2, true));
        assertEquals(R.string.sky_light_snow, Forecast.sky(71, true));
        assertEquals(R.string.sky_heavy_snow, Forecast.sky(75, true));
        assertEquals(R.string.sky_snow_showers, Forecast.sky(86, true));
        assertEquals(R.string.sky_thunderstorm, Forecast.sky(99, true));
        assertEquals(R.string.sky_cloudy, Forecast.sky(42, true));   // a code WMO does not use
    }

    @Test
    public void rainNearFreezingInDryAirIsSnow() {
        // The Ulaanbaatar blizzard: ICON said light rain (61) at 1.1 °C and 86 % humidity.
        assertEquals(71, Forecast.snowIfCold(61, 1.1, 86));
        assertEquals(73, Forecast.snowIfCold(63, 0.5, 80));
        assertEquals(75, Forecast.snowIfCold(82, -1, 90));
        // Warm rain stays rain; freezing rain stays freezing rain; dry codes are untouched.
        assertEquals(61, Forecast.snowIfCold(61, 6, 90));
        assertEquals(66, Forecast.snowIfCold(66, 0, 95));
        assertEquals(3, Forecast.snowIfCold(3, -5, 60));
    }

    @Test
    public void forecastDaysStayBetweenThreeAndSeven() {
        assertEquals(3, Forecast.forecastDays(3));
        assertEquals(7, Forecast.forecastDays(7));
        assertEquals(3, Forecast.forecastDays(0));
        assertEquals(7, Forecast.forecastDays(16));
    }

    @Test
    public void wetBulbMatchesStull() {
        // Stull (2011): 20 °C and 50 % give a wet-bulb of about 13.7 °C.
        assertEquals(13.7, Forecast.wetBulb(20, 50), 0.1);
        assertEquals(true, Forecast.wetBulb(1.1, 86) < 1.0);
    }

    @Test
    public void degreesRoundOnlyOnceInEitherUnit() {
        assertEquals(22, Forecast.degrees(21.6, false));
        assertEquals(71, Forecast.degrees(21.6, true));   // 70.88, not 22 °C rounded first (71.6 -> 72)
        assertEquals(32, Forecast.degrees(0, true));
        assertEquals(-40, Forecast.degrees(-40, true));
        assertEquals(212, Forecast.degrees(100, true));
    }

    @Test
    public void windInMilesPerHour() {
        assertEquals(0, Forecast.mph(0));
        assertEquals(10, Forecast.mph(16));
        assertEquals(62, Forecast.mph(100));
    }

    @Test
    public void aNationalModelLeadsWhereThereIsOne() {
        assertEquals("gem_seamless", Forecast.nationalModel("CA"));
        assertEquals("gem_seamless", Forecast.nationalModel("ca"));
        assertEquals("ukmo_seamless", Forecast.nationalModel("IE"));
        assertNull(Forecast.nationalModel("MN"));   // Mongolia keeps ICON, the model chosen for its snow
        assertNull(Forecast.nationalModel(""));
        assertNull(Forecast.nationalModel(null));
    }

    @Test
    public void autoPutsTheNationalModelAheadOfIcon() {
        assertArrayEquals(new String[]{"gem_seamless", "icon_seamless"}, Forecast.models("auto", "CA"));
        assertArrayEquals(new String[]{"icon_seamless"}, Forecast.models("auto", "MN"));
        assertArrayEquals(new String[]{"icon_seamless"}, Forecast.models("auto", null));
        assertArrayEquals(new String[]{"icon_seamless"}, Forecast.models(" ", "MN"));
    }

    @Test
    public void aNamedModelIsUsedAlone() {
        assertArrayEquals(new String[]{"icon_seamless"}, Forecast.models("icon_seamless", "CA"));
        assertArrayEquals(new String[]{"metno_seamless"}, Forecast.models("metno_seamless", "MN"));
    }

    @Test
    public void warmthIsWhereNowSitsOnTheWeeksScale() {
        java.time.LocalDate d = java.time.LocalDate.of(2026, 10, 31);
        Forecast.Day today = new Forecast.Day(d, 0, 14, 2);
        java.util.List<Forecast.Day> ahead = java.util.Arrays.asList(new Forecast.Day(d.plusDays(1), 0, 18, 4));
        // 2 to 18 across the week: 4 now is an eighth of the way up.
        Forecast.Report r = new Forecast.Report(4, 1, 0, false, today, ahead, -1, 80, 10, 0, null, new String[0]);
        assertArrayEquals(new double[]{2, 18}, Forecast.range(r), 0);
        assertEquals(0.125f, Forecast.warmth(r), 0.0001f);
        // Colder now than any forecast low: the scale reaches down to it, and it is the coldest end.
        Forecast.Report cold = new Forecast.Report(-1, -4, 0, false, today, ahead, -1, 80, 10, 0, null, new String[0]);
        assertEquals(-1, Forecast.range(cold)[0], 0);
        assertEquals(0f, Forecast.warmth(cold), 0f);
    }
}
