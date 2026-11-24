import harness.HubitatHarness
import harness.FakeAppDevice     // after HubitatHarness, which defines it

import static harness.TestRunner.finish
import static harness.TestRunner.test

final String APP = "BondHome/app/BOND_Home_Integration.groovy"
final String DEV = "BondHome/device/"
final String HUB = "ZZDI30907"
final String FAN = "7b90b724"
final String FAN2 = "a1b2c3d4"
final String FP = "f00dcafe"
final String TOKEN = "tok-SECRET-9f3e"
final String FAN_DNI = "${HUB}:bond:${FAN}".toString()
final String LIGHT_DNI = "${HUB}:bond:${FAN}:light".toString()
// The cabin fan's actions, as BOND reported them on 2026-11-24.
final List CABIN_ACTIONS = ["DecreaseSpeed", "IncreaseSpeed", "SetSpeed", "StartDimmer", "Stop", "ToggleLight",
                            "TogglePower", "TurnLightOff", "TurnLightOn", "TurnOff", "TurnOn"]
final Map DRIVERS = [
    "BOND_Fan.groovy": "BOND Fan", "BOND_Fan_With_Direction.groovy": "BOND Fan With Direction",
    "BOND_Fan_Light.groovy": "BOND Fan Light", "BOND_Fan_Dimmable_Light.groovy": "BOND Fan Dimmable Light",
    "BOND_Fan_Timer_Light.groovy": "BOND Fan Timer Light", "BOND_Fireplace.groovy": "BOND Fireplace",
    "BOND_Fireplace_Fan.groovy": "BOND Fireplace Fan", "BOND_Fireplace_Light.groovy": "BOND Fireplace Light",
    "BOND_Generic_Device.groovy": "BOND Generic Device", "BOND_Motorized_Shade.groovy": "BOND Motorized Shade"]

// Every harness a test loads; after each test, no log line may contain the token.
final List loaded = []
def check = { String name, Closure body ->
    test(name) {
        loaded.clear()
        body.call()
        loaded.each { h -> h.log.lines.each { assert !it[1].contains(TOKEN) : "token in a log line: ${it[1]}" } }
    }
}

// ---- App helpers ----

// The app as installed at the cabin: one fan, stored details from a good fetch, the hub ID known.
def app = { Map o = [:] ->
    def a = HubitatHarness.load(APP)
    a.binding.inputNames.addAll(["hubIp", "hubToken", "refreshInterval", "debugOutput",
                                 "fireplaces", "fans", "shades", "genericDevices"])
    a.settings.putAll([hubIp: "192.168.21.26", hubToken: TOKEN, refreshInterval: 30, debugOutput: false, fans: [FAN]] + o)
    a.childFactory = { Map p -> new FakeAppDevice(p) }
    a.syncHttpThrows = true     // Hubitat's httpGet/Put/Patch throw HttpResponseException for an error status
    a.state.hubId = HUB
    a.state.fanList = [(FAN): "Ceiling fan"]
    a.state.fanDetails = [(FAN): CABIN_ACTIONS]
    a.state.fanProperties = [(FAN): [max_speed: 3, trust_state: false]]
    loaded << a
    a
}
// Device 878 "Loft Fan (BOND)" and its disabled light 1189, as at the cabin.
def cabinDevices = { a ->
    def fan = new FakeAppDevice(id: 878, deviceNetworkId: FAN_DNI, name: "Loft Fan (BOND)", typeName: "BOND Fan",
                                capabilities: ["Switch", "FanControl"])
    def light = new FakeAppDevice(id: 1189, deviceNetworkId: LIGHT_DNI, name: "Ceiling fan Light",
                                  typeName: "BOND Fan Timer Light", disabled: true)
    fan.children[LIGHT_DNI] = light
    a.children[FAN_DNI] = fan
    [fan, light]
}
def ok = { a, Map data = [:] -> a.syncHttp << [status: 200, data: data] }
def httpError = { a, int status, Map data = [_error_msg: "nope"] -> a.syncHttp << [status: status, data: data] }
def down = { a, int n = 1 -> n.times { a.syncHttp << [exception: new java.net.SocketTimeoutException("Read timed out")] } }
def fanState = { Map o = [:] -> [power: 1, speed: 2, light: 0, timer: 0, _: "7fc1e84b"] + o }
def paths = { a -> a.http.collect { it.params.path.toString() } }

