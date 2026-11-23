/**
 *  Rheem EcoNet Integration
 *
 *  Copyright 2020 Dominick Meglio
 *  Modified 2026 by David LaPorte
 *
 *  Connects Hubitat to Rheem EcoNet water heaters through Rheem's cloud.
 *  Upstream: https://github.com/dcmeglio/hubitat-rheem
 *  Documentation: https://github.com/dlaporte/Hubitat/blob/main/RheemEcoNet/README.md
 *
 *  Changelog:
 *  v2.4.0 (2026-11-23) - David LaPorte's fork. Keeps the login token and logs in again only when
 *                        Rheem rejects it: at most every 10 minutes, and not for 30 minutes after a
 *                        rate limit. The settings page says why a login failed. Reads the heaters
 *                        every 30 minutes and their energy and water use every hour. Fixes the
 *                        setpoint and heat/off values Done wrote. No longer logs the settings.
 *  v2.3.0 - Dominick Meglio's last release (2020-12).
 *
 *	If you find this useful, donations are always appreciated (to Dominick Meglio)
 *	https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=7LBRPJRLJSDDN&source=url
 *
 */

import groovy.transform.Field
import groovyx.net.http.HttpResponseException

@Field static final String VERSION = "2.4.0"
@Field static String apiUrl = "https://rheem.clearblade.com"
@Field static String systemKey = "e2e699cb0bb0bbb88fc8858cb5a401"
@Field static String systemSecret = "E2E699CB0BE6C6FADDB1B0BC9A20"
@Field static final String USER_AGENT = "EcoNet/4.0.4 (com.rheem.econetprod; build:3577; iOS 13.5.0) Alamofire/4.9.1"
@Field static final long LOGIN_SPACING_MS = 600_000L      // at most one login per 10 minutes
@Field static final long RATE_LIMIT_HOLD_MS = 1_800_000L  // no logins for 30 minutes after a 429
@Field static final List<String> USAGE_TYPES = ["energyUsage", "waterUsage"]

definition(
    name: "Rheem EcoNet Integration",
    namespace: "dcm.rheem",
    author: "Dominick Meglio",
    description: "Integrate Hubitat with Rheem EcoNet Water Heaters",
    category: "My Apps",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    importUrl: "https://raw.githubusercontent.com/dlaporte/Hubitat/main/RheemEcoNet/app/Rheem_EcoNet_Integration.groovy",
    documentationLink: "https://github.com/dlaporte/Hubitat/blob/main/RheemEcoNet/README.md")

preferences {
    page(name: "prefAccountAccess", title: "Rheem EcoNet")
    page(name: "prefListDevice", title: "Rheem EcoNet")
}

// ---- Pages ----

def prefAccountAccess() {
    return dynamicPage(name: "prefAccountAccess", title: "Connect to Rheem EcoNet", nextPage: "prefListDevice", uninstall: false, install: false) {
        section("EcoNet Login Information") {
            input("username", "email", title: "Username", description: "Rheem EcoNet Username")
            input("password", "password", title: "Password", description: "Rheem EcoNet Password")
        }
        section("Settings") {
            input("debugOutput", "bool", title: "Enable debug logging?", description: "Turns itself off after 30 minutes",
                    defaultValue: false, displayDuringSetup: false, required: false)
        }
        displayFooter()
    }
}

// Opening this page always logs in, ignoring the spacing and the rate-limit hold,
// so a deliberate retry works.
def prefListDevice() {
    if (!login(true)) {
        return errorPage(loginMessage())
    }
    def waterHeaterList = getDevices()
    if (waterHeaterList == null) {
        return errorPage("Couldn't read your water heaters from Rheem EcoNet. Try again in a few minutes.")
    }
    if (!waterHeaterList) {
        return errorPage("No water heaters were found")
    }
    return dynamicPage(name: "prefListDevice", title: "Devices", install: true, uninstall: true) {
        section("Select which water heaters to monitor") {
            input name: "waterHeaters", type: "enum", required: false, multiple: true, options: waterHeaterList
        }
        displayFooter()
    }
}

private def errorPage(String text) {
    return dynamicPage(name: "prefListDevice", title: "Error", install: false, uninstall: true) {
        section("") {
            paragraph text
        }
        displayFooter()
    }
}

private String loginMessage() {
    Map r = (state.loginResult ?: [:]) as Map
    switch (r.result) {
        case "badCredentials":
            return "Rheem EcoNet rejected the username or password."
        case "rateLimited":
            return "Rheem EcoNet is limiting logins right now (too many attempts). Wait 30 minutes and try again."
        case "unreachable":
            return "Couldn't reach Rheem EcoNet: ${r.detail}. Check the hub's internet connection and try again."
        default:
            return "Couldn't log in to Rheem EcoNet."
    }
}

