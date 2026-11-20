import harness.HubitatHarness

import static harness.TestRunner.finish
import static harness.TestRunner.test

final String DRIVER = "LGglasshouse/device/LGTV-Glasshouse.groovy"
final String NOTIFIER = "LGglasshouse/device/LGTV-Glasshouse-Notifier.groovy"

def fresh = {
    def d = HubitatHarness.load(DRIVER)
    d.device.displayName = "Living Room TV"
    d.device.id = 417
    d.settings.putAll(tvIp: "10.0.0.20", tvPort: "8080", pollInterval: "1")
    d
}

// A stats reply for a TV that is on.
def statsOn = { Map extra = [:] ->
    [ok: true, powerState: [systemOn: true, raw: "Active", screenOn: true], volume: 12, muted: false,
     app: "hdmi1", app_id: "com.webos.app.hdmi1", inputs: [hdmi1: "Apple TV"]] + extra
}

def bodyOf = { Map req -> new groovy.json.JsonSlurper().parseText(req.params.body as String) }

// ---- Metadata ----

test("definition follows STYLE.md and runs single-threaded") {
    def d = fresh()
    assert d.definition.name == "LG TV (Glasshouse)"
    assert d.definition.namespace == "dlaporte"
    assert d.definition.author == "David LaPorte"
    assert d.definition.importUrl == "https://raw.githubusercontent.com/dlaporte/Hubitat/main/LGglasshouse/device/LGTV-Glasshouse.groovy"
    assert d.definition.singleThreaded == true
    assert d.capabilities == ["Refresh", "Initialize", "Actuator"]
    assert d.commands.isEmpty()
}

test("logging inputs come last; debugLogging is gone") {
    def d = fresh()
    def names = d.inputs*.name
    assert names.takeRight(2) == ["logEnable", "txtEnable"]
    assert !names.contains("debugLogging")
    assert names.containsAll(["tvIp", "tvPort", "tvMac", "pollInterval", "warnUnreachable", "prefPower",
                              "prefLaunchApp", "prefSendKey", "prefVolume", "prefMute", "prefInput",
                              "prefPictureMode", "prefScreen", "notifierEnabled"])
}

// ---- Lifecycle ----

test("initialize removes debugLogging, records driverVersion and polls every minute") {
    def d = fresh()
    d.settings.debugLogging = true
    d.call("initialize")
    assert d.device.removedSettings.contains("debugLogging")
    assert d.device.data.driverVersion == "0.11.0"
    assert d.schedules.refresh == "runEvery1Minute"
}

test("logsOff is scheduled after unschedule, so Initialize can't cancel it") {
    def d = fresh()
    d.settings.logEnable = true
    d.call("initialize")
    assert d.unschedules == ["*"]
    assert d.schedules.logsOff == 1800
}

test("updated logs 'Preferences saved' and still applies a power preference that differs") {
    def d = fresh()
    d.device.current.switch = "off"
    d.settings.prefPower = true
    d.call("updated")
    assert d.log.at("info").contains("Preferences saved")
    def control = d.httpMatching("/api/control")
    assert control.size() == 1 && bodyOf(control[0]) == [action: "powerOn"]
}

test("the Notifier records its driverVersion") {
    def n = HubitatHarness.load(NOTIFIER)
    n.call("updated")
    assert n.device.data.driverVersion == "0.11.0"
}

// ---- Logging ----

test("descriptionText is logged on change only, and not when txtEnable is false") {
    def d = fresh()
    d.call("statsCallback", new harness.FakeResponse(data: groovy.json.JsonOutput.toJson(statsOn())), [path: "/api/stats"])
    assert d.log.at("info").contains("Living Room TV volume is 12%")
    int lines = d.log.at("info").size()
    d.call("statsCallback", new harness.FakeResponse(data: groovy.json.JsonOutput.toJson(statsOn())), [path: "/api/stats"])
    assert d.log.at("info").size() == lines
    d.settings.txtEnable = false
    d.call("statsCallback", new harness.FakeResponse(data: groovy.json.JsonOutput.toJson(statsOn(volume: 20))), [path: "/api/stats"])
    assert d.log.at("info").size() == lines
    assert d.device.current.volume == 20
}

test("an ARC slider move logs one info line, not two per step") {
    def d = fresh()
    d.state.audioOutputRaw = "tv_external_speaker_arc"
    d.device.current.volume = 10
    d.call("setVolume", 13)
    3.times {
        def req = d.httpMatching("/api/control")[0]
        assert bodyOf(req) == [action: "volumeStep", value: 1]
        d.reply(req, json: [ok: true])
    }
    assert d.httpMatching("/api/control").isEmpty()
    assert d.log.at("info").findAll { it.contains("volumeStep") }.size() == 1
    assert !d.log.at("info").any { it.startsWith("POST /api/control") || it.contains("accepted (ok:true)") }
}

finish()
