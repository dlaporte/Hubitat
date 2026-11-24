import harness.HubitatHarness

import static harness.TestRunner.finish
import static harness.TestRunner.test

final String DRIVER = "ASH26/device/Amcrest-ASH26.groovy"

def fresh = {
    def d = HubitatHarness.load(DRIVER)
    d.device.displayName = "Backyard Floodlight"
    d.settings.putAll(ip: "10.0.0.30", username: "admin", password: "secret")
    d
}

final Map CHALLENGE = ["WWW-Authenticate": 'Digest realm="Login to AMC", qop="auth", nonce="abc123", opaque="xyz"']

// Answer the digest challenge for the oldest pending probe, then the authed call with `body`.
def answer = { d, String body ->
    def probe = d.http.find { it.callback == "unauthRequestHandler" }
    d.reply(probe, status: 401, headers: CHALLENGE)
    def authed = d.http.find { it.callback == "authedRequestHandler" }
    assert authed.params.headers.Authorization.startsWith('Digest username="admin"')
    d.reply(authed, body: body)
}

def settingsText = { String mode -> """table.All.General.MachineName=Backyard
table.All.VSP_PaaS.SN=ABC123
table.All.PaaS_UPGRADE.LastVersion=V2.0
table.All.Network.eth2.PhysicalAddress=aa:bb:cc:dd:ee:ff
table.All.WLan.eth2.SSID=home
table.All.Lighting_V2[0][0][1].Mode=${mode}
""" }

// The camera's event stream as the hub delivers it (Task 1 spike, 2026-11-23): one line
// per parse() call. Lines that end CRLF on the wire keep their "\r"; a JSON body is
// LF-only, so its lines arrive bare. Each part starts with the "\r" ending the last one.
def part = { String body ->
    List<String> lines = body.readLines()
    List<String> bodyLines = (lines.size() == 1) ? [lines[0] + "\r"] : lines
    ["\r", "--myboundary\r", "Content-Type: text/plain\r", "Content-Length:${body.length()}\r".toString(), "\r"] + bodyLines
}
def heartbeat = { -> part("Heartbeat") }
def pir = { String action, int index ->
    part("""Code=AlarmLocal;action=${action};index=${index};data={
   "EventID" : 8888898,
   "PTS" : 44951450300.0,
   "SenseMethod" : "PassiveInfrared",
   "UTC" : 1795446172.0
}""".toString())
}
def code = { String name, String action -> part("Code=${name};action=${action};index=0".toString()) }
def feed = { d, List<String> lines -> lines.each { d.call("parse", it) } }

// ---- Metadata ----

test("definition follows STYLE.md") {
    def d = fresh()
    assert d.definition.name == "Amcrest ASH26"
    assert d.definition.namespace == "dlaporte"
    assert d.definition.author == "David LaPorte"
    assert d.definition.importUrl == "https://raw.githubusercontent.com/dlaporte/Hubitat/main/ASH26/device/Amcrest-ASH26.groovy"
    assert d.definition.singleThreaded == true
    assert d.capabilities == ["Initialize", "Refresh", "Switch", "MotionSensor"]
}

test("preferences keep their names; the motion hold sits before the logging inputs") {
    def d = fresh()
    assert d.inputs*.name == ["ip", "username", "password", "motionHoldSec", "logEnable", "txtEnable"]
    def hold = d.inputs.find { it.name == "motionHoldSec" }
    assert hold.type == "number" && hold.defaultValue == 30
    assert hold.title == "Motion inactive after (seconds)"
}

// ---- Lifecycle ----

test("initialize removes debug, records driverVersion, polls every minute and fetches now") {
    def d = fresh()
    d.settings.debug = true
    d.call("initialize")
    assert d.device.removedSettings.contains("debug")
    assert d.device.data.driverVersion == "0.8.0"
    assert d.schedules.refresh == "runEvery1Minute"
    assert d.http.size() == 1 && d.http[0].params.uri.contains("getConfig&name=All")
}

test("logsOff is scheduled after unschedule, so Initialize can't cancel it") {
    def d = fresh()
    d.settings.logEnable = true
    d.call("initialize")
    assert d.unschedules == ["*"]
    assert d.schedules.logsOff == 1800
}

