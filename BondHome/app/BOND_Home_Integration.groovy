/**
 *  BOND Home Integration
 *
 *  Copyright 2019-2020 Dominick Meglio
 *  Modified 2026 by David LaPorte
 *
 *  Connects Hubitat to a BOND bridge (fans, fireplaces, shades and generic devices) over its local API.
 *  Upstream: https://github.com/dcmeglio/hubitat-bond
 *  Documentation: https://github.com/dlaporte/Hubitat/blob/main/BondHome/README.md
 *
 *  Changelog:
 *  v1.5.0 (2026-11-24) - David LaPorte's fork. Done no longer re-adds existing lights or stops polling.
 *                        Bridge timeouts and errors no longer crash the poll or commands, and an outage
 *                        logs one warning (with an hourly reminder and a recovery line) instead of an
 *                        error every poll; every device gets healthStatus. Speeds follow the fan's own
 *                        speed count (medium-low and medium-high round), with supportedFanSpeeds and
 *                        Cycle Speed. Fix Speed records power. Light state is polled; timer Dim works.
 *                        Visiting the settings page while the bridge is down no longer wipes the device
 *                        lists. Debug logging defaults off and turns itself off; settings aren't logged.
 *  2020.06.28 and earlier - Dominick Meglio (1.4.0): see https://github.com/dcmeglio/hubitat-bond
 *
 *	If you find this useful, donations are always appreciated (to Dominick Meglio)
 *	https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=7LBRPJRLJSDDN&source=url
 *
 */

import groovy.transform.Field

@Field static final String VERSION = "1.5.0"

// BOND speed number <-> Hubitat speed name, by the fan's speed count (upstream's tables,
// with the 10-speed "2: 1" and 9-speed ":medium" typos fixed, plus 1-speed fans).
@Field static final Map BOND_TO_HE = [
	10: [10: "high", 9: "high", 8: "medium-high", 7: "medium-high", 6: "medium", 5: "medium", 4: "medium-low", 3: "medium-low", 2: "low", 1: "low"],
	9: [9: "high", 8: "medium-high", 7: "medium-high", 6: "medium", 5: "medium", 4: "medium-low", 3: "medium-low", 2: "low", 1: "low"],
	8: [8: "high", 7: "medium-high", 6: "medium-high", 5: "medium", 4: "medium", 3: "medium-low", 2: "medium-low", 1: "low"],
	7: [7: "high", 6: "medium-high", 5: "medium", 4: "medium", 3: "medium-low", 2: "medium-low", 1: "low"],
	6: [6: "high", 5: "medium-high", 4: "medium", 3: "medium", 2: "medium-low", 1: "low"],
	5: [5: "high", 4: "medium-high", 3: "medium", 2: "medium-low", 1: "low"],
	4: [4: "high", 3: "medium", 2: "medium-low", 1: "low"],
	3: [3: "high", 2: "medium", 1: "low"],
	2: [2: "high", 1: "low"],
	1: [1: "high"]
]
@Field static final Map HE_TO_BOND = [
	10: ["high": 10, "medium-high": 8, "medium": 5, "medium-low": 3, "low": 1],
	9: ["high": 9, "medium-high": 7, "medium": 5, "medium-low": 3, "low": 1],
	8: ["high": 8, "medium-high": 6, "medium": 4, "medium-low": 3, "low": 1],
	7: ["high": 7, "medium-high": 6, "medium": 4, "medium-low": 3, "low": 1],
	6: ["high": 6, "medium-high": 5, "medium": 3, "medium-low": 2, "low": 1],
	5: ["high": 5, "medium-high": 4, "medium": 3, "medium-low": 2, "low": 1],
	4: ["high": 4, "medium": 3, "medium-low": 2, "low": 1],
	3: ["high": 3, "medium": 2, "low": 1],
	2: ["high": 2, "low": 1],
	1: ["high": 1]
]
@Field static final List SPEED_ORDER = ["low", "medium-low", "medium", "medium-high", "high"]

definition(
    name: "BOND Home Integration",
    namespace: "dcm.bond",
    author: "Dominick Meglio",
    description: "Connects to BOND Home hub",
    category: "My Apps",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
	documentationLink: "https://github.com/dlaporte/Hubitat/blob/main/BondHome/README.md",
	importUrl: "https://raw.githubusercontent.com/dlaporte/Hubitat/main/BondHome/app/BOND_Home_Integration.groovy")


preferences {
	page(name: "prefHub", title: "BOND")
	page(name: "prefListDevices", title: "BOND")
	page(name: "prefPowerSensors", title: "BOND")
}

def prefHub() {
	return dynamicPage(name: "prefHub", title: "Connect to BOND", nextPage:"prefListDevices", uninstall:false, install: false) {
		section("Hub Information"){
			input("hubIp", "text", title: "BOND Hub IP", description: "BOND Hub IP Address", required: true)
			input("hubToken", "text", title: "BOND Hub Token", description: "BOND Hub Token", required: true)
			input("refreshInterval", "number", title: "Poll BOND Home every N seconds", required: true, defaultValue: 30)
            input("debugOutput", "bool", title: "Enable debug logging", description: "Turns itself off after 30 minutes", defaultValue: false, displayDuringSetup: false, required: false)
		}
		displayFooter()
	}
}

def prefListDevices() {
	if (!getDevices())
	{
		return dynamicPage(name: "prefListDevices", title: "Connection Error", install: false, uninstall: false) {
			section("Error") {
				paragraph "Unable to retrieve devices. Please verify your BOND Hub ID and Token"
			}
			displayFooter()
		}
	}
	else
	{
		return dynamicPage(name: "prefListDevices", title: "Devices", nextPage: "prefPowerSensors", install: false, uninstall: false) {
			section("Devices") {
				if (state.fireplaceList.size() > 0)
					input(name: "fireplaces", type: "enum", title: "Fireplaces", required:false, multiple:true, options:state.fireplaceList, hideWhenEmpty: true)
				if (state.fanList.size() > 0)
					input(name: "fans", type: "enum", title: "Fans", required:false, multiple:true, options:state.fanList, hideWhenEmpty: true)
				if (state.shadeList.size() > 0)
					input(name: "shades", type: "enum", title: "Shades", required:false, multiple:true, options:state.shadeList, hideWhenEmpty: true)
				if (state.genericList.size() > 0)
					input(name: "genericDevices", type: "enum", title: "Generic Devices", required:false, multiple:true, options:state.genericList, hideWhenEmpty: true)
			}
			displayFooter()
		}
	}
}

