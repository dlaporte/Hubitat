/*
 *  Amcrest ASH26
 *
 *  LAN driver (CGI API with digest auth) for the Amcrest ASH26 outdoor camera's floodlight,
 *  plus device info that is otherwise only shown in the Amcrest app.
 *  Documentation: https://github.com/dlaporte/Hubitat/blob/main/ASH26/README.md
 *
 *  Changelog:
 *  v0.9.0 (2026-11-23) - motion from the camera's PIR sensors over its event stream
 *         (MotionSensor, with a "Motion inactive after" hold); motion light duration, night
 *         vision and motion detection commands and attributes; Reboot with a quiet 5-minute window;
 *         a poll already under way no longer undoes a change the camera just confirmed.
 *  v0.8.0 (2026-11-20) - switch updates as soon as the camera confirms On/Off; lightMode
 *         attribute; an offline camera is logged once with hourly reminders; healthStatus;
 *         standard logging and lifecycle.
 *  v0.7 - reset state.nc on initialize.
 *  v0.6 - loudly log chain abandonment on mid-sequence set failure.
 *  v0.5 - RFC 2617-compliant digest nc; on/off sequenced.
 *  v0.4 - async HTTP; install/update lifecycle.
 *  v0.3 - fixed two latent digest-auth bugs.
 *  v0.2 - debug logging option.
 *  v0.1 - initial release
 *
 *  Copyright 2021 David LaPorte
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

@Field static final String VERSION = "0.9.0"

// Last time each device's event stream delivered anything (ms). In memory, so the
// camera's 5-second heartbeat writes nothing to state; empty after a code save.
@Field static java.util.concurrent.ConcurrentHashMap<String, Long> streamActivity = new java.util.concurrent.ConcurrentHashMap<String, Long>()
@Field static final String ATTACH_URI = "/cgi-bin/eventManager.cgi?action=attach&codes=%5BAll%5D&heartbeat=5"
@Field static final List<Integer> STREAM_BACKOFF = [5, 15, 60, 300]   // seconds; then stays at 300
@Field static final long STREAM_SILENCE_MS = 60_000L                  // heartbeats come every 5 s
// The keys the Amcrest app writes for each setting (found by diffing the camera's config).
@Field static final List<String> LIGHT_DURATION_KEYS = [
    "Alarm[0].EventHandler.LightingLink.LightDuration",
    "Alarm[1].EventHandler.LightingLink.LightDuration",
    "Alarm[2].EventHandler.LightingLink.LightDuration"]
@Field static final List<String> NIGHT_VISION_KEYS = [
    "Lighting_V2[0][0][0].Mode", "Lighting_V2[0][1][0].Mode", "Lighting[0][1].Mode", "Lighting[0][2].Mode"]
@Field static final List<String> MOTION_DETECT_KEYS = [
    "MotionDetect[0].Enable", "MotionDetect[0].EventHandler.SnapshotEnable"]
@Field static final long REBOOT_WINDOW_MS = 300_000L   // failures this soon after Reboot are expected

metadata {
    definition(
        name: "Amcrest ASH26",
        namespace: "dlaporte",
        author: "David LaPorte",
        importUrl: "https://raw.githubusercontent.com/dlaporte/Hubitat/main/ASH26/device/Amcrest-ASH26.groovy",
        // Each request is two async round trips (digest challenge, then the call),
        // and the 1-minute poll can overlap a command; handlers share state.
        singleThreaded: true
    ) {
        capability "Initialize"
        capability "Refresh"
        capability "Switch"
        capability "MotionSensor"

        attribute "light_status", "string"
        attribute "device_name", "string"
        attribute "serial_number", "string"
        attribute "firmware_version", "string"
        attribute "mac_address", "string"
        attribute "wireless_ssid", "string"
        attribute "lightMode", "string"     // the camera's Lighting_V2 mode (Manual, Off, Auto, ...)
        attribute "healthStatus", "enum", ["online", "offline"]
        attribute "motionLightDuration", "number"           // seconds the light stays on after motion
        attribute "nightVision", "enum", ["Auto", "On", "Off"]
        attribute "motionDetection", "enum", ["on", "off"]  // the camera's video motion detection, not the PIR

        command "setMotionLightDuration", [[name: "seconds*", type: "NUMBER", description: "How long the light stays on after motion (1–3600)"]]
        command "setNightVision", [[name: "mode*", type: "ENUM", constraints: ["Auto", "On", "Off"]]]
        command "setMotionDetection", [[name: "setting*", type: "ENUM", constraints: ["on", "off"]]]
        command "reboot"
    }

    preferences {
        input name: "ip", type: "text", title: "IP address", required: true
        input name: "username", type: "text", title: "ASH26 username", required: true
        input name: "password", type: "password", title: "ASH26 password", required: true
        input name: "motionHoldSec", type: "number", title: "Motion inactive after (seconds)",
              description: "How long after the camera's PIR sensors all stop before motion goes inactive (0–600)",
              defaultValue: 30, range: "0..600"
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
}

void initialize() {
    unschedule()
    upgradeSettings()
    if (settings.logEnable) runIn(1800, "logsOff")
    // Let the next failure (if any) log again so Save gives fresh feedback;
    // commFailSince is kept so the recovery line reports the full outage.
    state.remove("commFailKey")
    // Reset the digest nonce counter so it doesn't accumulate forever in the
    // serialized state map. Each request gets a fresh server-side nonce, so
    // the counter's monotonic-increase isn't actually buying us replay
    // protection beyond what the server already guarantees.
    state.nc = 0
    // Motion and the event stream start clean, so a Save or hub reboot during motion
    // can't leave motion stuck active. streamFailSince is kept, so the recovery line
    // reports the whole outage; streamFailLastLog goes, so a Save logs a failure afresh.
    closeStream()
    ["streamUp", "streamRetryPending", "streamBackoff", "streamFailLastLog", "pirActive"].each { state.remove(it) }
    state.motion = "inactive"
    emitEvent(name: "motion", value: "inactive", descriptionText: "${device.displayName} motion is inactive")
    if (!ip || !username || !password) {
        log.error "required fields not completed, please complete for proper operation."
        return
    }
    runEvery1Minute("refresh")
    refresh()
    // Scheduled, not called: the old stream's STOP arrives after initialize returns,
    // and must still find streamClosing set.
    runIn(2, "connectStream")
}

private void upgradeSettings() {
    device.removeSetting("debug")          // replaced by logEnable in 0.8.0
    device.updateDataValue("driverVersion", VERSION)
}

// ---- Switch + refresh entry points ----

def refresh() {
    logDebug "refresh()"
    checkStreamWatchdog()
    get_camera_settings()
}

def on() {
    logDebug "on()"
    // Order matters: disable alarm-lighting *first*, then set manual mode.
    // With the previous parallel firing, the camera could observe the two
    // sets in arbitrary order.
    setSequence([["AlarmLighting[0][0].Enable", "false"], ["Lighting_V2[0][0][1].Mode", "Manual"]])
}

def off() {
    logDebug "off()"
    setSequence([["AlarmLighting[0][0].Enable", "true"], ["Lighting_V2[0][0][1].Mode", "Off"]])
}

private setSequence(List remaining) {
    if (!remaining) return
    def head = remaining[0]
    def rest = remaining.size() > 1 ? remaining[1..-1] : []
    set_camera_setting(head[0], head[1], [chainRest: rest])
}

// ---- Camera settings commands ----
// Each writes every key the Amcrest app writes for that setting, in one request.

def setMotionLightDuration(seconds) {
    logDebug "setMotionLightDuration(${seconds})"
    Integer s = null
    try { s = new BigDecimal(seconds.toString()).intValueExact() } catch (Exception e) { /* not a whole number */ }
    if (s == null || s < 1 || s > 3600) {
        log.warn "setMotionLightDuration: ${seconds} isn't a whole number of seconds from 1 to 3600; nothing sent"
        return
    }
    setConfigs(LIGHT_DURATION_KEYS.collectEntries { [(it): s.toString()] }, [name: "motionLightDuration", value: s])
}

