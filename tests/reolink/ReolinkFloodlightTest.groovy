import harness.HubitatHarness

import static harness.TestRunner.finish
import static harness.TestRunner.test

final String DRIVER = "ReolinkFloodlight/device/Reolink-Floodlight.groovy"

def fresh = {
    def d = HubitatHarness.load(DRIVER)
    d.device.displayName = "Driveway Camera"
    d.settings.putAll(ip: "10.0.0.5", username: "admin", password: "secret", pollInterval: 5, keepLightOn: true)
    d
}

// Reolink's reply to a successful Login.
def loginOk = { String token = "tok-abcd" -> [[cmd: "Login", code: 0, value: [Token: [name: token, leaseTime: 3600]]]] }

// The cmd names in a recorded camera request body.
def cmdsOf = { Map req -> new groovy.json.JsonSlurper().parseText(req.params.body as String)*.cmd }

// Give the driver a valid session without going through initialize().
def withToken = { d ->
    d.state.token = "tok-live"
    d.state.tokenExpiry = d.clock + 3_000_000L
    d
}

// ---- Metadata ----

test("definition follows STYLE.md") {
    def d = fresh()
    assert d.definition.name == "Reolink Floodlight Camera"
    assert d.definition.namespace == "dlaporte"
    assert d.definition.author == "David LaPorte"
    assert d.definition.importUrl == "https://raw.githubusercontent.com/dlaporte/Hubitat/main/ReolinkFloodlight/device/Reolink-Floodlight.groovy"
    assert d.definition.singleThreaded == true
    assert d.capabilities.containsAll(["Initialize", "Refresh", "Switch", "SwitchLevel", "MotionSensor"])
}

test("preferences keep their names; logging inputs replace debug") {
    def d = fresh()
    assert d.inputs*.name == ["ip", "username", "password", "pollInterval", "keepLightOn", "publishSnapshotURL", "logEnable", "txtEnable"]
}

// ---- Lifecycle ----

test("initialize removes the old debug setting and records driverVersion") {
    def d = fresh()
    d.settings.debug = true
    d.call("initialize")
    assert d.device.removedSettings.contains("debug")
    assert d.device.data.driverVersion == "0.10.0"
}

test("logsOff is scheduled after unschedule, so Initialize can't cancel it") {
    def d = fresh()
    d.settings.logEnable = true
    d.call("initialize")
    assert d.unschedules == ["*"]
    assert d.schedules.logsOff == 1800
}

test("initialize logs in, then the queued refresh runs; the poll loop starts") {
    def d = fresh()
    d.call("initialize")
    assert d.schedules.pollStatus == 1
    def login = d.httpMatching("cmd=Login")
    assert login.size() == 1
    d.reply(login[0], json: loginOk())
    assert d.state.token == "tok-abcd"
    def refresh = d.httpMatching("cmd=GetDevInfo")
    assert refresh.size() == 1
    assert refresh[0].params.uri.contains("token=tok-abcd")
}

test("updated logs 'Preferences saved' and re-initializes") {
    def d = fresh()
    d.call("updated")
    assert d.log.at("info").contains("Preferences saved")
    assert d.device.data.driverVersion == "0.10.0"
}

// ---- Logging ----

test("descriptionText is logged on change, and not when txtEnable is false") {
    def d = withToken(fresh())
    d.call("pollStatusHandler", [[cmd: "GetMdState", code: 0, value: [state: 1]]], [epoch: 0])
    assert d.log.at("info") == ["Driveway Camera motion is active"]
    d.call("pollStatusHandler", [[cmd: "GetMdState", code: 0, value: [state: 1]]], [epoch: 0])
    assert d.log.at("info").size() == 1          // unchanged value: no second line
    d.settings.txtEnable = false
    d.call("pollStatusHandler", [[cmd: "GetMdState", code: 0, value: [state: 0]]], [epoch: 0])
    assert d.log.at("info").size() == 1
    assert d.device.current.motion == "inactive"
}

test("debug lines only with logEnable, and no 'Reolink:' prefix anywhere") {
    def d = withToken(fresh())
    d.call("on")
    assert d.log.at("debug").isEmpty()
    d.settings.logEnable = true
    d.call("on")
    assert d.log.at("debug").any { it == "on()" }
    assert !new File(DRIVER).text.contains('"Reolink: ')
}

test("logsOff turns debug off") {
    def d = fresh()
    d.settings.logEnable = true
    d.call("logsOff")
    assert d.settings.logEnable == false
}

finish()
