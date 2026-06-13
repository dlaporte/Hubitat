/**
 * AcuRite Weather Station
 *
 *  David LaPorte
 *  Based on this helpful thread:
 *    https://community.smartthings.com/t/my-very-quick-and-dirty-integration-with-myacurite-smart-hub-st-webcore/97749
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Last Update 2026-06-12
 *
 *  v0.0.14 - swapped Polling capability for Refresh (modern Hubitat
 *            convention); poll() retained as a backward-compat alias.
 *  v0.0.13 - dropped poll_schedule() wrapper; runEvery uses "poll" directly.
 *  v0.0.12 - restored measured_light attribute; weatherSummary temp now
 *            renders with degree symbol.
 *  v0.0.11 - converted login + data fetch to asynchttpPost / asynchttpGet,
 *            so a poll cycle no longer blocks the scheduler when myacurite
 *            is slow or unreachable. Added derived `lightningActive` boolean
 *            (active when lightning_strike_count rose in the last N minutes;
 *            window configurable) and `weatherSummary` string (composed from
 *            temp + humidity + wind for dashboard tiles and TTS).
 *  v0.0.10 - cache the myacurite session token across polls instead of
 *            re-logging every 5 minutes (was ~288 logins/day). Dropped
 *            duplicate snake_case attributes (wind_direction, wind_speed,
 *            uv_index — the camelCase forms windDirection/windSpeed/
 *            ultravioletIndex are the canonical Hubitat names). Dropped
 *            unused measured_light attribute. Initialize() now cleans up
 *            legacy attribute names so upgraders don't see phantoms.
 *  v0.0.9 - suppressed password in debug logs
 *  v0.0.8 - added UltravioletIndex capability
 *  v0.0.7 - added windDirection (you're a machine, chad.andrews)
 *  v0.0.6 - added windSpeed and attributes (thanks again, chad.andrews)
 *  v0.0.5 - added debug option
 *  v0.0.4 - fixed scheduler (thanks chad.andrews)
 *  v0.0.3 - fixed typo
 *  v0.0.2 - adding polling interval, removed assumptions about sensor counts
 *  v0.0.1 - initial release
 *
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

metadata {
  definition(name: "AcuRite Weather Station", namespace: "dlaporte", author: "David LaPorte") {
    capability "Sensor"
    capability "Temperature Measurement"
    capability "Pressure Measurement"
    capability "Illuminance Measurement"
    capability "Relative Humidity Measurement"
    capability "UltravioletIndex"
    capability "Initialize"
    capability "Refresh"
    capability "Battery"

    attribute "location_name", "string"
    attribute "location_latitude", "string"
    attribute "location_longitude", "string"
    attribute "location_elevation", "decimal"
    attribute "location_timezone", "string"
    attribute "device_country", "string"

    attribute "device_name", "string"
    attribute "device_model", "string"
    attribute "device_status", "string"

    attribute "device_battery_level", "string"
    attribute "battery", "number"

    attribute "device_signal_strength", "number"
    attribute "device_last_checkin", "string"

    attribute "lightning_last_strike_distance", "number"
    attribute "lightning_closest_strike_distance", "number"
    attribute "lightning_strike_count", "number"
    attribute "interference", "number"
    attribute "measured_light", "number"
    attribute "light_intensity", "number"
    attribute "illuminance", "number"
    attribute "ultravioletIndex", "number"
    attribute "wind_speed_average", "number"
    attribute "rainfall", "decimal"
    attribute "pressure", "decimal"
    attribute "wind_chill", "decimal"
    attribute "dew_point", "number"
    attribute "wind_direction_abbreviation", "string"
    attribute "wind_direction_point", "string"
    attribute "windDirection", "string"
    attribute "windSpeed", "number"
    attribute "humidity", "number"
    attribute "temperature", "decimal"

    // v0.0.11 derived
    attribute "lightningActive", "string"  // "true"/"false"
    attribute "lastStrikeAt", "string"     // ISO timestamp of most recent strike count increase
    attribute "weatherSummary", "string"   // composed dashboard string
  }

  preferences() {
    section("Query Inputs") {
      input "acurite_username", "text", required: true, title: "AcuRite Username"
      input "acurite_password", "password", required: true, title: "AcuRite Password"
      input "device_id", "text", required: true, title: "Device ID", description: "Your Device ID can be found looking for 'hubs' in the Network section of Chrome's Developer Tools while loading the MyAcurite dashboard"
      input "poll_interval", "enum", title: "Poll Interval:", required: false, defaultValue: "5 Minutes", options: ["5 Minutes", "10 Minutes", "15 Minutes", "30 Minutes", "1 Hour", "3 Hours"]
      input "lightningWindowMinutes", "number", title: "Lightning-active window (minutes since last strike)",
          required: false, defaultValue: 10, range: "1..120"
      input "debug", "bool", required: false, defaultValue: false, title: "Debug Logging"
    }
  }
}

def installed() {
  log.info "AcuRite: installed()"
  initialize()
}

def updated() {
  log.info "AcuRite: updated()"
  initialize()
}

def refresh() {
  if (debug) log.info "AcuRite: refresh() called"
  get_acurite_data()
}

// Backward-compat alias — kept so rules that called poll() under the
// old Polling capability keep working.
def poll() { refresh() }

def initialize() {
  unschedule()
  if (debug) log.debug "AcuRite: initialize() called"

  // Drop attribute names removed/renamed in earlier versions so upgraders
  // don't see phantom entries in the Current States panel. measured_light
  // was erroneously removed in v0.0.10 and restored in v0.0.12 — no longer
  // listed here.
  ["wind_direction", "wind_speed", "uv_index"].each {
    try { device.deleteCurrentState(it) } catch (Exception e) { /* older HE without API; ignore */ }
  }

  if (!acurite_username || !acurite_password || !device_id) {
    log.error "AcuRite: required fields not completed.  Please complete for proper operation."
    return
  }
  def poll_interval_cmd = (settings?.poll_interval ?: "5 Minutes").replace(" ", "")
  "runEvery${poll_interval_cmd}"("refresh")
  if (debug) log.debug "AcuRite: scheduling as runEvery" + poll_interval_cmd
}

