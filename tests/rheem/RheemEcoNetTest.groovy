import harness.HubitatHarness
import harness.RecordingChild
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import static harness.TestRunner.finish
import static harness.TestRunner.test

final String APP = "RheemEcoNet/app/Rheem_EcoNet_Integration.groovy"
final String DRIVER = "RheemEcoNet/device/Rheem_EcoNet_Water_Heater.groovy"
final String DN = "5555666677778888"
final String SERIAL = "00-11-22-33-44-55-0001"
final String ID = DN + ":" + SERIAL

// ---- Fixtures ----

// David's gas heater as Rheem's REST API returned it on 2026-11-22, with made-up
// identifiers and network details.
def gasHeater = { Map o = [:] ->
    [device_name: DN, serial_number: SERIAL, device_type: "WH",
     "@TYPE": "gasWaterHeater", "@NAME": [constraints: [stringLength: 64], value: "Water Heater"],
     "@ENABLED": [value: true], "@ACTIVE": true, "@RUNNING": "",
     "@SETPOINT": [constraints: [isConversion: true, upperLimit: 140, lowerLimit: 90, units: "degree F", error: []], value: 120],
     "@ALERTCOUNT": 0, "@AWAY": false, "@VACATION": false, "@SCHEDULESTATUS": "", "@SCHEDULERESUME": "",
     "@SCHEDULE": false, "@CONNECTED": true, "@VERSION": [value: "RH-WIFI-00-01-06"],
     "@SIGNAL": [icon: "ic_signalstrength_5.png", constraints: [upperLimit: 500, lowerLimit: -500, units: ""], value: -52],
     "@SSID": [value: "home-network"], "@IP_ADDRESS": [value: "10.0.0.5"]] + o
}

// A heat pump heater, in pyeconet's field shapes.
def heatPump = { Map o = [:] ->
    [device_name: DN, serial_number: SERIAL, device_type: "WH",
     "@TYPE": "heatpumpWaterHeaterGen5", "@NAME": [value: "Garage Heater"],
     "@ENABLED": [value: 1], "@RUNNING": "Compressor Running",
     "@SETPOINT": [constraints: [upperLimit: 140, lowerLimit: 110], value: 125],
     "@MODE": [constraints: [enumText: ["OFF", "ENERGY SAVING", "HEAT PUMP ONLY ", "HIGH DEMAND", "ELECTRIC MODE", "VACATION"]],
               status: "ENERGY SAVING", value: 1],
     "@HOTWATER": "ic_tank_fourty_percent.png", "@TANK": [value: 95], "@COMBUSTION": [value: 88],
     "@ALERTCOUNT": 2, "@AWAY": true, "@AWAYCONFIG": [value: ""], "@VACATION": false,
     "@SCHEDULESTATUS": "Following Schedule", "@CONNECTED": true, "@VERSION": "HP-5.1"] + o
}

// A heat pump with Rheem's leak detection kit.
def leakKit = { Map o = [:] -> heatPump(["@VALVESTATUS": [title: "Shut-OFF Valve - Open", value: 1], "@LEAKINSTALLED": true] + o) }

// A usage report as dynamicAction returns it.
def usage = { String key, List values, String message = null ->
    Map u = [data: values.withIndex().collect { v, i -> [name: i.toString(), value: v] }, historyData: []]
    if (message != null) u.message = message
    [success: true, results: [(key): u]]
}

// ---- App helpers ----

def app = { ->
    def a = HubitatHarness.load(APP)
    a.settings.putAll(username: "user@example.com", password: "s3cret-pw", waterHeaters: [ID], debugOutput: false)
    a.childFactory = { Map p -> new RecordingChild(p) }
    a.syncHttpThrows = true     // Hubitat's httpPost throws HttpResponseException for an error status
    a
}
def authOk = { a, String token = "token-1234abcd" ->
    a.syncHttp << [status: 200, data: [options: [success: true, account_id: "A1"], user_token: token]]
}
def dataOk = { a, List equips -> a.syncHttp << [status: 200, data: [success: true, results: [locations: [[equiptments: equips]]]]] }
def paths = { a -> a.http.collect { it.params.path.toString().tokenize("/").last() } }
def existingChild = { a ->
    a.children["rheem:" + ID] = new RecordingChild(id: 33, deviceNetworkId: "rheem:" + ID, name: "Water Heater")
}

// ---- Driver helpers ----