// ---- Logging ----

test("a poll sets switch from the light mode and logs descriptionText on change only") {
    def d = fresh()
    d.call("refresh")
    answer(d, settingsText("Manual"))
    assert d.device.current.switch == "on" && d.device.current.light_status == "on"
    assert d.device.current.device_name == "Backyard"
    assert d.log.at("info").contains("Backyard Floodlight is on")
    int n = d.log.at("info").size()
    d.call("refresh")
    answer(d, settingsText("Manual"))
    assert d.log.at("info").size() == n
}

test("no 'ASH26:' prefixes; debug only with logEnable") {
    def d = fresh()
    d.call("refresh")
    assert d.log.at("debug").isEmpty()
    d.settings.logEnable = true
    d.call("refresh")
    assert d.log.at("debug") == ["refresh()"]
    assert !new File(DRIVER).text.contains('"ASH26: ')
}

// ---- Fixes: switch follows the camera's confirmation ----

test("On sets switch on as soon as the camera confirms both steps") {
    def d = fresh()
    d.device.current.switch = "off"
    d.call("on")
    answer(d, "OK")                                  // AlarmLighting[0][0].Enable=false
    assert d.device.current.switch == "off"          // not until the mode is set
    assert d.http.size() == 1 && d.http[0].params.uri.contains("Lighting_V2[0][0][1].Mode=Manual")
    answer(d, "OK")
    assert d.device.current.switch == "on"
    assert d.device.current.light_status == "on"
    assert d.device.current.lightMode == "Manual"
}

test("Off sets switch off once confirmed") {
    def d = fresh()
    d.device.current.switch = "on"
    d.call("off")
    answer(d, "OK")
    answer(d, "OK")
    assert d.device.current.switch == "off" && d.device.current.lightMode == "Off"
}

test("a rejected step leaves switch alone and says the chain was abandoned") {
    def d = fresh()
    d.device.current.switch = "off"
    d.call("on")
    answer(d, "Error")
    assert d.device.current.switch == "off"
    assert d.http.isEmpty()
    assert d.log.at("error").any { it.contains("chain abandoned") }
}

// ---- Fixes: lightMode ----

test("lightMode shows modes other than Manual and Off; switch is left alone for them") {
    def d = fresh()
    d.device.current.switch = "off"
    d.call("refresh")
    answer(d, settingsText("Auto"))
    assert d.device.current.lightMode == "Auto"
    assert d.device.current.switch == "off"
}

test("lightMode and healthStatus are declared; old attributes kept") {
    def names = fresh().attributes*.name
    assert names.containsAll(["light_status", "device_name", "serial_number", "firmware_version", "mac_address",
                              "wireless_ssid", "lightMode", "healthStatus"])
}

// ---- Fixes: outage handling ----

test("an offline camera logs one error, then an hourly reminder, then a recovery line") {
    def d = fresh()
    d.call("refresh")
    d.reply(d.http[0], status: 408, error: "connect timed out")
    assert d.log.at("error").size() == 1
    assert d.log.at("error")[0].contains("isn't answering")
    assert d.device.current.healthStatus == "offline"
    d.advance(60_000L)
    d.call("refresh")
    d.reply(d.http[0], status: 408, error: "No route to host")
    assert d.log.at("error").size() == 1
    assert d.log.at("warn").isEmpty()
    d.advance(3_600_000L)
    d.call("refresh")
    d.reply(d.http[0], status: 408, error: "connect timed out")
    assert d.log.at("warn").size() == 1 && d.log.at("warn")[0].contains("still not answering after 1h 1m")
    d.advance(240_000L)
    d.call("refresh")
    answer(d, settingsText("Off"))
    assert d.log.at("info").any { it.contains("responding again after 1h 5m") }
    assert d.device.current.healthStatus == "online"
}

test("a rejected login is reported as a credentials problem") {
    def d = fresh()
    d.call("refresh")
    d.reply(d.http[0], status: 401, headers: CHALLENGE)
    d.reply(d.http[0], status: 401, headers: CHALLENGE)
    assert d.log.at("error").size() == 1 && d.log.at("error")[0].contains("rejected the login")
}

