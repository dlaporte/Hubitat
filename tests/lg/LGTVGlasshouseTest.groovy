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

// A stats reply with every reading 0.12.0 uses.
def statsFull = { Map extra = [:] ->
    statsOn([powerState: [systemOn: true, raw: "Active", label: "On", screenOn: true],
             picture: [dynamicRange: "Dolby Vision", backlight_raw: 80, energySaving: "off"],
             sound: [output: "HDMI ARC", output_raw: "external_arc", mode: "standard",
                     outputs: [[id: "tv_speaker", name: "TV Speaker"], [id: "external_arc", name: "HDMI ARC"]]],
             sleepTimer: "off", lightSensor: [lux: 41.6], remote: [battery: 76], temp: 52] + extra)
}

// A stats reply for a TV in standby (reachable, switched off).
def statsStandby = { -> statsOn(powerState: [systemOn: false, raw: "Active Standby", screenOn: false]) }

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
            "Headphone / AUX", "TV Speaker + HDMI ARC", "TV Speaker + Optical", "TV Speaker + Bluetooth",
            "TV Speaker + Headphone", "LG Sound Sync", "LG WOWCAST", "Line Out", "External Speaker",
            "WiSA Speakers", "Mobile Phone"]
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
    assert d.http*.params*.uri.collect { it.toString().replaceAll(/.*:8080/, "") } == ["/api/stats", "/api/hdmi"]
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
    d.reply(d.httpMatching("/api/control")[0], json: [ok: true])
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

test("on starts the power watch: a refresh every 15 s until a stats reply shows the TV on") {
    def d = fresh()
    d.call("on")
    assert d.schedules.powerPoll == 15
    d.reset()
    d.advance(15_000L)
    d.call("powerPoll")
    assert d.http*.params*.uri.collect { it.toString().replaceAll(/.*:8080/, "") } == ["/api/stats", "/api/hdmi"]
    assert d.schedules.powerPoll == 15
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    assert d.device.current.switch == "on"
    assert !d.schedules.containsKey("powerPoll")
    assert d.state.powerTarget == null
}

test("the power watch gives up after 2 minutes") {
    def d = fresh()
    d.settings.tvMac = "AA:BB:CC:DD:EE:FF"
    d.call("on")
    int refreshes = 0
    for (int i = 0; i < 12 && d.schedules.containsKey("powerPoll"); i++) {
        d.schedules.remove("powerPoll")
        d.advance(15_000L)
        d.http.clear()
        d.call("powerPoll")
        if (d.httpMatching("/api/stats")) refreshes++
    }
    assert refreshes == 7          // at 15, 30 ... 105 s; at 120 s it stops
    assert !d.schedules.containsKey("powerPoll")
    assert d.state.powerTarget == null
    assert d.log.at("warn").size() == 1
    assert d.log.at("warn")[0].contains("didn't turn on within 2 minutes")
}

// Runs powerPoll every 15 s until the watch stops.
def pollOut = { d ->
    for (int i = 0; i < 12 && d.schedules.containsKey("powerPoll"); i++) {
        d.schedules.remove("powerPoll")
        d.advance(15_000L)
        d.call("powerPoll")
    }
}

test("Glasshouse's 'stayed in standby' during On is info while the watch waits; the TV coming on ends it quietly") {
    def d = fresh()
    d.settings.tvMac = "AA:BB:CC:DD:EE:FF"
    d.call("statsCallback", resp(statsStandby()), [path: "/api/stats"])
    d.call("on")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: false, error: "the TV stayed in standby"])
    assert d.log.at("warn").isEmpty()
    assert d.log.at("info").any { it.contains("the TV stayed in standby") }
    d.advance(15_000L)
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    assert d.device.current.switch == "on" && d.state.powerTarget == null
    assert d.log.at("warn").isEmpty()
}

test("if the TV never comes on, the 2-minute warning carries Glasshouse's reason") {
    def d = fresh()
    d.settings.tvMac = "AA:BB:CC:DD:EE:FF"
    d.call("statsCallback", resp(statsStandby()), [path: "/api/stats"])
    d.call("on")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: false, error: "the TV stayed in standby"])
    pollOut(d)
    assert d.log.at("warn").size() == 1
    assert d.log.at("warn")[0].contains("didn't turn on within 2 minutes") && d.log.at("warn")[0].contains("stayed in standby")
    assert d.state.powerError == null
}

test("any other refusal of On still warns at once") {
    def d = fresh()
    d.settings.tvMac = "AA:BB:CC:DD:EE:FF"
    d.call("on")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: false, error: "power actions disabled (set allowPower)"])
    assert d.log.at("warn").size() == 1 && d.log.at("warn")[0].contains("allowPower")
}

