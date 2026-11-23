/*
 *  Smart Oil Gauge (Connect)
 *
 *  Cloud app for Smart Oil Gauge (https://www.smartoilgauge.com) tank monitors, through the
 *  Droplet Fuel API (needs a Droplet Fuel monitoring subscription). Creates one Smart Oil
 *  Gauge child device per tank and serves web tiles.
 *  Documentation: https://github.com/dlaporte/Hubitat/blob/main/SmartOilGauge/README.md
 *  Based on: the Tank Utility app by EricS, itself based on Joshua Spain's work.
 *
 *  Changelog:
 *  v0.2.0 (2026-11-23) - The hourly check runs in the background; a bad Client ID / Secret is
 *         reported as such (it was logged as an outage); runOutDate per tank and on the tile.
 *  v0.1.0 (2026-11-20) - Repeated readings no longer skew usage rate or refill timing; new tanks
 *         get a device without re-saving; tile turns red at the low-fuel setting; API calls time
 *         out; outage logging; healthStatus per tank; standard logging and lifecycle.
 *  v0.0.16 - a failed API fetch no longer stops polling; daysRemaining
 *            uses the last measured usage rate instead of going stale.
 *  v0.0.15 - reverted v0.0.14's broken TZ override; capped tankHistory at 720.
 *  v0.0.14 - year-boundary fix for TEnergyTbl key; tile refill threshold
 *            matches setting; settings save no longer blocks on API call.
 *  v0.0.13 - fixed inverted Gallons-used label; XSS hardening; stopped
 *            logging settings (had ClientSecret); dropped Power Meter cap.
 *  v0.0.11 - dropped redundant Polling capability (Refresh covers it).
 *  v0.0.9  - tile page is zero-external (inline SVG tank).
 *  v0.0.7  - stripped nest-manager scaffolding (~660 lines).
 *  v0.0.6  - User-Agent header; expires_in from OAuth response.
 *  v0.0.5  - refill detection, usageRate, daysRemaining, lowFuel,
 *            accountNumber attributes.
 *  v0.0.3  - earlier release.
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
import java.text.SimpleDateFormat
import groovyx.net.http.HttpResponseException

@Field static final String VERSION = "0.2.0"

definition(
    name: "Smart Oil Gauge (Connect)",
    namespace: "dlaporte",
    author: "David LaPorte",
    description: "Virtual device handler for Smart Oil Gauge",
    importUrl: "https://raw.githubusercontent.com/dlaporte/Hubitat/main/SmartOilGauge/app/smartoilgauge.groovy",
    category: "My Apps",
    iconUrl: "https://github.com/dlaporte/Hubitat/raw/main/SmartOilGauge/images/tank-3.png",
    iconX2Url: "https://github.com/dlaporte/Hubitat/raw/main/SmartOilGauge/images/tank-3.png",
    singleInstance: true,
    oauth: true
)

static String appVersion() { VERSION }

preferences {
    page(name: "settings", title: "Smart Oil Gauge", content: "settingsPage", install: true)
}

mappings {
    path("/deviceTiles") { action: [GET: "renderDeviceTiles"] }
    path("/getTile/:dni") { action: [GET: "getTile"] }
}

private static String DROPLET_API() { "https://api.dropletfuel.com" }
private static String CHILD_NAME() { "Smart Oil Gauge" }

// Droplet Fuel's edge sits behind Mod Security which 406s any request with a
// curl-flavored or empty User-Agent. Hubitat's default UA slips through today,
// but an explicit value insulates the driver against future rule-tightening.
private Map dropletHeaders() {
    return ["User-Agent": "Hubitat SmartOilGauge driver/${appVersion()} (+https://github.com/dlaporte/Hubitat)"]
}

// ---- Lifecycle ----

void installed() {
    // Don't log ${settings} — it contains ClientSecret in plaintext.
    initialize()
}

void updated() {
    log.info "Preferences saved"
    initialize()
}

void uninstalled() {
    getAllChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

void initialize() {
    unsubscribe()
    unschedule()
    upgradeSettings()
    if (settings.logEnable) runIn(1800, "logsOff")

    // Only hit the API during initialize() on first install. Subsequent saves
    // rely on the hourly pollChildren — fetching synchronously here on every
    // preference save was making the settings page block for the duration of
    // the HTTP round-trip.
    Map tankData = (state.devices && state.deviceData) ? (state.deviceData as Map) : fetchTankData()
    boolean newDeviceCreated = false
    tankData.each { sensorId, tank ->
        if (!getChildDevice(getDeviceDNI(sensorId))) {
            ensureChild(sensorId, tank as Map)
            newDeviceCreated = true
        }
    }
    if (newDeviceCreated) {
        runIn(5, "updated", [overwrite: true])
        return
    }

    runEvery1Hour("pollChildren")
    pollChildren(false)
}

private void upgradeSettings() {
    app.removeSetting("showDebug")         // replaced by logEnable in 0.1.0
    state.appVersion = VERSION
}

// Returns the tank's child device, creating it if needed (also used by the
// poll, so a tank added to the account later gets a device without a re-save).
private ensureChild(String sensorId, Map tank) {
    String dni = getDeviceDNI(sensorId)
    def child = getChildDevice(dni)
    if (!child) {
        String label = tank?.tank_num ? "Tank ${tank.tank_num}" : "Tank ${sensorId}"
        child = addChildDevice("dlaporte", CHILD_NAME(), dni, [name: label, label: label])
        log.info "Created ${child.displayName} (dni: ${dni})"
    }
    return child
}

// ---- Settings page ----

private settingsPage() {
    if (!state.access_token) { try { state.access_token = createAccessToken() } catch (Exception e) { /* OAuth not enabled */ } }

    return dynamicPage(name: "settings", title: "Smart Oil Gauge Settings", nextPage: "", install: true, uninstall: true) {

        boolean haveToken = getToken()

        section("Authentication") {
            if (!haveToken) {
                paragraph "${state.lastErr ?: ''} Enter your Smart Oil Gauge Client ID and Secret."
                input "ClientId", "string", title: "Smart Oil Gauge Client ID", required: true
                input "ClientSecret", "string", title: "Smart Oil Gauge Client Secret", required: true, submitOnChange: true
            } else {
                paragraph "Authentication succeeded."
            }
        }

        if (haveToken) {
            List devs = state.devices ?: (fetchTankData().keySet() as List)
            section("Tanks (${devs.size()})") {
                if (state.access_token) {
                    if (devs.size() > 1) {
                        paragraph """<a href="${getAppEndpointUrl('deviceTiles')}" target="_blank">All Tanks</a> | <a href="${getLocalEndpointUrl('deviceTiles')}" target="_blank">(local)</a>"""
                    }
                    devs.each { sensorId ->
                        String dni = getDeviceDNI(sensorId)
                        def d = getChildDevice(dni)
                        if (d) {
                            paragraph """<a href="${getAppEndpointUrl('getTile/' + dni)}" target="_blank">${d.label ?: d.name}</a> | <a href="${getLocalEndpointUrl('getTile/' + dni)}" target="_blank">(local)</a>"""
                        }
                    }
                } else {
                    paragraph "Web tile access requires OAuth. Enable it from Apps Code → Smart Oil Gauge (Connect) → OAuth → Enable OAuth in App."
                }
            }

            section("Tank Alerts & Estimates") {
                input "lowFuelThresholdPct", "number", title: "Low-fuel alert threshold (% of tank capacity)",
                    required: false, defaultValue: 25, range: "1..99"
                input "refillThresholdPct", "number", title: "Minimum gallons increase to count as a refill (% of capacity)",
                    required: false, defaultValue: 5, range: "1..99"
                input "usageWindowHours", "number", title: "Usage-rate smoothing window (hours)",
                    required: false, defaultValue: 168, range: "6..720"
            }

            section("Application Security") {
                input "resetAppAccessToken", "bool", title: "Reset web tile access token?",
                    required: false, defaultValue: false, submitOnChange: true,
                    description: "Resetting invalidates any URL you've shared and generates a new one. Any dashboards using the old URL will need to be updated."
                resetAppAccessToken(settings.resetAppAccessToken == true)
            }
        }

        section("Logging") {
            input name: "logEnable", type: "bool", title: "Enable debug logging", description: "Turns itself off after 30 minutes", defaultValue: false
        }
    }
}

