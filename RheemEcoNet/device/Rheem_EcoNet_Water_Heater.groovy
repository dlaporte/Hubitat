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
 *  v2.4.0 (2026-11-23) - David LaPorte's fork. Reconnects with backoff (15 s up to every 30
 *                        minutes) and asks the app for a new login, with one warning per outage
 *                        instead of one every 15 seconds. Adds healthStatus, hot water, tank and
 *                        compressor health, shut-off valve, alerts, away, vacation, schedule,
 *                        Wi-Fi signal, firmware, and energy and water use today; setAway,
 *                        resumeSchedule and refresh. Modes come from the heater's own list. Fixes
 *                        setWaterHeaterMode("Off") on on/off heaters and the setpoint reading.
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

@Field static final String VERSION = "2.4.0"
@Field static String apiUrl = "ssl://rheem.clearblade.com:1884"
@Field static String systemKey = "e2e699cb0bb0bbb88fc8858cb5a401"
@Field static final List<Integer> RETRY_DELAYS = [15, 60, 300, 900, 1800]   // seconds
@Field static final int OUTAGE_WARN_SEC = 60
@Field static final long REMINDER_MS = 3_600_000L
@Field static final long STABLE_MS = 60_000L          // a connection this old resets the backoff when lost
@Field static final long QUEUE_MAX_AGE_MS = 900_000L  // a queued command older than this is dropped

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
    Map f = fields()
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
}

void applySnapshot(Map equip) {
}

def refresh() {
    logDebug "Asking the app for a reading"
    def equip = parent.refreshEquipment(device.deviceNetworkId)
    if (equip instanceof Map) applySnapshot(equip as Map)
}

private Map fields() {
    return (state.fields ?: [:]) as Map
}

private def val(Object field) {
    return field instanceof Map ? field.value : field
}

private boolean truthy(Object v) {
    return v == true || v?.toString() in ["1", "true"]
}

private void sendSupportedModes() {
    Map f = fields()
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
