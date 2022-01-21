/**
 *  eMotorWerks JuiceBox
 *
 *  Version - 0.1
 *
 *  Copyright 2017 David LaPorte
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

preferences {

	input(name: "id", type: "string", title: "Device ID", required: true, displayDuringSetup: true, capitalization: none)
    input(name: "pin", type: "string", title: "Device PIN", required: true, displayDuringSetup: true, capitalization: none)
    
	input(name: "amperage", type: "number", title: "Maximum Charge Rate (A)", required: true)
	
    input(title: "", description: "Time of Use", type: "paragraph", element: "paragraph")

    input(name: "tou", type: "bool", title: "Enable", required: true, defaultValue: false)
	input(name: "weekday_tou_start", type: "string", title: "Weekday TOU Start (HH:MM)", required: false)
	input(name: "weekday_tou_end", type: "string", title: "Weekday TOU End (HH:MM)", required: false)
	input(name: "weekend_tou_start", type: "string", title: "Weekend TOU Start (HH:MM)", required: false)
	input(name: "weekend_tou_end", type: "string", title: "Weekend TOU End (HH:MM)", required: false)
}


metadata {
    definition (name: "eMotorWerks JuiceBox", namespace: "dlaporte", author: "David LaPorte") {
        capability "Energy Meter"
	    capability "Power Meter"
        capability "Configuration"
        capability "Polling"
        capability "Sensor"
        capability "Refresh"

        attribute "currentState", "string"
        attribute "amps", "number"
        attribute "kwh", "number"
        attribute "seconds", "number"
        
    }

    simulator {
    }

	tiles(scale: 2) {

		valueTile("currentState", "device.currentState") {
				state("standby", label: '${currentValue}',
					icon: "https://raw.githubusercontent.com/dlaporte/SmartThings/master/DeviceHandlers/juicebox-evse/images/evse-icon.png",
                    backgroundColor: "#7bb630"
				)
				state("plugged", label: '${currentValue}',
					icon: "https://raw.githubusercontent.com/dlaporte/SmartThings/master/DeviceHandlers/juicebox-evse/images/evse-icon.png",
                    backgroundColor: "#7bb630"
				)
				state("charging", label: '${currentValue}',
					icon: "https://raw.githubusercontent.com/dlaporte/SmartThings/master/DeviceHandlers/juicebox-evse/images/evse-icon.png",
                    backgroundColor: "#7bb630"
				)
				state("error", label: '${currentValue}',
					icon: "https://raw.githubusercontent.com/dlaporte/SmartThings/master/DeviceHandlers/juicebox-evse/images/evse-icon.png",
                    backgroundColor: "#7bb630"
				)
				state("disconnect", label: '${currentValue}',
					icon: "https://raw.githubusercontent.com/dlaporte/SmartThings/master/DeviceHandlers/juicebox-evse/images/evse-icon.png",
                    backgroundColor: "#7bb630"
				)
		}


		multiAttributeTile(name:"summary", type:"generic", width:6, height:4) {
			tileAttribute("device.currentState", key: "PRIMARY_CONTROL") {
            	
				attributeState("standby", label: '${currentValue}',
					icon: "https://raw.githubusercontent.com/dlaporte/SmartThings/master/DeviceHandlers/juicebox-evse/images/evse-large.png",
                    backgroundColor: "#7bb630"
				)
				attributeState("plugged", label: '${currentValue}',
					icon: "https://raw.githubusercontent.com/dlaporte/SmartThings/master/DeviceHandlers/juicebox-evse/images/evse-large.png",
                    backgroundColor: "#7bb630"
				)
				attributeState("charging", label: '${currentValue}',
					icon: "https://raw.githubusercontent.com/dlaporte/SmartThings/master/DeviceHandlers/juicebox-evse/images/evse-large.png",
                    backgroundColor: "#7bb630"
				)
				attributeState("error", label: '${currentValue}',
					icon: "https://raw.githubusercontent.com/dlaporte/SmartThings/master/DeviceHandlers/juicebox-evse/images/evse-large.png",
                    backgroundColor: "#7bb630"
				)
				attributeState("disconnect", label: '${currentValue}',
					icon: "https://raw.githubusercontent.com/dlaporte/SmartThings/master/DeviceHandlers/juicebox-evse/images/evse-large.png",
                    backgroundColor: "#7bb630"
				)
			}

			tileAttribute("device.charge_details", key: "SECONDARY_CONTROL") {
				attributeState("charge_details", label: '${currentValue}')
	    		}

		}

		standardTile("recent", "recent", width: 6, height: 2) {
			state("default", label: "Recent")
		}

		standardTile("plugin", "plugin", width: 2, height: 2) {
			state("default", label: "Plug-in")
		}
		standardTile("plugout", "plugout", width: 2, height: 2) {
			state("default", label: "Plug-out")
		}
		standardTile("kwh", "kwh", width: 2, height: 2) {
			state("default", label: "Energy (kWh)")
		}
		standardTile("refresh", "device.poll", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state("default", action:"polling.poll", icon:"st.secondary.refresh")
		}

		standardTile("configure", "device.configure", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
		    state "configure", label:'', action:"configuration.configure", icon:"st.secondary.configure"
		}


		main "currentState"
  		details(["summary", "recent","plugin", "plugout", "kwh", "refresh", "configure"])
	}
}

def updated() {
	getToken()
    getCurrentState()
    getHistory()
}

def refresh() {
	log.debug "refresh called"
	getToken()
    getCurrentState()
    getHistory()
}

def poll() {
	log.debug "poll called"
	getToken()
    getCurrentState()
    getHistory()
}

def getCurrentState() {
	def params = [
		uri: "http://emwjuicebox.cloudapp.net",
		path: "/box_api_secure",
		body: [
			"cmd": "get_state",
            "device_id": "smartthings",
            "token": state.token
		]
	]

	try {
		httpPostJson(params) { response ->
			log.debug "getCurrentState: HTTP request successful, ${response.status}"
			if (response.data.success.toBoolean()) {
				log.debug "getCurrentState: success"
			    sendEvent(name: 'currentState', value: response.data.state)
                
				def kwh = (response.data.charging.wh_energy / 1000).toDouble().round(2)
				def hours = (Integer)(response.data.charging.seconds_charging / 3600)
				def minutes = (Integer)((response.data.charging.seconds_charging - (hours * 3600)) / 60)
				def seconds = (Integer)(response.data.charging.seconds_charging - (hours * 3600) - (minutes * 60))

				sendEvent(name: 'kwh', value: kwh, unit: "kWh")
				sendEvent(name: 'seconds', value: response.data.charging.seconds_charging, unit: "s")
                    
                if (response.data.state == "charging") {
                    sendEvent(name: 'amps', value: response.data.charging.amps_current, unit: "A")
                    sendEvent(name: 'power', value: response.data.charging.watt_power, unit: "W")
                    
                    def charge_details = "Charging: ${kwh}kWh charged in ${hours}h${minutes}m${seconds}s"
                    sendEvent(name: 'charge_details', value: charge_details)

				} else {
                    sendEvent(name: 'amps', value: "0", unit: "A")
                    sendEvent(name: 'power', value: "0", unit: "W")
                    
                    def charge_details = "Last charge: ${kwh}kWh charged in ${hours}h${minutes}m${seconds}s"
                    sendEvent(name: 'charge_details', value: charge_details)

                }
                
			} else {
				log.debug "getCurrentState: request failed: ${response.data.error_code}: ${response.data.error_message}}"
			}
		}
	} catch (e) {
		log.error "getCurrentState: something went wrong: $e"
	}
}

def getHistory() {
	def params = [
		uri: "http://emwjuicebox.cloudapp.net",
		path: "/box_api_secure",
		body: [
			"cmd": "get_history",
            "device_id": "smartthings",
            "token": state.token
		]
	]

	try {
		httpPostJson(params) { response ->
			log.debug "getHistory: HTTP request successful, ${response.status}"
			if (response.data.success.toBoolean()) {
				log.debug "getHistory: success"
			} else {
				log.debug "getHistory: request failed: ${response.data.error_code}: ${response.data.error_message}}"
			}
		}
	} catch (e) {
		log.error "getHistory: something went wrong: $e"
	}
}

def getToken() {
	if (state.token) {
        log.debug "getToken: state.token exists"
    } else {
        def params = [
            uri: "http://emwjuicebox.cloudapp.net",
            path: "/box_pin",
            body: [
                "cmd": "pair_device",
				"device_id": "smartthings",
                "id": settings.id,
                "pin": settings.pin

            ]
        ]

        try {
            httpPostJson(params) { response ->
                log.debug "getToken: HTTP request was successful, ${response.status}"
                if (response.data.success.toBoolean()) {
	                log.debug "getToken: setting state.token to ${response.data.token}"
				} else {
                	log.debug "getToken: request failed: ${response.data.error_code}: ${response.data.error_message}}"
                }
                state.token = response.data.token
            }
        } catch (e) {
            log.error "getToken: something went wrong: $e"
        }
	}
}

def configure() {
    getToken()

	def params = [
		uri: "http://emwjuicebox.cloudapp.net",
		path: "/box_api_secure",
		body: [
			"cmd": "set_limit",
            "device_id": "smartthings",
            "token": state.token,
            "amperage": settings.amperage
		]
	]
	try {
		httpPostJson(params) { response ->
			if (response.data.success == "true") {
				log.debug "configure: maximum amperage set to ${settings.amperage}"
			} else {
				log.debug "configure: request failed: ${response.data.error_code}: ${response.data.error_message}}"
			}
		}
	} catch (e) {
		log.error "configure: something went wrong: $e"
	}
}

0
