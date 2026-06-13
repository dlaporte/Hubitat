/*
 *  Reolink Floodlight Camera
 *
 *  Hubitat driver for Reolink cameras with a WhiteLed (floodlight) — including
 *  Elite Pro Floodlight PoE (F760P), Duo 3 PoE (P750) and similar models.
 *
 *  Exposes: Switch (on/off), SwitchLevel (brightness), MotionSensor (alarm state),
 *  AI detection attributes (person/vehicle/animal/face), floodlight mode, IR mode,
 *  day/night mode, device info, and a self-rescheduling poll loop.
 *
 *  Does NOT expose any siren/alarm-trigger commands by design.
 *
 *  v0.5 - converted all HTTP to asynchttpPost. The sync httpPost calls in the
 *         poll loop were blocking the Hubitat scheduler for up to 10s per
 *         request when the camera was offline; the async pattern eliminates
 *         that. User-visible behavior is identical when the camera is reachable.
 *  v0.4 - added powerLED toggle, RTSP URLs, SD-card health attributes,
 *         and snapshotURL (short-session JPEG endpoint for dashboards).
 *  v0.3 - added microphone enable/disable (SetEnc.audio);
 *         removed AI report toggles (the prefs were only filtering
 *         events driver-side, which was misleading — emission is now
 *         auto-gated on the camera's per-call AI support flags).
 *  v0.2 - UX pass: dropped redundant floodlightModeNum attribute,
 *         renamed cameraIp → cameraIP, shorter keepLightOn label,
 *         renamed flKeepOnSupported → floodlightKeepOnSupported.
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

metadata {

  definition(name: "Reolink Floodlight Camera", namespace: "dlaporte", author: "dlaporte") {
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

    command "setBrightness", [[name: "level", type: "NUMBER",
        description: "Floodlight brightness, 0-100 (0 turns the light off)"]]
    command "setFloodlightMode", [[name: "mode", type: "ENUM",
        description: "Off = fully disabled; other modes control auto-trigger behavior",
        constraints: ["Off", "NightSmart", "AlwaysAtNight", "Schedule"]]]
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
  }

  preferences {
    input("ip", "text", title: "IP Address", required: true)
    input("username", "text", title: "Username", required: true)
    input("password", "password", title: "Password", required: true)
    input("pollInterval", "number", title: "Motion/AI poll interval (seconds)",
        defaultValue: 5, range: "5..300", required: true)
    input("keepLightOn", "bool", title: "Keep floodlight on indefinitely (re-asserts state every 2 min)",
        defaultValue: true)
    input("debug", "bool", title: "Debug logging", defaultValue: false)
  }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

def installed() {
  log.info "Reolink: installed()"
  initialize()
}

def updated() {
  log.info "Reolink: updated()"
  initialize()
}

def initialize() {
  unschedule()
  state.token = null
  state.tokenExpiry = 0
  // v0.2 cleanup: drop legacy state map, renamed/removed attributes, and stale dataValue keys
  state.remove("AISupport")
  state.remove("flKeepOnSupported") // v0.1 stored capability here; v0.2 moved to dataValue
  ["cameraIp", "irMode", "floodlightModeNum", "alarmEnabled"].each {
    try { device.deleteCurrentState(it) } catch (Exception e) { /* older HE without API; ignore */ }
  }
  try { device.removeDataValue("flKeepOnSupported") } catch (Exception e) { /* fine if absent */ }
  // Bump epoch so any in-flight pollStatus from a previous initialize() bows out cleanly
  state.pollEpoch = ((state.pollEpoch ?: 0) as Integer) + 1

  if (!ip || !username || !password) {
    log.error "Reolink: IP, username, and password are required"
    return
  }

  sendEvent(name: "cameraIP", value: ip)
  // Snapshot URL embeds credentials so any Hubitat image tile or dashboard can fetch
  // a live JPEG via short-session auth. Plain HTTP, LAN only — same threat model as the
  // rest of the camera API. Do not screenshot/share unless you trust the audience.
  String snap = "http://${ip}/cgi-bin/api.cgi?cmd=Snap&channel=0&rs=hubitat" +
      "&user=${URLEncoder.encode(username, 'UTF-8')}&password=${URLEncoder.encode(password, 'UTF-8')}"
  sendEvent(name: "snapshotURL", value: snap)

  refresh()
  runIn(1, "pollStatus")
}

def uninstalled() {
  unschedule()
  if (hasValidToken()) {
    // Fire-and-forget logout — no callback needed. Skipped if token is expired
    // so we don't kick off a fresh login just to immediately invalidate it.
    sendCmd([[cmd: "Logout", param: [:]]], null, [:])
  }
}

