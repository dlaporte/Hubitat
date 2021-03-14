/**
 *  WADWAZ-1/Monoprice 15270 as Radon Fan Sensor
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
 * Parts List:
 *
 *	1) Dwyer 1910-00 Pressure Switch
 *	2) 1/8" barbed 3-way fitting
 *	3) 1/8" Barb x 1/8" NPT Male Pipe fitting
 *	4) 1/8" ID plastic tubing
 *	5) Monoprice 15270, Linear WADWAZ-1 ,or equivalent (eg. Schlage) Door/Window Sensor
 *
 * Install (non-destructively) on an existing RadonAway Easy Manometer:
 *
 *      1) Use a dremel or other tool to cut Monoprice 15270 sensor case to expose terminals
 *      2) Connect terminals on pressure switch to terminals on sensor
 *      3) Install 1/8" Barb x 1/8" NPT fitting on pressure switch low pressure connection  
 *      4) Test - attach tubing to adapter and gently inhale
 *      5) Remove 1/8" manometer tubing from hole installed in vent pipe
 *      6) Attach small segment of tubing to 3-way fitting
 *      7) Attach manometer tubing to 3-way fitting
 *      8) Connect tubing from adapter to 3-way fitting
 *      9) Insert short tube into vent pipe hole
 *      10) Test - shut off power to the fan to make sure there's not an updraft in the pipe that false positives
 *      11) Setup a CoRE piston to alert you 
 *      12) Sleep soundly knowing your house can no longer silently kill you if the fan dies!
 */

// for the UI
metadata {

	definition (name: "WADWAZ-1/Monoprice 15270 as Radon Fan Sensor", namespace: "dlaporte", author: "dlaporte") {
		capability "Switch"
		capability "Sensor"
		capability "Battery"
        capability "Polling"
		fingerprint deviceId: "0x2001", inClusters: "0x71, 0x85, 0x80, 0x72, 0x30, 0x86, 0x84"
	}

	simulator {
		// status messages
		status "on":  "command: 2001, payload: FF"
		status "off": "command: 2001, payload: 00"
	}

    tiles(scale: 2) {
	multiAttributeTile(name:"fan_icon", type: "generic", width: 6, height: 4){
		tileAttribute ("device.switch", key: "PRIMARY_CONTROL") {
			attributeState "on", label:'${name}', icon:"st.thermostat.fan-on", backgroundColor:"#7bb630"
			attributeState "off", label:'${name}', icon:"st.thermostat.fan-off", backgroundColor:"#bc2323"
		}
	}
            multiAttributeTile(name:"fan", type: "lighting", width: 6, height: 4){
                tileAttribute ("device.switch", key: "PRIMARY_CONTROL") {
                    attributeState "on", label:'', icon:"st.thermostat.fan-on", backgroundColor:"#7bb630"
                    attributeState "off", label:'', icon:"st.thermostat.fan-off", backgroundColor:"#bc2323"
                }
                tileAttribute("device.battery", key: "SECONDARY_CONTROL") {
                    attributeState("battery", label: 'Battery: ${currentValue}%')
                }
            }

            standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat", width: 1, height: 1) {
                state "default", action:"refresh.refresh", icon:"st.secondary.refresh"
            }

            main "fan_icon"
            details(["fan","refresh"])
    }
}

def parse(String description) {
	log.debug "parse:${description}"
	def result = null
	if (description.startsWith("Err")) {
		result = createEvent(descriptionText:description)
	} else if (description == "updated") {
		if (!state.MSR) {
		result = [
		response(zwave.wakeUpV1.wakeUpIntervalSet(seconds:4*3600, nodeid:zwaveHubNodeId)),
		response(zwave.manufacturerSpecificV2.manufacturerSpecificGet()),
		]
		}
	} else {
		def cmd = zwave.parse(description, [0x20: 1, 0x25: 1, 0x30: 1, 0x31: 5, 0x80: 1, 0x84: 1, 0x71: 3, 0x9C: 1])
		log.debug "parse:cmd = ${cmd}"
		if (cmd) {
			result = zwaveEvent(cmd)
		}
	}
	return result
}

def sensorValueEvent(value) {
	log.debug "sensorValueEvent:value = ${value}"
	def result = null
	if (value) {
		result = createEvent(name: "switch", value: "on", descriptionText: "$device.displayName is running")
	} else {
		result = createEvent(name: "switch", value: "off", descriptionText: "$device.displayName is not running")
	}
	return result
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd) {
	sensorValueEvent(cmd.value)
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicSet cmd) {
	sensorValueEvent(cmd.value)
}

