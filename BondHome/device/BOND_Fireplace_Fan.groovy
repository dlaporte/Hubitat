/**
 *  BOND Fireplace Fan
 *
 *  Copyright 2019-2020 Dominick Meglio
 *  Modified 2026 by David LaPorte
 *
 *  Upstream: https://github.com/dcmeglio/hubitat-bond
 *  Documentation: https://github.com/dlaporte/Hubitat/blob/main/BondHome/README.md
 *
 *  Changelog:
 *  v1.5.0 (2026-11-24) - David LaPorte's fork: healthStatus. Cycle Speed.
 *
 */

metadata {
    definition (
		name: "BOND Fireplace Fan", 
		namespace: "bond", 
		author: "dmeglio@gmail.com",
		importUrl: "https://raw.githubusercontent.com/dlaporte/Hubitat/main/BondHome/device/BOND_Fireplace_Fan.groovy"
	) {
		capability "FanControl"
		capability "Switch"
		attribute "healthStatus", "enum", ["online", "offline"]
		
		command "fixPower", [[name:"Power*", type: "ENUM", description: "Power", constraints: ["off","on"] ] ]
		command "fixSpeed", [[name:"Speed*", type: "ENUM", description: "Speed", constraints: ["off","low", "medium-low", "medium", "medium-high", "high", "on"] ] ]
		command "toggle"
    }
}

def setSpeed(speed) {
    parent.handleFPFanSpeed(device, speed)
}

def cycleSpeed() {
	def next = parent.nextSpeed(device)
	if (next != null)
		setSpeed(next)
}

def on() {
	parent.handleFPFanOn(device)
}

def off () {
	parent.handleFPFanOff(device)
}

def toggle() {
	if (device.currentValue("switch") == "on")
		off()
	else
		on()
}

def fixPower(power) {
	parent.fixFPFanPower(device, power)
}

def fixSpeed(speed) {
	parent.fixFPFanSpeed(device, speed)
}