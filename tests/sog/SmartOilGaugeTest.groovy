import harness.HubitatHarness

import static harness.TestRunner.finish
import static harness.TestRunner.test

final String APP = "SmartOilGauge/app/smartoilgauge.groovy"
final String CHILD = "SmartOilGauge/device/smartoilgauge.groovy"

def fresh = {
    def a = HubitatHarness.load(APP)
    a.settings.putAll(ClientId: "id", ClientSecret: "secret")
    a.state.APIToken = "api-token"
    a.state.APITokenExpirationTime = a.clock + 3_000_000L
    a
}

// last_read as the Droplet API formats it, `hoursAgo` before the harness clock (hub time zone).
def readAt = { double hoursAgo ->
    def fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    fmt.setTimeZone(TimeZone.getTimeZone("America/New_York"))
    fmt.format(new Date(1_800_000_000_000L - (long) (hoursAgo * 3_600_000L)))
}

def tank = { Map o = [:] ->
    [sensor_id: o.id ?: "S1", tank_num: o.num ?: "1", gallons: o.gallons ?: "150.0", tank_volume: "275",
     last_read: o.lastRead ?: readAt(1), acct_num: "A1", battery: "Good"]
}

// Queue one get_tank_data reply.
def tanksReply = { a, List tanks -> a.syncHttp << [status: 200, data: groovy.json.JsonOutput.toJson([result: "ok", data: tanks])] }

// ---- Metadata ----

test("app definition follows STYLE.md") {
    def a = fresh()
    assert a.definition.name == "Smart Oil Gauge (Connect)"
    assert a.definition.namespace == "dlaporte"
    assert a.definition.author == "David LaPorte"
    assert a.definition.importUrl == "https://raw.githubusercontent.com/dlaporte/Hubitat/main/SmartOilGauge/app/smartoilgauge.groovy"
    assert a.call("appVersion") == "0.1.0"
}

test("child driver definition follows STYLE.md") {
    def c = HubitatHarness.load(CHILD)
    assert c.definition.name == "Smart Oil Gauge"
    assert c.definition.author == "David LaPorte"
    assert c.definition.importUrl == "https://raw.githubusercontent.com/dlaporte/Hubitat/main/SmartOilGauge/device/smartoilgauge.groovy"
}

test("the settings page ends with the standard logEnable input; showDebug is gone") {
    def a = fresh()
    a.settings.remove("ClientId")
    a.call("settingsPage")
    assert a.inputs*.name == ["ClientId", "ClientSecret", "logEnable"]
    assert a.inputs[-1].title == "Enable debug logging"
}

// ---- Lifecycle ----

test("initialize removes showDebug, records the version and polls hourly") {
    def a = fresh()
    a.settings.showDebug = true
    tanksReply(a, [tank()])
    a.call("initialize")
    assert a.app.removedSettings.contains("showDebug")
    assert a.state.appVersion == "0.1.0"
    assert a.children.size() == 1
    // Creating a device re-runs updated() 5 s later; that run schedules the poll.
    assert a.schedules.updated == 5
    a.call("updated")
    assert a.schedules.pollChildren == "runEvery1Hour"
}

test("logsOff is scheduled after unschedule; logsOff turns debug off") {
    def a = fresh()
    a.settings.logEnable = true
    tanksReply(a, [tank()])
    a.call("initialize")
    assert a.unschedules == ["*"]
    assert a.schedules.logsOff == 1800
    a.call("logsOff")
    assert a.settings.logEnable == false
}

test("the child records driverVersion when the app sends it a reading") {
    def c = HubitatHarness.load(CHILD)
    c.call("generateEvent", [gallons: 100])
    assert c.device.data.driverVersion == "0.1.0"
    assert c.device.current.gallons == 100
}

test("no 'SOG:' prefixes; info lines aren't hidden behind debug") {
    def text = new File(APP).text
    assert !text.contains('"SOG: ')
    assert !text.contains("showDebug) log.info")
}

finish()
