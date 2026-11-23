/**
 *  Rheem Econet Water Heater
 *
 *  Copyright 2020 Dominick Meglio
 *  Modified 2026 by David LaPorte
 *
 *  A Rheem EcoNet water heater, created by the Rheem EcoNet Integration app. Live updates
 *  come over Rheem's MQTT service.
 *  Upstream: https://github.com/dcmeglio/hubitat-rheem
 *  Documentation: https://github.com/dlaporte/Hubitat/blob/main/RheemEcoNet/README.md
 *
 *  Changelog:
 *  v2.4.1 (2026-11-23) - Status text Rheem sends on several lines (such as "Schedule overridden"
 *                        with its end time) is kept on one line.
 *  v2.4.0 (2026-11-23) - David LaPorte's fork. Reconnects with backoff (15 s up to every 30
 *                        minutes) and asks the app for a new login, with one warning per outage
 *                        instead of one every 15 seconds. Adds healthStatus, hot water, tank and
 *                        compressor health, shut-off valve, alerts, away, vacation, schedule,
 *                        Wi-Fi signal, firmware, and energy and water use today; setAway,
 *                        resumeSchedule and refresh. Modes come from the heater's own list. Fixes
 *                        setWaterHeaterMode("Off") on on/off heaters and the setpoint reading.
 *                        Attributes follow the heater's own reports, and 15 s after a command it
 *                        reads the heater and warns if the command didn't take.
 *  v2.3.0 - Dominick Meglio's last release (2020-12).
 *
 *	If you find this useful, donations are always appreciated (to Dominick Meglio)
 *	https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=7LBRPJRLJSDDN&source=url
 *
 */