def zwaveEvent(hubitat.zwave.commands.switchbinaryv1.SwitchBinaryReport cmd) {
	sensorValueEvent(cmd.value)
}

def zwaveEvent(hubitat.zwave.commands.sensorbinaryv1.SensorBinaryReport cmd) {
	sensorValueEvent(cmd.sensorValue)
}

def zwaveEvent(hubitat.zwave.commands.sensoralarmv1.SensorAlarmReport cmd) {
	sensorValueEvent(cmd.sensorState)
}

def zwaveEvent(hubitat.zwave.commands.notificationv3.NotificationReport cmd) {
	def result = []
	if (cmd.notificationType == 0x06 && cmd.event == 0x16) {
		result << sensorValueEvent(1)
	} else if (cmd.notificationType == 0x06 && cmd.event == 0x17) {
		result << sensorValueEvent(0)
	} else if (cmd.notificationType == 0x07) {
		if (cmd.v1AlarmType == 0x07) { // special case for nonstandard messages from Monoprice door/window sensors
			result << sensorValueEvent(cmd.v1AlarmLevel)
		} else if (cmd.event == 0x01 || cmd.event == 0x02) {
			result << sensorValueEvent(1)
		} else if (cmd.event == 0x03) {
			result << createEvent(descriptionText: "$device.displayName covering was removed", isStateChange: true)
			result << response(zwave.wakeUpV1.wakeUpIntervalSet(seconds:4*3600, nodeid:zwaveHubNodeId))
		if(!state.MSR)
			result << response(zwave.manufacturerSpecificV2.manufacturerSpecificGet())
		} else if (cmd.event == 0x05 || cmd.event == 0x06) {
			result << createEvent(descriptionText: "$device.displayName detected glass breakage", isStateChange: true)
		} else if (cmd.event == 0x07) {
			if(!state.MSR)
				result << response(zwave.manufacturerSpecificV2.manufacturerSpecificGet())
			result << createEvent(name: "motion", value: "active", descriptionText:"$device.displayName detected motion")
		}
	} else if (cmd.notificationType) {
		def text = "Notification $cmd.notificationType: event ${([cmd.event] + cmd.eventParameter).join(", ")}"
		result << createEvent(name: "notification$cmd.notificationType", value: "$cmd.event", descriptionText: text, displayed: false)
	} else {
		def value = cmd.v1AlarmLevel == 255 ? "active" : cmd.v1AlarmLevel ?: "inactive"
		result << createEvent(name: "alarm $cmd.v1AlarmType", value: value, displayed: false)
	}
	result
}

def zwaveEvent(hubitat.zwave.commands.wakeupv1.WakeUpNotification cmd) {
	def result = [createEvent(descriptionText: "${device.displayName} woke up", isStateChange: false)]
	if (!state.lastbat || (new Date().time) - state.lastbat > 53*60*60*1000) {
		result << response(zwave.batteryV1.batteryGet())
		result << response("delay 1200")
	}
	if (!state.MSR) {
		result << response(zwave.wakeUpV1.wakeUpIntervalSet(seconds:4*3600, nodeid:zwaveHubNodeId))
		result << response(zwave.manufacturerSpecificV2.manufacturerSpecificGet())
		result << response("delay 1200")
	}
	result << response(zwave.wakeUpV1.wakeUpNoMoreInformation())
	result
}

def zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd) {
	def map = [ name: "battery", unit: "%" ]
	if (cmd.batteryLevel == 0xFF) {
		map.value = 1
		map.descriptionText = "${device.displayName} has a low battery"
		map.isStateChange = true
	} else {
		map.value = cmd.batteryLevel
	}
	state.lastbat = new Date().time
	[createEvent(map), response(zwave.wakeUpV1.wakeUpNoMoreInformation())]
}

def zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {
	def result = []

	def msr = String.format("%04X-%04X-%04X", cmd.manufacturerId, cmd.productTypeId, cmd.productId)
	log.debug "msr: $msr"
	updateDataValue("MSR", msr)
	
	result << createEvent(descriptionText: "$device.displayName MSR: $msr", isStateChange: false)
	
	if (msr == "011A-0601-0901") {  // Enerwave motion doesn't always get the associationSet that the hub sends on join
		result << response(zwave.associationV1.associationSet(groupingIdentifier:1, nodeId:zwaveHubNodeId))
	} else if (!device.currentState("battery")) {
		result << response(zwave.batteryV1.batteryGet())
	}
	result
}

def zwaveEvent(hubitat.zwave.Command cmd) {
	createEvent(descriptionText: "$device.displayName: $cmd", displayed: false)
}

0
