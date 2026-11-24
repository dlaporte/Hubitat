/**
 *  BOND Fan Timer Light
 *
 *  Copyright 2019 Dominick Meglio
 *  Modified 2026 by David LaPorte
 *
 *  Upstream: https://github.com/dcmeglio/hubitat-bond
 *  Documentation: https://github.com/dlaporte/Hubitat/blob/main/BondHome/README.md
 *
 *  Changelog:
 *  v1.5.0 (2026-11-24) - David LaPorte's fork: healthStatus. Dim (seconds) works.
 *
 */

metadata {
    definition (
		name: "BOND Fan Timer Light", 
		namespace: "bond", 
		author: "dmeglio@gmail.com",
		importUrl: "https://raw.githubusercontent.com/dlaporte/Hubitat/main/BondHome/device/BOND_Fan_Timer_Light.groovy"
	) {
		capability "Switch"
        capability "Light"
		attribute "healthStatus", "enum", ["online", "offline"]

		
		command "dim", ["number"]
		command "startDimming"
		command "stopDimming"
		
		command "fixPower", [[name:"Power*", type: "ENUM", description: "Power", constraints: ["off","on"] ] ]
		command "toggle"
    }
}

def dim(duration) {
	parent.handleDim(device, duration)
}

def startDimming() {
	parent.handleStartDimming(device)
}

def stopDimming() {
	parent.handleStopDimming(device)
}

def on() {
	parent.handleLightOn(device)
}

def off() {
    parent.handleLightOff(device)
}

def toggle() {
	if (device.currentValue("switch") == "on")
		off()
	else
		on()
}

def installed() {
	sendEvent(name: "numberOfButtons", value: "1")
}

def updated() {
	sendEvent(name: "numberOfButtons", value: "1")
}

def fixPower(power) {
	parent.fixLightPower(device, power)
}