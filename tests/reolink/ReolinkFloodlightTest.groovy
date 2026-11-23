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

test("the keep-on setting's title says it acts only after On, until Off") {
    def d = fresh()
    Map keep = d.inputs.find { it.name == "keepLightOn" }
    assert keep.title == "After On, keep the floodlight on until Off (sends On again every 2 min)"
    assert keep.description == "For cameras that turn a manually switched-on light off by themselves. Has no effect on cameras with Reolink's own keep-on."
    assert keep.defaultValue == true
}

// ---- Lifecycle ----

test("initialize removes the old debug setting and records driverVersion") {
    def d = fresh()
    d.settings.debug = true
    d.call("initialize")
    assert d.device.removedSettings.contains("debug")
    assert d.device.data.driverVersion == "0.10.1"
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
    assert d.device.data.driverVersion == "0.10.1"
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

// ---- Fixes: expired token ----

// Reolink reports errors as code 1 with error.rspCode; -6 is "please login first".
def expiredReply = { String cmd -> [[cmd: cmd, code: 1, error: [rspCode: -6, detail: "please login first"]]] }

test("an expired-token reply (code 1, rspCode -6) logs in again and retries once") {
    def d = withToken(fresh())
    d.call("setIRMode", "Auto")
    d.reply(d.httpMatching("cmd=SetIrLights")[0], json: expiredReply("SetIrLights"))
    def login = d.httpMatching("cmd=Login")
    assert login.size() == 1
    d.reply(login[0], json: loginOk("tok-new"))
    def retry = d.httpMatching("cmd=SetIrLights")
    assert retry.size() == 1 && retry[0].params.uri.contains("token=tok-new")
    d.reply(retry[0], json: [[cmd: "SetIrLights", code: 0, value: [rspCode: 200]]])
    assert d.device.current.IRMode == "Auto"
}

test("a second expired reply after the retry doesn't loop") {
    def d = withToken(fresh())
    d.call("setIRMode", "Auto")
    d.reply(d.httpMatching("cmd=SetIrLights")[0], json: expiredReply("SetIrLights"))
    d.reply(d.httpMatching("cmd=Login")[0], json: loginOk())
    d.reply(d.httpMatching("cmd=SetIrLights")[0], json: expiredReply("SetIrLights"))
    assert d.http.isEmpty()
    assert d.log.at("error").any { it.startsWith("setIRMode failed") }
}

test("an expired reply to Logout doesn't log in just to log out") {
    def d = withToken(fresh())
    d.call("uninstalled")
    d.reply(d.httpMatching("cmd=Logout")[0], json: expiredReply("Logout"))
    assert d.http.isEmpty()
}

// ---- Fixes: one login at a time ----

test("a poll during a login starts no second login and is dropped") {
    def d = fresh()
    d.call("pollStatus")
    d.call("pollStatus")
    assert d.httpMatching("cmd=Login").size() == 1
    assert d.http.size() == 1
}

test("a command during a login is queued and sent once the login succeeds") {
    def d = fresh()
    d.call("pollStatus")
    d.call("setIRMode", "Auto")
    assert d.httpMatching("cmd=Login").size() == 1
    d.reply(d.httpMatching("cmd=Login")[0], json: loginOk())
    assert d.httpMatching("cmd=GetMdState").size() == 1
    assert d.httpMatching("cmd=SetIrLights").size() == 1
    assert !d.state.loginQueue
}

test("a failed login drops the queue; the next poll tries again") {
    def d = fresh()
    d.call("pollStatus")
    d.call("setIRMode", "Auto")
    d.reply(d.httpMatching("cmd=Login")[0], error: "connect timed out")
    assert d.http.isEmpty()
    assert !d.state.loginQueue
    d.call("pollStatus")
    assert d.httpMatching("cmd=Login").size() == 1
}

test("a login that never answered stops blocking after 30 s") {
    def d = fresh()
    d.call("pollStatus")
    d.advance(31_000L)
    d.call("pollStatus")
    assert d.httpMatching("cmd=Login").size() == 2
}

// ---- Fixes: initialize and refresh ----

test("initialize logs out a live session before logging in again") {
    def d = withToken(fresh())
    d.call("initialize")
    def logout = d.httpMatching("cmd=Logout")
    assert logout.size() == 1 && logout[0].params.uri.contains("token=tok-live")
    assert d.httpMatching("cmd=Login").size() == 1
}

test("initialize schedules scheduledRefresh every 30 minutes") {
    def d = fresh()
    d.call("initialize")
    assert d.schedules.scheduledRefresh == "runEvery30Minutes"
}

test("scheduledRefresh leaves out GetWhiteLed; refresh includes it") {
    def d = withToken(fresh())
    d.call("scheduledRefresh")
    def timed = cmdsOf(d.http[0])
    assert timed.contains("GetHddInfo") && !timed.contains("GetWhiteLed")
    d.reset()
    d.call("refresh")
    assert cmdsOf(d.http[0]).contains("GetWhiteLed")
}

test("an LED query on a camera without an LED isn't a warning, and isn't sent again") {
    def d = withToken(fresh())
    d.call("refreshHandler", [
        [cmd: "GetAbility", code: 0, value: [Ability: [abilityChn: [[supportFLKeepOn: [permit: 0], powerLed: [permit: 0]]]]]],
        [cmd: "GetPowerLed", code: 1, error: [rspCode: -9, detail: "not support"]],
    ], [:])
    assert d.log.at("warn").isEmpty()
    d.call("refresh")
    assert !cmdsOf(d.http[0]).contains("GetPowerLed")
}

test("floodlight mode names come from one table") {
    def d = withToken(fresh())
    d.call("pollStatusHandler", [[cmd: "GetWhiteLed", code: 0, value: [WhiteLed: [mode: 1]]]], [epoch: 0])
    assert d.device.current.floodlightMode == "NightSmart"
    d.call("pollStatusHandler", [[cmd: "GetWhiteLed", code: 0, value: [WhiteLed: [mode: 7]]]], [epoch: 0])
    assert d.device.current.floodlightMode == "Unknown"
    d.call("setFloodlightMode", "Schedule")
    assert cmdsOf(d.http[0]) == ["SetWhiteLed"]
    assert d.http[0].params.body.contains('"mode":3')
}

test("the four setter handlers still update their attributes") {
    def d = withToken(fresh())
    def ok = [[code: 0]]
    d.call("setIRModeHandler", ok, [mode: "Off"])
    d.call("setDayNightModeHandler", ok, [mode: "Color"])
    d.call("setMicrophoneHandler", ok, [value: "On"])
    d.call("setPowerLEDHandler", ok, [value: "Off"])
    assert d.device.current.subMap(["IRMode", "dayNightMode", "microphone", "powerLED"]) ==
            [IRMode: "Off", dayNightMode: "Color", microphone: "On", powerLED: "Off"]
    d.call("setIRModeHandler", [[code: 1]], [mode: "Auto"])
    assert d.log.at("error").any { it.startsWith("setIRMode failed") }
}

// ---- Fixes: outage handling and healthStatus ----

test("an outage logs one error and sets offline; recovery logs info and sets online") {
    def d = withToken(fresh())
    d.call("pollStatus")
    d.reply(d.http[0], error: "connect timed out")
    assert d.log.at("error").size() == 1
    assert d.device.current.healthStatus == "offline"
    d.advance(5_000L)
    d.call("pollStatus")
    d.reply(d.httpMatching("cmd=GetMdState")[0], error: "no route to host")
    assert d.log.at("error").size() == 1          // repeat suppressed
    d.advance(600_000L)
    d.call("pollStatus")
    d.reply(d.httpMatching("cmd=GetMdState")[0], json: [[cmd: "GetMdState", code: 0, value: [state: 0]]])
    assert d.log.at("info").any { it.contains("responding again after 10m") }
    assert d.device.current.healthStatus == "online"
}

test("healthStatus is declared") {
    assert fresh().attributes*.name.contains("healthStatus")
}

finish()