def prefPowerSensors() {
	return dynamicPage(name: "prefPowerSensors", title: "Fireplace Power Meters", install: true, uninstall: true, hideWhenEmpty: true) {
		section("Fireplace Power Meters") {
			paragraph "For each fireplace device you can associate a power meter to more accurately tell when it is powered on"
			if (fireplaces != null) {
				for (def i = 0; i < fireplaces.size(); i++) {
					input(name: "fireplaceSensor${i}", type: "capability.powerMeter", title: "Sensor for ${state.fireplaceList[fireplaces[i]]}", required: false, submitOnChange: true)
				}
				for (def i = 0; i < fireplaces.size(); i++) {
					if (this.getProperty("fireplaceSensor${i}") != null)
					input(name: "fireplaceSensorThreshold${i}", type: "number", title: "Sensor threshold for ${state.fireplaceList[fireplaces[i]]}", required: false)
				}
			}
		}
		displayFooter()
	}
}

def installed() {
	initialize()
}

def updated() {
	unschedule()
	unsubscribe()
	initialize()
}

def uninstalled() {
	logDebug "Uninstalled app"

	for (device in getChildDevices())
	{
		deleteChildDevice(device.deviceNetworkId)
	}	
}

def initialize() {
	logDebug "initializing"
	// The poll goes first, so nothing below can leave it unarmed.
	schedule(pollCron(refreshInterval), "updateDevices")
	if (settings.debugOutput)
		runIn(1800, "logsOff")
	state.remove("health")       // the next poll re-sends healthStatus to every device
	if (!hubIp || !hubToken)
		log.error "BOND: set the bridge's IP address and token in the app"
	guarded("cleaning up devices") { cleanupChildDevices() }
	guarded("adding devices") { createChildDevices() }
	guarded("subscribing to power meters") { subscribeSensorEvents() }
}

private void guarded(String what, Closure c) {
	try {
		c.call()
	}
	catch (Exception e) {
		log.warn "BOND: error while ${what}: ${e}"
	}
}

// Seconds below 60 use a seconds schedule; longer intervals round to whole minutes, at most hourly.
String pollCron(interval) {
	Integer n = (interval != null && interval.toString().isInteger()) ? (interval.toString() as Integer) : 30
	if (n < 5)
		n = 30
	if (n < 60)
		return "0/${n} * * * * ? *".toString()
	int m = (n + 30).intdiv(60)
	if (m >= 60)
		return "0 0 * * * ? *"
	return "0 0/${m} * * * ? *".toString()
}

def getHubId() {
	if (state.hubId)
		return state.hubId
	Map r = bondRequest("GET", "/v2/sys/version", [timeout: 10])
	if (r.ok && r.data?.bondid)
		state.hubId = r.data.bondid
	else if (!r.ok)
		log.warn "BOND: couldn't read the bridge's ID: ${describeFailure(r)}"
	return state.hubId
}

// Reads every BOND device into new lists, and stores them only when the whole fetch
// succeeds, so a failed visit to the settings page can't wipe what polling and Done use.
def getDevices() {
	Map lists = [fireplaceList: [:], fireplaceDetails: [:], fireplaceProperties: [:],
		fanList: [:], fanDetails: [:], fanProperties: [:],
		shadeList: [:], shadeDetails: [:], shadeProperties: [:],
		genericList: [:], genericDetails: [:]]
	Map r = bondRequest("GET", "/v2/devices", [timeout: 10])
	if (!r.ok) {
		log.warn "BOND: couldn't list the bridge's devices: ${describeFailure(r)}"
		return false
	}
	for (entry in r.data) {
		if (entry.key == "_")
			continue
		if (!getDeviceById(entry.key as String, lists))
			return false
	}
	lists.each { k, v -> state[k] = v }
	return true
}

def getDeviceById(String id, Map lists) {
	Map r = bondRequest("GET", "/v2/devices/${id}", [timeout: 10])
	if (!r.ok)
		return keepStored(id, lists, r)
	String kind = [FP: "fireplace", CF: "fan", MS: "shade", GX: "generic"][r.data.type]
	if (kind == null)
		return true     // a device type this app doesn't support
	if (kind != "generic") {
		Map p = bondRequest("GET", "/v2/devices/${id}/properties", [timeout: 10])
		if (!p.ok)
			return keepStored(id, lists, p)
		lists[kind + "Properties"][id] = p.data
	}
	lists[kind + "List"][id] = r.data.name
	lists[kind + "Details"][id] = r.data.actions
	return true
}

// A device the bridge answers for with an error keeps what was stored for it, so one bad
// device can't block setup. A bridge that stops answering fails the whole fetch.
private boolean keepStored(String id, Map lists, Map r) {
	if (r.status == null) {
		log.warn "BOND: couldn't read device ${id}: ${describeFailure(r)}"
		return false
	}
	log.warn "BOND: couldn't read device ${id} (${describeFailure(r)}), so it keeps what was stored for it"
	for (kind in ["fireplace", "fan", "shade", "generic"]) {
		if (!state["${kind}List".toString()]?.containsKey(id))
			continue
		for (part in ["List", "Details", "Properties"]) {
			def old = state["${kind}${part}".toString()]?.get(id)
			if (old != null && lists.containsKey(kind + part))
				lists[kind + part][id] = old
		}
	}
	return true
}

def findChildDevice(deviceId) {
	def dev = getChildDevice("bond:" + deviceId)
	if (dev != null)
		return dev
	def hubId = getHubId()
	return hubId ? getChildDevice(hubId + ":bond:" + deviceId) : null
}

def findComponentDevice(dev, deviceId) {
	if (dev == null)
		return null
	def component = dev.getChildDevice("bond:" + deviceId)
	if (component != null)
		return component
	def hubId = getHubId()
	return hubId ? dev.getChildDevice(hubId + ":bond:" + deviceId) : null
}

def getBondIdFromDevice(device) {
	if (device?.deviceNetworkId.startsWith("bond:"))
		return device?.deviceNetworkId.split(":")[1]
	else
		return device?.deviceNetworkId.split(":")[2]
}

