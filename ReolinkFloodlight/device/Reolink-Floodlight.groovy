/*
 *  Reolink Floodlight Camera
 *
 *  LAN driver (HTTP JSON API) for Reolink cameras with a WhiteLed floodlight, such as the
 *  Elite Pro Floodlight PoE (F760P), Elite Floodlight WiFi (F751W) and Duo 3 PoE (P750).
 *  Documentation: https://github.com/dlaporte/Hubitat/blob/main/ReolinkFloodlight/README.md
 *
 *  Changelog:
 *  v0.11.0 (2026-11-23) - Use HTTPS setting (off by default): the login, commands and snapshot
 *         URL go over HTTPS, accepting the camera's self-signed certificate. Reboot command;
 *         for 5 minutes after it, the camera being unreachable isn't logged as an error.
 *  v0.10.1 (2026-11-23) - The keep-on setting's title says it acts only after On, until Off.
 *  v0.10.0 (2026-11-20) - Re-login after a camera reboot (expired-token replies were missed);
 *         one login at a time; device info refreshes every 30 minutes; logs out on Initialize;
 *         healthStatus; debug logging turns itself off; standard logging and lifecycle.
 *  v0.9.1 - driver now runs single-threaded, so overlapping polls can't race on
 *           state; fixes a burst of duplicate "isn't answering" errors when the
 *           camera loses power. Requires Hubitat 2.2.9+.
 *  v0.9 - connection-failure logging names the real cause: "camera isn't
 *         answering" (network) vs. "camera rejected the login" (credentials),
 *         instead of the misleading "login failed: null". Logs one ERROR when
 *         a problem starts, an hourly reminder, and one INFO on recovery with
 *         the outage length — previously every 5-second poll logged an error.
 *  v0.8 - surface + manage the camera's per-AI floodlight triggers (wlAiDetectType).
 *         Newer firmware arms these independently of the floodlight mode, so the
 *         light can flash on person/vehicle/animal detection at night even with
 *         floodlightMode Off. New attribute floodlightSmartTriggers, new command
 *         setFloodlightSmartTriggers; setFloodlightMode("Off") now warns when
 *         triggers remain armed. The poll loop now also tracks floodlightMode
 *         and floodlightSmartTriggers, so changes made in the Reolink app show
 *         up within one poll interval. NOTE: the firmware refuses to disarm
 *         ALL triggers over the LAN API (it silently re-arms every one of
 *         them); reaching all-off requires the Reolink app.
 *  v0.7 - manifest minimumHEVersion → 2.2.0.
 *  v0.6 - snapshotURL is now opt-in (embeds plaintext credentials).
 *  v0.5 - all HTTP converted to asynchttpPost.
 *  v0.4 - added powerLED, RTSP URLs, SD-card health, snapshotURL.
 *  v0.3 - microphone enable/disable; AI reporting auto-gated on
 *         the camera's capability flags.
 *  v0.2 - UX pass and attribute renames.
 *  v0.1 - initial release
 *
 *  Copyright 2026 David LaPorte
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

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String VERSION = "0.11.0"
@Field static final long REBOOT_WINDOW_MS = 300_000L   // failures this soon after Reboot are expected

// WhiteLed "mode" values, by index.
@Field static final List<String> FLOODLIGHT_MODES = ["Off", "NightSmart", "AlwaysAtNight", "Schedule"]

metadata {
    // singleThreaded: polls overlap (5 s interval vs. 10 s HTTP timeout), and concurrent
    // handlers would otherwise race on state — e.g. the connection-failure dedup keys.
    definition(
        name: "Reolink Floodlight Camera",
        namespace: "dlaporte",
        author: "David LaPorte",
        importUrl: "https://raw.githubusercontent.com/dlaporte/Hubitat/main/ReolinkFloodlight/device/Reolink-Floodlight.groovy",
        singleThreaded: true
    ) {
        capability "Initialize"
        capability "Refresh"
        capability "Switch"
        capability "SwitchLevel"
        capability "MotionSensor"

        attribute "model", "string"
        attribute "deviceName", "string"
        attribute "firmwareVersion", "string"
        attribute "hardwareVersion", "string"
        attribute "serial", "string"
        attribute "mac", "string"
        attribute "cameraIP", "string"

        attribute "floodlightMode", "string"
        attribute "floodlightSmartTriggers", "string"
        attribute "lightingSchedule", "string"
        attribute "IRMode", "string"
        attribute "dayNightMode", "string"
        attribute "microphone", "string"
        attribute "powerLED", "string"

        attribute "rtspMainStream", "string"
        attribute "rtspSubStream", "string"
        attribute "snapshotURL", "string"

        attribute "sdCardCapacityMB", "number"
        attribute "sdCardFreeMB", "number"
        attribute "sdCardMounted", "string"

        attribute "personDetected", "string"
        attribute "vehicleDetected", "string"
        attribute "animalDetected", "string"
        attribute "faceDetected", "string"
        attribute "healthStatus", "enum", ["online", "offline"]

        command "setBrightness", [[name: "level", type: "NUMBER",
                description: "Floodlight brightness, 0-100 (0 turns the light off)"]]
        command "setFloodlightMode", [[name: "mode", type: "ENUM",
                description: "Off = fully disabled; other modes control auto-trigger behavior",
                constraints: ["Off", "NightSmart", "AlwaysAtNight", "Schedule"]]]
        command "setFloodlightSmartTriggers", [
                [name: "people", type: "ENUM", description: "Flash the floodlight on person detection at night",
                  constraints: ["On", "Off"]],
                [name: "vehicle", type: "ENUM", description: "Flash the floodlight on vehicle detection at night",
                  constraints: ["On", "Off"]],
                [name: "animal", type: "ENUM", description: "Flash the floodlight on animal detection at night",
                  constraints: ["On", "Off"]]
        ]
        command "setLightingSchedule", [
                [name: "startTime", type: "STRING", description: "Lighting start time in 24-hour HH:mm (e.g. 18:00)"],
                [name: "endTime",   type: "STRING", description: "Lighting end time in 24-hour HH:mm (e.g. 06:00)"]
        ]
        command "setIRMode", [[name: "mode", type: "ENUM", constraints: ["Auto", "Off"]]]
        command "setDayNightMode", [[name: "mode", type: "ENUM", constraints: ["Auto", "Color", "Black&White"]]]
        command "setMicrophone", [[name: "state", type: "ENUM",
                description: "Enable or disable the camera's microphone (Enc.audio)",
                constraints: ["On", "Off"]]]
        command "setPowerLED", [[name: "state", type: "ENUM",
                description: "Toggle the camera's status/power LED (only on models with one)",
                constraints: ["On", "Off"]]]
        command "reboot"
    }

    preferences {
        input name: "ip", type: "text", title: "IP address", required: true
        input name: "username", type: "text", title: "Username", required: true
        input name: "password", type: "password", title: "Password", required: true
        input name: "useHttps", type: "bool", title: "Use HTTPS",
                description: "Encrypts the login and commands. The camera's certificate is self-signed, so it is accepted without being checked. Turn this off if the camera has HTTPS disabled.",
                defaultValue: false
        input name: "pollInterval", type: "number", title: "Motion/AI poll interval (seconds)", defaultValue: 5, range: "5..300", required: true
        input name: "keepLightOn", type: "bool", title: "After On, keep the floodlight on until Off (sends On again every 2 min)",
                description: "For cameras that turn a manually switched-on light off by themselves. Has no effect on cameras with Reolink's own keep-on.",
                defaultValue: true
        input name: "publishSnapshotURL", type: "bool", title: "Publish snapshotURL attribute (embeds username/password in plaintext — only enable if you use it in an image tile)", defaultValue: false
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
    // Free the old camera session before starting a new one; Reolink caps sessions.
    logoutIfLoggedIn()
    state.token = null
    state.tokenExpiry = 0
    state.remove("loginStartedAt")
    state.remove("loginQueue")
    // Let the next failure (if any) log again so Save gives fresh feedback;
    // commFailSince is kept so the recovery line reports the full outage.
    state.remove("commFailKey")
    // Bump epoch so any in-flight pollStatus from a previous initialize() bows out cleanly
    state.pollEpoch = ((state.pollEpoch ?: 0) as Integer) + 1

    if (!ip || !username || !password) {
        log.error "IP, username, and password are required"
        return
    }

    sendEvent(name: "cameraIP", value: ip)
    // snapshotURL is opt-in (default off). The URL embeds the camera's plaintext
    // username/password, which would otherwise live in event history, hub
    // backups, and the Maker API.
    if (publishSnapshotURL) {
        String snap = apiUrl("cmd=Snap&channel=0&rs=hubitat") +
                "&user=${URLEncoder.encode(username, 'UTF-8')}&password=${URLEncoder.encode(password, 'UTF-8')}"
        sendEvent(name: "snapshotURL", value: snap)
    } else {
        try { device.deleteCurrentState("snapshotURL") } catch (Exception e) { /* fine if absent */ }
    }

    runEvery30Minutes("scheduledRefresh")
    refresh()
    runIn(1, "pollStatus")
}

