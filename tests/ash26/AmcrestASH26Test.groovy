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

// Hubitat's eventStream status messages, exactly as Task 1's spike logged them.
final String STATUS_START = "START: EventStream Started"
final String STATUS_STOP = "STOP: EventStream Stopped"
final String STATUS_ERROR = "ERROR: Exception during EventStream Request: java.net.ConnectException: Failed to connect to /10.0.0.30:1"
final String STATUS_AUTH = "ERROR: Exception during EventStream Request: java.net.ProtocolException: Too many follow-up requests: 21"

// Bring the stream up: run connectStream, answer the probe's 401, report START, then
// deliver one heartbeat. START comes even for a connect that fails, so only data
// marks the stream up.
def streamUp = { d ->
    d.call("connectStream")
    d.reply(d.http.find { it.callback == "streamProbeHandler" }, status: 401, headers: CHALLENGE)
    d.call("eventStreamStatus", STATUS_START)
    feed(d, heartbeat())
}

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

// ---- 0.9.0: the event-stream connection ----

test("initialize schedules the stream connect after the poll; missing credentials open no stream") {
    def d = fresh()
    d.call("initialize")
    assert d.schedules.refresh == "runEvery1Minute"
    assert d.schedules.connectStream == 2
    assert d.eventStream.connects.isEmpty()
    def e = fresh()
    e.settings.password = null
    e.call("initialize")
    assert !e.schedules.containsKey("connectStream")
}

test("connectStream signs the encoded attach URI and connects with rawData") {
    def d = fresh()
    d.call("connectStream")
    assert d.http.size() == 1
    def probe = d.http[0]
    assert probe.callback == "streamProbeHandler"
    assert probe.params.uri == "http://10.0.0.30/cgi-bin/eventManager.cgi?action=attach&codes=%5BAll%5D&heartbeat=5"
    assert probe.params.headers == null
    d.reply(probe, status: 401, headers: CHALLENGE)
    def c = d.eventStream.connects[0]
    assert c.url == "http://10.0.0.30/cgi-bin/eventManager.cgi?action=attach&codes=%5BAll%5D&heartbeat=5"
    assert c.opts.rawData == true && c.opts.readTimeout == 30
    String auth = c.opts.headers.Authorization
    assert auth.startsWith('Digest username="admin"')
    assert auth.contains('uri="/cgi-bin/eventManager.cgi?action=attach&codes=%5BAll%5D&heartbeat=5"')
}

test("a probe that gets no challenge is a stream failure: one warn, reconnect in 5 s, health untouched") {
    def d = fresh()
    d.call("connectStream")
    d.reply(d.http[0], status: 408, error: "connect timed out")
    assert d.log.at("warn") == ["Backyard Floodlight motion events stopped (connect timed out, HTTP 408); reconnecting"]
    assert d.schedules.connectStream == 5
    assert d.eventStream.connects.isEmpty()
    assert d.device.current.healthStatus == null
}

test("a connect() that throws is a stream failure") {
    def d = fresh()
    d.eventStream.failWith = "unsupported option"
    d.call("connectStream")
    d.reply(d.http[0], status: 401, headers: CHALLENGE)
    assert d.log.at("warn") == ["Backyard Floodlight motion events stopped (unsupported option); reconnecting"]
    assert d.schedules.connectStream == 5
}

test("repeated failures back off 5, 15, 60, 300, 300 with one warn; the first data after a reconnect logs recovery and resets it") {
    def d = fresh()
    streamUp(d)
    List delays = []
    5.times {
        d.call("eventStreamStatus", STATUS_ERROR)
        delays << d.schedules.connectStream
        d.call("connectStream")              // the scheduled retry
        d.http.clear()                       // its probe isn't answered here
    }
    assert delays == [5, 15, 60, 300, 300]
    assert d.log.at("warn").size() == 1
    d.advance(120_000L)
    streamUp(d)
    assert d.log.at("info").contains("Backyard Floodlight motion events are back after 2m")
    d.call("eventStreamStatus", STATUS_ERROR)
    assert d.schedules.connectStream == 5
}

