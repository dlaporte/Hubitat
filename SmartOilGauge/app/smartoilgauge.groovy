/*
 *  Smart Oil Gauge (Connect)
 *
 *  v0.0.15 - third review round:
 *            - reverted the v0.0.14 Date.parse TZ change; it introduced
 *              an offset bug for hubs where the Droplet API's timezone
 *              didn't match the hub's.
 *            - state.tankHistory hard-capped at 720 entries regardless
 *              of windowHours setting.
 *  v0.0.14 - second review round fixes:
 *            - TEnergyTbl key changed from day-of-year (1-365) to "yyyy-DDD"
 *              so Jan 1 entries don't alias against a year-ago Jan 1.
 *            - Tile "refilled" threshold now uses refillThresholdPct
 *              (matching computeTankMetrics) instead of hardcoded -2 gal.
 *            - Date.parse in the tile renderer now uses the hub timezone
 *              instead of the JVM default.
 *            - initialize() reuses cached state.deviceData when present
 *              instead of synchronously re-fetching on every preference save.
 *  v0.0.13 - real-review fixes:
 *            - "Gallons used" condition was inverted after the v0.0.7
 *              subtraction-direction change; consumption was being labelled
 *              "refilled" and refills were going unlabelled.
 *            - daysRemaining: null no longer hits sendEvent (was storing
 *              the literal string "null" on the attribute).
 *            - Device labels html-escaped before interpolation into tile
 *              pages (XSS hardening).
 *            - installed()/updated() no longer log ${settings} (which
 *              contained ClientSecret in plaintext).
 *            - Dropped unused Power Meter capability on the child device.
 *            - Refill detection logs at unconditional log.info instead of
 *              gated logInfo, so the event surfaces without enabling debug.
 *  v0.0.12 - defensive `obs.data?.each` in fetchTankData; child device
 *            dropped dead parse() and empty installed() handlers.
 *  v0.0.11 - dropped the redundant Polling capability on the child device
 *            (Refresh covers it); poll() retained as a backward-compat alias.
 *  v0.0.10 - restored iconUrl/iconX2Url (Hubitat's definition() validator
 *            rejects empty/absent values) and the tank-3.png file they
 *            point at. The tile page itself remains zero-external.
 *  v0.0.9 - tile page now zero-external: dropped Bootstrap CDN, replaced
 *           the 5 tank PNGs with one inline SVG that fills dynamically.
 *  v0.0.8 - DRY'd getDevices + RefreshDeviceStatus into one fetchTankData().
 *  v0.0.7 - stripped nest-manager scaffolding (~660 lines); rewrote tile
 *           page with minimal inline CSS; external deps 11 → 2.
 *  v0.0.6 - explicit User-Agent, expires_in from OAuth response,
 *           fixed obs script-level binding.
 *  v0.0.5 - per-tank refill detection, usageRate, daysRemaining, lowFuel,
 *           accountNumber attributes.
 *  v0.0.4 - swapped dead rawgit URLs (since superseded), stopped
 *           force-enabling debug.
 *  v0.0.3 - earlier release.
 *
 *  Modified by David LaPorte. Based on the Tank Utility app by EricS,
 *  itself based on Joshua Spain's work.
 *
 *  Licensed under the Apache License, Version 2.0:
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  For Smart Oil Gauge (https://www.smartoilgauge.com). Requires a
 *  Droplet Fuel monitoring subscription for API access.
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
	singleInstance: true,
	oauth: true
)

static String appVersion() { "0.0.15" }

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
	// Don't log ${settings} — it contains ClientSecret in plaintext.
	log.info "Smart Oil Gauge installed"
	initialize()
}

void updated() {
	log.info "Smart Oil Gauge updated"
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

	// Only hit the API during initialize() on first install. Subsequent saves
	// rely on the hourly pollChildren — fetching synchronously here on every
	// preference save was making the settings page block for the duration of
	// the HTTP round-trip.
	Map tankData = (state.devices && state.deviceData) ? (state.deviceData as Map) : fetchTankData()
	boolean newDeviceCreated = false
	tankData.each { sensorId, tank ->
		String dni = getDeviceDNI(sensorId)
		def child = getChildDevice(dni)
		if (!child) {
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

// Single source of truth for the upstream call. Returns a Map keyed by
// sensor_id, or [:] on any failure. Also keeps state.devices (list of
// sensor_ids) and state.deviceData (the full Map) up to date.
private Map fetchTankData() {
	logTrace "fetchTankData()"
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
			if (resp.status != 200) {
				logError "fetchTankData: HTTP ${resp.status}"
				state.APIToken = null
				state.APITokenExpirationTime = 0L
				return
			}
			def obs = parseJson(resp.data.toString())
			if (obs?.result != "ok") {
				logError "fetchTankData: API non-ok: ${obs?.error_msg ?: obs}"
				return
			}
			obs.data?.each { dev -> deviceData[dev.sensor_id] = dev }
		}
	} catch (Exception e) {
		logError "fetchTankData exception: ${e.message}"
		state.APIToken = null
		state.APITokenExpirationTime = 0L
	}
	state.deviceData = deviceData
	state.devices = deviceData.keySet() as List
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
	Map deviceData = updateData ? fetchTankData() : (state.deviceData ?: [:])

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
				[lowFuel: derived.lowFuel ? "true" : "false"]
			]
			// Skip null derived metrics — sendEvent(value:null) stores the
			// literal string "null" on the attribute.
			if (derived.daysRemaining != null) events << [daysRemaining: derived.daysRemaining]
			if (derived.refillDetected) {
				events << [lastRefillAt: derived.refillAt]
				events << [lastRefillGallons: derived.refillGallons]
				log.info "SOG: refill detected on ${d.label} +${derived.refillGallons} gal"
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

	return """<div class="tank-body">
  <div class="tank-stats">
    <b>Capacity:</b> ${capacity ?: '—'} gal<br>
    <b>Tank level:</b> <span class="${lowFuelClass}">${level ?: '—'}%</span><br>
    <b>Gallons:</b> ${gallons ?: '—'}<br>
    <b>Gallons used:</b> ${used}${usageLine}${daysLine}<br>
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
	String fillColor = lvl <= 25 ? "#b94a48" : "#c69963"
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

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// Logging — gated entirely behind showDebug; one helper per level.
// ---------------------------------------------------------------------------

private void logTrace(String msg) { if (showDebug) log.trace "SOG: ${msg}" }
private void logInfo(String msg)  { if (showDebug) log.info  "SOG: ${msg}" }
private void logError(String msg) { log.error "SOG: ${msg}" }