test("a TV still on 2 minutes after Off warns once, whether a poll or a reply sees it first") {
    def d = fresh()
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    d.call("off")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: true])
    d.advance(120_000L)
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    assert d.state.powerTarget == null && d.device.current.switch == "on"
    assert d.log.at("warn").size() == 1 && d.log.at("warn")[0].contains("didn't turn off within 2 minutes")
}

test("reboot sends reboot and polls; a stats reply before the TV goes down doesn't stop it") {
    def d = fresh()
    d.call("reboot")
    assert bodyOf(d.httpMatching("/api/control")[0]) == [action: "reboot"]
    assert d.schedules.powerPoll == 15
    d.advance(2_000L)
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    assert d.schedules.powerPoll == 15
    d.advance(60_000L)
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    assert !d.schedules.containsKey("powerPoll")
}

// The values an attribute actually changed through (repeats collapsed), as rules see them.
def changes = { List values -> values.inject([]) { List seen, v -> (seen && seen[-1] == v) ? seen : seen + [v] } }


test("off: a stale 'on' reply right after doesn't bounce switch; an 'off' reply ends the watch") {
    def d = fresh()
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    d.call("off")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: true])
    assert d.device.current.switch == "off" && d.device.current.screen == "off"
    d.advance(2_000L)
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    assert d.device.current.switch == "off" && d.device.current.screen == "off"
    assert changes(d.eventsNamed("switch")*.value) == ["on", "off"]
    assert d.schedules.powerPoll == 15
    d.advance(13_000L)
    d.call("statsCallback", resp(statsStandby()), [path: "/api/stats"])
    assert d.state.powerTarget == null && !d.schedules.containsKey("powerPoll")
    assert changes(d.eventsNamed("switch")*.value) == ["on", "off"]
}

test("on from standby: a standby reply right after doesn't bounce switch") {
    def d = fresh()
    d.call("statsCallback", resp(statsStandby()), [path: "/api/stats"])
    assert d.device.current.switch == "off"
    d.call("on")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: true])
    d.advance(2_000L)
    d.call("statsCallback", resp(statsStandby()), [path: "/api/stats"])
    assert d.device.current.switch == "on"
    d.advance(13_000L)
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    assert d.state.powerTarget == null
    assert changes(d.eventsNamed("switch")*.value) == ["off", "on"]
}

test("a power command the TV doesn't follow gives way to the TV's report after 2 minutes") {
    def d = fresh()
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    d.call("off")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: true])
    d.advance(120_000L)
    d.call("powerPoll")
    assert d.state.powerTarget == null
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    assert d.device.current.switch == "on"
}

test("off to a TV that then drops off the network ends the watch") {
    def d = fresh()
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    d.call("off")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: true])
    d.call("statsCallback", unreachable(), [path: "/api/stats"])
    assert d.state.powerTarget == null && !d.schedules.containsKey("powerPoll")
    assert d.device.current.switch == "off"
}

test("a command reply refreshes through its own one-shot handler and leaves the poll schedule alone") {
    def d = fresh()
    d.call("initialize")
    d.reset()
    d.call("mute")
    d.reply(d.http.find { it.method == "POST" }, json: [ok: true])
    assert d.schedules.refreshAfterCommand == 2
    assert d.schedules.confirmAfterCommand == 45
    assert d.schedules.refresh == "runEvery1Minute"
    d.http.clear()
    d.call("refreshAfterCommand")
    assert d.httpMatching("/api/stats").size() == 1
    d.http.clear()
    d.call("confirmAfterCommand")
    assert d.httpMatching("/api/stats").size() == 1
}

// ---- The command hold ----

def pictureReply = { String raw ->
    [ok: true, rows: [[id: "pictureMode", value: raw,
                       choices: [[value: "cinema", label: "Cinema"], [value: "vivid", label: "Vivid"]]]]]
}

test("after an accepted mute, a read that still says unmuted doesn't flip mute back") {
    def d = fresh()
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    d.call("mute")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: true])
    d.advance(4_000L)
    d.call("statsCallback", resp(statsOn(muted: false)), [path: "/api/stats"])
    assert d.device.current.mute == "muted"
    assert changes(d.eventsNamed("mute")*.value) == ["unmuted", "muted"]
}

test("the hold ends after 15 s: a read that disagrees then is the TV's answer") {
    def d = fresh()
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    d.call("mute")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: true])
    d.advance(15_000L)
    d.call("statsCallback", resp(statsOn(muted: false)), [path: "/api/stats"])
    assert d.device.current.mute == "unmuted"
}