test("a long stream outage gets an hourly reminder") {
    def d = fresh()
    streamUp(d)
    d.call("eventStreamStatus", STATUS_ERROR)
    d.advance(1_800_000L)
    d.call("connectStream"); d.http.clear()
    d.call("eventStreamStatus", STATUS_ERROR)
    assert d.log.at("warn").size() == 1
    d.advance(1_900_000L)
    d.call("connectStream"); d.http.clear()
    d.call("eventStreamStatus", STATUS_ERROR)
    assert d.log.at("warn").size() == 2
    assert d.log.at("warn")[1].startsWith("Backyard Floodlight motion events still down after 1h 1m")
}

test("one drop that reports ERROR then STOP is handled once") {
    def d = fresh()
    streamUp(d)
    d.call("eventStreamStatus", STATUS_ERROR)
    d.call("eventStreamStatus", STATUS_STOP)
    assert d.runIns.findAll { it[1] == "connectStream" } == [[5, "connectStream"]]
    assert d.log.at("warn").size() == 1
    assert d.state.streamBackoff == 1
}

test("a reconnect forgets sensors whose Stop was lost, and starts the hold") {
    def d = fresh()
    streamUp(d)
    feed(d, pir("Start", 1))
    d.call("eventStreamStatus", STATUS_ERROR)
    streamUp(d)
    assert d.state.pirActive == null
    assert d.schedules.motionHoldExpired == 30
    d.call("motionHoldExpired")
    assert d.device.current.motion == "inactive"
}

test("closing an up stream on purpose: its STOP logs nothing and schedules nothing") {
    def d = fresh()
    streamUp(d)
    d.call("closeStream")
    assert d.eventStream.closes == 1
    d.call("eventStreamStatus", STATUS_STOP)
    assert d.log.at("warn").isEmpty()
    assert !d.schedules.containsKey("connectStream")
    assert d.state.streamClosing == null
}

test("closeStream with no stream open sets no flag, so a later real ERROR still reconnects") {
    def d = fresh()
    d.call("closeStream")
    assert d.eventStream.closes == 1
    assert d.state.streamClosing == null
    streamUp(d)
    d.call("eventStreamStatus", STATUS_ERROR)
    assert d.schedules.connectStream == 5
}

test("Save while the stream is up: a STOP before the scheduled connect is ignored") {
    def d = fresh()
    streamUp(d)
    d.call("initialize")
    assert d.eventStream.closes == 1
    d.call("eventStreamStatus", STATUS_STOP)
    assert d.log.at("warn").isEmpty()
    assert d.schedules.connectStream == 2
    d.call("connectStream")
    assert d.http.any { it.callback == "streamProbeHandler" }
}

test("a STOP that arrives after the scheduled connect counts as one drop (accepted)") {
    def d = fresh()
    streamUp(d)
    d.call("initialize")
    d.call("connectStream")              // ran before the old stream's STOP arrived
    d.call("eventStreamStatus", STATUS_STOP)
    assert d.log.at("warn").size() == 1
    assert d.runIns.findAll { it[1] == "connectStream" }*.get(0) == [2, 5]
    assert d.state.streamBackoff == 1
}

test("hub boot with a stale open stream and no STOP still connects") {
    def d = fresh()
    d.state.streamOpen = true            // left over from before the reboot
    d.state.streamUp = true
    d.call("initialize")
    assert d.state.streamClosing == true
    d.call("connectStream")
    assert d.state.streamClosing == null && d.state.streamOpen == null
    d.reply(d.http.find { it.callback == "streamProbeHandler" }, status: 401, headers: CHALLENGE)
    assert d.eventStream.connects.size() == 1
    d.call("eventStreamStatus", STATUS_START)
    assert d.state.streamOpen == true && d.state.streamUp == null
    feed(d, heartbeat())
    assert d.state.streamUp == true
}

