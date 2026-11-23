# Rheem EcoNet (David's fork)

A fork of Dominick Meglio's [Rheem EcoNet Integration](https://github.com/dcmeglio/hubitat-rheem)
(app and water heater driver, 2.3.0, unmaintained since 2020), fixed and extended
for my own heater. Credit for the original goes to Dominick; the field meanings
come from [pyeconet](https://github.com/w1ll1am23/pyeconet), the library behind
Home Assistant's EcoNet integration.

**Status:** personal. Upstream has no license, so this fork isn't offered
through HPM and keeps Dominick's copyright. History: [dlaporte/hubitat-rheem](https://github.com/dlaporte/hubitat-rheem)
is an untouched fork of upstream.

## Install (over an existing 2.3.0 install)

The app and driver keep upstream's names and namespace (`dcm.rheem`), so this is
an in-place update. Devices, rules and dashboards keep working.

1. **Apps code → Rheem EcoNet Integration → Import:**
   `https://raw.githubusercontent.com/dlaporte/Hubitat/main/RheemEcoNet/app/Rheem_EcoNet_Integration.groovy`.
   Save. Update the app first: the new app still works with the old driver.
2. **Drivers code → Rheem Econet Water Heater → Import:**
   `https://raw.githubusercontent.com/dlaporte/Hubitat/main/RheemEcoNet/device/Rheem_EcoNet_Water_Heater.groovy`.
   Save.
3. On each water heater device, click **Initialize**.
4. Open **Apps → Rheem EcoNet Integration → Next → Done**, once. This logs
   in and starts the 30-minute and hourly polls.
5. If HPM installed 2.3.0, open **HPM → Match Up**, and **Un-Match** "Rheem
   EcoNet Integration". Don't **Uninstall** it: that deletes the code and the
   devices.

Avoid opening the app's settings repeatedly. Each visit logs in, and Rheem
rate-limits logins (HTTP 429).

## What changed from 2.3.0

- **Login:**
  - the token is kept;
  - the app logs in again only when Rheem rejects it, at most every 10 minutes, and not for 30 minutes after a rate limit;
  - the settings page says why a login failed (credentials, rate limit, or Rheem unreachable).
- **Connection:**
  - the driver retries after 15 s, 1, 5 and 15 minutes, then every 30 minutes, asking the app for a new login;
  - one warning per outage after a minute, an hourly reminder, and an info line with the outage length on recovery;
  - 2.3.0 logged a warning every 15 seconds forever when its token expired.
- **Polling:** every heater's full state every 30 minutes, and energy and water use every hour.
- **Fixes:**
  - the setpoint is a number (Done used to store the whole constraints map);
  - heat/off follows `@ENABLED`, whether Rheem sends it as `true` or `1`;
  - `setWaterHeaterMode("Off")` turns an on/off heater off (it always sent on);
  - mode numbers come from the heater's own list;
  - the settings, including the password, are no longer logged.

## Attributes

| Attribute | Values | Heaters |
|---|---|---|
| `heatingSetpoint`, `thermostatSetpoint` | °F | all |
| `thermostatMode` | `off`, `heat`, `auto` (Energy Saving), `emergency heat` (High Demand, Performance) | all |
| `waterHeaterMode` | the heater's mode name, for example `ENERGY SAVING`; on/off heaters `ON` / `OFF` | all |
| `supportedWaterHeaterModes` | JSON list of the heater's mode names | heaters with modes |
| `thermostatOperatingState` | `heating` / `idle` | all |
| `runningState` | Rheem's running text, for example `Compressor Running`, or `idle` | all |
| `hotWaterAvailable` | 100, 66, 33 or 0 % | heat pumps and others that report it |
| `tankHealth`, `compressorHealth` | % | heat pumps |
| `shutoffValve` | `open` / `closed` | heaters with the leak kit |
| `alertCount` | number of active alerts (details are only in Rheem's app) | all |
| `away`, `vacation` | `on` / `off` | most |
| `scheduleStatus` | for example `Following Schedule` or `Schedule overridden Until 10:00 AM`, or `none` | heaters with schedules |
| `wifiSignal` | dBm (changes aren't logged) | all |
| `firmwareVersion` | Wi-Fi module version | all |
| `healthStatus` | `offline` after a minute without Rheem's MQTT service, or while the heater reports it is disconnected | all |
| `energyToday` | kWh, or kBTU for gas | heaters with usage reports |
| `waterToday` | gallons | heaters with usage reports |

An attribute the heater doesn't report stays empty.

## Commands

| Command | What it does |
|---|---|
| `setHeatingSetpoint` | Sets the setpoint, clamped to the heater's limits (an info line says so) |
| `heat`, `off`, `setThermostatMode` | On/off heaters: on or off. Heaters with modes: Off, or a heating mode (the current one, else Heat Pump Only, Electric or Gas) |
| `auto`, `emergencyHeat` | Energy Saving; High Demand or Performance |
| `setWaterHeaterMode` | A mode by name from the heater's own list. The 2.3.0 names still work: Energy Saver = Energy Saving, Heat Pump = Heat Pump Only, Normal = Electric, and Electric/Gas matches either |
| `setAway` | `on` / `off` |
| `resumeSchedule` | Resumes the heater's schedule. If Rheem asks to confirm ending an away event, the driver confirms once |
| `refresh` | Reads the heater from Rheem now |

A command or mode the heater doesn't support logs a warning that names what it
offers, and sends nothing. Fifteen seconds after every command, the driver reads the
heater from Rheem. That picks up changes the heater doesn't always report on its own,
such as the schedule's setpoint after `resumeSchedule`, and logs a warning if a
command didn't take (for example, away mode
on a heater that doesn't have it enabled). Attributes follow the heater's own
reports, not the commands sent to it. Cooling and fan commands from the Thermostat
capability log a warning. A command sent while disconnected goes out when the
connection is back, if that's within 15 minutes.

## Tested on

Verified on my heater, a gas heater with on/off (no modes), on 2026-11-23:

- Connects on Initialize, `healthStatus` online, and the full reading arrives
  through the app (firmware, alerts, away, vacation, schedule status, Wi-Fi).
- Setpoint reads as a number. `setHeatingSetpoint` 121, then 120: the heater
  reported each within 3 seconds, and the check 15 seconds later agreed.
- Heat/off reads correctly (`heat`, `ON`), and pressing Done no longer flips it to off.
- Refresh reads the heater without logging in. Opening the settings page logs in
  once; pressing Done doesn't log in again.
- Energy and water use: Rheem answers that this heater doesn't report them, so
  `energyToday` and `waterToday` stay empty (checked again daily).
- Schedules: with a schedule set up in Rheem's app, `scheduleStatus` reads
  `Following Schedule`. A setpoint change from Hubitat makes it `Schedule
  overridden Until <time>`. `resumeSchedule` went back to the schedule and its
  setpoint within 2 seconds, with no confirmation prompt.
- Away mode: Rheem's app offers only the setpoint and its schedule for this
  heater, with no away mode, and the heater reports no `@AWAYCONFIG`. `setAway on`
  isn't taken: `away` stays `off`, and the driver logs "didn't take away on (it
  reports off)".
- Power: `off` isn't taken by this heater, whether sent as `0` or `false`. Rheem
  keeps reporting it on, its app offers no on/off for it, and the driver logs
  "didn't take power off (it reports on)". Heaters whose app has on/off should take it.
- Not tried on hardware: the away-event confirmation prompt, and recovery from a
  real outage.

Heat pump modes, hot water, tank and compressor health, and the shut-off valve
are tested only against sample data shaped like pyeconet's.

## Notes

- Debug logging (app and driver) turns itself off after 30 minutes.
- The driver's descriptionText logging covers attribute changes. Turn it off on the device page.
- Rheem's cloud is the only path: there's no local API.
