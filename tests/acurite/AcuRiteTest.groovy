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

// The station's check-in time in the API's format, minutes before the harness clock.
def checkin = { long minutesAgo ->
    def fmt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    fmt.setTimeZone(TimeZone.getTimeZone("UTC"))
    fmt.format(new Date(1_800_000_000_000L - minutesAgo * 60_000L))
}

def loginOk = [token_id: "tok-1234", user: [account_users: [[account_id: 77]]]]

// A dashboard reply. `sensors` replaces the sensor list; wired_sensors is absent
// unless given (the API omits it on some stations).
def dashboard = { Map opts = [:] ->
    Map dev = [name: "Weather Station", model_code: "Atlas", status_code: "Active", battery_level: "Normal",
               signal_strength: 4, last_check_in_at: opts.checkin ?: checkin(5),
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

// ---- Fixes: session handling ----

test("an expired session logs in before fetching") {
    def d = withSession(fresh())
    d.state.acurite_token_expiry = d.clock - 1
    d.call("refresh")
    assert d.http.size() == 1 && d.http[0].callback == "loginHandler"
}

test("a live session fetches without logging in") {
    def d = withSession(fresh())
    d.call("refresh")
    assert d.http.size() == 1 && d.http[0].callback == "fetchAcuriteDataHandler"
    assert d.http[0].params.headers["x-one-vue-token"] == "tok-live"
}

[401, 403].each { int code ->
    test("HTTP ${code} on the data call logs in again once and retries") {
        def d = withSession(fresh())
        d.call("refresh")
        d.reply(d.http[0], status: code, error: "Unauthorized")
        assert d.http.size() == 1 && d.http[0].callback == "loginHandler"
        d.reply(d.http[0], json: loginOk)
        assert d.http.size() == 1 && d.http[0].callback == "fetchAcuriteDataHandler" && d.http[0].data.retried == true
        d.reply(d.http[0], status: code, error: "Unauthorized")
        assert d.http.isEmpty()                                   // no loop
        assert d.log.at("error").size() == 1
    }
}

test("a timeout keeps the session and doesn't log in again") {
    def d = withSession(fresh())
    d.call("refresh")
    d.reply(d.http[0], status: 408, error: "Read timed out")
    assert d.http.isEmpty()
    assert d.state.acurite_token == "tok-live"
}

test("a rejected login is reported as a credentials problem") {
    def d = fresh()
    d.call("refresh")
    d.reply(d.http[0], status: 401, error: "Unauthorized")
    assert d.log.at("error").size() == 1 && d.log.at("error")[0].contains("rejected the login")
}

// ---- Fixes: outage handling ----

test("an outage logs once, reminds hourly, and logs the recovery with its length") {
    def d = withSession(fresh())
    d.state.acurite_token_expiry = d.clock + 10 * 3_600_000L
    d.call("refresh")
    d.reply(d.http[0], status: 503, error: "Service Unavailable")
    assert d.log.at("warn").size() == 1 && d.log.at("warn")[0].contains("answered with an error")
    assert d.device.current.healthStatus == "offline"
    d.advance(300_000L)
    d.call("refresh")
    d.reply(d.http[0], status: 408, error: "Read timed out")
    assert d.log.at("warn").size() == 1                       // same outage, error text changed
    d.advance(3_600_000L)
    d.call("refresh")
    d.reply(d.http[0], status: 408, error: "Read timed out")
    assert d.log.at("warn").size() == 2 && d.log.at("warn")[1].contains("after 1h 5m")
    d.advance(300_000L)
    d.call("refresh")
    d.reply(d.http[0], json: dashboard(wired: []))
    assert d.log.at("info").any { it.contains("responding again after 1h 10m") }
    assert d.device.current.healthStatus == "online"
}

// ---- Fixes: parsing ----

test("a station without wired_sensors still gets lightning and the summary") {
    def d = withSession(fresh())
    d.call("refresh")
    d.reply(d.http[0], json: dashboard())                    // no wired_sensors key
    assert d.log.at("error").isEmpty()
    assert d.device.current.lightningActive == "false"
    assert d.device.current.weatherSummary == "41.2°F, 80% RH, wind SW 5 mph"
}

test("sensor entries without a name are skipped") {
    def d = withSession(fresh())
    d.call("refresh")
    d.reply(d.http[0], json: dashboard(wired: [null, [last_reading_value: "1"]]))
    assert d.log.at("error").isEmpty()
    assert d.device.current.weatherSummary
}

// ---- Fixes: lightningActive clears on time ----

test("lightningActive turns on with a new strike and clears when the window ends") {
    def d = withSession(fresh())
    d.state.acurite_prev_strikes = 2
    d.call("refresh")
    d.reply(d.http[0], json: dashboard(wired: [], strikes: "3"))
    assert d.device.current.lightningActive == "true"
    assert d.schedules.lightningWindowEnded == 600
    d.advance(600_000L)
    d.call("lightningWindowEnded")
    assert d.device.current.lightningActive == "false"
}

test("a timer that fires a moment early re-arms instead of leaving it on") {
    def d = withSession(fresh())
    d.state.acurite_prev_strikes = 2
    d.call("refresh")
    d.reply(d.http[0], json: dashboard(wired: [], strikes: "3"))
    d.advance(599_500L)
    d.call("lightningWindowEnded")
    assert d.device.current.lightningActive == "true"
    assert d.schedules.lightningWindowEnded == 1
}

// ---- Fixes: cleanup ----

test("attributes the capabilities already provide aren't declared again") {
    def names = fresh().attributes*.name
    assert names.intersect(["temperature", "humidity", "pressure", "illuminance", "ultravioletIndex", "battery"]).isEmpty()
    assert names.containsAll(["lightningActive", "weatherSummary", "windSpeed", "windDirection", "location_name", "healthStatus"])
}

test("initialize no longer deletes pre-0.0.10 attributes") {
    def d = fresh()
    d.call("initialize")
    assert d.device.deletedStates.isEmpty()
}

// ---- Fixes: a station that stopped reporting ----

test("a station that hasn't checked in for over 2 hours is offline, with one warning") {
    def d = withSession(fresh())
    d.call("refresh")
    d.reply(d.http[0], json: dashboard(wired: [], checkin: "2026-02-18T08:17:00.000+00:00"))
    assert d.device.current.healthStatus == "offline"
    assert d.log.at("warn").size() == 1 && d.log.at("warn")[0].contains("hasn't reported to myAcuRite since 2026-02-18T08:17:00.000+00:00")
    d.call("refresh")
    d.reply(d.http[0], json: dashboard(wired: [], checkin: "2026-02-18T08:17:00.000+00:00"))
    assert d.log.at("warn").size() == 1
    d.call("refresh")
    d.reply(d.http[0], json: dashboard(wired: []))
    assert d.device.current.healthStatus == "online"
    assert d.log.at("info").contains("the station is reporting to myAcuRite again")
}

test("a stale station stays offline when the API recovers from an outage") {
    def d = withSession(fresh())
    d.state.acurite_token_expiry = d.clock + 10 * 3_600_000L
    d.call("refresh")
    d.reply(d.http[0], json: dashboard(wired: [], checkin: "2026-02-18T08:17:00.000+00:00"))
    d.call("refresh")
    d.reply(d.http[0], status: 503, error: "Service Unavailable")
    d.call("refresh")
    d.reply(d.http[0], json: dashboard(wired: [], checkin: "2026-02-18T08:17:00.000+00:00"))
    assert d.device.current.healthStatus == "offline"
}

test("an unparseable check-in time doesn't mark the station stale") {
    def d = withSession(fresh())
    d.call("refresh")
    d.reply(d.http[0], json: dashboard(wired: [], checkin: "yesterday"))
    assert d.device.current.healthStatus == "online"
}

finish()
