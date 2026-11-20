/*
 *  Radon Fan Sensor
 *
 *  Z-Wave driver for a battery door sensor (GoControl WADWAZ-1 / Monoprice 15270) whose
 *  terminals are wired to a pressure switch on a radon mitigation fan.
 *  Documentation: https://github.com/dlaporte/Hubitat/blob/main/RadonFanSensor/README.md
 *
 *  Changelog:
 *  v0.10.0 (2026-11-20) - Initialize runs at hub boot; repeat reports no longer restart the
 *         alarm clock; no repeat alarm after a reboot; threshold changes apply on save; Invert
 *         sensor option; healthStatus; standard logging and lifecycle.
 *  v0.9 - fan alarm survives hub reboots and accounts for elapsed time.
 *  v0.7 - Z-Wave correctness: state.MSR, NOMI, and parse() cleanup.
 *  v0.6 - removed inapplicable Enerwave MSR branch.
 *  v0.5 - added ContactSensor capability (contact = closed when fan is
 *         running, open when off) alongside the existing switch attribute
 *         so the device matches the semantic Hubitat expects for a binary
 *         sensor. Added lastStartedAt / lastStoppedAt timestamps and a
 *         fanAlarm attribute that fires "alarm" if the fan has been off
 *         for longer than a user-configurable threshold (default 30 min).
 *         Gated all log.debug calls behind a debug preference (was always
 *         on). Existing switch-based rules are unchanged — additive only.
 *  v0.4 - removed SmartThings legacy simulator/tiles blocks, fixed
 *         wrong install-instructions URL, removed stray literal 0.
 *  v0.3 - earlier release
 *
 *  Copyright 2017 David LaPorte
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

import groovy.transform.Field

@Field static final String VERSION = "0.10.0"

metadata {
    definition(
        name: "Radon Fan Sensor",
        namespace: "dlaporte",
        author: "David LaPorte",
        importUrl: "https://raw.githubusercontent.com/dlaporte/Hubitat/main/RadonFanSensor/device/15270-radon-fan.groovy",
        singleThreaded: true
    ) {
        capability "Sensor"
        capability "Battery"
        capability "ContactSensor"
        capability "Refresh"
        capability "Initialize"

        attribute "switch", "enum", ["on", "off"]    // backward compat with prior rules
        attribute "lastStartedAt", "string"           // ISO timestamp of last fan-on transition
        attribute "lastStoppedAt", "string"           // ISO timestamp of last fan-off transition
        attribute "fanAlarm", "string"                // "ok" or "alarm" — alarm if off too long
        attribute "healthStatus", "enum", ["online", "offline"]
    }

    preferences {
        input name: "alarmAfterMinutes", type: "number", title: "Fire fanAlarm if fan has been off for (minutes)", defaultValue: 30, range: "1..1440"
        input name: "invertSensor", type: "bool", title: "Invert sensor", description: "Turn on if Hubitat shows the fan running when it is off (pressure switch wired the other way)", defaultValue: false
        input name: "logEnable", type: "bool", title: "Enable debug logging", description: "Turns itself off after 30 minutes", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

// ---- Lifecycle ----

void installed() {
    initialize()
}

void updated() {
    log.info "Preferences saved"
    initialize()
    // Re-derive the fan state from the last raw report so turning Invert
    // sensor on or off takes effect immediately.
    if (state.lastRaw != null) sensorValueEvent(state.lastRaw)
}

// Runs at hub boot (Initialize capability), from the Initialize button, and on
// every preferences save. A hub reboot drops pending runIn jobs, so everything
// is re-armed here.
void initialize() {
    unschedule()
    upgradeSettings()
    if (settings.logEnable) runIn(1800, "logsOff")
    rearmFanAlarm()
    rearmOfflineTimer()
}

private void upgradeSettings() {
    device.removeSetting("debug")          // replaced by logEnable in 0.10.0
    device.updateDataValue("driverVersion", VERSION)
}

// If the fan is off, re-arm the alarm timer for the *remaining* window, not a
// fresh one. Otherwise a reboot 28 min into a 30-min window resets the clock to
// a full 30, silently defeating the safety net. An alarm that already fired is
// left alone, so a reboot doesn't send the notification again.
private void rearmFanAlarm() {
    if (fanRunning() != false) return
    if (device.currentValue("fanAlarm") == "alarm") return
    Integer afterSec = alarmAfterSeconds()
    Integer delaySec = afterSec
    String stoppedAt = device.currentValue("lastStoppedAt")
    if (stoppedAt) {
        try {
            Long stoppedMs = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").parse(stoppedAt).time
            Long elapsedSec = (now() - stoppedMs).intdiv(1000)
            delaySec = Math.max(1, (afterSec - elapsedSec) as Integer)
        } catch (Exception e) { /* unparseable — use the full window */ }
    }
    runIn(delaySec, "checkFanAlarm")
}

