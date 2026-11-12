/*
 *  LG TV (Glasshouse)
 *
 *  Control a rooted LG webOS TV through the Glasshouse JSON API
 *  (typically on port 8080, no authentication). The dashboard itself
 *  uses POST /api/control with {"action":"...","value":...} and reads
 *  live state from GET /api/stats.
 *
 *  Picture-mode choices come from GET /api/picture (the pictureMode
 *  row; they change with the current content, e.g. Dolby Vision vs
 *  SDR). Inputs come from GET /api/hdmi. Both are shown as preference
 *  pull-downs, which Hubitat can fill at page-render time from state
 *  saved at refresh. Command-parameter ENUMs cannot do this - they are
 *  fixed at compile time - so setInput and setPictureMode stay free
 *  text and still work from rules and Maker API.
 *
 *  Power on sends a Wake-on-LAN magic packet from the hub and also
 *  POSTs the Glasshouse powerOn action, which works while the TV is in
 *  Active Standby with Glasshouse still running. Deep sleep relies on
 *  WOL (the TV must have Wake-on-LAN enabled).
 *
 *  v0.8 - initial public release. Preference-page controls for power,
 *         volume (including HDMI ARC volumeStep), mute, input, picture
 *         mode, screen, launch app and send key. Commands tab is just
 *         Initialize and Refresh.
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

metadata {
    definition(
        name: "LG TV (Glasshouse)",
        namespace: "dlaporte",
        author: "David LaPorte",
        importUrl: "https://raw.githubusercontent.com/dlaporte/Hubitat/main/LGglasshouse/device/LGTV-Glasshouse.groovy"
    ) {
        // Commands on the device page come only from capabilities.
        // Refresh and Initialize are the only ones declared, so the
        // Commands section shows just those two. on/off, setVolume,
        // volumeUp/Down, mute/unmute, setInput, setPictureMode,
        // screenOn/Off, toggleMute, toggleScreen, launchApp and
        // sendKey remain as methods: preferences and the volume
        // slider (POST /device/runmethod) still call them.
        capability "Refresh"
        capability "Initialize"
        capability "Actuator"

        attribute "switch", "enum", ["on", "off"]
        attribute "volume", "number"
        attribute "mute", "string"
        attribute "input", "string"
        attribute "currentApp", "string"
        attribute "pictureMode", "string"
        attribute "supportedPictureModes", "string"
        attribute "supportedInputs", "string"
        attribute "screen", "string"
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
        input name: "hdrTv", type: "paragraph",
            title: groupHeaderTitle("lgtv-glasshouse-hdr-tv", "TV"),
            description: "Power is applied on Save only if it differs from the TV. Launch app and Send key run on Save only when a choice is selected; Send key then resets so the same key can be sent again."
        input name: "prefPower", type: "bool", title: "Power",
            description: "On wakes the TV (Wake-on-LAN and power on). Off powers the TV down. Applied only when this differs from the current power state.",
            required: false
        input name: "prefLaunchApp", type: "enum", title: "Launch app",
            description: "Leave at (don't change) for no action. Choices come from the apps installed on the TV and refresh with each poll.",
            options: getLaunchAppPrefOptions(),
            required: false
        input name: "prefSendKey", type: "enum", title: "Send key",
            description: "Leave at (don't change) for no action. Choices come from the TV's remote keys, plus the usual navigation and media keys. After a key is sent, this resets so the same key can be sent again.",
            options: getSendKeyPrefOptions(),
            required: false

        input name: "hdrSound", type: "paragraph",
            title: groupHeaderTitle("lgtv-glasshouse-hdr-sound", "Sound"),
            description: "Release the volume slider to set the TV. Mute is applied on Save Preferences only if it differs from the TV."
        input name: "prefVolume", type: "number", title: "Volume",
            description: volumeSliderHtml(),
            range: "0..100", required: false
        input name: "prefMute", type: "bool", title: "Muted",
            description: "On is muted, off is unmuted. Applied only when this differs from the TV's current mute state.",
            required: false

        input name: "hdrPicture", type: "paragraph",
            title: groupHeaderTitle("lgtv-glasshouse-hdr-picture", "Picture"),
            description: "Input and picture mode choices come from the TV and are refreshed with each poll. Screen on is a toggle. All three are applied only when you click Save Preferences and the value differs from the TV."
        input name: "prefInput", type: "enum", title: "Input",
            description: "Leave at (don't change) to keep the current input.",
            options: getInputPrefOptions(),
            required: false
        input name: "prefPictureMode", type: "enum", title: "Picture mode",
            description: "Choices follow the content that is playing (for example Dolby Vision vs SDR). Leave at (don't change) to keep the current mode.",
            options: getPictureModePrefOptions(),
            required: false
        input name: "prefScreen", type: "bool", title: "Screen on",
            description: "On is the panel on, off turns only the screen off (the TV stays powered). Applied only when this differs from the current screen state.",
            required: false

        // Last driver preference group. Hubitat then appends "Default
        // Current State" and "Show on Home page", so they land in this
        // section under the HUBITAT heading together with debug logging.
        input name: "hdrHubitat", type: "paragraph",
            title: groupHeaderTitle("lgtv-glasshouse-hdr-hubitat", "Hubitat"),
            description: "Hubitat options for this device."
        input name: "debugLogging", type: "bool", title: "Enable debug logging",
            description: "Turns off automatically after 30 minutes",
            defaultValue: false
    }
}

// Sentinel option values that must never be sent to the TV.
// Kept as methods because a Hubitat driver is a Groovy script, not a class,
// so static final fields need @Field and are easy to get wrong.
private String prefUnchanged() { return "_unchanged" }
private String prefLoading() { return "_loading" }

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
#lgtv-glasshouse-vol-wrap {
  display: flex;
  align-items: center;
  gap: 0.55rem;
  margin: 0.15rem 0 0.1rem 0;
}
#lgtv-glasshouse-pref-volume {
  flex: 1 1 auto;
  width: 100%;
  accent-color: var(--primary-color, #3f51b5);
  height: 1.35rem;
  cursor: pointer;
}
#lgtv-glasshouse-vol-readout {
  min-width: 2.2ch;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--text-color, inherit);
}
#lgtv-glasshouse-vol-status, .lgtv-gh-vol-status {
  display: block;
  margin: 0.15rem 0 0 0;
  font-size: 0.85rem;
  color: var(--primary-color, #3f51b5);
  min-height: 1.1em;
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

// Volume preference: a range input in the description (innerHTML; inline
// handlers run, <script> tags do not) mirrors the PrimeVue InputNumber
// so the field can be used as a slider. Ids are unique to this driver.
def volumeSliderHtml() {
    String cur = volumePrefDisplay()
    String js = volumeSliderJs()
    String html = "<div id=\"lgtv-glasshouse-vol-wrap\" onmouseenter=\'" + js + "\'>"
    html += "<input type=\"range\" id=\"lgtv-glasshouse-pref-volume\" min=\"0\" max=\"100\" step=\"1\" aria-label=\"Volume\""
    if (cur) html += " value=\"" + cur + "\""
    html += " oninput=\'" + js + "\' onchange=\'" + js + "\' ontouchend=\'" + js + "\'>"
    html += "<span id=\"lgtv-glasshouse-vol-readout\">" + (cur ?: "") + "</span></div>"
    html += "<div id=\"lgtv-glasshouse-vol-status\" class=\"lgtv-gh-vol-status\"></div>"
    html += "Release the slider to set the TV volume (0-100). Save Preferences also stores this value. Leave the number blank for no change on Save. On HDMI ARC, the soundbar may ignore the TV's own level; the driver also sends Volume Up / Down (the same volumeStep Glasshouse uses) so the receiver can follow."
    return html
}

private String volumePrefDisplay() {
    def raw = settings?.prefVolume
    if (raw == null || raw.toString().trim() == "") {
        raw = device?.currentValue("volume")
    }
    if (raw == null || raw.toString().trim() == "") return ""
    try {
        Integer v = raw.toString().trim().replaceAll(/[, ]/, "").toInteger()
        if (v < 0) v = 0
        if (v > 100) v = 100
        return v.toString()
    } catch (ignored) {
        return ""
    }
}

// Single-quoted so Groovy does not interpolate. The JS itself has no
// single quotes or $. On input, mirrors the PrimeVue number field and
// Vue model (and hooks Save so the posted value is the slider). On
// release (change / touchend), POSTs /device/runmethod setVolume, the
// same endpoint the Commands tab uses, so the TV changes without Save.
// A short guard stops the 1-minute poll from snapping the slider back.
private String volumeSliderJs() {
    return '''(function(el){
var wrap=el&&el.closest?el.closest("#lgtv-glasshouse-vol-wrap"):null;
if(!wrap&&el&&el.id==="lgtv-glasshouse-vol-wrap")wrap=el;
if(!wrap)return;
var slider=wrap.querySelector("#lgtv-glasshouse-pref-volume");
if(!slider)return;
var cell=wrap.closest("div.p-2")||wrap.closest("[class*=col-12]")||document;
var box=cell.querySelector(".p-inputnumber");
var input=box?box.querySelector("input"):null;
if(!input){var byId=document.getElementById("prefVolume");input=byId&&byId.tagName==="INPUT"?byId:(byId&&byId.querySelector?byId.querySelector("input"):null);}
var ro=document.getElementById("lgtv-glasshouse-vol-readout");
var st=document.getElementById("lgtv-glasshouse-vol-status");
var ev=(typeof event!=="undefined")?event:null;
var evType=ev&&ev.type?ev.type:"";
var fromSlider=(el===slider);
function setStatus(msg){if(st)st.textContent=msg||"";}
function setModel(v){
  var n=input||box||cell;
  while(n){
    var c=n.__vueParentComponent;
    if(c){
      var ctx=c.ctx||c.proxy;
      if(ctx&&ctx.preferencesModel){ctx.preferencesModel.prefVolume=v;return;}
      if(c.emit&&c.props&&c.props.preference){try{c.emit("update:value",v);}catch(e3){}}
    }
    n=n.parentElement;
  }
  function walk(obj,depth){
    if(!obj||depth>8)return false;
    try{if(obj.preferencesModel&&typeof obj.preferencesModel==="object"){obj.preferencesModel.prefVolume=v;return true;}}catch(e4){}
    var kids=[obj.ctx,obj.proxy,obj.setupState,obj._instance,obj.subTree,obj.component,obj.__vue_app__,obj._vnode];
    for(var i=0;i<kids.length;i++){if(walk(kids[i],depth+1))return true;}
    return false;
  }
  var app=document.querySelector("#app");
  if(app){walk(app.__vue_app__,0);walk(app._vnode,0);}
}
function pushNumber(v){
  if(!input||isNaN(v))return;
  input.value=String(v);
  try{input.dispatchEvent(new InputEvent("input",{bubbles:true,composed:true,data:String(v),inputType:"insertFromPaste"}));}catch(e1){input.dispatchEvent(new Event("input",{bubbles:true}));}
  input.dispatchEvent(new Event("change",{bubbles:true}));
  try{input.dispatchEvent(new FocusEvent("blur",{bubbles:true}));}catch(e2){input.dispatchEvent(new Event("blur",{bubbles:true}));}
  setModel(v);
}
function showVal(v){
  if(ro)ro.textContent=isNaN(v)?"":String(v);
  if(!isNaN(v)){slider.value=String(v);wrap.setAttribute("data-lgtv-user",String(v));wrap.setAttribute("data-lgtv-edit-at",String(Date.now()));}
}
function applyLive(){
  var v=parseInt(slider.value,10);
  if(isNaN(v))return;
  if(v<0)v=0;if(v>100)v=100;
  var prev=wrap.getAttribute("data-lgtv-sent");
  var at=parseInt(wrap.getAttribute("data-lgtv-sent-at")||"0",10);
  if(String(v)===prev&&(Date.now()-at)<2000)return;
  wrap.setAttribute("data-lgtv-sent",String(v));
  wrap.setAttribute("data-lgtv-sent-at",String(Date.now()));
var parts=location.pathname.split("/");var id=null;for(var pi=0;pi<parts.length;pi++){if(parts[pi]==="device"){var nxt=parts[pi+1];if(nxt==="edit")nxt=parts[pi+2];if(nxt&&nxt===String(parseInt(nxt,10))){id=nxt;break;}}}
  if(!id){setStatus("Could not find device id");return;}
  setStatus("Setting volume to "+v+"...");
  fetch("/device/runmethod",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({id:parseInt(id,10),method:"setVolume",args:[{type:"NUMBER",value:v}]})})
    .then(function(r){if(!r.ok)throw new Error(String(r.status));setStatus("TV volume set to "+v);})
    .catch(function(){setStatus("Could not set volume");wrap.removeAttribute("data-lgtv-sent");});
}
function hookFetch(){
  if(window.lgtvGhFetchHooked)return;
  window.lgtvGhFetchHooked=1;
  var orig=window.fetch;
  window.fetch=function(url,opts){
    try{
      var u=typeof url==="string"?url:(url&&url.url);
      if(u&&String(u).indexOf("/device/preference/save")>=0&&opts&&opts.body){
        var body=JSON.parse(opts.body);
        var sl=document.getElementById("lgtv-glasshouse-pref-volume");
        if(sl&&body.preferences){
          var nv=parseInt(sl.value,10);
          if(!isNaN(nv)){
            body.preferences.forEach(function(p){if(p.name==="prefVolume")p.value=nv;});
            opts=Object.assign({},opts,{body:JSON.stringify(body)});
          }
        }
      }
    }catch(e5){}
    return orig.apply(this,arguments);
  };
}
function bind(){
  if(slider.getAttribute("data-lgtv-bound"))return;
  slider.setAttribute("data-lgtv-bound","1");
  hookFetch();
  slider.addEventListener("input",function(){var v=parseInt(slider.value,10);showVal(v);pushNumber(v);if(st)st.textContent="Release to set the TV volume";});
  slider.addEventListener("change",function(){var v=parseInt(slider.value,10);showVal(v);pushNumber(v);applyLive();});
  slider.addEventListener("touchend",function(){var v=parseInt(slider.value,10);showVal(v);pushNumber(v);applyLive();});
  if(input){
    var sync=function(){var n3=parseInt(input.value,10);if(isNaN(n3))return;if(slider.value!==String(n3)&&!wrap.getAttribute("data-lgtv-user")){slider.value=String(n3);if(ro)ro.textContent=String(n3);}};
    input.addEventListener("input",sync);
    input.addEventListener("change",sync);
  }
  setInterval(function(){
    var want=wrap.getAttribute("data-lgtv-user");
    var eat=parseInt(wrap.getAttribute("data-lgtv-edit-at")||"0",10);
    if(!want||Date.now()-eat>120000)return;
    if(slider.value!==want){slider.value=want;if(ro)ro.textContent=want;}
    if(input&&input.value!==want){pushNumber(parseInt(want,10));}
  },700);
}
bind();
if(fromSlider){
  var v=parseInt(slider.value,10);
  showVal(v);
  pushNumber(v);
  if(evType==="change"||evType==="touchend"||evType==="pointerup"||evType==="mouseup")applyLive();
  else if(evType==="input")setStatus("Release to set the TV volume");
}else if(input&&input.value!==""&&!wrap.getAttribute("data-lgtv-user")){
  var n2=parseInt(input.value,10);
  if(!isNaN(n2)){slider.value=String(n2);if(ro)ro.textContent=String(n2);}
}
})(this)'''
}

// ---------------------------------------------------------------------------
// Dynamic preference options (evaluated when the device page is drawn)
// ---------------------------------------------------------------------------

def getInputPrefOptions() {
    Map opts = [(prefUnchanged()): "(don't change)"]
    inputList().each { Map item ->
        String id = item.id?.toString()
        if (!id) return
        String label = item.label ? "${item.label} (${id})" : id
        opts[id] = label
    }
    if (opts.size() == 1) {
        String cur = device?.currentValue("input")?.toString()
        if (cur) opts[cur] = cur
        else opts[prefLoading()] = "Refresh the device to load inputs"
    }
    return opts
}

def getPictureModePrefOptions() {
    Map opts = [(prefUnchanged()): "(don't change)"]
    pictureModeList().each { Map item ->
        String v = item.value?.toString()
        if (!v) return
        opts[v] = (item.label ?: v).toString()
    }
    if (opts.size() == 1) {
        String cur = state?.pictureModeRaw?.toString() ?: device?.currentValue("pictureMode")?.toString()
        if (cur) opts[cur] = cur
        else opts[prefLoading()] = "Refresh the device to load picture modes"
    }
    return opts
}

def getLaunchAppPrefOptions() {
    Map opts = [(prefUnchanged()): "(don't change)"]
    appList().sort { (it.title ?: it.id ?: "").toString().toLowerCase() }.each { Map item ->
        String id = item.id?.toString()
        if (!id) return
        String title = item.title?.toString()
        opts[id] = title ? "${title}" : id
    }
    if (opts.size() == 1) {
        opts[prefLoading()] = "Refresh the device to load apps"
    }
    return opts
}

def getSendKeyPrefOptions() {
    Map opts = [(prefUnchanged()): "(don't change)"]
    sendKeyList().each { Map item ->
        String id = item.id?.toString()
        if (!id) return
        opts[id] = (item.label ?: id).toString()
    }
    if (opts.size() == 1) {
        builtinSendKeys().each { Map item ->
            opts[item.id] = item.label
        }
    }
    return opts
}

private List appList() {
    def raw = state.apps
    return (raw instanceof List) ? raw.findAll { it instanceof Map && it.id } : []
}

private List sendKeyList() {
    def raw = state.remoteKeys
    return (raw instanceof List) ? raw.findAll { it instanceof Map && it.id } : builtinSendKeys()
}

private List builtinSendKeys() {
    return [
        [id: "up", label: "Up"],
        [id: "down", label: "Down"],
        [id: "left", label: "Left"],
        [id: "right", label: "Right"],
        [id: "ok", label: "OK"],
        [id: "back", label: "Back"],
        [id: "home", label: "Home"],
        [id: "settings", label: "Settings"],
        [id: "media:playPause", label: "Play / Pause"],
        [id: "media:play", label: "Play"],
        [id: "media:pause", label: "Pause"],
        [id: "media:stop", label: "Stop"],
        [id: "media:rewind", label: "Rewind"],
        [id: "media:fastForward", label: "Fast forward"]
    ]
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

def installed() {
    log.info "${device.displayName} installed"
    initialize()
}

def updated() {
    log.info "${device.displayName} updated"
    // Apply preference choices first, and only when they actually differ
    // from the TV's current values. Blank or sentinel values are ignored,
    // so a Save Preferences that only changes IP, port or polling is safe.
    applyPreferredPower()
    applyPreferredLaunchApp()
    applyPreferredSendKey()
    applyPreferredInput()
    applyPreferredPictureMode()
    applyPreferredMute()
    applyPreferredVolume()
    applyPreferredScreen()
    initialize()
}

def initialize() {
    unschedule()
    if (!tvIp?.toString()?.trim()) {
        log.error "TV IP is required; set it in preferences."
        return
    }
    if (debugLogging) {
        log.debug "Debug logging enabled; will turn off in 30 minutes"
        runIn(1800, "logsOff")
    }
    schedulePoll()
    refresh()
}

def logsOff() {
    log.info "Debug logging disabled"
    device.updateSetting("debugLogging", [value: "false", type: "bool"])
}

private void schedulePoll() {
    String mins = (pollInterval != null) ? pollInterval.toString() : "1"
    if (mins == "0") {
        dbg "Polling is off"
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
    dbg "Polling every ${n} minute(s)"
}

private void applyPreferredPower() {
    def desired = settings?.prefPower
    if (desired == null || desired.toString().trim() == "") {
        dbg "Power preference is unset; not sending"
        return
    }
    boolean wantOn = (desired == true || desired.toString() == "true")
    String current = device.currentValue("switch")?.toString()
    if (!current) {
        dbg "Power preference skipped: current power state is unknown"
        return
    }
    boolean currentlyOn = (current == "on")
    if (wantOn == currentlyOn) {
        dbg "Power preference is already ${current}; not sending"
        return
    }
    log.info "Preference: power ${wantOn ? 'on' : 'off'}"
    if (wantOn) on() else off()
}

private void applyPreferredLaunchApp() {
    String desired = settings?.prefLaunchApp?.toString()?.trim()
    if (isPrefSentinel(desired)) {
        dbg "Launch app preference is unchanged; not sending"
        return
    }
    log.info "Preference: launch app ${desired}"
    launchApp(desired)
    resetEnumPref("prefLaunchApp")
}

private void applyPreferredSendKey() {
    String desired = settings?.prefSendKey?.toString()?.trim()
    if (isPrefSentinel(desired)) {
        dbg "Send key preference is unchanged; not sending"
        return
    }
    log.info "Preference: send key ${desired}"
    sendKey(desired)
    resetEnumPref("prefSendKey")
}

private void resetEnumPref(String name) {
    try {
        device.updateSetting(name, [value: prefUnchanged(), type: "enum"])
    } catch (e) {
        dbg "Could not reset preference ${name}: ${e.message}"
    }
}

private void applyPreferredInput() {
    String desired = settings?.prefInput?.toString()?.trim()
    if (isPrefSentinel(desired)) {
        dbg "Input preference is unchanged; not sending"
        return
    }
    String resolved = resolveInput(desired)
    String current = device.currentValue("input")?.toString()
    if (!resolved) {
        log.warn "Preference input '${desired}' is not a known input; not sending"
        return
    }
    if (resolved == current) {
        dbg "Input preference is already ${current}; not sending"
        return
    }
    log.info "Preference: set input to ${resolved}"
    setInput(resolved)
}

private void applyPreferredPictureMode() {
    String desired = settings?.prefPictureMode?.toString()?.trim()
    if (isPrefSentinel(desired)) {
        dbg "Picture mode preference is unchanged; not sending"
        return
    }
    String resolved = resolvePictureMode(desired)
    if (!resolved) {
        log.warn "Preference picture mode '${desired}' is not a known mode; not sending"
        return
    }
    if (pictureModeIsCurrent(resolved)) {
        dbg "Picture mode preference is already ${resolved}; not sending"
        return
    }
    log.info "Preference: set picture mode to ${resolved}"
    setPictureMode(resolved)
}

private void applyPreferredMute() {
    def desired = settings?.prefMute
    if (desired == null || desired.toString().trim() == "") {
        dbg "Mute preference is unset; not sending"
        return
    }
    boolean wantMuted = (desired == true || desired.toString() == "true")
    String current = device.currentValue("mute")?.toString()
    if (!current) {
        dbg "Mute preference skipped: current mute state is unknown"
        return
    }
    boolean currentlyMuted = (current == "muted")
    if (wantMuted == currentlyMuted) {
        dbg "Mute preference is already ${current}; not sending"
        return
    }
    log.info "Preference: ${wantMuted ? 'mute' : 'unmute'}"
    if (wantMuted) mute() else unmute()
}

private void applyPreferredVolume() {
    def raw = settings?.prefVolume
    if (raw == null || raw.toString().trim() == "") {
        dbg "Volume preference is unset; not sending"
        return
    }
    Integer desired
    try {
        desired = raw.toString().trim().replaceAll(/[, ]/, "").toInteger()
    } catch (ignored) {
        log.warn "Volume preference '${raw}' is not a number; not sending"
        return
    }
    if (desired < 0) desired = 0
    if (desired > 100) desired = 100
    def cur = device.currentValue("volume")
    if (cur == null || cur.toString().trim() == "") {
        dbg "Volume preference skipped: current volume is unknown"
        return
    }
    Integer current
    try {
        current = cur.toString().replaceAll(/[, ]/, "").toInteger()
    } catch (ignored) {
        dbg "Volume preference skipped: current volume '${cur}' is not a number"
        return
    }
    if (desired == current) {
        dbg "Volume preference is already ${current}; not sending"
        return
    }
    log.info "Preference: set volume to ${desired}"
    setVolume(desired)
}

private void applyPreferredScreen() {
    def desired = settings?.prefScreen
    if (desired == null || desired.toString().trim() == "") {
        dbg "Screen preference is unset; not sending"
        return
    }
    boolean wantOn = (desired == true || desired.toString() == "true")
    String current = device.currentValue("screen")?.toString()
    if (!current) {
        dbg "Screen preference skipped: current screen state is unknown"
        return
    }
    boolean currentlyOn = (current == "on")
    if (wantOn == currentlyOn) {
        dbg "Screen preference is already ${current}; not sending"
        return
    }
    log.info "Preference: screen ${wantOn ? 'on' : 'off'}"
    if (wantOn) screenOn() else screenOff()
}

private boolean isPrefSentinel(String value) {
    if (value == null || value == "") return true
    return value == prefUnchanged() || value == prefLoading() || value.startsWith("_")
}

private boolean pictureModeIsCurrent(String resolved) {
    String raw = state.pictureModeRaw?.toString()
    String label = device.currentValue("pictureMode")?.toString()
    if (raw && resolved == raw) return true
    if (label && (resolved.equalsIgnoreCase(label) || pictureModeLabel(resolved)?.equalsIgnoreCase(label))) return true
    return false
}

// ---------------------------------------------------------------------------
// Commands: power and screen
// ---------------------------------------------------------------------------

def on() {
    log.info "Power on"
    sendWol()
    sendControl("powerOn")
}

def off() {
    log.info "Power off"
    sendControl("powerOff")
}

def screenOff() {
    log.info "Screen off"
    sendControl("screenOff")
}

def screenOn() {
    log.info "Screen on"
    sendControl("screenOn")
}

def toggleScreen() {
    if (device.currentValue("screen")?.toString() == "off") {
        screenOn()
    } else if (device.currentValue("screen")?.toString() == "on") {
        screenOff()
    } else {
        log.warn "toggleScreen: current screen state is unknown; not sending"
    }
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

def volumeUp() {
    log.info "Volume up"
    sendControl("volumeStep", 1)
}

def volumeDown() {
    log.info "Volume down"
    sendControl("volumeStep", -1)
}

def mute() {
    log.info "Mute"
    sendControl("mute", true)
}

def unmute() {
    log.info "Unmute"
    sendControl("mute", false)
}

def toggleMute() {
    if (device.currentValue("mute")?.toString() == "muted") {
        unmute()
    } else {
        mute()
    }
}

// ---------------------------------------------------------------------------
// Commands: input, apps, keys, picture
// ---------------------------------------------------------------------------

def setInput(name) {
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
        log.warn "sendKey needs a remote key name (got '${key}')"
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

def refresh() {
    fetchJson("/api/stats", "statsCallback")
    fetchJson("/api/picture", "pictureCallback")
    fetchJson("/api/hdmi", "hdmiCallback")
    fetchJson("/api/remotekeys", "remoteKeysCallback")
}

// ---------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------

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

private void fetchJson(String path, String callback) {
    String uri = apiUrl(path)
    if (!uri) return
    Map params = [
        uri: uri,
        contentType: "application/json",
        timeout: 15,
        headers: ["Accept": "application/json"]
    ]
    dbg "GET ${path}"
    asynchttpGet(callback, params, [path: path])
}

private void sendControl(String action, value = null) {
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
        headers: ["Accept": "application/json", "Content-Type": "application/json"],
        body: body
    ]
    Map data = [action: action]
    if (value != null) {
        data.value = value
    }
    dbg "POST /api/control ${body}"
    if (action == "volume" || action == "volumeStep") {
        log.info "POST /api/control ${body}"
    }
    asynchttpPost("controlCallback", params, data)
}

void controlCallback(resp, data) {
    String action = data?.action ?: "control"
    if (!resp) {
        log.warn "Glasshouse ${action}: empty response"
        clearVolumeStepChain()
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
        log.warn "Glasshouse ${action} rejected: ${json.error ?: json}"
        clearVolumeStepChain()
        return
    }
    if (action == "volume" || action == "volumeStep") {
        log.info "Glasshouse ${action} accepted (ok:true); volume will update from the TV read-back"
    } else {
        dbg "Glasshouse ${action} ok"
    }
    // Volume is never applied optimistically: only applyStats (the TV
    // read-back) may set the volume attribute. Other actions still get
    // a prompt UI update, then refresh confirms.
    if (action == "volumeStep" && continueArcVolumeSteps()) {
        return
    }
    if (action != "volume" && action != "volumeStep") {
        applyOptimistic(data)
    }
    runIn(2, "refresh")
}

private void applyOptimistic(Map data) {
    if (!data) return
    String action = data.action
    def value = data.value
    if (action == "mute") {
        boolean muted = (value == true || value == "true")
        sendEvent(name: "mute", value: muted ? "muted" : "unmuted",
            descriptionText: "${device.displayName} is ${muted ? 'muted' : 'unmuted'}")
    } else if (action == "powerOn") {
        sendEvent(name: "switch", value: "on",
            descriptionText: "${device.displayName} is on")
    } else if (action == "powerOff") {
        sendEvent(name: "switch", value: "off",
            descriptionText: "${device.displayName} is off")
        sendEvent(name: "screen", value: "off",
            descriptionText: "${device.displayName} screen is off")
    } else if (action == "screenOn") {
        sendEvent(name: "screen", value: "on",
            descriptionText: "${device.displayName} screen is on")
    } else if (action == "screenOff") {
        sendEvent(name: "screen", value: "off",
            descriptionText: "${device.displayName} screen is off")
    } else if (action == "input" && value) {
        sendEvent(name: "input", value: value.toString(),
            descriptionText: "${device.displayName} input is ${value}")
    } else if (action == "pictureMode" && value) {
        String label = pictureModeLabel(value.toString()) ?: value.toString()
        sendEvent(name: "pictureMode", value: label,
            descriptionText: "${device.displayName} picture mode is ${label}")
    } else if (action == "launchApp" && value) {
        sendEvent(name: "currentApp", value: value.toString(),
            descriptionText: "${device.displayName} app is ${value}")
    }
}

void statsCallback(resp, data) {
    def json = requireJson(resp, "stats")
    if (!(json instanceof Map)) return
    state.failCount = 0
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

void remoteKeysCallback(resp, data) {
    def json = requireJson(resp, "remotekeys")
    if (!(json instanceof Map)) return
    applyRemoteKeys(json)
}

private def requireJson(resp, String label) {
    if (!resp) {
        notePollFailure("${label}: empty response")
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
    sendEvent(name: "switch", value: sw,
        descriptionText: "${device.displayName} is ${sw}")
    syncBoolPref("prefPower", systemOn)

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
            sendEvent(name: "volume", value: v, unit: "%",
                descriptionText: "${device.displayName} volume is ${v}%")
            syncNumberPref("prefVolume", v)
        } catch (ignored) {}
    }
    if (d.containsKey("muted")) {
        String muteVal = (d.muted == true) ? "muted" : "unmuted"
        sendEvent(name: "mute", value: muteVal,
            descriptionText: "${device.displayName} is ${muteVal}")
        syncBoolPref("prefMute", d.muted == true)
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
    sendEvent(name: "screen", value: screenVal,
        descriptionText: "${device.displayName} screen is ${screenVal}")
    syncBoolPref("prefScreen", screenOn)

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
    if (inputVal) {
        String label = inputs[inputVal] ?: inputVal
        sendEvent(name: "input", value: inputVal,
            descriptionText: "${device.displayName} input is ${label} (${inputVal})")
    } else if (appKey) {
        sendEvent(name: "input", value: appKey,
            descriptionText: "${device.displayName} source is ${appKey}")
    }

    if (title) {
        sendEvent(name: "currentApp", value: title,
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

    dbg "State: switch=${sw} volume=${d.volume} muted=${d.muted} input=${inputVal} app=${title}"
}

private void applyPicture(Map d) {
    Map row = pictureModeRow(d)
    if (!row) {
        dbg "No pictureMode row in /api/picture"
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
        sendEvent(name: "supportedPictureModes", value: JsonOutput.toJson(modes),
            descriptionText: "${device.displayName} has ${modes.size()} picture mode(s)")
    }
    String raw = row.value?.toString()
    if (raw) {
        state.pictureModeRaw = raw
        String label = pictureModeLabel(raw) ?: raw
        sendEvent(name: "pictureMode", value: label,
            descriptionText: "${device.displayName} picture mode is ${label}")
    }
    dbg "Picture modes: ${modes.collect { it.label ?: it.value }.join(', ')} (current ${raw})"
}

private void applyPictureFromStats(Map picture) {
    if (picture.modes instanceof List) {
        List modes = picture.modes.findAll { it instanceof Map }.collect { Map m ->
            [value: m.value?.toString(), label: m.label?.toString()]
        }
        if (modes) {
            state.pictureModes = modes
            sendEvent(name: "supportedPictureModes", value: JsonOutput.toJson(modes),
                descriptionText: "${device.displayName} has ${modes.size()} picture mode(s)")
        }
    }
    String modeRaw = picture.mode_raw?.toString()
    String modeLabel = picture.mode?.toString()
    if (modeRaw) state.pictureModeRaw = modeRaw
    if (modeLabel) {
        sendEvent(name: "pictureMode", value: modeLabel,
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
    dbg "Inputs: ${list.collect { it.label + ' (' + it.id + ')' }.join(', ')}"
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
    sendEvent(name: "supportedInputs", value: JsonOutput.toJson(list),
        descriptionText: "${device.displayName} has ${list.size()} input(s)")
}

// Build the Send key dropdown: always offer navigation and media,
// then any extra keys from GET /api/remotekeys. Skip remap
// "choices" that would launch an app, change input, or power off
// the TV (those have their own preferences).
private void applyRemoteKeys(Map d) {
    List list = []
    Map seen = [:]
    builtinSendKeys().each { Map item ->
        String id = item.id?.toString()
        if (!id || seen[id]) return
        seen[id] = true
        list << [id: id, label: (item.label ?: id).toString()]
    }
    if (d.keys instanceof List) {
        d.keys.each { def raw ->
            if (!(raw instanceof Map)) return
            Map k = (Map) raw
            String key = k.key?.toString()
            if (!key) return
            String id = "lg:${key}"
            if (seen[id] || seen[key]) return
            seen[id] = true
            String title = k.title?.toString() ?: key
            list << [id: id, label: title]
        }
    }
    if (d.choices instanceof List) {
        d.choices.each { def raw ->
            if (!(raw instanceof Map)) return
            Map c = (Map) raw
            String id = c.id?.toString()
            String kind = c.kind?.toString()
            if (!id) return
            // action includes powerOff / screenOff; input and app
            // are covered by other preferences.
            if (kind == "action" || kind == "input" || kind == "app") return
            if (seen[id]) return
            seen[id] = true
            list << [id: id, label: (c.title ?: id).toString()]
        }
    }
    state.remoteKeys = list
    dbg "Remote keys: ${list.size()}"
}

private void notePollFailure(String reason) {
    Integer n = (state.failCount ?: 0) + 1
    state.failCount = n
    // Do not flip switch to off on a failed poll: a network blip must
    // not fire rules. Log the first failure, then every 10th.
    if (n == 1 || (n % 10) == 0) {
        log.warn "Glasshouse request failed (${n} time${n == 1 ? '' : 's'}): ${reason}"
    } else {
        dbg "Glasshouse request failed (${n}): ${reason}"
    }
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
        dbg "JSON parse failed: ${e.message}"
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
    dbg "Sending Wake-on-LAN to ${mac}"
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

    if (lower.startsWith("media:")) {
        String rest = raw.substring(6)
        String mapped = mediaAction(rest)
        return mapped ? [action: "media", value: mapped] : [action: "media", value: rest]
    }
    if (lower.startsWith("rcu:")) {
        return [action: "rcu", value: rcuKey(raw.substring(4))]
    }
    if (lower.startsWith("lg:")) {
        String rest = raw.substring(3).trim()
        if (!rest) return null
        return [action: "lg", value: rest]
    }

    String media = mediaAction(lower)
    if (media) return [action: "media", value: media]

    return [action: "rcu", value: rcuKey(lower)]
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
        volumeup: "volumeUp",
        volumedown: "volumeDown",
        mute: "mute"
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

private void dbg(msg) {
    if (debugLogging) log.debug msg
}

// Keep the mute and screen toggles in the preferences pane in step with
// the TV, so a later Save Preferences does not fire a change the user
// did not make. device.updateSetting does not call updated().
private void syncBoolPref(String name, boolean value) {
    def cur = settings?."${name}"
    boolean already = (cur == true || cur?.toString() == "true")
    if (cur != null && already == value) return
    try {
        device.updateSetting(name, [value: value ? "true" : "false", type: "bool"])
    } catch (e) {
        dbg "Could not sync preference ${name}: ${e.message}"
    }
}

// On HDMI ARC/eARC (or when Glasshouse reports volume_control
// "steps"), also send volumeStep so a receiver that ignores the TV's
// own level can still follow. Each step is +/- 1, same as the
// dashboard's + / - buttons. Capped so a wild slider jump cannot
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
    state.volumeStepTarget = desired
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

// Keep the Volume preference in step with the TV, so a later Save
// Preferences does not fire a change the user did not make.
// device.updateSetting does not call updated().
private void syncNumberPref(String name, Integer value) {
    def cur = settings?."${name}"
    Integer already = null
    try {
        if (cur != null && cur.toString().trim() != "") {
            already = cur.toString().trim().replaceAll(/[, ]/, "").toInteger()
        }
    } catch (ignored) {}
    if (already != null && already == value) return
    try {
        device.updateSetting(name, [value: value.toString(), type: "number"])
    } catch (e) {
        dbg "Could not sync preference ${name}: ${e.message}"
    }
}