def setNightVision(mode) {
    logDebug "setNightVision(${mode})"
    String m = [auto: "Auto", on: "On", off: "Off"][mode?.toString()?.trim()?.toLowerCase()]
    if (!m) {
        log.warn "setNightVision: mode must be Auto, On or Off, not ${mode}; nothing sent"
        return
    }
    String value = (m == "On") ? "ForceOn" : m
    setConfigs(NIGHT_VISION_KEYS.collectEntries { [(it): value] }, [name: "nightVision", value: m])
}

def setMotionDetection(setting) {
    logDebug "setMotionDetection(${setting})"
    String v = setting?.toString()?.trim()?.toLowerCase()
    if (!(v in ["on", "off"])) {
        log.warn "setMotionDetection: setting must be on or off, not ${setting}; nothing sent"
        return
    }
    String enable = (v == "on") ? "true" : "false"
    setConfigs(MOTION_DETECT_KEYS.collectEntries { [(it): enable] }, [name: "motionDetection", value: v])
}

// One setConfig request for several keys. onOk is the attribute to show when the camera answers OK.
private void setConfigs(Map pairs, Map onOk) {
    String query = pairs.collect { k, v -> "${k}=${v}" }.join("&")
    String uri = "/cgi-bin/configManager.cgi?action=setConfig&${query}"
    asynchttpGet("unauthRequestHandler", [uri: "http://${ip}${uri}", contentType: "text/plain", timeout: 10], [
            uri: uri,
            finalCallback: "verifySetResponse",
            settingName: pairs.keySet().first(),
            settingValue: pairs.values().first(),
            onOk: onOk])
}