test("a read that agrees ends the hold, so a later change from the remote shows at once") {
    def d = fresh()
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    d.call("mute")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: true])
    d.advance(2_000L)
    d.call("statsCallback", resp(statsOn(muted: true)), [path: "/api/stats"])
    d.advance(2_000L)
    d.call("statsCallback", resp(statsOn(muted: false)), [path: "/api/stats"])
    assert d.device.current.mute == "unmuted"
}

test("input and mediaInputSource are held together") {
    def d = fresh()
    d.call("statsCallback", resp(statsOn(inputs: [hdmi1: "Apple TV", hdmi2: "PS5"])), [path: "/api/stats"])
    d.call("setInputSource", "hdmi2")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: true])
    d.advance(2_000L)
    d.call("statsCallback", resp(statsOn(inputs: [hdmi1: "Apple TV", hdmi2: "PS5"])), [path: "/api/stats"])
    assert d.device.current.input == "hdmi2" && d.device.current.mediaInputSource == "hdmi2"
}

test("picture mode is held against both the picture and the stats read") {
    def d = fresh()
    d.call("pictureCallback", resp(pictureReply("cinema")), [path: "/api/picture"])
    assert d.device.current.pictureMode == "Cinema"
    d.call("setPictureMode", "Vivid")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: true])
    assert d.device.current.pictureMode == "Vivid"
    d.advance(2_000L)
    d.call("pictureCallback", resp(pictureReply("cinema")), [path: "/api/picture"])
    assert d.device.current.pictureMode == "Vivid"

    def s = fresh()
    s.call("statsCallback", resp(statsOn(picture: [mode: "Cinema", mode_raw: "cinema",
            modes: [[value: "cinema", label: "Cinema"], [value: "vivid", label: "Vivid"]]])), [path: "/api/stats"])
    s.call("setPictureMode", "Vivid")
    s.reply(s.httpMatching("/api/control")[0], json: [ok: true])
    s.advance(2_000L)
    s.call("statsCallback", resp(statsOn(picture: [mode: "Cinema", mode_raw: "cinema"])), [path: "/api/stats"])
    assert s.device.current.pictureMode == "Vivid"
}

test("screen is held after screenOff") {
    def d = fresh()
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    d.call("screenOff")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: true])
    d.advance(2_000L)
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    assert d.device.current.screen == "off"
    assert changes(d.eventsNamed("screen")*.value) == ["on", "off"]
}

test("a power command drops a screen hold; the power watch decides screen") {
    def d = fresh()
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    d.call("screenOff")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: true])
    d.http.clear()
    d.call("on")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: true])
    d.advance(2_000L)
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    assert d.device.current.screen == "on"
}

test("a refused or unreachable command holds nothing") {
    def d = fresh()
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    d.call("mute")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: false, error: "nope"])
    d.call("screenOff")
    d.call("controlCallback", unreachable(), [action: "screenOff"])
    assert !d.state.holds
}

test("launchApp's app id isn't held: the TV's title replaces it on the next read") {
    def d = fresh()
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    d.call("launchApp", "netflix")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: true])
    d.advance(2_000L)
    d.call("statsCallback", resp(statsOn(app: "netflix", app_id: "netflix", display_title: "Netflix")), [path: "/api/stats"])
    assert d.device.current.currentApp == "Netflix"
}

test("initialize and an unreachable TV clear the holds") {
    def d = fresh()
    d.call("mute")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: true])
    assert d.state.holds?.mute
    d.call("initialize")
    assert !d.state.holds

    def u = fresh()
    u.call("mute")
    u.reply(u.httpMatching("/api/control")[0], json: [ok: true])
    u.call("statsCallback", unreachable(), [path: "/api/stats"])
    assert !u.state.holds
}

test("a volume command drops the mute hold: a volume step unmutes the TV") {
    def d = fresh()
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    d.call("mute")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: true])
    d.http.clear()
    d.call("volumeUp")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: true])
    d.advance(2_000L)
    d.call("statsCallback", resp(statsOn(muted: false, volume: 13)), [path: "/api/stats"])
    assert d.device.current.mute == "unmuted"
}