private void resetAppAccessToken(boolean reset) {
    if (!reset) return
    log.info "Resetting web tile access token"
    state.access_token = null
    try { state.access_token = createAccessToken() } catch (Exception e) { log.error "Could not regenerate access token: ${e.message}" }
    app?.clearSetting("resetAppAccessToken")
}

// ---- Droplet Fuel API ----

private boolean getToken() {
    if (!settings.ClientId || !settings.ClientSecret) {
        logDebug "getToken: no Client ID / Secret yet"
        return false
    }
    if (isTokenExpired() || !state.APIToken) {
        return getAPIToken()
    }
    return true
}

private boolean isTokenExpired() {
    if (!state.APITokenExpirationTime) return true
    return now() >= (state.APITokenExpirationTime as Long)
}

// ---- Droplet requests ----
// The settings page and first install call Droplet directly, because they need the
// answer while the page loads. The hourly check and a tank's Refresh ask in the
// background (tokenCallback, tankDataCallback), so the hub isn't held up waiting.

private Map tokenForm() {
    return [grant_type: "client_credentials", client_id: settings.ClientId, client_secret: settings.ClientSecret]
}

private Map tankDataForm() {
    return [access_token: state.APIToken, start_index: 0, max_results: 1000]
}

private Map dropletRequest(String path, Map form) {
    return [uri: DROPLET_API(), path: path, headers: dropletHeaders(), timeout: 15, body: form]
}