metadata {
    definition(name: "Rheem Econet Water Heater", namespace: "dcm.rheem", author: "dmeglio@gmail.com", singleThreaded: true,
            importUrl: "https://raw.githubusercontent.com/dlaporte/Hubitat/main/RheemEcoNet/device/Rheem_EcoNet_Water_Heater.groovy") {
        capability "Initialize"
        capability "Refresh"
        capability "Thermostat"
        capability "Actuator"
        capability "Sensor"
        capability "ThermostatHeatingSetpoint"
        capability "ThermostatSetpoint"
        capability "ThermostatOperatingState"
        capability "ThermostatMode"

        command "setWaterHeaterMode", [[name: "Mode*", type: "ENUM", description: "Mode", constraints: [
                "Off", "Energy Saving", "Heat Pump Only", "High Demand", "Electric", "Gas", "Electric/Gas",
                "Performance", "Vacation", "Energy Saver", "Heat Pump", "Normal"]]]
        command "setAway", [[name: "Away*", type: "ENUM", constraints: ["on", "off"]]]
        command "resumeSchedule"

        attribute "waterHeaterMode", "ENUM"
        attribute "supportedWaterHeaterModes", "JSON_OBJECT"
        attribute "runningState", "STRING"
        attribute "hotWaterAvailable", "NUMBER"
        attribute "tankHealth", "NUMBER"
        attribute "compressorHealth", "NUMBER"
        attribute "shutoffValve", "ENUM", ["open", "closed"]
        attribute "alertCount", "NUMBER"
        attribute "away", "ENUM", ["on", "off"]
        attribute "vacation", "ENUM", ["on", "off"]
        attribute "scheduleStatus", "STRING"
        attribute "wifiSignal", "NUMBER"
        attribute "firmwareVersion", "STRING"
        attribute "healthStatus", "ENUM", ["online", "offline"]
        attribute "energyToday", "NUMBER"
        attribute "waterToday", "NUMBER"
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", description: "Turns itself off after 30 minutes", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

import groovy.transform.Field
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.text.SimpleDateFormat

@Field static final String VERSION = "2.4.1"
@Field static String apiUrl = "ssl://rheem.clearblade.com:1884"
@Field static String systemKey = "e2e699cb0bb0bbb88fc8858cb5a401"
@Field static final List<Integer> RETRY_DELAYS = [15, 60, 300, 900, 1800]   // seconds
@Field static final int OUTAGE_WARN_SEC = 60
@Field static final long REMINDER_MS = 3_600_000L
@Field static final long STABLE_MS = 60_000L          // a connection this old resets the backoff when lost
@Field static final long QUEUE_MAX_AGE_MS = 900_000L  // a queued command older than this is dropped
// The fields the driver reads. The rest of what Rheem sends (network details, screen
// layouts) isn't kept.
@Field static final List<String> FIELDS_KEPT = ["@SETPOINT", "@ENABLED", "@MODE", "@RUNNING", "@HOTWATER", "@TANK",
        "@COMBUSTION", "@VALVESTATUS", "@ALERTCOUNT", "@AWAY", "@AWAYCONFIG", "@VACATION", "@SCHEDULESTATUS",
        "@SIGNAL", "@CONNECTED", "@VERSION", "@TYPE"]
// pyeconet's mode names: some are the same mode under another name.
@Field static final Map<String, String> MODE_ALIASES = [ENERGYSAVER: "ENERGYSAVING", HEATPUMP: "HEATPUMPONLY",
        ELECTRICMODE: "ELECTRIC", NORMAL: "ELECTRIC"]
@Field static final long DIALOG_WINDOW_MS = 60_000L
// Fields a command sets that are checked against the heater afterwards, with their names in the warning.
@Field static final Map<String, String> CHECKED_FIELDS = ["@SETPOINT": "setpoint", "@ENABLED": "on/off", "@MODE": "mode", "@AWAY": "away"]
@Field static final int CONFIRM_AFTER_SEC = 15

// ---- Lifecycle ----

def installed() {
    initialize()
}

def updated() {
    initialize()
}

def initialize() {
    unschedule()
    if (settings.logEnable) runIn(1800, "logsOff")
    device.updateDataValue("driverVersion", VERSION)
    sendSupportedModes()
    sendEvent(name: "supportedThermostatFanModes", value: JsonOutput.toJson([]))
    ["retryStep", "reconnectPending", "connectedAt", "outageSince", "outageWarnedAt", "outageRemindedAt", "lastError"].each {
        state.remove(it)
    }
    try {
        if (interfaces.mqtt.isConnected()) interfaces.mqtt.disconnect()
    } catch (e) {
        logDebug "MQTT disconnect: ${e.message}"
    }
    mqttConnectUntilSuccessful()
    try {
        refresh()
    } catch (e) {
        logDebug "Couldn't get a reading from the app: ${e.message}"
    }
}

// ---- MQTT connection ----

// Connects to Rheem's MQTT service and subscribes to the account's updates. On a failure
// it asks the app for a new login and retries with backoff. The name is kept from 2.3.0,
// whose scheduled retries may still be pending when this code lands.
def mqttConnectUntilSuccessful() {
    state.remove("reconnectPending")
    try {
        interfaces.mqtt.connect(apiUrl, parent.getClientId(), parent.getAccessToken(), systemKey, cleanSession: false)
        pauseExecution(3000)
        String account = parent.getAccountId()
        interfaces.mqtt.subscribe("user/${account}/device/reported", 2)
        interfaces.mqtt.subscribe("user/${account}/device/desired", 2)
        noteConnected()
        sendQueued(account)
        return true
    } catch (e) {
        noteConnectFailure(e.message ?: e.toString())
        return false
    }
}

def mqttClientStatus(String message) {
    if (message.startsWith("Status: Connection succeeded")) {
        noteConnected()
        return
    }
    if (message.contains("Connection lost") || message.contains("Client is not connected") || message.startsWith("Error:")) {
        logDebug "MQTT: ${message}"
        if (state.reconnectPending) return
        Long since = state.connectedAt as Long
        if (since != null && now() - since >= STABLE_MS) state.retryStep = 0
        state.remove("connectedAt")
        state.lastError = message
        startOutage()
        try {
            interfaces.mqtt.disconnect()
        } catch (e) {
            logDebug "MQTT disconnect: ${e.message}"
        }
        scheduleReconnect()
        return
    }
    logDebug "MQTT status: ${message}"
}

private void noteConnectFailure(String error) {
    logDebug "MQTT connect failed: ${error}"
    state.lastError = error
    startOutage()
    remindOutage()
    try {
        parent.relogin()
    } catch (e) {
        logDebug "Couldn't ask the app for a new login: ${e.message}"
    }
    scheduleReconnect()
}

private void scheduleReconnect() {
    int step = (state.retryStep ?: 0) as int
    int delay = RETRY_DELAYS[Math.min(step, RETRY_DELAYS.size() - 1)]
    state.retryStep = step + 1
    state.reconnectPending = true
    logDebug "Reconnecting in ${delay} s"
    runIn(delay, "mqttConnectUntilSuccessful")
}

private void noteConnected() {
    Long since = state.outageSince as Long
    if (since != null && state.outageWarnedAt != null) {
        log.info "${device.displayName} reconnected to Rheem EcoNet after ${duration(now() - since)}"
    } else if (since != null) {
        logDebug "Reconnected after ${duration(now() - since)}"
    }
    ["outageSince", "outageWarnedAt", "outageRemindedAt", "lastError"].each { state.remove(it) }
    state.connectedAt = now()
    unschedule("outageCheck")
    updateHealth()
}

private void startOutage() {
    if (state.outageSince == null) state.outageSince = now()
    if (state.outageWarnedAt == null) {
        long left = OUTAGE_WARN_SEC - (now() - (state.outageSince as long)).intdiv(1000L)
        runIn(Math.max(1L, left), "outageCheck")
    }
}

// 60 s after a connection failure: warns if the connection still isn't back.
void outageCheck() {
    if (state.outageSince == null || state.outageWarnedAt != null) return
    state.outageWarnedAt = now()
    state.outageRemindedAt = now()
    log.warn "${device.displayName} can't connect to Rheem EcoNet (${state.lastError ?: 'connection lost'}); retrying with backoff"
    updateHealth()
}

private void remindOutage() {
    Long reminded = state.outageRemindedAt as Long
    if (state.outageWarnedAt == null || reminded == null || now() - reminded < REMINDER_MS) return
    state.outageRemindedAt = now()
    log.warn "${device.displayName} still can't connect to Rheem EcoNet after ${duration(now() - (state.outageSince as long))} (${state.lastError})"
}

// offline once the outage warning is out, or while the heater itself reports it isn't connected.
private void updateHealth() {
    Map f = heaterFields()
    boolean heaterConnected = f.containsKey("@CONNECTED") ? truthy(val(f["@CONNECTED"])) : true
    String health = (state.outageWarnedAt == null && heaterConnected) ? "online" : "offline"
    emitEvent(name: "healthStatus", value: health, descriptionText: "${device.displayName} is ${health}")
}

// ---- Publishing ----

// Sends fields to the heater. While disconnected, keeps the latest command (for up to 15
// minutes) and reconnects; the command goes out once the connection is back.
private void publishWithRetry(Map msg) {
    String json = JsonOutput.toJson(buildMQTTMessage() + msg)
    logDebug "Sending ${json}"
    if (interfaces.mqtt.isConnected()) {
        try {
            interfaces.mqtt.publish("user/${parent.getAccountId()}/device/desired", json)
            noteCommandSent(msg)
            return
        } catch (e) {
            logDebug "MQTT publish failed: ${e.message}"
        }
    }
    state.queuedMessage = json
    state.queuedAt = now()
    log.warn "${device.displayName} isn't connected to Rheem EcoNet; the command will be sent when the connection is back"
    if (!state.reconnectPending) mqttConnectUntilSuccessful()
}

private void sendQueued(String account) {
    String json = state.queuedMessage
    Long at = state.queuedAt as Long
    state.remove("queuedMessage")
    state.remove("queuedAt")
    if (json == null) return
    if (at != null && now() - at > QUEUE_MAX_AGE_MS) {
        log.info "${device.displayName} dropped a command queued ${duration(now() - at)} ago"
        return
    }
    interfaces.mqtt.publish("user/${account}/device/desired", json)
    noteCommandSent(new JsonSlurper().parseText(json) as Map)
}

// Remembers what a command set, to check 15 s later that the heater took it.
private void noteCommandSent(Map msg) {
    Map check = (state.commandCheck ?: [:]) as Map
    msg.each { k, v ->
        if (CHECKED_FIELDS.containsKey(k.toString())) check[k.toString()] = v
    }
    if (!check) return
    state.commandCheck = check
    runIn(CONFIRM_AFTER_SEC, "confirmCommand")
}

// 15 s after a command: reads the heater from Rheem and warns about anything it didn't take.
void confirmCommand() {
    Map sent = (state.commandCheck ?: [:]) as Map
    state.remove("commandCheck")
    if (!pullSnapshot() || !sent) return
    Map f = heaterFields()
    List<String> missed = []
    sent.each { k, v ->
        String key = k.toString()
        if (!f.containsKey(key)) return
        String want = describeField(key, v, f)
        String have = describeField(key, val(f[key]), f)
        if (want != have) missed << "${CHECKED_FIELDS[key]} ${want} (it reports ${have})".toString()
    }
    if (missed) log.warn "${device.displayName} didn't take ${missed.join(', ')}"
}

// A field value as words, so a command and a reading compare like for like.
private String describeField(String key, Object v, Map f) {
    switch (key) {
        case "@ENABLED":
        case "@AWAY":
            return truthy(v) ? "on" : "off"
        case "@MODE":
            List<String> names = modeList(f)
            Integer i = isNumeric(v) ? (v.toString() as BigDecimal).intValue() : null
            return (i != null && i >= 0 && i < names.size()) ? names[i] : v?.toString()
        default:
            return isNumeric(v) ? new BigDecimal(v.toString()).stripTrailingZeros().toPlainString() : v?.toString()
    }
}

def buildMQTTMessage() {
    def fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
    fmt.setTimeZone(location.timeZone)
    return [transactionId: "ANDROID_" + fmt.format(new Date(now())), device_name: getDeviceName(), serial_number: getSerialNumber()]
}

def getDeviceName() {
    return device.deviceNetworkId.split(':')[1]
}

def getSerialNumber() {
    return device.deviceNetworkId.split(':')[2]
}

// ---- Fields ----

def parse(String message) {
    def msg = interfaces.mqtt.parseMessage(message)
    def payload
    try {
        payload = new JsonSlurper().parseText(msg.payload as String)
    } catch (e) {
        logDebug "Unreadable MQTT message on ${msg.topic}: ${e.message}"
        return
    }
    if (!(payload instanceof Map) || !isForThisHeater(payload as Map)) return
    logDebug "MQTT ${msg.topic}: ${msg.payload}"
    if (payload.dialog instanceof Map) {
        confirmDialog(payload.dialog as Map)
        return
    }
    // The desired topic carries commands (ours echoed back, or another client's), not
    // what the heater did. Only its reports update the attributes.
    if (msg.topic?.toString()?.endsWith("/desired")) return
    mergeFields(payload as Map)
    applyFields()
}

// From the app: the heater's full field set from Rheem's REST API.
void applySnapshot(Map equip) {
    mergeFields(equip)
    applyFields()
}

// From the app's hourly poll: today's energy or water use from Rheem's usage report.
void applyUsage(String type, Map response) {
    Map report = (response?.results ?: [:]) as Map
    def section = report[type == "energyUsage" ? "energy_usage" : "water_usage"]
    List data = section instanceof Map ? section.data as List : null
    if (data == null) {
        logDebug "No ${type} data in Rheem's report"
        return
    }
    BigDecimal total = data.inject(BigDecimal.ZERO) { BigDecimal sum, item ->
        isNumeric(item?.value) ? sum + new BigDecimal(item.value.toString()) : sum
    }
    BigDecimal rounded = Math.round(total.doubleValue() * 100) / 100
    if (type == "energyUsage") emitAttr("energyToday", rounded, energyUnit(section.message))
    else emitAttr("waterToday", rounded, "gal")
}

// The unit is a word in Rheem's message (pyeconet reads the fourth word). Without one, gas
// heaters report kBTU and the others kWh.
private String energyUnit(Object message) {
    String word = message?.toString()?.tokenize(" ")?.collect { it.replaceAll(/[^A-Za-z]/, "") }?.find { it ==~ /(?i)k?(wh|btu)/ }
    if (word) return word.toLowerCase().contains("btu") ? "kBTU" : "kWh"
    return val(heaterFields()["@TYPE"]) == "gasWaterHeater" ? "kBTU" : "kWh"
}

private boolean isForThisHeater(Map p) {
    if (p.device_name?.toString() != getDeviceName()) return false
    // Signal updates can carry the Wi-Fi module's serial number instead of the heater's (pyeconet).
    return p.serial_number?.toString() == getSerialNumber() || p.containsKey("@SIGNAL")
}

// pyeconet's merge: a dict merges into the stored dict; a bare value replaces the stored
// dict's value, or the stored value.
private void mergeFields(Map update) {
    Map f = heaterFields()
    update.each { k, v ->
        String key = k.toString()
        if (!FIELDS_KEPT.contains(key)) return
        def old = f[key]
        if (v instanceof Map) {
            Map merged = old instanceof Map ? new LinkedHashMap(old as Map) : [:]
            merged.putAll(v as Map)
            if (key == "@MODE" && (v as Map).containsKey("status")) alignModeValue(merged)
            f[key] = merged
        } else if (old instanceof Map) {
            if ((old as Map).containsKey("value")) {
                Map m = new LinkedHashMap(old as Map)
                m.value = v
                if (key == "@MODE") m.remove("status")   // the stored name no longer describes the value
                f[key] = m
            }
        } else {
            f[key] = v
        }
    }
    state.heaterFields = f
}

// An update that carries both a position and a name: the name wins (pyeconet).
private void alignModeValue(Map mode) {
    String status = mode.status?.toString()?.trim()
    List names = mode.constraints instanceof Map ? mode.constraints.enumText as List : null
    if (!status || !names) return
    int i = names.findIndexOf { it?.toString()?.trim()?.equalsIgnoreCase(status) }
    if (i >= 0) mode.value = i
}

// Works out every attribute from the stored fields. One whose field the heater doesn't
// report is left alone.
private void applyFields() {
    Map f = heaterFields()
    if (!f) return
    def setpoint = val(f["@SETPOINT"])
    if (isNumeric(setpoint)) {
        BigDecimal t = new BigDecimal(setpoint.toString())
        emitAttr("heatingSetpoint", t, "°F")
        emitAttr("thermostatSetpoint", t, "°F")
    }
    applyMode(f)
    if (f.containsKey("@RUNNING")) {
        String running = oneLine(val(f["@RUNNING"]))
        String opState = running ? "heating" : "idle"
        emitEvent(name: "thermostatOperatingState", value: opState, descriptionText: "${device.displayName} is ${opState}")
        emitAttr("runningState", running ?: "idle")
    }
    Integer hot = hotWaterPercent(f["@HOTWATER"])
    if (hot != null) emitAttr("hotWaterAvailable", hot, "%")
    emitNumber(f, "@TANK", "tankHealth", "%")
    emitNumber(f, "@COMBUSTION", "compressorHealth", "%")
    String valve = valveState(f["@VALVESTATUS"])
    if (valve) emitAttr("shutoffValve", valve)
    emitNumber(f, "@ALERTCOUNT", "alertCount", null)
    if (f.containsKey("@AWAY")) emitAttr("away", truthy(val(f["@AWAY"])) ? "on" : "off")
    if (f.containsKey("@VACATION")) emitAttr("vacation", truthy(val(f["@VACATION"])) ? "on" : "off")
    if (f.containsKey("@SCHEDULESTATUS")) emitAttr("scheduleStatus", oneLine(val(f["@SCHEDULESTATUS"])) ?: "none")
    def signal = val(f["@SIGNAL"])
    if (isNumeric(signal)) sendEvent(name: "wifiSignal", value: new BigDecimal(signal.toString()), unit: "dBm")  // changes often; not logged
    def version = val(f["@VERSION"])
    if (version != null && version.toString()) emitAttr("firmwareVersion", version.toString())
    sendSupportedModes()
    updateHealth()
}

private void applyMode(Map f) {
    Boolean enabled = enabledFlag(f)
    String name = currentModeName(f)
    if (enabled == null && f["@MODE"] == null && !f.containsKey("@SETPOINT")) return
    String mode = enabled == false ? "off" : (name != null ? thermostatModeFor(name) : "heat")
    emitAttr("thermostatMode", mode)
    String heaterMode = enabled == false ? "OFF" : (name ?: (enabled != null ? "ON" : null))
    if (heaterMode != null) emitAttr("waterHeaterMode", heaterMode)
    List<String> names = modeList(f)
    if (names) emitEvent(name: "supportedWaterHeaterModes", value: JsonOutput.toJson(names))
}

private Boolean enabledFlag(Map f) {
    return f.containsKey("@ENABLED") ? truthy(val(f["@ENABLED"])) : null
}

// The heater's own mode names, trimmed, in Rheem's order (a mode's value is its position).
private List<String> modeList(Map f) {
    def mode = f["@MODE"]
    def names = mode instanceof Map && mode.constraints instanceof Map ? mode.constraints.enumText : null
    return names instanceof List ? names.collect { it.toString().trim() } : []
}

private String currentModeName(Map f) {
    def mode = f["@MODE"]
    if (mode == null) return null
    List<String> names = modeList(f)
    def v = val(mode)
    Integer i = isNumeric(v) ? (v.toString() as BigDecimal).intValue() : null
    if (i != null && i >= 0 && i < names.size()) return names[i]
    String status = mode instanceof Map ? mode.status?.toString()?.trim() : null
    return status ?: null
}

// pyeconet's matching: case, spaces, underscores and slashes don't matter.
private String canonicalMode(Object name) {
    String n = name == null ? "" : name.toString().toUpperCase().replaceAll(/[\s_\/]/, "")
    return MODE_ALIASES[n] ?: n
}

// A mode name it doesn't know (such as ELECTRIC/GAS) counts as heat.
private String thermostatModeFor(String name) {
    switch (canonicalMode(name)) {
        case "ENERGYSAVING":
            return "auto"
        case "HIGHDEMAND":
        case "PERFORMANCE":
            return "emergency heat"
        case "OFF":
            return "off"
        default:
            return "heat"
    }
}

private Integer hotWaterPercent(Object raw) {
    if (raw == null) return null
    String icon = (raw instanceof Map ? (raw.icon ?: raw.value) : raw)?.toString() ?: ""
    if (icon.contains("hundread_percent") || icon.contains("hundred_percent")) return 100  // Rheem's spelling
    if (icon.contains("fourty_percent")) return 66   // the app shows it as 2/3 full
    if (icon.contains("ten_percent")) return 33      // the app shows it as 1/3 full
    if (icon.contains("empty") || icon.contains("zero_percent")) return 0
    logDebug "Unknown hot water icon: ${icon}"
    return null
}

private String valveState(Object raw) {
    String title = raw instanceof Map ? raw.title?.toString() : null
    if (title == "Shut-OFF Valve - Open") return "open"
    if (title == "Shut-OFF Valve - Closed") return "closed"
    return null
}

// Rheem's status text can span lines ("Schedule overridden\n Until 10:00 AM"); keep it on one.
private String oneLine(Object text) {
    return text == null ? "" : text.toString().replaceAll(/\s+/, " ").trim()
}

private boolean isNumeric(Object v) {
    return v != null && !(v instanceof Boolean) && v.toString().trim().isNumber()
}

private void emitNumber(Map f, String field, String name, String unit) {
    def v = val(f[field])
    if (isNumeric(v)) emitAttr(name, new BigDecimal(v.toString()), unit)
}

private void emitAttr(String name, Object value, String unit = null) {
    String shown = unit == null ? "${value}" : (unit in ["%", "°F"] ? "${value}${unit}" : "${value} ${unit}")
    Map evt = [name: name, value: value, descriptionText: "${device.displayName} ${name} is ${shown}"]
    if (unit) evt.unit = unit
    emitEvent(evt)
}

def refresh() {
    logDebug "Asking the app for a reading"
    pullSnapshot()
}

// Reads this heater from Rheem through the app. False when the app couldn't.
private boolean pullSnapshot() {
    def equip = parent.refreshEquipment(device.deviceNetworkId)
    if (!(equip instanceof Map)) return false
    applySnapshot(equip as Map)
    return true
}

private Map heaterFields() {
    return (state.heaterFields ?: [:]) as Map
}

private def val(Object field) {
    return field instanceof Map ? field.value : field
}

private boolean truthy(Object v) {
    return v == true || v?.toString() in ["1", "true"]
}

private void sendSupportedModes() {
    Map f = heaterFields()
    List<String> modes
    if (f) {
        modes = f["@MODE"] != null ? ["off", "heat", "emergency heat", "auto"] : (f.containsKey("@ENABLED") ? ["off", "heat"] : [])
    } else if (device.getDataValue("enabledDisabled") == "true") {
        modes = ["off", "heat"]
    } else if (device.getDataValue("tempOnly") == "true") {
        modes = []
    } else {
        modes = ["off", "heat", "emergency heat", "auto"]
    }
    emitEvent(name: "supportedThermostatModes", value: JsonOutput.toJson(modes))
}

private String duration(long ms) {
    long min = ms.intdiv(60_000L)
    if (min < 1) return "${ms.intdiv(1000L)} s"
    if (min < 120) return min < 60 ? "${min} min" : "1 h"
    long h = min.intdiv(60L)
    return h < 48 ? "${h} h" : "${h.intdiv(24L)} days"
}

// ---- Commands ----

def setHeatingSetpoint(temperature) {
    List<BigDecimal> limits = setpointLimits()
    if (limits == null) {
        log.warn "${device.displayName} doesn't report a setpoint; setHeatingSetpoint isn't supported"
        return
    }
    BigDecimal asked = new BigDecimal(temperature.toString())
    BigDecimal target = asked.max(limits[0]).min(limits[1])
    if (target != asked) log.info "${device.displayName} setpoint ${asked}°F is outside ${limits[0]}–${limits[1]}°F; sending ${target}°F"
    publishWithRetry(["@SETPOINT": Math.round(target.doubleValue()) as Integer])
}

// The heater's limits from its @SETPOINT field, or before the first reading from the data values.
private List<BigDecimal> setpointLimits() {
    def setpoint = heaterFields()["@SETPOINT"]
    def c = setpoint instanceof Map ? setpoint.constraints : null
    def lo = c instanceof Map ? c.lowerLimit : device.getDataValue("minTemp")
    def hi = c instanceof Map ? c.upperLimit : device.getDataValue("maxTemp")
    if (!isNumeric(lo) || !isNumeric(hi)) return null
    return [new BigDecimal(lo.toString()), new BigDecimal(hi.toString())]
}

def setThermostatMode(String mode) {
    switch (mode) {
        case "heat":
            heat()
            break
        case "off":
            off()
            break
        case "auto":
            auto()
            break
        case "emergency heat":
            emergencyHeat()
            break
        default:
            log.warn "${device.displayName}: thermostat mode ${mode} is not supported by a water heater"
    }
}

def heat() {
    Map msg = onOffMessage(true)
    if (msg) publishWithRetry(msg)
    else log.warn "${device.displayName} has no on/off or modes; heat isn't supported"
}

def off() {
    Map msg = onOffMessage(false)
    if (msg) publishWithRetry(msg)
    else log.warn "${device.displayName} has no on/off or modes; off isn't supported"
}

def auto() {
    selectMode("auto", ["Energy Saving"])
}

def emergencyHeat() {
    selectMode("emergencyHeat", ["High Demand", "Performance"])
}

def setWaterHeaterMode(String requested) {
    Map f = heaterFields()
    List<String> names = modeList(f)
    boolean turnOff = canonicalMode(requested) == "OFF"
    if (names) {
        Integer i = modeIndex(names, requested)
        if (i == null && !(turnOff && hasOnOff(f))) {
            log.warn "${device.displayName} doesn't offer mode ${requested}; it offers ${names.join(', ')}"
            return
        }
        Map msg = [:]
        if (hasOnOff(f)) msg["@ENABLED"] = turnOff ? 0 : 1
        if (i != null) msg["@MODE"] = i
        publishWithRetry(msg)
    } else if (hasOnOff(f)) {
        publishWithRetry(["@ENABLED": turnOff ? 0 : 1])
    } else {
        log.warn "${device.displayName} has no modes or on/off; setWaterHeaterMode isn't supported"
    }
}

def setAway(String value) {
    Map f = heaterFields()
    if (!(f.containsKey("@AWAY") || f.containsKey("@AWAYCONFIG"))) {
        log.warn "${device.displayName} doesn't report away mode; setAway isn't supported"
        return
    }
    publishWithRetry(["@AWAY": value == "on"])
}

def resumeSchedule() {
    if (!heaterFields().containsKey("@SCHEDULESTATUS")) {
        log.warn "${device.displayName} doesn't report a schedule; resumeSchedule isn't supported"
        return
    }
    state.resumeRequestedAt = now()
    publishWithRetry(["@SCHEDULERESUME": "resume"])
}

// Rheem asks for a confirmation before a schedule resume ends an away event, and waits for
// the same payload to be sent again (pyeconet). Confirm only a dialog that arrives within
// 60 s of our own resumeSchedule, and only once. pyeconet sends back the dialog's message;
// when that is text rather than fields, send our resume again.
private void confirmDialog(Map dialog) {
    Long asked = state.resumeRequestedAt as Long
    state.remove("resumeRequestedAt")
    if (asked == null || now() - asked > DIALOG_WINDOW_MS) {
        logDebug "Ignored a dialog from Rheem: ${dialog}"
        return
    }
    Map confirm = dialog.message instanceof Map ? dialog.message as Map : ["@SCHEDULERESUME": "resume"]
    log.info "${device.displayName} confirming Rheem's prompt${dialog.title ? " (${dialog.title})" : ''} to resume the schedule"
    publishWithRetry(confirm)
}

def cool() { notSupported("cool") }

def fanAuto() { notSupported("fanAuto") }

def fanCirculate() { notSupported("fanCirculate") }

def fanOn() { notSupported("fanOn") }

def setCoolingSetpoint(temperature) { notSupported("setCoolingSetpoint") }

def setThermostatFanMode(fanmode) { notSupported("setThermostatFanMode") }

def setSchedule(obj) { notSupported("setSchedule") }

private void notSupported(String command) {
    log.warn "${device.displayName}: ${command} is not supported by a water heater"
}

private boolean hasOnOff(Map f) {
    return f ? f.containsKey("@ENABLED") : device.getDataValue("enabledDisabled") == "true"
}

// @ENABLED where the heater has it, plus a mode where it has modes: Off, or for heat its
// current heating mode, else its first of Heat Pump Only, Electric and Gas.
private Map onOffMessage(boolean on) {
    Map f = heaterFields()
    List<String> names = modeList(f)
    Map msg = [:]
    if (hasOnOff(f)) msg["@ENABLED"] = on ? 1 : 0
    if (names) {
        Integer i = on ? heatingModeIndex(f, names) : modeIndex(names, "Off")
        if (i != null) msg["@MODE"] = i
    }
    return msg
}

private Integer heatingModeIndex(Map f, List<String> names) {
    String current = currentModeName(f)
    if (current != null && thermostatModeFor(current) == "heat" && canonicalMode(current) != "VACATION") {
        Integer i = modeIndex(names, current)
        if (i != null) return i
    }
    return ["Heat Pump Only", "Electric", "Gas"].findResult { modeIndex(names, it) }
}

private void selectMode(String command, List<String> wanted) {
    Map f = heaterFields()
    List<String> names = modeList(f)
    Integer i = wanted.findResult { modeIndex(names, it) }
    if (i == null) {
        log.warn "${device.displayName} has no ${wanted.join(' or ')} mode, so ${command} isn't supported${names ? "; it offers ${names.join(', ')}" : ''}"
        return
    }
    Map msg = ["@MODE": i]
    if (hasOnOff(f)) msg["@ENABLED"] = 1
    publishWithRetry(msg)
}

// The position of `requested` in the heater's own list, or null. "Electric/Gas" stands in
// for Electric or Gas, either way round.
private Integer modeIndex(List<String> names, Object requested) {
    String want = canonicalMode(requested)
    int i = names.findIndexOf { canonicalMode(it) == want }
    if (i < 0 && want in ["ELECTRIC", "GAS"]) i = names.findIndexOf { canonicalMode(it) == "ELECTRICGAS" }
    if (i < 0 && want == "ELECTRICGAS") i = names.findIndexOf { canonicalMode(it) in ["ELECTRIC", "GAS"] }
    return i < 0 ? null : i
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