def deleteComponentDevice(dev, deviceId) {
	def hubId = getHubId()
	for (dni in ["bond:" + deviceId, hubId ? hubId + ":bond:" + deviceId : null]) {
		if (dni && dev.getChildDevice(dni)) {
			log.info "BOND: removing ${dni}, because BOND no longer lists it"
			dev.deleteChildDevice(dni)
		}
	}
}

def createChildDevices() {
	def hubId = getHubId()
	if (!hubId) {
		log.warn "BOND: the bridge's ID is unknown, so no devices were added. Press Done again once the bridge answers."
		return
	}
	for (fireplace in (fireplaces ?: [])) {
		def details = storedDetails("fireplace", fireplace)
		if (details == null)
			continue
		def fpDevice = findChildDevice(fireplace)
		if (!fpDevice)
			fpDevice = addChildDevice("bond", "BOND Fireplace", hubId + ":bond:" + fireplace, 1234, ["name": state.fireplaceList[fireplace], isComponent: false])
		if (details.contains("TurnFpFanOn"))
			addComponent(fpDevice, hubId, fireplace + ":fan", "BOND Fireplace Fan", state.fireplaceList[fireplace] + " Fan")
		if (details.contains("TurnLightOn"))
			addComponent(fpDevice, hubId, fireplace + ":light", "BOND Fireplace Light", state.fireplaceList[fireplace] + " Light")
	}
	for (fan in (fans ?: [])) {
		def details = storedDetails("fan", fan)
		if (details == null)
			continue
		def fanDevice = findChildDevice(fan)
		if (!fanDevice) {
			String fanType = details.contains("SetDirection") ? "BOND Fan With Direction" : "BOND Fan"
			fanDevice = addChildDevice("bond", fanType, hubId + ":bond:" + fan, 1234, ["name": state.fanList[fan], isComponent: false])
		}
		String name = state.fanList[fan]
		if (details.contains("TurnUpLightOn") && details.contains("TurnDownLightOn")) {
			String type = "BOND Fan Light"
			if (details.contains("SetUpLightBrightness") && details.contains("SetDownLightBrightness"))
				type = "BOND Fan Dimmable Light"
			else if (details.contains("StartUpLightDimmer") && details.contains("StartDownLightDimmer"))
				type = "BOND Fan Timer Light"
			addComponent(fanDevice, hubId, fan + ":uplight", type, name + " Up Light")
			addComponent(fanDevice, hubId, fan + ":downlight", type, name + " Down Light")
		}
		else if (details.contains("TurnLightOn")) {
			String type = "BOND Fan Light"
			if (details.contains("SetBrightness"))
				type = "BOND Fan Dimmable Light"
			else if (details.contains("StartDimmer"))
				type = "BOND Fan Timer Light"
			addComponent(fanDevice, hubId, fan + ":light", type, name + " Light")
		}
	}
	for (shade in (shades ?: [])) {
		if (storedDetails("shade", shade) != null && !findChildDevice(shade))
			addChildDevice("bond", "BOND Motorized Shade", hubId + ":bond:" + shade, 1234, ["name": state.shadeList[shade], isComponent: false])
	}
	for (generic in (genericDevices ?: [])) {
		if (storedDetails("generic", generic) != null && !findChildDevice(generic))
			addChildDevice("bond", "BOND Generic Device", hubId + ":bond:" + generic, 1234, ["name": state.genericList[generic], isComponent: false])
	}
}

private void addComponent(parentDevice, hubId, String componentId, String type, String name) {
	if (!findComponentDevice(parentDevice, componentId))
		parentDevice.addChildDevice("bond", type, hubId + ":bond:" + componentId, ["name": name, isComponent: true])
}

// The device's actions from the last complete fetch; null, with one warning, when there are none.
private List storedDetails(String kind, bondId) {
	def details = state["${kind}Details".toString()]?.get(bondId)
	if (details == null)
		log.warn "BOND: no stored details for ${kind} ${bondId}, so it was left as it is. Open the app, click Next once the bridge answers, then Done."
	return details
}

def cleanupChildDevices()
{
	for (device in getChildDevices())
	{
		def deviceId = device.deviceNetworkId.replace("bond:","")
		if (deviceId.contains(":"))
			deviceId = deviceId.split(":")[1]
		
		def deviceFound = false
		for (fireplace in fireplaces)
		{
			if (fireplace == deviceId)
			{
				deviceFound = true
				cleanupFPComponents(device, fireplace)
				break
			}
		}
		
		if (deviceFound == true)
			continue
		
		for (fan in fans)
		{
			if (fan == deviceId)
			{
				deviceFound = true
				cleanupFanComponents(device, fan)
				break
			}
		}
		if (deviceFound == true)
			continue
			
		for (shade in shades)
		{
			if (shade == deviceId)
			{
				deviceFound = true
				break
			}
		}
		if (deviceFound == true)
			continue
			
		for (generic in genericDevices)
		{
			if (generic == deviceId)
			{
				deviceFound = true
				break
			}
		}
		if (deviceFound == true)
			continue
		
		deleteChildDevice(device.deviceNetworkId)
	}
}

// Cleanup acts only on details from a complete fetch; without them it does nothing
// (createChildDevices warns).
def cleanupFPComponents(device, fireplace)
{
	def details = state.fireplaceDetails?.get(fireplace)
	if (details == null)
		return
	if (!details.contains("TurnFpFanOn"))
		deleteComponentDevice(device, fireplace + ":fan")
	if (!details.contains("TurnLightOn"))
		deleteComponentDevice(device, fireplace + ":light")
}

def cleanupFanComponents(device, fan)
{
	def details = state.fanDetails?.get(fan)
	if (details == null)
		return
	if (!details.contains("TurnUpLightOn") || !details.contains("TurnDownLightOn"))
	{
		deleteComponentDevice(device, fan + ":uplight")
		deleteComponentDevice(device, fan + ":downlight")
	}
	if (!details.contains("TurnLightOn") || (details.contains("TurnUpLightOn") && details.contains("TurnDownLightOn")))
		deleteComponentDevice(device, fan + ":light")
}

def subscribeSensorEvents() {
	if (fireplaces != null)
	{
		for (def i = 0; i < fireplaces.size(); i++)
		{
			def sensorDevice = settings["fireplaceSensor${i}".toString()]
			if (sensorDevice != null)
			{
				logDebug "subscribing to power event for ${sensorDevice}"
				subscribe(sensorDevice, "power", "powerMeterEventHandler")
			}
		}
	}
}

