/*
 *  AcuRite Weather Station
 *
 *  Cloud driver for AcuRite sensors: polls the myAcuRite dashboard API (marapi.myacurite.com)
 *  for one hub's station readings, plus derived lightningActive and weatherSummary.
 *  Documentation: https://github.com/dlaporte/Hubitat/blob/main/AcuRite/README.md
 *  Based on: https://community.smartthings.com/t/my-very-quick-and-dirty-integration-with-myacurite-smart-hub-st-webcore/97749
 *
 *  Changelog:
 *  v0.1.0 (2026-11-20) - Logs in again only when the session expires or is rejected, not on
 *         every error; fetches on Initialize; a missing sensor list no longer stops a poll;
 *         lightningActive clears on time; outage logging and healthStatus; standard logging.
 *  v0.0.18 - defensive parse of lightning_strike_count; debug log no
 *            longer leaks the session token.
 *  v0.0.15 - skip sendEvent for null sensor readings.
 *  v0.0.14 - Polling capability → Refresh (poll() retained as alias).
 *  v0.0.12 - restored measured_light; degree symbol in weatherSummary.
 *  v0.0.11 - async HTTP; added lightningActive + weatherSummary.
 *  v0.0.10 - session token cached across polls; dropped duplicate
 *            snake_case attributes (windDirection/windSpeed/
 *            ultravioletIndex are canonical now).
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
 *  Copyright 2022 David LaPorte
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

@Field static final String VERSION = "0.1.0"

// Attributes whose changes get a descriptionText line (txtEnable).
@Field static final List<String> DESC_ATTRS = ["temperature", "humidity", "pressure", "illuminance", "ultravioletIndex",
        "windSpeed", "windDirection", "rainfall"]

metadata {
    definition(
        name: "AcuRite Weather Station",
        namespace: "dlaporte",
        author: "David LaPorte",
        importUrl: "https://raw.githubusercontent.com/dlaporte/Hubitat/main/AcuRite/device/acurite.groovy",
        // The login and data callbacks share the cached session in state.
        singleThreaded: true
    ) {
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

        attribute "device_signal_strength", "number"
        attribute "device_last_checkin", "string"

        attribute "lightning_last_strike_distance", "number"
        attribute "lightning_closest_strike_distance", "number"
        attribute "lightning_strike_count", "number"
        attribute "interference", "number"
        attribute "measured_light", "number"
        attribute "light_intensity", "number"
        attribute "wind_speed_average", "number"
        attribute "rainfall", "decimal"
        attribute "wind_chill", "decimal"
        attribute "dew_point", "number"
        attribute "wind_direction_abbreviation", "string"
        attribute "wind_direction_point", "string"
        attribute "windDirection", "string"
        attribute "windSpeed", "number"

        // v0.0.11 derived
        attribute "healthStatus", "enum", ["online", "offline"]
        // temperature, humidity, pressure, illuminance, ultravioletIndex and
        // battery come from the capabilities above.

        attribute "lightningActive", "string"  // "true"/"false"
        attribute "lastStrikeAt", "string"     // ISO timestamp of most recent strike count increase
        attribute "weatherSummary", "string"   // composed dashboard string
    }

    preferences() {
        section("Query Inputs") {
            input name: "acurite_username", type: "text", title: "AcuRite username", required: true
            input name: "acurite_password", type: "password", title: "AcuRite password", required: true
            input name: "device_id", type: "text", title: "Device ID", required: true, description: "Your Device ID can be found looking for 'hubs' in the Network section of Chrome's Developer Tools while loading the MyAcurite dashboard"
            input name: "poll_interval", type: "enum", title: "Poll interval", required: false, defaultValue: "5 Minutes", options: ["5 Minutes", "10 Minutes", "15 Minutes", "30 Minutes", "1 Hour", "3 Hours"]
            input name: "lightningWindowMinutes", type: "number", title: "Lightning-active window (minutes since last strike)", required: false, defaultValue: 10, range: "1..120"
            input name: "logEnable", type: "bool", title: "Enable debug logging", description: "Turns itself off after 30 minutes", defaultValue: false
            input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        }
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

    if (!acurite_username || !acurite_password || !device_id) {
        log.error "required fields not completed.  Please complete for proper operation."
        return
    }
    def poll_interval_cmd = (settings?.poll_interval ?: "5 Minutes").replace(" ", "")
    "runEvery${poll_interval_cmd}"("refresh")
    logDebug "scheduling as runEvery${poll_interval_cmd}"
    // Start with a fresh session so new credentials take effect, and show data
    // now instead of at the next poll.
    state.acurite_token = null
    refresh()
}

private void upgradeSettings() {
    device.removeSetting("debug")          // replaced by logEnable in 0.1.0
    device.updateDataValue("driverVersion", VERSION)
}

// ---- Commands ----

def refresh() {
    logDebug "refresh()"
    get_acurite_data()
}

// Backward-compat alias — kept so rules that called poll() under the
// old Polling capability keep working.
def poll() { refresh() }

// ---- HTTP (async) ----
// A session is used until the expiry stored at login (an hour), or until the
// API rejects it with 401/403; then the driver logs in again, once per poll.
// Other errors (timeouts, 5xx) keep the session and wait for the next poll,
// so an outage no longer means a failed login on every poll.

def get_acurite_data() {
    if (sessionValid()) {
        fetch_acurite_data(false)
    } else {
        acurite_login(true)
    }
}

private boolean sessionValid() {
    return state.acurite_token && state.acurite_account_id && now() < ((state.acurite_token_expiry ?: 0) as Long)
}

private fetch_acurite_data(boolean alreadyRetried) {
    def params = [
        uri: "https://marapi.myacurite.com/accounts/${state.acurite_account_id}/dashboard/hubs/${device_id}",
        headers: ["x-one-vue-token": state.acurite_token],
        timeout: 15
    ]
    // Log the URL only — params.headers contains the live session token, and
    // debug-enabled logs are routinely shared in community support threads.
    logDebug "fetch_acurite_data ${params.uri}"
    asynchttpGet("fetchAcuriteDataHandler", params, [retried: alreadyRetried])
}

def fetchAcuriteDataHandler(response, data) {
    try {
        Integer status = statusOf(response)
        if (status == 401 || status == 403) {
            // Session rejected: drop it and log in again, once.
            logDebug "data fetch HTTP ${status}; logging in again (retried=${data.retried})"
            state.acurite_token = null
            state.acurite_token_expiry = 0
            if (!data.retried) {
                acurite_login(true)
            } else {
                noteCommFailure("auth", "dashboard request rejected right after logging in (HTTP ${status})")
            }
            return
        }
        if (response.hasError() || status != 200) {
            noteCommFailure(failureKind(status), httpErrorDetail(response))
            return
        }
        def parsed = response.json
        if (parsed == null) parsed = new JsonSlurper().parseText(response.data.toString())
        noteCommSuccess()
        process_acurite_data(parsed)
    } catch (Exception e) {
        log.error "fetchAcuriteDataHandler exception: ${e.message}"
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
        Integer status = statusOf(response)
        if (response.hasError() || status != 200) {
            // A 4xx means myAcuRite answered and refused the credentials.
            String kind = (status != null && status >= 400 && status < 500 && status != 408) ? "auth" : failureKind(status)
            noteCommFailure(kind, httpErrorDetail(response))
            return
        }
        def parsed = response.json
        if (parsed == null) parsed = new JsonSlurper().parseText(response.data.toString())
        state.acurite_token = parsed.token_id
        state.acurite_account_id = parsed.user?.account_users?.getAt(0)?.account_id
        state.acurite_token_expiry = now() + (60 * 60 * 1000)
        logDebug "login OK, token cached for 1h"

        if (data?.fetchAfter && state.acurite_account_id) {
            fetch_acurite_data(true)
        }
    } catch (Exception e) {
        log.error "loginHandler exception: ${e.message}"
    }
}

// Runs when the lightning window ends (scheduled by armLightningClear).
void lightningWindowEnded() {
    if (now() >= lightningWindowEndMs()) {
        emitEvent(name: "lightningActive", value: "false", descriptionText: "${device.displayName} lightningActive is false")
    } else {
        armLightningClear()      // fired a moment early (runIn has 1 s resolution)
    }
}

private Long lightningWindowEndMs() {
    Integer windowMin = (settings.lightningWindowMinutes ?: 10) as Integer
    return ((state.acurite_last_strike_ms ?: 0L) as Long) + windowMin * 60_000L
}

private void armLightningClear() {
    Long remainingMs = lightningWindowEndMs() - now()
    runIn(Math.max(1L, (remainingMs + 999L).intdiv(1000L)) as Integer, "lightningWindowEnded")
}

// ---- Connection health ----
// One line when a failure starts, a reminder at most hourly, one recovery line
// with the outage length. The dedup key leaves out the error text, which can
// change during one outage. A myAcuRite outage is a warning (it is retried);
// rejected credentials are an error (the user has to fix them).

private void noteCommFailure(String kind, String detail) {
    Long nowMs = now()
    if (state.commFailSince == null) state.commFailSince = nowMs
    if (device.currentValue("healthStatus") != "offline") {
        emitEvent(name: "healthStatus", value: "offline", descriptionText: "${device.displayName} is offline")
    }
    // A 5xx and a timeout are the same outage; only rejected credentials differ.
    String key = (kind == "auth") ? "auth" : "down"
    if (state.commFailKey != key) {
        state.commFailKey = key
        state.commFailLastLog = nowMs
        if (kind == "auth") {
            log.error "myAcuRite rejected the login (${detail}); check the username and password in preferences. Repeats are suppressed; reminder hourly."
        } else if (kind == "http") {
            log.warn "myAcuRite answered with an error (${detail}). Repeats are suppressed; reminder hourly."
        } else {
            log.warn "myAcuRite isn't answering (${detail}). Repeats are suppressed; reminder hourly."
        }
    } else if (nowMs - ((state.commFailLastLog ?: 0L) as Long) >= 3_600_000L) {
        state.commFailLastLog = nowMs
        log.warn "myAcuRite still failing after ${formatDuration(nowMs - (state.commFailSince as Long))} (${detail})"
    }
}

private void noteCommSuccess() {
    if (device.currentValue("healthStatus") != "online") {
        emitEvent(name: "healthStatus", value: "online", descriptionText: "${device.displayName} is online")
    }
    if (state.commFailSince == null) return
    log.info "myAcuRite is responding again after ${formatDuration(now() - (state.commFailSince as Long))}"
    state.remove("commFailSince")
    state.remove("commFailKey")
    state.remove("commFailLastLog")
}

private Integer statusOf(response) {
    try { return response?.status as Integer } catch (Exception e) { return null }
}

// No status, or the hub's 408 for a connect/read timeout, means no answer.
private String failureKind(Integer status) {
    return (status == null || status == 408) ? "unreachable" : "http"
}

private String httpErrorDetail(response) {
    String msg = null
    try { msg = response?.getErrorMessage() } catch (Exception e) { /* no message available */ }
    Integer status = statusOf(response)
    return (msg ?: "no response") + (status ? ", HTTP ${status}" : "")
}