// Restart the offline timer for whatever is left of the window since the last
// check-in. With no stored check-in (first run of 0.10.0) it starts a full
// window; device.lastActivity isn't used because it can be stale.
private void rearmOfflineTimer() {
    Integer window = offlineAfterSeconds()
    Integer delaySec = window
    Long last = state.lastCheckinMs as Long
    if (last) delaySec = Math.max(1, (window - (now() - last).intdiv(1000)) as Integer)
    runIn(delaySec, "markOffline")
}

// ---- Commands ----

// The sensor sleeps, so nothing sent now would reach it. Queue the request for
// its next wake-up instead. (sensorBinaryGet is not used: on a WADWAZ-1 it may
// report the internal reed switch rather than the wired terminals.)
void refresh() {
    state.refreshPending = true
    log.info "${device.displayName} is asleep; it will report its battery and wake-up interval when it next wakes up"
}

// Called via runIn after the configured fan-off threshold. If the fan is still
// off, fire the fanAlarm attribute → "alarm" so Rule Machine can notify.
void checkFanAlarm() {
    if (fanRunning() != false) return
    if (device.currentValue("fanAlarm") == "alarm") return
    emitEvent(name: "fanAlarm", value: "alarm", isStateChange: true,
        descriptionText: "${device.displayName} fan has been off for ${alarmAfterSeconds().intdiv(60)} minutes")
}

// Called via runIn when the sensor has missed two wake-ups (see offlineAfterSeconds).
void markOffline() {
    if (device.currentValue("healthStatus") == "offline") return
    Long last = state.lastCheckinMs as Long
    Long hours = last ? (now() - last).intdiv(3_600_000L) : offlineAfterSeconds().intdiv(3600)
    log.warn "${device.displayName} hasn't checked in for ${hours} hours; check its battery and Z-Wave connection"
    emitEvent(name: "healthStatus", value: "offline", descriptionText: "${device.displayName} is offline")
}

// ---- Z-Wave handlers ----

def parse(String description) {
    logDebug "parse: ${description}"
    if (description.startsWith("Err")) {
        log.warn "Z-Wave error from ${device.displayName}: ${description}"
        return null
    }
    noteCheckin()
    def cmd = zwave.parse(description, [0x20: 1, 0x25: 1, 0x30: 1, 0x31: 5, 0x80: 1, 0x84: 1, 0x71: 3, 0x9C: 1])
    logDebug "parsed: ${cmd}"
    if (!cmd) return null
    def result = zwaveEvent(cmd)
    // Handlers may add nulls (sensorValueEvent sends its own events and returns null).
    return (result instanceof List) ? result.flatten().findAll { it != null } : result
}

// Every fan report lands here. value is the raw sensor value: 0 = contact open,
// anything else = closed. With the standard wiring, closed means the fan runs.
def sensorValueEvent(value) {
    logDebug "sensorValueEvent: value = ${value}"
    Integer raw = (value ?: 0) as Integer
    state.lastRaw = raw
    boolean running = (raw != 0) != (settings.invertSensor == true)
    // Each transition arrives as two frames ~0.4 s apart. Dedup on state the
    // driver owns (serialized by singleThreaded): the contact attribute can lag.
    Boolean prev = fanRunning()
    if (prev != null && prev == running) {
        logDebug "repeat report (running=${running}) ignored"
        return null
    }
    state.fanRunning = running
    if (running) {
        unschedule("checkFanAlarm")
        emitEvent(name: "contact", value: "closed", descriptionText: "${device.displayName} fan is running")
        sendEvent(name: "switch", value: "on")
        sendEvent(name: "lastStartedAt", value: isoNow())
        emitEvent(name: "fanAlarm", value: "ok", descriptionText: "${device.displayName} fan alarm cleared")
    } else {
        emitEvent(name: "contact", value: "open", descriptionText: "${device.displayName} fan stopped")
        sendEvent(name: "switch", value: "off")
        sendEvent(name: "lastStoppedAt", value: isoNow())
        runIn(alarmAfterSeconds(), "checkFanAlarm")
    }
    return null
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd) {
    sensorValueEvent(cmd.value)
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicSet cmd) {
    sensorValueEvent(cmd.value)
}