private void emitSetting(String name, value) {
    String text = [motionLightDuration: "motion light duration is ${value} s",
                   nightVision        : "night vision is ${value}",
                   motionDetection    : "motion detection is ${value}"][name]
    emitEvent(name: name, value: value, descriptionText: "${device.displayName} ${text}")
}

// ---- Reboot ----
// The camera is unreachable for a minute or two. For 5 minutes after the request,
// poll and stream failures are expected and logged at debug only.

def reboot() {
    logDebug "reboot()"
    String uri = "/cgi-bin/magicBox.cgi?action=reboot"
    asynchttpGet("unauthRequestHandler", [uri: "http://${ip}${uri}", contentType: "text/plain", timeout: 10],
            [uri: uri, finalCallback: "rebootResponse", reboot: true])
}

def rebootResponse(response, data) {
    String text = (response.data ?: "").toString().trim()
    if (text == "OK") {
        log.info "${device.displayName} rebooting; back in about 1–2 minutes"
        startRebootWindow()
    } else {
        log.warn "camera refused Reboot (${text})"
    }
}

private void startRebootWindow() {
    state.rebootStartedAt = now()
    closeStream()
    runIn(60, "connectStream")
}

private boolean inRebootWindow() {
    Long started = state.rebootStartedAt as Long
    return started != null && now() - started < REBOOT_WINDOW_MS
}

// ---------------------------------------------------------------------------
// Camera operations (async)
//
// Pattern: every Amcrest CGI call returns 401 with WWW-Authenticate on the
// first hit, then 200 once we resend with the digest Authorization header.
// We model that as a two-step async flow:
//   1. asynchttpGet(unauthRequestHandler, ...) — unauthenticated probe
//   2. unauthRequestHandler computes digest, asynchttpGet(authedRequestHandler)
//   3. authedRequestHandler dispatches to the per-operation parser via the
//      finalCallback name passed through userData.
// ---------------------------------------------------------------------------

private get_camera_settings() {
    String uri = "/cgi-bin/configManager.cgi?action=getConfig&name=All"
    def params = [
            uri: "http://${ip}${uri}",
            contentType: "text/plain",
            timeout: 10
    ]
    asynchttpGet("unauthRequestHandler", params, [
            uri: uri,
            finalCallback: "parseCameraSettings",
            sentAt: now()
    ])
}

private set_camera_setting(String name, String value, Map extra = [:]) {
    String uri = "/cgi-bin/configManager.cgi?action=setConfig&${name}=${value}"
    def params = [
            uri: "http://${ip}${uri}",
            contentType: "text/plain",
            timeout: 10
    ]
    Map userData = [
            uri: uri,
            finalCallback: "verifySetResponse",
            settingName: name,
            settingValue: value
    ]
    if (extra) userData.putAll(extra)
    asynchttpGet("unauthRequestHandler", params, userData)
}

