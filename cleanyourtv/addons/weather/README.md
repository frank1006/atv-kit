# Weather

The weather for one city, full screen on the TV: now (temperature, conditions, today's high and low), its details
(feels-like, humidity and wind, the US air quality index), and today and the days ahead. For the
[Clean Your TV](../../README.md) launcher it declares a [module card](../../docs/modules.md), so once you answer
**Show card** its tile reads "Weather" with a sky-blue glow, and it can give Home's header its weather (below); on any
other Android TV home it is an ordinary app.

![The weather page](https://raw.githubusercontent.com/Sumyabazar1/cleanyourtv/pr-assets/addon-weather/addon-3-weather.png)

- **The look** follows the Clean Your TV launcher: its Outfit font (SIL Open Font License, in
  [FONT-LICENSE-OFL.txt](FONT-LICENSE-OFL.txt)), its glass pills and panels, and the weather icons of its top-bar
  pill (Material Symbols, Apache 2.0).
  - The details are a resting-glass pill each; the air quality's band is a coloured dot on its pill.
  - Today and each day ahead are cards the size of a recent-apps tile, with the sky in words under each, in a row
    that scrolls with Left and Right like the launcher's recent apps; the focused card turns white and grows.
  - Each card has a bar of its day's low to high, on one scale for the whole row; today's bar has a dot at the
    temperature now.
  - Behind it all, a glow in the bars' colours, warm amber and cool blue, mixed by how warm the focused day is on
    that scale (today's: the temperature now), and moving smoothly as the focus moves.
  - The page opens at once on the last reading and shows a new one in place; its sections rise into place as the
    recent apps' do, one even gap apart, and the window fades in and out.
- **The city** is chosen once: type its name with the remote's keyboard (or voice), pick it from the matches,
  and it is kept. **Change city** at the bottom chooses another.
- **Data** comes from [Open-Meteo](https://open-meteo.com/), which needs no account or key: the weather from
  the national weather service's own model where Open-Meteo has one for the city's country (Environment Canada,
  NOAA, Météo-France, the Met Office, MET Norway, DMI, KNMI, MeteoSwiss, JMA, CMA), finer there than a worldwide
  model (in a Canadian city, GEM read 5 °C as phones did, ICON 8 °C); elsewhere, and for any day a national
  model does not reach, DWD's ICON model (Open-Meteo's default blend called light snow over Ulaanbaatar
  "overcast"; ICON matched what was falling). The air quality comes from the Copernicus (CAMS) model, shown on
  the US AQI scale.
- **Snow, not rain, near freezing.** The models type precipitation by the air temperature and call snow
  "light rain" at 1 °C (ICON and GFS did, in a Ulaanbaatar blizzard). The app decides by the wet-bulb
  temperature instead, which counts how dry the air is: at 1 °C or below, rain and drizzle are shown as snow.
- The page reads Open-Meteo only while it is on screen, every 30 minutes, or 5 minutes after a failure. Out of
  sight it reads nothing, except the one reading Home's header can ask for (below).
- **On Home** (the launcher's `header_weather_app` set to this app's package): an icon and a few words after the
  date, or, with `header_weather_by_buttons`, the icon and temperature as a top-bar button that opens this app. The
  launcher reads the last reading from this app on the TV ("7° Partly cloudy", the weather code for its icon,
  nothing else) and never goes online itself. Home asks when it comes to the front, and every 15 minutes while it
  stays there; when the reading is over 15 minutes old, the app reads Open-Meteo once, as a job that waits for the
  network (at most every 5 minutes). Nothing runs on a timer: with Home out of sight, nothing is read.
- Degrees Celsius by default (Fahrenheit is an option below); English, French, Italian, Turkish or Korean words,
  with the TV's language; day names follow the TV's language.

## Options

Set in `app/src/main/res/values/options.xml` before building. With the defaults the page is as above.

| Option | Default | What it does |
|---|---|---|
| `forecast_days` | `3` | Days in the row after today, 3 to 7. The cards keep their size; when they do not fit, the row scrolls. |
| `show_humidity` | `false` | The relative humidity now, as a pill after "Feels like". |
| `show_wind` | `false` | The wind speed now in km/h, as a pill after "Feels like" (mph with `temperature_unit` `f`). |
| `temperature_unit` | `c` | `f` shows degrees Fahrenheit for the temperature now, feels-like and the days' highs and lows, converted from the reading before rounding. |
| `show_both_units` | `false` | The temperature now in the other unit too, at the start of the "Feels like" pill ("22°C · Feels like 70°"). |
| `weather_model` | `auto` | `auto` picks the model by the city's country, as above. Or one [Open-Meteo model](https://open-meteo.com/en/docs) for every place, such as `icon_seamless` (the add-on's only model before `auto`). |

![A week, with humidity and wind](https://raw.githubusercontent.com/majnioui/cleanyourtv-contrib/pr-assets/addon-weather-week/weather-options.png)

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Open-Meteo: the place search, the weather and the air quality. |

That is all; the job for Home's reading needs none. What is sent: the city name you type in the search (and once, for a city chosen before this version, its name again to find its country), then that place's latitude and longitude. No
account, no telemetry, no ads, no background service, no location permission. The chosen city stays in the
app's own storage (backups are off).

## Build and install

JDK 17 and Android SDK platform 34 with build-tools 34.0.0, as for the launcher:

```bash
cd addons/weather && ./gradlew assembleRelease
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py --push app/build/outputs/apk/release/app-release.apk /data/local/tmp/addon.apk
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py 'pm install -r /data/local/tmp/addon.apk; rm -f /data/local/tmp/addon.apk'
```

Press Home: the launcher asks once whether to show the card. Change the answer later by holding OK on
Settings, then Module cards.

The release build is signed with the debug key so it installs over ADB like the launcher. Sign your own builds
with your own key if you hand them out; the launcher pins its Show answer to the signing key.

## Tested on

Hisense SmartTV 4K FFM (lushan, MediaTek MT9676), Google TV 12 / Android 12: city search with the TV keyboard,
the weather page, the card prompt, and the card shown in the launcher's dock.

The options: Xiaomi TV (MiTV-MOOR2), Google TV 11, with `forecast_days` 7, `show_humidity` and `show_wind` on.

## Maintainer

[@Sumyabazar1](https://github.com/Sumyabazar1)
