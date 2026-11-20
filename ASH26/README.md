# Amcrest ASH26

LAN driver for the Amcrest ASH26 outdoor security camera. Controls the
floodlight and surfaces device info that's otherwise only available through
the Amcrest mobile app.

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
| Enable debug logging | no | Verbose logging; turns itself off after 30 minutes |
| Enable descriptionText logging | no (default on) | Logs one line when the light, light mode or health changes |

## Attributes

`switch` (on/off — floodlight state), `light_status` (legacy duplicate of
switch), `lightMode` (the camera's light mode: `Manual` = on, `Off`, or
another mode such as motion-triggered, which leaves `switch` unchanged),
`healthStatus` (online/offline), `device_name`, `serial_number`,
`firmware_version`, `mac_address`, `wireless_ssid`

## Commands

`On`, `Off`, `Refresh` and `Initialize`. On and Off update `switch` as soon
as the camera confirms the change.

## Notes

- Polls the camera once a minute for current state.
- **Connection problems** are logged once, not every minute: an error that
  says whether the camera isn't answering, rejected the login, or answered
  with an error, a reminder at most hourly, and an info line with the
  outage length when it recovers.
- Only the day lighting profile (`Lighting_V2[0][0][1]`) is read and
  written.
- Uses RFC 2617-compliant HTTP digest auth.
- The `on()` and `off()` commands fire two sequential `setConfig` calls
  (toggle AlarmLighting, then set Mode). A failure mid-chain is logged
  explicitly so a half-configured camera state can't go unnoticed.
- Hub firmware floor: 2.2.9 (the driver runs single-threaded, an option
  added in 2.2.9).