private void upgradeSettings() {
    device.removeSetting("debug")          // replaced by logEnable in 0.10.0
    device.updateDataValue("driverVersion", VERSION)
}

void uninstalled() {
    unschedule()
    logoutIfLoggedIn()
}

// Fire-and-forget logout, no callback. Skipped when the token has expired, so we
// never log in just to log out.
private void logoutIfLoggedIn() {
    if (hasValidToken()) sendCmd([[cmd: "Logout", param: [:]]], null, [:])
}

// ---- Switch / SwitchLevel ----

def on() {
    logDebug "on()"
    def level = (device.currentValue("level") ?: 100) as Integer
    if (level <= 0) level = 100
    applyFloodlight(1, level)
}

def off() {
    logDebug "off()"
    unschedule("keepAlive")
    applyFloodlight(0, null)
}

// SwitchLevel capability — required for Rule Machine dimmer compatibility.
// `duration` (ramp time) is ignored; the camera firmware does not support fades.
def setLevel(level, duration = null) {
    setBrightness(level)
}

// Friendlier alias for setLevel — explicit "brightness" naming on the device page.
def setBrightness(level) {
    logDebug "setBrightness(${level})"
    Integer lvl = (level as BigDecimal).intValue()
    if (lvl < 0) lvl = 0
    if (lvl > 100) lvl = 100
    if (lvl == 0) {
        off()
    } else {
        applyFloodlight(1, lvl)
    }
}

