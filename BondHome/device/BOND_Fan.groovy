/**
 *  BOND Fan
 *
 *  Copyright 2019-2020 Dominick Meglio
 *  Modified 2026 by David LaPorte
 *
 *  Upstream: https://github.com/dcmeglio/hubitat-bond
 *  Documentation: https://github.com/dlaporte/Hubitat/blob/main/BondHome/README.md
 *
 *  Changelog:
 *  v1.5.0 (2026-11-24) - David LaPorte's fork: healthStatus. Cycle Speed; On and Set Speed remember the speed actually set.
 *
 */

metadata {
    definition (
		name: "BOND Fan", 
		namespace: "bond", 
		author: "dmeglio@gmail.com", 
		importUrl: "https://raw.githubusercontent.com/dlaporte/Hubitat/main/BondHome/device/BOND_Fan.groovy"
	) {
		capability "Switch"
        capability "FanControl"
		attribute "healthStatus", "enum", ["online", "offline"]
		
		command "fixPower", [[name:"Power*", type: "ENUM", description: "Power", constraints: ["off","on"] ] ]
		command "fixSpeed", [[name:"Speed*", type: "ENUM", description: "Speed", constraints: ["off","low", "medium-low", "medium", "medium-high", "high", "on"] ] ]
		command "toggle"
    }
}

def on() {
	parent.handleOn(device)
	if (state.lastSpeed != null)
	{
		def set = parent.handleFanSpeed(device, state.lastSpeed)
		if (set != null)
			state.lastSpeed = set
	}
}

def off() {
	parent.handleOff(device)
}

def toggle() {
	if (device.currentValue("switch") == "on")
		off()
	else
		on()
}

def handleLightOn(device) {
    parent.handleLightOn(device)
}

def handleLightOff(device) {
    parent.handleLightOff(device)
}

def setSpeed(speed) {
	def set = parent.handleFanSpeed(device, speed)
	if (set != null && set != "off" && set != "on")
		state.lastSpeed = set
}

def cycleSpeed() {
	def next = parent.nextSpeed(device)
	if (next != null)
		setSpeed(next)
}

def handleLightLevel(device, level)
{
	parent.handleLightLevel(device, level)
}

def handleDim(device, duration) {
	parent.handleDim(device, duration)
}

def handleStartDimming(device) {
	parent.handleStartDimming(device)
}

def handleStopDimming(device) {
	parent.handleStopDimming(device)
}

def fixPower(power) {
	parent.fixPowerState(device, power)
}

def fixSpeed(speed) {
	parent.fixFanSpeed(device, speed)
}

def fixLightPower(device, power) {
	parent.fixLightPower(device, power)
}

def fixLightLevel(device, level) {
	parent.fixLightLevel(device, level)
}