def driver = { ->
    def d = HubitatHarness.load(DRIVER)
    d.device.displayName = "Water Heater"
    d.device.deviceNetworkId = "rheem:" + ID
    d.withParent()
    d.settings.txtEnable = true
    d
}
// A driver holding `equip`'s fields, as after the app's first poll; recorded calls cleared.
def driverWith = { Map equip -> def d = driver(); d.call("applySnapshot", equip); d.reset(); d }
def connected = { Map equip -> def d = driverWith(equip); d.mqtt.connected = true; d }
// An MQTT message for this heater, as parse() receives it from the harness.
def mqtt = { Map payload, String topic = "user/A1/device/reported" ->
    JsonOutput.toJson([topic: topic, payload: JsonOutput.toJson([device_name: DN, serial_number: SERIAL] + payload)])
}
def published = { d -> d.mqtt.publishes.collect { new JsonSlurper().parseText(it[1] as String) } }

// ---- Frozen names ----

test("the app keeps its name, namespace and author") {
    def a = app()
    assert a.definition.name == "Rheem EcoNet Integration"
    assert a.definition.namespace == "dcm.rheem"
    assert a.definition.author == "Dominick Meglio"
}

test("the driver keeps its name and namespace") {
    def d = driver()
    assert d.definition.name == "Rheem Econet Water Heater"
    assert d.definition.namespace == "dcm.rheem"
    assert d.commands.contains("setWaterHeaterMode")
}

// ---- App: login ----

test("API calls reuse the stored token and don't log in") {
    def a = app()
    a.state.access_token = "token-old1"
    dataOk(a, [gasHeater()]); dataOk(a, [gasHeater()])
    a.call("getEquipment"); a.call("getEquipment")
    assert paths(a) == ["getUserDataForApp", "getUserDataForApp"]
    assert a.http[0].params.headers["ClearBlade-UserToken"] == "token-old1"
    assert a.http[0].params.body == [resource: "friedrich"]
}

test("with no token, the first call logs in once") {
    def a = app()
    authOk(a); dataOk(a, [gasHeater()])
    a.call("getEquipment")
    assert paths(a) == ["auth", "getUserDataForApp"]
    assert a.http[0].params.body == [email: "user@example.com", password: "s3cret-pw"]
    assert a.state.access_token == "token-1234abcd" && a.state.account_id == "A1"
    assert a.state.loginResult.result == "ok"
}

test("a 401 logs in again once and retries with the new token") {
    def a = app()
    a.state.access_token = "token-old1"
    a.syncHttp << [status: 401]
    authOk(a, "token-new2"); dataOk(a, [gasHeater()])
    Map equipment = a.call("getEquipment")
    assert equipment.keySet() == [ID] as Set
    assert paths(a) == ["getUserDataForApp", "auth", "getUserDataForApp"]
    assert a.http[2].params.headers["ClearBlade-UserToken"] == "token-new2"
}

test("logins are at least 10 minutes apart") {
    def a = app()
    a.state.lastLoginAt = a.clock - 5 * 60_000L
    assert a.call("login") == false
    assert a.http.isEmpty()
    a.advance(5 * 60_000L)
    authOk(a)
    assert a.call("login") == true
}

test("a 429 holds logins for 30 minutes and warns once") {
    def a = app()
    a.syncHttp << [status: 429]
    assert a.call("login") == false
    assert a.state.loginResult.result == "rateLimited"
    assert a.log.at("warn").size() == 1 && a.log.at("warn")[0].contains("429")
    a.advance(29 * 60_000L)
    assert a.call("login") == false
    assert a.http.size() == 1
    a.advance(2 * 60_000L)
    a.syncHttp << [status: 429]
    assert a.call("login") == false
    assert a.http.size() == 2
    assert a.log.at("warn").size() == 1
}

test("the settings page logs in despite the spacing and the hold") {
    def a = app()
    a.state.lastLoginAt = a.clock
    a.state.loginHoldUntil = a.clock + 1_000_000L
    authOk(a); dataOk(a, [gasHeater()])
    a.call("prefListDevice")
    assert paths(a) == ["auth", "getUserDataForApp"]
    assert a.state.deviceList == [(ID): "Water Heater"]
    assert a.inputs.find { it.name == "waterHeaters" }.options == [(ID): "Water Heater"]
}

