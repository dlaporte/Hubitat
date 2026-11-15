# Hubitat drivers and apps

A collection of [Hubitat Elevation](https://hubitat.com) drivers and apps
for hardware and services I run at home. All packages are available through
[Hubitat Package Manager](https://community.hubitat.com/t/release-hubitat-package-manager/38016) (HPM).

## Installation

In **Hubitat Package Manager → Install**, search by the package name
below. This repo is already registered in HPM's master list.

To install a single driver directly without HPM, open **Drivers code → New
driver → Import** and paste the file's raw URL.

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

### LG TV (Glasshouse)

LAN driver for a rooted LG webOS TV running
[Glasshouse](https://github.com/rorygallagher2024/glasshouse). Talks to
Glasshouse's local HTTP API (typically port 8080, no authentication in
the default configuration).

Exposes, on the Preferences page:
- Power (Wake-on-LAN plus Glasshouse `powerOn` / `powerOff`)
- Volume slider, including HDMI ARC/eARC `volumeStep` behavior for a
  soundbar or receiver that ignores the numeric volume
- Mute, input and picture-mode dropdowns populated from the TV, screen
  on/off, launch app, and send key
- Configurable polling
- Optional notification device that shows Hubitat notifications on the
  TV screen

The Commands tab is intentionally just Initialize and Refresh. The
driver does not declare `Switch` or `AudioVolume`, so dashboards,
HomeKit, and Rule Machine cannot treat it as a switch.

## Forks (not in HPM)

### Xiaomi Vacuums (Mi Connector)

Fork of fison67's [Mi Connector](https://github.com/fison67/mi_connector)
**Xiaomi Vacuums** child driver, as ported to Hubitat. It keeps the original
name and namespace (`fison67` / `Xiaomi Vacuums`) so Mi Connector keeps
using it and existing vacuum devices need no driver change. To install,
open **Drivers code → Xiaomi Vacuums → Import** and paste the raw URL of
`XiaomiVacuums/device/xiaomi-vacuums.groovy`.

Changes from upstream:
- **Logging** preference (`off` / `error` / `debug`, default `error`) that
  all of the driver's log output honors. Previously every status push and
  HTTP response was logged at debug, unconditionally.
- Removed the call to an undefined `updateLastTime()` that made every
  refresh log a `MissingMethodException`.

Kept here in case upstream disappears (upstream is unmaintained since 2022):
- `XiaomiVacuums/app/mi-connector.groovy`: the **Mi Connector** parent app
  (v0.0.75) as installed on Hubitat, unmodified. It isn't in either upstream
  branch; upstream's `hubitat` branch is the older v0.0.34.
- A fork of the upstream repo, both branches:
  [dlaporte/mi_connector](https://github.com/dlaporte/mi_connector).
- The Mi Connector **server** image, which has no published source:
  `ghcr.io/dlaporte/mi-connector:0.0.7` (also tagged `latest`; private). It is
  the linux/amd64 image of `fison67/mi-connector:0.0.7`, image ID
  `sha256:b9c5b7b6ad37…`. To use it, run `docker login ghcr.io` with a token
  that has `read:packages`, then point the compose service's `image:` at it.

MIT license (fison67) — see the file headers.

## License

Apache 2.0 — see individual file headers, except the Xiaomi Vacuums fork
(MIT).
