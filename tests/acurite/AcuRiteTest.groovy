import harness.HubitatHarness

import static harness.TestRunner.finish
import static harness.TestRunner.test

final String DRIVER = "AcuRite/device/acurite.groovy"

def fresh = {
    def d = HubitatHarness.load(DRIVER)
    d.device.displayName = "Cabin Weather"
    d.settings.putAll(acurite_username: "me@example.com", acurite_password: "secret", device_id: "24C86E0", poll_interval: "5 Minutes")
    d
}

def loginOk = [token_id: "tok-1234", user: [account_users: [[account_id: 77]]]]

// A dashboard reply. `sensors` replaces the sensor list; wired_sensors is absent
// unless given (the API omits it on some stations).
def dashboard = { Map opts = [:] ->
    Map dev = [name: "Weather Station", model_code: "Atlas", status_code: "Active", battery_level: "Normal",
               signal_strength: 4, last_check_in_at: "2026-11-20T19:00:00Z",
               sensors: opts.sensors ?: [
                   [sensor_name: "Temperature", last_reading_value: "41.2", chart_unit: "F"],
                   [sensor_name: "Humidity", last_reading_value: "80", chart_unit: "%"],
                   [sensor_name: "Wind Speed", last_reading_value: "5", chart_unit: "mph"],
                   [sensor_name: "Wind Direction", last_reading_value: "225", chart_unit: "°",
                    wind_direction: [abbreviation: "SW", point: "225"]],
                   [sensor_name: "Lightning Strike Count", last_reading_value: (opts.strikes ?: "0")]]]
    if (opts.containsKey("wired")) dev.wired_sensors = opts.wired
    [name: "Cabin", latitude: "44.1", longitude: "-71.1", elevation: 1000, elevation_unit: "ft",
     timezone: "America/New_York", country: "US", devices: [dev]]
}

// Give the driver a live session.
def withSession = { d ->
    d.state.acurite_token = "tok-live"
    d.state.acurite_account_id = 77
    d.state.acurite_token_expiry = d.clock + 3_000_000L
    d
}

// ---- Metadata ----

test("definition follows STYLE.md") {
    def d = fresh()
    assert d.definition.name == "AcuRite Weather Station"
    assert d.definition.namespace == "dlaporte"
    assert d.definition.author == "David LaPorte"
    assert d.definition.importUrl == "https://raw.githubusercontent.com/dlaporte/Hubitat/main/AcuRite/device/acurite.groovy"
    assert d.definition.singleThreaded == true
    assert d.capabilities.containsAll(["Initialize", "Refresh", "Temperature Measurement", "Battery"])
}

test("preferences keep their names; logging inputs replace debug") {
    assert fresh().inputs*.name == ["acurite_username", "acurite_password", "device_id", "poll_interval",
                                    "lightningWindowMinutes", "logEnable", "txtEnable"]
}

// ---- Lifecycle ----

test("initialize removes debug, records driverVersion, schedules the poll and fetches now") {
    def d = withSession(fresh())
    d.settings.debug = true
    d.call("initialize")
    assert d.device.removedSettings.contains("debug")
    assert d.device.data.driverVersion == "0.1.0"
    assert d.schedules.refresh == "runEvery5Minutes"
    // the cached session is dropped, so this is a fresh login
    assert d.http.size() == 1 && d.http[0].callback == "loginHandler"
}

test("logsOff is scheduled after unschedule, so Initialize can't cancel it") {
    def d = fresh()
    d.settings.logEnable = true
    d.call("initialize")
    assert d.unschedules == ["*"]
    assert d.schedules.logsOff == 1800
}

test("login then fetch fills the attributes and weatherSummary") {
    def d = fresh()
    d.call("refresh")
    d.reply(d.http[0], json: loginOk)
    def fetch = d.http[0]
    assert fetch.params.uri.endsWith("/accounts/77/dashboard/hubs/24C86E0")
    d.reply(fetch, json: dashboard(wired: []))
    assert d.device.current.temperature == "41.2"
    assert d.device.current.windDirection == "225"
    assert d.device.current.weatherSummary == "41.2°F, 80% RH, wind SW 5 mph"
}

// ---- Logging ----

test("descriptionText for weather values is logged on change only, and not when txtEnable is false") {
    def d = withSession(fresh())
    d.call("refresh")
    d.reply(d.http[0], json: dashboard(wired: []))
    assert d.log.at("info").contains("Cabin Weather temperature is 41.2 F")
    int n = d.log.at("info").size()
    d.call("refresh")
    d.reply(d.http[0], json: dashboard(wired: []))
    assert d.log.at("info").size() == n
    d.settings.txtEnable = false
    d.call("refresh")
    d.reply(d.http[0], json: dashboard(wired: [], sensors: [[sensor_name: "Temperature", last_reading_value: "39.0", chart_unit: "F"]]))
    assert d.log.at("info").size() == n
}

test("no 'AcuRite:' prefixes; the token never reaches the logs") {
    def d = fresh()
    d.settings.logEnable = true
    d.call("refresh")
    d.reply(d.http[0], json: loginOk)
    assert !d.log.lines.any { it[1].contains("tok-1234") }
    assert !new File(DRIVER).text.contains('"AcuRite: ')
}

finish()
