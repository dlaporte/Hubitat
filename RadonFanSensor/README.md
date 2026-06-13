# Radon Fan Sensor

Z-Wave driver for a battery-powered door sensor wired to a pressure switch
on a radon mitigation fan. The driver presents the fan as a `ContactSensor`
and exposes a `fanAlarm` attribute that fires `"alarm"` if the fan has
been off longer than a configurable threshold — surviving hub reboots
without resetting the clock.

## What problem this solves

A radon mitigation fan that quietly fails is a serious health hazard.
Commercial monitors exist but are expensive and usually require their own
ecosystem. This driver turns a $20 Z-Wave door sensor into a fan-failure
alarm.

## Hardware

You'll build a small assembly — these are the parts I used:

| Part | Notes |
|---|---|
| Dwyer 1910-00 pressure switch | The kind that closes on suction |
| Monoprice 15270 (or WADWAZ-1) door sensor with screw terminals | Z-Wave Plus |
| 1/8" barbed 3-way fitting | Tees off the manometer line |
| 1/8" Barb × 1/8" NPT Male pipe fitting | Connects pressure switch to the tee |
| 1/8" ID plastic tubing | Short run |

## Install (non-destructively, on an existing RadonAway Easy Manometer)

1. Use a Dremel or other tool to expose the door sensor's screw terminals
2. Connect the pressure-switch terminals to the door-sensor terminals
3. Install the 1/8" barb × 1/8" NPT fitting on the pressure switch's
   *low-pressure* connection
4. Test: attach tubing to the adapter and gently inhale — the sensor should
   trip
5. Remove the manometer tubing from the hole in the vent pipe
6. Attach a short segment of tubing to the 3-way fitting
7. Attach the manometer tubing to the 3-way fitting
8. Connect tubing from the adapter to the 3-way fitting
9. Insert the short tube into the vent-pipe hole
10. Test: shut off power to the fan and confirm there's no updraft in the
    pipe that would false-positive the sensor
11. Pair the door sensor to your Hubitat hub and assign this driver

## Install (in Hubitat)

In **Hubitat Package Manager → Install**, search for `Radon Fan Sensor`.

Then pair the door sensor:
1. **Devices → Add Device → Z-Wave**
2. Trigger the sensor's inclusion mode
3. Once paired, change the **Type** to `Radon Fan Sensor` and click **Save**
4. Click **Initialize**

## Preferences

| Name | Default | What it does |
|---|---|---|
| Fire fanAlarm if fan has been off for (minutes) | 30 | When `fanAlarm` becomes `"alarm"` |
| Debug logging | off | Verbose logging |

## Attributes

- **`contact`** — `"closed"` when the fan is running, `"open"` when it's stopped (this is the canonical attribute from the `ContactSensor` capability)
- **`switch`** — `"on"` / `"off"` — legacy duplicate kept for backward compatibility
- **`lastStartedAt`** / **`lastStoppedAt`** — ISO timestamps of the last transitions
- **`fanAlarm`** — `"ok"` while running or just-stopped; becomes `"alarm"`
  once the configured threshold elapses with the fan still off; resets to
  `"ok"` the moment the fan starts again
- **`battery`** — sensor battery %

## Commands

`Refresh` requests current state from the Z-Wave device.

## Notes

- The `fanAlarm` timer **survives hub reboots**. On Initialize, if the fan
  is currently off, the driver re-arms the alarm for the *remaining* window
  (subtracting the time already elapsed since `lastStoppedAt`).
- Hub firmware floor: 2.1.9.
- Sleep soundly knowing your house can no longer silently kill you if
  the fan dies.
