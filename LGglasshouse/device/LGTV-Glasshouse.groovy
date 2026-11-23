/*
 *  LG TV (Glasshouse)
 *
 *  LAN driver for a rooted LG webOS TV running Glasshouse, through its local JSON API
 *  (typically port 8080). Every control is a command; state is reported as attributes.
 *  Documentation: https://github.com/dlaporte/Hubitat/blob/main/LGglasshouse/README.md
 *
 *  Changelog:
 *  v0.12.0 (2026-11-22) - TV controls are commands (Switch, AudioVolume, MediaInputSource and
 *         custom picture, sound and power commands), so rules, dashboards, HomeKit and voice
 *         can use them; the controls left Preferences. New picture, sound, light sensor, remote
 *         battery and temperature attributes. switch goes off when the TV can't be reached.
 *         A value a command sets is held briefly against a read-back that lags.
 *  v0.11.0 (2026-11-20) - Send key offers only keys Glasshouse accepts; optional API token;
 *         healthStatus; runs single-threaded; ARC volume steps log at debug; standard
 *         logging and lifecycle; unused toggle and volume step methods removed.
 *  v0.10 - 2026-11-15 - A TV that is switched off (unreachable) is logged
 *         as one info line when it goes and one when it is back, not
 *         repeated warnings; new Warn when the TV can't be reached option.
 *  v0.9 - 2026-11-14 - Notification device: an optional child that
 *         shows Hubitat notifications on the TV as an on-screen toast.
 *  v0.8 - 2026-11-11 - Commands tab trimmed to Initialize and Refresh;
 *         added a TV section (power, launch app, send key).
 *  v0.7 - 2026-11-11 - HDMI ARC volumeStep so a soundbar or receiver
 *         that ignores the numeric volume still follows the slider.
 *  v0.4 - 2026-11-11 - volume slider with live apply on release.
 *  v0.3 - 2026-11-11 - grouped preference sections (Connection, Sound,
 *         Picture, Hubitat).
 *  v0.2 - 2026-11-11 - input and picture-mode dropdowns populated from
 *         the TV.
 *  v0.1 - 2026-11-11 - initial release.
 *
 *  Copyright 2026 David LaPorte
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String VERSION = "0.12.0"

// The only keys Glasshouse's control API accepts (rcu and media actions).
@Field static final List<String> RCU_KEYS = ["up", "down", "left", "right", "ok", "back", "settings", "home"]

// Send key choices: the rcu keys and Glasshouse's media keys.
@Field static final List<String> SEND_KEYS = ["up", "down", "left", "right", "ok", "back", "home", "settings",
        "media:playPause", "media:play", "media:pause", "media:stop", "media:rewind", "media:fastForward",
        "media:channelUp", "media:channelDown"]

// Values Glasshouse's energySaving and sleepTimer actions accept.
@Field static final List<String> ENERGY_SAVING = ["auto", "off", "min", "med", "max", "screen_off"]
@Field static final List<String> SLEEP_TIMER = ["off", "10", "30", "60", "90", "120"]

// Glasshouse's sound output names (the soundOutput attribute) and their ids,
// from its names.js. The TV's own list (sound.outputs in /api/stats) wins;
// this is the fallback, and the choices the setSoundOutput command offers.
@Field static final Map<String, String> SOUND_OUTPUTS = [
    "TV Speaker": "tv_speaker",
    "HDMI ARC": "external_arc",
    "Optical": "optical",
    "Bluetooth": "bt_soundbar",
    "Headphone / AUX": "headphone",
    "TV Speaker + HDMI ARC": "tv_speaker_external_arc",
    "TV Speaker + Optical": "tv_external_speaker",
    "TV Speaker + Bluetooth": "tv_speaker_bluetooth",
    "TV Speaker + Headphone": "tv_speaker_headphone",
    "LG Sound Sync": "soundbar",
    "LG WOWCAST": "wow_cast",
    "Line Out": "lineout",
    "External Speaker": "external_speaker",
    "WiSA Speakers": "wisa_speaker",
    "Mobile Phone": "mobile_phone"
]

// How long a value a command just set is held against a read that disagrees
// (see holdValue).
@Field static final long COMMAND_HOLD_MS = 15_000L

// Holds an accepted command makes stale: a volume change unmutes the TV, and
// an input or app change can change the input and the picture mode. Power
// commands drop every hold (startPowerWatch).
@Field static final Map<String, List<String>> HOLDS_DROPPED_BY = [
    volume: ["mute"], volumeStep: ["mute"],
    input: ["pictureMode"], launchApp: ["input", "pictureMode"]
]

// Settings earlier versions used. The TV controls moved from Preferences to
// commands in 0.12.0.
@Field static final List<String> RETIRED_SETTINGS = ["debugLogging", "prefPower", "prefLaunchApp", "prefSendKey",
        "prefVolume", "prefMute", "prefInput", "prefPictureMode", "prefScreen"]

metadata {
    definition(
        name: "LG TV (Glasshouse)",
        namespace: "dlaporte",
        author: "David LaPorte",
        importUrl: "https://raw.githubusercontent.com/dlaporte/Hubitat/main/LGglasshouse/device/LGTV-Glasshouse.groovy",
        // Poll callbacks arrive together and share state (failure counters,
        // the ARC volume-step chain), so run them one at a time.
        singleThreaded: true
    ) {
        // Standard capabilities give Rule Machine, dashboards, HomeKit and voice
        // on/off, volume, mute and input. Custom commands cover the rest.
        capability "Switch"
        capability "AudioVolume"
        capability "MediaInputSource"
        capability "IlluminanceMeasurement"   // the TV's ambient light sensor
        capability "Battery"                  // the Magic Remote
        capability "Refresh"
        capability "Initialize"
        capability "Actuator"

        command "setPictureMode", [[name: "mode*", type: "STRING", description: "A mode from supportedPictureModes (value or label)"]]
        command "screenOn"
        command "screenOff"
        command "launchApp", [[name: "appId*", type: "STRING", description: "App id (for example netflix) or title"]]
        command "sendKey", [[name: "key*", type: "ENUM", constraints: SEND_KEYS]]
        command "setBacklight", [[name: "level*", type: "NUMBER", description: "0-100 (OLED Light on an OLED)"]]
        command "setEnergySaving", [[name: "mode*", type: "ENUM", constraints: ENERGY_SAVING]]
        command "setSoundOutput", [[name: "output*", type: "ENUM", constraints: SOUND_OUTPUTS.keySet() as List]]
        command "setSleepTimer", [[name: "minutes*", type: "ENUM", constraints: SLEEP_TIMER]]
        command "setLgSetting", [[name: "id*", type: "STRING", description: "Glasshouse setting id, for example filmMakerMode"],
                                 [name: "value*", type: "STRING", description: "on or off for a switch setting, otherwise its value"]]
        command "reboot"

        // switch, volume, mute, supportedInputs, mediaInputSource, illuminance
        // and battery come from the capabilities above.
        attribute "input", "string"
        attribute "currentApp", "string"
        attribute "pictureMode", "string"
        attribute "supportedPictureModes", "string"
        attribute "screen", "string"
        attribute "healthStatus", "enum", ["online", "offline"]
        attribute "dynamicRange", "string"
        attribute "backlight", "number"
        attribute "energySaving", "string"
        attribute "soundOutput", "string"
        attribute "soundMode", "string"
        attribute "sleepTimer", "string"
        attribute "powerStateLabel", "string"
        attribute "tvTemperature", "number"
    }

    preferences {
        // Group headers: Hubitat 2.5.3 puts every preference, including
        // paragraph "headers", in the same 3-column PrimeFlex cell
        // (div.p-2.col-12.md:col-6.xl:col-4), so a bare title looks like
        // any other tile. Titles and descriptions are assigned with Vue
        // innerHTML (not escaped) and there is no CSP style-src, so a
        // <style> block in the first header's description is honoured.
        // The rules are keyed to unique ids only this driver injects, so
        // they cannot restyle any other device. PrimeFlex .grid is
        // flexbox, not CSS Grid; full-width uses flex-basis, not grid-column.
        input name: "hdrConnection", type: "paragraph",
            title: groupHeaderTitle("lgtv-glasshouse-hdr-connection", "Connection"),
            description: groupHeaderCss() + "How the hub reaches the TV."
        input name: "tvIp", type: "text", title: "TV IP",
            description: "LAN address of the TV. The hub must be able to reach this address.",
            required: true
        input name: "tvPort", type: "text", title: "Port",
            description: "Glasshouse HTTP port (for example 8080). This is text so the hub does not insert a thousands separator.",
            required: true, defaultValue: "8080"
        input name: "apiToken", type: "password", title: "API token",
            description: "Only if a token is set in Glasshouse's config; it is sent as a Bearer token. Leave blank otherwise.",
            required: false
        input name: "tvMac", type: "text", title: "MAC for Wake-on-LAN",
            description: "Used to wake the TV from deep sleep. Prefills the address reported by the TV.",
            required: false
        input name: "pollInterval", type: "enum", title: "Poll interval",
            description: "How often to refresh TV state. Choose Off to poll only when a command runs.",
            options: [
                "0":  "Off",
                "1":  "Every 1 minute",
                "2":  "Every 2 minutes",
                "5":  "Every 5 minutes",
                "10": "Every 10 minutes",
                "15": "Every 15 minutes",
                "30": "Every 30 minutes"
            ],
            defaultValue: "1", required: true
        input name: "warnUnreachable", type: "bool", title: "Warn when the TV can't be reached",
            description: "Turn on only if Always Ready is on in Glasshouse, so the TV stays on the network when switched off with the remote. Otherwise a switched-off TV looks like a failure. The power button still switches the TV fully off. When off, an unreachable TV is logged once as info.",
            defaultValue: false

        // Last driver preference group. Hubitat then appends "Default
        // Current State" and "Show on Home page", so they land in this
        // section under the HUBITAT heading together with debug logging.
        input name: "hdrHubitat", type: "paragraph",
            title: groupHeaderTitle("lgtv-glasshouse-hdr-hubitat", "Hubitat"),
            description: "Hubitat options for this device."
        input name: "notifierEnabled", type: "bool", title: "Notification device",
            description: "Creates a child device that shows Hubitat notifications on the TV screen. Turning this off deletes that device, and any rules using it lose it.",
            defaultValue: false
        input name: "logEnable", type: "bool", title: "Enable debug logging", description: "Turns itself off after 30 minutes", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

// Title HTML for a full-width section header. Inline style is a fallback
// for the look of the label itself if a future UI ever strips <style>.
def groupHeaderTitle(String id, String label) {
    return "<span id=\"${id}\" style=\"font-weight:700;letter-spacing:.08em;text-transform:uppercase;color:var(--primary-color,#3f51b5);font-size:1.05rem\">${label}</span>"
}

// One stylesheet, injected only on this device's page (it lives in the
// Connection header description). :has() targets the PrimeFlex cell that
// wraps a unique header id; other devices and Hubitat's own "Default
// Current State" / "Show on Home page" tiles are not selected.
def groupHeaderCss() {
    // PrimeFlex utility classes use a colon (md:col-6). In CSS that
    // character must be escaped; the Groovy string below yields md\:col-6.
    String cell = "div.p-2.col-12.md\\:col-6.xl\\:col-4"
    return """<style id="lgtv-glasshouse-pref-style">
${cell}:has([id^="lgtv-glasshouse-hdr-"]) {
  flex: 0 0 100% !important;
  width: 100% !important;
  max-width: 100% !important;
  padding-top: 0.9rem;
  padding-bottom: 0.1rem;
}
${cell}:has(#lgtv-glasshouse-hdr-connection) {
  padding-top: 0.15rem;
}
${cell}:has([id^="lgtv-glasshouse-hdr-"]) > div {
  border: none !important;
  background: transparent !important;
  box-shadow: none !important;
  border-radius: 0 !important;
  border-bottom: 2px solid var(--primary-color, #3f51b5) !important;
  padding: 0.05rem 0.15rem 0.4rem 0.15rem !important;
}
[id^="lgtv-glasshouse-hdr-"] {
  display: inline-block;
  font-size: 1.05rem !important;
  font-weight: 700 !important;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--primary-color, #3f51b5);
}
/* Hide Hubitat's "Default Current State" only on this device page.
   The rule is anchored to our HUBITAT header id, so other devices
   are unaffected. The underlying setting is not changed. */