test("the settings page names each kind of login failure") {
    [[reply: [status: 200, data: [options: [success: false]]], text: "rejected the username or password"],
     [reply: [status: 400], text: "rejected the username or password"],
     [reply: [status: 429], text: "limiting logins"],
     [reply: [exception: "Connection timed out"], text: "Couldn't reach Rheem EcoNet: Connection timed out"]].each { c ->
        def a = app()
        a.syncHttp << c.reply
        a.call("prefListDevice")
        assert a.paragraphs.any { it.contains(c.text) }: "${c.reply}"
        assert !a.paragraphs.any { it.contains("username and password you entered is incorrect") }
    }
}

test("the settings page says when Rheem couldn't list the heaters") {
    def a = app()
    authOk(a)
    a.syncHttp << [exception: "Read timed out"] << [exception: "Read timed out"]
    a.call("prefListDevice")
    assert a.paragraphs.any { it.contains("Couldn't read your water heaters") }
}

test("the password and whole tokens are never logged") {
    def a = app()
    a.settings.debugOutput = true
    authOk(a); dataOk(a, [gasHeater()])
    a.syncHttp << [status: 200, data: usage("energy_usage", [1])] << [status: 200, data: usage("water_usage", [2])]
    a.call("installed")
    assert a.log.lines.size() > 0
    assert !a.log.lines.any { it[1].contains("s3cret-pw") || it[1].contains("token-1234") }
    assert a.log.at("info").any { it.contains("abcd") }
}

test("relogin logs in, subject to the 10-minute spacing") {
    def a = app()
    authOk(a)
    assert a.call("relogin") == true
    assert a.call("relogin") == false
    assert a.http.size() == 1
}

// ---- App: equipment and children ----

test("getEquipment falls back to getLocation and skips errored and non-heater equipment") {
    def a = app()
    a.state.access_token = "token-0001"
    a.syncHttp << [status: 200, data: [success: false]]
    a.syncHttp << [status: 200, data: [results: [locations: [[equiptments: [gasHeater(),
            [device_type: "WH", device_name: "1", serial_number: "2", error: "offline"],
            [device_type: "HVAC", device_name: "3", serial_number: "4"]]]]]]]
    Map equipment = a.call("getEquipment")
    assert paths(a) == ["getUserDataForApp", "getLocation"]
    assert equipment.keySet() == [ID] as Set
}

test("Done writes the data values from the snapshot and hands the heater its fields") {
    def a = app()
    a.state.access_token = "token-0001"
    existingChild(a)
    dataOk(a, [gasHeater()])
    a.syncHttp << [status: 200, data: [success: false]] << [status: 200, data: [success: false]]
    a.call("updated")
    def c = a.children["rheem:" + ID]
    assert c.data == [minTemp: "90", maxTemp: "140", tempOnly: "false", enabledDisabled: "true"]
    assert c.callsTo("applySnapshot").size() == 1
    assert c.callsTo("applySnapshot")[0][1]["@SETPOINT"].value == 120
    assert a.schedules.pollEquipment == "runEvery30Minutes"
    assert a.schedules.pollUsage == "runEvery1Hour"
    assert paths(a) == ["getUserDataForApp", "dynamicAction", "dynamicAction"]
}

test("a heat pump's data values: modes, not on/off; the app knows it has a heat pump") {
    def a = app()
    a.state.access_token = "token-0001"
    existingChild(a)
    dataOk(a, [heatPump()])
    a.call("pollEquipment")
    def c = a.children["rheem:" + ID]
    assert c.data == [minTemp: "110", maxTemp: "140", tempOnly: "false", enabledDisabled: "false"]
    assert a.call("hasHeatPump", c) == true
    assert a.call("hasMode", c, "HIGH DEMAND") == true
}

test("a heater with neither modes nor on/off is temperature-only") {
    def a = app()
    a.state.access_token = "token-0001"
    existingChild(a)
    Map equip = gasHeater()
    equip.remove("@ENABLED")
    dataOk(a, [equip])
    a.call("pollEquipment")
    assert a.children["rheem:" + ID].data.tempOnly == "true"
    assert a.children["rheem:" + ID].data.enabledDisabled == "false"
}

test("a newly selected heater gets a child device named from the list") {
    def a = app()
    a.state.access_token = "token-0001"
    a.state.deviceList = [(ID): "Water Heater"]
    dataOk(a, [gasHeater()])
    a.syncHttp << [status: 200, data: [success: false]] << [status: 200, data: [success: false]]
    a.call("updated")
    def c = a.children["rheem:" + ID]
    assert c.typeName == "Rheem Econet Water Heater" && c.name == "Water Heater"
    assert c.callsTo("applySnapshot").size() == 1
}