def zwaveEvent(hubitat.zwave.commands.switchbinaryv1.SwitchBinaryReport cmd) {
    sensorValueEvent(cmd.value)
}

def zwaveEvent(hubitat.zwave.commands.sensorbinaryv1.SensorBinaryReport cmd) {
    sensorValueEvent(cmd.sensorValue)
}

def zwaveEvent(hubitat.zwave.commands.sensoralarmv1.SensorAlarmReport cmd) {
    sensorValueEvent(cmd.sensorState)
}

// Dispatch kept as-is until a debug capture of fan start/stop and cover removal
// shows which frames carry what (see docs backlog: type-7 dispatch).
def zwaveEvent(hubitat.zwave.commands.notificationv3.NotificationReport cmd) {
    def result = []
    if (cmd.notificationType == 0x06 && cmd.event == 0x16) {
        result << sensorValueEvent(1)
    } else if (cmd.notificationType == 0x06 && cmd.event == 0x17) {
        result << sensorValueEvent(0)
    } else if (cmd.notificationType == 0x07) {
        if (cmd.v1AlarmType == 0x07) { // special case for nonstandard messages from Monoprice door/window sensors
            result << sensorValueEvent(cmd.v1AlarmLevel)
        } else if (cmd.event == 0x01 || cmd.event == 0x02) {
            result << sensorValueEvent(1)
        } else if (cmd.event == 0x03) {
            result << createEvent(descriptionText: "$device.displayName covering was removed", isStateChange: true)
            result << response(zwave.wakeUpV1.wakeUpIntervalSet(seconds:4*3600, nodeid:zwaveHubNodeId))
        if(!state.MSR)
            result << response(zwave.manufacturerSpecificV2.manufacturerSpecificGet())
        } else if (cmd.event == 0x05 || cmd.event == 0x06) {
            result << createEvent(descriptionText: "$device.displayName detected glass breakage", isStateChange: true)
        } else if (cmd.event == 0x07) {
            if(!state.MSR)
                result << response(zwave.manufacturerSpecificV2.manufacturerSpecificGet())
            result << createEvent(name: "motion", value: "active", descriptionText:"$device.displayName detected motion")
        }
    } else if (cmd.notificationType) {
        def text = "Notification $cmd.notificationType: event ${([cmd.event] + cmd.eventParameter).join(", ")}"
        result << createEvent(name: "notification$cmd.notificationType", value: "$cmd.event", descriptionText: text, displayed: false)
    } else {
        def value = cmd.v1AlarmLevel == 255 ? "active" : cmd.v1AlarmLevel ?: "inactive"
        result << createEvent(name: "alarm $cmd.v1AlarmType", value: value, displayed: false)
    }
    result
}

def zwaveEvent(hubitat.zwave.commands.wakeupv1.WakeUpNotification cmd) {
    logDebug "woke up"
    def result = []
    boolean refreshNow = state.refreshPending == true
    if (refreshNow || !state.lastbat || now() - (state.lastbat as Long) > 53*60*60*1000L) {
        result << response(zwave.batteryV1.batteryGet())
        result << response("delay 1200")
    }
    if (!state.MSR) {
        result << response(zwave.wakeUpV1.wakeUpIntervalSet(seconds:4*3600, nodeid:zwaveHubNodeId))
        result << response(zwave.manufacturerSpecificV2.manufacturerSpecificGet())
        result << response("delay 1200")
    }
    // The offline threshold is based on the interval the sensor reports.
    if (refreshNow || !wakeUpIntervalKnown()) {
        result << response(zwave.wakeUpV1.wakeUpIntervalGet())
        result << response("delay 1200")
    }
    state.remove("refreshPending")
    result << response(zwave.wakeUpV1.wakeUpNoMoreInformation())
    result
}