// ---- Driver helpers ----

def driver = { String file ->
    def d = HubitatHarness.load(DEV + file)
    d.device.displayName = "Loft Fan (BOND)"
    d.device.deviceNetworkId = FAN_DNI
    d.withParent()
    loaded << d
    d
}

// ---- Frozen names ----

check("the app keeps its name, namespace and author") {
    def a = app()
    assert a.definition.name == "BOND Home Integration"
    assert a.definition.namespace == "dcm.bond"
    assert a.definition.author == "Dominick Meglio"
}

check("every driver keeps its name and namespace") {
    DRIVERS.each { file, name ->
        def d = driver(file)
        assert d.definition.name == name
        assert d.definition.namespace == "bond"
    }
}

// ---- Requests ----

check("a timeout on a command warns that the bridge isn't answering, without a crash") {
    def a = app(); cabinDevices(a)
    down(a)
    assert a.call("executeAction", FAN, "TurnOn") == false
    assert a.log.at("warn") == ["BOND didn't accept TurnOn for Loft Fan (BOND) (${FAN}): bridge not answering (java.net.SocketTimeoutException: Read timed out)".toString()]
}

check("an HTTP error on a command logs its status and body") {
    def a = app(); cabinDevices(a)
    httpError(a, 404, [_error_msg: "not found"])
    assert a.call("executeAction", FAN, "TurnOn") == false
    assert a.log.at("warn")[0].contains("HTTP 404") && a.log.at("warn")[0].contains("not found")
}

check("commands use a 10 s timeout and send the token only in the header") {
    def a = app(); cabinDevices(a)
    ok(a)
    assert a.call("executeAction", FAN, "SetSpeed", 2) == true
    def p = a.http[0].params
    assert a.http[0].method == "SYNC_PUT" && p.path == "/v2/devices/${FAN}/actions/SetSpeed".toString()
    assert p.body == '{"argument": 2}' && p.timeout == 10 && p.headers["BOND-Token"] == TOKEN
}

check("fix state is a PATCH of the state") {
    def a = app(); cabinDevices(a)
    ok(a)
    assert a.call("executeFixState", FAN, '{"power": 0}') == true
    assert a.http[0].method == "SYNC_PATCH" && a.http[0].params.path == "/v2/devices/${FAN}/state".toString()
}

check("a device fetch that fails partway leaves the stored lists as they were") {
    def a = app()
    ok(a, [(FAN): [_: "x"], _: "y"])     // the device list answers
    down(a)                              // the fan's details don't
    assert a.call("getDevices") == false
    assert a.state.fanDetails == [(FAN): CABIN_ACTIONS] && a.state.fanList == [(FAN): "Ceiling fan"]
    assert a.state.fanProperties[FAN].max_speed == 3
}

check("a failed properties read also leaves them as they were") {
    def a = app()
    ok(a, [(FAN): [_: "x"], _: "y"])
    ok(a, [name: "Ceiling fan", type: "CF", actions: ["TurnOn"]])
    httpError(a, 500)
    assert a.call("getDevices") == false
    assert a.state.fanDetails == [(FAN): CABIN_ACTIONS]
}

check("a complete fetch replaces the lists") {
    def a = app()
    a.state.fanList = [old: "Gone fan"]
    ok(a, [(FAN): [_: "x"], _: "y"])
    ok(a, [name: "Ceiling fan", type: "CF", actions: CABIN_ACTIONS])
    ok(a, [max_speed: 3, trust_state: false])
    assert a.call("getDevices") == true
    assert a.state.fanList == [(FAN): "Ceiling fan"] && a.state.fanDetails == [(FAN): CABIN_ACTIONS]
    assert a.state.fanProperties == [(FAN): [max_speed: 3, trust_state: false]]
    assert a.state.fireplaceList == [:] && a.state.shadeList == [:] && a.state.genericList == [:]
}

check("the hub ID is read once and kept") {
    def a = app(); a.state.hubId = null
    ok(a, [bondid: HUB, fw_ver: "v3"])
    assert a.call("getHubId") == HUB && a.call("getHubId") == HUB
    assert a.http.size() == 1 && a.state.hubId == HUB
}

check("hasAction names itself when it fails") {
    def a = app()
    down(a)
    assert a.call("hasAction", FAN, "TurnOn") == false
    assert a.log.at("warn")[0].startsWith("BOND hasAction:")
}