test("a heater that is no longer selected loses its child device") {
    def a = app()
    a.state.access_token = "token-0001"
    existingChild(a)
    a.children["rheem:9:9"] = new RecordingChild(deviceNetworkId: "rheem:9:9", name: "Old")
    dataOk(a, [gasHeater()])
    a.syncHttp << [status: 200, data: [success: false]] << [status: 200, data: [success: false]]
    a.call("updated")
    assert a.children.keySet() == ["rheem:" + ID] as Set
}

test("Rheem being unreachable at Done warns once and still schedules the polls") {
    def a = app()
    a.state.access_token = "token-0001"
    existingChild(a)
    a.syncHttp << [exception: "Network unreachable"] << [exception: "Network unreachable"]
    a.call("updated")
    assert a.log.at("warn").size() == 1 && a.log.at("warn")[0].contains("trying again in 30 minutes")
    assert a.schedules.pollEquipment == "runEvery30Minutes"
    assert a.children["rheem:" + ID].callsTo("applySnapshot").isEmpty()
}

test("refreshEquipment returns the calling heater's snapshot instead of calling it back") {
    def a = app()
    a.state.access_token = "token-0001"
    existingChild(a)
    dataOk(a, [gasHeater()])
    def equip = a.call("refreshEquipment", "rheem:" + ID)
    def c = a.children["rheem:" + ID]
    assert equip["@SETPOINT"].value == 120
    assert c.callsTo("applySnapshot").isEmpty()
    assert c.data.maxTemp == "140"
    dataOk(a, [gasHeater()])
    a.call("pollEquipment")
    assert c.callsTo("applySnapshot").size() == 1
}

test("debug logging turns itself off after 30 minutes") {
    def a = app()
    a.settings.debugOutput = true
    a.state.access_token = "token-0001"
    a.syncHttp << [exception: "x"] << [exception: "x"]
    a.call("updated")
    assert a.schedules.debugOff == 1800
    a.call("debugOff")
    assert a.settings.debugOutput == false
}

test("the account page keeps its inputs; debug logging defaults to off") {
    def a = app()
    a.call("prefAccountAccess")
    assert a.inputs*.name == ["username", "password", "debugOutput"]
    assert a.inputs.find { it.name == "debugOutput" }.defaultValue == false
}

test("translateThermostatMode, kept for older drivers, counts unknown names as heat") {
    def a = app()
    assert ["ENERGY SAVING": "auto", "Energy Saver": "auto", "HIGH DEMAND": "emergency heat", "PERFORMANCE": "emergency heat",
            "OFF": "off", "ELECTRIC/GAS": "heat", "HEAT PUMP ONLY ": "heat", "GAS": "heat"]
            .every { name, mode -> a.call("translateThermostatMode", name) == mode }
}

// ---- App: usage ----

test("pollUsage asks for today's energy and water use and hands both to the heater") {
    def a = app()
    a.state.access_token = "token-0001"
    existingChild(a)
    a.syncHttp << [status: 200, data: usage("energy_usage", [1, 2])] << [status: 200, data: usage("water_usage", [3])]
    a.call("pollUsage")
    List bodies = a.http*.params*.body
    assert bodies*.usage_type == ["energyUsage", "waterUsage"]
    assert bodies.every { it.ACTION == "waterheaterUsageReportView" && it.device_name == DN && it.serial_number == SERIAL }
    assert bodies[0].start_date == "2027-01-15T00:00:00.999-05:00"
    assert bodies[0].end_date == "2027-01-15T23:59:59.999-05:00"
    assert bodies[1].start_date == "2027-01-15T00:00:00.999"
    assert bodies[1].end_date == "2027-01-15T23:59:59.999"
    assert a.children["rheem:" + ID].callsTo("applyUsage").collect { it[1] } == ["energyUsage", "waterUsage"]
}

test("a heater without usage reports is skipped until the next day") {
    def a = app()
    a.state.access_token = "token-0001"
    existingChild(a)
    a.syncHttp << [status: 200, data: [success: false]] << [status: 200, data: [success: false]]
    a.call("pollUsage")
    assert a.log.at("info").count { it.contains("checking again tomorrow") } == 2
    a.http.clear()
    a.call("pollUsage")
    assert a.http.isEmpty()
    a.advance(24 * 3_600_000L)
    a.syncHttp << [status: 200, data: [success: false]] << [status: 200, data: [success: false]]
    a.call("pollUsage")
    assert a.http.size() == 2
}

finish()