private Map backgroundRequest(String path, Map form) {
    return dropletRequest(path, form) + [body: formBody(form), requestContentType: "application/x-www-form-urlencoded"]
}

private String formBody(Map form) {
    return form.collect { k, v ->
        URLEncoder.encode(k.toString(), "UTF-8") + "=" + URLEncoder.encode(v == null ? "" : v.toString(), "UTF-8")
    }.join("&")
}

private void storeToken(json) {
    state.APIToken = json.access_token
    // Use the server-supplied lease (default 3600 s), less 60 s of slack, so the token is
    // renewed before the server-side expiry lands.
    Integer ttlSec = (json.expires_in ?: 3600) as Integer
    state.APITokenExpirationTime = now() + ((ttlSec - 60) * 1000L)
    state.lastErr = ""
    logDebug "Token refresh OK — lease ${ttlSec}s"
}

private void clearToken() {
    state.APIToken = null
    state.APITokenExpirationTime = 0L
}

// Keep the cached tank list on failure: overwriting it with [:] emptied state.devices,
// and the poll then bailed every hour until the app was re-saved.
private void storeTankData(Map deviceData) {
    state.deviceData = deviceData
    state.devices = deviceData.keySet() as List
    noteCommSuccess()
}

private boolean getAPIToken() {
    logDebug "getAPIToken: requesting a new API token"
    boolean ok = false
    String failKind = "down"
    state.lastErr = ""
    try {
        httpPost(dropletRequest("/brand_token.php", tokenForm())) { resp ->
            if (resp.status == 200 && resp.data?.access_token) {
                storeToken(resp.data)
                ok = true
            } else {
                state.lastErr = "Token request returned ${resp.status}: ${resp.data?.error ?: resp.data}"
                if (resp.status in [400, 401, 403]) failKind = "auth"
            }
        }
    } catch (HttpResponseException e) {
        // httpPost throws for an HTTP error status; 400/401/403 means a bad Client ID / Secret.
        state.lastErr = "Token request returned ${e.statusCode}: ${e.message}"
        if (e.statusCode in [400, 401, 403]) failKind = "auth"
    } catch (Exception e) {
        state.lastErr = "Token request exception: ${e.message}"
    }
    if (state.lastErr) {
        noteCommFailure(failKind, state.lastErr as String)
        clearToken()
    }
    return ok
}

// The direct tank-data call (settings page, first install). Returns a Map keyed by
// sensor_id, or [:] on any failure.
private Map fetchTankData() {
    logDebug "fetchTankData()"
    Map deviceData = [:]
    boolean ok = false
    if (!getToken()) return deviceData
    try {
        httpPost(dropletRequest("/auto/get_tank_data.php", tankDataForm())) { resp ->
            if (resp.status != 200) {
                noteCommFailure("down", "get_tank_data returned HTTP ${resp.status}")
                clearToken()
                return
            }
            def obs = parseJson(resp.data.toString())
            if (obs?.result != "ok") {
                noteCommFailure("down", "get_tank_data error: ${obs?.error_msg ?: obs}")
                return
            }
            obs.data?.each { dev -> deviceData[dev.sensor_id] = dev }
            ok = true
        }
    } catch (Exception e) {
        noteCommFailure("down", "get_tank_data: ${e.message}")
        clearToken()
    }
    if (ok) storeTankData(deviceData)
    return deviceData
}

