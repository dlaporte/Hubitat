# Hubitat drivers and apps

A collection of [Hubitat Elevation](https://hubitat.com) drivers and apps
for hardware and services I run at home. All packages are available through
[Hubitat Package Manager](https://community.hubitat.com/t/release-hubitat-package-manager/38016) (HPM).

## Installation via HPM

1. Open **Hubitat Package Manager** → **Settings → Modify list of repositories**
2. Add `https://raw.githubusercontent.com/dlaporte/Hubitat/main/repository.json`
3. **HPM → Install** → search by package name below

To install a single driver directly without HPM, open **Drivers code → New driver → Import**
and paste the file's raw URL.

## Packages

### Reolink Floodlight Camera

LAN driver for Reolink cameras with a WhiteLed (floodlight). Tested against
**Elite Pro Floodlight PoE (F760P)**, **Elite Floodlight WiFi (F751W)**, and
**Duo 3 PoE (P750)**; should work on any Reolink WhiteLed model since
features are gated on the camera's `GetAbility` permits, not the model
string.

Exposes:
- `Switch` (floodlight on/off) and `SwitchLevel` (brightness 0–100)
- `MotionSensor` (motion / no motion) + custom AI attributes
  (`personDetected`, `vehicleDetected`, `animalDetected`, `faceDetected` —
  only emitted when the camera reports support for each type)
- Floodlight mode (`Off`, `NightSmart`, `AlwaysAtNight`, `Schedule`),
  lighting schedule, IR mode, day/night mode, microphone toggle, status LED
- RTSP main/sub stream URLs and an optional snapshot URL for dashboard tiles
- SD-card capacity / free / mounted attributes

Async HTTP throughout; 5-second poll loop for motion/AI with epoch-guarded
self-rescheduling. Does **not** expose any siren or alarm-trigger commands
by design.

### Amcrest ASH26

LAN driver for the Amcrest ASH26 outdoor security camera (and likely
similar Amcrest CGI-API cameras). Controls the floodlight and surfaces
device info that's otherwise only available in the Amcrest mobile app.

Async HTTP with RFC 2617-compliant digest auth. 1-minute refresh cadence.

### AcuRite Weather Station

Cloud driver for AcuRite sensors via [myacurite.com](https://www.myacurite.com).
Polls the dashboard API on a user-configurable interval (default 5 minutes)
and publishes temperature, humidity, pressure, illuminance, UV, rainfall,
wind, dew point, lightning strikes, and more.

Derived attributes:
- `lightningActive` — `true` if a strike was detected within the configured
  window (default 10 minutes). One-step Rule Machine trigger for storm rules.
- `weatherSummary` — composed string like `"72°F, 45% RH, wind SW 8 mph"`
  for dashboard tiles and TTS.

Session token cached across polls (~95% reduction in login API calls vs.
the original).

### Smart Oil Gauge

App + child device for [Smart Oil Gauge](https://www.smartoilgauge.com) /
Droplet Fuel API. Polls hourly and creates one child device per tank.

Per-tank attributes:
- `level` (%), `gallons`, `capacity`, `lastreading`, `battery`,
  `accountNumber`
- `usageRate` (gal/day, smoothed over a configurable window)
- `daysRemaining` (gallons / usageRate)
- `lowFuel` (`true` / `false` vs. a configurable threshold)
- `lastRefillAt` / `lastRefillGallons` (refills auto-detected from gallons
  jumping above the threshold)

Includes an OAuth-served web tile page (`/deviceTiles` and `/getTile/<dni>`)
with an inline-SVG tank visualization — zero external assets except the
Droplet API itself.

### Radon Fan Sensor

Z-Wave driver for a [Monoprice 15270 / WADWAZ-1](https://www.monoprice.com)
door sensor wired to a pressure switch on a radon mitigation fan.
The driver presents the fan as a `ContactSensor` (closed = running,
open = stopped) with timestamps and an alarm attribute:

- `fanAlarm` becomes `"alarm"` if the fan has been off longer than the
  configured `alarmAfterMinutes` (default 30). Survives hub reboots —
  initialize() re-arms the timer with the remaining window.
- `lastStartedAt` / `lastStoppedAt` ISO timestamps for use in Rule Machine.

The driver also keeps the legacy `switch` attribute (`on`/`off`) for
backward compatibility with any rules that already use it.

Parts list, install instructions, and theory of operation are in the driver
header.

## License

Apache 2.0 — see individual file headers.
