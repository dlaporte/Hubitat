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

// ---- Driver: metadata ----

test("the driver declares the new attributes, commands and logging inputs") {
    def d = driver()
    assert d.definition.singleThreaded == true
    assert d.capabilities.containsAll(["Initialize", "Refresh", "Thermostat", "ThermostatMode", "ThermostatHeatingSetpoint"])
    assert d.attributes*.name.containsAll(["waterHeaterMode", "supportedWaterHeaterModes", "runningState", "hotWaterAvailable",
            "tankHealth", "compressorHealth", "shutoffValve", "alertCount", "away", "vacation", "scheduleStatus",
            "wifiSignal", "firmwareVersion", "healthStatus", "energyToday", "waterToday"])
    assert d.commands.containsAll(["setWaterHeaterMode", "setAway", "resumeSchedule"])
    List modes = d.commandDefs.setWaterHeaterMode[0].constraints
    assert modes.containsAll(["Heat Pump", "Energy Saver", "High Demand", "Normal", "Vacation", "Off",
            "Energy Saving", "Heat Pump Only", "Electric", "Gas", "Performance"])
    assert d.inputs*.name == ["logEnable", "txtEnable"]
}

// ---- Driver: connection ----

test("initialize connects with the token, subscribes and asks the app for a reading") {
    def d = driver()
    d.call("initialize")
    Map c = d.mqtt.connects[0]
    assert c.url == "ssl://rheem.clearblade.com:1884" && c.clientId == "hub-uid"
    assert c.user == "token-abcd1234" && c.pass == "e2e699cb0bb0bbb88fc8858cb5a401" && c.opts.cleanSession == false
    assert d.mqtt.subscribes*.getAt(0) == ["user/A1/device/reported", "user/A1/device/desired"]
    assert d.device.current.healthStatus == "online"
    assert d.parent.callsTo("refreshEquipment") == [["refreshEquipment", "rheem:" + ID]]
    assert d.device.data.driverVersion == "2.4.0"
    assert d.device.current.supportedThermostatFanModes == "[]"
    assert d.log.at("warn").isEmpty()
}

test("initialize with debug on turns it off after 30 minutes") {
    def d = driver()
    d.settings.logEnable = true
    d.call("initialize")
    assert d.schedules.logsOff == 1800
}

test("a failed connect asks the app to log in again and backs off 15, 60, 300, 900, then 1800 s") {
    def d = driver()
    d.mqtt.failWith = "Not authorized to connect"
    d.call("initialize")
    List delays = [d.schedules.mqttConnectUntilSuccessful]
    5.times {
        d.call("mqttConnectUntilSuccessful")
        delays << d.schedules.mqttConnectUntilSuccessful
    }
    assert delays == [15, 60, 300, 900, 1800, 1800]
    assert d.parent.callsTo("relogin").size() == 6
}

test("an outage warns once after 60 s, reminds hourly and reports the recovery with its length") {
    def d = driver()
    d.mqtt.failWith = "Connection refused"
    d.call("initialize")
    assert d.log.at("warn").isEmpty()
    assert d.schedules.outageCheck == 60
    d.advance(15_000); d.call("mqttConnectUntilSuccessful")
    d.advance(45_000); d.call("outageCheck")
    assert d.log.at("warn").size() == 1 && d.log.at("warn")[0].contains("Connection refused")
    assert d.device.current.healthStatus == "offline"
    d.advance(60_000); d.call("mqttConnectUntilSuccessful")
    d.advance(300_000); d.call("mqttConnectUntilSuccessful")
    assert d.log.at("warn").size() == 1
    d.advance(3_600_000); d.call("mqttConnectUntilSuccessful")
    assert d.log.at("warn").size() == 2 && d.log.at("warn")[1].contains("still can't connect")
    d.mqtt.failWith = null
    d.advance(1_800_000); d.call("mqttConnectUntilSuccessful")
    assert d.log.at("info").any { it.contains("reconnected to Rheem EcoNet after 1 h") }
    assert d.device.current.healthStatus == "online"
    assert !d.schedules.containsKey("outageCheck")
}