private applyFloodlight(Integer desiredState, Integer brightness) {
    def param = [WhiteLed: [channel: 0, state: desiredState]]
    if (brightness != null) param.WhiteLed.bright = brightness
    sendCmd([[cmd: "SetWhiteLed", param: param]], "applyFloodlightHandler", [
            desiredState: desiredState,
            brightness: brightness
    ])
}

def applyFloodlightHandler(result, data) {
    if (!result || result[0]?.code != 0) {
        log.error "SetWhiteLed failed: ${result}"
        return
    }
    emitDesc("switch", data.desiredState == 1 ? "on" : "off")
    if (data.brightness != null) emitDesc("level", data.brightness, "%")
    if (data.desiredState == 1 && shouldKeepAlive()) armKeepAlive()
}

def keepAlive() {
    if (device.currentValue("switch") != "on") return
    def level = (device.currentValue("level") ?: 100) as Integer
    logDebug "keepAlive — re-asserting state:1 bright:${level}"
    applyFloodlight(1, level)
    // Reschedule eagerly; if applyFloodlight fails (camera offline) we still want
    // to keep trying so the light comes back on as soon as the camera returns.
    if (shouldKeepAlive()) {
        runIn(120, "keepAlive")
    }
}

private void armKeepAlive() {
    unschedule("keepAlive")
    runIn(120, "keepAlive")
}

private boolean shouldKeepAlive() {
    return keepLightOn && device.getDataValue("floodlightKeepOnSupported") != "true"
}

// ---- Custom commands ----

// Mode "Off" turns the light off NOW (state:0) AND clears the auto-mode
// (mode:0) so it won't re-trigger from schedule or always-at-night. The
// Reolink app does not expose mode:0.
// CAVEAT: on firmware with per-AI floodlight triggers (wlAiDetectType), those
// triggers are independent of the mode and stay armed — the camera will still
// flash the light on armed detections at night. The handler warns when that's
// the case; see setFloodlightSmartTriggers.
// Other modes only change the auto-behavior; they don't toggle the light off.
def setFloodlightMode(String mode) {
    Integer m = FLOODLIGHT_MODES.indexOf(mode)
    if (m < 0) {
        log.error "invalid floodlight mode '${mode}'"
        return
    }
    def cmds
    if (m == 0) {
        // Read WhiteLed back in the same batch so we can warn about armed triggers
        cmds = [[cmd: "SetWhiteLed", param: [WhiteLed: [channel: 0, state: 0, mode: 0]]],
                        [cmd: "GetWhiteLed", param: [channel: 0]]]
    } else {
        cmds = [[cmd: "SetWhiteLed", param: [WhiteLed: [channel: 0, mode: m]]]]
    }
    sendCmd(cmds, "setFloodlightModeHandler", [
            mode: mode,
            modeNum: m
    ])
}

def setFloodlightModeHandler(result, data) {
    if (!result || result[0]?.code != 0) {
        log.error "setFloodlightMode failed: ${result}"
        return
    }
    emitDesc("floodlightMode", data.mode)
    if (data.modeNum == 0) {
        unschedule("keepAlive")
        emitDesc("switch", "off")
        def types = result.find { it.cmd == "GetWhiteLed" && it.code == 0 }?.value?.WhiteLed?.wlAiDetectType
        if (types != null) {
            emitSmartTriggers(types)
            def armed = types.findAll { it.value == 1 }.collect { it.key }
            if (armed) {
                log.warn "floodlight mode is Off, but AI triggers are still armed (${armed.join(', ')}) — " +
                        "the camera will flash the light on those detections at night. The firmware refuses to disarm " +
                        "ALL of them over the LAN API; use setFloodlightSmartTriggers to minimize (e.g. vehicle only), " +
                        "or the Reolink app to disable every trigger."
            }
        }
    }
}