// ---------------------------------------------------------------------------
// Switch / SwitchLevel
// ---------------------------------------------------------------------------

def on() {
  if (debug) log.debug "Reolink: on()"
  def level = (device.currentValue("level") ?: 100) as Integer
  if (level <= 0) level = 100
  applyFloodlight(1, level)
}

def off() {
  if (debug) log.debug "Reolink: off()"
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
  if (debug) log.debug "Reolink: setBrightness(${level})"
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
    log.error "Reolink: SetWhiteLed failed: ${result}"
    return
  }
  sendEvent(name: "switch", value: data.desiredState == 1 ? "on" : "off")
  if (data.brightness != null) sendEvent(name: "level", value: data.brightness)
  if (data.desiredState == 1 && shouldKeepAlive()) {
    unschedule("keepAlive")
    runIn(120, "keepAlive")
  }
}

def keepAlive() {
  if (device.currentValue("switch") != "on") return
  def level = (device.currentValue("level") ?: 100) as Integer
  if (debug) log.debug "Reolink: keepAlive — re-asserting state:1 bright:${level}"
  applyFloodlight(1, level)
  // Reschedule eagerly; if applyFloodlight fails (camera offline) we still want
  // to keep trying so the light comes back on as soon as the camera returns.
  if (shouldKeepAlive()) {
    runIn(120, "keepAlive")
  }
}

private boolean shouldKeepAlive() {
  return keepLightOn && device.getDataValue("floodlightKeepOnSupported") != "true"
}

// ---------------------------------------------------------------------------
// Custom commands
// ---------------------------------------------------------------------------

// Mode "Off" fully disables the floodlight — turns the light off NOW (state:0)
// AND clears the auto-mode (mode:0) so it won't re-trigger from schedule, smart
// detection, or always-at-night. The Reolink app does not expose mode:0; this
// is the only path to a truly silent floodlight.
// Other modes only change the auto-behavior; they don't toggle the light off.
def setFloodlightMode(String mode) {
  def m = ["Off": 0, "NightSmart": 1, "AlwaysAtNight": 2, "Schedule": 3][mode]
  if (m == null) {
    log.error "Reolink: invalid floodlight mode '${mode}'"
    return
  }
  def param = (m == 0)
      ? [WhiteLed: [channel: 0, state: 0, mode: 0]]
      : [WhiteLed: [channel: 0, mode: m]]
  sendCmd([[cmd: "SetWhiteLed", param: param]], "setFloodlightModeHandler", [
      mode: mode,
      modeNum: m
  ])
}

def setFloodlightModeHandler(result, data) {
  if (!result || result[0]?.code != 0) {
    log.error "Reolink: setFloodlightMode failed: ${result}"
    return
  }
  sendEvent(name: "floodlightMode", value: data.mode)
  if (data.modeNum == 0) {
    unschedule("keepAlive")
    sendEvent(name: "switch", value: "off")
  }
}

def setLightingSchedule(String startTime, String endTime) {
  def s = parseHm(startTime)
  def e = parseHm(endTime)
  if (s == null || e == null) {
    log.error "Reolink: setLightingSchedule got unparseable time(s): start='${startTime}' end='${endTime}'"
    return
  }
  def sched = [StartHour: s.hour, StartMin: s.minute, EndHour: e.hour, EndMin: e.minute]
  sendCmd([[cmd: "SetWhiteLed", param: [WhiteLed: [channel: 0, LightingSchedule: sched]]]],
      "setLightingScheduleHandler", [sched: sched])
}

def setLightingScheduleHandler(result, data) {
  if (!result || result[0]?.code != 0) {
    log.error "Reolink: setLightingSchedule failed: ${result}"
    return
  }
  def s = data.sched
  sendEvent(name: "lightingSchedule",
      value: String.format("%02d:%02d-%02d:%02d", s.StartHour, s.StartMin, s.EndHour, s.EndMin))
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
  if (!(mode in ["Auto", "Off"])) { log.error "Reolink: invalid IR mode"; return }
  sendCmd([[cmd: "SetIrLights", param: [IrLights: [channel: 0, state: mode]]]],
      "setIRModeHandler", [mode: mode])
}

def setIRModeHandler(result, data) {
  if (result && result[0]?.code == 0) sendEvent(name: "IRMode", value: data.mode)
  else log.error "Reolink: setIRMode failed: ${result}"
}

def setDayNightMode(String mode) {
  if (!(mode in ["Auto", "Color", "Black&White"])) { log.error "Reolink: invalid day/night mode"; return }
  sendCmd([[cmd: "SetIsp", param: [Isp: [channel: 0, dayNight: mode]]]],
      "setDayNightModeHandler", [mode: mode])
}

