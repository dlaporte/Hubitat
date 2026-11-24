# BOND Home Integration (David's fork)

A fork of Dominick Meglio's [BOND Home Integration](https://github.com/dcmeglio/hubitat-bond)
(app and ten drivers, 1.4.0, unmaintained since 2021), fixed for the cabin's ceiling fan.
Credit for the original goes to Dominick.

**Status:** personal. Upstream has no license, so this fork isn't offered through HPM and
keeps Dominick's copyright. History: [dlaporte/hubitat-bond](https://github.com/dlaporte/hubitat-bond)
is an untouched fork of upstream.

## Install (over an existing 1.4.0 install)

The app and drivers keep upstream's names and namespaces (`dcm.bond`, `bond`), so this is an
in-place update. Devices, rules and dashboards keep working.

1. **Apps code → BOND Home Integration → Import:**
   `https://raw.githubusercontent.com/dlaporte/Hubitat/main/BondHome/app/BOND_Home_Integration.groovy`.
   Save. Update the app first: the new app works with the old drivers.
2. For each **BOND …** driver in **Drivers code**, **Import** its file from
   `https://raw.githubusercontent.com/dlaporte/Hubitat/main/BondHome/device/` and Save.
3. Open **Apps → BOND Home Integration**, click **Next**, **Next**, **Done**, once.
4. If HPM installed 1.4.0, open **HPM → Match Up** and **Un-Match** the BOND package. Don't
   **Uninstall** it: that deletes the code and the devices.

## What changed from 1.4.0

- **Done:**
  - finds existing lights instead of trying to add them again, which could leave polling stopped;
  - arms polling before anything else;
  - never adds or deletes devices from incomplete data.
- **Settings page:**
  - visiting it while the bridge is down no longer wipes the stored device lists;
  - a device that answers with an error keeps what was stored for it, and no longer blocks the page.
  - the bridge's `__` entry (metadata on newer bridges) is no longer requested as a device, which logged a 404.
- **Connection problems:**
  - requests time out (5 s for polls, 10 s for commands), and timeouts no longer crash the poll or commands;
  - an outage logs one warning after 2 minutes, an hourly reminder, and an info line with its length on recovery (1.4.0 logged an error every poll);
  - a command BOND didn't accept logs a warning;
  - every device has `healthStatus`.
- **Polling:**
  - one device's problem doesn't stop the others;
  - deleted devices are skipped;
  - intervals of 60 seconds or more work (whole minutes, at most hourly).
- **Speeds:**
  - follow the fan's own speed count (1–10). A request the fan doesn't have rounds: on a 3-speed fan, medium-low runs low and medium-high runs high, and `speed` shows what was set;
  - `supportedFanSpeeds` is set, which the Google Home app needs;
  - Cycle Speed works;
  - "auto" logs a warning.
- **Fix Speed** (corrects BOND's record without sending anything):
  - to a speed, also records power on;
  - off, shows the switch off;
  - on, records power on only.
- **Lights:**
  - on/off is polled from BOND's light flag, and brightness is kept while off;
  - timer lights' Dim (seconds) works, also on two lights at once.
- **Fireplaces:**
  - Fix Flame works;
  - the power meter uses its threshold setting;
  - Set Flame off shows the flame off, even with a power meter;
  - the fireplace fan's state follows BOND's fan power, and its speed uses BOND's fireplace-fan action (`SetFpFan`) when the fireplace has it.
- **Logging:** debug logging defaults off and turns itself off after 30 minutes. The settings, including the token, are never logged.

## Attributes

| Attribute | Devices | Values |
|---|---|---|
| `switch` | all | `on` / `off` |
| `speed` | fans | `off`, the fan's speeds, or `on` (power on with no speed BOND reports) |
| `supportedFanSpeeds` | fans | JSON list, for example `["low","medium","high","on","off"]` for a 3-speed fan |
| `level` | dimmable lights | 0–100, kept while the light is off |
| `direction` | fans with direction | `forward` / `reverse` |
| `flame` | fireplaces | `off`, `low`, `medium`, `high` |
| `windowShade` | shades | `open` / `closed` |
| `healthStatus` | all | `online` / `offline` (the bridge isn't answering) |

## Commands

- **Fans:**
  - On, Off, Toggle;
  - Set Speed, which rounds to the fan's speeds;
  - Cycle Speed: off → lowest → … → highest → off;
  - Fix Power and Fix Speed.
- **Lights:**
  - On, Off, Toggle, Fix Power;
  - dimmable: Set Level, Fix Level;
  - timer: Dim (seconds), Start Dimming, Stop Dimming.
- **Fireplaces:** On, Off, Set Flame, Fix Power, Fix Flame. **Shades:** Open, Close, Stop, Fix Shade.

## Notes

- **Hiding a fan's light:** keep its Hubitat device **disabled** rather than deleting it. The
  app re-adds a deleted light on the next Done for as long as BOND lists a light. A disabled
  light isn't polled and gets no events.
- **RF fans:** BOND guesses an RF fan's state from what it sent. A wall switch, the remote or a
  power cut can make it wrong. Fix Power and Fix Speed correct BOND's record without sending anything.
- **Polling:** the app reads each device's state every *N* seconds (setting, default 30). BOND's
  push protocol isn't usable from a Hubitat app.
- **A device BOND stops answering for** (it was removed from the bridge, for example) gets one
  warning, and an info line when it answers again.
- **Testing:** on the cabin's hub (a 3-speed RF ceiling fan with a disabled timer light), 1.5.0's
  polling, `healthStatus`, Done and outage handling (a 5-minute outage) were verified in November 2026. Speed commands, Cycle Speed and
  the light are tested in the local harness and wait for a visit to be tried on the fan itself.
  Fireplaces, shades, generic devices and up/down lights are tested only in the harness. Update this
  line after the visit.
