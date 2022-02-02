/*
 *  Amcrest ASH26
 * Enable control of lights, night vision, motion detection, and other settings only available through Amcrest mobile app
 *
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
    input "debug", "bool", required: true, defaultValue: false, title: "Debug Logging"
  }
}

def refresh() {
  if (debug) log.debug "ASH26: refresh() called"
  get_camera_settings()
}

def hash(algorithm, text) {
  return new String(java.security.MessageDigest.getInstance(algorithm).digest(text.bytes).collect {
    String.format("%02x", it)
  }.join(''))
}

private String calcDigestAuth(uri, algorithm, headers) {

  def HA1 = new String(username + ":" + headers.realm.trim() + ":" + password)
  def HA1_hash = hash(algorithm, HA1)

  def HA2 = new String("GET:" + uri)
  def HA2_hash = hash(algorithm, HA2)

  // increase nc every request by one
  if (!state.nc) {
    state.nc = 1
  } else {
    state.nc = state.nc + 1
  }

  def cnonce = java.util.UUID.randomUUID().toString().replaceAll('-', '').substring(0, 8)
  def response = new String(HA1_hash + ":" + headers.nonce + ":" + state.nc + ":" + cnonce + ":" + "auth:" + HA2_hash)
  def response_enc = new String(hash(algorithm, response))

  def eol = " "

  return new String('Digest username="' + username + '",' + eol +
    'realm="' + headers.realm + '",' + eol +
    'qop="' + headers.qop + '",' + eol +
    'algorithm="' + algorithm + '",' + eol +
    'uri="' + uri + '",' + eol +
    'nonce="' + headers.nonce + '",' + eol +
    'cnonce="' + cnonce + '",' + eol +
    'opaque="' + headers.opaque + '",' + eol +
    'nc=' + state.nc + ',' + eol +
    'response="' + response_enc + '"')
}

def get_camera_settings() {

  def camera_settings = [: ]
  def url = "http://${ip}"
  def uri = "/cgi-bin/configManager.cgi?action=getConfig&name="

  for (setting in ["All"]) {
    def params = [
      uri: url + uri + setting,
      contentType: 'text/plain'
    ]
    if (debug) log.debug "ASH26: params: " + params
    try {
      httpGet(params) {
        resp ->
          if (debug) log.debug "ASH26: get_camera_settings response status: ${resp.status}"
      }
    } catch (groovyx.net.http.HttpResponseException e1) {
      if (e1.response.status == 401) {

        digest_header = e1.response.getHeaders('WWW-Authenticate').toString()
        digest_map = stringToMap(digest_header.replaceAll("WWW-Authenticate: Digest ", "").replaceAll("=", ":").replaceAll("\"", ""))
        params.put("headers", ["Authorization": calcDigestAuth(params[uri], "MD5", digest_map)])
        if (debug) log.debug "ASH26: get_camera_settings follow-up params: " + params
        try {
          httpGet(params) {
            resp ->
              if (debug) log.debug "ASH26: get_camera_settings follow-up response status: ${resp.status}"
            resp.data.text.splitEachLine("=") {
              items ->
                if (items.size() == 2) {
                  camera_settings.put(items[0].trim(), items[1].trim())
                  //if (debug) log.debug "ASH26: get_camera_settings data: " + items[0].trim() + "=" + items[1].trim()
                }
            }
          }
        } catch (groovyx.net.http.HttpResponseException e2) {
          log.error "ASH26: get_camera_settings failed: " + e2.response.status
        }
      }
    }
  }

  sendEvent(name: "device_name", value: camera_settings['table.All.General.MachineName'])
  sendEvent(name: "serial_number", value: camera_settings['table.All.VSP_PaaS.SN'])
  sendEvent(name: "firmware_version", value: camera_settings['table.All.PaaS_UPGRADE.LastVersion'])
  sendEvent(name: "mac_address", value: camera_settings['table.All.Network.eth2.PhysicalAddress'])
  sendEvent(name: "wireless_ssid", value: camera_settings['table.All.WLan.eth2.SSID'])

  if (camera_settings['table.All.Lighting_V2[0][0][1].Mode'] == "Manual") {
    sendEvent(name: "light_status", value: "on")
  } else if (camera_settings['table.All.Lighting_V2[0][0][1].Mode'] == "Off") {
    sendEvent(name: "light_status", value: "off")
  }
}

def set_camera_setting(setting, value) {

  def url = "http://${ip}"
  def uri = "/cgi-bin/configManager.cgi?action=setConfig&"

  def params = [
    uri: url + uri + setting + "=" + value,
    contentType: 'text/plain'
  ]

  try {
    httpGet(params) {
      resp ->
        if (debug) log.debug "ASH26: get_camera_settings response status: " + response.status
    }
  } catch (groovyx.net.http.HttpResponseException e1) {
    if (e1.response.status == 401) {

      digest_header = e1.response.getHeaders('WWW-Authenticate').toString()
      digest_map = stringToMap(digest_header.replaceAll("WWW-Authenticate: Digest ", "").replaceAll("=", ":").replaceAll("\"", ""))
      params.put("headers", ["Authorization": calcDigestAuth(params[uri], "MD5", digest_map)])
      if (debug) log.debug "ASH26: set_camera_settings follow-up params: " + params
      try {
        httpGet(params) {
          resp ->
            if (resp.data.text.trim() != ("OK")) {
              log.error "ASH26: set_camera_setting: error setting " + setting + ": " + resp.data.text
            } else {
              if (debug) log.debug "ASH26: set_camera_setting: " + setting + "=" + value
            }
        }
      } catch (groovyx.net.http.HttpResponseException e2) {
        log.error "ASH26: set_camera_setting failed: " + e2.response.status
      }
    }
  }
}

def on() {
  if (debug) log.debug "ASH26: on() called"
  set_camera_setting("AlarmLighting[0][0].Enable", "false")
  set_camera_setting("Lighting_V2[0][0][1].Mode", "Manual")
}

def off() {
  if (debug) log.debug "ASH26: off() called"
  set_camera_setting("AlarmLighting[0][0].Enable", "true")
  set_camera_setting("Lighting_V2[0][0][1].Mode", "Off")
}

def initialize() {
  unschedule()
  log.info "ASH26: initialize() called"

  if (!ip || !username || !password) {
    log.error "ASH26: required fields not completed, please complete for proper operation."
    return
  }
  runEvery1Minute("refresh")
}