// ---- Lifecycle ----

def installed() {
    logDebug "Installed"
    initialize()
}

def updated() {
    logDebug "Updated"
    unsubscribe()
    initialize()
}

def uninstalled() {
    logDebug "uninstalling app"
    for (device in getChildDevices()) {
        deleteChildDevice(device.deviceNetworkId)
    }
}

def initialize() {
    unschedule()
    if (settings.debugOutput) runIn(1800, "debugOff")
    cleanupChildDevices()
    Map equipment = getEquipment()
    if (equipment == null) log.warn "Couldn't read the water heaters from Rheem EcoNet; trying again in 30 minutes"
    createChildDevices(equipment)
    runEvery30Minutes("pollEquipment")
    runEvery1Hour("pollUsage")
    if (equipment != null) pollUsage()
}

// ---- Heaters ----

// The water heaters on the account, keyed "device_name:serial_number". getUserDataForApp is
// what Rheem's own app and pyeconet use; getLocation is the older call. Null when Rheem
// couldn't be read.
Map getEquipment() {
    def result = apiPost("/getUserDataForApp", [resource: "friedrich"])
    if (!(result?.success && result?.results?.locations != null)) {
        logDebug "getUserDataForApp didn't answer; trying getLocation"
        result = apiPost("/getLocation")
    }
    if (result?.results?.locations == null) return null
    Map found = [:]
    result.results.locations.each { loc ->
        // Yup the API has a typo and called it "equiptments"
        loc.equiptments?.each { equip ->
            if (equip.device_type == "WH" && !equip.containsKey("error")) {
                found["${equip.device_name}:${equip.serial_number}".toString()] = equip
            }
        }
    }
    return found
}

def getDevices() {
    Map equipment = getEquipment()
    if (equipment == null) return null
    Map list = [:]
    equipment.each { String id, Map equip ->
        def name = equip["@NAME"]
        list[id] = (name instanceof Map ? name.value : name) ?: id
    }
    state.deviceList = list
    return list
}

private List<String> selectedHeaters() {
    def chosen = settings.waterHeaters
    if (chosen instanceof Collection) return chosen.collect { it.toString() }
    return chosen ? [chosen.toString()] : []
}

def createChildDevices(Map equipment = null) {
    for (String waterHeater in selectedHeaters()) {
        String dni = "rheem:${waterHeater}"
        def device = getChildDevice(dni)
        if (!device) {
            String name = (state.deviceList ?: [:])[waterHeater] ?: "Rheem Water Heater"
            device = addChildDevice("dcm.rheem", "Rheem Econet Water Heater", dni, [name: name, isComponent: false])
        }
        Map equip = equipment?.get(waterHeater) as Map
        if (equip != null) applyToChild(device, equip, true)
    }
}

def cleanupChildDevices() {
    List<String> selected = selectedHeaters()
    for (device in getChildDevices()) {
        String id = device.deviceNetworkId.replace("rheem:", "")
        if (!selected.contains(id)) deleteChildDevice(device.deviceNetworkId)
    }
}

// Writes the data values the driver falls back on and, when `deliver`, hands the heater its
// fields. The data values keep 2.3.0's meanings: enabledDisabled is on/off without modes.
private void applyToChild(device, Map equip, boolean deliver) {
    boolean hasModes = equip["@MODE"] instanceof Map
    boolean hasOnOff = equip.containsKey("@ENABLED")
    String id = device.deviceNetworkId.replace("rheem:", "")
    Map modes = (state.deviceModes ?: [:]) as Map
    modes[id] = hasModes ? (equip["@MODE"].constraints?.enumText ?: []) : []
    state.deviceModes = modes
    def setpoint = equip["@SETPOINT"]
    if (setpoint instanceof Map && setpoint.constraints instanceof Map) {
        device.updateDataValue("minTemp", setpoint.constraints.lowerLimit.toString())
        device.updateDataValue("maxTemp", setpoint.constraints.upperLimit.toString())
    }
    device.updateDataValue("tempOnly", (!hasModes && !hasOnOff).toString())
    device.updateDataValue("enabledDisabled", (hasOnOff && !hasModes).toString())
    if (deliver) device.applySnapshot(equip)
}

// Every 30 minutes: reads every heater from Rheem.
void pollEquipment() {
    readEquipment(null)
}

