# Reolink Floodlight Camera

LAN driver for Reolink cameras with a built-in WhiteLed floodlight.

## Supported hardware

Tested live against:

| Model | Item # |
|---|---|
| Elite Pro Floodlight PoE | F760P |
| Elite Floodlight WiFi | F751W |
| Duo 3 PoE | P750 |

Should work on any Reolink camera with a WhiteLed (Duo Floodlight WiFi/PoE,
etc.). Feature exposure is gated on the camera's `GetAbility` permits, not
on the model string, so new models with the same API surface should "just
work."

## Setup

Just the camera's username and password. No cloud account or third-party
setup required. The driver talks to the camera over HTTP on your LAN.

If you have multiple Reolink cameras, create a separate device per camera.

## Install

In **Hubitat Package Manager → Install**, search for `Reolink Floodlight Camera`.

Then create a device:
1. **Devices → Add Device → Virtual**
2. **Type:** Reolink Floodlight Camera
3. Open the device, fill in the preferences (IP, username, password), Save
4. Click **Initialize**

## Preferences

| Name | Default | What it does |
|---|---|---|
| IP address | required | The camera's LAN IP |
| Username | required | Local camera account |
| Password | required | Local camera password |
| Motion/AI poll interval | 5 sec | How often to poll for motion / AI events |
| After On, keep the floodlight on until Off | on | Only after Hubitat turns the light on (On or Set Level): sends On again every 2 minutes until Hubitat turns it off. For cameras whose firmware turns a manually switched-on light off by itself; it has no effect on cameras with Reolink's own keep-on. While it's on, a light switched off in the Reolink app comes back on within 2 minutes, because Hubitat still thinks it's on. Turn it off from Hubitat |
| Publish snapshotURL attribute | **off** | When on, exposes a `Snap` URL with embedded credentials — convenient for image tiles but leaks the password into event history / hub backups. |
| Enable debug logging | off | Verbose logging; turns itself off after 30 minutes |
| Enable descriptionText logging | on | Logs one line when a user-facing attribute changes (light, motion, AI detections, modes, health) |

## Attributes

**Device info:** `model`, `deviceName`, `firmwareVersion`, `hardwareVersion`,
`serial`, `mac`, `cameraIP`

**Floodlight:** `switch` (on/off), `level` (0–100 brightness),
`floodlightMode` (Off / NightSmart / AlwaysAtNight / Schedule),
`floodlightSmartTriggers` (which AI detections flash the light at night,
e.g. `people,vehicle,dog_cat` or `none`), `lightingSchedule`

**Other camera config:** `IRMode` (Auto/Off), `dayNightMode` (Auto / Color /
Black&White), `microphone` (On/Off), `powerLED` (On/Off)

**Detection:** `motion`, plus AI attributes `personDetected`, `vehicleDetected`,
`animalDetected`, `faceDetected` — only emitted when the camera reports
support for each AI type.

**Streaming:** `rtspMainStream`, `rtspSubStream`, optional `snapshotURL`

**Storage:** `sdCardCapacityMB`, `sdCardFreeMB`, `sdCardMounted`

**Health:** `healthStatus` (online/offline): offline while the camera isn't
answering or is rejecting the login, online again on the first good reply.

## Commands

Standard `On`, `Off`, `Set Level (level, duration)` from the Switch /
SwitchLevel capabilities, `Initialize` and `Refresh`, plus:

- `Set Brightness (level)` — friendlier alias for Set Level
- `Set Floodlight Mode (mode)` — Off / NightSmart / AlwaysAtNight / Schedule.
  Picking **Off** turns the light off NOW and clears auto-mode in one call;
  the Reolink app can't reach this state. On newer firmware the per-AI smart
  triggers below survive this and keep flashing the light at night — the
  driver logs a warning when that's the case.
- `Set Floodlight Smart Triggers (people, vehicle, animal)` — arm/disarm the
  camera's per-AI floodlight triggers (`wlAiDetectType`). These are separate
  from the mode AND from detection itself: disarming a trigger stops the light
  from flashing on that detection, but the detection attributes/notifications
  keep working. **Firmware limit:** the camera refuses to disarm *all*
  triggers over the LAN API (it silently re-arms every one); the driver
  rejects all-Off with an explanation. Leave one armed (vehicle is usually
  quietest) or use the Reolink app to reach fully off.
- `Set Lighting Schedule (startTime, endTime)` — HH:mm format
- `Set IR Mode (mode)` — Auto / Off
- `Set Day Night Mode (mode)` — Auto / Color / Black&White
- `Set Microphone (state)` — On / Off
- `Set Power LED (state)` — On / Off (only on models with a controllable status LED)

## Notes

- **Refresh:** device info, SD card, IR, day/night, microphone and LED
  refresh every 30 minutes on their own. The timed refresh leaves the
  floodlight's `switch`/`level` alone (see the keepAlive note in the code);
  click **Refresh** to re-read those. Motion, AI detections, floodlight mode
  and smart triggers are polled every few seconds.
- **After a camera reboot** the driver logs in again on the first rejected
  request. It never runs more than one login at a time, since the camera
  limits concurrent sessions, and it logs out when you click
  **Initialize** or save preferences.

- **"The light still comes on at night with mode Off":** newer firmware arms
  per-AI floodlight triggers (person/vehicle/animal) independently of the
  floodlight mode. Check the `floodlightSmartTriggers` attribute — if it isn't
  `none`, the camera itself flashes the light on those detections at night.
  Use `Set Floodlight Smart Triggers` to trim them (see the firmware limit
  above).
- **Connection problems** are logged once, not on every poll: an ERROR
  that says whether the camera *isn't answering* (offline, off WiFi, wrong
  IP — a network problem) or *rejected the login* (check username/password),
  a reminder at most hourly while it persists, and an INFO line with the
  outage length when the camera comes back.
- **No siren / alarm-trigger commands** are exposed by design. The
  `AudioAlarmPlay` API is intentionally not surfaced.
- HTTP is used on the LAN; HTTPS works but most cameras ship with
  self-signed certs and the driver doesn't currently handle them.
- Hub firmware floor: **2.2.9** (the driver runs single-threaded, an option
  added in 2.2.9).
