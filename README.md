# Charging Live Meter

Android live wallpaper project for Samsung Galaxy A23 / Android 14.

## Import
1. Extract the ZIP.
2. Open the `ChargingLiveMeter` folder in KlencodIDE as an existing Gradle project.
3. Allow Gradle sync and install missing Android SDK/build tools if prompted.
4. Build and install the debug APK.
5. Open the app and tap **معاينة وتعيين الخلفية الحية**.
6. Choose whether to apply it to the Home screen (or both if the launcher offers that option).

## Measurement note
The displayed W value is an estimate of battery-side electrical power calculated from the battery current and voltage exposed by Android. It is NOT a guaranteed measurement of USB input power or the charger's advertised maximum wattage. The USB/charger input current is not exposed to normal apps without root. Some Samsung firmware versions may not expose a usable current reading; in that case the wallpaper displays `-- W`.

## What changed in this revision
- `BatteryReader.kt` (new): single place that reads level/status/voltage/temperature and the current.
  Order: CURRENT_NOW -> CURRENT_AVERAGE -> sysfs (if readable) -> CHARGE_COUNTER delta (estimate, needs 30 s).
  Unit is detected (uA vs mA), the sign convention is learned from real charging/discharging readings,
  and an unavailable reading stays `null` (`-- W`), never 0.0.
- `ChargingWallpaperService.kt`: semi-circular gauge (-3k..+3k mA), needle driven only by a real reading,
  30 fps animation while charging / 10 fps otherwise, loop stops when the wallpaper is hidden.
- `MainActivity.kt`: the app screen now shows a live diagnostics readout (raw values from the phone).

## Precise battery percentage (e.g. 22.545 %)
Computed as CHARGE_COUNTER / full capacity. Full capacity is learned from real level changes (stored
in SharedPreferences) or taken from the firmware's design capacity when available. If neither agrees with
the integer level within 1.5 points, only the integer percentage is shown. The decimals are an estimate.

## Build status
This is a source project ZIP. It has not been built or tested on a physical phone in this environment.