// Reads every heater from Rheem. The heater named by `callerDni` gets its fields as the
// return value rather than a call back.
private Map readEquipment(String callerDni) {
    Map equipment = getEquipment()
    if (equipment == null) {
        logDebug "Couldn't read the heaters from Rheem EcoNet"
        return null
    }
    state.lastSnapshotAt = now()
    Map callerEquip = null
    for (String waterHeater in selectedHeaters()) {
        String dni = "rheem:${waterHeater}"
        def device = getChildDevice(dni)
        Map equip = equipment[waterHeater] as Map
        if (device == null || equip == null) continue
        boolean isCaller = dni == callerDni
        applyToChild(device, equip, !isCaller)
        if (isCaller) callerEquip = equip
    }
    return callerEquip
}

// Hourly: today's energy and water use. A heater that doesn't report a type isn't asked
// again for it until the next day.
void pollUsage() {
    String today = dateStamp("yyyy-MM-dd")
    Map unsupported = (state.usageUnsupported ?: [:]) as Map
    for (String id in selectedHeaters()) {
        def device = getChildDevice("rheem:${id}")
        if (device == null) continue
        List<String> parts = id.tokenize(":")
        Map marks = (unsupported[id] ?: [:]) as Map
        for (String type in USAGE_TYPES) {
            if (marks[type] == today) continue
            def resp = apiPost("/dynamicAction", usageRequest(parts[0], parts[1], type))
            if (resp == null) continue
            if (resp.success) {
                device.applyUsage(type, resp)
            } else {
                marks[type] = today
                log.info "${device.displayName} doesn't report ${type == 'energyUsage' ? 'energy' : 'water'} use; checking again tomorrow"
            }
        }
        unsupported[id] = marks
    }
    state.usageUnsupported = unsupported
}

// pyeconet's request: energy dates carry the hub's UTC offset, water dates don't.
private Map usageRequest(String deviceName, String serial, String type) {
    String zone = type == "energyUsage" ? "XXX" : ""
    return [ACTION: "waterheaterUsageReportView", device_name: deviceName, serial_number: serial,
            start_date: dateStamp("yyyy-MM-dd'T'00:00:00.999" + zone),
            end_date: dateStamp("yyyy-MM-dd'T'23:59:59.999" + zone),
            usage_type: type]
}

private String dateStamp(String pattern) {
    def fmt = new java.text.SimpleDateFormat(pattern)
    fmt.setTimeZone(location.timeZone)
    return fmt.format(new Date(now()))
}

// ---- Called by the heaters ----

// After an MQTT connect failure: the token may have expired. login() applies the
// 10-minute spacing and the rate-limit hold.
def relogin() {
    logDebug "A heater asked for a new login"
    return login()
}

// A heater's refresh(): reads every heater now and returns the caller's fields.
def refreshEquipment(String callerDni = null) {
    return readEquipment(callerDni)
}

// Kept for drivers older than 2.4.0, which ask the app about modes.
def hasHeatPump(device) {
    return modesOf(device).any { it.toString().toUpperCase().startsWith("HEAT PUMP") }
}

def hasMode(device, mode) {
    return modesOf(device).any { it == mode }
}

private List modesOf(device) {
    String id = device.deviceNetworkId.replace("rheem:", "")
    return ((state.deviceModes ?: [:])[id] ?: []) as List
}

// Kept for drivers older than 2.4.0. A mode name it doesn't know counts as heat.
def translateThermostatMode(mode) {
    switch (mode?.toString()?.trim()?.toUpperCase()) {
        case "ENERGY SAVING":
        case "ENERGY SAVER":
            return "auto"
        case "HIGH DEMAND":
        case "PERFORMANCE":
            return "emergency heat"
        case "OFF":
            return "off"
        default:
            return "heat"
    }
}

def getClientId() {
    return getHubUID()
}

def getAccessToken() {
    return state.access_token
}

def getAccountId() {
    return state.account_id
}

// ---- Rheem API ----

// Logs in and stores the token. The settings pages pass fromPage, which skips the
// 10-minute spacing and the rate-limit hold.
boolean login(boolean fromPage = false) {
    long t = now()
    if (!fromPage) {
        if (state.loginHoldUntil != null && t < (state.loginHoldUntil as long)) {
            logDebug "Not logging in: Rheem rate-limited the last attempt"
            return false
        }
        if (state.lastLoginAt != null && t - (state.lastLoginAt as long) < LOGIN_SPACING_MS) {
            logDebug "Not logging in: the last attempt was under 10 minutes ago"
            return false
        }
    }
    state.lastLoginAt = t
    Map params = [
        uri: apiUrl,
        path: "/api/v/1/user/auth",
        headers: ["ClearBlade-SystemKey": systemKey, "ClearBlade-SystemSecret": systemSecret],
        requestContentType: "application/json",
        body: [email: settings.username, password: settings.password]
    ]
    String result = null
    String detail = null
    try {
        httpPost(params) { resp ->
            if (resp.data?.options?.success && resp.data?.user_token) {
                state.access_token = resp.data.user_token
                state.account_id = resp.data.options.account_id
                result = "ok"
            } else {
                result = "badCredentials"
            }
        }
    } catch (HttpResponseException e) {
        if (e.statusCode == 429) {
            result = "rateLimited"
        } else if (e.statusCode == 400 || e.statusCode == 401) {
            result = "badCredentials"
        } else {
            result = "unreachable"
            detail = "HTTP ${e.statusCode}"
        }
    } catch (Exception e) {
        result = "unreachable"
        detail = e.message ?: e.toString()
    }
    recordLogin(result, detail, fromPage)
    return result == "ok"
}

