# Style guide

These are the conventions every driver and app in this repo follows. Their
purpose is to keep the packages consistent with each other without
breaking anyone's installed devices. `scripts/check_style.py` enforces the
mechanical parts, and CI runs it on every pull request.

## Scope

This guide covers every package except the forks of other people's code:
Xiaomi Vacuums (`XiaomiVacuums/`), which stays a verbatim upstream copy, and
Rheem EcoNet (`RheemEcoNet/`) and BOND Home (`BondHome/`), which keep Dominick
Meglio's names and headers. Neither upstream has a license, so they carry no
Apache block.

## Never rename these

Hubs, HPM and users' rules hold references to all of the following.
Renaming any of them breaks installs:

- **Attribute names**, even the snake_case ones such as `light_status`. New
  attributes use camelCase.
- **Scheduled and callback method names**: anything passed to `runIn`,
  `schedule`, `runEvery*` or `asynchttp*`. A renamed callback throws errors
  until someone clicks Save on each device.
- **File and directory names**, plus the manifest URLs in `repository.json`.
- **Driver and app `name` and `namespace`**, and the manifest `id`s.

Setting names can be retired; see Lifecycle below.

## File header

```groovy
/*
 *  <Driver or app name>
 *
 *  <One to three lines: what it talks to and how (LAN API, cloud, Z-Wave).>
 *  Documentation: https://github.com/dlaporte/Hubitat/blob/main/<Package>/README.md
 *  Based on: <upstream credit, kept verbatim; omit the line if there is none>
 *
 *  Changelog:
 *  v1.4.0 (2026-11-20) - Newest first. One to three lines per entry.
 *  v1.3 - Older entries keep whatever format they already had.
 *
 *  Copyright <first year> David LaPorte
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 */
```

- Design notes go in comments next to the code they explain.
- Install steps and parts lists go in the README.
- There is no "Last Update" line; the newest changelog entry's date serves
  that purpose.

## Layout

- Indent with 4 spaces, never tabs.
- Separate sections with comment dividers such as `// ---- Lifecycle ----`,
  in this order: Lifecycle, Commands, device handlers (Z-Wave or HTTP),
  Helpers, Logging.
- Don't reorder a large existing file only to match; add the dividers
  where the code already sits.

## `definition()`

```groovy
definition(
    name: "Unchanged",
    namespace: "dlaporte",
    author: "David LaPorte",
    importUrl: "https://raw.githubusercontent.com/dlaporte/Hubitat/main/<Package>/<path>.groovy",
    singleThreaded: true
) {
```

- `importUrl` is the raw `main` URL of the file itself.
- Use `singleThreaded: true` whenever callbacks or event handlers share
  `state`. It needs hub firmware 2.2.9 or later.

## Preferences

- Use the named form: `input name: "x", type: "bool", title: "Sentence case"`.
- The logging inputs come last, with exactly this text:

```groovy
input name: "logEnable", type: "bool", title: "Enable debug logging", description: "Turns itself off after 30 minutes", defaultValue: false
input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
```

- Apps have only `logEnable`.
- Child devices with no preferences of their own have neither.

## Logging

Every file with preferences pastes the canonical block, unchanged, at its
very end:

- drivers: [`scripts/style/logging-driver.groovy`](scripts/style/logging-driver.groovy)
- apps: [`scripts/style/logging-app.groovy`](scripts/style/logging-app.groovy)

The block provides:

- `logsOff()`;
- `logDebug(msg)`;
- `logDesc(msg)`, for drivers only: a descriptionText line, unless
  `txtEnable` is false;
- `emitEvent(map)`, for drivers only: sends the event, then logs its
  `descriptionText` only if the value changed.

To change the block, change it in `scripts/style/` and in every file in the
same pull request.

**Levels**

| Level | Use for |
|---|---|
| debug | Protocol detail: requests, raw frames, parsed commands. Always through `logDebug`. |
| info | descriptionText lines (through `emitEvent`/`logDesc`), lifecycle lines ("preferences saved"), and recovery lines |
| warn | Something degraded and will be retried, or bad input |
| error | Something the user has to fix |

**Rules**

- `txtEnable` gates only per-event descriptionText lines. Lifecycle, warn
  and error lines are never gated. The check is null-safe
  (`settings.txtEnable != false`), so a device that hasn't been saved since
  the setting appeared still logs.
- No hard-coded driver prefixes such as `Reolink:`; the Logs page already
  names the source.
- descriptionText starts with `${device.displayName}` (Hubitat convention).
- A `logDebug` message is built even when debug is off. Keep it cheap, and
  don't call anything that might throw. The hub's Groovy lacks some GDK
  methods the local Groovy has, for example `String.takeRight`; use plain
  Java such as `substring`. An `if (debug) log.debug` that becomes
  `logDebug` now runs its string on every call.