def powerMeterEventHandler(evt) {
	logDebug "Received power meter event ${evt.value} from ${evt.device}"
	List fps = fireplaces ?: []
	for (int i = 0; i < fps.size(); i++)
	{
		def sensorDevice = settings["fireplaceSensor${i}".toString()]
		if (sensorDevice == null || evt.device?.id != sensorDevice.id)
			continue
		def fireplace = fps[i]
		def fireplaceDevice = findChildDevice(fireplace)
		if (fireplaceDevice == null)
			return
		def setThreshold = settings["fireplaceSensorThreshold${i}".toString()]
		BigDecimal threshold = setThreshold != null ? (setThreshold as BigDecimal) : 10
		def value = ((evt.value ?: 0) as BigDecimal) < threshold ? "off" : "on"
		if (value != fireplaceDevice.currentValue("switch"))
		{
			logDebug "current state ${fireplaceDevice.currentValue("switch")} changing to ${value}"
			fireplaceDevice.sendEvent(name: "switch", value: value)
		}
		if (value == "off")
		{
			def fanDevice = findComponentDevice(fireplaceDevice, fireplace + ":fan")
			if (fanDevice)
				fanDevice.sendEvent(name: "speed", value: "off")
		}
		return
	}
}

def updateDevices() {
	noteOutcome(pollAll())
}

// Polls every selected device. Returns false when the bridge stopped answering; the rest
// of the poll is then skipped, so an outage costs one 5 s timeout per poll.
private boolean pollAll() {
	for (fan in (fans ?: [])) {
		if (!pollDevice(fan) { device, data -> updateFan(device, fan, data) })
			return false
	}
	List fps = fireplaces ?: []
	for (int i = 0; i < fps.size(); i++) {
		def fireplace = fps[i]
		int index = i
		if (!pollDevice(fireplace) { device, data -> updateFireplace(device, fireplace, index, data) })
			return false
	}
	for (shade in (shades ?: [])) {
		if (!pollDevice(shade) { device, data -> updateShade(device, data) })
			return false
	}
	for (generic in (genericDevices ?: [])) {
		if (!pollDevice(generic) { device, data -> updateGeneric(device, data) })
			return false
	}
	return true
}

// Reads one device's state and applies it. Returns false only when the bridge didn't answer.
private boolean pollDevice(bondId, Closure update) {
	def device = findChildDevice(bondId)
	if (device == null) {
		logDebug "BOND device ${bondId} has no Hubitat device; skipped"
		return true
	}
	Map r = bondRequest("GET", "/v2/devices/${bondId}/state", [timeout: 5])
	if (!r.ok && r.status == null) {
		state.lastPollError = r.error
		return false
	}
	if (!r.ok) {
		noteDeviceProblem(bondId, device, describeFailure(r))
		return true
	}
	noteDeviceOk(bondId, device)
	try {
		update.call(device, r.data)
	}
	catch (Exception e) {
		log.warn "BOND: couldn't update ${device.displayName} (${bondId}): ${e}"
	}
	return true
}

private void updateFan(device, fan, data) {
	if (data.power > 0) {
		device.sendEvent(name: "switch", value: "on")
		device.sendEvent(name: "speed", value: speedName(maxSpeed("fan", fan), data.speed))
	}
	else {
		device.sendEvent(name: "switch", value: "off")
		device.sendEvent(name: "speed", value: "off")
	}
	device.sendEvent(name: "supportedFanSpeeds", value: supportedSpeedsJson(maxSpeed("fan", fan)))
	if (device.hasAttribute("direction")) {
		if (data.direction == 1)
			device.sendEvent(name: "direction", value: "forward")
		else if (data.direction == -1)
			device.sendEvent(name: "direction", value: "reverse")
	}
	guarded("updating the lights of ${device.displayName}") {
		boolean lightOn = (data.light ?: 0) > 0
		updateLight(findComponentDevice(device, fan + ":light"), lightOn, data.brightness)
		updateLight(findComponentDevice(device, fan + ":uplight"), lightOn && (data.up_light ?: 0) > 0, data.up_light_brightness)
		updateLight(findComponentDevice(device, fan + ":downlight"), lightOn && (data.down_light ?: 0) > 0, data.down_light_brightness)
	}
}

// One light: on/off from BOND's light flags; level from brightness, kept while off
// (upstream issue #24). Disabled lights are left alone.
private void updateLight(light, boolean on, brightness) {
	if (light == null || light.isDisabled())
		return
	light.sendEvent(name: "switch", value: on ? "on" : "off")
	if (brightness != null)
		light.sendEvent(name: "level", value: brightness)
}

private void updateFireplace(device, fireplace, int i, data) {
	if (data.flame > 0 && data.power > 0)
		device.sendEvent(name: "flame", value: data.flame <= 25 ? "low" : (data.flame <= 50 ? "medium" : "high"))
	else
		device.sendEvent(name: "flame", value: "off")
	if (settings["fireplaceSensor${i}".toString()] == null)
		device.sendEvent(name: "switch", value: data.power > 0 ? "on" : "off")
	guarded("updating the parts of ${device.displayName}") {
		def fpFan = findComponentDevice(device, fireplace + ":fan")
		if (fpFan != null && !fpFan.isDisabled()) {
			// The fan runs when BOND says so (fpfan_power); without that field, when the fireplace is on.
			boolean fanOn = data.fpfan_power != null ? data.fpfan_power > 0 : data.power > 0
			fpFan.sendEvent(name: "switch", value: fanOn ? "on" : "off")
			fpFan.sendEvent(name: "speed", value: fanOn ? speedName(maxSpeed("fireplace", fireplace), data.fpfan_speed) : "off")
			fpFan.sendEvent(name: "supportedFanSpeeds", value: supportedSpeedsJson(maxSpeed("fireplace", fireplace)))
		}
		updateLight(findComponentDevice(device, fireplace + ":light"), data.power > 0 && data.light == 1, null)
	}
}

private void updateShade(device, data) {
	boolean open = data.open == 1
	device.sendEvent(name: "switch", value: open ? "on" : "off")
	device.sendEvent(name: "windowShade", value: open ? "open" : "closed")
}

private void updateGeneric(device, data) {
	device.sendEvent(name: "switch", value: data.power > 0 ? "on" : "off")
}

