import harness.HubitatHarness

import static harness.TestRunner.finish
import static harness.TestRunner.test

final String DRIVER = "ASH26/device/Amcrest-ASH26.groovy"

def fresh = {
    def d = HubitatHarness.load(DRIVER)
    d.device.displayName = "Backyard Floodlight"
    d.settings.putAll(ip: "10.0.0.30", username: "admin", password: "secret")
    d
}

final Map CHALLENGE = ["WWW-Authenticate": 'Digest realm="Login to AMC", qop="auth", nonce="abc123", opaque="xyz"']

// Answer the digest challenge for the oldest pending probe, then the authed call with `body`.
def answer = { d, String body ->
    def probe = d.http.find { it.callback == "unauthRequestHandler" }
    d.reply(probe, status: 401, headers: CHALLENGE)
    def authed = d.http.find { it.callback == "authedRequestHandler" }
    assert authed.params.headers.Authorization.startsWith('Digest username="admin"')
    d.reply(authed, body: body)
}

def settingsText = { String mode -> """table.All.General.MachineName=Backyard
table.All.VSP_PaaS.SN=ABC123
table.All.PaaS_UPGRADE.LastVersion=V2.0
table.All.Network.eth2.PhysicalAddress=aa:bb:cc:dd:ee:ff
table.All.WLan.eth2.SSID=home
table.All.Lighting_V2[0][0][1].Mode=${mode}
""" }

// ---- Metadata ----

test("definition follows STYLE.md") {
    def d = fresh()
    assert d.definition.name == "Amcrest ASH26"
    assert d.definition.namespace == "dlaporte"
    assert d.definition.author == "David LaPorte"
    assert d.definition.importUrl == "https://raw.githubusercontent.com/dlaporte/Hubitat/main/ASH26/device/Amcrest-ASH26.groovy"
    assert d.definition.singleThreaded == true
    assert d.capabilities == ["Initialize", "Refresh", "Switch"]
}

test("preferences keep their names; logging inputs replace debug") {
    assert fresh().inputs*.name == ["ip", "username", "password", "logEnable", "txtEnable"]
}

// ---- Lifecycle ----

test("initialize removes debug, records driverVersion, polls every minute and fetches now") {
    def d = fresh()
    d.settings.debug = true
    d.call("initialize")
    assert d.device.removedSettings.contains("debug")
    assert d.device.data.driverVersion == "0.8.0"
    assert d.schedules.refresh == "runEvery1Minute"
    assert d.http.size() == 1 && d.http[0].params.uri.contains("getConfig&name=All")
}

test("logsOff is scheduled after unschedule, so Initialize can't cancel it") {
    def d = fresh()
    d.settings.logEnable = true
    d.call("initialize")
    assert d.unschedules == ["*"]
    assert d.schedules.logsOff == 1800
}

// ---- Logging ----

test("a poll sets switch from the light mode and logs descriptionText on change only") {
    def d = fresh()
    d.call("refresh")
    answer(d, settingsText("Manual"))
    assert d.device.current.switch == "on" && d.device.current.light_status == "on"
    assert d.device.current.device_name == "Backyard"
    assert d.log.at("info").contains("Backyard Floodlight is on")
    int n = d.log.at("info").size()
    d.call("refresh")
    answer(d, settingsText("Manual"))
    assert d.log.at("info").size() == n
}

test("no 'ASH26:' prefixes; debug only with logEnable") {
    def d = fresh()
    d.call("refresh")
    assert d.log.at("debug").isEmpty()
    d.settings.logEnable = true
    d.call("refresh")
    assert d.log.at("debug") == ["refresh()"]
    assert !new File(DRIVER).text.contains('"ASH26: ')
}

finish()