// Newer firmware arms per-AI floodlight triggers (wlAiDetectType) independently
// of the floodlight mode — factory default is all-off, but once armed (e.g. by
// enabling Smart Mode in the Reolink app) the camera flashes the light on those
// detections at night even with mode Off. This only affects the LIGHT; AI
// detection/reporting itself is separate (GetAiCfg) and untouched here.
// FIRMWARE LIMIT: a write that would leave zero triggers armed is rejected and
// silently re-arms ALL of them, so all-Off is refused client-side; only the
// Reolink app (proprietary protocol) can reach the all-off state.
def setFloodlightSmartTriggers(String people, String vehicle, String animal) {
    List supported = (device.getDataValue("floodlightAiTriggerTypes") ?: "").tokenize(",")
    if (!supported) {
        log.warn "this camera does not report per-AI floodlight triggers (run Refresh first if it should)"
        return
    }
    Map want = [people: people, vehicle: vehicle, dog_cat: animal]
    Map flags = [:]
    supported.each { t ->
        if (want[t] != null) flags[t] = (want[t] == "On") ? 1 : 0
    }
    if (!flags) {
        log.warn "none of the requested trigger types (${want.keySet().join(', ')}) are supported by this camera (${supported.join(', ')})"
        return
    }
    if (flags.every { it.value == 0 }) {
        log.error "the camera firmware refuses to disarm ALL floodlight triggers over the LAN API " +
                "(it silently re-arms every one of them). Leave at least one On — vehicle is usually the " +
                "quietest — or turn them all off in the Reolink app, which can reach that state."
        return
    }
    sendCmd([[cmd: "SetWhiteLed", param: [WhiteLed: [channel: 0, wlAiDetectType: flags]]],
                      [cmd: "GetWhiteLed", param: [channel: 0]]],
            "setFloodlightSmartTriggersHandler", [:])
}

def setFloodlightSmartTriggersHandler(result, data) {
    if (!result || result[0]?.code != 0) {
        log.error "setFloodlightSmartTriggers failed: ${result}"
        return
    }
    def types = result.find { it.cmd == "GetWhiteLed" && it.code == 0 }?.value?.WhiteLed?.wlAiDetectType
    if (types != null) emitSmartTriggers(types)
}

// Publish the armed trigger set and remember which types this camera supports.
private emitSmartTriggers(Map types) {
    device.updateDataValue("floodlightAiTriggerTypes", types.keySet().join(","))
    def armed = types.findAll { it.value == 1 }.collect { it.key }.join(",")
    emitDesc("floodlightSmartTriggers", armed ?: "none")
}

def setLightingSchedule(String startTime, String endTime) {
    def s = parseHm(startTime)
    def e = parseHm(endTime)
    if (s == null || e == null) {
        log.error "setLightingSchedule got unparseable time(s): start='${startTime}' end='${endTime}'"
        return
    }
    def sched = [StartHour: s.hour, StartMin: s.minute, EndHour: e.hour, EndMin: e.minute]
    sendCmd([[cmd: "SetWhiteLed", param: [WhiteLed: [channel: 0, LightingSchedule: sched]]]],
            "setLightingScheduleHandler", [sched: sched])
}

def setLightingScheduleHandler(result, data) {
    if (!result || result[0]?.code != 0) {
        log.error "setLightingSchedule failed: ${result}"
        return
    }
    emitDesc("lightingSchedule", formatSchedule(data.sched))
}

// Accepts either "HH:mm" or an ISO datetime like "1970-01-01T18:00:00.000-0500"
// (Hubitat TIME inputs from rule-machine pass the latter form).
private Map parseHm(String t) {
    if (!t) return null
    def m = t =~ /(\d{1,2}):(\d{2})/
    if (m && m[0]) return [hour: m[0][1] as Integer, minute: m[0][2] as Integer]
    return null
}

def setIRMode(String mode) {
    if (!(mode in ["Auto", "Off"])) { log.error "invalid IR mode"; return }
    sendCmd([[cmd: "SetIrLights", param: [IrLights: [channel: 0, state: mode]]]],
            "setIRModeHandler", [mode: mode])
}

def setIRModeHandler(result, data) {
    applySetResult(result, "IRMode", data.mode, "setIRMode")
}

def setDayNightMode(String mode) {
    if (!(mode in ["Auto", "Color", "Black&White"])) { log.error "invalid day/night mode"; return }
    sendCmd([[cmd: "SetIsp", param: [Isp: [channel: 0, dayNight: mode]]]],
            "setDayNightModeHandler", [mode: mode])
}

def setDayNightModeHandler(result, data) {
    applySetResult(result, "dayNightMode", data.mode, "setDayNightMode")
}

def setMicrophone(String value) {
    if (!(value in ["On", "Off"])) { log.error "invalid microphone state '${value}'"; return }
    Integer v = (value == "On") ? 1 : 0
    sendCmd([[cmd: "SetEnc", param: [Enc: [channel: 0, audio: v]]]],
            "setMicrophoneHandler", [value: value])
}

def setMicrophoneHandler(result, data) {
    applySetResult(result, "microphone", data.value, "setMicrophone")
}