// The fan's speed count from the last complete fetch (fireplace fans: the fireplace's), 1-10, default 3.
Integer maxSpeed(String kind, bondId) {
	def props = (kind == "fireplace" ? state.fireplaceProperties : state.fanProperties)?.get(bondId)
	def m = props?.max_speed
	Integer n = (m != null && m.toString().isInteger()) ? (m.toString() as Integer) : 3
	return Math.min(Math.max(n, 1), 10)
}

// ---- Outages (STYLE.md pattern) ----

// Blips under 2 minutes log at debug. Then one warning, an hourly reminder, and one
// recovery line with the outage length; healthStatus follows.
private void noteOutcome(boolean answered) {
	long t = now()
	if (answered) {
		if (state.outageStart) {      // a healthy poll writes nothing to state
			if (state.outageDeclared)
				log.info "BOND bridge at ${hubIp} is answering again after ${formatDuration(t - (state.outageStart as Long))}"
			else
				logDebug "BOND bridge answering again after a blip"
			state.remove("outageStart")
			state.remove("outageDeclared")
			state.remove("outageWarnedAt")
		}
		setHealth("online")
		return
	}
	if (!state.outageStart) {
		state.outageStart = t
		logDebug "BOND bridge not answering (${state.lastPollError}); retrying"
		return
	}
	long downFor = t - (state.outageStart as Long)
	if (!state.outageDeclared) {
		if (downFor < 120000L) {
			logDebug "BOND bridge still not answering after ${formatDuration(downFor)}"
			return
		}
		state.outageDeclared = true
		state.outageWarnedAt = t
		log.warn "BOND bridge at ${hubIp} isn't answering (${state.lastPollError}); still trying"
		setHealth("offline")
		return
	}
	if (t - (state.outageWarnedAt as Long) >= 3600000L) {
		state.outageWarnedAt = t
		log.warn "BOND bridge at ${hubIp} still isn't answering after ${formatDuration(downFor)}"
	}
}

private void setHealth(String status) {
	if (state.health == status)
		return
	state.health = status
	for (d in bondDevices()) {
		try {
			d.sendEvent(name: "healthStatus", value: status)
		}
		catch (Exception e) {
			logDebug "BOND: couldn't set healthStatus on ${d.displayName}: ${e}"
		}
	}
}

// Every BOND device in Hubitat that isn't disabled: top-level devices and their components.
// What it found is kept in state.healthDevices, so the hub can show the component lookup works.
private List bondDevices() {
	List all = []
	List skipped = []
	for (d in getChildDevices()) {
		if (d.isDisabled())
			skipped << d.deviceNetworkId
		else
			all << d
		def id = getBondIdFromDevice(d)
		for (suffix in [":light", ":uplight", ":downlight", ":fan"]) {
			def c = findComponentDevice(d, id + suffix)
			if (c == null)
				continue
			if (c.isDisabled())
				skipped << c.deviceNetworkId
			else
				all << c
		}
	}
	state.healthDevices = [sent: all*.deviceNetworkId, skippedDisabled: skipped]
	return all
}

private void noteDeviceProblem(bondId, device, String why) {
	Map problems = state.deviceProblems ?: [:]
	if (problems.containsKey(bondId))
		return
	log.warn "BOND: couldn't read ${device.displayName} (${bondId}): ${why}"
	problems[bondId] = now()
	state.deviceProblems = problems
}

private void noteDeviceOk(bondId, device) {
	Map problems = state.deviceProblems
	if (!problems?.containsKey(bondId))
		return
	problems.remove(bondId)
	state.deviceProblems = problems
	log.info "BOND: ${device.displayName} (${bondId}) is answering again"
}

private String formatDuration(long ms) {
	long mins = (ms + 30000L).intdiv(60000L)
	if (mins < 60)
		return "${mins} min"
	long h = mins.intdiv(60L)
	long m = mins % 60L
	return m ? "${h} h ${m} min" : "${h} h"
}

def handleOn(device) {
	def bondId = getBondIdFromDevice(device)
	logDebug "Handling On event for ${bondId}"

    if (executeAction(bondId, "TurnOn") && shouldSendEvent(bondId))
    {
        device.sendEvent(name: "switch", value: "on")
    }
	
}

def handleLightOn(device) {
	def bondId = getBondIdFromDevice(device)
    logDebug "Handling Light On event for ${bondId}"
	if (device.deviceNetworkId.contains("uplight"))
	{
		if (executeAction(bondId, "TurnUpLightOn")) 
		{
			device.sendEvent(name: "switch", value: "on")
		}
	}
	else if (device.deviceNetworkId.contains("downlight"))
	{
		if (executeAction(bondId, "TurnDownLightOn")) 
		{
			device.sendEvent(name: "switch", value: "on")
		}
	}
    else
	{
        if (executeAction(bondId, "TurnLightOn")) 
		{
			device.sendEvent(name: "switch", value: "on")
		}
    }
}

def handleLightOff(device) {
	def bondId = getBondIdFromDevice(device)
    logDebug "Handling Light Off event for ${bondId}"   
	if (device.deviceNetworkId.contains("uplight"))
	{
		if (executeAction(bondId, "TurnUpLightOff")) 
		{
			device.sendEvent(name: "switch", value: "off")
		}
	}
	else if (device.deviceNetworkId.contains("downlight"))
	{
		if (executeAction(bondId, "TurnDownLightOff")) 
		{
			device.sendEvent(name: "switch", value: "off")
		}
	}	
    else
	{
        if (executeAction(bondId, "TurnLightOff")) 
		{
			device.sendEvent(name: "switch", value: "off")
		}
    }
}

def handleDim(device, duration)
{
	if (device.deviceNetworkId.contains("uplight"))
		dimUsingTimer(device, duration, "StartUpLightDimmer")
	else if (device.deviceNetworkId.contains("downlight"))
		dimUsingTimer(device, duration, "StartDownLightDimmer")
	else
		dimUsingTimer(device, duration, "StartDimmer")
}

def dimUsingTimer(device, duration, command)
{
	def bondId = getBondIdFromDevice(device)
	BigDecimal seconds = (duration != null && duration.toString().isNumber()) ? (duration as BigDecimal) : null
	if (seconds == null || seconds <= 0) {
		log.warn "${device.displayName}: Dim needs a number of seconds, so nothing was sent"
		return
	}
	// overwrite: false, so dimming a second light doesn't cancel the first light's Stop.
	if (executeAction(bondId, command))
		runInMillis((seconds * 1000).toLong(), "stopDimmer", [overwrite: false, data: [bondId: bondId]])
}