// First callback: expects a 401, parses the WWW-Authenticate challenge,
// then re-issues the same request with an Authorization header.
def unauthRequestHandler(response, data) {
    if (!response.hasError() && response.status == 200) {
        // Unusual: a previously-cached connection let us through without auth.
        // Hand straight off to the operation parser.
        noteCommSuccess()
        if (data.finalCallback) "${data.finalCallback}"(response, data)
        return
    }
    if (response.status != 401) {
        requestFailed(data, failureKind(response), httpErrorDetail(response))
        return
    }

    def wwwAuth = extractHeader(response, "WWW-Authenticate")
    if (!wwwAuth) {
        requestFailed(data, "http", "401 without a WWW-Authenticate header")
        return
    }
    def digestMap = parseDigestChallenge(wwwAuth)
    String authHeader = calcDigestAuth(data.uri, "MD5", digestMap)

    def params = [
            uri: "http://${ip}${data.uri}",
            contentType: "text/plain",
            headers: ["Authorization": authHeader],
            timeout: 10
    ]
    asynchttpGet("authedRequestHandler", params, data)
}

// Second callback: dispatches the (now-authenticated) response to the
// operation's parser by the name passed in through data.finalCallback.
def authedRequestHandler(response, data) {
    if (response.hasError() || response.status != 200) {
        // A 401 to the digest-signed request means the credentials are wrong.
        String kind = (response.status == 401) ? "auth" : failureKind(response)
        requestFailed(data, kind, httpErrorDetail(response), true)
        return
    }
    noteCommSuccess(data)
    if (data.finalCallback) {
        "${data.finalCallback}"(response, data)
    }
}

def parseCameraSettings(response, data) {
    def camera_settings = [: ]
    def text = response.data ?: ""
    text.eachLine { line ->
        def parts = line.split("=", 2)
        if (parts.length == 2) {
            camera_settings.put(parts[0].trim(), parts[1].trim())
        }
    }

    sendEvent(name: "device_name",       value: camera_settings['table.All.General.MachineName'])
    sendEvent(name: "serial_number",     value: camera_settings['table.All.VSP_PaaS.SN'])
    sendEvent(name: "firmware_version",  value: camera_settings['table.All.PaaS_UPGRADE.LastVersion'])
    sendEvent(name: "mac_address",       value: camera_settings['table.All.Network.eth2.PhysicalAddress'])
    sendEvent(name: "wireless_ssid",     value: camera_settings['table.All.WLan.eth2.SSID'])

    // A poll sent before the last confirmed change may have read the old values, and would
    // undo that change on the attributes; skip its settings, and the next poll shows them.
    if (data?.sentAt != null && state.lastSetOkAt != null && (data.sentAt as Long) < (state.lastSetOkAt as Long)) {
        logDebug "poll started before the last setting change; its settings are skipped"
        return
    }
    String duration = camera_settings['table.All.Alarm[0].EventHandler.LightingLink.LightDuration']
    if (duration?.isInteger()) emitSetting("motionLightDuration", duration as Integer)
    String nightVision = camera_settings['table.All.Lighting_V2[0][0][0].Mode']
    if (nightVision) emitSetting("nightVision", nightVision == "ForceOn" ? "On" : nightVision)
    String motionDetect = camera_settings['table.All.MotionDetect[0].Enable']
    if (motionDetect in ["true", "false"]) emitSetting("motionDetection", motionDetect == "true" ? "on" : "off")
    applyLightMode(camera_settings['table.All.Lighting_V2[0][0][1].Mode'])
}

// Manual = light on, Off = light off. Other modes (motion-triggered and so on)
// only show in lightMode; switch keeps its last value, as before 0.8.0.
private void applyLightMode(String mode) {
    if (!mode) return
    emitEvent(name: "lightMode", value: mode, descriptionText: "${device.displayName} light mode is ${mode}")
    if (mode == "Manual") {
        emitEvent(name: "light_status", value: "on", descriptionText: "${device.displayName} light is on")
        emitEvent(name: "switch",       value: "on", descriptionText: "${device.displayName} is on")
    } else if (mode == "Off") {
        emitEvent(name: "light_status", value: "off", descriptionText: "${device.displayName} light is off")
        emitEvent(name: "switch",       value: "off", descriptionText: "${device.displayName} is off")
    }
}

