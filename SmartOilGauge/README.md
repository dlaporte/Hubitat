# Smart Oil Gauge

Parent app + child device for [Smart Oil Gauge](https://www.smartoilgauge.com)
heating-oil tank sensors via the Droplet Fuel API. Creates one child device
per tank, polls hourly, and computes refill-detection, usage rate, days
remaining, and low-fuel alerts.

## Supported hardware

Smart Oil Gauge ultrasonic sensors, accessed via Droplet Fuel's brand API.
Supports any number of tanks per account.

## Setup

You need a **Droplet Fuel API client ID and secret**. These are issued by
your fuel dealer (the same account that gave you the Smart Oil Gauge
sensor); they're separate from the consumer login you use on smartoilgauge.com.

If you don't have them yet, ask your dealer. They typically come from the
[Droplet Fuel monitoring service](https://www.dropletfuel.com).

## Install

In **Hubitat Package Manager → Install**, search for `Smart Oil Gauge`.

This installs both the parent app and the child device driver.

Then:
1. **Apps → Add User App → Smart Oil Gauge (Connect)**
2. Enter your Client ID and Client Secret
3. Authentication should succeed; the app creates a `Tank N` child device per
   tank on your account
4. Tune the thresholds if you want, then click Save

## Preferences

| Name | Default | What it does |
|---|---|---|
| Smart Oil Gauge Client ID | required | Droplet Fuel API client ID |
| Smart Oil Gauge Client Secret | required | Droplet Fuel API client secret |
| Low-fuel alert threshold (% of capacity) | 25 | When `lowFuel` becomes `"true"` |
| Refill detection threshold (% of capacity) | 5 | Minimum jump in gallons to count as a refill |
| Usage-rate smoothing window (hours) | 168 (7 days) | How far back `usageRate` is computed from |
| Reset web tile access token | off | Invalidate OAuth URL and generate a new one |
| Enable debug logging | off | Verbose logging |

## Per-tank attributes

**Tank state:** `gallons`, `capacity`, `level` (%), `lastreading`,
`battery`, `accountNumber`

**Derived:**

- **`usageRate`** — gallons per day, smoothed over the configured window
- **`daysRemaining`** — `gallons / usageRate`, rounded
- **`lowFuel`** — `"true"` when level falls below the threshold
- **`lastRefillAt`** / **`lastRefillGallons`** — emitted when a refill is
  detected (gallons rose by more than the threshold)

**Capability-mandated, also populated:** `humidity`, `energy` (both carry
the tank level percentage so they can be used as data slots in dashboards).

## Commands

Per-tank child devices expose `Refresh` (with `poll()` as a legacy alias).
The parent app also polls hourly automatically.

## Web tile

The parent app serves a per-tank web tile at
`/getTile/<deviceNetworkId>?access_token=…` and an all-tanks view at
`/deviceTiles?access_token=…`. Both URLs are linked from the app's
settings page. The HTML is fully self-contained — only inline CSS and an
inline-SVG tank visualization that fills dynamically based on level.

If you share a tile URL and later want to invalidate it, toggle **Reset
web tile access token** in the app settings.

## Notes

- The driver caches the Droplet API token across polls (1-hour lease).
- Refills are auto-detected when gallons rise by more than
  `refillThresholdPct` of capacity since the last poll. On a 275-gallon
  tank with the default 5%, that's any jump of 13.75 gallons or more —
  enough to ignore gauge jitter but catch any real fill.
- Hub firmware floor: **2.2.0**.