test("launchApp drops the input and picture mode holds") {
    def d = fresh()
    d.call("statsCallback", resp(statsOn(inputs: [hdmi1: "Apple TV", hdmi2: "PS5"])), [path: "/api/stats"])
    d.call("pictureCallback", resp(pictureReply("cinema")), [path: "/api/picture"])
    d.call("setPictureMode", "Vivid")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: true])
    d.http.clear()
    d.call("setInputSource", "hdmi2")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: true])
    assert !d.state.holds.containsKey("pictureMode")
    d.http.clear()
    d.call("launchApp", "netflix")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: true])
    assert !d.state.holds
    d.advance(2_000L)
    d.call("statsCallback", resp(statsOn(app: "netflix", app_id: "netflix", display_title: "Netflix",
            inputs: [hdmi1: "Apple TV", hdmi2: "PS5"])), [path: "/api/stats"])
    assert d.device.current.input == "netflix"
}

test("a power command drops every hold") {
    def d = fresh()
    d.call("mute")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: true])
    d.call("off")
    assert !d.state.holds
}

// ---- Standby ----

def uris = { d -> d.http*.params*.uri.collect { it.toString().replaceAll(/.*:8080/, "") } }

test("picture settings are asked for only once a stats reply shows the TV on") {
    def d = fresh()
    d.call("refresh")
    assert uris(d) == ["/api/stats", "/api/hdmi"]
    d.http.clear()
    d.call("statsCallback", resp(statsOn()), [path: "/api/stats"])
    assert uris(d) == ["/api/picture"]
    d.http.clear()
    d.call("statsCallback", resp(statsStandby()), [path: "/api/stats"])
    assert uris(d).isEmpty()
    d.call("statsCallback", unreachable(), [path: "/api/stats"])
    assert uris(d).isEmpty()
}

test("in standby the picture readings keep their values; the rest still update") {
    def d = fresh()
    d.call("statsCallback", resp(statsFull()), [path: "/api/stats"])
    d.call("statsCallback", resp(statsFull(
            powerState: [systemOn: false, raw: "Active Standby", label: "Standby", screenOn: false],
            picture: [dynamicRange: "SDR", backlight_raw: 55, energySaving: "auto", mode: "Eco", mode_raw: "eco",
                      modes: [[value: "eco", label: "Eco"]]],
            sleepTimer: "30", temp: 40)), [path: "/api/stats"])
    def c = d.device.current
    assert c.dynamicRange == "Dolby Vision" && c.backlight == 80 && c.energySaving == "off"
    assert c.pictureMode == null && c.supportedPictureModes == null
    assert c.switch == "off" && c.powerStateLabel == "Standby" && c.sleepTimer == "30" && c.tvTemperature == 40
}

test("a sleep timer the 2 s read misses shows after the later refresh") {
    def d = fresh()
    d.call("statsCallback", resp(statsFull()), [path: "/api/stats"])
    d.call("setSleepTimer", "10")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: true])
    assert d.schedules.refreshAfterCommand == 2 && d.schedules.confirmAfterCommand == 45
    d.advance(2_000L)
    d.call("statsCallback", resp(statsFull(sleepTimer: "off")), [path: "/api/stats"])
    assert d.device.current.sleepTimer == "off"
    d.advance(43_000L)
    d.http.clear()
    d.call("confirmAfterCommand")
    assert d.httpMatching("/api/stats").size() == 1
    d.call("statsCallback", resp(statsFull(sleepTimer: "10")), [path: "/api/stats"])
    assert d.device.current.sleepTimer == "10"
}

test("initialize clears the power watch") {
    def d = fresh()
    d.call("on")
    d.call("initialize")
    assert !d.schedules.containsKey("powerPoll")
    assert d.state.powerTarget == null
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
    assert d.schedules.powerPoll == 15
}

// ---- Readings ----

test("a stats reply fills in the new attributes") {
    def d = fresh()
    d.call("statsCallback", resp(statsFull()), [path: "/api/stats"])
    def c = d.device.current
    assert c.dynamicRange == "Dolby Vision" && c.backlight == 80 && c.energySaving == "off"
    assert c.soundOutput == "HDMI ARC" && c.soundMode == "standard" && c.sleepTimer == "off"
    assert c.powerStateLabel == "On" && c.illuminance == 42 && c.battery == 76 && c.tvTemperature == 52
    assert d.state.soundOutputs == [[id: "tv_speaker", name: "TV Speaker"], [id: "external_arc", name: "HDMI ARC"]]
}

test("null lux, battery, backlight and temperature are skipped") {
    def d = fresh()
    d.call("statsCallback", resp(statsFull(lightSensor: null, remote: [battery: null], temp: null,
            picture: [dynamicRange: "SDR", backlight_raw: null, energySaving: "auto"])), [path: "/api/stats"])
    ["illuminance", "battery", "backlight", "tvTemperature"].each { assert !d.device.current.containsKey(it) }
    assert d.device.current.energySaving == "auto"
}