def setPowerLED(String value) {
    if (!(value in ["On", "Off"])) { log.error "invalid powerLED state '${value}'"; return }
    if (device.getDataValue("powerLEDSupported") != "true") {
        log.warn "this camera does not report a controllable power LED"
        return
    }
    sendCmd([[cmd: "SetPowerLed", param: [PowerLed: [channel: 0, state: value]]]],
            "setPowerLEDHandler", [value: value])
}

def setPowerLEDHandler(result, data) {
    applySetResult(result, "powerLED", data.value, "setPowerLED")
}

// Restarts the camera. It is unreachable for a minute or two; for 5 minutes after the
// camera accepts, connection failures are expected and aren't logged as errors.
def reboot() {
    logDebug "reboot()"
    sendCmd([[cmd: "Reboot", param: [:]]], "rebootHandler", [:])
}

def rebootHandler(result, data) {
    if (result == null) return   // no reply: noteCommFailure has logged it
    def r = result instanceof List ? result[0] : null
    if (r?.code == 0) {
        log.info "${device.displayName} rebooting; back in about 1–2 minutes"
        state.rebootStartedAt = now()
        // The camera's session ends with the reboot, so the next request logs in fresh.
        state.token = null
        state.tokenExpiry = 0
        return
    }
    def err = r?.error
    String detail = err ? "${err.detail}, rspCode ${err.rspCode}" : "${result}"
    log.warn "camera refused Reboot (${detail}); the account may need admin rights"
}

private boolean inRebootWindow() {
    Long started = state.rebootStartedAt as Long
    return started != null && now() - started < REBOOT_WINDOW_MS
}

// Shared body of the simple setter callbacks above.
private void applySetResult(result, String attr, value, String label) {
    if (result && result[0]?.code == 0) emitDesc(attr, value)
    else log.error "${label} failed: ${result}"
}

// ---- Refresh + poll ----

void refresh() {
    logDebug "refresh()"
    requestInfo(true)
}

// Every 30 minutes, so SD-card space, IR/day-night, microphone, LED and firmware
// stay current. It leaves out GetWhiteLed: that reply sets switch/level and starts
// keepAlive, which only a command or a manual Refresh may do. A timed refresh could
// catch a NightSmart flash and hold the light on, or catch the light between the
// camera's timeout and keepAlive's re-assert and stop keepAlive.
void scheduledRefresh() {
    requestInfo(false)
}

private void requestInfo(boolean includeWhiteLed) {
    List cmds = [
            [cmd: "GetDevInfo",   param: [:]],
            [cmd: "GetLocalLink", param: [:]],
            [cmd: "GetAbility",   param: [User: [userName: username]]]
    ]
    if (includeWhiteLed) cmds << [cmd: "GetWhiteLed", param: [channel: 0]]
    cmds += [
            [cmd: "GetIrLights",  param: [channel: 0]],
            [cmd: "GetIsp",       param: [channel: 0]],
            [cmd: "GetEnc",       param: [channel: 0]],
            [cmd: "GetRtspUrl",   param: [channel: 0]],
            [cmd: "GetHddInfo",   param: [:]]
    ]
    // Skip the LED query once GetAbility has said there's no LED.
    if (device.getDataValue("powerLEDSupported") != "false") cmds << [cmd: "GetPowerLed", param: [channel: 0]]
    sendCmd(cmds, "refreshHandler", [:])
}