test("Save during a stream outage warns again, and the recovery line covers the whole outage") {
    def d = fresh()
    streamUp(d)
    d.call("eventStreamStatus", STATUS_ERROR)
    d.advance(600_000L)
    d.call("initialize")
    d.call("connectStream")
    d.reply(d.http.find { it.callback == "streamProbeHandler" }, status: 408, error: "connect timed out")
    assert d.log.at("warn").size() == 2
    d.advance(60_000L)
    streamUp(d)
    assert d.log.at("info").contains("Backyard Floodlight motion events are back after 11m")
}

test("watchdog: a minute with no data from an up stream closes it and reconnects") {
    def d = fresh()
    streamUp(d)
    feed(d, heartbeat())
    d.advance(50_000L)
    d.call("refresh")
    assert d.eventStream.closes == 0
    d.advance(11_000L)
    d.call("refresh")
    assert d.eventStream.closes == 1
    assert d.log.at("warn") == ["Backyard Floodlight motion events stopped (no data from the camera for 61 s); reconnecting"]
    assert d.schedules.connectStream == 5
    d.call("eventStreamStatus", STATUS_STOP)       // the close's own STOP
    assert d.log.at("warn").size() == 1
    assert d.runIns.count { it[1] == "connectStream" } == 1
}

test("watchdog: a missing activity stamp (after a code save) is set, not treated as silence") {
    def d = fresh()
    d.state.streamOpen = true            // open, but this fresh load has no stamp yet
    d.state.streamUp = true
    d.call("refresh")
    assert d.eventStream.closes == 0
    d.advance(61_000L)
    d.call("refresh")
    assert d.eventStream.closes == 1
}

test("watchdog: a stream that starts but never sends data is closed after a minute") {
    def d = fresh()
    d.call("connectStream")
    d.reply(d.http[0], status: 401, headers: CHALLENGE)
    d.call("eventStreamStatus", STATUS_START)
    d.advance(61_000L)
    d.call("refresh")
    assert d.eventStream.closes == 1
    assert d.log.at("warn") == ["Backyard Floodlight motion events stopped (no data from the camera for 61 s); reconnecting"]
}

test("START alone doesn't mark the stream up; the first line after it does") {
    def d = fresh()
    d.call("connectStream")
    d.reply(d.http[0], status: 401, headers: CHALLENGE)
    d.call("eventStreamStatus", STATUS_START)
    assert d.state.streamOpen == true
    assert d.state.streamUp == null
    feed(d, ["\r"])
    assert d.state.streamUp == true
}

test("the observed refused connect (START, ERROR, STOP) is one failure each time, with no recovery line") {
    def d = fresh()
    List delays = []
    2.times {
        d.call("connectStream")
        d.reply(d.http.find { it.callback == "streamProbeHandler" }, status: 401, headers: CHALLENGE)
        d.http.clear()
        d.call("eventStreamStatus", STATUS_START)
        d.call("eventStreamStatus", STATUS_ERROR)
        d.call("eventStreamStatus", STATUS_STOP)
        delays << d.schedules.connectStream
    }
    assert delays == [5, 15]
    assert d.log.at("warn").size() == 1
    assert !d.log.at("info").any { it.contains("motion events are back") }
    assert d.state.streamOpen == null && d.state.streamUp == null
}

test("a stream login the camera rejects logs once and waits the longest backoff") {
    def d = fresh()
    d.call("connectStream")
    d.reply(d.http[0], status: 401, headers: CHALLENGE)
    d.call("eventStreamStatus", STATUS_START)
    d.call("eventStreamStatus", STATUS_AUTH)
    d.call("eventStreamStatus", STATUS_STOP)
    assert d.log.at("warn") == ["Backyard Floodlight motion events stopped (the camera rejected the login); reconnecting"]
    assert d.schedules.connectStream == 300
}

test("while the poll's login is rejected, the stream probes but doesn't connect") {
    def d = fresh()
    d.state.commFailKey = "auth|10.0.0.30"
    d.call("connectStream")
    d.reply(d.http[0], status: 401, headers: CHALLENGE)
    assert d.eventStream.connects.isEmpty()
    assert d.log.at("warn") == ["Backyard Floodlight motion events stopped (the camera is rejecting the login); reconnecting"]
    assert d.schedules.connectStream == 5
}