test("a failure that recovers within 60 s logs no warning and no recovery line") {
    def d = driver()
    d.mqtt.failWith = "Connection refused"
    d.call("initialize")
    d.mqtt.failWith = null
    d.advance(15_000); d.call("mqttConnectUntilSuccessful")
    assert !d.schedules.containsKey("outageCheck")
    d.call("outageCheck")
    assert d.log.at("warn").isEmpty()
    assert !d.log.at("info").any { it.contains("reconnected") }
}

test("a lost connection retries after 15 s when it had lasted, and backs off when it hadn't") {
    def d = driver()
    d.call("initialize")
    d.advance(3_600_000)
    d.call("mqttClientStatus", "Error: Connection lost: Connection reset")
    assert d.schedules.mqttConnectUntilSuccessful == 15
    assert d.mqtt.disconnects >= 1
    d.call("mqttConnectUntilSuccessful")
    d.advance(10_000)
    d.call("mqttClientStatus", "Error: Connection lost: Connection reset")
    assert d.schedules.mqttConnectUntilSuccessful == 60
}

test("a status message while a reconnect is pending doesn't schedule another") {
    def d = driver()
    d.mqtt.failWith = "Connection refused"
    d.call("initialize")
    d.call("mqttClientStatus", "Error: Client is not connected")
    assert d.schedules.mqttConnectUntilSuccessful == 15
    assert d.state.retryStep == 1
}

test("other status messages are only logged at debug") {
    def d = driver()
    d.call("mqttClientStatus", "Status: something new")
    assert d.log.at("warn").isEmpty() && d.runIns.isEmpty()
}

test("a command while disconnected is queued and sent on reconnect") {
    def d = driver()
    d.mqtt.failWith = "down"
    d.call("publishWithRetry", ["@SETPOINT": 121])
    assert d.mqtt.publishes.isEmpty()
    assert d.log.at("warn").any { it.contains("isn't connected") }
    d.mqtt.failWith = null
    d.call("mqttConnectUntilSuccessful")
    assert published(d)*."@SETPOINT" == [121]
    assert !d.state.containsKey("queuedMessage")
}

test("a queued command older than 15 minutes is dropped, not sent") {
    def d = driver()
    d.mqtt.failWith = "down"
    d.call("publishWithRetry", ["@AWAY": true])
    d.advance(16 * 60_000L)
    d.mqtt.failWith = null
    d.call("mqttConnectUntilSuccessful")
    assert d.mqtt.publishes.isEmpty()
    assert d.log.at("info").any { it.contains("dropped a command") }
}

test("published messages carry the heater's ids and a transaction id") {
    def d = driver()
    d.mqtt.connected = true
    d.call("publishWithRetry", ["@SETPOINT": 121])
    assert d.mqtt.publishes[0][0] == "user/A1/device/desired"
    Map m = published(d)[0]
    assert m.device_name == DN && m.serial_number == SERIAL
    assert m.transactionId == "ANDROID_2027-01-15T03:00:00"
}

// ---- Driver: fields ----

test("the snapshot's setpoint is a number, not the constraints map") {
    def d = driver()
    d.call("applySnapshot", gasHeater())
    assert d.device.current.heatingSetpoint == 120 && d.device.current.thermostatSetpoint == 120
    assert d.eventsNamed("heatingSetpoint")[0].unit == "°F"
}

test("@ENABLED true and 1 read as heat; false and 0 as off") {
    [[true, "heat", "ON"], [1, "heat", "ON"], [false, "off", "OFF"], [0, "off", "OFF"]].each { v, mode, whm ->
        def d = driver()
        d.call("applySnapshot", gasHeater("@ENABLED": [value: v]))
        assert d.device.current.thermostatMode == mode: "${v}"
        assert d.device.current.waterHeaterMode == whm
    }
}

