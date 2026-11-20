import harness.HubitatHarness

import static harness.TestRunner.finish
import static harness.TestRunner.test

final String DRIVER = "RadonFanSensor/device/15270-radon-fan.groovy"

def fresh = {
    def d = HubitatHarness.load(DRIVER)
    d.device.displayName = "Radon Fan"
    d
}

// ---- Metadata ----

test("harness loads the driver and records its metadata") {
    def d = fresh()
    assert d.definition.name == "Radon Fan Sensor"
    assert d.definition.namespace == "dlaporte"
    assert d.capabilities.containsAll(["Sensor", "Battery", "ContactSensor", "Refresh"])
    assert d.attributes*.name.containsAll(["switch", "lastStartedAt", "lastStoppedAt", "fanAlarm"])
    assert d.inputs*.name.contains("alarmAfterMinutes")
}

test("definition follows STYLE.md and declares Initialize") {
    def d = fresh()
    assert d.definition.author == "David LaPorte"
    assert d.definition.importUrl == "https://raw.githubusercontent.com/dlaporte/Hubitat/main/RadonFanSensor/device/15270-radon-fan.groovy"
    assert d.definition.singleThreaded == true
    assert d.capabilities.contains("Initialize")
    assert d.fingerprints.isEmpty()
}

test("preferences: logging inputs replace the old debug input") {
    def d = fresh()
    assert d.inputs*.name.containsAll(["alarmAfterMinutes", "logEnable", "txtEnable"])
    assert !d.inputs*.name.contains("debug")
    assert d.inputs*.name.takeRight(2) == ["logEnable", "txtEnable"]
}

// ---- Lifecycle ----

test("initialize retires the debug setting and records driverVersion") {
    def d = fresh()
    d.call("initialize")
    assert d.device.removedSettings.contains("debug")
    assert d.device.data.driverVersion == "0.10.0"
    assert d.unschedules.first() == "*"
}

test("initialize arms logsOff after clearing schedules when debug is on") {
    def d = fresh()
    d.settings.logEnable = true
    d.call("initialize")
    assert d.schedules.logsOff == 1800
}

test("initialize does not arm logsOff when debug is off") {
    def d = fresh()
    d.call("initialize")
    assert !d.schedules.containsKey("logsOff")
}

test("updated logs once at info and runs initialize") {
    def d = fresh()
    d.settings.logEnable = true
    d.call("updated")
    assert d.log.at("info").contains("Preferences saved")
    assert d.device.data.driverVersion == "0.10.0"
    assert d.schedules.logsOff == 1800
}

test("installed runs initialize") {
    def d = fresh()
    d.call("installed")
    assert d.device.data.driverVersion == "0.10.0"
}

test("logsOff turns debug logging off") {
    def d = fresh()
    d.settings.logEnable = true
    d.call("logsOff")
    assert d.settings.logEnable == false
    assert d.device.settingUpdates.last() == [name: "logEnable", value: "false", type: "bool"]
}

test("initialize re-arms the fan alarm for the remaining window") {
    def d = fresh()
    d.device.current.contact = "open"
    d.device.current.fanAlarm = "ok"
    d.device.current.lastStoppedAt = d.iso(d.clock - 10 * 60 * 1000L)
    d.call("initialize")
    assert d.schedules.checkFanAlarm == 20 * 60
}

// ---- Logging ----

test("debug lines only when logEnable is on") {
    def d = fresh()
    d.call("parse", "zw device: 0A, command: 3003, payload: FF")
    assert d.log.at("debug").isEmpty()
    d.settings.logEnable = true
    d.call("parse", "zw device: 0A, command: 3003, payload: FF")
    assert !d.log.at("debug").isEmpty()
}

test("battery report: descriptionText logged once per change when txtEnable is unset") {
    def d = fresh()
    d.call("zwaveEvent", d.zw("batteryv1.BatteryReport", batteryLevel: 80))
    assert d.eventsNamed("battery").last().value == 80
    assert d.log.at("info") == ["Radon Fan battery is 80%"]
    d.call("zwaveEvent", d.zw("batteryv1.BatteryReport", batteryLevel: 80))
    assert d.log.at("info").size() == 1
}

test("battery report: no descriptionText line when txtEnable is false") {
    def d = fresh()
    d.settings.txtEnable = false
    d.call("zwaveEvent", d.zw("batteryv1.BatteryReport", batteryLevel: 80))
    assert d.eventsNamed("battery").last().value == 80
    assert d.log.at("info").isEmpty()
}

test("Err messages are logged as warnings, not nameless events") {
    def d = fresh()
    def result = d.call("parse", "Err 106: something failed")
    assert result == null
    assert d.events.isEmpty()
    assert d.log.at("warn").any { it.contains("Err 106") }
}

test("wake-up returns only commands, with NOMI last") {
    def d = fresh()
    d.state.MSR = "014F-2001-0102"
    def result = d.call("zwaveEvent", d.zw("wakeupv1.WakeUpNotification"))
    assert result.every { it instanceof String }
    assert result.last() == "response:wakeUpV1.wakeUpNoMoreInformation()"
}