.grid:has(#lgtv-glasshouse-hdr-hubitat) > div.p-2:has(#defaultCurrentState),
.grid:has(#lgtv-glasshouse-hdr-hubitat) > div.p-2:has([id="defaultCurrentState"]) {
  display: none !important;
}
</style>"""
}

private List inputList() {
    def raw = state.inputList
    return (raw instanceof List) ? raw.findAll { it instanceof Map } : []
}

private List pictureModeList() {
    def raw = state.pictureModes
    return (raw instanceof List) ? raw.findAll { it instanceof Map } : []
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

void installed() {
    initialize()
}

void updated() {
    log.info "Preferences saved"
    syncNotifierChild()
    initialize()
}

void initialize() {
    unschedule()
    ["powerTarget", "powerWatchUntil", "powerWatchFrom", "holds"].each { state.remove(it) }
    upgradeSettings()
    if (settings.logEnable) runIn(1800, "logsOff")
    if (!tvIp?.toString()?.trim()) {
        log.error "TV IP is required; set it in preferences."
        return
    }
    schedulePoll()
    refresh()
}

private void upgradeSettings() {
    RETIRED_SETTINGS.each { device.removeSetting(it) }
    state.remove("remoteKeys")             // Send key no longer uses /api/remotekeys (0.11.0)
    device.updateDataValue("driverVersion", VERSION)
}

// ---------------------------------------------------------------------------
// Notification device (child)
// ---------------------------------------------------------------------------

// Glasshouse cuts toast text at 120 characters (server/lib/controls.js).
private Integer toastMaxLength() { return 120 }

private String notifierDni() { return "${device.id}-notifier" }

// Creates or deletes the Notifier child to match the preference. The label
// is set only at creation, so a rename sticks across later saves. Errors
// are caught so a missing Notifier driver cannot stop updated().
private void syncNotifierChild() {
    String dni = notifierDni()
    def child = getChildDevice(dni)
    boolean wanted = (notifierEnabled == true || notifierEnabled?.toString() == "true")
    if (wanted && !child) {
        String label = "${device.displayName} Notifier"
        try {
            addChildDevice("dlaporte", "LG TV (Glasshouse) Notifier", dni,
                [name: "LG TV (Glasshouse) Notifier", label: label, isComponent: false])
            log.info "Created notification device ${label}"
        } catch (e) {
            log.error "Could not create the notification device. Install the LG TV (Glasshouse) Notifier driver, then Save Preferences again. (${e.message})"
        }
    } else if (!wanted && child) {
        try {
            deleteChildDevice(dni)
            log.info "Deleted notification device ${child.displayName}"
        } catch (e) {
            log.error "Could not delete the notification device ${child.displayName}: ${e.message}"
        }
    }
}

// Called by the Notifier child. Public so parent.sendToast() reaches it,
// but not a command: the Notifier is how rules send a notification.
// A string value is LG's standard toast. Glasshouse shows "hello" for
// empty text, so blank messages are dropped here.
void sendToast(String text) {
    String msg = text?.trim()
    if (!msg) {
        log.warn "Not sending an empty notification to the TV"
        return
    }
    if (msg.length() > toastMaxLength()) {
        log.warn "Notification is ${msg.length()} characters; the TV shows only the first ${toastMaxLength()}"
    }
    sendControl("toast", msg)
}

private void schedulePoll() {
    String mins = (pollInterval != null) ? pollInterval.toString() : "1"
    if (mins == "0") {
        logDebug "Polling is off"
        return
    }
    Integer n
    try {
        n = mins.toInteger()
    } catch (e) {
        n = 1
    }
    switch (n) {
        case 1:
            runEvery1Minute("refresh")
            break
        case 5:
            runEvery5Minutes("refresh")
            break
        case 10:
            runEvery10Minutes("refresh")
            break
        case 15:
            runEvery15Minutes("refresh")
            break
        case 30:
            runEvery30Minutes("refresh")
            break
        default:
            // Hubitat has no runEvery2Minutes; Quartz cron covers 2 and any other value.
            schedule("0 */${n} * * * ?", "refresh")
            break
    }
    logDebug "Polling every ${n} minute(s)"
}

// ---------------------------------------------------------------------------
// Commands: power and screen
// ---------------------------------------------------------------------------

// Power on sends a Wake-on-LAN magic packet from the hub and also POSTs the
// Glasshouse powerOn action, which works while the TV is in Active Standby with
// Glasshouse still running. Deep sleep relies on WOL (the TV must have
// Wake-on-LAN enabled).

def on() {
    log.info "Power on"
    sendWol()
    sendControl("powerOn")
    startPowerWatch("on", 0L)
}

def off() {
    log.info "Power off"
    sendControl("powerOff")
    startPowerWatch("off", 0L)
}

def screenOff() {
    log.info "Screen off"
    sendControl("screenOff")
}

def screenOn() {
    log.info "Screen on"
    sendControl("screenOn")
}

// Needs allowPower in Glasshouse's config; otherwise Glasshouse refuses and
// the reason is logged.
def reboot() {
    log.info "Reboot"
    sendControl("reboot")
    // Glasshouse answers, then the TV goes down; a stats reply in the first
    // 30 s is from before the reboot.
    startPowerWatch("on", 30_000L)
}

// The power watch, after on, off or reboot. A TV that is fully off, or
// rebooting, can't answer the refresh that follows a command, and one that is
// switching can still report its old state. So: poll every 15 s for up to
// 2 minutes until a stats reply agrees with the command (applyStats ends the
// watch), and meanwhile don't let a reply that disagrees flip switch back,
// which rules and HomeKit would see as two changes.
private void startPowerWatch(String target, long ignoreForMs) {
    state.remove("holds")
    state.powerTarget = target
    state.powerWatchUntil = now() + 120_000L
    state.powerWatchFrom = now() + ignoreForMs
    runIn(15, "powerPoll")
}

void powerPoll() {
    if (!state.powerTarget) return
    if (now() >= ((state.powerWatchUntil ?: 0L) as Long)) {
        logDebug "The TV didn't turn ${state.powerTarget} within 2 minutes; back to the normal poll"
        stopPowerWatch()
        return
    }
    refresh()
    runIn(15, "powerPoll")
}

private void stopPowerWatch() {
    state.remove("powerTarget")
    state.remove("powerWatchUntil")
    state.remove("powerWatchFrom")
    unschedule("powerPoll")
}

// True while a power command is waiting for the TV to agree and this reply
// still disagrees; ends the watch once it agrees (or has run out).
private boolean holdPowerState(String reported) {
    String target = state.powerTarget as String
    if (!target) return false
    if (now() >= ((state.powerWatchUntil ?: 0L) as Long)) {
        stopPowerWatch()
        return false
    }
    if (reported != target) {
        logDebug "The TV still reports ${reported} after ${target}; holding switch at ${device.currentValue('switch')}"
        return true
    }
    if (now() >= ((state.powerWatchFrom ?: 0L) as Long)) {
        stopPowerWatch()
        logDebug "The TV is ${reported}; power watch ended"
    }
    return false
}

// ---------------------------------------------------------------------------
// Commands: volume
// ---------------------------------------------------------------------------

def setVolume(volume) {
    Integer v
    try {
        v = volume.toInteger()
    } catch (e) {
        log.warn "setVolume: '${volume}' is not a number"
        return
    }
    if (v < 0) v = 0
    if (v > 100) v = 100
    log.info "Set volume to ${v}"
    // The volume attribute is set only from a later TV read-back
    // (refresh after Glasshouse answers), never from this command.
    // On HDMI ARC/eARC, Glasshouse's dashboard still offers a 0-100
    // slider (action "volume") but also shows + / - that send
    // volumeStep, because a receiver may ignore the level and only
    // follow those keys (Glasshouse #437). volumeStep also updates
    // the TV's reported level, so we must not send both — that would
    // overshoot. Prefer steps on ARC; otherwise POST action "volume".
    if (!startArcVolumeSteps(v)) {
        sendControl("volume", v)
    }
}

def mute() {
    log.info "Mute"
    sendControl("mute", true)
}

def unmute() {
    log.info "Unmute"
    sendControl("mute", false)
}

// One volumeStep each, the same as Glasshouse's + / - buttons. Works on the TV
// speakers and on HDMI ARC. A press cancels a setVolume step chain in progress.
def volumeUp() {
    log.info "Volume up"
    clearVolumeStepChain()
    sendControl("volumeStep", 1)
}

def volumeDown() {
    log.info "Volume down"
    clearVolumeStepChain()
    sendControl("volumeStep", -1)
}

// ---------------------------------------------------------------------------
// Commands: input, apps, keys, picture
// ---------------------------------------------------------------------------

def setInputSource(name) {
    String key = resolveInput(name)
    if (!key) {
        log.warn "Unknown input '${name}'. Known inputs: ${inputHelp()}"
        return
    }
    log.info "Set input to ${key}"
    sendControl("input", key)
}

def launchApp(appId) {
    String id = resolveApp(appId)
    if (!id) {
        log.warn "launchApp needs an app id (got '${appId}')"
        return
    }
    log.info "Launch app ${id}"
    sendControl("launchApp", id)
}

def sendKey(key) {
    Map sent = resolveKey(key)
    if (!sent) {
        log.warn "Glasshouse doesn't accept the key '${key}'; choose one from the sendKey list"
        return
    }
    log.info "Send key ${sent.action}:${sent.value}"
    sendControl(sent.action as String, sent.value)
}

def setPictureMode(mode) {
    String raw = resolvePictureMode(mode)
    if (!raw) {
        log.warn "Unknown picture mode '${mode}'. Known modes: ${pictureModeHelp()}"
        return
    }
    log.info "Set picture mode to ${raw}"
    sendControl("pictureMode", raw)
}

// ---------------------------------------------------------------------------
// Commands: picture, sound and settings
// ---------------------------------------------------------------------------

def setBacklight(level) {
    Integer v = toIntOrNull(level)
    if (v == null || v < 0 || v > 100) {
        log.warn "setBacklight: '${level}' isn't a number from 0 to 100"
        return
    }
    log.info "Set backlight to ${v}"
    sendControl("backlight", v)
}

def setEnergySaving(mode) {
    String m = mode?.toString()?.trim()?.toLowerCase()
    if (!(m in ENERGY_SAVING)) {
        log.warn "setEnergySaving: '${mode}' isn't one of ${ENERGY_SAVING.join(', ')}"
        return
    }
    log.info "Set energy saving to ${m}"
    sendControl("energySaving", m)
}

def setSoundOutput(output) {
    String id = resolveSoundOutput(output)
    if (!id) {
        log.warn "setSoundOutput: '${output}' isn't a sound output (${soundOutputHelp()})"
        return
    }
    log.info "Set sound output to ${output} (${id})"
    sendControl("soundOutput", id)
}

def setSleepTimer(minutes) {
    String m = minutes?.toString()?.trim()?.toLowerCase()
    if (!(m in SLEEP_TIMER)) {
        log.warn "setSleepTimer: '${minutes}' isn't one of ${SLEEP_TIMER.join(', ')}"
        return
    }
    log.info "Set sleep timer to ${m == 'off' ? 'off' : m + ' minutes'}"
    sendControl("sleepTimer", m)
}

// Any of Glasshouse's LG settings by id (its Settings tab lists them).
def setLgSetting(id, value) {
    String key = id?.toString()?.trim()
    String raw = value?.toString()?.trim()
    if (!key || !raw) {
        log.warn "setLgSetting needs a setting id and a value (got '${id}', '${value}')"
        return
    }
    List<Map> forms = lgSettingPayloads(key, raw)
    log.info "Set LG setting ${key} to ${raw}"
    sendControl("lgSetting", forms[0], forms.size() > 1 ? [fallback: forms[1]] : null)
}

// Glasshouse takes { id, on } for a switch setting and { id, value } for the
// rest, and a number setting takes only a JSON number. The driver doesn't keep
// Glasshouse's list of settings, so an ambiguous value gets a second form,
// sent only if Glasshouse refuses the first as "not one this setting takes".
// on/off goes as a switch first: a choice or number setting refuses a boolean
// without changing anything, while "on" sent as a value to a switch setting
// would switch it off.
private List<Map> lgSettingPayloads(String key, String raw) {
    String lower = raw.toLowerCase()
    if (lower in ["on", "true"]) return [[id: key, on: true], [id: key, value: raw]]
    if (lower in ["off", "false"]) return [[id: key, on: false], [id: key, value: raw]]
    if (raw ==~ /-?\d+/) return [[id: key, value: raw.toInteger()], [id: key, value: raw]]
    return [[id: key, value: raw]]
}

def refresh() {
    fetchJson("/api/stats", "statsCallback")
    fetchJson("/api/picture", "pictureCallback")
    fetchJson("/api/hdmi", "hdmiCallback")
}

// The refreshes 2 s and 45 s after a command, each under its own name: a runIn
// for "refresh" would replace the periodic poll's schedule. The 2 s one shows
// the result quickly. The TV doesn't always report a change that soon (the
// first sleep timer set after a quiet spell still read off at 2 s), so the
// 45 s one, past Glasshouse's longest read cache (30 s), shows what the TV
// ended up with, even with polling off.
void refreshAfterCommand() {
    refresh()
}

void confirmAfterCommand() {
    refresh()
}

// ---------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------

// The Glasshouse dashboard itself uses POST /api/control with
// {"action":"...","value":...} and reads live state from GET /api/stats; this
// driver does the same.

private String apiUrl(String path) {
    String ip = tvIp?.toString()?.trim()
    if (!ip) {
        log.error "TV IP is not set"
        return null
    }
    Integer port = parsedPort()
    return "http://${ip}:${port}${path}"
}

private Integer parsedPort() {
    String raw = (tvPort != null) ? tvPort.toString() : "8080"
    // Strip thousands separators and spaces that a previous number
    // preference may have stored (for example "8,080").
    String cleaned = raw.trim().replaceAll(/[, ]/, "")
    try {
        Integer p = cleaned.toInteger()
        if (p >= 1 && p <= 65535) return p
    } catch (ignored) {}
    log.warn "Port '${tvPort}' is not valid; using 8080"
    return 8080
}

// Adds the optional API token. Glasshouse answers 401 to every call when a token
// is set in its config and the request doesn't carry it.
private Map requestHeaders(Map headers) {
    String token = settings?.apiToken?.toString()?.trim()
    if (token) headers.Authorization = "Bearer ${token}".toString()
    return headers
}

private void fetchJson(String path, String callback) {
    String uri = apiUrl(path)
    if (!uri) return
    Map params = [
        uri: uri,
        contentType: "application/json",
        timeout: 15,
        headers: requestHeaders(["Accept": "application/json"])
    ]
    logDebug "GET ${path}"
    asynchttpGet(callback, params, [path: path])
}

private void sendControl(String action, value = null, Map extra = null) {
    Map payload = [action: action]
    if (value != null) {
        payload.value = value
    }
    String body = JsonOutput.toJson(payload)
    String uri = apiUrl("/api/control")
    if (!uri) return
    Map params = [
        uri: uri,
        contentType: "application/json",
        requestContentType: "application/json",
        timeout: 20,
        headers: requestHeaders(["Accept": "application/json", "Content-Type": "application/json"]),
        body: body
    ]
    Map data = [action: action]
    if (value != null) {
        data.value = value
    }
    if (extra) data.putAll(extra)
    logDebug "POST /api/control ${body}"
    asynchttpPost("controlCallback", params, data)
}

void controlCallback(resp, data) {
    String action = data?.action ?: "control"
    if (action == "toast") {
        toastCallback(resp, data)
        return
    }
    if (!resp) {
        log.warn "Glasshouse ${action}: empty response"
        clearVolumeStepChain()
        return
    }
    if (isUnreachable(resp)) {
        clearVolumeStepChain()
        if (action == "powerOn") {
            log.info "${device.displayName} isn't on the network yet; waiting for Wake-on-LAN to wake it"
        } else if (warnOnUnreachable()) {
            log.warn "Glasshouse ${action} failed (${device.displayName} can't be reached): ${safeError(resp)}"
        } else {
            log.info "${action} not sent: ${device.displayName} is off or unreachable"
        }
        return
    }
    if (resp.hasError() || (resp.status && (resp.status as Integer) >= 400)) {
        String err = safeError(resp)
        log.warn "Glasshouse ${action} failed: ${err}"
        clearVolumeStepChain()
        return
    }
    def json = safeJson(resp)
    if (json instanceof Map && json.ok == false) {
        // See lgSettingPayloads: the second form, only when Glasshouse refused
        // the value itself.
        if (action == "lgSetting" && data?.fallback instanceof Map &&
                json.error?.toString()?.contains("not one this setting takes")) {
            logDebug "Glasshouse refused ${data.value}; trying ${data.fallback}"
            sendControl("lgSetting", data.fallback)
            return
        }
        log.warn "Glasshouse ${action} rejected: ${json.error ?: json}"
        clearVolumeStepChain()
        return
    }
    logDebug "Glasshouse ${action} ok"
    HOLDS_DROPPED_BY[action]?.each { clearHold(it) }
    // Volume is never applied optimistically: only applyStats (the TV
    // read-back) may set the volume attribute. Other actions still get
    // a prompt UI update, then refresh confirms.
    if (action == "volumeStep" && continueArcVolumeSteps()) {
        return
    }
    if (action != "volume" && action != "volumeStep") {
        applyOptimistic(data)
    }
    runIn(2, "refreshAfterCommand")
    runIn(45, "confirmAfterCommand")
}

// Toast results are only logged: nothing on the TV changed, so no
// refresh, and a toast must not reset an ARC volume-step chain.
private void toastCallback(resp, data) {
    if (!resp) {
        log.warn "Glasshouse toast: empty response"
        return
    }
    if (isUnreachable(resp)) {
        if (warnOnUnreachable()) {
            log.warn "Glasshouse toast failed (${device.displayName} can't be reached): ${safeError(resp)}"
        } else {
            log.info "Notification not shown (${device.displayName} is off or unreachable): ${data?.value}"
        }
        return
    }
    if (resp.hasError() || (resp.status && (resp.status as Integer) >= 400)) {
        log.warn "Glasshouse toast failed (is the TV on?): ${safeError(resp)}"
        return
    }
    def json = safeJson(resp)
    if (json instanceof Map && json.ok == false) {
        log.warn "Glasshouse toast rejected: ${json.error ?: json}"
        return
    }
    log.info "Notification sent to the TV: ${data?.value}"
}

private void applyOptimistic(Map data) {
    if (!data) return
    String action = data.action
    def value = data.value
    if (action == "mute") {
        boolean muted = (value == true || value == "true")
        holdValue("mute", muted ? "muted" : "unmuted")
        emitEvent(name: "mute", value: muted ? "muted" : "unmuted",
            descriptionText: "${device.displayName} is ${muted ? 'muted' : 'unmuted'}")
    } else if (action == "powerOn") {
        emitEvent(name: "switch", value: "on",
            descriptionText: "${device.displayName} is on")
    } else if (action == "powerOff") {
        emitEvent(name: "switch", value: "off",
            descriptionText: "${device.displayName} is off")
        emitEvent(name: "screen", value: "off",
            descriptionText: "${device.displayName} screen is off")
    } else if (action == "screenOn") {
        holdValue("screen", "on")
        emitEvent(name: "screen", value: "on",
            descriptionText: "${device.displayName} screen is on")
    } else if (action == "screenOff") {
        holdValue("screen", "off")
        emitEvent(name: "screen", value: "off",
            descriptionText: "${device.displayName} screen is off")
    } else if (action == "input" && value) {
        holdValue("input", value.toString())
        emitInput(value.toString(), "${device.displayName} input is ${value}")
    } else if (action == "pictureMode" && value) {
        String label = pictureModeLabel(value.toString()) ?: value.toString()
        holdValue("pictureMode", label)
        emitEvent(name: "pictureMode", value: label,
            descriptionText: "${device.displayName} picture mode is ${label}")
    } else if (action == "launchApp" && value) {
        emitEvent(name: "currentApp", value: value.toString(),
            descriptionText: "${device.displayName} app is ${value}")
    }
}

// The command hold. After an accepted command whose result is shown at once
// (mute, screen, input, picture mode), the TV can still report the old value
// for a few seconds: the first mute after a quiet spell read back unmuted 4 s
// later although the TV was muted. Glasshouse's own dashboard holds a value it
// has just set in the same way. So for COMMAND_HOLD_MS a read that disagrees
// doesn't change the attribute; one that agrees ends the hold. A newer command
// replaces it, and one that can change it drops it (HOLDS_DROPPED_BY). switch
// is the power watch's (startPowerWatch), and currentApp isn't held:
// launchApp shows the app id, the TV reports its title.
private void holdValue(String attr, String value) {
    Map holds = (state.holds instanceof Map) ? (state.holds as Map) : [:]
    holds[attr] = [value: value, until: now() + COMMAND_HOLD_MS]
    state.holds = holds
}

private void clearHold(String attr) {
    Map holds = (state.holds instanceof Map) ? (state.holds as Map) : null
    if (holds?.containsKey(attr)) {
        holds.remove(attr)
        state.holds = holds
    }
}

// True when a read reporting `reported` for attr is to be ignored.
private boolean heldBack(String attr, String reported) {
    Map holds = (state.holds instanceof Map) ? (state.holds as Map) : null
    Map h = holds?.get(attr) as Map
    if (!h) return false
    if (now() >= ((h.until ?: 0L) as Long) || h.value?.toString() == reported) {
        clearHold(attr)
        return false
    }
    logDebug "The TV still reports ${attr} ${reported}; holding ${h.value} from the last command"
    return true
}

void statsCallback(resp, data) {
    def json = requireJson(resp, "stats")
    if (!(json instanceof Map)) return
    noteReachable()
    applyStats(json)
}

void pictureCallback(resp, data) {
    def json = requireJson(resp, "picture")
    if (!(json instanceof Map)) return
    applyPicture(json)
}

void hdmiCallback(resp, data) {
    def json = requireJson(resp, "hdmi")
    if (!(json instanceof Map)) return
    applyHdmi(json)
}

// No longer requested (0.11.0). Kept so a reply still in flight when the code
// is updated has a handler to land on.
void remoteKeysCallback(resp, data) {
}

private def requireJson(resp, String label) {
    if (!resp) {
        notePollFailure("${label}: empty response")
        return null
    }
    if (isUnreachable(resp)) {
        noteUnreachable(label, safeError(resp))
        return null
    }
    if (resp.hasError() || (resp.status && (resp.status as Integer) >= 400)) {
        notePollFailure("${label}: ${safeError(resp)}")
        return null
    }
    def json = safeJson(resp)
    if (!(json instanceof Map)) {
        notePollFailure("${label}: could not parse JSON")
        return null
    }
    if (json.ok == false) {
        notePollFailure("${label}: ${json.error?.toString() ?: 'ok=false'}")
        return null
    }
    return json
}

private void applyStats(Map d) {
    Map ps = (d.powerState instanceof Map) ? d.powerState : [:]
    boolean systemOn = (ps.systemOn == true || ps.label == "On" || ps.raw == "Active")
    // Active Standby and Standby both mean the TV is powered down from the user's point of view.
    if (ps.raw == "Standby" || ps.raw == "Active Standby") {
        systemOn = false
    }
    String sw = systemOn ? "on" : "off"
    boolean holdPower = holdPowerState(sw)
    if (!holdPower) {
        emitEvent(name: "switch", value: sw,
            descriptionText: "${device.displayName} is ${sw}")
    }

    // Remember how volume can be changed, for setVolume's ARC path.
    if (d.volume_control != null) {
        state.volumeControl = d.volume_control.toString()
    }
    if (d.sound instanceof Map && d.sound.output_raw) {
        state.audioOutputRaw = d.sound.output_raw.toString()
    } else if (d.audio_output) {
        state.audioOutputRaw = d.audio_output.toString()
    }

    if (d.containsKey("volume") && d.volume != null) {
        try {
            Integer v = d.volume.toInteger()
            emitEvent(name: "volume", value: v, unit: "%",
                descriptionText: "${device.displayName} volume is ${v}%")
        } catch (ignored) {}
    }
    if (d.containsKey("muted")) {
        String muteVal = (d.muted == true) ? "muted" : "unmuted"
        if (!heldBack("mute", muteVal)) {
            emitEvent(name: "mute", value: muteVal,
                descriptionText: "${device.displayName} is ${muteVal}")
        }
    }

    // Screen can be off while the TV is still powered (switch stays on).
    boolean screenOn
    if (!systemOn) {
        screenOn = false
    } else if (ps.containsKey("screenOn")) {
        screenOn = (ps.screenOn != false)
    } else {
        screenOn = true
    }
    String screenVal = screenOn ? "on" : "off"
    if (!holdPower && !heldBack("screen", screenVal)) {
        emitEvent(name: "screen", value: screenVal,
            descriptionText: "${device.displayName} screen is ${screenVal}")
    }

    // Fallback input map used until /api/hdmi answers. applyHdmi overwrites this.
    if (d.inputs instanceof Map && !inputList()) {
        Map inputs = d.inputs
        state.inputs = inputs
        List list = inputs.collect { k, v -> [id: k.toString(), label: v?.toString() ?: k.toString()] }
        storeInputs(list)
    }

    String appKey = d.app?.toString()
    String appId = d.app_id?.toString()
    String appName = d.app_name?.toString()
    String title = d.display_title?.toString() ?: appName ?: appKey

    String inputVal = null
    Map inputs = state.inputs instanceof Map ? state.inputs : [:]
    if (appKey && (inputs.containsKey(appKey) || appKey.toLowerCase() == "livetv")) {
        inputVal = appKey
    } else if (appId && appId.toLowerCase().contains(".hdmi")) {
        def m = (appId =~ /hdmi(\d)/)
        if (m) inputVal = "hdmi${m[0][1]}"
    }
    String reportedInput = inputVal ?: appKey
    if (reportedInput && heldBack("input", reportedInput)) {
        // input and mediaInputSource stay at the command's value
    } else if (inputVal) {
        String label = inputs[inputVal] ?: inputVal
        emitInput(inputVal, "${device.displayName} input is ${label} (${inputVal})")
    } else if (appKey) {
        emitInput(appKey, "${device.displayName} source is ${appKey}")
    }

    if (title) {
        emitEvent(name: "currentApp", value: title,
            descriptionText: "${device.displayName} is showing ${title}")
    }

    // Picture modes from /api/picture take precedence; use stats only as a fallback.
    if (!pictureModeList() && d.picture instanceof Map) {
        applyPictureFromStats(d.picture)
    }

    if (d.apps instanceof List) {
        state.apps = d.apps.findAll { it instanceof Map }.collect { Map a ->
            [id: a.id?.toString(), title: a.title?.toString()]
        }
    }

    if (d.mac) {
        state.mac = d.mac.toString()
    }
    if (d.containsKey("wakeOnLan")) {
        state.wakeOnLan = (d.wakeOnLan == true)
    }

    applyReadings(d, ps)

    logDebug "State: switch=${sw} volume=${d.volume} muted=${d.muted} input=${inputVal} app=${title}"
}

// Picture, sound and sensor readings from the same /api/stats reply. A
// reading Glasshouse reports as null (no light sensor, no remote paired) is
// skipped rather than cleared.
private void applyReadings(Map d, Map ps) {
    String name = device.displayName
    Map pic = (d.picture instanceof Map) ? d.picture : [:]
    if (pic.dynamicRange) {
        emitEvent(name: "dynamicRange", value: pic.dynamicRange.toString(),
            descriptionText: "${name} dynamic range is ${pic.dynamicRange}")
    }
    Integer backlight = toIntOrNull(pic.backlight_raw)
    if (backlight != null) {
        emitEvent(name: "backlight", value: backlight, unit: "%",
            descriptionText: "${name} backlight is ${backlight}%")
    }
    if (pic.energySaving) {
        emitEvent(name: "energySaving", value: pic.energySaving.toString(),
            descriptionText: "${name} energy saving is ${pic.energySaving}")
    }
    Map snd = (d.sound instanceof Map) ? d.sound : [:]
    if (snd.output) {
        emitEvent(name: "soundOutput", value: snd.output.toString(),
            descriptionText: "${name} sound output is ${snd.output}")
    }
    if (snd.mode) {
        emitEvent(name: "soundMode", value: snd.mode.toString(),
            descriptionText: "${name} sound mode is ${snd.mode}")
    }
    if (snd.outputs instanceof List) {
        state.soundOutputs = snd.outputs.findAll { it instanceof Map && it.id }.collect { Map o ->
            [id: o.id.toString(), name: (o.name ?: o.id).toString()]
        }
    }
    if (d.sleepTimer != null) {
        String st = d.sleepTimer.toString()
        emitEvent(name: "sleepTimer", value: st,
            descriptionText: "${name} sleep timer is ${st == 'off' ? 'off' : st + ' minutes'}")
    }
    if (ps.label) {
        emitEvent(name: "powerStateLabel", value: ps.label.toString(),
            descriptionText: "${name} power state is ${ps.label}")
    }
    Integer lux = toIntOrNull((d.lightSensor instanceof Map) ? d.lightSensor.lux : null)
    if (lux != null) {
        emitEvent(name: "illuminance", value: lux, unit: "lx",
            descriptionText: "${name} illuminance is ${lux} lx")
    }
    Integer battery = toIntOrNull((d.remote instanceof Map) ? d.remote.battery : null)
    if (battery != null) {
        emitEvent(name: "battery", value: battery, unit: "%",
            descriptionText: "${name} remote battery is ${battery}%")
    }
    // The TV's chip temperature moves a degree at a time all day; no
    // descriptionText, so it doesn't fill the log.
    Integer temp = toIntOrNull(d.temp)
    if (temp != null) {
        emitEvent(name: "tvTemperature", value: temp, unit: "\u00B0C")
    }
}

private Integer toIntOrNull(v) {
    if (v == null) return null
    try {
        return Math.round(v.toString().trim().toDouble()) as Integer
    } catch (ignored) {
        return null
    }
}

// input (frozen) and MediaInputSource's mediaInputSource carry the same value,
// one setInputSource accepts back. Only input logs a descriptionText.
private void emitInput(String value, String descriptionText) {
    emitEvent(name: "input", value: value, descriptionText: descriptionText)
    sendEvent(name: "mediaInputSource", value: value)
}

private void applyPicture(Map d) {
    Map row = pictureModeRow(d)
    if (!row) {
        logDebug "No pictureMode row in /api/picture"
        return
    }
    List modes = []
    if (row.choices instanceof List) {
        modes = row.choices.findAll { it instanceof Map }.collect { Map m ->
            [value: m.value?.toString(), label: (m.label ?: m.value)?.toString()]
        }
    }
    if (modes) {
        state.pictureModes = modes
        emitEvent(name: "supportedPictureModes", value: JsonOutput.toJson(modes),
            descriptionText: "${device.displayName} has ${modes.size()} picture mode(s)")
    }
    String raw = row.value?.toString()
    if (raw) {
        String label = pictureModeLabel(raw) ?: raw
        if (!heldBack("pictureMode", label)) {
            state.pictureModeRaw = raw
            emitEvent(name: "pictureMode", value: label,
                descriptionText: "${device.displayName} picture mode is ${label}")
        }
    }
    logDebug "Picture modes: ${modes.collect { it.label ?: it.value }.join(', ')} (current ${raw})"
}

private void applyPictureFromStats(Map picture) {
    if (picture.modes instanceof List) {
        List modes = picture.modes.findAll { it instanceof Map }.collect { Map m ->
            [value: m.value?.toString(), label: m.label?.toString()]
        }
        if (modes) {
            state.pictureModes = modes
            emitEvent(name: "supportedPictureModes", value: JsonOutput.toJson(modes),
                descriptionText: "${device.displayName} has ${modes.size()} picture mode(s)")
        }
    }
    String modeRaw = picture.mode_raw?.toString()
    String modeLabel = picture.mode?.toString()
    if (modeLabel && heldBack("pictureMode", modeLabel)) return
    if (modeRaw) state.pictureModeRaw = modeRaw
    if (modeLabel) {
        emitEvent(name: "pictureMode", value: modeLabel,
            descriptionText: "${device.displayName} picture mode is ${modeLabel}")
    }
}

private Map pictureModeRow(Map d) {
    List rows = []
    if (d.rows instanceof List) rows.addAll(d.rows)
    // Some responses nest rows under a section.
    d.each { k, v ->
        if (v instanceof Map && v.rows instanceof List) rows.addAll(v.rows)
        if (v instanceof List) rows.addAll(v.findAll { it instanceof Map })
    }
    return rows.find { it instanceof Map && it.id == "pictureMode" }
}

private void applyHdmi(Map d) {
    List sources = []
    if (d.inputs instanceof List) {
        sources = d.inputs.findAll { it instanceof Map }
    }
    List list = []
    Map byKey = [:]
    sources.each { Map src ->
        String key = hdmiInputKey(src)
        if (!key) return
        String label = src.label?.toString() ?: key
        list << [id: key, label: label]
        byKey[key] = label
    }
    if (list) {
        state.inputs = byKey
        storeInputs(list)
    }
    logDebug "Inputs: ${list.collect { it.label + ' (' + it.id + ')' }.join(', ')}"
}

private String hdmiInputKey(Map src) {
    // Glasshouse's input command uses keys such as "hdmi1", not "HDMI_1".
    def port = src.port
    if (port != null) {
        try {
            Integer p = port.toInteger()
            if (p > 0) return "hdmi${p}"
        } catch (ignored) {}
    }
    String appId = src.appId?.toString() ?: ""
    def m = (appId.toLowerCase() =~ /hdmi(\d)/)
    if (m) return "hdmi${m[0][1]}"
    String id = src.id?.toString() ?: ""
    def m2 = (id.toLowerCase() =~ /hdmi[\s_\-]?(\d)/)
    if (m2) return "hdmi${m2[0][1]}"
    return id ? id.toLowerCase() : null
}

private void storeInputs(List list) {
    state.inputList = list
    emitEvent(name: "supportedInputs", value: JsonOutput.toJson(list),
        descriptionText: "${device.displayName} has ${list.size()} input(s)")
}

private void notePollFailure(String reason) {
    Integer n = (state.failCount ?: 0) + 1
    state.failCount = n
    // Do not flip switch to off on a failed poll: a network blip must
    // not fire rules. Log the first failure, then every 10th.
    if (n == 1 || (n % 10) == 0) {
        log.warn "Glasshouse request failed (${n} time${n == 1 ? '' : 's'}): ${reason}"
    } else {
        logDebug "Glasshouse request failed (${n}): ${reason}"
    }
}

// The hub's HTTP client reports a request that never reached the TV (seen
// on the hub: no route to host, connect timed out) as status 408. A read
// timeout is also 408, so a TV that stops answering mid-request counts too.
private boolean isUnreachable(resp) {
    try {
        return resp != null && resp.status != null && (resp.status as Integer) == 408
    } catch (ignored) {
        return false
    }
}

private boolean warnOnUnreachable() {
    return (warnUnreachable == true || warnUnreachable?.toString() == "true")
}

// A switched-off TV drops off the network, which is normal unless Always
// Ready keeps it there. By default that is one info line when the TV goes
// and one when it is back (noteReachable); with Warn when the TV can't be
// reached on, it is a warning on the first miss and every 10th. Only the
// stats request counts: all four poll requests fail together, and a
// counter written from four concurrent callbacks loses updates.
private void noteUnreachable(String label, String reason) {
    if (label != "stats") {
        logDebug "Glasshouse ${label} unreachable: ${reason}"
        return
    }
    Integer n = (state.unreachableCount ?: 0) + 1
    state.unreachableCount = n
    if (n == 1) state.unreachableSince = now()
    if (device.currentValue("healthStatus") != "offline") {
        emitEvent(name: "healthStatus", value: "offline", descriptionText: "${device.displayName} is offline")
    }
    // A TV that can't be reached is off as far as rules and dashboards go,
    // which is also what an off command was waiting for.
    if (state.powerTarget == "off") stopPowerWatch()
    state.remove("holds")
    emitEvent(name: "switch", value: "off", descriptionText: "${device.displayName} is off")
    emitEvent(name: "screen", value: "off", descriptionText: "${device.displayName} screen is off")
    if (warnOnUnreachable()) {
        if (n == 1 || (n % 10) == 0) {
            log.warn "${device.displayName} can't be reached (${n} poll${n == 1 ? '' : 's'}): ${reason}"
        } else {
            logDebug "${device.displayName} can't be reached (${n}): ${reason}"
        }
    } else if (n == 1) {
        log.info "${device.displayName} is off or unreachable (${reason}); further misses are logged at debug only"
    } else {
        logDebug "${device.displayName} is still off or unreachable (${n}): ${reason}"
    }
}

// Called when stats answers.
private void noteReachable() {
    if (device.currentValue("healthStatus") != "online") {
        emitEvent(name: "healthStatus", value: "online", descriptionText: "${device.displayName} is online")
    }
    Integer n = (state.unreachableCount ?: 0) as Integer
    if (n > 0) {
        String polls = "${n} missed poll${n == 1 ? '' : 's'}"
        Long since = state.unreachableSince as Long
        String after = since ? "after ${formatDuration(now() - since)} (${polls})" : "(after ${polls})"
        log.info "${device.displayName} is reachable again ${after}"
        state.remove("unreachableCount")
        state.remove("unreachableSince")
    }
    state.failCount = 0
}

private String formatDuration(Long ms) {
    Long totalMin = Math.max(0L, ms as long).intdiv(60_000L)
    if (totalMin == 0) return "under a minute"
    Long h = totalMin.intdiv(60L)
    Long m = totalMin % 60L
    return h > 0 ? "${h}h ${m}m" : "${m}m"
}

private def safeJson(resp) {
    try {
        def j = resp.getJson()
        if (j instanceof Map || j instanceof List) return j
    } catch (ignored) {}
    try {
        def raw = resp.data
        if (raw instanceof Map || raw instanceof List) return raw
        if (raw instanceof String && raw) {
            return new JsonSlurper().parseText(raw)
        }
        // Some firmware wraps the body as GString / byte[] / already-parsed.
        if (raw != null) {
            String s = raw.toString()
            if (s && (s.startsWith("{") || s.startsWith("["))) {
                return new JsonSlurper().parseText(s)
            }
        }
    } catch (e) {
        logDebug "JSON parse failed: ${e.message}"
    }
    return null
}

private String safeError(resp) {
    try {
        String msg = resp.getErrorMessage()
        if (msg) return "HTTP ${resp.status ?: '?'} ${msg}"
    } catch (ignored) {}
    return "HTTP ${resp?.status ?: '?'}"
}

// ---------------------------------------------------------------------------
// Wake-on-LAN
// ---------------------------------------------------------------------------

private void sendWol() {
    String raw = (tvMac ?: state.mac ?: "").toString()
    String mac = raw.replaceAll(/[^0-9A-Fa-f]/, "").toLowerCase()
    if (mac.length() != 12) {
        log.warn "Wake-on-LAN skipped: set a 12-digit MAC in preferences (got '${raw}')"
        return
    }
    if (state.wakeOnLan == false) {
        log.warn "The TV last reported Wake-on-LAN as disabled; sending a magic packet anyway"
    }
    logDebug "Sending Wake-on-LAN to ${mac}"
    try {
        sendHubCommand(new hubitat.device.HubAction(
            "wake on lan ${mac}",
            hubitat.device.Protocol.LAN,
            null,
            [:]
        ))
    } catch (e) {
        log.warn "Wake-on-LAN failed: ${e.message}"
    }
}

// ---------------------------------------------------------------------------
// Name resolution
// ---------------------------------------------------------------------------

private String resolveInput(name) {
    if (name == null) return null
    String raw = name.toString().trim()
    if (!raw) return null
    String lower = raw.toLowerCase()
    if (lower == "livetv" || lower == "live tv" || lower == "tv") return "livetv"

    // "hdmi1", "HDMI_1", "HDMI 1", "1"
    def hdmi = (lower =~ /^hdmi[\s_\-]?(\d)$/)
    if (hdmi) return "hdmi${hdmi[0][1]}"
    if (lower =~ /^\d$/) return "hdmi${lower}"

    Map inputs = state.inputs instanceof Map ? state.inputs : [:]
    if (inputs.containsKey(raw)) return raw
    if (inputs.containsKey(lower)) return lower
    def byLabel = inputList().find { it.id?.equalsIgnoreCase(raw) || it.label?.equalsIgnoreCase(raw) }
    if (byLabel) return byLabel.id?.toString()
    String match = inputs.find { k, v ->
        k.toString().equalsIgnoreCase(raw) || v?.toString()?.equalsIgnoreCase(raw)
    }?.key
    return match?.toString()
}

private String resolveApp(appId) {
    if (appId == null) return null
    String raw = appId.toString().trim()
    if (!raw) return null
    List apps = state.apps ?: []
    def exact = apps.find { it.id == raw }
    if (exact) return exact.id
    def byTitle = apps.find { it.title?.equalsIgnoreCase(raw) }
    if (byTitle) return byTitle.id
    def byTail = apps.find { it.id?.toLowerCase()?.endsWith("." + raw.toLowerCase()) || it.id?.equalsIgnoreCase(raw) }
    if (byTail) return byTail.id
    // Pass through unknown ids (e.g. Netflix on a set that has not been polled yet).
    return raw
}

private Map resolveKey(key) {
    if (key == null) return null
    String raw = key.toString().trim()
    if (!raw) return null
    String lower = raw.toLowerCase()

    // Glasshouse rejects anything outside its rcu and media key sets (there is
    // no "lg" action), so unknown keys return null rather than being sent.
    if (lower.startsWith("media:")) {
        String mapped = mediaAction(raw.substring(6))
        return mapped ? [action: "media", value: mapped] : null
    }
    if (!lower.startsWith("rcu:")) {
        String media = mediaAction(lower)
        if (media) return [action: "media", value: media]
    }
    String rcu = rcuKey(lower.startsWith("rcu:") ? lower.substring(4) : lower)
    return (rcu in RCU_KEYS) ? [action: "rcu", value: rcu] : null
}

private String mediaAction(String raw) {
    String lower = raw.toLowerCase().replaceAll(/[\s_\-]/, "")
    Map map = [
        play: "play",
        pause: "pause",
        playpause: "playPause",
        stop: "stop",
        rewind: "rewind",
        ff: "fastForward",
        fastforward: "fastForward",
        fastfwd: "fastForward",
        channelup: "channelUp",
        channeldown: "channelDown"
    ]
    return map[lower]
}

private String rcuKey(String raw) {
    String lower = raw.toLowerCase().trim()
    Map map = [
        enter: "ok",
        select: "ok",
        confirm: "ok",
        ok: "ok",
        menu: "settings",
        setting: "settings",
        settings: "settings",
        exit: "back",
        back: "back",
        home: "home",
        up: "up",
        down: "down",
        left: "left",
        right: "right"
    ]
    return map[lower] ?: lower
}

private String resolvePictureMode(mode) {
    if (mode == null) return null
    String raw = mode.toString().trim()
    if (!raw) return null
    List modes = pictureModeList()
    def exact = modes.find { it.value == raw }
    if (exact) return exact.value
    def byLabel = modes.find { it.label?.equalsIgnoreCase(raw) || it.value?.equalsIgnoreCase(raw) }
    if (byLabel) return byLabel.value
    return raw
}

// The TV's own outputs (sound.outputs in /api/stats) first; the fixed table
// for a name the TV hasn't listed.
private String resolveSoundOutput(output) {
    String want = output?.toString()?.trim()
    if (!want) return null
    List listed = (state.soundOutputs instanceof List) ? state.soundOutputs : []
    def match = listed.find { it instanceof Map && it.name?.toString()?.equalsIgnoreCase(want) }
    if (match) return match.id?.toString()
    String known = SOUND_OUTPUTS.keySet().find { it.equalsIgnoreCase(want) }
    return known ? SOUND_OUTPUTS[known] : null
}

private String soundOutputHelp() {
    List listed = (state.soundOutputs instanceof List) ? state.soundOutputs : []
    return (listed ? listed.collect { it.name } : SOUND_OUTPUTS.keySet() as List).join(", ")
}

private String pictureModeLabel(String raw) {
    List modes = pictureModeList()
    def found = modes.find { it.value == raw || it.value?.equalsIgnoreCase(raw) }
    return found?.label
}

private String inputHelp() {
    List list = inputList()
    if (!list) return "(none known yet; try hdmi1 or livetv)"
    return list.collect { "${it.id} (${it.label})" }.join(", ")
}

private String pictureModeHelp() {
    List modes = pictureModeList()
    if (!modes) return "(none known yet; poll the TV first)"
    return modes.collect { it.label ?: it.value }.join(", ")
}

// On HDMI ARC/eARC (or when Glasshouse reports volume_control
// "steps"), also send volumeStep so a receiver that ignores the TV's
// own level can still follow. Each step is +/- 1, same as the
// dashboard's + / - buttons. Capped so a large setVolume jump cannot
// fire a long burst.
private boolean startArcVolumeSteps(Integer desired) {
    if (desired == null) return false
    String out = (state.audioOutputRaw ?: "").toString().toLowerCase()
    String vc = (state.volumeControl ?: "").toString()
    boolean arc = out.contains("arc")
    if (vc == "none") return false
    if (!(vc == "steps" || arc)) return false
    Integer current = null
    try {
        def cv = device.currentValue("volume")
        if (cv != null) current = cv.toInteger()
    } catch (ignored) {
        return false
    }
    if (current == null || current == desired) return false
    int delta = desired - current
    int dir = (delta > 0) ? 1 : -1
    int n = Math.abs(delta)
    if (n > 30) n = 30
    state.volumeStepDir = dir
    state.volumeStepLeft = n
    log.info "HDMI ARC: sending ${n} x volumeStep(${dir}) so the receiver can follow (same path as Glasshouse + / -)"
    sendControl("volumeStep", dir)
    return true
}

// Returns true if another step was dispatched (callback should not
// refresh yet).
private boolean continueArcVolumeSteps() {
    def left = state.volumeStepLeft
    if (left == null) return false
    int n
    try {
        n = left.toInteger()
    } catch (ignored) {
        clearVolumeStepChain()
        return false
    }
    if (n > 1) {
        state.volumeStepLeft = n - 1
        Integer dir = 1
        try {
            dir = state.volumeStepDir?.toInteger() ?: 1
        } catch (ignored) {}
        sendControl("volumeStep", dir)
        return true
    }
    clearVolumeStepChain()
    return false
}

private void clearVolumeStepChain() {
    state.remove("volumeStepLeft")
    state.remove("volumeStepDir")
    state.remove("volumeStepTarget")
}

// ---- BEGIN standard logging (driver) v1: keep identical in every driver, see STYLE.md ----

void logsOff() {
    log.info "Debug logging turned off"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

private void logDebug(String msg) {
    if (settings.logEnable) log.debug msg
}

private void logDesc(String msg) {
    if (settings.txtEnable != false) log.info msg
}

// Sends the event; logs its descriptionText only when the value actually changes.
private void emitEvent(Map evt) {
    boolean changed = device.currentValue(evt.name as String)?.toString() != evt.value?.toString()
    sendEvent(evt)
    if (changed && evt.descriptionText) logDesc(evt.descriptionText as String)
}

// ---- END standard logging ----
