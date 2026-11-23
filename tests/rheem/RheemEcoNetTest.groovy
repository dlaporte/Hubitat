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

finish()
