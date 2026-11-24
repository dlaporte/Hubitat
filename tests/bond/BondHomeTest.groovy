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

finish()