test("a repeat poll logs no new descriptionText") {
    def d = fresh()
    d.call("statsCallback", resp(statsFull()), [path: "/api/stats"])
    int n = d.log.at("info").size()
    d.call("statsCallback", resp(statsFull()), [path: "/api/stats"])
    assert d.log.at("info").size() == n
}

test("descriptionText for the readings; temperature changes aren't logged") {
    def d = fresh()
    d.call("statsCallback", resp(statsFull()), [path: "/api/stats"])
    def info = d.log.at("info")
    assert info.contains("Living Room TV illuminance is 42 lx")
    assert info.contains("Living Room TV remote battery is 76%")
    assert info.contains("Living Room TV sound output is HDMI ARC")
    assert !info.any { it.contains("temperature") }
}

// ---- Picture, sound and setting commands ----

test("setBacklight, setEnergySaving and setSleepTimer send their actions") {
    def d = fresh()
    d.call("setBacklight", "40.0")
    d.call("setEnergySaving", "MED")
    d.call("setSleepTimer", "30")
    d.call("setSleepTimer", "off")
    assert d.http.collect { bodyOf(it) } == [[action: "backlight", value: 40], [action: "energySaving", value: "med"],
                                            [action: "sleepTimer", value: "30"], [action: "sleepTimer", value: "off"]]
}

test("bad values send nothing and log one warning each") {
    def d = fresh()
    d.call("setBacklight", 150)
    d.call("setBacklight", "bright")
    d.call("setEnergySaving", "eco")
    d.call("setSleepTimer", "15")
    d.call("sendKey", "bogus")
    d.call("setInputSource", "Xbox")
    d.call("setLgSetting", "filmMakerMode", " ")
    d.call("setLgSetting", "", "on")
    d.call("setSoundOutput", "Speakers")
    assert d.http.isEmpty()
    assert d.log.at("warn").size() == 9
}

test("setSoundOutput uses the TV's own id for a name, and the fixed table for a name the TV hasn't listed") {
    def d = fresh()
    d.call("setSoundOutput", "Optical")
    d.state.soundOutputs = [[id: "tv_speaker", name: "TV Speaker"], [id: "external_optical", name: "Optical"]]
    d.call("setSoundOutput", "optical")
    d.call("setSoundOutput", "HDMI ARC")
    assert d.http.collect { bodyOf(it) } == [[action: "soundOutput", value: "optical"],
                                            [action: "soundOutput", value: "external_optical"],
                                            [action: "soundOutput", value: "external_arc"]]
}

test("setLgSetting: on/off as a switch, integers as numbers, anything else as a value") {
    def d = fresh()
    d.call("setLgSetting", "filmMakerMode", "on")
    d.call("setLgSetting", "contrast", "85")
    d.call("setLgSetting", "soundMode", "movie")
    assert d.http.collect { bodyOf(it) } == [[action: "lgSetting", value: [id: "filmMakerMode", on: true]],
                                            [action: "lgSetting", value: [id: "contrast", value: 85]],
                                            [action: "lgSetting", value: [id: "soundMode", value: "movie"]]]
}

test("a choice setting that refuses off as a switch gets it as a value") {
    def d = fresh()
    d.call("setLgSetting", "truMotion", "off")
    def first = d.http[0]
    assert bodyOf(first).value == [id: "truMotion", on: false]
    d.reply(first, json: [ok: false, error: "That value is not one this setting takes"])
    def second = d.http[0]
    assert bodyOf(second).value == [id: "truMotion", value: "off"]
    d.reply(second, json: [ok: true])
    assert d.log.at("warn").isEmpty()
    assert d.schedules.refreshAfterCommand == 2
}

test("a refusal for another reason isn't retried, and its error text is in the warning") {
    def d = fresh()
    d.call("setLgSetting", "filmMakerMode", "on")
    d.reply(d.http[0], json: [ok: false, error: "Not available in the current picture mode, or for what is on screen."])
    assert d.http.isEmpty()
    assert d.log.at("warn") == ["Glasshouse lgSetting rejected: Not available in the current picture mode, or for what is on screen."]
}

test("a reboot Glasshouse refuses warns with its reason") {
    def d = fresh()
    d.call("reboot")
    d.reply(d.httpMatching("/api/control")[0], json: [ok: false, error: "power actions disabled (set allowPower)"])
    assert d.log.at("warn") == ["Glasshouse reboot rejected: power actions disabled (set allowPower)"]
}

finish()