def stopDimmer(data)
{
	executeAction(data.bondId, "Stop")
}

def handleStartDimming(device)
{
	def bondId = getBondIdFromDevice(device)
	if (device.deviceNetworkId.contains("uplight"))
	{
		executeAction(bondId, "StartUpLightDimmer")
	}
	else if (device.deviceNetworkId.contains("downlight"))
	{
		executeAction(bondId, "StartDownLightDimmer")
	}
	else
	{
		executeAction(bondId, "StartDimmer")
	}
}

def handleStopDimming(device)
{
	def bondId = getBondIdFromDevice(device)
	executeAction(bondId, "Stop")
}

def handleLightLevel(device, level) {
	def bondId = getBondIdFromDevice(device)
	logDebug "Handling Light Level event for ${bondId}"
	if (device.deviceNetworkId.contains("uplight"))
	{
		if (executeAction(bondId, "SetUpLightBrightness", level)) 
		{
			device.sendEvent(name: "level", value: level)
		}
	}
	else if (device.deviceNetworkId.contains("downlight"))
	{
		if (executeAction(bondId, "SetDownLightBrightness", level)) 
		{
			device.sendEvent(name: "level", value: level)
		}
	}
    else 
	{
		if (executeAction(bondId, "SetBrightness", level)) 
		{
			device.sendEvent(name: "level", value: level)
		}
    }
}

def handleSetFlame(device, height)
{
	def bondId = getBondIdFromDevice(device)
	logDebug "Handling Flame event for ${bondId}"
	
	if (height == "off")
	{
		if (handleOff(device))
			device.sendEvent(name: "flame", value: "off")
	}
	else 
	{
		def flameHeight = 0
		if (height == "low")
			flameHeight = 1
		else if (height == "medium")
			flameHeight = 50
		else if (height == "high")
			flameHeight = 100
			
		if (executeAction(bondId, "SetFlame", flameHeight))
		{
			device.sendEvent(name: "flame", value: height)
		}
	}
}

def handleOpen(device)
{
	def bondId = getBondIdFromDevice(device)
	logDebug "Handling Open event for ${bondId}"
	
    if (executeAction(bondId, "Open")) 
    {
        device.sendEvent(name: "windowShade", value: "open")
    }
}

def handleClose(device)
{
	def bondId = getBondIdFromDevice(device)
	logDebug "Handling Close event for ${bondId}"
	
    if (executeAction(bondId, "Close")) 
    {
        device.sendEvent(name: "windowShade", value: "closed")
    }
}

def handleStop(device)
{
	def bondId = getBondIdFromDevice(device)
	logDebug "Handling Stop event for ${bondId}"
	
    executeAction(bondId, "Hold")
}

def handlePreset(device)
{
	def bondId = getBondIdFromDevice(device)
	logDebug "Handling Preset event for ${bondId}"
	
    executeAction(bondId, "Preset")
}

def fixPowerState(device, state) 
{
	def bondId = getBondIdFromDevice(device)
	logDebug "Setting power state for ${bondId} to ${state}"
	
	def power
	if (state == "on")
		power = 1
	else 
		power = 0

	if (executeFixState(bondId, '{"power": ' + power + '}'))
    {
		if (power == 1)
			device.sendEvent(name: "switch", value: "on")
		else
		{
			device.sendEvent(name: "switch", value: "off")
			if (device.hasAttribute("speed"))
				device.sendEvent(name: "speed", value: "off")
			if (device.hasAttribute("flame"))
				device.sendEvent(name: "flame", value: "off")
		}
    }
}

def fixFlameState(device, height)
{
	def bondId = getBondIdFromDevice(device)
	logDebug "Setting flame state for ${bondId} to ${height}"
	def flameHeight = 0
	if (height == "low")
		flameHeight = 1
	else if (height == "medium")
		flameHeight = 50
	else if (height == "high")
		flameHeight = 100
	if (executeFixState(bondId, '{"flame": ' + flameHeight + '}'))
		device.sendEvent(name: "flame", value: height)
}

def fixFanSpeed(device, fanState)
{
	fixSpeedState(device, "fan", fanState, "power", "speed")
}

def fixShadeState(device, state) 
{
	def bondId = getBondIdFromDevice(device)
	logDebug "Setting shade state for ${bondId} to ${state}"

	def open
	if (state == "open")
		open = 1
	else
		open = 0
		
	if (executeFixState(bondId, '{"open": ' + open + '}'))
    {
		if (open == 1)
		{
			device.sendEvent(name: "switch", value: "on")
			device.sendEvent(name: "windowShade", value: "open")
		}
		else
		{
			device.sendEvent(name: "switch", value: "off")
			device.sendEvent(name: "windowShade", value: "closed")
		}
    }
}

def fixDirection(device, state) 
{
	def bondId = getBondIdFromDevice(device)
	logDebug "Setting direction state for ${bondId} to ${state}"

	def direction
	if (state == "forward")
		direction = 1
	else
		direction = -1
		
	if (executeFixState(bondId, '{"direction": ' + direction + '}'))
    {
		if (direction == 1)
		{
			device.sendEvent(name: "direction", value: "forward")
		}
		else
		{
			device.sendEvent(name: "direction", value: "reverse")
		}
    }
}

def fixFPFanPower(device, state) 
{
	def bondId = getBondIdFromDevice(device)
	logDebug "Setting FP fan power state for ${bondId} to ${state}"

	def fppower
	if (state == "on")
		fppower = 1
	else
		fppower = 0
		
	if (executeFixState(bondId, '{"fpfan_power": ' + fppower + '}'))
    {
		if (fppower == 1)
		{
			device.sendEvent(name: "switch", value: "on")
			device.sendEvent(name: "speed", value: "on")
		}
		else
		{
			device.sendEvent(name: "switch", value: "off")
			device.sendEvent(name: "speed", value: "off")
		}
    }
}

def fixFPFanSpeed(device, fanState)
{
	fixSpeedState(device, "fireplace", fanState, "fpfan_power", "fpfan_speed")
}