// ---------------------------------------------------------------------------
// HTTP — async with the same try-cached-then-relogin retry behavior as v0.0.10
// ---------------------------------------------------------------------------

def get_acurite_data() {
  if (state.acurite_token && state.acurite_account_id) {
    fetch_acurite_data(false)
  } else {
    acurite_login(true)
  }
}

private fetch_acurite_data(boolean alreadyRetried) {
  def params = [
    uri: "https://marapi.myacurite.com/accounts/${state.acurite_account_id}/dashboard/hubs/${device_id}",
    headers: ["x-one-vue-token": state.acurite_token],
    timeout: 15
  ]
  if (debug) log.debug "AcuRite: fetch_acurite_data params: " + params
  asynchttpGet("fetchAcuriteDataHandler", params, [retried: alreadyRetried])
}

def fetchAcuriteDataHandler(response, data) {
  try {
    if (response.hasError() || response.status == 401) {
      // Token rejected — clear it and re-login once.
      if (debug) log.debug "AcuRite: data fetch 401/error, will re-login. retried=${data.retried}"
      state.acurite_token = null
      state.acurite_token_expiry = 0
      if (!data.retried) {
        acurite_login(true)
      } else {
        log.warn "AcuRite: data fetch failed even after re-login"
      }
      return
    }
    if (response.status != 200) {
      log.error "AcuRite: data failed: HTTP ${response.status}: ${response.data}"
      return
    }
    def parsed = response.json
    if (parsed == null) parsed = new JsonSlurper().parseText(response.data.toString())
    process_acurite_data(parsed)
  } catch (Exception e) {
    log.error "AcuRite: fetchAcuriteDataHandler exception: ${e.message}"
  }
}

private acurite_login(boolean fetchAfter) {
  def body = JsonOutput.toJson([
      remember: true,
      email: acurite_username,
      password: acurite_password
  ])
  def params = [
      uri: "https://marapi.myacurite.com/users/login",
      contentType: "application/json",
      requestContentType: "application/json",
      body: body,
      timeout: 15
  ]
  asynchttpPost("loginHandler", params, [fetchAfter: fetchAfter])
}

def loginHandler(response, data) {
  try {
    if (response.hasError() || response.status != 200) {
      log.error "AcuRite: login failed: HTTP ${response.status}"
      return
    }
    def parsed = response.json
    if (parsed == null) parsed = new JsonSlurper().parseText(response.data.toString())
    state.acurite_token = parsed.token_id
    state.acurite_account_id = parsed.user?.account_users?.getAt(0)?.account_id
    state.acurite_token_expiry = now() + (60 * 60 * 1000)
    if (debug) log.debug "AcuRite: login OK, token cached for 1h"

    if (data?.fetchAfter && state.acurite_account_id) {
      fetch_acurite_data(true)
    }
  } catch (Exception e) {
    log.error "AcuRite: loginHandler exception: ${e.message}"
  }
}

// ---------------------------------------------------------------------------
// Parsing + derived attributes
// ---------------------------------------------------------------------------