def verifySetResponse(response, data) {
    String text = (response.data ?: "").toString().trim()
    if (text == "OK") {
        logDebug "set ${data.settingName} = ${data.settingValue}"
        state.lastSetOkAt = now()
        if (data.chainRest) {
            setSequence(data.chainRest as List)
        } else if (data.onOk) {
            emitSetting(data.onOk.name as String, data.onOk.value)
        } else if (data.settingName == "Lighting_V2[0][0][1].Mode") {
            // The last step of On/Off: show the result now, not at the next poll.
            applyLightMode(data.settingValue as String)
        }
    } else {
        log.error "set ${data.settingName} failed: ${text}"
        if (data.chainRest) {
            // Don't silently swallow the rest of the chain — for on()/off() this
            // means the camera ends up in a half-configured state. Loudly flag it.
            log.error "chain abandoned with ${(data.chainRest as List).size()} step(s) remaining; camera state may be inconsistent"
        }
    }
}

// ---- Event stream: motion ----
// The camera's three PIR sensors report AlarmLocal Start/Stop per sensor index. Motion
// is active while any sensor is, and goes inactive motionHoldSec after the last stops.

// Called by the hub with the event stream's data, one line per call.
def parse(String text) {
    streamActivity[streamKey()] = now()
    // The first data after START: the stream is really up. One state read per line once up.
    if (!state.streamUp && state.streamOpen && !state.streamClosing) streamStarted()
    if (!text?.contains("Code=")) return
    text.readLines().each { String line ->
        if (line.contains("Code=")) handleStreamEvent(line)
    }
}

private void handleStreamEvent(String line) {
    def m = (line =~ /Code=([^;]+);action=([^;]+);index=(\d+)/)
    if (!m.find()) {
        logDebug "stream: unrecognised event ${line.take(120)}"
        return
    }
    String code = m.group(1)
    String action = m.group(2)
    Integer index = m.group(3) as Integer
    if (code != "AlarmLocal") {
        logDebug "stream event ${code} ${action} ${index} ignored"
        return
    }
    if (action == "Start") pirStarted(index)
    else if (action == "Stop") pirStopped(index)
}

// Indexes come back from state as Integer or Long, so compare as Integer.
private void pirStarted(Integer index) {
    List active = (state.pirActive ?: []) as List
    if (!active.any { (it as Integer) == index }) active << index
    state.pirActive = active
    unschedule("motionHoldExpired")
    if (state.motion != "active") {
        state.motion = "active"
        emitEvent(name: "motion", value: "active", descriptionText: "${device.displayName} motion is active")
    }
}

private void pirStopped(Integer index) {
    List active = ((state.pirActive ?: []) as List).findAll { (it as Integer) != index }
    state.pirActive = active
    if (!active) startMotionHold()
}

// Starts the countdown to inactive; a hold of 0 goes inactive now.
private void startMotionHold() {
    if (state.motion != "active") return
    Integer hold = motionHoldSeconds()
    if (hold == 0) motionHoldExpired()
    else runIn(hold, "motionHoldExpired")
}

private Integer motionHoldSeconds() {
    def v = settings.motionHoldSec
    Integer s = (v == null || v.toString().trim() == "") ? 30 : (v as Integer)
    return Math.max(0, Math.min(600, s))
}

void motionHoldExpired() {
    if (((state.pirActive ?: []) as List) || state.motion != "active") return
    state.motion = "inactive"
    emitEvent(name: "motion", value: "inactive", descriptionText: "${device.displayName} motion is inactive")
}

private String streamKey() {
    return device.id.toString()
}

// ---- Event stream: connection ----
// The hub holds the camera's eventManager attach stream open. Every reconnect after a
// deliberate close is scheduled, never immediate, so the old stream's STOP finds
// streamClosing set. Stream problems don't change healthStatus, which follows the poll.

