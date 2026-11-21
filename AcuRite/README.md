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
| AcuRite username | required | myacurite.com login email |
| AcuRite password | required | myacurite.com password |
| Device ID | required | Hub ID from your dashboard URL |
| Poll interval | 5 Minutes | 5/10/15/30 Minutes or 1/3 Hours |
| Lightning-active window (minutes) | 10 | How long `lightningActive` stays true after a strike |
| Enable debug logging | off | Verbose logging; turns itself off after 30 minutes |
| Enable descriptionText logging | on | Logs one line when temperature, humidity, pressure, light, UV, wind, rainfall, lightningActive or health changes |

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

**Health:** `healthStatus`: `offline` while myAcuRite isn't answering or is
rejecting the login, or while the station itself hasn't reported to
myAcuRite for over 2 hours (its readings are then stale; one warning is
logged). `online` otherwise.

**Derived:**

- **`lightningActive`** — `"true"` when a strike was detected within the
  configured window; it turns `"false"` when the window ends. One-step Rule
  Machine trigger for storm-mode rules.
- **`weatherSummary`** — composed string like
  `"72°F, 45% RH, wind SW 8 mph"` for dashboard tiles and TTS rules.

## Commands

`Refresh` (or `poll()` as a legacy alias) — fetch current data immediately.
`Initialize` — start a fresh session, reschedule the poll and fetch now.

## Notes

- The session token is cached across polls and renewed after an hour or
  when myAcuRite rejects it (401/403). Other errors, such as timeouts or a
  myAcuRite outage, keep the session and wait for the next poll.
- **Connection problems** are logged once, not every poll: a warning when
  myAcuRite isn't answering (an error if it rejects the login), a reminder
  at most hourly, and an info line with the outage length when it recovers.
- The driver reads the first device the dashboard API returns for the hub.
- Async HTTP throughout — a slow myacurite.com response won't block the
  Hubitat scheduler.
- Hub firmware floor: **2.2.9** (the driver runs single-threaded, an option
  added in 2.2.9).
