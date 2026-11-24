/**
 *  BOND Generic Device
 *
 *  Copyright 2019-2020 Dominick Meglio
 *  Modified 2026 by David LaPorte
 *
 *  Upstream: https://github.com/dcmeglio/hubitat-bond
 *  Documentation: https://github.com/dlaporte/Hubitat/blob/main/BondHome/README.md
 *
 *  Changelog:
 *  v1.5.0 (2026-11-24) - David LaPorte's fork: healthStatus.
 *
 */

metadata {
    definition (
		name: "BOND Generic Device", 
		namespace: "bond", 
		author: "dmeglio@gmail.com",
		importUrl: "https://raw.githubusercontent.com/dlaporte/Hubitat/main/BondHome/device/BOND_Generic_Device.groovy"
	) {
		capability "Switch"
		attribute "healthStatus", "enum", ["online", "offline"]
		command "fixPower", [[name:"Power*", type: "ENUM", description: "Power", constraints: ["off","on"] ] ]
		command "toggle"
    }
}

def on() {
	parent.handleOn(device)
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

def fixPower(power) {
	parent.fixPowerState(device, power)
}