test("an MQTT value replaces the value inside a stored field and keeps its limits") {
    def d = driverWith(gasHeater())
    d.call("parse", mqtt(["@SETPOINT": 118, "@ENABLED": 0]))
    assert d.device.current.heatingSetpoint == 118
    assert d.device.current.thermostatMode == "off"
    assert d.state.fields["@SETPOINT"].constraints.upperLimit == 140
}

test("@ACTIVE no longer sets the mode") {
    def d = driverWith(gasHeater())
    d.call("parse", mqtt(["@ACTIVE": false]))
    assert d.device.current.thermostatMode == "heat"
}

test("the mode position maps through the heater's own list; a name wins over a position") {
    def d = driverWith(heatPump())
    assert d.device.current.waterHeaterMode == "ENERGY SAVING" && d.device.current.thermostatMode == "auto"
    assert d.device.current.supportedWaterHeaterModes ==
            JsonOutput.toJson(["OFF", "ENERGY SAVING", "HEAT PUMP ONLY", "HIGH DEMAND", "ELECTRIC MODE", "VACATION"])
    assert d.device.current.supportedThermostatModes == JsonOutput.toJson(["off", "heat", "emergency heat", "auto"])
    d.call("parse", mqtt(["@MODE": 3]))
    assert d.device.current.waterHeaterMode == "HIGH DEMAND" && d.device.current.thermostatMode == "emergency heat"
    d.call("parse", mqtt(["@MODE": [value: 0, status: "HEAT PUMP ONLY"]]))
    assert d.device.current.waterHeaterMode == "HEAT PUMP ONLY" && d.device.current.thermostatMode == "heat"
}

test("a position-only update after a bare one isn't pulled back by a stale name") {
    def d = driverWith(heatPump())
    d.call("parse", mqtt(["@MODE": 3]))
    d.call("parse", mqtt(["@MODE": [value: 4]]))
    assert d.device.current.waterHeaterMode == "ELECTRIC MODE"
}

test("a mode name it doesn't know counts as heat") {
    def d = driverWith(heatPump("@MODE": [constraints: [enumText: ["OFF", "ELECTRIC/GAS"]], value: 1]))
    assert d.device.current.thermostatMode == "heat"
    assert d.device.current.waterHeaterMode == "ELECTRIC/GAS"
}

test("David's gas heater: its readings, and nothing for fields it doesn't have") {
    def d = driver()
    d.call("applySnapshot", gasHeater())
    Map c = d.device.current
    assert c.alertCount == 0 && c.away == "off" && c.vacation == "off" && c.scheduleStatus == "none"
    assert c.firmwareVersion == "RH-WIFI-00-01-06" && c.wifiSignal == -52
    assert c.thermostatOperatingState == "idle" && c.runningState == "idle"
    assert c.healthStatus == "online"
    assert c.supportedThermostatModes == JsonOutput.toJson(["off", "heat"])
    ["hotWaterAvailable", "tankHealth", "compressorHealth", "shutoffValve", "supportedWaterHeaterModes"].each {
        assert d.eventsNamed(it).isEmpty(): it
    }
}

test("the heat pump's readings") {
    def d = driverWith(heatPump())
    Map c = d.device.current
    assert c.hotWaterAvailable == 66 && c.tankHealth == 95 && c.compressorHealth == 88
    assert c.alertCount == 2 && c.away == "on" && c.vacation == "off"
    assert c.scheduleStatus == "Following Schedule" && c.firmwareVersion == "HP-5.1"
    assert c.runningState == "Compressor Running" && c.thermostatOperatingState == "heating"
}

test("hot water icons read as 100, 66, 33 and 0%") {
    ["ic_tank_hundread_percent.png": 100, "ic_tank_fourty_percent.png": 66, "ic_tank_ten_percent.png": 33,
     "ic_tank_empty.png": 0, "ic_tank_zero_percent.png": 0].each { icon, pct ->
        def d = driverWith(heatPump("@HOTWATER": icon))
        assert d.device.current.hotWaterAvailable == pct: icon
    }
}