def setDayNightModeHandler(result, data) {
  if (result && result[0]?.code == 0) sendEvent(name: "dayNightMode", value: data.mode)
  else log.error "Reolink: setDayNightMode failed: ${result}"
}

def setMicrophone(String value) {
  if (!(value in ["On", "Off"])) { log.error "Reolink: invalid microphone state '${value}'"; return }
  Integer v = (value == "On") ? 1 : 0
  sendCmd([[cmd: "SetEnc", param: [Enc: [channel: 0, audio: v]]]],
      "setMicrophoneHandler", [value: value])
}

def setMicrophoneHandler(result, data) {
  if (result && result[0]?.code == 0) sendEvent(name: "microphone", value: data.value)
  else log.error "Reolink: setMicrophone failed: ${result}"
}

def setPowerLED(String value) {
  if (!(value in ["On", "Off"])) { log.error "Reolink: invalid powerLED state '${value}'"; return }
  if (device.getDataValue("powerLEDSupported") != "true") {
    log.warn "Reolink: this camera does not report a controllable power LED"
    return
  }
  sendCmd([[cmd: "SetPowerLed", param: [PowerLed: [channel: 0, state: value]]]],
      "setPowerLEDHandler", [value: value])
}

def setPowerLEDHandler(result, data) {
  if (result && result[0]?.code == 0) sendEvent(name: "powerLED", value: data.value)
  else log.error "Reolink: setPowerLED failed: ${result}"
}

// ---------------------------------------------------------------------------
// Refresh + poll
// ---------------------------------------------------------------------------

def refresh() {
  if (debug) log.debug "Reolink: refresh()"
  sendCmd([
      [cmd: "GetDevInfo",   param: [:]],
      [cmd: "GetLocalLink", param: [:]],
      [cmd: "GetAbility",   param: [User: [userName: username]]],
      [cmd: "GetWhiteLed",  param: [channel: 0]],
      [cmd: "GetIrLights",  param: [channel: 0]],
      [cmd: "GetIsp",       param: [channel: 0]],
      [cmd: "GetEnc",       param: [channel: 0]],
      [cmd: "GetRtspUrl",   param: [channel: 0]],
      [cmd: "GetHddInfo",   param: [:]],
      [cmd: "GetPowerLed",  param: [channel: 0]]
  ], "refreshHandler", [:])
}

def refreshHandler(result, data) {
  if (!result) return
  result.each { r ->
    if (r.code != 0) {
      log.warn "Reolink: ${r.cmd} returned code ${r.code}"
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
        sendEvent(name: "switch", value: w.state == 1 ? "on" : "off")
        sendEvent(name: "level",  value: w.bright != null ? w.bright : 0)
        def modeName = (w.mode != null && w.mode >= 0 && w.mode <= 3)
            ? ["Off", "NightSmart", "AlwaysAtNight", "Schedule"][w.mode as Integer]
            : "Unknown"
        sendEvent(name: "floodlightMode", value: modeName)
        if (w.LightingSchedule) {
          def s = w.LightingSchedule
          sendEvent(name: "lightingSchedule",
              value: String.format("%02d:%02d-%02d:%02d", s.StartHour, s.StartMin, s.EndHour, s.EndMin))
        }
        if (w.state == 1 && shouldKeepAlive()) {
          unschedule("keepAlive")
          runIn(120, "keepAlive")
        }
        break
      case "GetIrLights":
        sendEvent(name: "IRMode", value: r.value.IrLights?.state ?: "unknown")
        break
      case "GetIsp":
        sendEvent(name: "dayNightMode", value: r.value.Isp?.dayNight ?: "unknown")
        break
      case "GetEnc":
        def en = r.value.Enc?.audio
        if (en != null) sendEvent(name: "microphone", value: en == 1 ? "On" : "Off")
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
        if (s != null) sendEvent(name: "powerLED", value: s)
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
      [cmd: "GetAiState", param: [channel: 0]]
  ], "pollStatusHandler", [epoch: myEpoch])
}

def pollStatusHandler(result, data) {
  // If initialize() bumped the epoch while we were in flight, skip emissions.
  if (((state.pollEpoch ?: 0) as Integer) != (data.epoch as Integer)) return
  if (!result) return

  result.each { r ->
    if (r.code != 0) return
    if (r.cmd == "GetMdState") {
      sendEvent(name: "motion", value: r.value?.state == 1 ? "active" : "inactive")
    } else if (r.cmd == "GetAiState") {
      def v = r.value ?: [:]
      // Gate each emission on the per-call `support` flag so unsupported AI
      // types don't surface as perpetually-"inactive" clutter.
      if (v.people?.support  == 1) sendEvent(name: "personDetected",  value: v.people.alarm_state  == 1 ? "active" : "inactive")
      if (v.vehicle?.support == 1) sendEvent(name: "vehicleDetected", value: v.vehicle.alarm_state == 1 ? "active" : "inactive")
      if (v.dog_cat?.support == 1) sendEvent(name: "animalDetected",  value: v.dog_cat.alarm_state == 1 ? "active" : "inactive")
      if (v.face?.support    == 1) sendEvent(name: "faceDetected",    value: v.face.alarm_state    == 1 ? "active" : "inactive")
    }
  }
}