// ---- Polling ----

// The hourly check and a tank's Refresh (updateData true) ask Droplet in the
// background. A save (false) re-applies the cached readings without calling Droplet.
void pollChildren(boolean updateData = true) {
    logDebug "pollChildren(updateData=${updateData})"
    if (updateData) pollInBackground()
    else applyTankData((state.deviceData ?: [:]) as Map)
}

private void pollInBackground() {
    if (!settings.ClientId || !settings.ClientSecret) {
        logDebug "pollChildren: no Client ID / Secret yet"
        return
    }
    if (state.APIToken && !isTokenExpired()) requestTankData()
    else asynchttpPost("tokenCallback", backgroundRequest("/brand_token.php", tokenForm()))
}

private void requestTankData() {
    asynchttpPost("tankDataCallback", backgroundRequest("/auto/get_tank_data.php", tankDataForm()))
}

void tokenCallback(response, data) {
    Integer status = statusOf(response)
    def json = jsonOf(response)
    if (status == 200 && json?.access_token) {
        storeToken(json)
        requestTankData()
        return
    }
    // A background request hands back the HTTP status rather than throwing.
    String detail = "Token request returned ${status ?: 'no response'}: ${json?.error ?: errorText(response)}"
    state.lastErr = detail
    noteCommFailure(status in [400, 401, 403] ? "auth" : "down", detail)
    clearToken()
    setAllTanksHealth("offline")
}

void tankDataCallback(response, data) {
    Integer status = statusOf(response)
    if (status != 200) {
        noteCommFailure("down", "get_tank_data: ${status ? "HTTP ${status}" : errorText(response)}")
        clearToken()
        setAllTanksHealth("offline")
        return
    }
    def obs = jsonOf(response)
    if (obs?.result != "ok") {
        noteCommFailure("down", "get_tank_data error: ${obs?.error_msg ?: obs}")
        setAllTanksHealth("offline")
        return
    }
    Map deviceData = [:]
    obs.data?.each { dev -> deviceData[dev.sensor_id] = dev }
    storeTankData(deviceData)
    applyTankData(deviceData)
}

private Integer statusOf(response) {
    try {
        return response?.status as Integer
    } catch (Exception e) {
        return null
    }
}

private def jsonOf(response) {
    try {
        def body = response?.data
        return body ? parseJson(body.toString()) : null
    } catch (Exception e) {
        return null
    }
}

private String errorText(response) {
    try {
        return response?.getErrorMessage() ?: "no response"
    } catch (Exception e) {
        return "no response"
    }
}

// Turns Droplet's tank map into events on each tank device: readings, usage, refills
// and health.
private void applyTankData(Map deviceData) {
    List devices = state.devices ?: []
    if (!devices) {
        logDebug "applyTankData: no known devices"
        return
    }

    devices.each { sensorId ->
        try {
            Map devData = deviceData[sensorId]
            if (!devData) {
                logDebug "applyTankData: no API data for ${sensorId}"
                return
            }
            def d = ensureChild(sensorId, devData)

            float gallons = devData.gallons.toFloat()
            float capacity = devData.tank_volume.toFloat()
            float level = ((gallons / capacity) * 100).round(2)
            String lastReadTime = devData.last_read
            String acctNum = devData.acct_num ?: ""
            int battery = batteryToPercent(devData.battery)
            Map derived = computeTankMetrics(sensorId, gallons, capacity, lastReadTime)
            recordDailyLevel(d.id, level)

            List events = [
                [level: level],
                [energy: level],
                [humidity: level],
                [capacity: capacity],
                [gallons: gallons],
                [lastreading: lastReadTime],
                [battery: battery],
                [accountNumber: acctNum],
                [usageRate: derived.usageRate]
            ]
            // Skip null derived metrics — sendEvent(value:null) stores the
            // literal string "null" on the attribute.
            if (derived.daysRemaining != null) {
                events << [daysRemaining: derived.daysRemaining]
                events << [runOutDate: runOutDate(derived.daysRemaining as Integer)]
            }
            events << [healthStatus: readingIsStale(sensorId, d.label as String, lastReadTime) ? "offline" : "online"]
            if (derived.refillDetected) {
                events << [lastRefillAt: derived.refillAt]
                events << [lastRefillGallons: derived.refillGallons]
                log.info "refill detected on ${d.label} +${derived.refillGallons} gal"
            }
            // Last, so a rule triggered by lowFuel reads this reading's level and runOutDate.
            events << [lowFuel: derived.lowFuel ? "true" : "false"]
            events.each { evt -> d.generateEvent(evt) }
        } catch (Exception e) {
            log.error "applyTankData: error for sensor ${sensorId}: ${e.message}"
        }
    }
}