test("the leak kit's shut-off valve reads open, then closed") {
    def d = driverWith(leakKit())
    assert d.device.current.shutoffValve == "open"
    d.call("parse", mqtt(["@VALVESTATUS": [title: "Shut-OFF Valve - Closed"]]))
    assert d.device.current.shutoffValve == "closed"
}

test("the heater reporting @CONNECTED false makes healthStatus offline") {
    def d = driverWith(gasHeater())
    d.call("parse", mqtt(["@CONNECTED": false]))
    assert d.device.current.healthStatus == "offline"
    d.call("parse", mqtt(["@CONNECTED": true]))
    assert d.device.current.healthStatus == "online"
}

test("updates for another heater are ignored; a signal update with another serial is taken") {
    def d = driverWith(gasHeater())
    d.call("parse", JsonOutput.toJson([topic: "user/A1/device/reported",
            payload: JsonOutput.toJson([device_name: "9999", serial_number: SERIAL, "@SETPOINT": 100])]))
    d.call("parse", JsonOutput.toJson([topic: "user/A1/device/reported",
            payload: JsonOutput.toJson([device_name: DN, serial_number: "OTHER", "@SETPOINT": 101])]))
    assert d.device.current.heatingSetpoint == 120
    d.call("parse", JsonOutput.toJson([topic: "user/A1/device/reported",
            payload: JsonOutput.toJson([device_name: DN, serial_number: "WIFI-MODULE", "@SIGNAL": -60])]))
    assert d.device.current.wifiSignal == -60
}

test("network details aren't kept in state") {
    def d = driverWith(gasHeater())
    assert !d.state.fields.containsKey("@SSID") && !d.state.fields.containsKey("@IP_ADDRESS")
    assert d.state.fields.containsKey("@SETPOINT")
}

test("an unreadable MQTT message is ignored") {
    def d = driverWith(gasHeater())
    d.call("parse", JsonOutput.toJson([topic: "user/A1/device/reported", payload: "not json"]))
    assert d.events.isEmpty() && d.log.at("warn").isEmpty()
}

test("descriptionText is logged once per change; Wi-Fi signal changes aren't logged") {
    def d = driverWith(gasHeater())
    d.call("parse", mqtt(["@SETPOINT": 121]))
    assert d.log.at("info") == ["Water Heater heatingSetpoint is 121°F", "Water Heater thermostatSetpoint is 121°F"]
    d.call("parse", mqtt(["@SIGNAL": -60]))
    assert d.log.at("info").size() == 2
    d.settings.txtEnable = false
    d.call("parse", mqtt(["@SETPOINT": 122]))
    assert d.log.at("info").size() == 2
}

// ---- Driver: usage ----

test("applyUsage sums today's energy; the unit comes from Rheem's message, else the heater type") {
    def d = driverWith(gasHeater())
    d.call("applyUsage", "energyUsage", usage("energy_usage", [1.25, 2.5, 0], "Energy usage shown in kBTU"))
    assert d.device.current.energyToday == 3.75
    assert d.eventsNamed("energyToday")[-1].unit == "kBTU"
    d.call("applyUsage", "energyUsage", usage("energy_usage", [1], "Usage"))
    assert d.eventsNamed("energyToday")[-1].unit == "kBTU"
    def hp = driverWith(heatPump())
    hp.call("applyUsage", "energyUsage", usage("energy_usage", [0.5], "Showing energy in KWH today"))
    assert hp.eventsNamed("energyToday")[-1].unit == "kWh"
    hp.call("applyUsage", "energyUsage", usage("energy_usage", [0.5]))
    assert hp.eventsNamed("energyToday")[-1].unit == "kWh"
}

test("applyUsage sums today's water in gallons; a report without data emits nothing") {
    def d = driverWith(heatPump())
    d.call("applyUsage", "waterUsage", usage("water_usage", [12.4, 3.1]))
    assert d.device.current.waterToday == 15.5 && d.eventsNamed("waterToday")[0].unit == "gal"
    d.reset()
    d.call("applyUsage", "waterUsage", [success: true, results: [:]])
    assert d.events.isEmpty()
}