// ---- Logging ----

check("debug logging defaults to off and says it turns itself off") {
    def a = app()
    a.call("prefHub")
    def input = a.inputs.find { it.name == "debugOutput" }
    assert input.defaultValue == false && input.description == "Turns itself off after 30 minutes"
}

check("logsOff turns debug logging off") {
    def a = app(debugOutput: true)
    a.call("logsOff")
    assert a.app.settingUpdates == [[name: "debugOutput", value: "false", type: "bool"]]
    assert a.log.at("info") == ["BOND: debug logging turned off"]
}

// ---- Done ----

check("Done with the cabin's devices adds and deletes nothing, and arms the poll") {
    def a = app()
    def (fan, light) = cabinDevices(a)
    a.call("updated")
    assert a.children.keySet() == [FAN_DNI] as Set
    assert fan.children.keySet() == [LIGHT_DNI] as Set && fan.children[LIGHT_DNI].is(light)
    assert light.disabled && fan.deletedChildren.isEmpty()
    assert a.schedules.updateDevices == "cron:0/30 * * * * ? *"
    assert a.log.at("warn").isEmpty() && a.log.at("error").isEmpty()
}

check("Done with debug on arms logsOff for 30 minutes, after unschedule") {
    def a = app(debugOutput: true); cabinDevices(a)
    a.call("updated")
    assert a.schedules.logsOff == 1800
}

check("a failure in child setup leaves the poll armed") {
    def a = app()
    def fan = new FakeAppDevice(id: 878, deviceNetworkId: FAN_DNI, name: "Loft Fan (BOND)")
    fan.metaClass.getChildDevice = { String dni -> throw new RuntimeException("boom") }
    a.children[FAN_DNI] = fan
    a.call("updated")
    assert a.schedules.updateDevices == "cron:0/30 * * * * ? *"
    assert a.log.at("warn").any { it.contains("boom") }
}

check("Done with no stored details for the fan adds and deletes nothing, and warns once") {
    def a = app()
    def (fan, light) = cabinDevices(a)
    a.state.fanDetails = [:]
    a.call("updated")
    assert fan.children.keySet() == [LIGHT_DNI] as Set && fan.deletedChildren.isEmpty()
    assert a.children.keySet() == [FAN_DNI] as Set
    assert a.log.at("warn").size() == 1 && a.log.at("warn")[0].contains(FAN)
    assert a.schedules.updateDevices
}

check("Done with the hub ID unknown creates nothing") {
    def a = app()
    a.state.hubId = null          // and the bridge doesn't answer the ID request
    a.call("updated")
    assert a.children.isEmpty()
    assert a.log.at("warn").any { it.contains("bridge's ID is unknown") }
}

check("a light BOND no longer lists is deleted, with an info line") {
    def a = app()
    def (fan, light) = cabinDevices(a)
    a.state.fanDetails = [(FAN): CABIN_ACTIONS - ["TurnLightOn", "TurnLightOff", "ToggleLight", "StartDimmer"]]
    a.call("updated")
    assert fan.deletedChildren == [LIGHT_DNI]
    assert a.log.at("info").any { it.contains(LIGHT_DNI) }
}

check("a new install adds the fan and its timer light") {
    def a = app()
    a.call("updated")
    def fan = a.children[FAN_DNI]
    assert fan.typeName == "BOND Fan" && fan.name == "Ceiling fan"
    assert fan.children[LIGHT_DNI].typeName == "BOND Fan Timer Light"
}

check("installing and saving never log the settings, even with debug on") {
    def a = app(debugOutput: true); cabinDevices(a)
    a.call("updated")
    assert !a.log.lines.any { it[1].contains("settings") }
}

check("poll intervals become valid schedules") {
    def a = app()
    [(null): "0/30 * * * * ? *", 0: "0/30 * * * * ? *", 3: "0/30 * * * * ? *", 30: "0/30 * * * * ? *",
     45: "0/45 * * * * ? *", 60: "0 0/1 * * * ? *", 90: "0 0/2 * * * ? *", 120: "0 0/2 * * * ? *",
     1800: "0 0/30 * * * ? *", 3600: "0 0 * * * ? *", 7200: "0 0 * * * ? *"].each { n, cron ->
        assert a.call("pollCron", n) == cron
    }
}