def refreshHandler(result, data) {
    if (!result) return
    result.each { r ->
        if (r.code != 0) {
            // Models without a status LED reject GetPowerLed; that's expected.
            if (r.cmd == "GetPowerLed" && device.getDataValue("powerLEDSupported") != "true") {
                logDebug "GetPowerLed not supported (code ${r.code})"
            } else {
                log.warn "${r.cmd} returned code ${r.code}"
            }
            return
        }
        switch (r.cmd) {
            case "GetDevInfo":
                def d = r.value.DevInfo ?: [:]
                sendEvent(name: "model",           value: d.model   ?: "unknown")
                sendEvent(name: "deviceName",      value: d.name    ?: "unknown")
                sendEvent(name: "firmwareVersion", value: d.firmVer ?: "unknown")
                sendEvent(name: "hardwareVersion", value: d.hardVer ?: "unknown")
                sendEvent(name: "serial",          value: d.serial  ?: "unknown")
                break
            case "GetLocalLink":
                def ll = r.value.LocalLink ?: [:]
                sendEvent(name: "mac",      value: ll.mac ?: "unknown")
                // 'static' is a Groovy reserved word — use subscript form
                sendEvent(name: "cameraIP", value: ll["static"]?.ip ?: ip)
                break
            case "GetAbility":
                def chn = r.value.Ability?.abilityChn?.getAt(0) ?: [:]
                device.updateDataValue("floodlightKeepOnSupported", String.valueOf((chn.supportFLKeepOn?.permit ?: 0) > 0))
                device.updateDataValue("powerLEDSupported",         String.valueOf((chn.powerLed?.permit ?: 0) > 0))
                break
            case "GetWhiteLed":
                def w = r.value.WhiteLed ?: [:]
                emitDesc("switch", w.state == 1 ? "on" : "off")
                emitDesc("level", w.bright != null ? w.bright : 0, "%")
                emitDesc("floodlightMode", floodlightModeName(w.mode))
                if (w.LightingSchedule) emitDesc("lightingSchedule", formatSchedule(w.LightingSchedule))
                if (w.wlAiDetectType != null) emitSmartTriggers(w.wlAiDetectType)
                if (w.state == 1 && shouldKeepAlive()) armKeepAlive()
                break
            case "GetIrLights":
                emitDesc("IRMode", r.value.IrLights?.state ?: "unknown")
                break
            case "GetIsp":
                emitDesc("dayNightMode", r.value.Isp?.dayNight ?: "unknown")
                break
            case "GetEnc":
                def en = r.value.Enc?.audio
                if (en != null) emitDesc("microphone", en == 1 ? "On" : "Off")
                break
            case "GetRtspUrl":
                def u = r.value.rtspUrl ?: [:]
                if (u.mainStream) sendEvent(name: "rtspMainStream", value: u.mainStream)
                if (u.subStream)  sendEvent(name: "rtspSubStream",  value: u.subStream)
                break
            case "GetHddInfo":
                def h = r.value.HddInfo?.getAt(0) ?: [:]
                if (h.capacity != null) sendEvent(name: "sdCardCapacityMB", value: h.capacity)
                if (h.size     != null) sendEvent(name: "sdCardFreeMB",     value: h.size)
                if (h.mount    != null) sendEvent(name: "sdCardMounted",    value: h.mount == 1 ? "true" : "false")
                break
            case "GetPowerLed":
                def s = r.value.PowerLed?.state
                if (s != null) emitDesc("powerLED", s)
                break
        }
    }
}

def pollStatus() {
    Integer myEpoch = (state.pollEpoch ?: 0) as Integer

    // Schedule the next poll first, BEFORE firing the request. That way even if
    // the camera is offline (response never arrives) the loop keeps ticking at
    // its normal cadence — not stalled behind a 10-second HTTP timeout. The
    // meaningful epoch check happens in pollStatusHandler (after the request
    // returns); here in pollStatus, single-threaded execution guarantees the
    // epoch hasn't changed since we read it.
    Integer delay = Math.max(5, ((pollInterval ?: 5) as Integer))
    runIn(delay, "pollStatus")

    sendCmd([
            [cmd: "GetMdState", param: [channel: 0]],
            [cmd: "GetAiState", param: [channel: 0]],
            [cmd: "GetWhiteLed", param: [channel: 0]]
    ], "pollStatusHandler", [epoch: myEpoch])
}

def pollStatusHandler(result, data) {
    // If initialize() bumped the epoch while we were in flight, skip emissions.
    if (((state.pollEpoch ?: 0) as Integer) != (data.epoch as Integer)) return
    if (!result) return

    result.each { r ->
        if (r.code != 0) return
        if (r.cmd == "GetMdState") {
            emitDesc("motion", r.value?.state == 1 ? "active" : "inactive")
        } else if (r.cmd == "GetAiState") {
            def v = r.value ?: [:]
            // Gate each emission on the per-call `support` flag so unsupported AI
            // types don't surface as perpetually-"inactive" clutter.
            if (v.people?.support  == 1) emitDesc("personDetected", v.people.alarm_state  == 1 ? "active" : "inactive")
            if (v.vehicle?.support == 1) emitDesc("vehicleDetected", v.vehicle.alarm_state == 1 ? "active" : "inactive")
            if (v.dog_cat?.support == 1) emitDesc("animalDetected", v.dog_cat.alarm_state == 1 ? "active" : "inactive")
            if (v.face?.support    == 1) emitDesc("faceDetected", v.face.alarm_state    == 1 ? "active" : "inactive")
        } else if (r.cmd == "GetWhiteLed") {
            // Track mode + smart triggers on the poll so changes made in the Reolink
            // app show up in Hubitat within one poll interval. Deliberately does NOT
            // touch switch/level: those interact with the keepAlive re-assert loop
            // and are still owned by commands + refresh().
            def w = r.value?.WhiteLed
            if (w?.wlAiDetectType != null) emitSmartTriggers(w.wlAiDetectType)
            if (w?.mode != null) emitDesc("floodlightMode", floodlightModeName(w.mode))
        }
    }
}

// ---- Auth + HTTP (async) ----

// The camera's API URL, over HTTPS when Use HTTPS is on.
private String apiUrl(String query) {
    return "${settings.useHttps ? 'https' : 'http'}://${ip}/cgi-bin/api.cgi?${query}"
}

// HTTPS requests accept the camera's self-signed certificate.
private Map withTls(Map params) {
    if (settings.useHttps) params.ignoreSSLIssues = true
    return params
}

