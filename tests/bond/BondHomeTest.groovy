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

finish()