// ---- 0.9.0: settings commands ----

def uriOf = { Map req -> req.params.uri as String }
final String SET = "http://10.0.0.30/cgi-bin/configManager.cgi?action=setConfig"

test("the settings commands and attributes are declared") {
    def d = fresh()
    assert d.commandDefs.setMotionLightDuration == [[name: "seconds*", type: "NUMBER", description: "How long the light stays on after motion (1–3600)"]]
    assert d.commandDefs.setNightVision == [[name: "mode*", type: "ENUM", constraints: ["Auto", "On", "Off"]]]
    assert d.commandDefs.setMotionDetection == [[name: "setting*", type: "ENUM", constraints: ["on", "off"]]]
    def attrs = d.attributes.collectEntries { [(it.name): [it.type, it.values]] }
    assert attrs.motionLightDuration == ["number", null]
    assert attrs.nightVision == ["enum", ["Auto", "On", "Off"]]
    assert attrs.motionDetection == ["enum", ["on", "off"]]
}

test("setMotionLightDuration writes all three PIR durations in one request, then shows the value") {
    def d = fresh()
    d.call("setMotionLightDuration", 30)
    assert d.http.size() == 1
    assert uriOf(d.http[0]) == SET +
            "&Alarm[0].EventHandler.LightingLink.LightDuration=30" +
            "&Alarm[1].EventHandler.LightingLink.LightDuration=30" +
            "&Alarm[2].EventHandler.LightingLink.LightDuration=30"
    answer(d, "OK")
    assert d.http.isEmpty()
    assert d.device.current.motionLightDuration == 30
    assert d.log.at("info").contains("Backyard Floodlight motion light duration is 30 s")
}

test("setMotionLightDuration rejects anything but a whole number from 1 to 3600") {
    def d = fresh()
    [0, 3601, 2.5, "abc"].each { d.call("setMotionLightDuration", it) }
    assert d.http.isEmpty()
    assert d.log.at("warn").size() == 4
    d.call("setMotionLightDuration", "3600")
    assert d.http.size() == 1
}

test("setNightVision writes the four keys the Amcrest app writes; On is stored as ForceOn") {
    def d = fresh()
    d.call("setNightVision", "On")
    assert uriOf(d.http[0]) == SET +
            "&Lighting_V2[0][0][0].Mode=ForceOn&Lighting_V2[0][1][0].Mode=ForceOn" +
            "&Lighting[0][1].Mode=ForceOn&Lighting[0][2].Mode=ForceOn"
    answer(d, "OK")
    assert d.device.current.nightVision == "On"
    d.call("setNightVision", "auto")
    assert uriOf(d.http[0]).endsWith("&Lighting[0][2].Mode=Auto")
    answer(d, "OK")
    assert d.device.current.nightVision == "Auto"
    d.call("setNightVision", "dim")
    assert d.http.isEmpty() && d.log.at("warn").size() == 1
}

test("setMotionDetection switches video motion detection and its snapshot together") {
    def d = fresh()
    d.call("setMotionDetection", "on")
    assert uriOf(d.http[0]) == SET + "&MotionDetect[0].Enable=true&MotionDetect[0].EventHandler.SnapshotEnable=true"
    answer(d, "OK")
    assert d.device.current.motionDetection == "on"
    d.call("setMotionDetection", "off")
    assert uriOf(d.http[0]).endsWith("SnapshotEnable=false")
    answer(d, "OK")
    assert d.device.current.motionDetection == "off"
    d.call("setMotionDetection", "maybe")
    assert d.http.isEmpty() && d.log.at("warn").size() == 1
}

test("a rejected setting logs an error and leaves the attribute alone") {
    def d = fresh()
    d.device.current.motionLightDuration = 60
    d.call("setMotionLightDuration", 30)
    answer(d, "Error")
    assert d.log.at("error") == ["set Alarm[0].EventHandler.LightingLink.LightDuration failed: Error"]
    assert d.device.current.motionLightDuration == 60
}

