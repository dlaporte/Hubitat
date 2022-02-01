/**
 * AcuRite Weather Station
 *
 *  David LaPorte
 *  Based on this helpful thread:
 *    https://community.smartthings.com/t/my-very-quick-and-dirty-integration-with-myacurite-smart-hub-st-webcore/97749
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
 *  Last Update 01/31/2022
 *
 *
 *  v0.0.3 - fixed typo
 *  v0.0.2 - adding polling interval, removed assumptions about sensor counts
 *  v0.0.1 - initial release
 *
 */

metadata {
  definition(name: "AcuRite Weather Station", namespace: "dlaporte", author: "David LaPorte") {
    capability "Actuator"
    capability "Sensor"
    capability "Temperature Measurement"
    capability "Pressure Measurement"
    capability "Illuminance Measurement"
    capability "Relative Humidity Measurement"
    capability "Initialize"
    capability "Polling"
    capability "Battery"

    attribute "account_id", "number"
    attribute "device_id", "number"
    attribute "poll_interval", "enum"
  }

  preferences() {
    section("Query Inputs") {
      input "acurite_username", "text", required: true, title: "AcuRite Username"
      input "acurite_password", "password", required: true, title: "AcuRite Password"
      input "device_id", "text", required: true, title: "Device ID", description: "Your Device ID can be found looking for 'hubs' in the Network section of Chrome's Developer Tools while loading the MyAcurite dashboard"
      input "poll_interval", "enum", title: "Poll Interval:", required: false, defaultValue: "5 Minutes", options: ["5 Minutes", "10 Minutes", "15 Minutes", "30 Minutes", "1 Hour", "3 Hours"]
    }
  }
}

def poll() {
  log.info "AcuRite: poll() called"
  get_acurite_data()
}

def get_acurite_data() {
  def login_params = [
    uri: "https://marapi.myacurite.com",
    path: "/users/login",
    body: [
      "remember": true,
      "email": "${acurite_username}",
      "password": "${acurite_password}"
    ]
  ]
  log.debug(login_params)

  try {
    httpPostJson(login_params) {
      login_resp ->
        def token_id = login_resp.data.token_id

      def account_id = login_resp.data.user.account_users[0].account_id

      def data_params = [
        uri: "https://marapi.myacurite.com",
        path: "/accounts/${account_id}/dashboard/hubs/${device_id}",
        headers: [
          "x-one-vue-token": "${token_id}"
        ]
      ]

      log.debug(data_params)

      try {
        httpGet(data_params) {
          data_resp ->
            log.debug "AcuRite: data response status: ${data_resp.status}"
          //log.debug "data: ${data_resp.data}"

          def data = data_resp.data

          sendEvent(name: "location_name", value: data.name)
          sendEvent(name: "location_latitude", value: data.latitude)
          sendEvent(name: "location_longitude", value: data.longitude)
          sendEvent(name: "location_elevation", value: data.elevation, unit: data.elevation_unit)
          sendEvent(name: "location_timezone", value: data.timezone)
          sendEvent(name: "device_country", value: data.country)

          sendEvent(name: "device_name", value: data.devices[0].name)
          sendEvent(name: "device_model", value: data.devices[0].model_code)
          sendEvent(name: "device_status", value: data.devices[0].status_code)

          sendEvent(name: "device_battery_level", value: data.devices[0].battery_level)

          if (data.devices[0].battery_level == "Normal") {
            battery = 100
          } else {
            battery = 0
          }
          sendEvent(name: "battery", value: battery)

          sendEvent(name: "device_signal_strength", value: data.devices[0].signal_strength)
          sendEvent(name: "device_last_checkin", value: data.devices[0].last_check_in_at)

          for (sensor in [data.devices[0].sensors, data.devices[0].wired_sensors].flatten()) {
            def sensor_name = sensor.sensor_name.replaceAll(" ", "_").toLowerCase()
            def sensor_value = sensor.last_reading_value
            def sensor_unit = sensor.chart_unit
            if (sensor_unit) {
              sensor_unit = sensor.chart_unit
              sendEvent(name: "${sensor_name}", value: "${sensor_value}", unit: "${sensor_unit}")
            } else {
              sendEvent(name: "${sensor_name}", value: "${sensor_value}")
            }
            if (sensor_name == "wind_direction") {
              sendEvent(name: "wind_direction_abbreviation", value: "${sensor.wind_direction.abbreviation}")
              sendEvent(name: "wind_direction_point", value: "${sensor.wind_direction.point}")
            }
            if (sensor_name == "light_intensity") {
              sendEvent(name: "illuminance", value: "${sensor_value}", unit: "${sensor_unit}")
            }
          }
        }
      } catch (groovyx.net.http.HttpResponseException e2) {
        log.error "AcuRite: data failed: " + e2.response.status + ": " + e2.response.data
      }

    }
  } catch (groovyx.net.http.HttpResponseException e1) {
    log.error "AcuRite: login failed: " + e1.response.status
  }
}

def initialize() {
  unschedule()
  log.info "AcuRite: initialize() called"

  if (!acurite_username || !acurite_password || !device_id) {
    log.warn "AcuRite required fields not completed.  Please complete for proper operation."
    return
  }
  def poll_interval_cmd = (settings?.pollInterval ?: "5 Minutes").replace(" ", "")
  "runEvery${pollIntervalCmd}"(pollSchedule)
}
