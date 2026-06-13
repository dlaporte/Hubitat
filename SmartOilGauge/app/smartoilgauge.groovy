/*
 *  Smart Oil Gauge (Connect)
 *
 *  v0.0.7 - Major cleanup pass:
 *           - Stripped the nest-manager automation-eval scaffolding (~70 lines):
 *             automationGenericEvt / doTheEvent / scheduleAutomationEval /
 *             runAutomationEval / getAutoRunSec. It only ever fed the daily
 *             energy table, which we now update directly from pollChildren.
 *           - Stripped execution-history accumulators (~60 lines):
 *             storeLastEventData, storeExecutionHistory, addToList, plus
 *             state.detailEventHistory / evalExecutionHistory /
 *             detailExecutionHistory. The app never read them back.
 *           - Stripped the per-app "disable this automation" toggle and the
 *             setAutomationStatus / getAutoType / getIsAutomationDisabled
 *             machinery. Single-automation app — uninstall to disable.
 *           - Removed enableOauth() which POSTed to an undocumented Hubitat
 *             admin endpoint (http://localhost:8080/app/edit/update). If the
 *             access token isn't present, the settings page now shows clear
 *             instructions instead.
 *           - Rewrote the tile-rendering HTML: dropped FontAwesome, FlowType,
 *             clipboard.js, vex, Swiper, gstatic charts, Google Fonts,
 *             normalize.css, hamburgers.css, and the diagpages_new.css /
 *             diagpages.min.js dependency on tonesto7/nest-manager. The
 *             tile page now depends only on Bootstrap (stable CDN) + tank
 *             images from this repo. Net external dependencies dropped from
 *             11 to 2.
 *           - Dropped icons() / gitRepo / gitBranch / gitPath / getAppImg /
 *             getDevImg helpers (only used to fetch icons from someone
 *             else's repo).
 *           - Initialize() removes the legacy state keys above so upgraders
 *             don't accumulate dead data.
 *  v0.0.6 - explicit User-Agent on all Droplet calls + use expires_in from
 *           OAuth response + fixed obs script-level binding.
 *  v0.0.5 - per-tank refill detection, usageRate, daysRemaining, lowFuel,
 *           accountNumber attributes + threshold preferences.
 *  v0.0.4 - swapped dead cdn.rawgit.com URLs to jsdelivr (since superseded
 *           by v0.0.7's local CSS rewrite) + stopped force-enabling debug.
 *  v0.0.3 - earlier release
 *
 *  Modified by David LaPorte. Based on the Tank Utility app from EricS,
 *  based on ideas from Joshua Spain. The nest-manager-derived dashboard
 *  scaffolding has been cut here; what's left is straight-line oil-gauge
 *  polling.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * For monitoring your oil tank using Smart Oil Gauge (https://www.smartoilgauge.com)
 * You must subscribe to the Droplet Fuel monitoring service ($2/month) for the
 * required API access.
 */

import groovy.json.*
import java.text.SimpleDateFormat

definition(
	name: "Smart Oil Gauge (Connect)",
	namespace: "dlaporte",
	author: "David LaPorte",
	description: "Virtual device handler for Smart Oil Gauge",
	category: "My Apps",
	iconUrl: "https://github.com/dlaporte/Hubitat/raw/main/SmartOilGauge/images/tank-3.png",
	iconX2Url: "https://github.com/dlaporte/Hubitat/raw/main/SmartOilGauge/images/tank-3.png",
	iconX3Url: "https://github.com/dlaporte/Hubitat/raw/main/SmartOilGauge/images/tank-3.png",
	singleInstance: true,
	oauth: true
)

static String appVersion() { "0.0.7" }

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

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

void installed() {
	log.info "Smart Oil Gauge installed: ${settings}"
	initialize()
}

void updated() {
	log.info "Smart Oil Gauge updated: ${settings}"
	initialize()
}