test("the poll shows motion light duration, night vision and motion detection") {
    def d = fresh()
    d.call("refresh")
    answer(d, settingsText("Off") + """table.All.Alarm[0].EventHandler.LightingLink.LightDuration=60
table.All.Lighting_V2[0][0][0].Mode=ForceOn
table.All.MotionDetect[0].Enable=false
""")
    assert d.device.current.motionLightDuration == 60
    assert d.device.current.nightVision == "On"
    assert d.device.current.motionDetection == "off"
}

// ---- 0.9.0: Reboot ----

test("Reboot sends magicBox reboot; on OK an info line, the window opens and the stream reconnects in 60 s") {
    def d = fresh()
    assert d.commands.contains("reboot")
    streamUp(d)
    d.call("reboot")
    assert uriOf(d.http[0]) == "http://10.0.0.30/cgi-bin/magicBox.cgi?action=reboot"
    answer(d, "OK")
    assert d.log.at("info").contains("Backyard Floodlight rebooting; back in about 1–2 minutes")
    assert d.state.rebootStartedAt == d.clock
    assert d.eventStream.closes == 1
    assert d.schedules.connectStream == 60
}

test("no reply to the signed Reboot request means it's probably restarting: info, window, no error") {
    def d = fresh()
    d.call("reboot")
    d.reply(d.http[0], status: 401, headers: CHALLENGE)
    d.reply(d.http[0], status: 408, error: "Read timed out")
    assert d.log.at("info").contains("Backyard Floodlight didn't answer the Reboot request; it's probably restarting")
    assert d.log.at("error").isEmpty() && d.log.at("warn").isEmpty()
    assert d.state.rebootStartedAt == d.clock
    assert d.device.current.healthStatus == "offline"
    assert d.schedules.connectStream == 60
}

test("a camera that doesn't answer the Reboot probe never got it: warn, normal outage error, no window") {
    def d = fresh()
    d.call("reboot")
    d.reply(d.http[0], status: 408, error: "connect timed out")
    assert d.log.at("warn") == ["camera didn't answer; Reboot not sent (connect timed out, HTTP 408)"]
    assert d.log.at("error").size() == 1 && d.log.at("error")[0].contains("isn't answering")
    assert d.state.rebootStartedAt == null
}

test("a refused Reboot warns with the camera's reply and opens no window") {
    def d = fresh()
    streamUp(d)
    d.call("reboot")
    answer(d, "Error")
    assert d.log.at("warn") == ["camera refused Reboot (Error)"]
    assert d.state.rebootStartedAt == null
    assert d.eventStream.closes == 0
}

test("inside the reboot window, poll and stream failures log nothing above debug; the return logs one line") {
    def d = fresh()
    streamUp(d)
    d.call("reboot")
    answer(d, "OK")
    d.call("eventStreamStatus", STATUS_STOP)        // the deliberate close
    d.advance(20_000L)
    d.call("refresh")
    d.reply(d.http.find { it.callback == "unauthRequestHandler" }, status: 408, error: "connect timed out")
    d.advance(40_000L)
    d.call("connectStream")
    d.reply(d.http.find { it.callback == "streamProbeHandler" }, status: 408, error: "connect timed out")
    assert d.log.at("error").isEmpty() && d.log.at("warn").isEmpty()
    assert d.device.current.healthStatus == "offline"
    d.advance(30_000L)
    d.call("refresh")
    answer(d, settingsText("Off"))
    assert d.log.at("info").contains("Backyard Floodlight is back after 1m (reboot)")
    assert !d.log.at("info").any { it.contains("responding again") }
    assert d.state.rebootStartedAt == null
    streamUp(d)
    assert !d.log.at("info").any { it.contains("motion events are back") }
}

test("a camera still down 5 minutes after Reboot gets the normal outage error") {
    def d = fresh()
    d.call("reboot")
    answer(d, "OK")
    d.advance(301_000L)
    d.call("refresh")
    d.reply(d.http[0], status: 408, error: "connect timed out")
    assert d.log.at("error").size() == 1 && d.log.at("error")[0].contains("isn't answering")
    assert d.state.rebootStartedAt == null
}

finish()
