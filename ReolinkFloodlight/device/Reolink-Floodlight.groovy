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
    attribute "cameraIp", "string"

    attribute "floodlightMode", "string"
    attribute "floodlightModeNum", "number"
    attribute "lightingSchedule", "string"
    attribute "irMode", "string"
    attribute "dayNightMode", "string"

    attribute "personDetected", "string"
    attribute "vehicleDetected", "string"
    attribute "animalDetected", "string"
    attribute "faceDetected", "string"

    command "setFloodlightMode", [[name: "mode", type: "ENUM", description: "Floodlight auto-mode",
        constraints: ["Off", "NightSmart", "AlwaysAtNight", "Schedule"]]]
    command "setLightingSchedule", [
        [name: "startHour", type: "NUMBER"],
        [name: "startMinute", type: "NUMBER"],
        [name: "endHour", type: "NUMBER"],
        [name: "endMinute", type: "NUMBER"]
    ]
    command "setIrMode", [[name: "mode", type: "ENUM", constraints: ["Auto", "Off"]]]
    command "setDayNightMode", [[name: "mode", type: "ENUM", constraints: ["Auto", "Color", "Black&White"]]]
    command "disableFloodlight"
  }

  preferences {
    input("ip", "text", title: "IP Address", required: true)
    input("username", "text", title: "Username", required: true)
    input("password", "password", title: "Password", required: true)
    input("pollInterval", "number", title: "Motion/AI poll interval (seconds)",
        defaultValue: 5, range: "5..300", required: true)
    input("keepLightOn", "bool", title: "Refresh floodlight every 2 minutes while on (works around firmware 3-min auto-off)",
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
  state.aiSupport = [:]
  // Bump epoch so any in-flight pollStatus from a previous initialize() bows out cleanly
  state.pollEpoch = ((state.pollEpoch ?: 0) as Integer) + 1

  if (!ip || !username || !password) {
    log.error "Reolink: IP, username, and password are required"
    return
  }

  sendEvent(name: "cameraIp", value: ip)
  refresh()
  runIn(1, "pollStatus")
}

def uninstalled() {
  unschedule()
  if (state.token) {
    try { sendCmd([[cmd: "Logout", param: [:]]]) } catch (Exception e) { /* best-effort */ }
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

def setLevel(level, duration = null) {
  if (debug) log.debug "Reolink: setLevel(${level})"
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

  def resp = sendCmd([[cmd: "SetWhiteLed", param: param]])
  if (resp && resp[0]?.code == 0) {
    sendEvent(name: "switch", value: desiredState == 1 ? "on" : "off")
    if (brightness != null) sendEvent(name: "level", value: brightness)
    if (desiredState == 1 && shouldKeepAlive()) {
      unschedule("keepAlive")
      runIn(120, "keepAlive")
    }
  } else {
    log.error "Reolink: SetWhiteLed failed: ${resp}"
  }
}

def keepAlive() {
  if (device.currentValue("switch") != "on") return
  def level = (device.currentValue("level") ?: 100) as Integer
  if (debug) log.debug "Reolink: keepAlive — re-asserting state:1 bright:${level}"
  applyFloodlight(1, level)
  // Always reschedule if we're still meant to be on, so a transient HTTP failure
  // inside applyFloodlight doesn't terminate the keep-alive loop.
  if (device.currentValue("switch") == "on" && shouldKeepAlive()) {
    runIn(120, "keepAlive")
  }
}

private boolean shouldKeepAlive() {
  return keepLightOn && !state.flKeepOnSupported
}

// ---------------------------------------------------------------------------
// Custom commands
// ---------------------------------------------------------------------------

def setFloodlightMode(String mode) {
  def m = ["Off": 0, "NightSmart": 1, "AlwaysAtNight": 2, "Schedule": 3][mode]
  if (m == null) {
    log.error "Reolink: invalid floodlight mode '${mode}'"
    return
  }
  def resp = sendCmd([[cmd: "SetWhiteLed", param: [WhiteLed: [channel: 0, mode: m]]]])
  if (resp && resp[0]?.code == 0) {
    sendEvent(name: "floodlightMode", value: mode)
    sendEvent(name: "floodlightModeNum", value: m)
  } else {
    log.error "Reolink: setFloodlightMode failed: ${resp}"
  }
}

def setLightingSchedule(startHour, startMinute, endHour, endMinute) {
  def sched = [
      StartHour: startHour as Integer, StartMin: startMinute as Integer,
      EndHour: endHour as Integer,     EndMin: endMinute as Integer
  ]
  def resp = sendCmd([[cmd: "SetWhiteLed", param: [WhiteLed: [channel: 0, LightingSchedule: sched]]]])
  if (resp && resp[0]?.code == 0) {
    sendEvent(name: "lightingSchedule",
        value: String.format("%02d:%02d-%02d:%02d", sched.StartHour, sched.StartMin, sched.EndHour, sched.EndMin))
  } else {
    log.error "Reolink: setLightingSchedule failed: ${resp}"
  }
}

def setIrMode(String mode) {
  if (!(mode in ["Auto", "Off"])) { log.error "Reolink: invalid IR mode"; return }
  def resp = sendCmd([[cmd: "SetIrLights", param: [IrLights: [channel: 0, state: mode]]]])
  if (resp && resp[0]?.code == 0) sendEvent(name: "irMode", value: mode)
}

def setDayNightMode(String mode) {
  if (!(mode in ["Auto", "Color", "Black&White"])) { log.error "Reolink: invalid day/night mode"; return }
  def resp = sendCmd([[cmd: "SetIsp", param: [Isp: [channel: 0, dayNight: mode]]]])
  if (resp && resp[0]?.code == 0) sendEvent(name: "dayNightMode", value: mode)
}

// Fully disable the floodlight: turns the light off now AND clears the auto-mode
// so it won't re-trigger at night, on schedule, or on AI detection. The Reolink
// app does not expose mode:0, so this is the only path to a truly silent floodlight.
def disableFloodlight() {
  if (debug) log.debug "Reolink: disableFloodlight()"
  unschedule("keepAlive")
  def resp = sendCmd([[cmd: "SetWhiteLed", param: [WhiteLed: [channel: 0, state: 0, mode: 0]]]])
  if (resp && resp[0]?.code == 0) {
    sendEvent(name: "switch",            value: "off")
    sendEvent(name: "floodlightMode",    value: "Off")
    sendEvent(name: "floodlightModeNum", value: 0)
  } else {
    log.error "Reolink: disableFloodlight failed: ${resp}"
  }
}

// ---------------------------------------------------------------------------
// Refresh + poll
// ---------------------------------------------------------------------------

def refresh() {
  if (debug) log.debug "Reolink: refresh()"
  def resp = sendCmd([
      [cmd: "GetDevInfo",   param: [:]],
      [cmd: "GetLocalLink", param: [:]],
      [cmd: "GetAbility",   param: [User: [userName: username]]],
      [cmd: "GetWhiteLed",  param: [channel: 0]],
      [cmd: "GetIrLights",  param: [channel: 0]],
      [cmd: "GetIsp",       param: [channel: 0]]
  ])
  if (!resp) return

  resp.each { r ->
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
        sendEvent(name: "cameraIp", value: ll["static"]?.ip ?: ip)
        break
      case "GetAbility":
        def chn = r.value.Ability.abilityChn?.getAt(0) ?: [:]
        state.aiSupport = [
            people:  (chn.supportAiPeople?.permit ?: 0)  > 0,
            vehicle: (chn.supportAiVehicle?.permit ?: 0) > 0,
            animal:  ((chn.supportAiDogCat?.permit ?: 0) > 0) || ((chn.supportAiAnimal?.permit ?: 0) > 0),
            face:    (chn.supportAiFace?.permit ?: 0)    > 0
        ]
        state.flKeepOnSupported = (chn.supportFLKeepOn?.permit ?: 0) > 0
        break
      case "GetWhiteLed":
        def w = r.value.WhiteLed ?: [:]
        sendEvent(name: "switch",            value: w.state == 1 ? "on" : "off")
        sendEvent(name: "level",             value: w.bright != null ? w.bright : 0)
        sendEvent(name: "floodlightModeNum", value: w.mode != null ? w.mode : 0)
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
        sendEvent(name: "irMode", value: r.value.IrLights?.state ?: "unknown")
        break
      case "GetIsp":
        sendEvent(name: "dayNightMode", value: r.value.Isp?.dayNight ?: "unknown")
        break
    }
  }
}

def pollStatus() {
  // Capture epoch at start; if initialize() runs while this poll is mid-flight
  // it bumps state.pollEpoch and we bow out instead of double-scheduling.
  def myEpoch = state.pollEpoch

  def cmds = [
      [cmd: "GetMdState", param: [channel: 0]],
      [cmd: "GetAiState", param: [channel: 0]]
  ]
  def resp = sendCmd(cmds)
  if (resp) {
    resp.each { r ->
      if (r.code != 0) return
      if (r.cmd == "GetMdState") {
        sendEvent(name: "motion", value: r.value?.state == 1 ? "active" : "inactive")
      } else if (r.cmd == "GetAiState") {
        def v = r.value ?: [:]
        if (state.aiSupport?.people)  sendEvent(name: "personDetected",  value: v.people?.alarm_state  == 1 ? "active" : "inactive")
        if (state.aiSupport?.vehicle) sendEvent(name: "vehicleDetected", value: v.vehicle?.alarm_state == 1 ? "active" : "inactive")
        if (state.aiSupport?.animal)  sendEvent(name: "animalDetected",  value: v.dog_cat?.alarm_state == 1 ? "active" : "inactive")
        if (state.aiSupport?.face)    sendEvent(name: "faceDetected",    value: v.face?.alarm_state    == 1 ? "active" : "inactive")
      }
    }
  }

  if (state.pollEpoch == myEpoch) {
    Integer delay = Math.max(5, ((pollInterval ?: 5) as Integer))
    runIn(delay, "pollStatus")
  }
}

// ---------------------------------------------------------------------------
// Auth + HTTP
// ---------------------------------------------------------------------------

private boolean ensureToken() {
  if (state.token && state.tokenExpiry && now() < (state.tokenExpiry as Long) - 60_000L) return true
  return login()
}

private boolean login() {
  state.token = null
  def body = [[cmd: "Login", action: 0, param: [User: [Version: "0", userName: username, password: password]]]]
  def params = [
      uri: "http://${ip}/cgi-bin/api.cgi?cmd=Login",
      contentType: "application/json",
      requestContentType: "application/json",
      body: JsonOutput.toJson(body),
      timeout: 10
  ]
  def ok = false
  try {
    httpPost(params) { resp ->
      def data = parseBody(resp.data)
      if (data && data[0]?.code == 0) {
        state.token = data[0].value.Token.name
        state.tokenExpiry = now() + ((data[0].value.Token.leaseTime as Long) * 1000L)
        if (debug) log.debug "Reolink: login OK token=${state.token} leaseTime=${data[0].value.Token.leaseTime}s"
        ok = true
      } else {
        log.error "Reolink: login failed: ${data}"
      }
    }
  } catch (Exception e) {
    log.error "Reolink: login exception: ${e.message}"
  }
  return ok
}

private List sendCmd(List cmds, boolean retried = false) {
  if (!ensureToken()) return null

  def body = cmds.collect { [cmd: it.cmd, action: 0, param: (it.param ?: [:])] }
  def firstCmd = cmds[0].cmd
  def params = [
      uri: "http://${ip}/cgi-bin/api.cgi?cmd=${firstCmd}&token=${state.token}",
      contentType: "application/json",
      requestContentType: "application/json",
      body: JsonOutput.toJson(body),
      timeout: 10
  ]
  if (debug) log.debug "Reolink: POST ${firstCmd} body=${params.body}"

  def result = null
  try {
    httpPost(params) { resp ->
      result = parseBody(resp.data)
      if (debug) log.debug "Reolink: <- ${firstCmd}: ${result}"
    }
  } catch (Exception e) {
    log.error "Reolink: sendCmd(${firstCmd}) exception: ${e.message}"
    return null
  }

  if (result?.any { (it?.code as Integer) == -6 } && !retried) {
    if (debug) log.debug "Reolink: token expired, re-auth and retry"
    state.token = null
    return sendCmd(cmds, true)
  }
  return result
}

private parseBody(data) {
  if (data == null) return null
  if (data instanceof List || data instanceof Map) return data
  if (data instanceof String) return new JsonSlurper().parseText(data)
  try { return new JsonSlurper().parseText(data.toString()) } catch (Exception e) { return null }
}
