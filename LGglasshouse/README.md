# LG TV (Glasshouse)

LAN driver for a rooted LG webOS TV running
[Glasshouse](https://github.com/rorygallagher2024/glasshouse). It talks
to Glasshouse's local HTTP API (typically on port 8080, with no
authentication in the default configuration).

Every control is a command, so Rule Machine, dashboards, HomeKit, voice
assistants and Maker API can all use the TV. It is a standard `Switch`,
`AudioVolume` and `MediaInputSource` device, with extra commands for
picture, sound and power settings.

## Requirements

- A rooted LG webOS TV with [Glasshouse](https://github.com/rorygallagher2024/glasshouse)
  installed and reachable on the LAN (default HTTP port 8080)
- The Hubitat hub must be able to reach the TV's IP. This is a local API;
  there is no cloud account
- Wake-on-LAN from deep sleep needs the TV's MAC address, and Wake-on-LAN
  enabled on the TV. The driver prefills the MAC from the TV once it has
  been reached
- **Reboot** needs `allowPower` turned on in Glasshouse's config

Glasshouse itself is documented at the project page above. This driver
does not install or configure Glasshouse.

## Install

In **Hubitat Package Manager → Install**, search for `LG TV (Glasshouse)`.

To install the drivers directly without HPM, open **Drivers code → New
driver → Import** and import each of these:

```
https://raw.githubusercontent.com/dlaporte/Hubitat/main/LGglasshouse/device/LGTV-Glasshouse.groovy
https://raw.githubusercontent.com/dlaporte/Hubitat/main/LGglasshouse/device/LGTV-Glasshouse-Notifier.groovy
```

The second one, LG TV (Glasshouse) Notifier, is only used by the
optional [notification device](#notifications).

Then create a device:

1. **Devices → Add Device → Virtual**
2. **Type:** LG TV (Glasshouse)
3. Open the device, fill in **TV IP** (and **Port** if Glasshouse is not
   on 8080), then Save
4. Click **Initialize**
5. After the first refresh, `supportedInputs` and `supportedPictureModes`
   list the TV's inputs and picture modes. Set the MAC if you want
   Wake-on-LAN from deep sleep.

### Upgrading from 0.11 or earlier

The TV controls (power, volume, mute, input, picture mode, screen, launch
app and send key) moved from the Preferences page to commands. Click
**Initialize** once after updating; it removes the old settings. Rules
that read the attributes keep working.

## Features

- **Power** — On sends a Wake-on-LAN magic packet and Glasshouse's
  `powerOn` action (works in Active Standby while Glasshouse is still
  running). Off sends `powerOff`. A TV that is fully off takes a while to
  boot, so after On (and Reboot) the driver checks every 15 seconds for up
  to 2 minutes, and `switch` turns on within about 15 seconds of the TV
  answering.
- **Volume** — `setVolume` sets the TV volume. When audio goes over HDMI
  ARC/eARC, a soundbar or receiver may ignore the numeric volume and only
  follow volume up/down steps. In that case the driver sends `volumeStep`
  commands (the same + / − buttons on the Glasshouse dashboard), in a
  chain of at most 30 steps for a large jump. `volumeUp` and `volumeDown`
  send one step. The `volume` attribute updates only after the TV
  confirms it.
- **Input** — `setInputSource` takes the input's id (`hdmi1`) or its label
  (`Apple TV`), ignoring case.
- **Picture and sound** — picture mode, backlight, energy saving, sound
  output and sleep timer commands, plus `setLgSetting` for any LG setting
  Glasshouse exposes (Filmmaker Mode, Eye Comfort, sound mode, game
  options and others).
- **Screen on/off** — turns only the panel off; the TV stays powered.
- **Apps and keys** — launch an app by id or title; send the navigation
  and media keys Glasshouse accepts.
- **Readings** — the TV's ambient light sensor (`illuminance`), the Magic
  Remote's battery (`battery`), dynamic range, backlight, energy saving,
  sound output and mode, sleep timer, power state and the TV's own
  temperature.
- **Off when unreachable** — a TV that drops off the network is reported
  as `switch` and `screen` off, with `healthStatus` offline.
- **Polling** — configurable interval (off, or 1–30 minutes; default every
  1 minute).
- **Notifications** — an optional child device that shows Hubitat
  notifications on the TV screen. See [Notifications](#notifications).

## Preferences

| Section | Settings |
|---|---|
| Connection | TV IP, Port, API token (only if Glasshouse has one set), MAC for Wake-on-LAN, Poll interval, Warn when the TV can't be reached |
| Hubitat | Notification device, Enable debug logging (turns itself off after 30 minutes), Enable descriptionText logging (plus Hubitat's own Show on Home page) |

Saving preferences never sends anything to the TV.

## Commands

| Command | Parameter | What it does |
|---|---|---|
| `on`, `off` | — | Power on (Wake-on-LAN plus `powerOn`) or off |
| `setVolume` | 0–100 | Sets the volume (volume steps on HDMI ARC) |
| `volumeUp`, `volumeDown` | — | One volume step |
| `mute`, `unmute` | — | Mute or unmute |
| `setInputSource` | input id or label | Switches input, for example `hdmi1` or `Apple TV` |
| `setPictureMode` | mode | A value or label from `supportedPictureModes`. The modes on offer follow what is playing (for example Dolby Vision vs SDR) |
| `screenOn`, `screenOff` | — | Panel on or off; the TV stays powered |
| `launchApp` | app id or title | For example `netflix` |
| `sendKey` | key | `up`, `down`, `left`, `right`, `ok`, `back`, `home`, `settings`, and the media keys play/pause, play, pause, stop, rewind, fast forward, channel up and channel down |
| `setBacklight` | 0–100 | Backlight, or OLED Light on an OLED, for the current picture mode |
| `setEnergySaving` | `auto`, `off`, `min`, `med`, `max`, `screen_off` | Energy saving step. Some TVs show a confirmation prompt on screen for a step other than Auto |
| `setSoundOutput` | output name | The names Glasshouse shows, the same as the `soundOutput` attribute (TV Speaker, HDMI ARC, Optical, Bluetooth, Headphone / AUX, …). The driver uses the TV's own list of outputs |
| `setSleepTimer` | `off`, `10`, `30`, `60`, `90`, `120` | Minutes until the TV turns off |
| `setLgSetting` | setting id, value | Any setting on Glasshouse's Settings tab, by its id. Use `on` or `off` for a switch setting (`filmMakerMode on`), a number for a number setting (`contrast 85`), and the value's name for a choice (`soundMode movie`) |
| `reboot` | — | Restarts the TV. Needs `allowPower` in Glasshouse's config |
| `refresh` | — | Reads the TV's state now |
| `initialize` | — | Restarts polling; click it after updating the driver |

A value a command doesn't accept (backlight 150, an unknown input or
key, a blank setting) is logged as a warning and not sent. When
Glasshouse refuses a command, the warning includes its reason.

## Attributes

| Attribute | Values |
|---|---|
| `switch` | `on` / `off`. Off also while the TV can't be reached |
| `volume` | 0–100 |
| `mute` | `muted` / `unmuted` |
| `input` | The current input or app key, for example `hdmi1` |
| `mediaInputSource` | Same value as `input` |
| `supportedInputs` | JSON list of `{id, label}` |
| `currentApp` | The title of what is on screen |
| `pictureMode` | The current picture mode's label |
| `supportedPictureModes` | JSON list of `{value, label}` |
| `screen` | `on` / `off` |
| `healthStatus` | `offline` while the TV can't be reached (normal when it is switched fully off), `online` when it answers |
| `illuminance` | Lux from the TV's ambient light sensor. Not set on TVs without one |
| `battery` | The Magic Remote's battery, % |
| `dynamicRange` | SDR, HDR10, Dolby Vision… |
| `backlight` | 0–100 |
| `energySaving` | `auto`, `off`, `min`, `med`, `max` or `screen_off` |
| `soundOutput` | The output's name, for example `HDMI ARC` |
| `soundMode` | The sound mode's id, for example `standard` or `movie` (the value `setLgSetting soundMode` takes) |
| `sleepTimer` | `off` or minutes |
| `powerStateLabel` | On, Screen off, Screen Saver, Always Ready, Standby… |
| `tvTemperature` | °C, the TV's own chip temperature (not a room temperature). Changes aren't logged |

## Notifications

To show Hubitat notifications on the TV, turn on **Notification device**
in the Hubitat section of the TV's Preferences page and click **Save
Preferences**. This creates a child device named `<TV name> Notifier`
(you can rename it). It is a standard Hubitat notification device, so
Rule Machine, Notifier, Basic Rules, and HSM can send messages to it.

- Each message appears as LG's standard toast: a bar at the bottom
  centre of the screen, text only, which fades after a few seconds.
- The TV shows at most 120 characters. Longer messages are cut off and
  a warning is logged. Blank messages are not sent.
- Nothing is queued. If the TV is in standby, the message is accepted
  but not shown. If the TV is fully off, sending fails and a warning is
  logged.
- Turning **Notification device** off and saving deletes the child
  device, and any rules that use it lose it.

The Notifier has no connection settings of its own; the TV device sends
each message to Glasshouse.

## Notes

- Glasshouse's HTTP API is unauthenticated by default. Anyone who can
  reach port 8080 can control the TV, so keep it on a trusted LAN. If you
  set a token in Glasshouse's config, enter it as **API token**; without
  it every request gets a 401.
- A TV that is switched fully off drops off the network. The driver then
  sets `switch` and `screen` to off and `healthStatus` to offline, and logs
  one info line when the TV goes and one when it is back, not repeated
  warnings. Commands sent while it can't be reached are logged the same
  way. If Always Ready is on in Glasshouse, the TV stays on the network
  when switched off with the remote; then turn on **Warn when the TV
  can't be reached** to get a warning on the first miss and every 10th.
  The recovery line says how long the TV was unreachable. The power
  button still switches the TV fully off. Other failures, where the TV
  answers with an error, are always warnings.
- After a command the driver reads the TV again 2 seconds and 45 seconds
  later. The TV sometimes reports the old value for a few seconds after a
  change, so after mute, screen, input and picture mode commands the
  driver keeps the value it set for up to 15 seconds unless the TV agrees
  sooner.
- The driver runs single-threaded (an option added in Hubitat 2.2.9), so
  the poll callbacks can't race on shared state. Hub firmware floor:
  **2.2.9**.