// ---------------------------------------------------------------------------
// Auth + HTTP (async)
// ---------------------------------------------------------------------------

private boolean hasValidToken() {
  return state.token && state.tokenExpiry && now() < (state.tokenExpiry as Long) - 60_000L
}

// Public entry point for all camera commands.
//   cmds            — list of {cmd, param} maps (action:0 added automatically)
//   successCallback — method name (string) to invoke with (result, userData) on response
//   userData        — opaque map preserved through the request/response round-trip
//
// If the session token is missing/expired we kick off login first, then resume
// this sendCmd from inside loginResponseHandler. Pattern is async-safe — Hubitat
// serializes per-device execution, so there's no concurrent-login race.
private sendCmd(List cmds, String successCallback, Map userData = [:]) {
  if (!hasValidToken()) {
    loginAsync(cmds, successCallback, userData)
    return
  }

  def body = cmds.collect { [cmd: it.cmd, action: 0, param: (it.param ?: [:])] }
  String firstCmd = cmds[0].cmd
  def params = [
      uri: "http://${ip}/cgi-bin/api.cgi?cmd=${firstCmd}&token=${state.token}",
      contentType: "application/json",
      requestContentType: "application/json",
      body: JsonOutput.toJson(body),
      timeout: 10
  ]
  if (debug) log.debug "Reolink: POST ${firstCmd}"

  asynchttpPost("sendCmdResponseHandler", params, [
      cmds: cmds,
      successCallback: successCallback,
      userData: userData ?: [:]
  ])
}

private loginAsync(List queuedCmds = null, String queuedCallback = null, Map queuedData = [:]) {
  state.token = null
  def body = [[cmd: "Login", action: 0, param: [User: [Version: "0", userName: username, password: password]]]]
  def params = [
      uri: "http://${ip}/cgi-bin/api.cgi?cmd=Login",
      contentType: "application/json",
      requestContentType: "application/json",
      body: JsonOutput.toJson(body),
      timeout: 10
  ]
  asynchttpPost("loginResponseHandler", params, [
      queuedCmds: queuedCmds,
      queuedCallback: queuedCallback,
      queuedData: queuedData ?: [:]
  ])
}

def loginResponseHandler(response, data) {
  def parsed = parseAsync(response, "Login")
  if (!parsed || parsed[0]?.code != 0) {
    log.error "Reolink: login failed: ${parsed}"
    return
  }
  state.token = parsed[0].value.Token.name
  state.tokenExpiry = now() + ((parsed[0].value.Token.leaseTime as Long) * 1000L)
  if (debug) log.debug "Reolink: login OK token=…${state.token?.takeRight(4)} leaseTime=${parsed[0].value.Token.leaseTime}s"

  // Resume the command that was waiting on this login.
  if (data?.queuedCmds) {
    sendCmd(data.queuedCmds, data.queuedCallback, data.queuedData ?: [:])
  }
}

def sendCmdResponseHandler(response, data) {
  def cmds = data.cmds
  def successCallback = data.successCallback
  def userData = (data.userData ?: [:]) as Map
  String firstCmd = cmds[0].cmd

  def result = parseAsync(response, firstCmd)

  // code:-6 means token expired between hasValidToken() and the actual request
  // landing. Clear it and retry once. Use a flag on userData to bound recursion.
  if (result?.any { (it?.code as Integer) == -6 } && !userData.retried) {
    if (debug) log.debug "Reolink: token expired mid-request, re-auth and retry"
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

private parseAsync(response, String label) {
  try {
    if (response?.hasError()) {
      if (debug) log.debug "Reolink: ${label} HTTP error: ${response.getErrorMessage()}"
      return null
    }
    if (response.status != 200) {
      log.warn "Reolink: ${label} HTTP ${response.status}"
      return null
    }
    def body = response.data
    if (body == null) return null
    if (body instanceof List || body instanceof Map) return body
    if (body instanceof String) return new JsonSlurper().parseText(body)
    return new JsonSlurper().parseText(body.toString())
  } catch (Exception e) {
    log.error "Reolink: parse failed for ${label}: ${e.message}"
    return null
  }
}