private String formatDuration(Long ms) {
    Long totalMin = Math.max(0L, ms as long).intdiv(60_000L)
    if (totalMin == 0) return "under a minute"
    Long h = totalMin.intdiv(60L)
    Long m = totalMin % 60L
    return h > 0 ? "${h}h ${m}m" : "${m}m"
}

// ---- Parsing + derived attributes ----

private void process_acurite_data(data) {
    sendEvent(name: "location_name", value: data.name)
    sendEvent(name: "location_latitude", value: data.latitude)
    sendEvent(name: "location_longitude", value: data.longitude)
    sendEvent(name: "location_elevation", value: data.elevation, unit: data.elevation_unit)
    sendEvent(name: "location_timezone", value: data.timezone)
    sendEvent(name: "device_country", value: data.country)

    def dev = data.devices ? data.devices[0] : null
    if (!dev) {
        log.warn "response had no devices"
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

    // wired_sensors is missing on some stations; a null in the list used to
    // throw here and skip the lightning and summary updates below.
    for (sensor in [dev.sensors, dev.wired_sensors].flatten().findAll { it instanceof Map && it.sensor_name }) {
        def sensor_name = sensor.sensor_name.replaceAll(" ", "_").toLowerCase()
        def attr_name = rename[sensor_name] ?: sensor_name
        def sensor_value = sensor.last_reading_value
        def sensor_unit = sensor.chart_unit
        snap[sensor_name] = [value: sensor_value, unit: sensor_unit]

        // Skip null readings — sendEvent on null sets the attribute to the
        // literal string "null". Lightning distance fields are often null
        // when there's been no recent strike.
        if (sensor_value != null) {
            Map evt = [name: attr_name, value: sensor_value]
            if (sensor_unit) evt.unit = sensor_unit
            if (attr_name in DESC_ATTRS) {
                evt.descriptionText = "${device.displayName} ${attr_name} is ${sensor_value}${sensor_unit ? ' ' + sensor_unit : ''}"
                emitEvent(evt)
            } else {
                sendEvent(evt)
            }
        }

        if (sensor_name == "wind_direction") {
            def abbr = sensor.wind_direction?.abbreviation
            def pt = sensor.wind_direction?.point
            if (abbr != null) sendEvent(name: "wind_direction_abbreviation", value: abbr)
            if (pt != null)   sendEvent(name: "wind_direction_point",        value: pt)
            snap["wind_direction_abbreviation"] = [value: abbr]
        }
        if (sensor_name == "light_intensity" && sensor_value != null) {
            sendEvent(name: "illuminance", value: sensor_value, unit: sensor_unit)
        }
    }

    // Derived: lightningActive
    // If strike count increased since last poll, stamp lastStrikeAt = now.
    // If lastStrikeAt was within the configured window, lightningActive = true.
    def currStrikes = snap["lightning_strike_count"]?.value
    // Defensive parse — if the API ever returns a non-numeric value here
    // (empty string, "N/A", etc.) a bare `as Long` cast would throw and
    // abort the whole sensor loop, losing subsequent updates. isDouble()
    // accepts both "3" and "3.0" since some weather APIs floatify counts.
    Long currStrikesLong = null
    if (currStrikes != null && currStrikes.toString().isDouble()) {
        currStrikesLong = (currStrikes.toString().toDouble() as Long)
    }
    if (currStrikesLong != null) {
        Long prevStrikes = (state.acurite_prev_strikes ?: 0L) as Long
        if (currStrikesLong > prevStrikes) {
            def fmt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")
            fmt.setTimeZone(location?.timeZone ?: TimeZone.getDefault())
            String ts = fmt.format(new Date(now()))
            state.acurite_last_strike_ms = now()
            sendEvent(name: "lastStrikeAt", value: ts)
        }
        state.acurite_prev_strikes = currStrikesLong

        Integer windowMin = (settings.lightningWindowMinutes ?: 10) as Integer
        Long cutoff = now() - (windowMin * 60L * 1000L)
        boolean active = (state.acurite_last_strike_ms ?: 0L) >= cutoff
        emitEvent(name: "lightningActive", value: active ? "true" : "false", descriptionText: "${device.displayName} lightningActive is ${active}")
        // Clear it when the window ends, not at the first poll after.
        if (active) armLightningClear()
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