private void process_acurite_data(data) {
  sendEvent(name: "location_name", value: data.name)
  sendEvent(name: "location_latitude", value: data.latitude)
  sendEvent(name: "location_longitude", value: data.longitude)
  sendEvent(name: "location_elevation", value: data.elevation, unit: data.elevation_unit)
  sendEvent(name: "location_timezone", value: data.timezone)
  sendEvent(name: "device_country", value: data.country)

  def dev = data.devices ? data.devices[0] : null
  if (!dev) {
    log.warn "AcuRite: response had no devices"
    return
  }

  sendEvent(name: "device_name", value: dev.name)
  sendEvent(name: "device_model", value: dev.model_code)
  sendEvent(name: "device_status", value: dev.status_code)
  sendEvent(name: "device_battery_level", value: dev.battery_level)
  sendEvent(name: "battery", value: dev.battery_level == "Normal" ? 100 : 0)
  sendEvent(name: "device_signal_strength", value: dev.signal_strength)
  sendEvent(name: "device_last_checkin", value: dev.last_check_in_at)

  // Sensor names from the API come in like "Wind Speed" → "wind_speed". A few
  // sensors get remapped to canonical Hubitat attribute names. Others emit
  // under their snake_case API name (e.g. dew_point, wind_chill, rainfall).
  def rename = [
      "wind_speed":     "windSpeed",
      "wind_direction": "windDirection",
      "uv_index":       "ultravioletIndex"
  ]

  // Cache values for the weather-summary composition below.
  Map snap = [:]

  for (sensor in [dev.sensors, dev.wired_sensors].flatten()) {
    def sensor_name = sensor.sensor_name.replaceAll(" ", "_").toLowerCase()
    def attr_name = rename[sensor_name] ?: sensor_name
    def sensor_value = sensor.last_reading_value
    def sensor_unit = sensor.chart_unit
    if (sensor_unit) {
      sendEvent(name: attr_name, value: sensor_value, unit: sensor_unit)
    } else {
      sendEvent(name: attr_name, value: sensor_value)
    }
    snap[sensor_name] = [value: sensor_value, unit: sensor_unit]

    if (sensor_name == "wind_direction") {
      sendEvent(name: "wind_direction_abbreviation", value: sensor.wind_direction?.abbreviation)
      sendEvent(name: "wind_direction_point", value: sensor.wind_direction?.point)
      snap["wind_direction_abbreviation"] = [value: sensor.wind_direction?.abbreviation]
    }
    if (sensor_name == "light_intensity") {
      sendEvent(name: "illuminance", value: sensor_value, unit: sensor_unit)
    }
  }

  // Derived: lightningActive
  // If strike count increased since last poll, stamp lastStrikeAt = now.
  // If lastStrikeAt was within the configured window, lightningActive = true.
  def currStrikes = snap["lightning_strike_count"]?.value
  if (currStrikes != null) {
    Long currStrikesLong = (currStrikes as Long)
    Long prevStrikes = (state.acurite_prev_strikes ?: 0L) as Long
    if (currStrikesLong > prevStrikes) {
      String ts = new Date().format("yyyy-MM-dd'T'HH:mm:ssZ", location.timeZone ?: TimeZone.getDefault())
      state.acurite_last_strike_ms = now()
      sendEvent(name: "lastStrikeAt", value: ts)
    }
    state.acurite_prev_strikes = currStrikesLong

    Integer windowMin = (settings.lightningWindowMinutes ?: 10) as Integer
    Long cutoff = now() - (windowMin * 60L * 1000L)
    boolean active = (state.acurite_last_strike_ms ?: 0L) >= cutoff
    sendEvent(name: "lightningActive", value: active ? "true" : "false")
  }

  // Derived: weatherSummary  ("72°F, 45% RH, wind SW 8 mph")
  // AcuRite returns temperature unit as bare "F"/"C"; we prepend ° for display.
  def parts = []
  if (snap.temperature?.value != null) {
    def tu = snap.temperature.unit
    String tempPart = "${snap.temperature.value}"
    if (tu in ["F", "C"]) tempPart += "°${tu}"
    else if (tu) tempPart += tu
    else tempPart += "°"
    parts << tempPart
  }
  if (snap.humidity?.value != null) {
    parts << "${snap.humidity.value}% RH"
  }
  String dir = snap["wind_direction_abbreviation"]?.value
  def speed = snap.wind_speed?.value
  if (speed != null) {
    parts << (dir ? "wind ${dir} ${speed} ${snap.wind_speed.unit ?: ''}".trim()
                  : "wind ${speed} ${snap.wind_speed.unit ?: ''}".trim())
  }
  if (parts) sendEvent(name: "weatherSummary", value: parts.join(", "))
}