// Today plus the days remaining, in the hub's time zone (Calendar, so a DST change can't
// shift the date).
private String runOutDate(Integer days) {
    TimeZone tz = getTimeZone() ?: TimeZone.getDefault()
    Calendar cal = Calendar.getInstance(tz)
    cal.setTimeInMillis(now())
    cal.add(Calendar.DAY_OF_MONTH, days)
    def fmt = new SimpleDateFormat("yyyy-MM-dd")
    fmt.setTimeZone(tz)
    return fmt.format(cal.getTime())
}

// "2026-12-15" -> "Dec 15", for the tile.
private String tileDate(Object isoDate) {
    try {
        return new SimpleDateFormat("MMM d").format(new SimpleDateFormat("yyyy-MM-dd").parse(isoDate.toString()))
    } catch (Exception e) {
        return isoDate.toString()
    }
}

private int batteryToPercent(String level) {
    switch (level) {
        case "Excellent": return 100
        case "Good":      return 75
        case "Fair":      return 50
        case "Poor":      return 1
        default:          return 0
    }
}

// Computes refill / usage-rate / days-remaining / low-fuel for one tank.
// State shape: state.tankHistory[sensorId] = [[gallons, timeMs], ...] kept
// within the smoothing window. Refill events break the chain so post-refill
// usageRate isn't polluted by the jump.
// A sample is only added when last_read changes: the gauge uploads less often
// than the hourly poll, and a repeated reading counted again (with a new
// timestamp) skewed the usage rate and refill timing. Samples keep the poll
// time (now()), as before.
private Map computeTankMetrics(String sensorId, Float gallons, Float capacity, String lastRead = null) {
    Long nowMs = now()
    Integer windowHours = (settings.usageWindowHours ?: 168) as Integer
    Float refillThresholdPct = (settings.refillThresholdPct ?: 5) as Float
    Float lowFuelThresholdPct = (settings.lowFuelThresholdPct ?: 25) as Float

    if (state.tankHistory == null) state.tankHistory = [:]
    def hist = state.tankHistory[sensorId] ?: []
    if (state.lastReadSeen == null) state.lastReadSeen = [:]
    boolean newReading = !lastRead || state.lastReadSeen[sensorId] != lastRead

    boolean refillDetected = false
    Float refillGallons = 0
    String refillAt = null
    if (hist && newReading) {
        Float prevGallons = hist.last()[0] as Float
        Float jumpThreshold = capacity * refillThresholdPct / 100
        if (gallons > prevGallons + jumpThreshold) {
            refillDetected = true
            refillGallons = (gallons - prevGallons).round(2)
            refillAt = new Date(nowMs).format("yyyy-MM-dd HH:mm:ss", getTimeZone() ?: TimeZone.getDefault())
            hist = []
        }
    }

    Long cutoff = nowMs - (windowHours * 3600L * 1000L)
    hist = hist.findAll { (it[1] as Long) >= cutoff }
    if (newReading) {
        hist << [gallons, nowMs]
        if (lastRead) state.lastReadSeen[sensorId] = lastRead
    }
    // Hard cap independent of windowHours so an aggressive window setting
    // can't bloat state indefinitely. 720 = max window at 1-per-hour polling.
    while (hist.size() > 720) hist.removeAt(0)
    state.tankHistory[sensorId] = hist

    Float usageRate = 0
    Integer daysRemaining = -1
    if (hist.size() >= 2) {
        Float oldestGal = hist.first()[0] as Float
        Long oldestTime = hist.first()[1] as Long
        Float hours = (nowMs - oldestTime) / (3600.0 * 1000.0)
        Float galConsumed = oldestGal - gallons
        if (hours >= 1 && galConsumed > 0) {
            usageRate = (galConsumed / hours * 24).round(2)
        }
    }

    // With no measurable consumption in the window (flat readings, or right
    // after a refill resets hist), estimate from the last measured rate so
    // daysRemaining follows current gallons instead of freezing.
    if (state.lastUsageRate == null) state.lastUsageRate = [:]
    if (usageRate > 0) state.lastUsageRate[sensorId] = usageRate
    Float estimateRate = usageRate > 0 ? usageRate : (state.lastUsageRate[sensorId] as Float)
    if (estimateRate) daysRemaining = (gallons / estimateRate).round() as Integer

    return [
        usageRate: usageRate,
        daysRemaining: daysRemaining < 0 ? null : daysRemaining,
        lowFuel: (gallons / capacity * 100) <= lowFuelThresholdPct,
        refillDetected: refillDetected,
        refillAt: refillAt,
        refillGallons: refillGallons
    ]
}

