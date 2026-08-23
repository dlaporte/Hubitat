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
| IP Address | required | The camera's LAN IP |
| Username | required | Local camera account |
| Password | required | Local camera password |
| Motion/AI poll interval | 5 sec | How often to poll for motion / AI events |
| Keep floodlight on indefinitely | on | Re-asserts state every 2 min (firmware auto-off workaround on models without native keep-on) |
| Publish snapshotURL attribute | **off** | When on, exposes a `Snap` URL with embedded credentials — convenient for image tiles but leaks the password into event history / hub backups. |
| Debug logging | off | Verbose logging for ~30 min |

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

## Commands

Standard `On`, `Off`, `Set Level (level, duration)` from the Switch /
SwitchLevel capabilities, plus:

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

- **"The light still comes on at night with mode Off":** newer firmware arms
  per-AI floodlight triggers (person/vehicle/animal) independently of the
  floodlight mode. Check the `floodlightSmartTriggers` attribute — if it isn't
  `none`, the camera itself flashes the light on those detections at night.
  Use `Set Floodlight Smart Triggers` to trim them (see the firmware limit
  above).
- **No siren / alarm-trigger commands** are exposed by design. The
  `AudioAlarmPlay` API is intentionally not surfaced.
- HTTP is used on the LAN; HTTPS works but most cameras ship with
  self-signed certs and the driver doesn't currently handle them.
- Hub firmware floor: **2.2.0** (uses `device.deleteCurrentState` for
  attribute cleanup on upgrade).
