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
def resp = { Map m -> new harness.FakeResponse(data: groovy.json.JsonOutput.toJson(m)) }

final List<String> ACCEPTED = ["up", "down", "left", "right", "ok", "back", "home", "settings",
        "media:playPause", "media:play", "media:pause", "media:stop", "media:rewind", "media:fastForward",
        "media:channelUp", "media:channelDown"]

// ---- Metadata ----

test("definition follows STYLE.md and runs single-threaded") {
    def d = fresh()
    assert d.definition.name == "LG TV (Glasshouse)"
    assert d.definition.namespace == "dlaporte"
    assert d.definition.author == "David LaPorte"
    assert d.definition.importUrl == "https://raw.githubusercontent.com/dlaporte/Hubitat/main/LGglasshouse/device/LGTV-Glasshouse.groovy"
    assert d.definition.singleThreaded == true
}

test("capabilities make the TV a standard controllable device") {
    assert fresh().capabilities == ["Switch", "AudioVolume", "MediaInputSource", "IlluminanceMeasurement", "Battery",
                                    "Refresh", "Initialize", "Actuator"]
}

test("custom commands and their parameters") {
    def d = fresh()
    assert d.commands == ["setPictureMode", "screenOn", "screenOff", "launchApp", "sendKey", "setBacklight",
                          "setEnergySaving", "setSoundOutput", "setSleepTimer", "setLgSetting", "reboot"]
    def params = { String c -> d.commandDefs[c].collect { [it.name, it.type] } }
    assert params("setPictureMode") == [["mode*", "STRING"]]
    assert params("launchApp") == [["appId*", "STRING"]]
    assert params("setBacklight") == [["level*", "NUMBER"]]
    assert params("setLgSetting") == [["id*", "STRING"], ["value*", "STRING"]]
    assert params("sendKey") == [["key*", "ENUM"]]
    assert d.commandDefs.sendKey[0].constraints == ACCEPTED
    assert d.commandDefs.setEnergySaving[0].constraints == ["auto", "off", "min", "med", "max", "screen_off"]
    assert d.commandDefs.setSleepTimer[0].constraints == ["off", "10", "30", "60", "90", "120"]
    assert d.commandDefs.setSoundOutput[0].constraints == ["TV Speaker", "HDMI ARC", "Optical", "Bluetooth",
                                                         "Headphone / AUX", "TV Speaker + HDMI ARC"]
    assert d.commandDefs.screenOn == [] && d.commandDefs.screenOff == [] && d.commandDefs.reboot == []
}

test("attributes the capabilities declare aren't declared again; the new stats attributes are") {
    assert fresh().attributes*.name == ["input", "currentApp", "pictureMode", "supportedPictureModes", "screen",
                                        "healthStatus", "dynamicRange", "backlight", "energySaving", "soundOutput",
                                        "soundMode", "sleepTimer", "powerStateLabel", "tvTemperature"]
}

test("preferences: connection and Hubitat settings only, no TV controls") {
    def d = fresh()
    assert d.inputs.findAll { it.type != "paragraph" }*.name == ["tvIp", "tvPort", "apiToken", "tvMac", "pollInterval",
                                                                 "warnUnreachable", "notifierEnabled", "logEnable", "txtEnable"]
    assert d.inputs.findAll { it.type == "paragraph" }*.name == ["hdrConnection", "hdrHubitat"]
}

// ---- Lifecycle ----

test("initialize removes retired settings, records driverVersion 0.12.0 and polls every minute") {
    def d = fresh()
    d.settings.putAll(debugLogging: true, prefPower: true, prefVolume: 30, prefInput: "hdmi2")
    d.call("initialize")
    assert d.device.removedSettings.containsAll(["debugLogging", "prefPower", "prefLaunchApp", "prefSendKey",
            "prefVolume", "prefMute", "prefInput", "prefPictureMode", "prefScreen"])
    assert !d.settings.keySet().any { it.startsWith("pref") }
    assert d.device.data.driverVersion == "0.12.0"
    assert d.schedules.refresh == "runEvery1Minute"
}