void connectStream() {
    ["streamClosing", "streamRetryPending", "streamOpen"].each { state.remove(it) }
    if (!ip || !username || !password) return
    asynchttpGet("streamProbeHandler", [uri: "http://${ip}${ATTACH_URI}", contentType: "text/plain", timeout: 10], [:])
}

// The unauthenticated probe's 401 carries the digest challenge the stream request signs.
def streamProbeHandler(response, data) {
    String wwwAuth = (response.status == 401) ? extractHeader(response, "WWW-Authenticate") : null
    if (!wwwAuth) {
        streamFailed(response.status == 401 ? "401 without a WWW-Authenticate header" : httpErrorDetail(response))
        return
    }
    // On a 401 the hub's HTTP client resends the signed request 20 times (Task 1), so
    // never connect while the poll's login is being rejected.
    if (state.commFailKey?.toString()?.startsWith("auth|")) {
        streamFailed("the camera is rejecting the login")
        return
    }
    String auth = calcDigestAuth(ATTACH_URI, "MD5", parseDigestChallenge(wwwAuth))
    try {
        interfaces.eventStream.connect("http://${ip}${ATTACH_URI}", [headers: [Authorization: auth], rawData: true, readTimeout: 30])
    } catch (Exception e) {
        streamFailed(e.message ?: e.toString())
    }
}

// START comes for every connect(), even one that then fails (Task 1), so it only marks
// the stream open; the first data marks it up (parse). A refused connect reports START,
// ERROR, STOP; our own close() reports STOP; close() with nothing open reports nothing.
def eventStreamStatus(String msg) {
    logDebug "stream status: ${msg}"
    if (msg?.startsWith("START")) {
        state.streamOpen = true
        streamActivity[streamKey()] = now()   // the watchdog counts silence from here
    } else if (msg?.startsWith("STOP") || msg?.startsWith("ERROR")) {
        state.remove("streamOpen")
        state.remove("streamUp")
        if (state.streamClosing) {            // our own close
            state.remove("streamClosing")
            return
        }
        if (state.streamRetryPending) return  // one drop reports ERROR then STOP
        if (msg.contains("Too many follow-up requests")) {
            // The hub retried a 401 twenty times; don't add failed logins quickly.
            state.streamBackoff = STREAM_BACKOFF.size() - 1
            streamFailed("the camera rejected the login")
            return
        }
        streamFailed(msg)
    }
}

// The first data after a connect: the stream is really up.
private void streamStarted() {
    state.streamUp = true
    state.remove("streamBackoff")
    // A Stop lost while the stream was down mustn't leave motion stuck active.
    state.remove("pirActive")
    startMotionHold()
    if (state.streamFailSince != null) {
        log.info "${device.displayName} motion events are back after ${formatDuration(now() - (state.streamFailSince as Long))}"
    }
    state.remove("streamFailSince")
    state.remove("streamFailLastLog")
}

// close() blocks about 5 s while the hub stops an open stream, and its STOP is queued
// behind the caller; with nothing open it returns at once and reports nothing (Task 1).
// So the flag is set only for an open stream, and a missing STOP can't leave it set to
// swallow the next real drop. connectStream clears it too.
private void closeStream() {
    if (state.streamOpen) state.streamClosing = true
    state.remove("streamOpen")
    state.remove("streamUp")
    try {
        interfaces.eventStream.close()
    } catch (Exception e) {
        logDebug "stream close: ${e.message}"
    }
}

private void streamFailed(String detail) {
    state.remove("streamUp")
    state.streamRetryPending = true
    Integer step = (state.streamBackoff ?: 0) as Integer
    Integer delay = STREAM_BACKOFF[Math.min(step, STREAM_BACKOFF.size() - 1)]
    state.streamBackoff = Math.min(step + 1, STREAM_BACKOFF.size())
    runIn(delay, "connectStream")
    if (inRebootWindow()) {
        logDebug "motion events not back yet after Reboot (${detail})"
        return
    }
    Long nowMs = now()
    if (state.streamFailSince == null) state.streamFailSince = nowMs
    if (state.streamFailLastLog == null) {
        state.streamFailLastLog = nowMs
        log.warn "${device.displayName} motion events stopped (${detail}); reconnecting"
    } else if (nowMs - (state.streamFailLastLog as Long) >= 3_600_000L) {
        state.streamFailLastLog = nowMs
        log.warn "${device.displayName} motion events still down after ${formatDuration(nowMs - (state.streamFailSince as Long))} (${detail}); still reconnecting"
    }
}