test("MSR report stores the MSR and returns only commands") {
    def d = fresh()
    def result = d.call("zwaveEvent",
            d.zw("manufacturerspecificv2.ManufacturerSpecificReport", manufacturerId: 0x14F, productTypeId: 0x2001, productId: 0x102))
    assert d.state.MSR == "014F-2001-0102"
    assert d.device.data.MSR == "014F-2001-0102"
    assert result.every { it instanceof String }
}

test("unhandled commands produce no events") {
    def d = fresh()
    def result = d.call("zwaveEvent", d.zw("Command"))
    assert result == null
}

// ---- Fan state ----

test("declares healthStatus and the Invert sensor preference") {
    def d = fresh()
    assert d.attributes.find { it.name == "healthStatus" }?.values == ["online", "offline"]
    def invert = d.inputs.find { it.name == "invertSensor" }
    assert invert?.type == "bool" && invert.defaultValue == false
}

test("stop report: open/off, lastStoppedAt, alarm check armed") {
    def d = fresh()
    d.device.current.contact = "closed"
    d.call("sensorValueEvent", 0)
    assert d.device.current.contact == "open"
    assert d.device.current.switch == "off"
    assert d.eventsNamed("lastStoppedAt").size() == 1
    assert d.schedules.checkFanAlarm == 1800
    assert d.log.at("info") == ["Radon Fan is not running"]
    // Rule 815 on the cabin hub triggers on switch; keep the wording it has always seen.
    assert d.eventsNamed("switch").last().descriptionText == "Radon Fan is not running"
}

test("repeat stop report: no events, no new runIn") {
    def d = fresh()
    d.device.current.contact = "closed"
    d.call("sensorValueEvent", 0)
    d.reset()
    d.call("sensorValueEvent", 0)
    assert d.events.isEmpty()
    assert d.runIns.isEmpty()
}

test("repeat report is ignored even when the contact attribute lags") {
    def d = fresh()
    d.device.current.contact = "closed"
    d.call("sensorValueEvent", 0)
    d.device.staleValues.contact = "closed"     // attribute hasn't caught up yet
    d.reset()
    d.call("sensorValueEvent", 0)
    assert d.events.isEmpty()
    assert d.runIns.isEmpty()
}

test("first report after deploy takes the prior state from contact") {
    def d = fresh()
    d.device.current.contact = "closed"         // state.fanRunning not set yet
    d.call("sensorValueEvent", 0xFF)
    assert d.events.isEmpty()
    d.call("sensorValueEvent", 0)
    assert d.device.current.contact == "open"
}

test("start report: closed/on, fanAlarm ok, alarm check cancelled") {
    def d = fresh()
    d.device.current.contact = "closed"
    d.call("sensorValueEvent", 0)
    d.call("sensorValueEvent", 0xFF)
    assert d.device.current.contact == "closed"
    assert d.device.current.switch == "on"
    assert d.device.current.fanAlarm == "ok"
    assert d.eventsNamed("lastStartedAt").size() == 1
    assert !d.schedules.containsKey("checkFanAlarm")
    assert d.eventsNamed("switch").last().descriptionText == "Radon Fan is running"
}

test("BasicSet and type-7 notifications reach the same logic") {
    def d = fresh()
    d.device.current.contact = "open"
    d.call("zwaveEvent", d.zw("basicv1.BasicSet", value: 0xFF))
    assert d.device.current.contact == "closed"
    d.zwave.next = d.zw("notificationv3.NotificationReport",
            notificationType: 7, v1AlarmType: 7, v1AlarmLevel: 0, event: 0xFE, eventParameter: [])
    def result = d.call("parse", "zw device: 0A, command: 7105, payload: 07 00 00 FF 07 FE 00")
    assert result == []
    assert d.device.current.contact == "open"
}

// ---- Invert ----

test("Invert sensor: raw 0 reads as running") {
    def d = fresh()
    d.settings.invertSensor = true
    d.device.current.contact = "open"
    d.call("sensorValueEvent", 0)
    assert d.device.current.contact == "closed"
}

test("turning Invert on and saving flips the stored state at once") {
    def d = fresh()
    d.device.current.contact = "closed"
    d.call("sensorValueEvent", 0)
    assert d.device.current.contact == "open"
    d.settings.invertSensor = true
    d.call("updated")
    assert d.device.current.contact == "closed"
    assert !d.schedules.containsKey("checkFanAlarm")
}

// ---- Fan alarm ----

test("checkFanAlarm while stopped sets alarm once") {
    def d = fresh()
    d.device.current.contact = "closed"
    d.call("sensorValueEvent", 0)
    d.call("checkFanAlarm")
    assert d.device.current.fanAlarm == "alarm"
    assert d.log.at("info").last() == "Radon Fan has been off for 30 minutes"
    d.reset()
    d.call("checkFanAlarm")
    assert d.events.isEmpty()
}

