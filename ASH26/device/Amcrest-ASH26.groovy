/*
 *  Amcrest ASH26
 *  Enable control of lights, night vision, motion detection, and other settings
 *  only available through Amcrest mobile app.
 *
 *  v0.5 - format digest `nc` as RFC 2617 8-digit hex (was decimal int, which
 *         Amcrest accepted but stricter servers would reject), and chain
 *         on()/off()'s two SetConfig calls sequentially via the response
 *         handler instead of firing them concurrently.
 *  v0.4 - converted all HTTP to asynchttpGet so the 1-minute refresh loop no
 *         longer blocks the Hubitat scheduler for up to 10 seconds per call
 *         when the camera is offline. Added installed()/updated() lifecycle
 *         methods so preference saves actually re-schedule the poll. Replaced
 *         the single-element setting loop with a direct call. Added `def` to
 *         digest_header/digest_map so they're locals rather than script-level
 *         bindings.
 *  v0.3 - fixed two latent bugs: response.status NPE in set_camera_setting
 *         success path, and params[uri] passing null into the digest-auth
 *         HA2 hash (Amcrest's lax validator masked it).
 *  v0.2 - added debug logging option
 *  v0.1 - initial release
 *
 *  Copyright 2021 David LaPorte
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */

metadata {

  definition(name: "Amcrest ASH26", namespace: "dlaporte", author: "dlaporte") {
    capability "Initialize"
    capability "Refresh"
    capability "Switch"

    attribute "light_status", "string"
    attribute "device_name", "string"
    attribute "serial_number", "string"
    attribute "firmware_version", "string"
    attribute "mac_address", "string"
    attribute "wireless_ssid", "string"
  }

  preferences {
    input("ip", "text", title: "IP Address", required: true)
    input("username", "text", title: "ASH26 Username", required: true)
    input("password", "password", title: "ASH26 Password", required: true)
    input "debug", "bool", required: false, defaultValue: false, title: "Debug Logging"
  }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

def installed() {
  log.info "ASH26: installed()"
  initialize()
}

def updated() {
  log.info "ASH26: updated()"
  initialize()
}

def initialize() {
  unschedule()
  if (!ip || !username || !password) {
    log.error "ASH26: required fields not completed, please complete for proper operation."
    return
  }
  runEvery1Minute("refresh")
  refresh()
}

// ---------------------------------------------------------------------------
// Switch + refresh entry points
// ---------------------------------------------------------------------------

def refresh() {
  if (debug) log.debug "ASH26: refresh()"
  get_camera_settings()
}

def on() {
  if (debug) log.debug "ASH26: on()"
  // Order matters: disable alarm-lighting *first*, then set manual mode.
  // With the previous parallel firing, the camera could observe the two
  // sets in arbitrary order.
  setSequence([["AlarmLighting[0][0].Enable", "false"], ["Lighting_V2[0][0][1].Mode", "Manual"]])
}

def off() {
  if (debug) log.debug "ASH26: off()"
  setSequence([["AlarmLighting[0][0].Enable", "true"], ["Lighting_V2[0][0][1].Mode", "Off"]])
}

private setSequence(List remaining) {
  if (!remaining) return
  def head = remaining[0]
  def rest = remaining.size() > 1 ? remaining[1..-1] : []
  set_camera_setting(head[0], head[1], [chainRest: rest])
}

// ---------------------------------------------------------------------------
// Camera operations (async)
//
// Pattern: every Amcrest CGI call returns 401 with WWW-Authenticate on the
// first hit, then 200 once we resend with the digest Authorization header.
// We model that as a two-step async flow:
//   1. asynchttpGet(unauthRequestHandler, ...) — unauthenticated probe
//   2. unauthRequestHandler computes digest, asynchttpGet(authedRequestHandler)
//   3. authedRequestHandler dispatches to the per-operation parser via the
//      finalCallback name passed through userData.
// ---------------------------------------------------------------------------

private get_camera_settings() {
  String uri = "/cgi-bin/configManager.cgi?action=getConfig&name=All"
  def params = [
      uri: "http://${ip}${uri}",
      contentType: "text/plain",
      timeout: 10
  ]
  asynchttpGet("unauthRequestHandler", params, [
      uri: uri,
      finalCallback: "parseCameraSettings"
  ])
}

private set_camera_setting(String name, String value, Map extra = [:]) {
  String uri = "/cgi-bin/configManager.cgi?action=setConfig&${name}=${value}"
  def params = [
      uri: "http://${ip}${uri}",
      contentType: "text/plain",
      timeout: 10
  ]
  Map userData = [
      uri: uri,
      finalCallback: "verifySetResponse",
      settingName: name,
      settingValue: value
  ]
  if (extra) userData.putAll(extra)
  asynchttpGet("unauthRequestHandler", params, userData)
}

// First callback: expects a 401, parses the WWW-Authenticate challenge,
// then re-issues the same request with an Authorization header.
def unauthRequestHandler(response, data) {
  if (!response.hasError() && response.status == 200) {
    // Unusual: a previously-cached connection let us through without auth.
    // Hand straight off to the operation parser.
    if (data.finalCallback) "${data.finalCallback}"(response, data)
    return
  }
  if (response.status != 401) {
    log.error "ASH26: unexpected status ${response.status} for ${data.uri}"
    return
  }

  def wwwAuth = extractHeader(response, "WWW-Authenticate")
  if (!wwwAuth) {
    log.error "ASH26: 401 without WWW-Authenticate header"
    return
  }
  def digestMap = parseDigestChallenge(wwwAuth)
  String authHeader = calcDigestAuth(data.uri, "MD5", digestMap)

  def params = [
      uri: "http://${ip}${data.uri}",
      contentType: "text/plain",
      headers: ["Authorization": authHeader],
      timeout: 10
  ]
  asynchttpGet("authedRequestHandler", params, data)
}

// Second callback: dispatches the (now-authenticated) response to the
// operation's parser by the name passed in through data.finalCallback.
def authedRequestHandler(response, data) {
  if (response.hasError() || response.status != 200) {
    log.error "ASH26: authed request failed for ${data.uri}: HTTP ${response.status}"
    return
  }
  if (data.finalCallback) {
    "${data.finalCallback}"(response, data)
  }
}

def parseCameraSettings(response, data) {
  def camera_settings = [: ]
  def text = response.data ?: ""
  text.eachLine { line ->
    def parts = line.split("=", 2)
    if (parts.length == 2) {
      camera_settings.put(parts[0].trim(), parts[1].trim())
    }
  }

  sendEvent(name: "device_name",       value: camera_settings['table.All.General.MachineName'])
  sendEvent(name: "serial_number",     value: camera_settings['table.All.VSP_PaaS.SN'])
  sendEvent(name: "firmware_version",  value: camera_settings['table.All.PaaS_UPGRADE.LastVersion'])
  sendEvent(name: "mac_address",       value: camera_settings['table.All.Network.eth2.PhysicalAddress'])
  sendEvent(name: "wireless_ssid",     value: camera_settings['table.All.WLan.eth2.SSID'])

  def mode = camera_settings['table.All.Lighting_V2[0][0][1].Mode']
  if (mode == "Manual") {
    sendEvent(name: "light_status", value: "on")
    sendEvent(name: "switch",       value: "on")
  } else if (mode == "Off") {
    sendEvent(name: "light_status", value: "off")
    sendEvent(name: "switch",       value: "off")
  }
}

def verifySetResponse(response, data) {
  String text = (response.data ?: "").toString().trim()
  if (text == "OK") {
    if (debug) log.debug "ASH26: set ${data.settingName} = ${data.settingValue}"
    if (data.chainRest) setSequence(data.chainRest as List)
  } else {
    log.error "ASH26: set ${data.settingName} failed: ${text}"
  }
}

// ---------------------------------------------------------------------------
// Digest auth helpers
// ---------------------------------------------------------------------------

private String extractHeader(response, String name) {
  def headers = response.headers
  if (!headers) return null
  String lname = name.toLowerCase()
  def entry = headers.find { it.key?.toLowerCase() == lname }
  return entry?.value?.toString()
}

private Map parseDigestChallenge(String wwwAuth) {
  // "Digest realm=\"foo\", nonce=\"bar\", qop=\"auth\", opaque=\"xyz\""
  String stripped = wwwAuth.replaceFirst(/(?i)^\s*Digest\s+/, "")
  Map result = [:]
  stripped.split(",").each { part ->
    def kv = part.split("=", 2)
    if (kv.length == 2) {
      result[kv[0].trim()] = kv[1].trim().replaceAll(/^"|"$/, "")
    }
  }
  return result
}

private String hash(String algorithm, String text) {
  return java.security.MessageDigest.getInstance(algorithm).digest(text.bytes).collect {
    String.format("%02x", it)
  }.join('')
}

private String calcDigestAuth(String uri, String algorithm, Map headers) {
  String HA1 = "${username}:${headers.realm.trim()}:${password}"
  String HA1_hash = hash(algorithm, HA1)

  String HA2 = "GET:${uri}"
  String HA2_hash = hash(algorithm, HA2)

  // Each request gets a fresh nonce challenge from the server, so reuse
  // protection isn't actually relying on this counter — but RFC 2617 §3.2.2
  // still requires the wire format to be exactly 8 lowercase hex digits.
  state.nc = (state.nc ?: 0) + 1
  String nc = String.format("%08x", (state.nc as Integer))

  String cnonce = java.util.UUID.randomUUID().toString().replaceAll('-', '').substring(0, 8)
  String response = "${HA1_hash}:${headers.nonce}:${nc}:${cnonce}:auth:${HA2_hash}"
  String response_enc = hash(algorithm, response)

  String eol = " "
  return 'Digest username="' + username + '",' + eol +
    'realm="' + headers.realm + '",' + eol +
    'qop="' + headers.qop + '",' + eol +
    'algorithm="' + algorithm + '",' + eol +
    'uri="' + uri + '",' + eol +
    'nonce="' + headers.nonce + '",' + eol +
    'cnonce="' + cnonce + '",' + eol +
    'opaque="' + (headers.opaque ?: "") + '",' + eol +
    'nc=' + nc + ',' + eol +
    'response="' + response_enc + '"'
}