test("logsOff is scheduled after unschedule, so Initialize can't cancel it") {
    def d = fresh()
    d.settings.logEnable = true
    d.call("initialize")
    assert d.unschedules == ["*"]
    assert d.schedules.logsOff == 1800
}

test("updated logs 'Preferences saved' and sends nothing to the TV, even with 0.11 settings left") {
    def d = fresh()
    d.device.current.putAll(switch: "off", mute: "unmuted", volume: 10, input: "hdmi1", screen: "on")
    d.settings.putAll(prefPower: true, prefMute: true, prefVolume: 30, prefInput: "hdmi2", prefScreen: false,
                      prefPictureMode: "cinema", prefLaunchApp: "netflix", prefSendKey: "home")
    d.call("updated")
    assert d.log.at("info").contains("Preferences saved")
    assert d.http.findAll { it.method == "POST" }.isEmpty()
    assert d.hubCommands.isEmpty()
}

test("a poll no longer writes any preference") {
    def d = fresh()
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    assert d.device.settingUpdates.isEmpty()
}

test("the preference option providers and the volume slider are gone") {
    def d = fresh()
    ["getInputPrefOptions", "getPictureModePrefOptions", "getLaunchAppPrefOptions", "getSendKeyPrefOptions",
     "volumeSliderHtml"].each { m ->
        try { d.call(m); assert false: "${m} still exists" } catch (MissingMethodException ignored) { }
    }
}