// ---- Polling ----

check("a poll updates the fan, marks it online, and leaves the disabled light alone") {
    def a = app()
    def (fan, light) = cabinDevices(a)
    ok(a, fanState(power: 1, speed: 2))
    a.call("updateDevices")
    assert fan.current.switch == "on" && fan.current.speed == "medium" && fan.current.healthStatus == "online"
    assert paths(a) == ["/v2/devices/${FAN}/state".toString()] && a.http[0].params.timeout == 5
    assert light.events.isEmpty()
    assert a.state.healthDevices == [sent: [FAN_DNI], skippedDisabled: [LIGHT_DNI]]
}

check("a healthy poll writes nothing to state once it's online") {
    def a = app()
    cabinDevices(a)
    ok(a, fanState()); a.call("updateDevices")
    def before = new LinkedHashMap(a.state)
    ok(a, fanState()); a.call("updateDevices")
    assert a.state == before
}

check("power off reads switch off and speed off") {
    def a = app()
    def (fan, light) = cabinDevices(a)
    ok(a, fanState(power: 0, speed: 3))
    a.call("updateDevices")
    assert fan.current.switch == "off" && fan.current.speed == "off"
}

check("a selected fan with no Hubitat device is skipped without a request") {
    def a = app()
    a.call("updateDevices")
    assert a.http.isEmpty() && a.log.at("warn").isEmpty() && a.log.at("error").isEmpty()
}

check("one device that throws doesn't stop the next") {
    def a = app(fans: [FAN, FAN2])
    a.state.fanProperties[FAN2] = [max_speed: 3]
    def bad = new FakeAppDevice(id: 1, deviceNetworkId: FAN_DNI, name: "Bad fan")
    bad.metaClass.sendEvent = { Map m -> throw new RuntimeException("kaput") }
    def good = new FakeAppDevice(id: 2, deviceNetworkId: "${HUB}:bond:${FAN2}".toString(), name: "Good fan")
    a.children[bad.deviceNetworkId] = bad
    a.children[good.deviceNetworkId] = good
    ok(a, fanState()); ok(a, fanState(speed: 3))
    a.call("updateDevices")
    assert good.current.speed == "high"
    assert a.log.at("warn").any { it.contains("Bad fan") && it.contains("kaput") }
}

check("the first 'not answering' skips the rest of the poll") {
    def a = app(fans: [FAN, FAN2])
    cabinDevices(a)
    a.children["${HUB}:bond:${FAN2}".toString()] = new FakeAppDevice(id: 2, deviceNetworkId: "${HUB}:bond:${FAN2}".toString(), name: "Fan 2")
    down(a)
    a.call("updateDevices")
    assert a.http.size() == 1
}

check("a poll with no stored properties assumes 3 speeds") {
    def a = app()
    def (fan, light) = cabinDevices(a)
    a.state.fanProperties = [:]
    ok(a, fanState(speed: 3))
    a.call("updateDevices")
    assert fan.current.speed == "high" && a.log.at("warn").isEmpty()
}

// ---- Outages ----

// Runs `n` failed polls 30 s apart.
def failPolls = { a, int n -> n.times { down(a); a.call("updateDevices"); a.advance(30_000) } }

check("failures under 2 minutes log only at debug") {
    def a = app()
    def (fan, light) = cabinDevices(a)
    ok(a, fanState()); a.call("updateDevices")      // online first
    failPolls(a, 4)                                  // 0, 30, 60, 90 s
    assert a.log.at("warn").isEmpty() && fan.current.healthStatus == "online"
}

check("at 2 minutes, one warning and healthStatus offline on every enabled device") {
    def a = app()
    def (fan, light) = cabinDevices(a)
    ok(a, fanState()); a.call("updateDevices")
    failPolls(a, 5)                                  // the 5th failure is at 120 s
    assert a.log.at("warn") == ["BOND bridge at 192.168.21.26 isn't answering (java.net.SocketTimeoutException: Read timed out); still trying"]
    assert fan.current.healthStatus == "offline" && light.events.isEmpty()
    failPolls(a, 100)                                // 50 more minutes: no more warnings
    assert a.log.at("warn").size() == 1
}