test("checkFanAlarm does nothing while the fan runs") {
    def d = fresh()
    d.device.current.contact = "open"
    d.call("sensorValueEvent", 0xFF)
    d.reset()
    d.call("checkFanAlarm")
    assert d.events.isEmpty()
}

test("after a reboot an alarm that already fired is not re-armed") {
    def d = fresh()
    d.device.current.contact = "open"
    d.device.current.fanAlarm = "alarm"
    d.device.current.lastStoppedAt = d.iso(d.clock - 3 * 3600 * 1000L)
    d.call("initialize")
    assert !d.schedules.containsKey("checkFanAlarm")
}

test("a new threshold applies on save, from the time already elapsed") {
    def d = fresh()
    d.device.current.contact = "open"
    d.device.current.fanAlarm = "ok"
    d.device.current.lastStoppedAt = d.iso(d.clock - 10 * 60 * 1000L)
    d.settings.alarmAfterMinutes = 60
    d.call("updated")
    assert d.schedules.checkFanAlarm == 50 * 60
}

// ---- Health ----

test("any message marks the sensor online and arms the offline timer (interval unknown: 7 days)") {
    def d = fresh()
    d.call("parse", "zw device: 0A, command: 8407, payload: ")
    assert d.state.lastCheckinMs == d.clock
    assert d.device.current.healthStatus == "online"
    assert d.schedules.markOffline == 7 * 24 * 3600
}

test("offline timer is 2x the reported wake-up interval plus an hour") {
    def d = fresh()
    d.state.wakeUpIntervalSec = 14400
    d.call("parse", "zw device: 0A, command: 8407, payload: ")
    assert d.schedules.markOffline == 2 * 14400 + 3600
}

test("a reported interval of 0 counts as unknown") {
    def d = fresh()
    d.state.wakeUpIntervalSec = 0
    d.call("parse", "zw device: 0A, command: 8407, payload: ")
    assert d.schedules.markOffline == 7 * 24 * 3600
}

test("WakeUpIntervalReport stores the interval and reschedules") {
    def d = fresh()
    d.call("zwaveEvent", d.zw("wakeupv1.WakeUpIntervalReport", seconds: 86400, nodeid: 1))
    assert d.state.wakeUpIntervalSec == 86400
    assert d.schedules.markOffline == 2 * 86400 + 3600
}

test("initialize with no stored check-in starts a full window") {
    def d = fresh()
    d.device.lastActivity = new Date(d.clock - 30L * 24 * 3600 * 1000)   // stale; must not be used
    d.call("initialize")
    assert d.schedules.markOffline == 7 * 24 * 3600
}

test("initialize re-arms the offline timer for the remaining window") {
    def d = fresh()
    d.state.wakeUpIntervalSec = 14400
    d.state.lastCheckinMs = d.clock - 3600 * 1000L
    d.call("initialize")
    assert d.schedules.markOffline == 2 * 14400 + 3600 - 3600
}

test("markOffline warns once and sets offline") {
    def d = fresh()
    d.state.lastCheckinMs = d.clock - 50L * 3600 * 1000
    d.call("markOffline")
    assert d.device.current.healthStatus == "offline"
    assert d.log.at("warn").size() == 1
    assert d.log.at("warn")[0].contains("50 hours")
    d.reset()
    d.call("markOffline")
    assert d.events.isEmpty() && d.log.at("warn").isEmpty()
}

// ---- Wake-up and refresh ----

test("wake-up asks for the interval while it is unknown") {
    def d = fresh()
    d.state.MSR = "014F-2001-0102"
    d.state.lastbat = d.clock
    def result = d.call("zwaveEvent", d.zw("wakeupv1.WakeUpNotification"))
    assert result.contains("response:wakeUpV1.wakeUpIntervalGet()")
    assert !result.contains("response:batteryV1.batteryGet()")
    assert result.last() == "response:wakeUpV1.wakeUpNoMoreInformation()"
}

test("wake-up with a known interval and no refresh pending sends only NOMI") {
    def d = fresh()
    d.state.MSR = "014F-2001-0102"
    d.state.lastbat = d.clock
    d.state.wakeUpIntervalSec = 86400
    def result = d.call("zwaveEvent", d.zw("wakeupv1.WakeUpNotification"))
    assert result == ["response:wakeUpV1.wakeUpNoMoreInformation()"]
}

test("refresh queues battery and interval for the next wake-up") {
    def d = fresh()
    d.state.MSR = "014F-2001-0102"
    d.state.lastbat = d.clock
    d.state.wakeUpIntervalSec = 86400
    def sent = d.call("refresh")
    assert !sent
    assert d.state.refreshPending == true
    assert d.log.at("info").any { it.contains("next wakes up") }
    def result = d.call("zwaveEvent", d.zw("wakeupv1.WakeUpNotification"))
    assert result.contains("response:batteryV1.batteryGet()")
    assert result.contains("response:wakeUpV1.wakeUpIntervalGet()")
    assert result.last() == "response:wakeUpV1.wakeUpNoMoreInformation()"
    assert !d.state.containsKey("refreshPending")
}

finish()