// ---- Driver: commands ----

test("setHeatingSetpoint sends the setpoint") {
    def d = connected(gasHeater())
    d.call("setHeatingSetpoint", 121)
    assert published(d)*."@SETPOINT" == [121]
}

test("setpoints outside the heater's limits are clamped, with an info line") {
    def d = connected(gasHeater())
    d.call("setHeatingSetpoint", 150)
    d.call("setHeatingSetpoint", 80)
    assert published(d)*."@SETPOINT" == [140, 90]
    assert d.log.at("info").count { it.contains("outside 90–140°F") } == 2
}

test("before the first reading, the setpoint limits come from the stored data values") {
    def d = driver()
    d.mqtt.connected = true
    d.device.data.putAll(minTemp: "90", maxTemp: "140")
    d.call("setHeatingSetpoint", 141)
    assert published(d)*."@SETPOINT" == [140]
}

test("setWaterHeaterMode('Off') on an on/off heater turns it off (the 2.3.0 bug)") {
    def d = connected(gasHeater())
    d.call("setWaterHeaterMode", "Off")
    d.call("setWaterHeaterMode", "Normal")
    assert published(d).collect { it.findAll { k, v -> k.startsWith("@") } } == [["@ENABLED": 0], ["@ENABLED": 1]]
}

test("heat and off on an on/off heater send @ENABLED only") {
    def d = connected(gasHeater())
    d.call("heat"); d.call("off"); d.call("setThermostatMode", "heat")
    assert published(d).collect { it.findAll { k, v -> k.startsWith("@") } } == [["@ENABLED": 1], ["@ENABLED": 0], ["@ENABLED": 1]]
}

test("modes are sent by position in the heater's own list, and the old names still work") {
    def d = connected(heatPump())
    ["Energy Saver": 1, "Heat Pump": 2, "High Demand": 3, "Normal": 4, "Electric": 4, "Vacation": 5,
     "energy saving": 1, "HEAT_PUMP_ONLY": 2].each { name, idx ->
        d.mqtt.publishes.clear()
        d.call("setWaterHeaterMode", name)
        assert published(d)[0]."@MODE" == idx: name
        assert published(d)[0]."@ENABLED" == 1
    }
    d.mqtt.publishes.clear()
    d.call("setWaterHeaterMode", "Off")
    assert published(d)[0]."@MODE" == 0 && published(d)[0]."@ENABLED" == 0
}

test("Electric/Gas stands in for Electric or Gas, either way round") {
    def d = connected(heatPump("@MODE": [constraints: [enumText: ["OFF", "ELECTRIC/GAS", "VACATION"]], value: 1]))
    d.call("setWaterHeaterMode", "Gas")
    assert published(d)[0]."@MODE" == 1
    def g = connected(heatPump("@MODE": [constraints: [enumText: ["OFF", "GAS"]], value: 1]))
    g.call("setWaterHeaterMode", "Electric/Gas")
    assert published(g)[0]."@MODE" == 1
}

test("a mode the heater doesn't list warns, names what it offers, and sends nothing") {
    def d = connected(heatPump())
    d.call("setWaterHeaterMode", "Performance")
    assert d.mqtt.publishes.isEmpty()
    assert d.log.at("warn")[0].contains("it offers OFF, ENERGY SAVING, HEAT PUMP ONLY, HIGH DEMAND, ELECTRIC MODE, VACATION")
}

test("auto and emergencyHeat pick Energy Saving and High Demand; heat keeps a heating mode or picks Heat Pump") {
    def d = connected(heatPump())
    d.call("auto"); d.call("emergencyHeat"); d.call("heat")
    assert published(d).collect { it."@MODE" } == [1, 3, 2]
    d.call("parse", mqtt(["@MODE": 4]))
    d.mqtt.publishes.clear()
    d.call("heat"); d.call("off")
    assert published(d).collect { [it."@MODE", it."@ENABLED"] } == [[4, 1], [0, 0]]
}