- Never log secrets: passwords, client secrets or tokens. If a token has to
  appear, show only its last 4 characters.
- `log.warn` and `log.error` are called directly. Wrapping them adds
  nothing.

## Outage handling

A driver that polls a device or a cloud service must:

- log one line when a failure starts;
- log a reminder at most hourly while it lasts;
- log one recovery line that includes the outage length;
- keep a `healthStatus` attribute (`"online"` or `"offline"`).

Each driver chooses its own wording and level. A TV that is switched off is
normal and logs at info; a camera that stops answering logs an error. The
reference implementation is `noteCommFailure` / `noteCommSuccess` in
`ReolinkFloodlight/device/Reolink-Floodlight.groovy`. The dedup key must not
include the error text, which can change during a single outage.

## Lifecycle

```groovy
void installed() {
    initialize()
}

void updated() {
    log.info "Preferences saved"
    initialize()
    // driver-specific extras, if any
}

void initialize() {
    unschedule()
    upgradeSettings()
    if (settings.logEnable) runIn(1800, "logsOff")
    // re-arm the driver's own schedules, then fetch data
}

private void upgradeSettings() {
    device.removeSetting("debug")          // retired setting names, one line each
    device.updateDataValue("driverVersion", VERSION)
}
```

- `logsOff` is scheduled in `initialize()`, after `unschedule()`. Otherwise
  a reboot, the Initialize button or a Maker API call would cancel the
  auto-off and leave debug on for good.
- **A code update runs neither `updated()` nor `initialize()`.** That is why
  migrations live in `initialize()`: Maker API, the Initialize button or a
  hub reboot can run it.
- Anything that must hold between two events arriving close together, such
  as deduplicating repeated reports, is keyed on a `state` value the driver
  owns, not on `device.currentValue`. `singleThreaded` serializes `state`,
  but an attribute can lag behind.

## Versioning

- Three-part versions: `MAJOR.MINOR.PATCH`. Bump the minor for features or
  behaviour changes and the patch for fixes only.
- Declare the version once per file:

```groovy
import groovy.transform.Field

@Field static final String VERSION = "1.4.0"
```

- The version must match in four places: `VERSION`, the newest changelog
  entry, the manifest's `version` and the README (if it mentions one).
- HPM (`compareVersions`) treats a version as newer if any later part is
  larger, so only ever move versions forward.

## Manifests

Fields go in this order:

```json
{
  "packageName": "...",
  "author": "David LaPorte",
  "version": "1.4.0",
  "minimumHEVersion": "2.2.9",
  "dateReleased": "2026-11-20",
  "documentationLink": "https://github.com/dlaporte/Hubitat/blob/main/<Package>/README.md",
  "communityLink": "(only if a forum thread exists)",
  "releaseNotes": "This release only; older notes live in the file header changelog.",
  "drivers": [
    { "id": "(never change)", "name": "...", "namespace": "dlaporte",
      "location": "https://raw.githubusercontent.com/dlaporte/Hubitat/main/<path>.groovy", "required": true }
  ]
}
```

- `minimumHEVersion` is at least 2.2.9.
- Each `location` equals that file's `importUrl`.
- Never use `refs/heads` in a URL.

## README

- Every package README has these sections: Install, Preferences,
  Attributes, Commands and Notes. Others, such as Supported hardware or
  Setup, are welcome.
- Update the README in the same pull request as the code it describes.

## Testing

`tests/harness/HubitatHarness.groovy` loads a driver into plain Groovy with
fakes for:

- events, `runIn`/`unschedule`, settings, `state`;
- the device object, logs and a settable clock;
- Z-Wave command classes.

Drivers can then be unit-tested off the hub. It matters most for logic you
can't trigger remotely, such as a sensor transition.

- Tests live in `tests/<package>/*Test.groovy` and run with
  `scripts/run-tests.sh`, which uses the local `groovy` (6.x).
- The hub runs Groovy 2.4, so the hub's compile-on-save is still the check
  for syntax.
- The harness is a fake, not an emulator. If a driver needs a platform call
  the harness doesn't model, add it to the harness in the same pull request.

## Releasing

1. Work on a branch.
2. Run the local checks:
   - `python3 -m unittest discover -s scripts/tests`
   - `python3 scripts/check_style.py`
3. Commit whitespace and header changes on their own. Prove them with
   `python3 scripts/same_code.py <rev> <file>`, which must report "same
   code".
4. Save a copy of the code currently on the hub, then push the branch code
   to the hub's existing driver or app entry. Verify on the real device.
5. Bump the manifest last, and only once the code has proved itself on a
   hub. Until then `check_style.py` reports a manifest version mismatch,
   which is the merge gate. HPM users get exactly what the manifest
   announces, and fresh installs and HPM Repair pull from `main`, so
   untested code must not be merged.
6. Merge, then run HPM Update on the hubs.