check("an hourly reminder while it lasts, then a recovery line with the outage length") {
    def a = app()
    def (fan, light) = cabinDevices(a)
    ok(a, fanState()); a.call("updateDevices")
    failPolls(a, 5)                                  // declared at 120 s
    a.advance(3600_000)
    down(a); a.call("updateDevices")
    assert a.log.at("warn")[1] == "BOND bridge at 192.168.21.26 still isn't answering after 1 h 3 min"
    a.advance(600_000)
    ok(a, fanState()); a.call("updateDevices")
    assert a.log.at("info") == ["BOND bridge at 192.168.21.26 is answering again after 1 h 13 min"]
    assert fan.current.healthStatus == "online" && a.state.outageStart == null
}

check("a blip that recovers logs nothing above debug") {
    def a = app()
    def (fan, light) = cabinDevices(a)
    failPolls(a, 2)
    ok(a, fanState()); a.call("updateDevices")
    assert a.log.at("warn").isEmpty() && a.log.at("info").isEmpty()
}

check("an HTTP error for one device warns once, isn't an outage, and logs its recovery") {
    def a = app()
    def (fan, light) = cabinDevices(a)
    3.times { httpError(a, 404); a.call("updateDevices"); a.advance(60_000) }
    assert a.log.at("warn").size() == 1 && a.log.at("warn")[0].contains("HTTP 404")
    assert fan.current.healthStatus == "online"
    ok(a, fanState()); a.call("updateDevices")
    assert a.log.at("info") == ["BOND: Loft Fan (BOND) (${FAN}) is answering again".toString()]
}

// ---- Light state ----

check("a light's on/off comes from BOND's light flag, and its level is kept while it's off") {
    def a = app()
    def (fan, light) = cabinDevices(a)
    light.disabled = false
    ok(a, fanState(light: 0, brightness: 60)); a.call("updateDevices")
    assert light.current.switch == "off" && light.current.level == 60
    ok(a, fanState(light: 1, brightness: 60)); a.call("updateDevices")
    assert light.current.switch == "on" && light.current.level == 60
}

check("up and down lights need both the main light flag and their own") {
    def a = app()
    def (fan, light) = cabinDevices(a)
    fan.children.clear()
    def up = new FakeAppDevice(id: 3, deviceNetworkId: "${HUB}:bond:${FAN}:uplight".toString(), name: "Up")
    def dn = new FakeAppDevice(id: 4, deviceNetworkId: "${HUB}:bond:${FAN}:downlight".toString(), name: "Down")
    fan.children[up.deviceNetworkId] = up; fan.children[dn.deviceNetworkId] = dn
    ok(a, fanState(light: 1, up_light: 1, down_light: 0, up_light_brightness: 40)); a.call("updateDevices")
    assert up.current.switch == "on" && up.current.level == 40 && dn.current.switch == "off"
}

check("every driver declares healthStatus") {
    DRIVERS.keySet().each { file ->
        assert driver(file).attributes.find { it.name == "healthStatus" }?.values == ["online", "offline"] : file
    }
}

// ---- Speeds ----

check("speed names follow the fan's speed count") {
    def a = app()
    assert a.call("speedNames", 1) == ["high"]
    assert a.call("speedNames", 2) == ["low", "high"]
    assert a.call("speedNames", 3) == ["low", "medium", "high"]
    assert a.call("speedNames", 4) == ["low", "medium-low", "medium", "high"]
    (5..10).each { assert a.call("speedNames", it) == ["low", "medium-low", "medium", "medium-high", "high"] }
}

check("BOND speed numbers map to names, with upstream's typos fixed") {
    def a = app()
    assert a.call("speedName", 10, 2) == "low"         // upstream: 1
    assert a.call("bondSpeed", 9, "medium") == 5       // upstream key ":medium"
    assert a.call("speedName", 1, 1) == "high" && a.call("bondSpeed", 1, "high") == 1
    assert [1, 2, 3].collect { a.call("speedName", 3, it) } == ["low", "medium", "high"]
}

check("power on with speed 0, missing, or above the fan's maximum reads 'on'") {
    def a = app()
    assert a.call("speedName", 3, 0) == "on" && a.call("speedName", 3, null) == "on" && a.call("speedName", 3, 4) == "on"
}