def zwaveEvent(hubitat.zwave.commands.wakeupv1.WakeUpIntervalReport cmd) {
    logDebug "wake-up interval: ${cmd.seconds} s"
    state.wakeUpIntervalSec = cmd.seconds as Integer
    runIn(offlineAfterSeconds(), "markOffline")
    return null
}

def zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd) {
    Map evt = [name: "battery", unit: "%"]
    if (cmd.batteryLevel == 0xFF) {
        evt.value = 1
        evt.descriptionText = "${device.displayName} has a low battery"
        evt.isStateChange = true
    } else {
        evt.value = cmd.batteryLevel
        evt.descriptionText = "${device.displayName} battery is ${cmd.batteryLevel}%"
    }
    state.lastbat = now()
    // Don't send wakeUpNoMoreInformation here — WakeUpNotification owns NOMI
    // as its final command. Sending it again from BatteryReport could put
    // the device to sleep before the controller finishes its command queue.
    emitEvent(evt)
    return []
}

def zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {
    def result = []

    def msr = String.format("%04X-%04X-%04X", cmd.manufacturerId, cmd.productTypeId, cmd.productId)
    logDebug "MSR: ${msr}"
    updateDataValue("MSR", msr)
    // All other handlers check `state.MSR` (not `device.data.MSR`), so we
    // have to set both — otherwise WakeUpNotification keeps re-running its
    // "do we have MSR?" config branch on every wake-up forever.
    state.MSR = msr

    // Request battery on first MSR if we don't have one yet.
    if (!device.currentState("battery")) {
        result << response(zwave.batteryV1.batteryGet())
    }
    result
}

def zwaveEvent(hubitat.zwave.Command cmd) {
    logDebug "unhandled: ${cmd}"
    return null
}

// ---- Helpers ----

// Driver-owned fan state; falls back to the contact attribute on the first
// report after upgrading to 0.10.0. null means unknown.
private Boolean fanRunning() {
    if (state.fanRunning != null) return state.fanRunning as Boolean
    def contact = device.currentValue("contact")
    return contact == null ? null : contact == "closed"
}

// Any message from the sensor proves it's alive.
private void noteCheckin() {
    state.lastCheckinMs = now()
    if (device.currentValue("healthStatus") != "online") {
        emitEvent(name: "healthStatus", value: "online", descriptionText: "${device.displayName} is online")
    }
    runIn(offlineAfterSeconds(), "markOffline")
}

private boolean wakeUpIntervalKnown() {
    return ((state.wakeUpIntervalSec ?: 0) as Integer) > 0
}

// Offline after two missed wake-ups plus an hour's grace. Until the sensor has
// reported its interval (or if it reports 0), allow a week.
private Integer offlineAfterSeconds() {
    Integer interval = (state.wakeUpIntervalSec ?: 0) as Integer
    return interval > 0 ? 2 * interval + 3600 : 7 * 24 * 3600
}

private Integer alarmAfterSeconds() {
    return ((settings.alarmAfterMinutes ?: 30) as Integer) * 60
}

private String isoNow() {
    def fmt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")
    fmt.setTimeZone(location?.timeZone ?: TimeZone.getDefault())
    return fmt.format(new Date(now()))
}

// ---- BEGIN standard logging (driver) v1: keep identical in every driver, see STYLE.md ----

void logsOff() {
    log.info "Debug logging turned off"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

private void logDebug(String msg) {
    if (settings.logEnable) log.debug msg
}

private void logDesc(String msg) {
    if (settings.txtEnable != false) log.info msg
}

// Sends the event; logs its descriptionText only when the value actually changes.
private void emitEvent(Map evt) {
    boolean changed = device.currentValue(evt.name as String)?.toString() != evt.value?.toString()
    sendEvent(evt)
    if (changed && evt.descriptionText) logDesc(evt.descriptionText as String)
}

// ---- END standard logging ----