test("auto on a heater without Energy Saving warns and sends nothing") {
    def d = connected(gasHeater())
    d.call("auto")
    assert d.mqtt.publishes.isEmpty()
    assert d.log.at("warn")[0].contains("has no Energy Saving mode")
}

test("setThermostatMode cool warns") {
    def d = connected(gasHeater())
    d.call("setThermostatMode", "cool")
    assert d.mqtt.publishes.isEmpty() && d.log.at("warn").size() == 1
}

test("setAway sends @AWAY where the heater has away mode, and warns where it doesn't") {
    def d = connected(gasHeater())
    d.call("setAway", "on"); d.call("setAway", "off")
    assert published(d)*."@AWAY" == [true, false]
    Map noAway = gasHeater()
    noAway.remove("@AWAY")
    def n = connected(noAway)
    n.call("setAway", "on")
    assert n.mqtt.publishes.isEmpty() && n.log.at("warn")[0].contains("away")
}

test("resumeSchedule sends resume and confirms Rheem's dialog once, only within 60 s") {
    def d = connected(heatPump())
    d.call("resumeSchedule")
    assert published(d)[0]."@SCHEDULERESUME" == "resume"
    d.advance(5_000)
    d.call("parse", mqtt([dialog: [title: "End Away", message: ["@SCHEDULERESUME": "resume", "@AWAY": false]]]))
    assert published(d).size() == 2 && published(d)[1]."@AWAY" == false
    d.call("parse", mqtt([dialog: [message: ["@SCHEDULERESUME": "resume"]]]))
    assert published(d).size() == 2
    d.call("resumeSchedule")
    d.advance(61_000)
    d.call("parse", mqtt([dialog: [message: ["@X": 1]]]))
    assert published(d).size() == 3
}

test("a dialog whose message is text is confirmed by sending the resume again") {
    def d = connected(gasHeater())
    d.call("resumeSchedule")
    d.call("parse", mqtt([dialog: [title: "End Away", message: "Continuing will end Away Event"]]))
    assert published(d)*."@SCHEDULERESUME" == ["resume", "resume"]
    assert d.log.at("info").any { it.contains("confirming Rheem's prompt (End Away)") }
}

test("resumeSchedule on a heater without a schedule warns") {
    Map equip = gasHeater()
    equip.remove("@SCHEDULESTATUS")
    def d = connected(equip)
    d.call("resumeSchedule")
    assert d.mqtt.publishes.isEmpty() && d.log.at("warn").size() == 1
}

test("Thermostat commands a water heater can't do warn, once each") {
    def d = connected(gasHeater())
    d.call("cool"); d.call("fanAuto"); d.call("fanCirculate"); d.call("fanOn")
    d.call("setCoolingSetpoint", 70); d.call("setThermostatFanMode", "on"); d.call("setSchedule", "x")
    assert d.log.at("warn").size() == 7 && d.log.at("warn").every { it.contains("not supported by a water heater") }
    assert d.log.at("error").isEmpty() && d.mqtt.publishes.isEmpty()
}

test("refresh asks the app and applies the heater's fields") {
    def d = driverWith(gasHeater())
    d.parent.answers.refreshEquipment = { String dni -> gasHeater("@SETPOINT": [value: 119]) }
    d.call("refresh")
    assert d.device.current.heatingSetpoint == 119
    assert d.state.fields["@SETPOINT"].constraints.upperLimit == 140
}

test("before the first reading, a mode command on a heater of unknown type warns") {
    def d = driver()
    d.mqtt.connected = true
    d.device.data.putAll(enabledDisabled: "false", tempOnly: "false")
    d.call("setWaterHeaterMode", "High Demand")
    assert d.mqtt.publishes.isEmpty() && d.log.at("warn")[0].contains("no modes or on/off")
}

finish()