private boolean hasValidToken() {
    return state.token && state.tokenExpiry && now() < (state.tokenExpiry as Long) - 60_000L
}

// Public entry point for all camera commands.
//   cmds            — list of {cmd, param} maps (action:0 added automatically)
//   successCallback — method name (string) to invoke with (result, userData) on response
//   userData        — opaque map preserved through the request/response round-trip
//
// If the session token is missing/expired we kick off login first, then resume
// this sendCmd from inside loginResponseHandler. singleThreaded serialises the
// handlers, but a login is async: commands and polls that arrive while it's in
// flight would each start their own, so they wait for it instead (see
// queueBehindLogin).
private sendCmd(List cmds, String successCallback, Map userData = [:]) {
    if (!hasValidToken()) {
        if (loginInFlight()) queueBehindLogin(cmds, successCallback, userData)
        else loginAsync(cmds, successCallback, userData)
        return
    }

    def body = cmds.collect { [cmd: it.cmd, action: 0, param: (it.param ?: [:])] }
    String firstCmd = cmds[0].cmd
    def params = withTls([
            uri: apiUrl("cmd=${firstCmd}&token=${state.token}"),
            contentType: "application/json",
            requestContentType: "application/json",
            body: JsonOutput.toJson(body),
            timeout: 10
    ])
    logDebug "POST ${firstCmd}"

    asynchttpPost("sendCmdResponseHandler", params, [
            cmds: cmds,
            successCallback: successCallback,
            userData: userData ?: [:]
    ])
}

// A login counts as in flight for 30 s; the request itself times out at 10 s.
private boolean loginInFlight() {
    Long started = state.loginStartedAt as Long
    return started && now() - started < 30_000L
}

// One login at a time: every login opens a camera session, Reolink caps them, and
// sessions are only freed by Logout or lease expiry. A poll is dropped (the next
// one is seconds away); anything else waits and is sent once the login succeeds.
private void queueBehindLogin(List cmds, String successCallback, Map userData) {
    if (successCallback == "pollStatusHandler") {
        logDebug "login in progress; skipping this poll"
        return
    }
    List q = (state.loginQueue ?: []) as List
    q << [cmds: cmds, successCallback: successCallback, userData: userData ?: [:]]
    state.loginQueue = new ArrayList(q.subList(Math.max(0, q.size() - 10), q.size()))
    logDebug "login in progress; queued ${cmds[0].cmd}"
}

private loginAsync(List queuedCmds = null, String queuedCallback = null, Map queuedData = [:]) {
    state.token = null
    state.loginStartedAt = now()
    def body = [[cmd: "Login", action: 0, param: [User: [Version: "0", userName: username, password: password]]]]
    def params = withTls([
            uri: apiUrl("cmd=Login"),
            contentType: "application/json",
            requestContentType: "application/json",
            body: JsonOutput.toJson(body),
            timeout: 10
    ])
    asynchttpPost("loginResponseHandler", params, [
            queuedCmds: queuedCmds,
            queuedCallback: queuedCallback,
            queuedData: queuedData ?: [:]
    ])
}

def loginResponseHandler(response, data) {
    state.remove("loginStartedAt")
    // An HTTP-level error means the camera never answered, so the credentials
    // were never checked; a rejected login comes back as a JSON error instead.
    if (response?.hasError()) {
        dropLoginQueue()
        noteCommFailure("unreachable", httpErrorDetail(response))
        return
    }
    def parsed = parseAsync(response, "Login")
    if (!parsed) {
        dropLoginQueue()
        noteCommFailure("unreachable", "empty or unreadable reply to Login")
        return
    }
    if (parsed[0]?.code != 0) {
        dropLoginQueue()
        def err = parsed[0]?.error
        noteCommFailure("auth", err ? "${err.detail}, rspCode ${err.rspCode}" : "code ${parsed[0]?.code}")
        return
    }
    noteCommSuccess()
    state.token = parsed[0].value.Token.name
    state.tokenExpiry = now() + ((parsed[0].value.Token.leaseTime as Long) * 1000L)
    logDebug "login OK token=…${lastFour(state.token as String)} leaseTime=${parsed[0].value.Token.leaseTime}s"

    // Resume the command that started this login, then any that queued behind it.
    if (data?.queuedCmds) {
        sendCmd(data.queuedCmds, data.queuedCallback, data.queuedData ?: [:])
    }
    List q = (state.loginQueue ?: []) as List
    state.remove("loginQueue")
    q.each { sendCmd(it.cmds as List, it.successCallback as String, (it.userData ?: [:]) as Map) }
}

// The failure itself is logged by noteCommFailure; queued commands are abandoned.
private void dropLoginQueue() {
    List q = (state.loginQueue ?: []) as List
    if (q) logDebug "login failed; dropping ${q.size()} queued command(s)"
    state.remove("loginQueue")
}