// Fix Speed corrects BOND's record without transmitting. A speed also records power on,
// so the next poll keeps it; off records power off.
private void fixSpeedState(device, String kind, fanState, String powerKey, String speedKey) {
	def bondId = getBondIdFromDevice(device)
	logDebug "Setting fan speed for ${bondId} to ${fanState}"
	if (fanState == "off" || fanState == "on") {
		boolean on = fanState == "on"
		if (executeFixState(bondId, '{"' + powerKey + '": ' + (on ? 1 : 0) + '}')) {
			device.sendEvent(name: "switch", value: fanState)
			if (!on)
				device.sendEvent(name: "speed", value: "off")
		}
		return
	}
	Integer max = maxSpeed(kind, bondId)
	String name = resolveSpeed(max, fanState?.toString())
	if (name == null) {
		log.warn "${device.displayName}: BOND fans have no '${fanState}' speed, so nothing was changed"
		return
	}
	if (executeFixState(bondId, '{"' + powerKey + '": 1, "' + speedKey + '": ' + bondSpeed(max, name) + '}')) {
		device.sendEvent(name: "switch", value: "on")
		device.sendEvent(name: "speed", value: name)
	}
}

def fixLightPower(device, state) {
	def bondId = getBondIdFromDevice(device)
    logDebug "Setting light state for ${bondId} to ${state}"
	
	def power
	if (state == "on")
		power = 1
	else
		power = 0
		
	if (device.deviceNetworkId.contains("uplight"))
	{
		if (executeFixState(bondId, '{"up_light": ' + power + '}'))
		{
			device.sendEvent(name: "switch", value: state)
		}
	}
	else if (device.deviceNetworkId.contains("downlight"))
	{
		if (executeFixState(bondId, '{"down_light": ' + power + '}'))
		{
			device.sendEvent(name: "switch", value: state)
		}
	}
    else
	{
        if (executeFixState(bondId, '{"light": ' + power + '}'))
		{
			device.sendEvent(name: "switch", value: state)
		}
    }
}

def fixLightLevel(device, state) {
	def bondId = getBondIdFromDevice(device)
	logDebug "Setting light level for ${bondId} to ${state}"
	
	if (device.deviceNetworkId.contains("uplight"))
	{
		if (executeFixState(bondId, '{"up_light_brightness": ' + state + '}')) 
		{
			device.sendEvent(name: "level", value: state)
		}
		if (state == 0)
		{
			if (executeFixState(bondId, '{"up_light": 0}')) 
			{
				device.sendEvent(name: "switch", value: "off")
			}
		}
	}
	else if (device.deviceNetworkId.contains("downlight"))
	{
		if (executeFixState(bondId, '{"down_light_brightness": ' + state + '}'))
		{
			device.sendEvent(name: "level", value: state)
		}
		if (state == 0)
		{
			if (executeFixState(bondId, '{"down_light": 0}')) 
			{
				device.sendEvent(name: "switch", value: "off")
			}
		}
	}
    else 
	{
		if (executeFixState(bondId, '{"brightness": ' + state + '}'))
		{
			device.sendEvent(name: "level", value: state)
		}
		if (state == 0)
		{
			if (executeFixState(bondId, '{"light": 0}')) 
			{
				device.sendEvent(name: "switch", value: "off")
			}
		}
    }
}

// ---- Fan speeds ----

// The speed names a fan with `max` speeds has, low to high.
List speedNames(Integer max) {
	if (max == 1)
		return ["high"]
	if (max == 2)
		return ["low", "high"]
	if (max == 3)
		return ["low", "medium", "high"]
	if (max == 4)
		return ["low", "medium-low", "medium", "high"]
	return SPEED_ORDER
}

// Poll: BOND's speed number as a name; "on" when it isn't a speed this fan has.
String speedName(Integer max, speed) {
	Integer n = (speed != null && speed.toString().isInteger()) ? (speed.toString() as Integer) : null
	if (n == null || n < 1 || n > max)
		return "on"
	return BOND_TO_HE[max][n]
}

Integer bondSpeed(Integer max, String name) {
	return HE_TO_BOND[max][name]
}

// The name a request runs at on this fan: medium-low falls back to low, and medium-high
// (or medium, on 1-2 speed fans) to high; a 1-speed fan runs everything at high. A
// number is clamped to the fan's range. Null when the request isn't a speed ("auto").
String resolveSpeed(Integer max, String requested) {
	if (requested == null)
		return null
	if (requested.isInteger())
		return BOND_TO_HE[max][Math.min(Math.max(requested as Integer, 1), max)]
	if (speedNames(max).contains(requested))
		return requested
	if (!SPEED_ORDER.contains(requested))
		return null
	if (max == 1)
		return "high"
	return requested == "medium-low" ? "low" : "high"
}

String supportedSpeedsJson(Integer max) {
	return groovy.json.JsonOutput.toJson(speedNames(max) + ["on", "off"])
}

// Cycle Speed: off, on or unknown -> the lowest speed; then each speed; the highest -> off.
def nextSpeed(device) {
	def bondId = getBondIdFromDevice(device)
	String kind = device.deviceNetworkId.endsWith(":fan") ? "fireplace" : "fan"
	List names = speedNames(maxSpeed(kind, bondId))
	int i = names.indexOf(device.currentValue("speed"))
	if (i < 0)
		return names[0]
	return i == names.size() - 1 ? "off" : names[i + 1]
}

def handleFanSpeed(device, speed) {
	logDebug "Handling Fan Speed event for ${getBondIdFromDevice(device)}"
	if (speed == "off") {
		if (handleOff(device))
			device.sendEvent(name: "speed", value: "off")
		return null
	}
	if (speed == "on") {
		handleOn(device)
		return null
	}
	return sendSpeed(device, "fan", speed)
}

def handleFPFanSpeed(device, speed) {
	logDebug "Handling Fireplace Fan Speed event for ${getBondIdFromDevice(device)}"
	if (speed == "off") {
		handleFPFanOff(device)
		return null
	}
	if (speed == "on") {
		handleFPFanOn(device)
		return null
	}
	return sendSpeed(device, "fireplace", speed)
}

// SetSpeed, rounded to a speed the fan has. Returns the name set, or null.
private String sendSpeed(device, String kind, speed) {
	def bondId = getBondIdFromDevice(device)
	Integer max = maxSpeed(kind, bondId)
	String name = resolveSpeed(max, speed?.toString())
	if (name == null) {
		log.warn "${device.displayName}: BOND fans have no '${speed}' speed, so nothing was sent"
		return null
	}
	// Fireplace fans take SetFpFan when the fireplace lists it (upstream sent SetSpeed).
	String action = (kind == "fireplace" && state.fireplaceDetails?.get(bondId)?.contains("SetFpFan")) ? "SetFpFan" : "SetSpeed"
	if (!executeAction(bondId, action, bondSpeed(max, name)))
		return null
	device.sendEvent(name: "switch", value: "on")
	device.sendEvent(name: "speed", value: name)
	return name
}

