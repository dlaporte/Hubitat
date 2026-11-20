import harness.HubitatHarness

import static harness.TestRunner.finish
import static harness.TestRunner.test

final String APP = "SmartOilGauge/app/smartoilgauge.groovy"
final String CHILD = "SmartOilGauge/device/smartoilgauge.groovy"

def fresh = {
    def a = HubitatHarness.load(APP)
    a.settings.putAll(ClientId: "id", ClientSecret: "secret")
    a.state.APIToken = "api-token"
    a.state.APITokenExpirationTime = a.clock + 1000 * 3_600_000L
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

// A set-up app with one tank device, as after install.
def installed = {
    def a = fresh()
    tanksReply(a, [tank()])
    a.call("initialize")
    a.call("updated")             // the re-run initialize() schedules (5 s later)
    a.reset()
    a
}

def child = { a, String id = "S1" -> a.children["55.${id}"] }

// ---- Fixes: repeated readings ----

test("a poll that returns the same reading adds no usage sample") {
    def a = installed()
    int before = (a.state.tankHistory["S1"] as List).size()
    a.advance(3_600_000L)
    tanksReply(a, [tank()])                                // same last_read
    a.call("pollChildren")
    assert (a.state.tankHistory["S1"] as List).size() == before
    a.advance(3_600_000L)
    tanksReply(a, [tank(gallons: "149.0", lastRead: readAt(0))])
    a.call("pollChildren")
    assert (a.state.tankHistory["S1"] as List).size() == before + 1
}

test("saving the app (cached data) adds no usage sample") {
    def a = installed()
    int before = (a.state.tankHistory["S1"] as List).size()
    a.call("pollChildren", false)
    assert (a.state.tankHistory["S1"] as List).size() == before
}

// ---- Fixes: new tanks ----

test("a tank that appears later gets a device at the next poll") {
    def a = installed()
    tanksReply(a, [tank(), tank(id: "S2", num: "2")])
    a.call("pollChildren")
    assert child(a, "S2")?.displayName == "Tank 2"
    assert child(a, "S2").current.gallons == 150.0f
}

// ---- Fixes: tile ----

test("the tile turns red at the low-fuel setting, not a fixed 25%") {
    def a = fresh()
    assert a.call("renderTankSvg", 20).contains("#b94a48")          // default 25%
    a.settings.lowFuelThresholdPct = 15
    assert !a.call("renderTankSvg", 20).contains("#b94a48")
    a.settings.lowFuelThresholdPct = 35
    assert a.call("renderTankSvg", 30).contains("#b94a48")
}

// ---- Fixes: timeouts ----

test("both Droplet API calls carry a timeout") {
    def a = fresh()
    a.state.APIToken = null
    a.syncHttp << [status: 200, data: [access_token: "t2", expires_in: 3600]]
    tanksReply(a, [tank()])
    a.call("fetchTankData")
    assert a.http.size() == 2 && a.http.every { it.params.timeout == 15 }
}

// ---- Fixes: cleanup ----

test("the unused groovy.json import and the v0.0.7 state cleanup are gone") {
    assert !new File(APP).text.contains("import groovy.json.*")
    def a = fresh()
    a.state.autoTyp = "x"
    tanksReply(a, [tank()])
    a.call("initialize")
    assert a.state.autoTyp == "x"
}

// ---- Fixes: outage handling and healthStatus ----

test("rejected credentials are an error, not an outage warning") {
    def a = fresh()
    a.state.APIToken = null
    a.syncHttp << [status: 401, data: [error: "invalid_client"]]
    a.call("pollChildren")
    assert a.log.at("error").size() == 1 && a.log.at("error")[0].contains("rejected the Client ID / Secret")
}

test("an API outage logs once, reminds hourly, recovers with its length; tanks go offline and back") {
    def a = installed()
    assert child(a).current.healthStatus == "online"
    a.syncHttp << [exception: "Read timed out"]
    a.call("pollChildren")
    assert a.log.at("warn").size() == 1 && a.log.at("warn")[0].contains("isn't answering")
    assert child(a).current.healthStatus == "offline"
    a.advance(3_600_000L)
    a.syncHttp << [exception: "Read timed out"]
    a.call("pollChildren")
    assert a.log.at("warn").size() == 2 && a.log.at("warn")[1].contains("after 1h 0m")
    assert a.log.at("error").isEmpty()
    a.advance(3_600_000L)
    // A failed fetch drops the token, so recovery starts with a new one.
    a.syncHttp << [status: 200, data: [access_token: "t3", expires_in: 3600]]
    tanksReply(a, [tank(lastRead: readAt(0))])
    a.call("pollChildren")
    assert a.log.at("info").any { it.contains("responding again after 2h 0m") }
    assert child(a).current.healthStatus == "online"
}

test("a tank whose last reading is over 96 hours old is offline") {
    def a = installed()
    tanksReply(a, [tank(lastRead: readAt(97))])
    a.call("pollChildren")
    assert child(a).current.healthStatus == "offline"
    assert a.log.at("warn").any { it.contains("Tank 1") && it.contains("hasn't reported") }
    tanksReply(a, [tank(lastRead: readAt(0.5))])
    a.call("pollChildren")
    assert child(a).current.healthStatus == "online"
}

test("healthStatus is declared on the tank device; the lastRefillAt comment no longer says ISO") {
    def c = HubitatHarness.load(CHILD)
    assert c.attributes*.name.contains("healthStatus")
    assert !new File(CHILD).text.contains("ISO timestamp")
}

finish()
