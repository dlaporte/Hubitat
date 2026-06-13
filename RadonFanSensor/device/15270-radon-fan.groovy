/**
 *  Radon Fan Sensor
 *
 *  v0.5 - added ContactSensor capability (contact = closed when fan is
 *         running, open when off) alongside the existing switch attribute
 *         so the device matches the semantic Hubitat expects for a binary
 *         sensor. Added lastStartedAt / lastStoppedAt timestamps and a
 *         fanAlarm attribute that fires "alarm" if the fan has been off
 *         for longer than a user-configurable threshold (default 30 min).
 *         Gated all log.debug calls behind a debug preference (was always
 *         on). Existing switch-based rules are unchanged — additive only.
 *  v0.4 - removed SmartThings legacy simulator/tiles blocks, fixed
 *         wrong install-instructions URL, removed stray literal 0.
 *  v0.3 - earlier release
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
 *  Instructions:
 *
 *      1) Click "Drivers Code" and "+ Add Driver" in your Hubitat web interface
 *      2) Paste in the code from: https://github.com/dlaporte/Hubitat/blob/main/RadonFanSensor/device/15270-radon-fan.groovy
 *      3) Click "Save"
 * 
 * Parts List:
 *
 *      1) Dwyer 1910-00 Pressure Switch
 *      2) I door switch with terminals, similar to WADWAZ-1 or Monoprice 15270
 *      3) 1/8" barbed 3-way fitting
 *      4) 1/8" Barb x 1/8" NPT Male Pipe fitting
 *      5) 1/8" ID plastic tubing
 *
 * Install (non-destructively) on an existing RadonAway Easy Manometer:
 *
 *      1) Use a dremel or other tool to cut away sensor case to expose terminals
 *      2) Connect terminals on pressure switch to terminals on sensor
 *      3) Install 1/8" Barb x 1/8" NPT fitting on pressure switch low pressure connection  
 *      4) Test - attach tubing to adapter and gently inhale
 *      5) Remove 1/8" manometer tubing from hole installed in vent pipe
 *      6) Attach small segment of tubing to 3-way fitting
 *      7) Attach manometer tubing to 3-way fitting
 *      8) Connect tubing from adapter to 3-way fitting
 *      9) Insert short tube into vent pipe hole
 *      10) Test - shut off power to the fan to make sure there's not an updraft in the pipe that false positives
 *      11) Sleep soundly knowing your house can no longer silently kill you if the fan dies!
 */

// for the UI
metadata {

	definition (name: "Radon Fan Sensor", namespace: "dlaporte", author: "dlaporte") {
		capability "Sensor"
		capability "Battery"
		capability "ContactSensor"
		capability "Refresh"

		attribute "switch", "enum", ["on", "off"]    // backward compat with prior rules
		attribute "lastStartedAt", "string"           // ISO timestamp of last fan-on transition
		attribute "lastStoppedAt", "string"           // ISO timestamp of last fan-off transition
		attribute "fanAlarm", "string"                // "ok" or "alarm" — alarm if off too long

		fingerprint deviceId: "0x2001", inClusters: "0x71, 0x85, 0x80, 0x72, 0x30, 0x86, 0x84"
	}

	preferences {
		input "alarmAfterMinutes", "number", title: "Fire fanAlarm if fan has been off for (minutes)",
			defaultValue: 30, required: false, range: "1..1440"
		input "debug", "bool", title: "Debug logging", defaultValue: false
	}
}

def parse(String description) {
	if (debug) log.debug "parse:${description}"
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
		if (debug) log.debug "parse:cmd = ${cmd}"
		if (cmd) {
			result = zwaveEvent(cmd)
		}
	}
	return result
}

def sensorValueEvent(value) {
	if (debug) log.debug "sensorValueEvent:value = ${value}"
	String ts = new Date().format("yyyy-MM-dd'T'HH:mm:ssZ", location.timeZone ?: TimeZone.getDefault())
	def result = null
	if (value) {
		// Fan started: switch=on, contact=closed, record timestamp, cancel pending alarm
		result = createEvent(name: "switch", value: "on", descriptionText: "$device.displayName is running")
		sendEvent(name: "contact", value: "closed", descriptionText: "fan running")
		sendEvent(name: "lastStartedAt", value: ts)
		sendEvent(name: "fanAlarm", value: "ok")
		unschedule("checkFanAlarm")
	} else {
		// Fan stopped: switch=off, contact=open, record timestamp, schedule alarm check
		result = createEvent(name: "switch", value: "off", descriptionText: "$device.displayName is not running")
		sendEvent(name: "contact", value: "open", descriptionText: "fan stopped")
		sendEvent(name: "lastStoppedAt", value: ts)
		Integer afterMin = (alarmAfterMinutes ?: 30) as Integer
		runIn(afterMin * 60, "checkFanAlarm")
	}
	return result
}

// Called via runIn after the configured fan-off threshold. If the fan is still
// off, fire the fanAlarm attribute → "alarm" so Rule Machine can notify.
def checkFanAlarm() {
	if (device.currentValue("contact") == "open") {
		sendEvent(name: "fanAlarm", value: "alarm", descriptionText: "fan has been off too long",
			isStateChange: true)
	}
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
	if (debug) log.debug "msr: $msr"
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

def refresh() {
	if (debug) log.debug "refresh() called"
	def commands = []
	
	// Request current sensor state
	commands << zwave.sensorBinaryV1.sensorBinaryGet()
	
	// Request battery level if device supports it
	if (!state.lastbat || (new Date().time) - state.lastbat > 53*60*60*1000) {
		commands << zwave.batteryV1.batteryGet()
	}
	
	// Return the commands to be sent
	return commands.collect{ response(it) }
}

void initialize() {
	if (debug) log.debug "initialize() called"
	refresh()
}
