# LG TV (Glasshouse)

LAN driver for a rooted LG webOS TV running
[Glasshouse](https://github.com/rorygallagher2024/glasshouse). It talks
to Glasshouse's local HTTP API (typically on port 8080, with no
authentication in the default configuration) and exposes power, volume,
mute, input, picture mode, screen, app launch, and remote-key controls
on the device's Preferences page.

## Requirements

- A rooted LG webOS TV with [Glasshouse](https://github.com/rorygallagher2024/glasshouse)
  installed and reachable on the LAN (default HTTP port 8080)
- The Hubitat hub must be able to reach the TV's IP. This is a local API;
  there is no cloud account
- Wake-on-LAN from deep sleep needs the TV's MAC address, and Wake-on-LAN
  enabled on the TV. The driver prefills the MAC from the TV once it has
  been reached

Glasshouse itself is documented at the project page above. This driver
does not install or configure Glasshouse.

## Install

In **Hubitat Package Manager → Install**, search for `LG TV (Glasshouse)`.

To install the driver directly without HPM, open **Drivers code → New
driver → Import** and paste:

```
https://raw.githubusercontent.com/dlaporte/Hubitat/main/LGglasshouse/device/LGTV-Glasshouse.groovy
```

Then create a device:

1. **Devices → Add Device → Virtual**
2. **Type:** LG TV (Glasshouse)
3. Open the device, fill in **TV IP** (and **Port** if Glasshouse is not
   on 8080), then Save
4. Click **Initialize**
5. After the first refresh, the Input, Picture mode, Launch app, and Send
   key lists are filled from the TV. Set the MAC if you want Wake-on-LAN
   from deep sleep.

## Features

- **Power** — On sends a Wake-on-LAN magic packet and Glasshouse's
  `powerOn` action (works in Active Standby while Glasshouse is still
  running). Off sends `powerOff`.
- **Volume slider** — Sets the TV volume. When audio goes over HDMI
  ARC/eARC, a soundbar or receiver may ignore the numeric volume and only
  follow volume up/down steps. In that case the driver sends `volumeStep`
  commands (the same + / − buttons on the Glasshouse dashboard), in a
  chain of at most 30 steps for a large jump. The hub's volume number
  updates only after the TV confirms it.
- **Mute** — Applied on Save only if it differs from the TV.
- **Input and picture mode** — Dropdowns populated from the TV
  (`/api/hdmi` and `/api/picture`). Picture-mode choices follow the
  content that is playing (for example Dolby Vision vs SDR).
- **Screen on/off** — Turns only the panel off; the TV stays powered.
- **Launch app** and **Send key** — Dropdowns filled from the apps and
  remote keys reported by the TV. Both reset after use so the same choice
  can be sent again.
- **Polling** — Configurable interval (off, or 1–30 minutes; default every
  1 minute).

## Preferences

The device page is grouped into sections:

| Section | Controls |
|---|---|
| Connection | TV IP, Port, MAC for Wake-on-LAN, Poll interval |
| TV | Power toggle, Launch app, Send key |
| Sound | Volume slider, Mute |
| Picture | Input, Picture mode, Screen on |
| Hubitat | Enable debug logging (plus Hubitat's own Show on Home page) |

Most preference actions (power, mute, input, picture mode, screen) are
applied on **Save Preferences** only when the value differs from the TV.
Launch app and Send key run on Save when a choice is selected. The volume
slider is sent when you release it.

## Commands

The Commands tab is intentionally just **Initialize** and **Refresh**.
Power, volume, mute, input, picture mode, screen, launch app, and send
key are all on the Preferences page.

Because the driver does not declare the `Switch` or `AudioVolume`
capabilities (those would put On, Off, and volume commands back on the
Commands tab), it is not a Switch/AudioVolume device. Hubitat dashboards,
HomeKit, and Rule Machine cannot treat it as a switch. The `switch`,
`volume`, and `mute` attributes are still updated, so they can be read
in rules.

## Notes

- Glasshouse's HTTP API is unauthenticated by default. Anyone who can
  reach port 8080 can control the TV, so keep it on a trusted LAN.
- Don't switch Power off and Save while you're watching; that turns the
  TV off.
- Hub firmware floor: **2.2.0**.