def handleFPFanOn(device) {
	def bondId = getBondIdFromDevice(device)
	logDebug "Handling Fan On event for ${bondId}"
	
    if (executeAction(bondId, "TurnFpFanOn")) 
    {
        device.sendEvent(name: "switch", value: "on")
        device.sendEvent(name: "speed", value: "on")
        return true
    }
	
	return false
}

def handleFPFanOff(device) {
	def bondId = getBondIdFromDevice(device)
	logDebug "Handling Fan Off event for ${bondId}"
	
	
    if (executeAction(bondId, "TurnFpFanOff")) 
    {
        device.sendEvent(name: "switch", value: "off")
        device.sendEvent(name: "speed", value: "off")
        return true
    }
	
	return false
}

def handleOff(device) {
	def bondId = getBondIdFromDevice(device)
	logDebug "Handling Off event for ${bondId}"
	if (!executeAction(bondId, "TurnOff"))
		return false
	// With a power meter, the meter decides a fireplace's switch; the command still succeeded.
	if (shouldSendEvent(bondId)) {
		device.sendEvent(name: "switch", value: "off")
		if (device.hasCapability("FanControl"))
			device.sendEvent(name: "speed", value: "off")
	}
	return true
}

def handleDirection(device, direction)
{
	def bondId = getBondIdFromDevice(device)
	logDebug "Handling Direction event for ${bondId}"

    def bondDirection = 1
    if (direction == "reverse")
    bondDirection = -1
    if (executeAction(bondId, "SetDirection", bondDirection)) 
    {
        device.sendEvent(name: "direction", value: direction)
    }
    
}

def hasAction(bondId, commandType) {
	Map r = bondRequest("GET", "/v2/devices/${bondId}/actions", [timeout: 10])
	if (!r.ok) {
		log.warn "BOND hasAction: couldn't read the actions of ${bondId}: ${describeFailure(r)}"
		return false
	}
	return r.data?.containsKey(commandType) ?: false
}

def executeAction(bondId, action) {
	logDebug "${bondId} -> calling action ${action}"
	return bondCommand(bondId, "PUT", "/v2/devices/${bondId}/actions/${action}", "{}", action as String)
}

def executeAction(bondId, action, argument) {
	logDebug "${bondId} -> calling action ${action} with ${argument}"
	return bondCommand(bondId, "PUT", "/v2/devices/${bondId}/actions/${action}", '{"argument": ' + argument + '}', action as String)
}

def executeFixState(bondId, body) {
	logDebug "${bondId} -> fixing state ${body}"
	return bondCommand(bondId, "PATCH", "/v2/devices/${bondId}/state", body as String, "fix state ${body}".toString())
}

// A command or fix-state: true when BOND accepted it; otherwise one warning, every time.
private boolean bondCommand(bondId, String method, String path, String body, String what) {
	Map r = bondRequest(method, path, [body: body, timeout: 10])
	if (r.ok)
		return true
	log.warn "BOND didn't accept ${what} for ${deviceLabel(bondId)}: ${describeFailure(r)}"
	return false
}

// Every BOND request goes through here. Returns [ok: true, data:], [ok: false, status:, body:]
// for an HTTP error, or [ok: false, status: null, error:] when the bridge isn't answering.
// The token goes only in the header and is never logged.
private Map bondRequest(String method, String path, Map opts = [:]) {
	Map result = [ok: false, status: null]
	if (!hubIp || !hubToken) {
		result.error = "no bridge IP or token set"
		return result
	}
	def params = [
		uri: "http://${hubIp}",
		path: path,
		contentType: "application/json",
		headers: [ 'BOND-Token': hubToken ],
		timeout: opts.timeout ?: 10
	]
	if (opts.body != null)
		params.body = opts.body
	Closure handler = { resp ->
		if (resp.status in [200, 201, 204]) {
			result.ok = true
			result.data = resp.data
		}
		else {
			result.status = resp.status
			result.body = resp.data
		}
	}
	try {
		if (method == "GET")
			httpGet(params, handler)
		else if (method == "PUT")
			httpPut(params, handler)
		else
			httpPatch(params, handler)
	}
	catch (groovyx.net.http.HttpResponseException e) {
		result.status = e.statusCode
		result.body = e.response?.data
	}
	catch (Exception e) {
		result.error = e.toString()
	}
	return result
}

private String describeFailure(Map r) {
	if (r.status != null)
		return "HTTP ${r.status}" + (r.body != null ? " ${r.body}" : "")
	return "bridge not answering (${r.error})"
}

private String deviceLabel(bondId) {
	def d = findChildDevice(bondId)
	return d ? "${d.displayName} (${bondId})" : "${bondId}"
}

def shouldSendEvent(bondId) {
	for (fan in fans) 
	{
		if (fan == bondId)
			return true;
	}
	
	if (fireplaces != null)
	{
		for (def i = 0; i < fireplaces.size(); i++)
		{
			if (fireplaces[i] == bondId)
			{
				if (this.getProperty("fireplaceSensor${i}") != null)
					return false;
				return true;
			}
		}
	}
	return true;
}

def logDebug(msg) {
    if (settings?.debugOutput) {
		log.debug msg
	}
}

def logsOff() {
	log.info "BOND: debug logging turned off"
	app.updateSetting("debugOutput", [value: "false", type: "bool"])
}

def displayFooter(){
	section() {
		paragraph getFormat("line")
		paragraph "<div style='color:#1A77C9;text-align:center'>BOND Home Integration<br><a href='https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=7LBRPJRLJSDDN&source=url' target='_blank'><img src='https://www.paypalobjects.com/webstatic/mktg/logo/pp_cc_mark_37x23.jpg' border='0' alt='PayPal Logo'></a><br><br>Please consider donating. This app took a lot of work to make.<br>If you find it valuable, I'd certainly appreciate it!</div>"
	}       
}

def getFormat(type, myText=""){			// Modified from @Stephack Code   
    if(type == "line") return "<hr style='background-color:#1A77C9; height: 1px; border: 0;'>"
    if(type == "title") return "<h2 style='color:#1A77C9;font-weight: bold'>${myText}</h2>"
}
