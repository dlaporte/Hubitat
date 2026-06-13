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
| IP Address | yes | The camera's LAN IP |
| ASH26 Username | yes | Camera login |
| ASH26 Password | yes | Camera password |
| Debug Logging | no | Verbose logging |

## Attributes

`switch` (on/off — floodlight state), `light_status` (legacy duplicate of
switch), `device_name`, `serial_number`, `firmware_version`, `mac_address`,
`wireless_ssid`

## Commands

`On`, `Off`, `Refresh` from the Switch + Refresh capabilities.

## Notes

- Polls the camera once a minute for current state.
- Uses RFC 2617-compliant HTTP digest auth.
- The `on()` and `off()` commands fire two sequential `setConfig` calls
  (toggle AlarmLighting, then set Mode). A failure mid-chain is logged
  explicitly so a half-configured camera state can't go unnoticed.
- Hub firmware floor: 2.1.9.