// From the 1-minute poll: heartbeats come every 5 s, so a minute of silence since START
// or the last data means a dead connection, whatever the hub's read timeout does.
private void checkStreamWatchdog() {
    if (!state.streamOpen) return
    String key = streamKey()
    Long last = streamActivity[key]
    if (last == null) {                       // no stamp since a code save: start counting now
        streamActivity[key] = now()
        return
    }
    long silentMs = now() - last
    if (silentMs > STREAM_SILENCE_MS) {
        closeStream()
        streamFailed("no data from the camera for ${silentMs.intdiv(1000)} s")
    }
}

// ---- Connection health ----
// The poll runs every minute, so each failure isn't logged: one ERROR naming
// the cause when a problem starts, a WARN reminder at most hourly, and one INFO
// on recovery. The dedup key leaves out the error text, which can change
// during one outage. A failed command is always logged.

// authed: the failure was on the digest-signed request (the second step), not the probe.
private void requestFailed(Map data, String kind, String detail, boolean authed = false) {
    if (data?.reboot) {
        if (authed && kind == "unreachable") {
            // The camera can restart before it answers.
            log.info "${device.displayName} didn't answer the Reboot request; it's probably restarting"
            startRebootWindow()
            noteCommFailure(kind, detail)      // inside the window: offline, logged at debug
            return
        }
        log.warn authed ? "camera refused Reboot (${detail})" : "camera didn't answer; Reboot not sent (${detail})"
    }
    noteCommFailure(kind, detail)
    if (data?.settingName) {
        log.error "set ${data.settingName} failed: ${detail}"
        if (data.chainRest) {
            log.error "chain abandoned with ${(data.chainRest as List).size()} step(s) remaining; camera state may be inconsistent"
        }
    }
}

// No status, or the hub's 408 for a connect/read timeout, means the camera
// never answered; any other status is the camera answering with an error.
private String failureKind(response) {
    def status = null
    try { status = response?.status } catch (Exception e) { /* none */ }
    return (status == null || status == 408) ? "unreachable" : "http"
}

private void noteCommFailure(String kind, String detail) {
    String key = "${kind}|${ip}"
    Long nowMs = now()
    if (state.commFailSince == null) state.commFailSince = nowMs
    if (device.currentValue("healthStatus") != "offline") {
        emitEvent(name: "healthStatus", value: "offline", descriptionText: "${device.displayName} is offline")
    }
    if (inRebootWindow()) {
        logDebug "camera at ${ip} not answering yet after Reboot (${detail})"
        return
    }
    state.remove("rebootStartedAt")   // the window is over: from here it's a normal outage
    if (state.commFailKey != key) {
        state.commFailKey = key
        state.commFailLastLog = nowMs
        if (kind == "auth") {
            log.error "camera at ${ip} rejected the login (${detail}); check the username and password in preferences. Repeats are suppressed; reminder hourly."
        } else if (kind == "http") {
            log.error "camera at ${ip} answered with an error (${detail}). Repeats are suppressed; reminder hourly."
        } else {
            log.error "camera at ${ip} isn't answering (${detail}); it looks offline, off the network, or at a different IP. Repeats are suppressed; reminder hourly."
        }
    } else if (nowMs - ((state.commFailLastLog ?: 0L) as Long) >= 3_600_000L) {
        state.commFailLastLog = nowMs
        String what = (kind == "auth") ? "still rejecting the login" : (kind == "http" ? "still answering with an error" : "still not answering")
        log.warn "camera at ${ip} ${what} after ${formatDuration(nowMs - (state.commFailSince as Long))} (${detail})"
    }
}

