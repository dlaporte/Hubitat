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

finish()
