# Amcrest ASH26

LAN driver for the Amcrest ASH26 outdoor security camera. Controls the
floodlight and its settings, reports motion from the camera's PIR sensors,
and surfaces device info that's otherwise only available through the
Amcrest mobile app.

## Supported hardware

The Amcrest ASH26, and likely other Amcrest cameras that use the same
`/cgi-bin/configManager.cgi` CGI API with HTTP digest auth.

## Setup

Just the camera's username and password. No cloud account.

## Install

In **Hubitat Package Manager → Install**, search for `Amcrest ASH26`.

Then:
1. **Devices → Add Device → Virtual**
2. **Type:** Amcrest ASH26
3. Open the device, fill in the preferences (IP, username, password), Save
4. Click **Initialize**

## Preferences

| Name | Required | What it does |
|---|---|---|
| IP address | yes | The camera's LAN IP |
| ASH26 username | yes | Camera login |
| ASH26 password | yes | Camera password |
| Motion inactive after (seconds) | no (default 30) | How long after the camera's PIR sensors all stop before `motion` goes inactive (0–600) |
| Enable debug logging | no | Verbose logging; turns itself off after 30 minutes |
| Enable descriptionText logging | no (default on) | Logs one line when the light, light mode, health, motion or a camera setting changes |

## Attributes

`switch` (on/off — floodlight state), `light_status` (legacy duplicate of
switch), `lightMode` (the camera's light mode: `Manual` = on, `Off`, or
another mode such as motion-triggered, which leaves `switch` unchanged),
`motion` (active/inactive, from the camera's PIR sensors),
`motionLightDuration` (seconds the light stays on after motion),
`nightVision` (`Auto`, `On` or `Off`), `motionDetection` (on/off — the
camera's video motion detection), `healthStatus` (online/offline),
`device_name`, `serial_number`, `firmware_version`, `mac_address`,
`wireless_ssid`

## Commands

- `On`, `Off`, `Refresh` and `Initialize`. On and Off update `switch` as
  soon as the camera confirms the change.
- **Set Motion Light Duration** (1–3600 seconds): how long the light stays
  on after motion, written to all three PIR sensors.
- **Set Night Vision** (`Auto`, `On`, `Off`): the camera's IR night vision,
  as in the Amcrest app.
- **Set Motion Detection** (on/off): the camera's video motion detection,
  the Amcrest app's Motion Detection switch. It doesn't affect `motion`,
  which comes from the PIR sensors.
- **Reboot:** restarts the camera. It's back in a minute or two; for 5
  minutes, connection failures aren't logged as errors, and one line says
  when it's back.

## Notes

- Polls the camera once a minute for current state.
- **Motion** comes from the camera's three PIR sensors, pushed over its
  event stream (`eventManager.cgi?action=attach`), so it updates within a
  second. `motion` stays active while any sensor sees motion, and goes
  inactive after the "Motion inactive after" time. After updating the
  driver, click **Initialize** to start the stream.
- **If the stream drops,** one warning is logged and the driver
  reconnects (after 5 s, then 15 s, 1 min and every 5 min), with a
  reminder at most hourly and an info line when motion events are back. A
  stream that sends nothing for a minute (the camera sends a heartbeat
  every 5 s) is treated as dropped. While the camera rejects the login,
  the stream isn't opened, because the hub retries a rejected stream login
  many times in a row.
- **HomeKit:** HomeKit Bridge may export the floodlight as a Motion
  Sensor, so the Home app shows a sensor instead of the light. In HomeKit
  Bridge, find the device and click **Switch** next to **Export as:**. The
  motion sensor stays, as an extra on the same accessory. If the device
  was in HomeKit before 0.9.0, its motion sensor may not update until you
  remove the device from HomeKit Bridge and add it back (Restart
  integration wasn't enough in testing). Removing it also removes it from
  any Home scenes and automations.
- **Brightness isn't adjustable.** The camera accepts a brightness
  setting, but the light doesn't change (tested on an ASH26 in 2026).
- **Connection problems** are logged once, not every minute: an error that
  says whether the camera isn't answering, rejected the login, or answered
  with an error, a reminder at most hourly, and an info line with the
  outage length when it recovers.
- The floodlight's On and Off use only the day lighting profile
  (`Lighting_V2[0][0][1]`).
- Uses RFC 2617-compliant HTTP digest auth.
- The `on()` and `off()` commands fire two sequential `setConfig` calls
  (toggle AlarmLighting, then set Mode). A failure mid-chain is logged
  explicitly so a half-configured camera state can't go unnoticed.
- Hub firmware floor: 2.2.9 (the driver runs single-threaded, an option
  added in 2.2.9).