test("a command that fails during an outage still says so") {
    def d = fresh()
    d.state.commFailKey = "unreachable|10.0.0.30"          // outage already logged
    d.state.commFailSince = d.clock
    d.call("on")
    d.reply(d.http[0], status: 408, error: "connect timed out")
    assert d.log.at("error").any { it.startsWith("set AlarmLighting[0][0].Enable failed") }
}

// ---- 0.9.0: motion from the PIR sensors ----

test("the captured walk-by gives one active, then inactive 30 s after the last PIR stop") {
    def d = fresh()
    feed(d, heartbeat())
    assert d.eventsNamed("motion").isEmpty()
    feed(d, pir("Start", 1))
    assert d.eventsNamed("motion")*.value == ["active"]
    feed(d, code("AlarmInputSourceSignal", "Start"))
    feed(d, pir("Start", 0))
    feed(d, pir("Stop", 1))
    assert !d.schedules.containsKey("motionHoldExpired")
    feed(d, pir("Start", 1))
    feed(d, code("AlarmInputSourceSignal", "Stop"))
    feed(d, pir("Stop", 0))
    assert !d.schedules.containsKey("motionHoldExpired")
    feed(d, pir("Stop", 1))
    assert d.schedules.motionHoldExpired == 30
    assert d.eventsNamed("motion")*.value == ["active"]
    d.call("motionHoldExpired")
    assert d.eventsNamed("motion")*.value == ["active", "inactive"]
    assert d.device.current.motion == "inactive"
    assert d.log.at("info") == ["Backyard Floodlight motion is active", "Backyard Floodlight motion is inactive"]
}

test("a PIR start during the hold cancels it; a late hold expiry then changes nothing") {
    def d = fresh()
    feed(d, pir("Start", 2))
    feed(d, pir("Stop", 2))
    assert d.schedules.motionHoldExpired == 30
    feed(d, pir("Start", 2))
    assert !d.schedules.containsKey("motionHoldExpired")
    d.call("motionHoldExpired")
    assert d.device.current.motion == "active"
    assert d.eventsNamed("motion")*.value == ["active"]
}

test("the hold follows the preference: 0 goes inactive at once, 120 waits 120 s, 900 is capped at 600") {
    def d = fresh()
    d.settings.motionHoldSec = 0
    feed(d, pir("Start", 0))
    feed(d, pir("Stop", 0))
    assert d.device.current.motion == "inactive"
    assert !d.runIns.any { it[1] == "motionHoldExpired" }
    d.settings.motionHoldSec = 120
    feed(d, pir("Start", 0))
    feed(d, pir("Stop", 0))
    assert d.schedules.motionHoldExpired == 120
    d.settings.motionHoldSec = 900
    feed(d, pir("Start", 0))
    feed(d, pir("Stop", 0))
    assert d.schedules.motionHoldExpired == 600
}

test("only AlarmLocal moves motion; other codes and heartbeats are ignored") {
    def d = fresh()
    feed(d, code("VideoMotion", "Start"))
    feed(d, code("AlarmInputSourceSignal", "Start"))
    feed(d, heartbeat())
    assert d.eventsNamed("motion").isEmpty()
    assert d.state.pirActive == null
}

test("a repeated Start for one sensor counts once; indexes read back from state as Long still match") {
    def d = fresh()
    feed(d, pir("Start", 1))
    feed(d, pir("Start", 1))
    assert d.state.pirActive == [1]
    feed(d, pir("Stop", 1))
    assert d.schedules.motionHoldExpired == 30
    d.state.pirActive = [0L]
    d.state.motion = "active"
    feed(d, pir("Stop", 0))
    assert d.state.pirActive == []
}

test("initialize sets motion inactive, even mid-motion or without credentials, and forgets active sensors") {
    def d = fresh()
    d.state.motion = "active"
    d.state.pirActive = [1]
    d.device.current.motion = "active"
    d.call("initialize")
    assert d.eventsNamed("motion")*.value == ["inactive"]
    assert d.state.motion == "inactive"
    assert d.state.pirActive == null
    def e = fresh()
    e.settings.ip = null
    e.call("initialize")
    assert e.device.current.motion == "inactive"
}

finish()