test("the Notifier records its driverVersion") {
    def n = HubitatHarness.load(NOTIFIER)
    n.call("updated")
    assert n.device.data.driverVersion == "0.12.0"
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

// ---- Fixes: send key ----

test("refresh no longer fetches /api/remotekeys") {
    def d = fresh()
    d.call("refresh")
    assert d.http*.params*.uri.collect { it.toString().replaceAll(/.*:8080/, "") } == ["/api/stats", "/api/picture", "/api/hdmi"]
}

test("sendKey sends accepted keys and refuses the ones Glasshouse rejects") {
    def d = fresh()
    d.call("sendKey", "media:channelUp")
    d.call("sendKey", "menu")
    assert d.http.collect { bodyOf(it) } == [[action: "media", value: "channelUp"], [action: "rcu", value: "settings"]]
    d.reset()
    ["lg:INPUT", "volumeUp", "media:volumeDown", "mute", "bogus"].each { d.call("sendKey", it) }
    assert d.http.isEmpty()
    assert d.log.at("warn").size() == 5
}

test("a late /api/remotekeys reply (in flight across the update) is ignored") {
    def d = fresh()
    d.call("remoteKeysCallback", new harness.FakeResponse(data: '{"ok":true,"keys":[{"key":"INPUT"}]}'), [path: "/api/remotekeys"])
    assert d.state.remoteKeys == null
    assert d.log.at("warn").isEmpty() && d.log.at("error").isEmpty()
}

// ---- Fixes: API token ----

test("an API token is sent as a Bearer header and never logged") {
    def d = fresh()
    d.settings.apiToken = "s3cret-token"
    d.settings.logEnable = true
    d.call("refresh")
    d.call("sendKey", "home")
    assert d.http.every { it.params.headers.Authorization == "Bearer s3cret-token" }
    assert !d.log.lines.any { it[1].contains("s3cret") }
}

test("no Authorization header without a token") {
    def d = fresh()
    d.settings.apiToken = "  "
    d.call("refresh")
    assert d.http.every { !it.params.headers.containsKey("Authorization") }
}

test("the API token input sits in the Connection group as a password") {
    def d = fresh()
    def names = d.inputs*.name
    assert names.indexOf("apiToken") == names.indexOf("tvPort") + 1
    assert d.inputs.find { it.name == "apiToken" }.type == "password"
}

// ---- Fixes: dead code ----

test("toggleMute and toggleScreen stay gone") {
    def d = fresh()
    ["toggleMute", "toggleScreen"].each { m ->
        try { d.call(m); assert false: "${m} still exists" } catch (MissingMethodException ignored) { }
    }
}

// ---- Standard commands ----

test("volumeUp and volumeDown send one step each") {
    def d = fresh()
    d.call("volumeUp")
    d.call("volumeDown")
    assert d.http.collect { bodyOf(it) } == [[action: "volumeStep", value: 1], [action: "volumeStep", value: -1]]
}

test("Switch and AudioVolume commands send their Glasshouse actions") {
    def d = fresh()
    d.settings.tvMac = "AA:BB:CC:DD:EE:FF"
    d.call("on"); d.call("off"); d.call("mute"); d.call("unmute"); d.call("setVolume", "25")
    assert d.http.collect { bodyOf(it) } == [[action: "powerOn"], [action: "powerOff"], [action: "mute", value: true],
                                            [action: "mute", value: false], [action: "volume", value: 25]]
    assert d.hubCommands.size() == 1      // Wake-on-LAN with on
}

test("picture mode, screen and app commands send their Glasshouse actions") {
    def d = fresh()
    d.state.pictureModes = [[value: "cinema", label: "Cinema"]]
    d.state.apps = [[id: "netflix", title: "Netflix"]]
    d.call("setPictureMode", "Cinema"); d.call("screenOff"); d.call("screenOn"); d.call("launchApp", "netflix")
    assert d.http.collect { bodyOf(it) } == [[action: "pictureMode", value: "cinema"], [action: "screenOff"],
                                            [action: "screenOn"], [action: "launchApp", value: "netflix"]]
}

test("setInputSource accepts an input's id or label, ignoring case") {
    def d = fresh()
    d.call("hdmiCallback", resp([ok: true, inputs: [[port: 1, label: "Apple TV"], [port: 2, label: "PS5"]]]), [path: "/api/hdmi"])
    d.call("setInputSource", "HDMI2")
    d.call("setInputSource", "apple tv")
    assert d.http.collect { bodyOf(it) } == [[action: "input", value: "hdmi2"], [action: "input", value: "hdmi1"]]
}

test("setInputSource with an unknown name warns once and sends nothing") {
    def d = fresh()
    d.call("setInputSource", "Xbox")
    assert d.http.isEmpty()
    assert d.log.at("warn").size() == 1
}

test("mediaInputSource carries the same value as input") {
    def d = fresh()
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    assert d.device.current.input == "hdmi1" && d.device.current.mediaInputSource == "hdmi1"
    d.call("setInputSource", "hdmi2")
    d.reply(d.http[0], json: [ok: true])
    assert d.device.current.input == "hdmi2" && d.device.current.mediaInputSource == "hdmi2"
}

test("the ARC chain no longer writes volumeStepTarget") {
    def d = fresh()
    d.state.audioOutputRaw = "arc"
    d.device.current.volume = 10
    d.call("setVolume", 12)
    assert !d.state.containsKey("volumeStepTarget")
}

// ---- Fixes: healthStatus ----

test("an unreachable TV goes offline once; it comes back online with the outage length") {
    def d = fresh()
    d.call("statsCallback", new harness.FakeResponse(status: 408, errorMessage: "connect timed out"), [path: "/api/stats"])
    assert d.device.current.healthStatus == "offline"
    assert d.log.at("info").count { it.contains("is off or unreachable") } == 1
    d.advance(60_000L)
    d.call("statsCallback", new harness.FakeResponse(status: 408, errorMessage: "connect timed out"), [path: "/api/stats"])
    assert d.log.at("info").count { it.contains("is off or unreachable") } == 1
    d.advance(2 * 3_600_000L)
    d.call("statsCallback", new harness.FakeResponse(data: groovy.json.JsonOutput.toJson(statsOn())), [path: "/api/stats"])
    assert d.device.current.healthStatus == "online"
    assert d.log.at("info").any { it.contains("reachable again after 2h 1m (2 missed polls)") }
}

test("healthStatus is declared") {
    assert fresh().attributes*.name.contains("healthStatus")
}

// ---- Power ----

def unreachable = { new harness.FakeResponse(status: 408, errorMessage: "connect timed out") }

test("on starts power-up polling: a refresh every 15 s until a stats reply shows the TV on") {
    def d = fresh()
    d.call("on")
    assert d.schedules.powerUpPoll == 15
    d.reset()
    d.advance(15_000L)
    d.call("powerUpPoll")
    assert d.http*.params*.uri.collect { it.toString().replaceAll(/.*:8080/, "") } == ["/api/stats", "/api/picture", "/api/hdmi"]
    assert d.schedules.powerUpPoll == 15
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    assert d.device.current.switch == "on"
    assert !d.schedules.containsKey("powerUpPoll")
    assert d.state.powerUpUntil == null
}

test("power-up polling gives up after 2 minutes") {
    def d = fresh()
    d.call("on")
    int refreshes = 0
    for (int i = 0; i < 12 && d.schedules.containsKey("powerUpPoll"); i++) {
        d.schedules.remove("powerUpPoll")
        d.advance(15_000L)
        d.http.clear()
        d.call("powerUpPoll")
        if (d.httpMatching("/api/stats")) refreshes++
    }
    assert refreshes == 7          // at 15, 30 ... 105 s; at 120 s it stops
    assert !d.schedules.containsKey("powerUpPoll")
    assert d.state.powerUpUntil == null
}

test("reboot sends reboot and polls; a stats reply before the TV goes down doesn't stop it") {
    def d = fresh()
    d.call("reboot")
    assert bodyOf(d.httpMatching("/api/control")[0]) == [action: "reboot"]
    assert d.schedules.powerUpPoll == 15
    d.advance(2_000L)
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    assert d.schedules.powerUpPoll == 15
    d.advance(60_000L)
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    assert !d.schedules.containsKey("powerUpPoll")
}

test("a command reply refreshes through its own one-shot handler and leaves the poll schedule alone") {
    def d = fresh()
    d.call("initialize")
    d.reset()
    d.call("mute")
    d.reply(d.http.find { it.method == "POST" }, json: [ok: true])
    assert d.schedules.refreshAfterCommand == 2
    assert d.schedules.refresh == "runEvery1Minute"
    d.http.clear()
    d.call("refreshAfterCommand")
    assert d.httpMatching("/api/stats").size() == 1
}

test("initialize clears power-up polling") {
    def d = fresh()
    d.call("on")
    d.call("initialize")
    assert !d.schedules.containsKey("powerUpPoll")
    assert d.state.powerUpUntil == null
}

test("an unreachable TV turns switch and screen off along with healthStatus") {
    def d = fresh()
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    assert d.device.current.switch == "on" && d.device.current.screen == "on"
    d.call("statsCallback", unreachable(), [path: "/api/stats"])
    assert d.device.current.switch == "off" && d.device.current.screen == "off"
    assert d.device.current.healthStatus == "offline"
}

test("a picture or hdmi miss alone doesn't turn the switch off") {
    def d = fresh()
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    d.call("pictureCallback", unreachable(), [path: "/api/picture"])
    d.call("hdmiCallback", unreachable(), [path: "/api/hdmi"])
    assert d.device.current.switch == "on"
}

test("a command to an unreachable TV logs info, or a warning with warnUnreachable on") {
    def d = fresh()
    d.call("mute")
    d.reply(d.http[0], status: 408, error: "connect timed out")
    assert d.log.at("warn").isEmpty()
    assert d.log.at("info").any { it.contains("mute not sent") && it.contains("off or unreachable") }
    d.settings.warnUnreachable = true
    d.call("mute")
    d.reply(d.http[0], status: 408, error: "connect timed out")
    assert d.log.at("warn").size() == 1
}

test("on for a TV that is fully off logs that it is waiting for Wake-on-LAN, not a warning") {
    def d = fresh()
    d.settings.tvMac = "AA:BB:CC:DD:EE:FF"
    d.call("on")
    d.reply(d.httpMatching("/api/control")[0], status: 408, error: "connect timed out")
    assert d.log.at("warn").isEmpty()
    assert d.log.at("info").any { it.contains("Wake-on-LAN") }
    assert d.schedules.powerUpPoll == 15
}

finish()