void uninstalled() {
	getAllChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

void initialize() {
	logTrace "initialize()"
	unsubscribe()
	unschedule()

	// v0.0.7 cleanup: drop legacy state from the nest-manager scaffolding so
	// the device State Variables panel stops showing dead data.
	[
		"detailEventHistory", "evalExecutionHistory", "detailExecutionHistory",
		"autoTyp", "autoDisabled", "autoDisabledDt", "lastEventData",
		"autoRunDt", "autoRunInSchedDt", "evalSched", "evalSchedLastTime",
		"autoExecMS", "dbgAppndName"
	].each { state.remove(it) }

	// Create child devices for any newly-discovered tanks.
	List devs = getDevices()
	Map deviceStatus = RefreshDeviceStatus()
	boolean newDeviceCreated = false
	devs.each { String sensorId ->
		String dni = getDeviceDNI(sensorId)
		def child = getChildDevice(dni)
		if (!child) {
			Map tank = deviceStatus[sensorId]
			String label = tank?.tank_num ? "Tank ${tank.tank_num}" : "Tank ${sensorId}"
			child = addChildDevice("dlaporte", CHILD_NAME(), dni, [name: label, label: label])
			logInfo "Created ${child.displayName} (dni: ${dni})"
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

// ---------------------------------------------------------------------------
// Settings page
// ---------------------------------------------------------------------------

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
			List devs = state.devices ?: getDevices()
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
			input "showDebug", "bool", title: "Enable debug logging", required: false, defaultValue: false
		}
	}
}

private void resetAppAccessToken(boolean reset) {
	if (!reset) return
	logInfo "Resetting web tile access token"
	state.access_token = null
	try { state.access_token = createAccessToken() } catch (Exception e) { logError "Could not regenerate access token: ${e.message}" }
	app?.clearSetting("resetAppAccessToken")
}

// ---------------------------------------------------------------------------
// Droplet Fuel API
// ---------------------------------------------------------------------------

private boolean getToken() {
	if (!settings.ClientId || !settings.ClientSecret) {
		logTrace "getToken: no Client ID / Secret yet"
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

private boolean getAPIToken() {
	logTrace "getAPIToken: requesting a new API token"
	def params = [
		uri: DROPLET_API(),
		path: "/brand_token.php",
		headers: dropletHeaders(),
		body: [
			grant_type: "client_credentials",
			client_id: settings.ClientId,
			client_secret: settings.ClientSecret
		]
	]
	boolean ok = false
	try {
		httpPost(params) { resp ->
			if (resp.status == 200 && resp.data?.access_token) {
				state.APIToken = resp.data.access_token
				// Use the server-supplied lease (defaults to 3600s) rather than a
				// hardcoded 1-hour bucket. Subtract 60s of slack so we refresh
				// before the server-side expiry actually lands.
				Integer ttlSec = (resp.data.expires_in ?: 3600) as Integer
				state.APITokenExpirationTime = now() + ((ttlSec - 60) * 1000L)
				state.lastErr = ""
				ok = true
				logInfo "Token refresh OK — lease ${ttlSec}s"
			} else {
				state.lastErr = "Token request returned ${resp.status}: ${resp.data?.error ?: resp.data}"
			}
		}
	} catch (Exception e) {
		state.lastErr = "Token request exception: ${e.message}"
	}
	if (state.lastErr) {
		logError "getAPIToken failed: ${state.lastErr}"
		state.APIToken = null
		state.APITokenExpirationTime = 0L
	}
	return ok
}

List getDevices() {
	logTrace "getDevices()"
	List devices = []
	if (!getToken()) {
		logTrace "getDevices: no token available"
		return devices
	}
	def params = [
		uri: DROPLET_API(),
		path: "/auto/get_tank_data.php",
		headers: dropletHeaders(),
		body: [access_token: state.APIToken, start_index: 0, max_results: 1000]
	]
	try {
		httpPost(params) { resp ->
			if (resp.status == 200) {
				def obs = parseJson(resp.data.toString())
				if (obs?.result != "ok") {
					logError "getDevices: API returned non-ok: ${obs?.error_msg ?: obs}"
					return
				}
				obs.data.each { dev ->
					logTrace "getDevices: found sensor_id ${dev.sensor_id}"
					devices << dev.sensor_id
				}
			} else {
				logError "getDevices: HTTP ${resp.status}"
				state.APIToken = null
				state.APITokenExpirationTime = 0L
			}
		}
		state.devices = devices
	} catch (Exception e) {
		logError "getDevices exception: ${e.message}"
		state.APIToken = null
		state.APITokenExpirationTime = 0L
	}
	return devices
}

private Map RefreshDeviceStatus() {
	logTrace "RefreshDeviceStatus()"
	Map deviceData = [:]
	if (!getToken()) return deviceData
	def params = [
		uri: DROPLET_API(),
		path: "/auto/get_tank_data.php",
		headers: dropletHeaders(),
		body: [access_token: state.APIToken, start_index: 0, max_results: 1000]
	]
	try {
		httpPost(params) { resp ->
			if (resp.status == 200) {
				def obs = parseJson(resp.data.toString())
				if (obs?.result != "ok") {
					logError "RefreshDeviceStatus: API returned non-ok: ${obs?.error_msg ?: obs}"
					return
				}
				obs.data.each { dev ->
					deviceData[dev.sensor_id] = dev
				}
			} else {
				logError "RefreshDeviceStatus: HTTP ${resp.status}"
				state.APIToken = null
				state.APITokenExpirationTime = 0L
			}
		}
	} catch (Exception e) {
		logError "RefreshDeviceStatus exception: ${e.message}"
		state.APIToken = null
		state.APITokenExpirationTime = 0L
	}
	state.deviceData = deviceData
	return deviceData
}

// ---------------------------------------------------------------------------
// Polling — pushes events to each child device and updates derived metrics
// ---------------------------------------------------------------------------

void pollChildren(boolean updateData = true) {
	logTrace "pollChildren(updateData=${updateData})"
	if (!getToken()) {
		logInfo "pollChildren: skipping — no API token"
		return
	}
	List devices = state.devices ?: []
	if (!devices) {
		logInfo "pollChildren: no known devices"
		return
	}
	Map deviceData = updateData ? RefreshDeviceStatus() : (state.deviceData ?: [:])

	devices.each { sensorId ->
		try {
			String dni = getDeviceDNI(sensorId)
			def d = getChildDevice(dni)
			if (!d) {
				logInfo "pollChildren: no child device for ${dni}"
				return
			}
			Map devData = deviceData[sensorId]
			if (!devData) {
				logInfo "pollChildren: no API data for ${d.label}"
				return
			}

			float gallons = devData.gallons.toFloat()
			float capacity = devData.tank_volume.toFloat()
			float level = ((gallons / capacity) * 100).round(2)
			String lastReadTime = devData.last_read
			String acctNum = devData.acct_num ?: ""
			int battery = batteryToPercent(devData.battery)
			Map derived = computeTankMetrics(sensorId, gallons, capacity)
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
				[usageRate: derived.usageRate],
				[daysRemaining: derived.daysRemaining],
				[lowFuel: derived.lowFuel ? "true" : "false"]
			]
			if (derived.refillDetected) {
				events << [lastRefillAt: derived.refillAt]
				events << [lastRefillGallons: derived.refillGallons]
				logInfo "pollChildren: refill on ${d.label} +${derived.refillGallons} gal"
			}
			events.each { evt -> d.generateEvent(evt) }
		} catch (Exception e) {
			logError "pollChildren: error for sensor ${sensorId}: ${e.message}"
		}
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
private Map computeTankMetrics(String sensorId, Float gallons, Float capacity) {
	Long nowMs = now()
	Integer windowHours = (settings.usageWindowHours ?: 168) as Integer
	Float refillThresholdPct = (settings.refillThresholdPct ?: 5) as Float
	Float lowFuelThresholdPct = (settings.lowFuelThresholdPct ?: 25) as Float

	if (state.tankHistory == null) state.tankHistory = [:]
	def hist = state.tankHistory[sensorId] ?: []

	boolean refillDetected = false
	Float refillGallons = 0
	String refillAt = null
	if (hist) {
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
	hist << [gallons, nowMs]
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
			if (usageRate > 0) daysRemaining = (gallons / usageRate).round() as Integer
		}
	}

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
// pipeline triggered off subscribe(d, "energy", ...); now a 10-line direct
// call from pollChildren.
private void recordDailyLevel(devId, float level) {
	String key = "TEnergyTbl${devId}"
	List table = state[key] ?: []
	Integer dayNum = new Date().format("D", location.timeZone ?: TimeZone.getDefault()) as Integer
	if (table && table.last()[0] == dayNum) table = table.take(table.size() - 1)
	table << [dayNum, level]
	while (table.size() > 365) table.removeAt(0)
	state[key] = table
}

// ---------------------------------------------------------------------------
// Tile rendering
// ---------------------------------------------------------------------------

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
	return renderDeviceTiles(null, device)
}

def renderDeviceTiles(type = null, theDev = null) {
	def allDevices = theDev ? [theDev] : app.getChildDevices(true).sort { it?.getLabel() }
	String panelsHtml = allDevices.findAll { it?.typeName == CHILD_NAME() }.collect { dev ->
		"""<div class="panel panel-primary"><div class="panel-heading"><h3 class="panel-title">${dev?.getLabel()}</h3></div><div class="panel-body">${getEDeviceTile(dev)}</div></div>"""
	}.join("\n")

	String title = theDev ? theDev.getLabel() : "All Tanks"
	String html = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, shrink-to-fit=no">
<title>Smart Oil Gauge — ${title}</title>
<link rel="stylesheet" href="https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/css/bootstrap.min.css" integrity="sha384-BVYiiSIFeK1dGmJRAkycuHAHRg32OmUcww7on3RYdg4Va+PmSTsz/K68vbdEjh4u" crossorigin="anonymous">
<style>
  body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif; background: #f6f8fa; margin: 0; padding: 16px; }
  h1 { font-size: 24px; margin: 8px 0 16px 0; }
  .panel { max-width: 720px; margin: 0 auto 16px; }
  .panel-title { font-size: 18px; }
  .tank-body { display: grid; grid-template-columns: 1fr auto; gap: 16px; align-items: center; }
  .tank-stats b { display: inline-block; min-width: 110px; }
  .tank-image img { max-width: 180px; height: auto; }
  .tank-level { font-size: 48px; font-weight: 600; text-align: center; margin: 4px 0 0 0; }
  .lowfuel { color: #b94a48; }
  .refresh { float: right; }
</style>
</head>
<body>
<h1>Smart Oil Gauge — ${title} <a class="btn btn-default btn-sm refresh" href="javascript:location.reload(true)">⟳ Refresh</a></h1>
${panelsHtml}
</body>
</html>"""
	render contentType: "text/html", data: html
}

private String getEDeviceTile(dev) {
	List table = state["TEnergyTbl${dev.id}"] ?: []
	if (table.size() == 0) {
		return """<div class="tank-body"><div>Waiting for data...</div></div>"""
	}

	// Snapshot of current state from the device's attributes
	def gallons = dev.currentValue("gallons")
	def capacity = dev.currentValue("capacity")
	def level = dev.currentValue("level")
	def lastReadTime = dev.currentValue("lastreading")
	def usageRate = dev.currentValue("usageRate")
	def daysRemaining = dev.currentValue("daysRemaining")
	String lowFuel = dev.currentValue("lowFuel")

	// "Gallons used" computed from the daily snapshot table — penultimate vs.
	// current. Skips the trailing dup if today's reading already updated.
	def t0 = table
	def yesterdayLevel = t0.size() > 1 ? t0[-2][1] : null
	String used = "—"
	if (yesterdayLevel != null && capacity) {
		float yesterdayGal = (capacity as Float) * (yesterdayLevel as Float) / 100
		float galDelta = (gallons as Float) - yesterdayGal
		used = galDelta < -2 ? "${galDelta.round(2)} (refilled)" : "${galDelta.round(2)}"
	}

	String formattedRead = "—"
	if (lastReadTime) {
		try {
			Date d = Date.parse("yyyy-MM-dd HH:mm:ss", lastReadTime.toString())
			SimpleDateFormat fmt = new SimpleDateFormat("MMM d, yyyy h:mm a")
			if (getTimeZone()) fmt.setTimeZone(getTimeZone())
			formattedRead = fmt.format(d)
		} catch (Exception e) { formattedRead = lastReadTime.toString() }
	}

	// Tank image: 5-stage based on % of typical-fill (capacity * 0.8)
	def num = 1
	if (capacity && gallons) {
		float cap80 = (capacity as Float) * 0.8
		float g = gallons as Float
		if (g >= cap80 * 0.25) num = 2
		if (g >= cap80 * 0.45) num = 3
		if (g >= cap80 * 0.65) num = 4
		if (g >= cap80 * 0.90) num = 5
	}
	String tankImg = "https://github.com/dlaporte/Hubitat/raw/main/SmartOilGauge/images/tank-${num}.png"

	String lowFuelClass = (lowFuel == "true") ? " lowfuel" : ""
	String usageLine = (usageRate && (usageRate as Float) > 0)
		? "<br><b>Usage rate:</b> ${usageRate} gal/day"
		: ""
	String daysLine = (daysRemaining != null && daysRemaining.toString() != "null")
		? "<br><b>Days remaining:</b> ${daysRemaining}"
		: ""

	return """<div class="tank-body">
  <div class="tank-stats">
    <b>Capacity:</b> ${capacity ?: '—'}<br>
    <b>Tank level:</b> <span class="${lowFuelClass}">${level ?: '—'}%</span><br>
    <b>Gallons:</b> ${gallons ?: '—'}<br>
    <b>Gallons used:</b> ${used}${usageLine}${daysLine}<br>
    <b>Last updated:</b> ${formattedRead}
  </div>
  <div class="tank-image">
    <img src="${tankImg}" alt="tank">
    <div class="tank-level">${level ?: '—'}</div>
  </div>
</div>"""
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

String getDeviceDNI(String DeviceID) {
	return [app.id, DeviceID].join('.')
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

// ---------------------------------------------------------------------------
// Logging — gated entirely behind showDebug; one helper per level.
// ---------------------------------------------------------------------------

private void logTrace(String msg) { if (showDebug) log.trace "SOG: ${msg}" }
private void logInfo(String msg)  { if (showDebug) log.info  "SOG: ${msg}" }
private void logError(String msg) { log.error "SOG: ${msg}" }