check("requests round to a speed the fan has") {
    def a = app()
    def r = { int max, String s -> a.call("resolveSpeed", max, s) }
    assert ["low", "medium-low", "medium", "medium-high", "high"].collect { r(1, it) } == ["high"] * 5
    assert ["low", "medium-low", "medium", "medium-high", "high"].collect { r(2, it) } == ["low", "low", "high", "high", "high"]
    assert ["low", "medium-low", "medium", "medium-high", "high"].collect { r(3, it) } == ["low", "low", "medium", "high", "high"]
    assert r(4, "medium-high") == "high" && r(4, "medium-low") == "medium-low"
    assert r(5, "medium-high") == "medium-high"
    assert r(3, "7") == "high" && r(3, "0") == "low"
    assert r(3, "auto") == null && r(3, null) == null
}

check("on the cabin fan, medium-low runs at speed 1 and reads low") {
    def a = app()
    def (fan, light) = cabinDevices(a)
    ok(a)
    assert a.call("handleFanSpeed", fan, "medium-low") == "low"
    assert a.http[0].params.body == '{"argument": 1}'
    assert fan.current.speed == "low" && fan.current.switch == "on"
}

check("medium-high runs at speed 3 and reads high") {
    def a = app()
    def (fan, light) = cabinDevices(a)
    ok(a)
    assert a.call("handleFanSpeed", fan, "medium-high") == "high"
    assert a.http[0].params.body == '{"argument": 3}'
}

check("auto warns and sends nothing") {
    def a = app()
    def (fan, light) = cabinDevices(a)
    assert a.call("handleFanSpeed", fan, "auto") == null
    assert a.http.isEmpty() && a.log.at("warn") == ["Loft Fan (BOND): BOND fans have no 'auto' speed, so nothing was sent"]
}

check("a failed SetSpeed changes nothing and returns null") {
    def a = app()
    def (fan, light) = cabinDevices(a)
    down(a)
    assert a.call("handleFanSpeed", fan, "low") == null && fan.current.speed == null
}

check("a poll sets supportedFanSpeeds from the fan's speeds") {
    def a = app()
    def (fan, light) = cabinDevices(a)
    ok(a, fanState()); a.call("updateDevices")
    assert fan.current.supportedFanSpeeds == '["low","medium","high","on","off"]'
}

check("Fix Speed to a speed records power on, so the next poll keeps it") {
    def a = app()
    def (fan, light) = cabinDevices(a)
    ok(a)
    a.call("fixFanSpeed", fan, "medium-low")
    assert a.http[0].method == "SYNC_PATCH" && a.http[0].params.body == '{"power": 1, "speed": 1}'
    assert fan.current.switch == "on" && fan.current.speed == "low"
}

check("Fix Speed off records power off and shows the switch off") {
    def a = app()
    def (fan, light) = cabinDevices(a)
    ok(a)
    a.call("fixFanSpeed", fan, "off")
    assert a.http[0].params.body == '{"power": 0}' && fan.current.switch == "off" && fan.current.speed == "off"
}

check("Fix Speed on records power on only") {
    def a = app()
    def (fan, light) = cabinDevices(a)
    ok(a)
    a.call("fixFanSpeed", fan, "on")
    assert a.http[0].params.body == '{"power": 1}' && fan.current.switch == "on"
}

check("Cycle Speed goes off, low, medium, high, off") {
    def a = app()
    def (fan, light) = cabinDevices(a)
    def seq = []
    [null, "off", "on", "low", "medium", "high", "medium-low"].each { cur ->
        fan.current.speed = cur
        seq << a.call("nextSpeed", fan)
    }
    assert seq == ["low", "low", "low", "medium", "high", "off", "low"]
}

check("a fireplace fan uses the fireplace's speed count") {
    def a = app(fans: null, fireplaces: [FP])
    a.state.fireplaceProperties = [(FP): [max_speed: 2]]
    def fpFan = new FakeAppDevice(id: 9, deviceNetworkId: "${HUB}:bond:${FP}:fan".toString(), name: "FP fan")
    ok(a)
    assert a.call("handleFPFanSpeed", fpFan, "medium") == "high"
    assert a.http[0].params.body == '{"argument": 2}'
}

check("the fan driver stores the speed actually set") {
    def d = driver("BOND_Fan.groovy")
    d.parent.answers.handleFanSpeed = { dev, s -> s == "medium-low" ? "low" : null }
    d.call("setSpeed", "medium-low")
    assert d.state.lastSpeed == "low"
    d.call("setSpeed", "auto")                      // fails: nothing stored
    assert d.state.lastSpeed == "low"
}