// Daily level snapshot for the tile chart. Was a 70-line "automation eval"
// pipeline triggered off subscribe(d, "energy", ...); now a direct call
// from pollChildren.
//
// Keys are "yyyy-DDD" (year + day-of-year) so Jan 1 entries don't alias
// against the previous year's Jan 1. Daily dedup compares string keys.
private void recordDailyLevel(devId, float level) {
    String key = "TEnergyTbl${devId}"
    List table = state[key] ?: []
    String dayKey = new Date().format("yyyy-DDD", location.timeZone ?: TimeZone.getDefault())
    if (table && table.last()[0] == dayKey) table = table.take(table.size() - 1)
    table << [dayKey, level]
    while (table.size() > 365) table.removeAt(0)
    state[key] = table
}

// ---- Tile rendering ----

def getTile() {
    String dni = "${params?.dni}"
    if (!dni) {
        render contentType: "text/html", data: "Invalid parameters"
        return
    }
    def device = getChildDevice(dni)
    if (!device) {
        render contentType: "text/html", data: "Device '${dni}' not found"
        return
    }
    return renderDeviceTiles(device)
}

def renderDeviceTiles(theDev = null) {
    def allDevices = theDev ? [theDev] : app.getChildDevices(true).sort { it?.getLabel() }
    String panelsHtml = allDevices.findAll { it?.typeName == CHILD_NAME() }.collect { dev ->
        """<div class="panel"><h2>${htmlEscape(dev?.getLabel())}</h2>${getEDeviceTile(dev)}</div>"""
    }.join("\n")

    String title = htmlEscape(theDev ? theDev.getLabel() : "All Tanks")
    String html = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Smart Oil Gauge — ${title}</title>
<style>
  body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
         background: #f6f8fa; margin: 0; padding: 16px; color: #24292e; }
  h1 { font-size: 22px; margin: 8px 0 16px 0; display: flex; justify-content: space-between; align-items: center; }
  h2 { font-size: 16px; margin: 0; padding: 12px 16px; background: #2c5aa0; color: #fff;
       border-top-left-radius: 6px; border-top-right-radius: 6px; }
  .panel { max-width: 720px; margin: 0 auto 16px; background: #fff;
           border: 1px solid #d1d5da; border-radius: 6px; }
  .tank-body { display: grid; grid-template-columns: 1fr auto; gap: 16px; align-items: center; padding: 16px; }
  .tank-stats b { display: inline-block; min-width: 130px; }
  .tank-level { font-size: 42px; font-weight: 600; text-align: center; margin: 4px 0 0 0; }
  .lowfuel { color: #b94a48; }
  .refresh { padding: 6px 12px; font-size: 14px; text-decoration: none; color: #fff;
             background: #2c5aa0; border-radius: 4px; }
  .refresh:hover { background: #1e4078; }
</style>
</head>
<body>
<h1>Smart Oil Gauge — ${title}<a class="refresh" href="javascript:location.reload(true)">⟳ Refresh</a></h1>
${panelsHtml}
</body>
</html>"""
    render contentType: "text/html", data: html
}

private String getEDeviceTile(dev) {
    List table = state["TEnergyTbl${dev.id}"] ?: []
    if (table.size() == 0) {
        return """<div class="tank-body"><div>Waiting for data…</div></div>"""
    }

    def gallons = dev.currentValue("gallons")
    def capacity = dev.currentValue("capacity")
    def level = dev.currentValue("level")
    def lastReadTime = dev.currentValue("lastreading")
    def usageRate = dev.currentValue("usageRate")
    def daysRemaining = dev.currentValue("daysRemaining")
    def runOut = dev.currentValue("runOutDate")
    String lowFuel = dev.currentValue("lowFuel")

    // "Gallons used" — penultimate vs. current in the daily snapshot table.
    // Positive consumption when gallons dropped; "refilled" label uses the
    // same percentage threshold as computeTankMetrics so the tile and the
    // lastRefillAt event agree about what counts as a refill.
    def yesterdayLevel = table.size() > 1 ? table[-2][1] : null
    String used = "—"
    if (yesterdayLevel != null && capacity) {
        Float refillThresholdPct = (settings.refillThresholdPct ?: 5) as Float
        Float refillCutoff = (capacity as Float) * refillThresholdPct / 100
        float yesterdayGal = (capacity as Float) * (yesterdayLevel as Float) / 100
        float consumed = yesterdayGal - (gallons as Float)   // positive = consumed
        if (consumed < -refillCutoff) {
            used = "+${(-consumed).round(2)} (refilled)"
        } else {
            used = "${consumed.round(2)}"
        }
    }

    String formattedRead = "—"
    if (lastReadTime) {
        try {
            // Droplet's API doesn't document the timezone of last_read, so we
            // parse it in JVM default (which on Hubitat matches the hub's TZ in
            // the common case) and format in the hub's TZ for display. v0.0.14
            // tried to set the parser TZ explicitly but that introduced an
            // offset bug whenever the API timezone didn't match the hub.
            Date d = Date.parse("yyyy-MM-dd HH:mm:ss", lastReadTime.toString())
            SimpleDateFormat fmt = new SimpleDateFormat("MMM d, yyyy h:mm a")
            if (getTimeZone()) fmt.setTimeZone(getTimeZone())
            formattedRead = fmt.format(d)
        } catch (Exception e) { formattedRead = lastReadTime.toString() }
    }

    String lowFuelClass = (lowFuel == "true") ? " lowfuel" : ""
    String usageLine = (usageRate && (usageRate as Float) > 0)
        ? "<br><b>Usage rate:</b> ${usageRate} gal/day"
        : ""
    String daysLine = (daysRemaining != null && daysRemaining.toString() != "null")
        ? "<br><b>Days remaining:</b> ${daysRemaining}"
        : ""
    String runOutLine = runOut ? "<br><b>Runs out around:</b> ${tileDate(runOut)}" : ""

    return """<div class="tank-body">
  <div class="tank-stats">
    <b>Capacity:</b> ${capacity ?: '—'} gal<br>
    <b>Tank level:</b> <span class="${lowFuelClass}">${level ?: '—'}%</span><br>
    <b>Gallons:</b> ${gallons ?: '—'}<br>
    <b>Gallons used:</b> ${used}${usageLine}${daysLine}${runOutLine}<br>
    <b>Last updated:</b> ${formattedRead}
  </div>
  <div class="tank-image">
    ${renderTankSvg(level)}
    <div class="tank-level ${lowFuelClass}">${level ?: '—'}%</div>
  </div>
</div>"""
}

// Inline SVG tank that fills from the bottom based on level (0..100).
// Zero external assets — no PNGs, no CSS framework needed.
private String renderTankSvg(def level) {
    Float lvl = level != null ? (level as Float) : 0f
    if (lvl < 0) lvl = 0f
    if (lvl > 100) lvl = 100f
    // Tank body interior: y=12..132 (height 120). Fill rises from bottom.
    Float fillHeight = 120 * lvl / 100
    Float fillY = 12 + (120 - fillHeight)
    Float lowPct = (settings.lowFuelThresholdPct ?: 25) as Float
    String fillColor = lvl <= lowPct ? "#b94a48" : "#c69963"
    return """<svg width="140" height="170" viewBox="0 0 100 150" xmlns="http://www.w3.org/2000/svg">
  <rect x="42" y="2" width="16" height="8" fill="#555" rx="1"/>
  <rect x="8" y="12" width="84" height="120" rx="6" fill="#f0f0f0" stroke="#333" stroke-width="2"/>
  <rect x="10" y="${fillY}" width="80" height="${fillHeight}" fill="${fillColor}" opacity="0.85"/>
  <line x1="8" y1="42" x2="92" y2="42" stroke="#999" stroke-width="0.5" stroke-dasharray="3,3"/>
  <line x1="8" y1="72" x2="92" y2="72" stroke="#999" stroke-width="0.5" stroke-dasharray="3,3"/>
  <line x1="8" y1="102" x2="92" y2="102" stroke="#999" stroke-width="0.5" stroke-dasharray="3,3"/>
  <rect x="8" y="12" width="84" height="120" rx="6" fill="none" stroke="#333" stroke-width="2"/>
</svg>"""
}

// ---- Connection health ----
// The poll runs hourly, so every failure would log: instead one line when a
// failure starts, a reminder at most hourly, one recovery line with the
// outage length. An API outage is a warning (it is retried); a rejected
// Client ID / Secret is an error (the user has to fix it).

private void noteCommFailure(String kind, String detail) {
    Long nowMs = now()
    if (state.commFailSince == null) state.commFailSince = nowMs
    String key = (kind == "auth") ? "auth" : "down"
    if (state.commFailKey != key) {
        state.commFailKey = key
        state.commFailLastLog = nowMs
        if (key == "auth") {
            log.error "Droplet Fuel rejected the Client ID / Secret (${detail}); check them in the app. Repeats are suppressed; reminder hourly."
        } else {
            log.warn "Droplet Fuel API isn't answering properly (${detail}). Repeats are suppressed; reminder hourly."
        }
    } else if (nowMs - ((state.commFailLastLog ?: 0L) as Long) >= 3_600_000L) {
        state.commFailLastLog = nowMs
        log.warn "Droplet Fuel API still failing after ${formatDuration(nowMs - (state.commFailSince as Long))} (${detail})"
    }
}

private void noteCommSuccess() {
    if (state.commFailSince == null) return
    log.info "Droplet Fuel API is responding again after ${formatDuration(now() - (state.commFailSince as Long))}"
    state.remove("commFailSince")
    state.remove("commFailKey")
    state.remove("commFailLastLog")
}

private void setAllTanksHealth(String value) {
    (state.devices ?: []).each { sensorId ->
        def d = getChildDevice(getDeviceDNI(sensorId as String))
        if (d) d.generateEvent([healthStatus: value])
    }
}

// A gauge that stops uploading (battery, Wi-Fi) leaves its last reading in the
// API forever. Stale = last_read over 96 hours old (the threshold the Smart Oil
// Gauge site uses). One warning per tank when it goes stale. last_read has no
// documented time zone; it's read in the hub's, which is close enough here.
private boolean readingIsStale(String sensorId, String label, String lastRead) {
    if (!lastRead) return false
    Long readMs
    try {
        def fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        if (getTimeZone()) fmt.setTimeZone(getTimeZone())
        readMs = fmt.parse(lastRead).time
    } catch (Exception e) {
        return false
    }
    boolean stale = now() - readMs > 96 * 3_600_000L
    List staleTanks = (state.staleTanks ?: []) as List
    if (stale && !(sensorId in staleTanks)) {
        log.warn "${label} hasn't reported since ${lastRead}; check the gauge's battery and Wi-Fi"
        staleTanks << sensorId
    } else if (!stale && (sensorId in staleTanks)) {
        log.info "${label} is reporting again"
        staleTanks.remove(sensorId)
    }
    state.staleTanks = staleTanks
    return stale
}

private String formatDuration(Long ms) {
    Long totalMin = Math.max(0L, ms as long).intdiv(60_000L)
    if (totalMin == 0) return "under a minute"
    Long h = totalMin.intdiv(60L)
    Long m = totalMin % 60L
    return h > 0 ? "${h}h ${m}m" : "${m}m"
}

// ---- Helpers ----

String getDeviceDNI(String DeviceID) {
    return [app.id, DeviceID].join('.')
}

// Minimal HTML entity escaping for values interpolated into rendered tiles.
// Device labels are user-controlled and the tile URLs are OAuth-accessible.
private String htmlEscape(s) {
    if (s == null) return ""
    return s.toString()
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

String getAppEndpointUrl(String subPath) {
    return "${getFullApiServerUrl()}${subPath ? "/${subPath}" : ""}?access_token=${state.access_token}"
}

String getLocalEndpointUrl(String subPath) {
    return "${getFullLocalApiServerUrl()}${subPath ? "/${subPath}" : ""}?access_token=${state.access_token}"
}

private TimeZone getTimeZone() {
    return location?.timeZone
}

// ---- BEGIN standard logging (app) v1: keep identical in every app, see STYLE.md ----

void logsOff() {
    log.info "Debug logging turned off"
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}

private void logDebug(String msg) {
    if (settings.logEnable) log.debug msg
}

// ---- END standard logging ----
