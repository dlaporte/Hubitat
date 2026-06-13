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

metadata {
  definition(name: "AcuRite Weather Station", namespace: "dlaporte", author: "David LaPorte") {
    capability "Actuator"
    capability "Sensor"
    capability "Temperature Measurement"
    capability "Pressure Measurement"
    capability "Illuminance Measurement"
    capability "Relative Humidity Measurement"
    capability "UltravioletIndex"
    capability "Initialize"
    capability "Polling"
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

  }

  preferences() {
    section("Query Inputs") {
      input "acurite_username", "text", required: true, title: "AcuRite Username"
      input "acurite_password", "password", required: true, title: "AcuRite Password"
      input "device_id", "text", required: true, title: "Device ID", description: "Your Device ID can be found looking for 'hubs' in the Network section of Chrome's Developer Tools while loading the MyAcurite dashboard"
      input "poll_interval", "enum", title: "Poll Interval:", required: false, defaultValue: "5 Minutes", options: ["5 Minutes", "10 Minutes", "15 Minutes", "30 Minutes", "1 Hour", "3 Hours"]
      input "debug", "bool", required: true, defaultValue: false, title: "Debug Logging"
    }
  }
}

def poll() {
  if (debug) log.info "AcuRite: poll() called"
  get_acurite_data()
}

def get_acurite_data() {
  // Try the data fetch with whatever token we have cached. If it fails on
  // 401 (or there's no token), log in and try once more. This is the cache —
  // previously the driver was hitting /users/login on every single poll,
  // which at 5-minute intervals is ~288 logins/day per device.
  if (!fetch_acurite_data()) {
    if (acurite_login() && !fetch_acurite_data()) {
      log.warn "AcuRite: data fetch failed even after re-login"
    }
  }
}

private boolean fetch_acurite_data() {
  if (!state.acurite_token || !state.acurite_account_id) return false

  def data_params = [
    uri: "https://marapi.myacurite.com",
    path: "/accounts/${state.acurite_account_id}/dashboard/hubs/${device_id}",
    headers: ["x-one-vue-token": state.acurite_token]
  ]
  if (debug) log.debug "AcuRite: data_params: " + data_params

  boolean ok = false
  try {
    httpGet(data_params) { data_resp ->
      if (debug) {
        log.debug "AcuRite: data response status: ${data_resp.status}"
        log.debug "AcuRite: data response: ${data_resp.data}"
      }
      process_acurite_data(data_resp.data)
      ok = true
    }
  } catch (groovyx.net.http.HttpResponseException e) {
    if (e.response.status == 401) {
      // token rejected — clear it and let the caller decide whether to re-login
      if (debug) log.debug "AcuRite: token rejected (401), will re-login"
      state.acurite_token = null
      state.acurite_token_expiry = 0
    } else {
      log.error "AcuRite: data failed: ${e.response.status}: ${e.response.data}"
    }
  } catch (Exception e) {
    log.error "AcuRite: data exception: ${e.message}"
  }
  return ok
}

private boolean acurite_login() {
  def login_params = [
    uri: "https://marapi.myacurite.com",
    path: "/users/login",
    body: [
      "remember": true,
      "email": acurite_username,
      "password": acurite_password
    ]
  ]

  boolean ok = false
  try {
    httpPostJson(login_params) { login_resp ->
      state.acurite_token = login_resp.data.token_id
      state.acurite_account_id = login_resp.data.user.account_users[0].account_id
      // Cache for 1h. Any sooner-than-expected expiry surfaces as a 401 in
      // fetch_acurite_data and triggers a re-login on the next poll.
      state.acurite_token_expiry = now() + (60 * 60 * 1000)
      if (debug) log.debug "AcuRite: login OK, token cached for 1h"
      ok = true
    }
  } catch (groovyx.net.http.HttpResponseException e) {
    log.error "AcuRite: login failed: ${e.response?.status}"
  } catch (Exception e) {
    log.error "AcuRite: login exception: ${e.message}"
  }
  return ok
}

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

  // Sensor names from the API come in like "Wind Speed" → "wind_speed". The
  // generic loop emits them under that snake_case name. A few sensors get
  // remapped to canonical Hubitat attribute names (capability-mandated for
  // illuminance / ultravioletIndex, convention for windSpeed / windDirection).
  def rename = [
      "wind_speed":     "windSpeed",
      "wind_direction": "windDirection",
      "uv_index":       "ultravioletIndex"
  ]

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
    if (sensor_name == "wind_direction") {
      sendEvent(name: "wind_direction_abbreviation", value: sensor.wind_direction?.abbreviation)
      sendEvent(name: "wind_direction_point", value: sensor.wind_direction?.point)
    }
    if (sensor_name == "light_intensity") {
      // Hubitat's Illuminance Measurement capability publishes under `illuminance`.
      sendEvent(name: "illuminance", value: sensor_value, unit: sensor_unit)
    }
  }
}

def poll_schedule() {
  poll()
}

def initialize() {
  unschedule()
  if (debug) log.debug "AcuRite: initialize() called"

  // v0.0.10 cleanup: drop attribute names removed/renamed this version so
  // upgraders don't see phantom entries in the Current States panel.
  ["wind_direction", "wind_speed", "uv_index", "measured_light"].each {
    try { device.deleteCurrentState(it) } catch (Exception e) { /* older HE without API; ignore */ }
  }

  if (!acurite_username || !acurite_password || !device_id) {
    log.error "AcuRite: required fields not completed.  Please complete for proper operation."
    return
  }
  def poll_interval_cmd = (settings?.poll_interval ?: "5 Minutes").replace(" ", "")
  "runEvery${poll_interval_cmd}"(poll_schedule)
  if (debug) log.debug "AcuRite: scheduling as runEvery" + poll_interval_cmd
}
