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
    attribute "device_latitude", "decimal"
    attribute "device_longitude", "decimal"
    attribute "device_elevation", "decimal"
    attribute "device_timezone", "string"
    attribute "device_country", "string"
    attribute "device_name", "string"
    attribute "device_model", "string"
    attribute "device_status", "string"
    attribute "device_battery_level", "string"
    attribute "device_signal_strength", "number"
    attribute "device_last_checkin", "string"

    attribute "daily_temperature_high", "decimal"
    attribute "daily_temperature_high_time", "date"
    attribute "daily_temperature_low", "decimal"
    attribute "daily_temperature_low_time", "date"
    attribute "daily_lightning_strikes", "number"
    attribute "daily_measured_light", "number"

    attribute "temperature", "decimal"
    attribute "humidity", "decimal"
    attribute "wind_speed", "decimal"
    attribute "wind_degree", "decimal"
    attribute "wind_direction", "string"
    attribute "dew_point", "decimal"

    attribute "wind_chill", "decimal"

    attribute "pressure", "decimal"
    attribute "pressure_unit", "string"

    attribute "rainfall", "decimal"

    attribute "wind_speed_average", "decimal"
    attribute "wind_speed_average_unit", "string"

    attribute "uv_index", "decimal"

    attribute "light_intensity", "decimal"
    attribute "light_intensity_unit", "string"

    attribute "measured_light", "decimal"
    attribute "measured_light_unit", "string"

    attribute "lightning_count", "number"
    attribute "lightning_closest_distance", "decimal"
    attribute "lightning_last_distance", "decimal"
  }

  preferences() {
    section("Query Inputs") {
      input "acurite_username", "text", required: true, title: "AcuRite Username"
      input "acurite_password", "text", required: true, title: "AcuRite Password"
      input "device_id", "text", required: true, title: "Device ID"
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
          log.debug "data: ${data_resp.data}"

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

          sendEvent(name: "daily_temperature_high", value: data.devices[0].temp_high_value, unit: data.devices[0].sensors[0].chart_unit)
          sendEvent(name: "daily_temperature_high_time", value: data.devices[0].temp_high_at)
          sendEvent(name: "daily_temperature_low", value: data.devices[0].temp_low_value, unit: data.devices[0].sensors[0].chart_unit)
          sendEvent(name: "daily_temperature_low_time", value: data.devices[0].temp_low_at)
          sendEvent(name: "daily_lightning_strikes", value: data.devices[0].daily_cumulative_strikes)
          sendEvent(name: "daily_measured_light", value: data.devices[0].daily_cumulative_measured_light)
          sendEvent(name: "temperature", value: data.devices[0].sensors[0].last_reading_value, unit: data.devices[0].sensors[0].chart_unit)
          sendEvent(name: "humidity", value: data.devices[0].sensors[1].last_reading_value, unit: data.devices[0].sensors[1].chart_unit)
          sendEvent(name: "wind_speed", value: data.devices[0].sensors[2].last_reading_value, unit: data.devices[0].sensors[2].chart_unit)
          sendEvent(name: "wind_degree", value: data.devices[0].sensors[3].last_reading_value)
          sendEvent(name: "wind_direction", value: data.devices[0].sensors[3].wind_direction.abbreviation)
          sendEvent(name: "dew_point", value: data.devices[0].sensors[4].last_reading_value, unit: data.devices[0].sensors[4].chart_unit)
          sendEvent(name: "wind_chill", value: data.devices[0].sensors[5].last_reading_value, unit: data.devices[0].sensors[5].chart_unit)
          sendEvent(name: "pressure", value: data.devices[0].sensors[6].last_reading_value, unit: data.devices[0].sensors[6].chart_unit)
          sendEvent(name: "rainfall", value: data.devices[0].sensors[7].last_reading_value, unit: data.devices[0].sensors[7].chart_unit)
          sendEvent(name: "wind_speed_average", value: data.devices[0].sensors[8].last_reading_value, unit: data.devices[0].sensors[8].chart_unit)
          sendEvent(name: "uv_index", value: data.devices[0].sensors[9].last_reading_value)
          sendEvent(name: "illuminance", value: data.devices[0].sensors[10].last_reading_value, unit: data.devices[0].sensors[10].chart_unit)
          sendEvent(name: "measured_light", value: data.devices[0].sensors[11].last_reading_value, unit: data.devices[0].sensors[11].chart_unit)

          sendEvent(name: "lightning_count", value: data.devices[0].wired_sensors[0].last_reading_value)
          sendEvent(name: "lightning_closest_distance", value: data.devices[0].wired_sensors[1].last_reading_value, unit: data.devices[0].wired_sensors[1].chart_unit)
          sendEvent(name: "lightning_last_distance", value: data.devices[0].wired_sensors[2].last_reading_value, unit: data.devices[0].wired_sensors[2].chart_unit)

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
  runEvery5Minutes("poll")
}