// data: the request's userData; polls carry sentAt.
private void noteCommSuccess(Map data = null) {
    if (device.currentValue("healthStatus") != "online") {
        emitEvent(name: "healthStatus", value: "online", descriptionText: "${device.displayName} is online")
    }
    boolean failing = state.commFailSince != null
    Long rebootAt = state.rebootStartedAt as Long
    if (rebootAt != null && !failing && !inRebootWindow()) {
        state.remove("rebootStartedAt")       // a restart no poll noticed, already over
        rebootAt = null
    }
    // The camera restarts in about 30 s, which can fall between two polls, so no poll fails.
    // A poll sent 15 s or more after Reboot was accepted reached the restarted camera.
    Long sentAt = data?.sentAt as Long
    boolean quickRestart = rebootAt != null && sentAt != null && sentAt >= rebootAt + 15_000L
    if (rebootAt != null && (failing || quickRestart)) {
        log.info "${device.displayName} is back after ${formatDuration(now() - rebootAt)} (reboot)"
        state.remove("rebootStartedAt")
    } else if (failing) {
        log.info "camera at ${ip} is responding again after ${formatDuration(now() - (state.commFailSince as Long))}"
    }
    if (!failing) return
    state.remove("commFailSince")
    state.remove("commFailKey")
    state.remove("commFailLastLog")
}

private String httpErrorDetail(response) {
    String msg = null
    def status = null
    try { msg = response?.getErrorMessage() } catch (Exception e) { /* no message available */ }
    try { status = response?.status } catch (Exception e) { /* no status available */ }
    return (msg ?: "no response") + (status ? ", HTTP ${status}" : "")
}

private String formatDuration(Long ms) {
    Long totalMin = Math.max(0L, ms as long).intdiv(60_000L)
    if (totalMin == 0) return "under a minute"
    Long h = totalMin.intdiv(60L)
    Long m = totalMin % 60L
    return h > 0 ? "${h}h ${m}m" : "${m}m"
}

// ---- Digest auth helpers ----

private String extractHeader(response, String name) {
    def headers = response.headers
    if (!headers) return null
    String lname = name.toLowerCase()
    def entry = headers.find { it.key?.toLowerCase() == lname }
    return entry?.value?.toString()
}

private Map parseDigestChallenge(String wwwAuth) {
    // "Digest realm=\"foo\", nonce=\"bar\", qop=\"auth\", opaque=\"xyz\""
    String stripped = wwwAuth.replaceFirst(/(?i)^\s*Digest\s+/, "")
    Map result = [:]
    stripped.split(",").each { part ->
        def kv = part.split("=", 2)
        if (kv.length == 2) {
            result[kv[0].trim()] = kv[1].trim().replaceAll(/^"|"$/, "")
        }
    }
    return result
}

private String hash(String algorithm, String text) {
    return java.security.MessageDigest.getInstance(algorithm).digest(text.bytes).collect {
        String.format("%02x", it)
    }.join('')
}

private String calcDigestAuth(String uri, String algorithm, Map headers) {
    String HA1 = "${username}:${headers.realm.trim()}:${password}"
    String HA1_hash = hash(algorithm, HA1)

    String HA2 = "GET:${uri}"
    String HA2_hash = hash(algorithm, HA2)

    // Each request gets a fresh nonce challenge from the server, so reuse
    // protection isn't actually relying on this counter — but RFC 2617 §3.2.2
    // still requires the wire format to be exactly 8 lowercase hex digits.
    state.nc = (state.nc ?: 0) + 1
    String nc = String.format("%08x", (state.nc as Integer))

    String cnonce = java.util.UUID.randomUUID().toString().replaceAll('-', '').substring(0, 8)
    String response = "${HA1_hash}:${headers.nonce}:${nc}:${cnonce}:auth:${HA2_hash}"
    String response_enc = hash(algorithm, response)

    String eol = " "
    return 'Digest username="' + username + '",' + eol +
        'realm="' + headers.realm + '",' + eol +
        'qop="' + headers.qop + '",' + eol +
        'algorithm="' + algorithm + '",' + eol +
        'uri="' + uri + '",' + eol +
        'nonce="' + headers.nonce + '",' + eol +
        'cnonce="' + cnonce + '",' + eol +
        'opaque="' + (headers.opaque ?: "") + '",' + eol +
        'nc=' + nc + ',' + eol +
        'response="' + response_enc + '"'
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
