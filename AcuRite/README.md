# AcuRite Weather Station

Cloud driver for AcuRite weather sensors via [myacurite.com](https://www.myacurite.com).
Polls the dashboard API and publishes temperature, humidity, pressure,
illuminance, UV, rainfall, wind, dew point, lightning strikes, and more —
plus a couple of derived attributes that are nicer to trigger automations
on than the raw data.

## Supported hardware

Anything visible on your AcuRite dashboard. The driver is generic over
the dashboard's sensor list (it doesn't hardcode specific stations).

Tested with an AcuRite Atlas; should also work with Iris, 5-in-1, 3-in-1,
and most other AcuRite sensors.

## Setup

1. You need a working **myacurite.com** account with your hub registered there
2. Find your **Device ID** (sometimes called Hub ID): in Chrome, open the
   myacurite dashboard with DevTools → Network tab, and look for a URL like
   `…/accounts/<account_id>/dashboard/hubs/<DEVICE_ID>`. The trailing
   number is the Device ID.

## Install

In **Hubitat Package Manager → Install**, search for `AcuRite Weather Station`.

Then:
1. **Devices → Add Device → Virtual**
2. **Type:** AcuRite Weather Station
3. Fill in the preferences (username, password, device ID, poll interval), Save
4. Click **Initialize**

## Preferences

| Name | Default | What it does |
|---|---|---|
| AcuRite Username | required | myacurite.com login email |
| AcuRite Password | required | myacurite.com password |
| Device ID | required | Hub ID from your dashboard URL |
| Poll Interval | 5 Minutes | 5/10/15/30 Minutes or 1/3 Hours |
| Lightning-active window (minutes) | 10 | How long `lightningActive` stays true after a strike |
| Debug Logging | off | Verbose logging |

## Attributes

**Standard capabilities:** `temperature`, `humidity`, `pressure`, `illuminance`,
`ultravioletIndex`, `battery`

**Wind:** `windSpeed`, `windDirection` (degrees), `wind_direction_abbreviation` ("SSW"),
`wind_direction_point` ("South-southwest"), `wind_speed_average`

**Other sensors:** `dew_point`, `wind_chill`, `rainfall`, `light_intensity`,
`measured_light`, `interference`

**Lightning:** `lightning_strike_count`, `lightning_closest_strike_distance`,
`lightning_last_strike_distance`, `lastStrikeAt`, `lightningActive`

**Location / device info:** `location_name`, `location_latitude`,
`location_longitude`, `location_elevation`, `location_timezone`,
`device_country`, `device_name`, `device_model`, `device_status`,
`device_battery_level`, `device_signal_strength`, `device_last_checkin`

**Derived:**

- **`lightningActive`** — `"true"` when a strike was detected within the
  configured window. One-step Rule Machine trigger for storm-mode rules.
- **`weatherSummary`** — composed string like
  `"72°F, 45% RH, wind SW 8 mph"` for dashboard tiles and TTS rules.

## Commands

`Refresh` (or `poll()` as a legacy alias) — fetch current data immediately.

## Notes

- Session token is cached across polls (~95% reduction in login API calls
  vs. always logging in).
- Async HTTP throughout — a slow myacurite.com response won't block the
  Hubitat scheduler.
- Hub firmware floor: **2.2.0** (uses `device.deleteCurrentState` for
  upgrade cleanup).