def sendCmdResponseHandler(response, data) {
    def cmds = data.cmds
    def successCallback = data.successCallback
    def userData = (data.userData ?: [:]) as Map
    String firstCmd = cmds[0].cmd

    // While a session token is still valid, polls skip Login entirely, so this
    // is where an outage shows up first.
    if (response?.hasError()) {
        noteCommFailure("unreachable", httpErrorDetail(response))
    } else {
        noteCommSuccess()
    }

    def result = parseAsync(response, firstCmd)

    // rspCode -6 ("please login first") means the camera no longer accepts the token:
    // it expired in flight, or the camera rebooted. Reolink reports it as code 1 with
    // error.rspCode -6, not as code -6. Clear the token and retry once (the retried
    // flag bounds it); a Logout is never retried, since that would log in just to
    // log out.
    if (tokenRejected(result) && !userData.retried && firstCmd != "Logout") {
        logDebug "token expired mid-request, re-auth and retry"
        state.token = null
        Map nextUserData = ([:] + userData)
        nextUserData.retried = true
        sendCmd(cmds, successCallback, nextUserData)
        return
    }

    if (successCallback) {
        "${successCallback}"(result, userData)
    }
}

private boolean tokenRejected(result) {
    if (!(result instanceof List)) return false
    return result.any { r ->
        r instanceof Map && ((r.code as Integer) == -6 || (r.error instanceof Map && (r.error.rspCode as Integer) == -6))
    }
}

// ---- Connection-health logging ----
// The poll loop retries every few seconds, so logging each failure would write
// ~700 identical lines an hour. Instead: one ERROR naming the cause when a
// problem starts, a WARN reminder at most hourly, and one INFO on recovery.
// The dedup key deliberately excludes the error text, which can alternate
// between messages (e.g. connect timeout vs. no route) during one outage.

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
            log.error "camera at ${ip} rejected the login (${detail}) — check the username/password " +
                    "in preferences. Repeats are suppressed; reminder hourly."
        } else {
            log.error "camera at ${ip} isn't answering (${detail}) — it looks offline, off the network, " +
                    "or at a different IP. This is a connection problem, not a username/password problem." +
                    (settings.useHttps ? " HTTPS is on: check the camera has HTTPS enabled, or turn Use HTTPS off." : "") +
                    " Repeats are suppressed; reminder hourly."
        }
    } else if (nowMs - ((state.commFailLastLog ?: 0L) as Long) >= 3_600_000L) {
        state.commFailLastLog = nowMs
        String what = (kind == "auth") ? "still rejecting the login" : "still not answering"
        log.warn "camera at ${ip} ${what} after ${formatDuration(nowMs - (state.commFailSince as Long))} (${detail})"
    }
}

private void noteCommSuccess() {
    if (device.currentValue("healthStatus") != "online") {
        emitEvent(name: "healthStatus", value: "online", descriptionText: "${device.displayName} is online")
    }
    if (state.commFailSince == null) return
    Long rebootAt = state.rebootStartedAt as Long
    if (rebootAt != null) {
        log.info "${device.displayName} is back after ${formatDuration(now() - rebootAt)} (reboot)"
        state.remove("rebootStartedAt")
    } else {
        log.info "camera at ${ip} is responding again after ${formatDuration(now() - (state.commFailSince as Long))}"
    }
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

private parseAsync(response, String label) {
    try {
        if (response?.hasError()) {
            logDebug "${label} HTTP error: ${response.getErrorMessage()}"
            return null
        }
        if (response.status != 200) {
            log.warn "${label} HTTP ${response.status}"
            return null
        }
        def body = response.data
        if (body == null) return null
        if (body instanceof List || body instanceof Map) return body
        if (body instanceof String) return new JsonSlurper().parseText(body)
        return new JsonSlurper().parseText(body.toString())
    } catch (Exception e) {
        log.error "parse failed for ${label}: ${e.message}"
        return null
    }
}

// Plain Java substring: the hub's Groovy has no String.takeRight.
private String lastFour(String s) {
    return s ? s.substring(Math.max(0, s.length() - 4)) : ""
}

private String floodlightModeName(mode) {
    Integer m = mode as Integer
    return (m != null && m >= 0 && m < FLOODLIGHT_MODES.size()) ? FLOODLIGHT_MODES[m] : "Unknown"
}

private String formatSchedule(Map s) {
    return String.format("%02d:%02d-%02d:%02d", s.StartHour, s.StartMin, s.EndHour, s.EndMin)
}

// User-facing attributes carry a descriptionText, logged on change when txtEnable is on.
private void emitDesc(String name, value, String unit = null) {
    String text = (name == "switch") ? "${device.displayName} is ${value}" : "${device.displayName} ${name} is ${value}${unit ?: ''}"
    Map evt = [name: name, value: value, descriptionText: text]
    if (unit) evt.unit = unit
    emitEvent(evt)
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