// Logs a failure loudly only when its kind changes (or on a settings-page attempt), so a
// failure that repeats doesn't warn more than once.
private void recordLogin(String result, String detail, boolean fromPage) {
    boolean loud = fromPage || (state.loginResult?.result != result)
    state.loginResult = [result: result, detail: detail, at: now()]
    switch (result) {
        case "ok":
            state.remove("loginHoldUntil")
            log.info "Logged in to Rheem EcoNet (token ending ${last4(state.access_token)})"
            break
        case "badCredentials":
            if (loud) log.error "Rheem EcoNet rejected the username or password; open the app and check them"
            else logDebug "Rheem EcoNet rejected the login again"
            break
        case "rateLimited":
            state.loginHoldUntil = now() + RATE_LIMIT_HOLD_MS
            if (loud) log.warn "Rheem EcoNet is limiting logins (HTTP 429); not logging in again for 30 minutes"
            else logDebug "Rheem EcoNet rate-limited the login again; holding for 30 minutes"
            break
        default:
            if (loud) log.warn "Couldn't reach Rheem EcoNet to log in: ${detail}"
            else logDebug "Couldn't reach Rheem EcoNet to log in: ${detail}"
    }
}

private String last4(Object token) {
    String s = token?.toString() ?: ""
    return s.length() > 4 ? s.substring(s.length() - 4) : "****"
}

// POSTs to one of Rheem's code services with the stored token. Logs in first only when
// there is no token; on a 401 logs in once more (within the limits) and retries.
def apiPost(String path, Map body = null) {
    if (!state.access_token && !login()) return null
    try {
        return apiPostOnce(path, body)
    } catch (HttpResponseException e) {
        if (e.statusCode != 401) {
            noteApiFailure(path, e)
            return null
        }
        logDebug "Rheem rejected the token (401) for ${path}; logging in again"
        state.remove("access_token")
        if (!login()) return null
        try {
            return apiPostOnce(path, body)
        } catch (Exception e2) {
            noteApiFailure(path, e2)
            return null
        }
    } catch (Exception e) {
        noteApiFailure(path, e)
        return null
    }
}

private def apiPostOnce(String path, Map body) {
    Map params = [
        uri: apiUrl,
        path: "/api/v/1/code/${systemKey}${path}",
        timeout: 60,
        headers: [
            "ClearBlade-SystemKey": systemKey,
            "ClearBlade-SystemSecret": systemSecret,
            "ClearBlade-UserToken": state.access_token,
            "User-Agent": USER_AGENT
        ],
        requestContentType: "application/json"
    ]
    if (body != null) params.body = body
    def result = null
    httpPost(params) { resp ->
        result = resp.data
    }
    return result
}

private void noteApiFailure(String path, Exception e) {
    String why = e instanceof HttpResponseException ? "HTTP ${e.statusCode}" : (e.message ?: e.toString())
    logDebug "Rheem EcoNet ${path} failed: ${why}"
}

// ---- Logging ----

def logDebug(msg) {
    if (settings?.debugOutput) {
        log.debug msg
    }
}

void debugOff() {
    log.info "Debug logging turned off"
    app.updateSetting("debugOutput", [value: "false", type: "bool"])
}

def displayFooter() {
    section() {
        paragraph getFormat("line")
        paragraph "<div style='color:#1A77C9;text-align:center'>Rheem EcoNet Integration<br><a href='https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=7LBRPJRLJSDDN&source=url' target='_blank'><img src='https://www.paypalobjects.com/webstatic/mktg/logo/pp_cc_mark_37x23.jpg' border='0' alt='PayPal Logo'></a><br><br>Please consider donating. This app took a lot of work to make.<br>If you find it valuable, I'd certainly appreciate it!</div>"
    }
}

def getFormat(type, myText = "") {			// Modified from @Stephack Code
    if (type == "line") return "<hr style='background-color:#1A77C9; height: 1px; border: 0;'>"
    if (type == "title") return "<h2 style='color:#1A77C9;font-weight: bold'>${myText}</h2>"
}