check("On sends TurnOn, then the stored speed, rounded") {
    def d = driver("BOND_Fan.groovy")
    d.state.lastSpeed = "medium-low"                // stored by 1.4.0
    d.parent.answers.handleFanSpeed = { dev, s -> s == "medium-low" ? "low" : s }
    d.call("on")
    assert d.parent.calls.collect { it[0] } == ["handleOn", "handleFanSpeed"]
    assert d.state.lastSpeed == "low"
}

check("Cycle Speed asks the app for the next speed and sets it, on all three fan drivers") {
    ["BOND_Fan.groovy": "handleFanSpeed", "BOND_Fan_With_Direction.groovy": "handleFanSpeed",
     "BOND_Fireplace_Fan.groovy": "handleFPFanSpeed"].each { file, handler ->
        def d = driver(file)
        d.parent.answers.nextSpeed = "medium"
        d.parent.answers[handler] = { dev, s -> s }
        d.call("cycleSpeed")
        assert d.parent.callsTo(handler).collect { it[2] } == ["medium"] : file
    }
}

// ---- Timer light ----

check("Dim starts the dimmer and schedules Stop with only the BOND ID") {
    def a = app()
    def (fan, light) = cabinDevices(a)
    ok(a)
    a.call("handleDim", light, 5)
    assert paths(a) == ["/v2/devices/${FAN}/actions/StartDimmer".toString()]
    assert a.runInMillisCalls == [[5000L, "stopDimmer", [data: [bondId: FAN]]]]
    ok(a)
    a.call("stopDimmer", [bondId: FAN])
    assert paths(a)[1] == "/v2/devices/${FAN}/actions/Stop".toString()
}

check("a failed StartDimmer schedules no Stop") {
    def a = app()
    def (fan, light) = cabinDevices(a)
    down(a)
    a.call("handleDim", light, 2.5)
    assert a.runInMillisCalls.isEmpty()
}

// ---- Fireplaces ----

def fireplaceApp = { Map o = [:] ->
    def a = app([fans: null, fireplaces: [FP]] + o)
    a.binding.inputNames.addAll(["fireplaceSensor0", "fireplaceSensorThreshold0", "fireplaceSensor1", "fireplaceSensorThreshold1"])
    def fp = new FakeAppDevice(id: 20, deviceNetworkId: "${HUB}:bond:${FP}".toString(), name: "Fireplace")
    def fpFan = new FakeAppDevice(id: 21, deviceNetworkId: "${HUB}:bond:${FP}:fan".toString(), name: "Fireplace Fan")
    fp.children[fpFan.deviceNetworkId] = fpFan
    a.children[fp.deviceNetworkId] = fp
    [a, fp, fpFan]
}

check("Fix Flame patches the flame height") {
    def (a, fp, fpFan) = fireplaceApp()
    ok(a)
    a.call("fixFlameState", fp, "medium")
    assert a.http[0].params.body == '{"flame": 50}' && fp.current.flame == "medium"
}

check("the power meter uses the threshold set, and finds hub-ID devices") {
    def (a, fp, fpFan) = fireplaceApp(fireplaceSensor0: [id: 5], fireplaceSensorThreshold0: 50)
    a.call("powerMeterEventHandler", [device: [id: 5], value: "30.5"])
    assert fp.current.switch == "off" && fpFan.current.speed == "off"
    a.call("powerMeterEventHandler", [device: [id: 5], value: "60"])
    assert fp.current.switch == "on"
}

check("the power meter defaults to a threshold of 10, and skips empty sensor slots") {
    def (a, fp, fpFan) = fireplaceApp(fireplaces: ["empty0", FP], fireplaceSensor0: null, fireplaceSensor1: [id: 6])
    a.call("powerMeterEventHandler", [device: [id: 6], value: "12"])
    assert fp.current.switch == "on"
}

// ---- Release ----

check("import URLs point at this repo") {
    assert app().definition.importUrl == "https://raw.githubusercontent.com/dlaporte/Hubitat/main/BondHome/app/BOND_Home_Integration.groovy"
    DRIVERS.keySet().each { file ->
        assert driver(file).definition.importUrl == "https://raw.githubusercontent.com/dlaporte/Hubitat/main/BondHome/device/${file}".toString()
    }
}

finish()
