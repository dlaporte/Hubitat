/*
 *
 * Modified by David LaPorte
 *  Based on the Tank Utility app from EricS, based on ideas from Joshua Spain
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *	http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */

import groovy.json.*
import java.text.SimpleDateFormat
import groovy.time.*
import groovy.transform.Field

definition(
	name: "Smart Oil Gauge (Connect)",
	namespace: "dlaporte",
	author: "David LaPorte",
	description: "Virtual device handler for Smart Oil Gauge",
	category: "My Apps",
	iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
	iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
	iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
	singleInstance: true,
	oauth:true
)

static String appVersion() { "0.0.2" }

preferences {
	page(name: "settings", title: "Settings", content: "settingsPage", install:true)
}

mappings {
	path("/deviceTiles")    {action: [GET: "renderDeviceTiles"]}
	path("/getTile/:dni")   {action: [GET: "getTile"]}
}

private static String SmartOilGaugeAPIEndPoint(){ return "https://api.dropletfuel.com" }
private static String SmartOilGaugeDataEndPoint(){ return SmartOilGaugeAPIEndPoint() }
private static getChildName(){ return "Smart Oil Gauge" }

void installed(){
	log.info "Installed with settings: ${settings}"
	initialize()
}

void updated(){
	log.info "Updated with settings: ${settings}"
	initialize()
}

void uninstalled(){
	def todelete = getAllChildDevices()
	todelete.each { deleteChildDevice(it.deviceNetworkId) }
}

void initialize(){
	LogTrace("initialize")

	settingUpdate("showDebug", "true", "bool")
	Boolean traceWasOn = false
	if(settings.advAppDebug){
		traceWasOn = true
	}
	settingUpdate("advAppDebug", "true", "bool")

	if(!state.autoTyp){ state.autoTyp = "chart" }
	unsubscribe()
	unschedule()

	setAutomationStatus()

	List devs = getDevices()
	Map devicestatus = RefreshDeviceStatus()
	Boolean quickOut = false
	devs.each { String dev ->
		String ChildName = getChildName()
		String SOGDeviceID = dev
		String dni = getDeviceDNI(SOGDeviceID)
        LogTrace("ChildName: " + ChildName)
		Map devinfo = devicestatus[SOGDeviceID]
		def d = getChildDevice(dni)
		if(!d){
            label = "Tank " + devinfo.tank_num
            d = addChildDevice("dlaporte", ChildName, dni)
                        
			LogAction("created ${d.displayName} with dni: ${dni}", "info", true)
			runIn(5, "updated", [overwrite: true])
			quickOut = true
			return
		}else{
		}
		if(d){
			LogAction("device for ${d.displayName} with dni ${dni} found", "info", true)
			subscribe(d, "energy", automationGenericEvt)
		}
		return d
	}
	if(quickOut){ return } // we'll be back with runIn after devices settle

	subscribe(location, "sunrise", automationGenericEvt)

	runEvery1Hour("pollChildren")

	scheduleAutomationEval(30)

	if(!traceWasOn){
		settingUpdate("advAppDebug", "false", "bool")
	}
	runIn(1800, logsOff, [overwrite: true])

	pollChildren(false)
}

private settingsPage(){
	if(!state.access_token){ getAccessToken() }
	if(!state.access_token){ enableOauth(); getAccessToken() }

	return dynamicPage(name: "settings", title: "Settings", nextPage: "", uninstall:true, install:true){
	 	Boolean message = getToken()
		if(!message){
			section("Authentication"){
				paragraph "${state.lastErr} Enter your Smart Oil Gauge Client ID and Secret."
				input "ClientId", "string", title: "Smart Oil Gauge Client ID", required: true
				input "ClientSecret", "string", title: "Smart Oil Gauge Client Secret", required: true, submitOnChange: true
			}
		}else{
			section("Authentication"){
				paragraph "Authentication Succeeded!"
			}

			section(){
				List devs = state.devices
				if(!devs){
					devs = getDevices()
					Map devicestatus = RefreshDeviceStatus()
				}
				String t1 = ""
				t1 = devs?.size() ? "Status\n • Tanks (${devs.size()})" : ""
				if(devs?.size() > 1){
					String myUrl = getAppEndpointUrl("deviceTiles")
					String myLUrl = getLocalEndpointUrl("deviceTiles")
					String myStr = """ <a href="${myUrl}" target="_blank">All Tanks</a>   <a href="${myLUrl}" target="_blank">(local)</a>"""
					paragraph imgTitle(getAppImg("graph_icon.png"), paraTitleStr(myStr))
				}
				devs.each { dev ->
					String deviceid = dev
					String dni = getDeviceDNI(deviceid)
					def d1 = getChildDevice(dni)
					if(!d1){ return }

					String myUrl = getAppEndpointUrl("getTile/"+dni)
					String myLUrl = getLocalEndpointUrl("getTile/"+dni)

                    String myStr = """ <a href="${myUrl}" target="_blank">${d1.label ?: d1.name}</a> <a href="${myLUrl}" target="_blank">(local)</a>"""
					paragraph imgTitle(getAppImg("graph_icon.png"), paraTitleStr(myStr))
				}
			}
		}

		section(sectionTitleStr("Automation Options:")){
			input "autoDisabledreq", "bool", title: imgTitle(getAppImg("disable_icon2.png"), inputTitleStr("Disable this Automation?")), required: false, defaultValue: false /* state.autoDisabled */, submitOnChange: true
			setAutomationStatus()

			input("showDebug", "bool", title: imgTitle(getAppImg("debug_icon.png"), inputTitleStr("Debug Option")), description: "Show ${app?.name} Logs in the IDE?", required: false, defaultValue: false, submitOnChange: true)
			if(showDebug){
				input("advAppDebug", "bool", title: imgTitle(getAppImg("list_icon.png"), inputTitleStr("Show Verbose Logs?")), required: false, defaultValue: false, submitOnChange: true)
			}else{
				settingUpdate("advAppDebug", "false", "bool")
			}
		}
		section(sectionTitleStr("Application Security")){
			paragraph title:"What does resetting do?", "If you share a url with someone and want to remove their access you can reset your token and this will invalidate any URL you shared and create a new one for you.  This will require any use in dashboards to be updated to the new URL."
			input (name: "resetAppAccessToken", type: "bool", title: "Reset Access Token?", required: false, defaultValue: false, submitOnChange: true, image: getAppImg("reset_icon.png"))
			resetAppAccessToken(settings.resetAppAccessToken == true)
		}
	}
}

List getDevices(){
	LogTrace("getDevices()")
	List devices = []

	if(!getToken()){
		LogAction("getDevice: no token available", "info", true)
		return devices
	}
	def Params = [
		uri: SmartOilGaugeDataEndPoint(),
		path: "/auto/get_tank_data.php",
		body: [
			'access_token': state.APIToken,
            'start_index': 0,
            'max_results': 1000
		],
	]
	
	try {
		httpPost(Params){ resp ->
			if(resp.status == 200) {
                obs = parseJson(resp.data.toString())
                obs["data"].each { dev ->
					LogAction("Found device ID: ${dev.sensor_id}", "debug", false)
                    devices += dev.sensor_id
				}         
			}else{
				LogAction("Error from Smart Oil Gauge in getDevices - Return Code: ${resp.status} | Response: ${resp.data}", "error", true)
				state.APIToken = null
				state.APITokenExpirationTime = 0L
			}
		}
		state.devices = devices
	} catch (e){
		log.error "Error in getDevices: $e"
		state.APIToken = null
		state.APITokenExpirationTime = 0L
	}
	return devices
}

private Map RefreshDeviceStatus(){
	LogTrace("RefreshDeviceStatus()")
	Map deviceData = [:]

	if(!getToken()){
		LogAction("RefreshDeviceStatus: no token available", "info", true)
		return deviceData
	}
    
	def Params = [
		uri: SmartOilGaugeDataEndPoint(),
		path: "/auto/get_tank_data.php",
		body: [
			'access_token': state.APIToken,
            'start_index': 0,
            'max_results': 1000
		]
    ]
    
	try {
		httpPost(Params){ resp ->
            if(resp.status == 200) {
                obs = parseJson(resp.data.toString())
                obs["data"].each { dev ->
                    LogTrace("tank " + dev.tank_num)
                    String dni = [app.id, dev.sensor_id].join('.')
                    deviceData[dev.sensor_id] = dev
                    LogTrace("RefreshDeviceStatus: received device data for ${dni} = ${deviceData[dev.sensor_id]}")
                }
			} else {
				LogAction("RefreshDeviceStatus: Error while receiving events ${resp.status}", "error", true)
				state.APIToken = null
				state.APITokenExpirationTime = 0L
			}
        }
    } catch (e){
        log.error "Error while processing events for RefreshDeviceStatus ${e}"
		state.APIToken = null
		state.APITokenExpirationTime = 0L
	}
	state.deviceData = deviceData
	return deviceData
}

private Boolean getToken(){
	LogTrace("getToken()")
	Boolean message = true
	if(!settings.ClientId || !settings.ClientSecret){
		LogAction("getToken no ClientId", "warn", false)
		return false
	 }
	if (isTokenExpired() || !state.APIToken){
		LogTrace("API token expired at ${state.APITokenExpirationTime}. Refreshing API Token")
		message = getAPIToken()
		if(!message){
			log.warn "getToken $message  Was not able to refresh API token expired at ${state.APITokenExpirationTime}."
		}
	} 
	return message
}

Boolean isTokenExpired(){
	Long currentDate = now()
	if (!state.APITokenExpirationTime){
		return true
	}else{
		Long ExpirationDate = state.APITokenExpirationTime
		if (currentDate >= ExpirationDate){return true}else{return false}
	}
}

private Boolean getAPIToken(){
	log.trace "getAPIToken()Requesting an API Token!"
	def Params = [
		uri: SmartOilGaugeAPIEndPoint(),
		path: "/brand_token.php",
		//headers: ['Authorization': "Basic ${getBase64AuthString()}"]
        body: [
            'grant_type': 'client_credentials',
            'client_id': settings.ClientId,
            'client_secret': settings.ClientSecret
        ]
	]

	try {
		httpPost(Params){ resp ->
			LogAction("getToken Return Code: ${resp.status} Response: ${resp.data}", "debug", false)
			if(resp.status == 200){
				if (resp.data.access_token){
					state.APIToken = resp?.data?.access_token
					state.APITokenExpirationTime = now() + (60 * 60 * 1000)
					LogAction("Token refresh Success. Token expires at ${state.APITokenExpirationTime}", "info", false)
					state.lastErr=""
					return true
				} 
			}
			state.lastErr="Was not able to refresh API token ${resp.data.error}"
		}
	} catch (e){
        
		state.lastErr="Error in the getAPIToken method: $e"
	}
	if(state.lastErr){
		log.error "returning false getAPIToken ${state.lastErr}"
		state.APIToken = null
		state.APITokenExpirationTime = 0L
		return false
	}
	return true
}

void pollChildren(Boolean updateData=true){
	Long execTime = now()
	LogTrace("pollChildren")
	if(!getToken()){
		LogAction("pollChildren: Was not able to refresh API token expired at ${state.APITokenExpirationTime}.", "warn", true)
		return
	}
	List devices = state.devices
	if(!devices){
		LogAction("pollChildren: no devices available", "warn", true)
		return
	}
	Map deviceData
	if(updateData){
		deviceData = RefreshDeviceStatus()
	}else{
		deviceData = state.deviceData
	}
	devices.each {dev ->
		try {
			String deviceid = dev
			String dni = getDeviceDNI(deviceid)
			def d = getChildDevice(dni)
			if(!d){
				LogAction("pollChildren: no device found $dni", "warn", true)
				return false
			}
			def devData = deviceData[deviceid]
			if(!devData){
				LogAction("pollChildren: no device data available $d.label", "warn", true)
				return false
			}
            def gallons = devData.gallons
			def level = ((devData.gallons.toFloat()/devData.tank_volume.toFloat())*100).round(2)
			def lastReadTime = devData.last_read
			def capacity = devData.tank_volume
//            def battery = devData.battery
            def battery
            if (devData.battery == "Good") {
                battery = 100
            } else if (devData.battery == "Fair") {
                battery = 50
            } else if (devData.battery == "Poor") {
                battery = 1
            } else {
                battery = 0
            }
			def events = [
				['level': level],
				['energy': level],
				['capacity': capacity],
				['lastreading': lastReadTime],
                ['battery': battery]
			]
			LogAction("pollChidren: Sending events: ${events}", "info", false)
			events.each {
				event -> d.generateEvent(event)
			}
			LogTrace("pollChildren: sent device data for ${deviceid} = ${devData}")
		} catch (e){
			log.error "pollChildren: Error while sending  events for pollChildren: ${e}"
		}
	}
	storeExecutionHistory((now()-execTime), "pollChildren")
}

String getDeviceDNI(String DeviceID){
	return [app.id, DeviceID].join('.')
}

static String strCapitalize(str){
	return str ? str?.toString().capitalize() : null
}

void automationGenericEvt(evt){
	Long startTime = now()
	Long eventDelay = startTime - evt.date.getTime()
	LogAction("${evt?.name.toUpperCase()} Event | Device: ${evt?.displayName} | Value: (${strCapitalize(evt?.value)}) with a delay of ${eventDelay}ms", "info", false)

	doTheEvent(evt)
}

void doTheEvent(evt){
	if(getIsAutomationDisabled()){ return }
	else {
		scheduleAutomationEval()
		storeLastEventData(evt)
	}
}

void storeLastEventData(evt){
	if(evt){
		def newVal = ["name":evt.name, "displayName":evt.displayName, "value":evt.value, "date":formatDt(evt.date), "unit":evt.unit]
		state.lastEventData = newVal
		//log.debug "LastEvent: ${state.lastEventData}"

		List list = state.detailEventHistory ?: []
		Integer listSize = 10
		if(list?.size() < listSize){
			list.push(newVal)
		}
		else if(list?.size() > listSize){
			Integer nSz = (list?.size()-listSize) + 1
			List nList = list?.drop(nSz)
			nList?.push(newVal)
			list = nList
		}
		else if(list?.size() == listSize){
			List nList = list?.drop(1)
			nList?.push(newVal)
			list = nList
		}
		if(list){ state.detailEventHistory = list }
	}
}

void storeExecutionHistory(val, String method = null){
	//log.debug "storeExecutionHistory($val, $method)"
	//try {
		if(method){
			LogTrace("${method} Execution Time: (${val} milliseconds)")
		}
		if(method in ["watchDogCheck", "checkNestMode", "schMotCheck"]){
			state.autoExecMS = val ?: null
			List list = state.evalExecutionHistory ?: []
			Integer listSize = 20
			list = addToList(val, list, listSize)
			if(list){ state.evalExecutionHistory = list }
		}
		//if(!(method in ["watchDogCheck", "checkNestMode"])){
			List list = state.detailExecutionHistory ?: []
			Integer listSize = 15
			list = addToList([val, method, getDtNow()], list, listSize)
			if(list){ state.detailExecutionHistory = list }
		//}
/*
	} catch (ex){
		log.error "storeExecutionHistory Exception:", ex
		//parent?.sendExceptionData(ex, "storeExecutionHistory", true, getAutoType())
	}
*/
}

List addToList(val, list, Integer listSize){
	if(list?.size() < listSize){
		list.push(val)
	} else if(list?.size() > listSize){
		Integer nSz = (list?.size()-listSize) + 1
		List nList = list?.drop(nSz)
		nList?.push(val)
		list = nList
	} else if(list?.size() == listSize){
		List nList = list?.drop(1)
		nList?.push(val)
		list = nList
	}
	return list
}

static Integer defaultAutomationTime(){
	return 5
}

void scheduleAutomationEval(Integer schedtime = defaultAutomationTime()){
	Integer theTime = schedtime
	if(theTime < defaultAutomationTime()){ theTime = defaultAutomationTime() }
	String autoType = getAutoType()
	def random = new Random()
	Integer random_int = random.nextInt(6)  // this randomizes a bunch of automations firing at same time off same event
	Boolean waitOverride = false
	switch(autoType){
		case "chart":
			if(theTime == defaultAutomationTime()){
				theTime += random_int
			}
			Integer schWaitVal = settings.schMotWaitVal?.toInteger() ?: 60
			if(schWaitVal > 120){ schWaitVal = 120 }
			Integer t0 = getAutoRunSec()
			if((schWaitVal - t0) >= theTime ){
				theTime = (schWaitVal - t0)
				waitOverride = true
			}
			//theTime = Math.min( Math.max(theTime,defaultAutomationTime()), 120)
			break
	}
	if(!state.evalSched){
		runIn(theTime, "runAutomationEval", [overwrite: true])
		state.autoRunInSchedDt = getDtNow()
		state.evalSched = true
		state.evalSchedLastTime = theTime
	}else{
		String str = "scheduleAutomationEval: "
		Integer t0 = state.evalSchedLastTime
		if(t0 == null){ t0 = 0 }
		Integer timeLeftPrev = t0 - getAutoRunInSec()
		if(timeLeftPrev < 0){ timeLeftPrev = 100 }
		String str1 = " Schedule change: from (${timeLeftPrev}sec) to (${theTime}sec)"
		if(timeLeftPrev > (theTime + 5) || waitOverride){
			if(Math.abs(timeLeftPrev - theTime) > 3){
				runIn(theTime, "runAutomationEval", [overwrite: true])
				state.autoRunInSchedDt = getDtNow()
				state.evalSched = true
				state.evalSchedLastTime = theTime
				LogTrace("${str}Performing${str1}")
			}
		}else{ LogTrace("${str}Skipping${str1}") }
	}
}


Integer getAutoRunSec(){ return !state.autoRunDt ? 100000 : GetTimeDiffSeconds(state.autoRunDt, null, "getAutoRunSec").toInteger() }

Integer getAutoRunInSec(){ return !state.autoRunInSchedDt ? 100000 : GetTimeDiffSeconds(state.autoRunInSchedDt, null, "getAutoRunInSec").toInteger() }

void runAutomationEval(){
	LogTrace("runAutomationEval")
	Long execTime = now()
	String autoType = getAutoType()
	state.evalSched = false
	state.evalSchedLastTime = null
	switch(autoType){
		case "chart":
			List devs = state.devices
			devs.each { dev ->
				String deviceid = dev
				def deviceData = state.deviceData
				def devData = deviceData[deviceid]
				def LastReading = devData.lastReading
				String dni = getDeviceDNI(deviceid)
				def d1 = getChildDevice(dni)
           
				def level = ((devData.gallons.toFloat() / devData.tank_volume.toFloat())*100).round(2)
				getSomeData(d1, level)
			}
			break
		default:
			LogAction("runAutomationEval: Invalid Option Received ${autoType}", "warn", true)
			break
	}
	storeExecutionHistory((now()-execTime), "runAutomationEval")
}


void getSomeData(dev, level){
	LogAction("getSomeData: ${level}", "info", false)
	if (state."TEnergyTbl${dev.id}" == null){
		state."TEnergyTbl${dev.id}" = []
	}

	List energyTbl = state."TEnergyTbl${dev.id}"

	Date newDate = new Date()
	if(newDate == null){ Logger("got null for new Date()") }

	Integer dayNum = newDate.format("D", location.timeZone) as Integer

	state."TEnergyTbl${dev.id}" =	addValue(energyTbl, dayNum, level)
}

List addValue(List table, Integer dayNum, val){
	List newTable = table
	if(table?.size()){
		Integer lastDay = table.last()[0]
        
		if(lastDay == dayNum /* || (val == last && val == secondtolast)*/ ){
			newTable = table.take(table.size() - 1)
		}
	}
	newTable.add([dayNum, val])
	while(newTable.size() > 365){ newTable.removeAt(0) }
	return newTable
}


def getTile(){
	LogTrace ("getTile()")
	String responseMsg = ""

	String dni = "${params?.dni}"
	if (dni){
		def device = getChildDevice(dni)
//		def device = parent.getDevice(dni)
		if (device){
			return renderDeviceTiles(null, device)
		}else{
			responseMsg = "Device '${dni}' Not Found"
		}
	}else{
		responseMsg = "Invalid Parameters"
	}
	render contentType: "text/html",
		data: "${responseMsg}"
}

def renderDeviceTiles(type=null, theDev=null){
	Long execTime = now()
//	try {
		String devHtml = ""
		String navHtml = ""
		String scrStr = ""
		def allDevices = []
		if(theDev){
			allDevices << theDev
		}else{
			allDevices = app.getChildDevices(true)
		}


		def devices = allDevices
		Integer devNum = 1
		String myType = type ?: "All Devices"
		devices?.sort {it?.getLabel()}.each { dev ->
			def navMap = [:]
			Boolean hasHtml = true // (dev?.hasHtml() == true)
			if((dev?.typeName in ["Smart Oil Gauge"]) &&
				((hasHtml && !type) || (hasHtml && type && dev?.name == type)) ){
                LogTrace("renderDeviceTiles: ${dev.id} ${dev.name} ${theDev?.name} ${dev.typeName}")
				navMap = ["key":dev?.getLabel(), "items":[]]
				def navItems = navHtmlBuilder(navMap, devNum)
				String myTile = getEDeviceTile(devNum, dev) //dev.name == "Nest Thermostat" ? getTDeviceTile(devNum, dev) : getWDeviceTile(devNum, dev)
				if(navItems?.html){ navHtml += navItems?.html }
				if(navItems?.js){ scrStr += navItems?.js }

				devHtml += """
				<div class="panel panel-primary" style="max-width: 600px; margin: 30 auto; position: relative;">
					<div id="key-item${devNum}" class="panel-heading">
						<h1 class="panel-title panel-title-text">${dev?.getLabel()}</h1>
					</div>
					<div class="panel-body">
						<div style="margin: auto; position: relative;">
							<div>${myTile}</div>
						</div>
					</div>
				</div>
				"""
				devNum = devNum+1
			}
		}

		String myTitle = "All Devices"
		myTitle = type ? "${type}s" : myTitle
		myTitle = theDev ? "Tank Chart" : myTitle
		String html = """
		<html lang="en">
			<head>
				${getWebHeaderHtml(myType, true, true, true, true)}
				<link rel="stylesheet" href="https://cdn.rawgit.com/tonesto7/nest-manager/master/Documents/css/diagpages_new.css">
				<style>
					h1, h2, h3, h4, h5, h6 {
						padding: 20px;
						margin: 4px;
					}
				</style>
			</head>
			<body style="background-color:powderblue;">
				<button onclick="topFunction()" id="scrollTopBtn" title="Go to top"><i class="fa fa-arrow-up centerText" aria-hidden="true"></i></button>
				<nav id="menu-page" class="pushy pushy-left" data-focus="#nav-key-item1">
					<div class="nav-home-btn centerText"><button id="goHomeBtn" class="btn-link" title="Go Back to Home Page"><i class="fa fa-home centerText" aria-hidden="true"></i> Go Home</button></div>
					<!--Include your navigation here-->
					${navHtml}
				</nav>
				<!-- Site Overlay -->
				<div class="site-overlay"></div>

				<!-- Your Content -->
				<div id="container">
					<div id="top-hdr" class="navbar navbar-default navbar-fixed-top">
						<div class="centerText">
							<div class="row">
								<div class="col-xs-2">
									<div class="left-head-col pull-left">
										<div class="menu-btn-div">
											<div class="hamburger-wrap">
												<button id="menu-button" class="menu-btn hamburger hamburger--collapse hamburger--accessible" title="Menu" type="button">
													<span class="hamburger-box">
														<span class="hamburger-inner"></span>
													</span>
													<!--<span class="hamburger-label">Menu</span>-->
												</button>
											</div>
										</div>
									</div>
								</div>
								<div class="col-xs-8 centerText">
									<h3 class="title-text"><img class="logoIcn" src="https://raw.githubusercontent.com/dlaporte/SmartThings/master/DeviceHandlers/rollie-gauge/images/oil-icon.png"> ${myTitle}</img></h3>
								</div>
								<div class="col-xs-2 right-head-col pull-right">
									<button id="rfrshBtn" type="button" class="btn refresh-btn pull-right" title="Refresh Page Content"><i id="rfrshBtnIcn" class="fa fa-refresh" aria-hidden="true"></i></button>
								</div>
							</div>
						</div>
					</div>
					<!-- Page Content -->
					<div id="page-content-wrapper">
						<div class="container">
							<div id="main" class="panel-body">
								${devHtml}
							</div>
						</div>
					</div>
				</div>
				<script>
					\$("body").flowtype({
						minFont: 7,
						maxFont: 10,
						fontRatio: 30
					});
				</script>
				<script src="https://cdn.rawgit.com/tonesto7/nest-manager/master/Documents/js/diagpages.min.js"></script>
				<script>
					\$(document).ready(function(){
						${scrStr}
					});
					\$("#goHomeBtn").click(function(){
						closeNavMenu();
						toggleMenuBtn();
						window.location.replace('${getAppEndpointUrl("deviceTiles")}');
					});
				</script>
			</body>
		</html>
		"""
/* """ */
		storeExecutionHistory((now()-execTime), "renderDeviceTiles")
		render contentType: "text/html", data: html
//	} catch (ex){ log.error "renderDeviceData Exception:", ex }
}

Map navHtmlBuilder(navMap, Integer idNum){
	Map res = [:]
	String htmlStr = ""
	String jsStr = ""
	if(navMap?.key){
		htmlStr += """
			<div class="nav-cont-bord-div nav-menu">
			  <div class="nav-cont-div">
				<li class="nav-key-item"><a id="nav-key-item${idNum}">${navMap?.key}<span class="icon"></span></a></li>"""
		jsStr += navJsBuilder("nav-key-item${idNum}", "key-item${idNum}")
	}
	if(navMap?.items){
		def nItems = navMap?.items
		nItems?.each {
			htmlStr += """\n<li class="nav-subkey-item"><a id="nav-subitem${idNum}-${it?.toString().toLowerCase()}">${it}<span class="icon"></span></a></li>"""
			jsStr += navJsBuilder("nav-subitem${idNum}-${it?.toString().toLowerCase()}", "item${idNum}-${it?.toString().toLowerCase()}")
		}
	}
	htmlStr += """\n		</div>
						</div>"""
	res["html"] = htmlStr
	res["js"] = jsStr
	return res
}

String navJsBuilder(String btnId, String divId){
	String res = """
			\$("#${btnId}").click(function(){
				\$("html, body").animate({scrollTop: \$("#${divId}").offset().top - hdrHeight - 20},500);
				closeNavMenu();
				toggleMenuBtn();
			});
	"""
	return "\n${res}"
}


String getWebHeaderHtml(String title, Boolean clipboard=true, Boolean vex=false, Boolean swiper=false, Boolean charts=false){
	String html = """
		<meta charset="utf-8">
		<meta name="viewport" content="width=device-width, initial-scale=1, shrink-to-fit=no">
		<meta name="description" content="NST Graphs">
		<meta name="author" content="Anthony S.">
		<meta http-equiv="cleartype" content="on">
		<meta name="MobileOptimized" content="320">
		<meta name="HandheldFriendly" content="True">
		<meta name="apple-mobile-web-app-capable" content="yes">

		<title>Smart Oil Gauge Graphs ('${location?.name}') - ${title}</title>

		<script src="https://cdnjs.cloudflare.com/ajax/libs/jquery/3.2.1/jquery.min.js"></script>
		<link href="https://fonts.googleapis.com/css?family=Roboto" rel="stylesheet">
		<script src="https://use.fontawesome.com/fbe6a4efc7.js"></script>
		<script src="https://fastcdn.org/FlowType.JS/1.1/flowtype.js"></script>
		<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/normalize/7.0.0/normalize.min.css">
		<link rel="stylesheet" href="https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/css/bootstrap.min.css" integrity="sha384-BVYiiSIFeK1dGmJRAkycuHAHRg32OmUcww7on3RYdg4Va+PmSTsz/K68vbdEjh4u" crossorigin="anonymous">
		<link rel="stylesheet" href="https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/css/bootstrap-theme.min.css" integrity="sha384-rHyoN1iRsVXV4nD0JutlnGaslCJuC7uwjduW9SVrLvRYooPp2bWYgmgJQIXwl/Sp" crossorigin="anonymous">
		<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/hamburgers/0.9.1/hamburgers.min.css">
		<script src="https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/js/bootstrap.min.js" integrity="sha384-Tc5IQib027qvyjSMfHjOMaLkfuWVxZxUPnCJA7l2mCWNIpG9mGCD8wGNIcPD7Txa" crossorigin="anonymous"></script>
		<script type="text/javascript">
			const serverUrl = '${apiServerUrl('')}';
			const cmdUrl = '${getAppEndpointUrl('deviceTiles')}';
		</script>
	"""
	html += clipboard ? """<script src="https://cdnjs.cloudflare.com/ajax/libs/clipboard.js/1.7.1/clipboard.min.js"></script>""" : ""
	html += vex ? """<script type="text/javascript" src="https://cdnjs.cloudflare.com/ajax/libs/vex-js/3.1.0/js/vex.combined.min.js"></script>""" : ""
	html += swiper ? """<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/Swiper/4.3.3/css/swiper.min.css" />""" : ""
	html += vex ? """<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/vex-js/3.1.0/css/vex.min.css" />""" : ""
	html += vex ? """<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/vex-js/3.1.0/css/vex-theme-top.min.css" />""" : ""
	html += swiper ? """<script src="https://cdnjs.cloudflare.com/ajax/libs/Swiper/4.3.3/js/swiper.min.js"></script>""" : ""
	html += charts ? """<script src="https://www.gstatic.com/charts/loader.js"></script>""" : ""
	html += vex ? """<script>vex.defaultOptions.className = 'vex-theme-default'</script>""" : ""

	return html
}

String hideChartHtml(){
	String data = """
		<div class="swiper-slide">
			<section class="sectionBg" style="min-height: 250px;">
				<h3>Event History</h3>
			<br>
			<div class="centerText">
				<p>Waiting for more data to be collected...</p>
				<p>This may take a few hours</p>
			</div>
			</section>
		</div>
	"""
	return data
}

String getAutoType(){
	return state.autoTyp ?: (String)null
}

String getAutomationType(){
	return state.autoTyp ?: (String)null
}

String getAppEndpointUrl(String subPath){ return "${getFullApiServerUrl()}${subPath ? "/${subPath}" : ""}?access_token=${state.access_token}" }
String getLocalEndpointUrl(String subPath){ return "${getFullLocalApiServerUrl()}${subPath ? "/${subPath}" : ""}?access_token=${state.access_token}" }

Boolean getAccessToken(){
	try {
		if(!state.access_token){ state.access_token = createAccessToken() }
		else { return true }
	}
	catch (ex){
		String msg = "Error: OAuth is not Enabled for ${app?.name}!."
	//	sendPush(msg)
		log.error "getAccessToken Exception ${ex?.message}"
		LogAction("getAccessToken Exception | $msg", "warn", true)
		return false
	}
}

void enableOauth(){
	def params = [
			uri: "http://localhost:8080/app/edit/update?_action_update=Update&oauthEnabled=true&id=${app.appTypeId}",
			headers: ['Content-Type':'text/html;charset=utf-8']
	]
	try {
		httpPost(params){ resp ->
			//LogTrace("response data: ${resp.data}")
		}
	} catch (e){
		log.debug "enableOauth something went wrong: ${e}"
	}
}

void resetAppAccessToken(Boolean reset){
	if(!reset){ return }
	LogAction("Resetting Access Token....", "info", true)
	//revokeAccessToken()
	state.access_token = null
	state.accessToken = null
	if(getAccessToken()){
		LogAction("Reset Access Token... Successful", "info", true)
		settingUpdate("resetAppAccessToken", "false", "bool")
	}
}

static String sectionTitleStr(String title)	{ return '<h3>'+title+'</h3>' }
static String inputTitleStr(String title)	{ return '<u>'+title+'</u>' }
static String pageTitleStr(String title)		{ return '<h1>'+title+'</h1>' }
static String paraTitleStr(String title)		{ return '<b>'+title+'</b>' }

static String imgTitle(String imgSrc, String titleStr, String color=null, Integer imgWidth=30, Integer imgHeight=null){
	String imgStyle = ""
	imgStyle += imgWidth ? "width: ${imgWidth}px !important;" : ""
	imgStyle += imgHeight ? "${imgWidth ? " " : ""}height: ${imgHeight}px !important;" : ""
	if(color){ return """<div style="color: ${color}; font-weight: bold;"><img style="${imgStyle}" src="${imgSrc}"> ${titleStr}</img></div>""" }
	else { return """<img style="${imgStyle}" src="${imgSrc}"> ${titleStr}</img>""" }
}

static String icons(String name, String napp="App"){
	def icon_names = [
		"i_dt": "delay_time",
		"i_not": "notification",
		"i_calf": "cal_filter",
		"i_set": "settings",
		"i_sw": "switch_on",
		"i_mod": "mode",
		"i_hmod": "hvac_mode",
		"i_inst": "instruct",
		"i_err": "error",
		"i_cfg": "configure",
		"i_t": "temperature"

	]
	//return icon_names[name]
	String t0 = icon_names?."${name}"
	//LogAction("t0 ${t0}", "warn", true)
	if(t0) return "https://raw.githubusercontent.com/${gitPath()}/Images/$napp/${t0}_icon.png".toString()
	else return "https://raw.githubusercontent.com/${gitPath()}/Images/$napp/${name}".toString()
}

static String gitRepo()	{ return "tonesto7/nest-manager"}
static String gitBranch()	{ return "master" }
static String gitPath()	{ return "${gitRepo()}/${gitBranch()}"}

String getAppImg(String imgName, Boolean on = null){
	return (!disAppIcons || on) ? icons(imgName) : ""
}

String getDevImg(String imgName, Boolean on = null){
	return (!disAppIcons || on) ? icons(imgName, "Devices") : ""
}

void logsOff(){
	Logger("debug logging disabled...")
	settingUpdate("showDebug", "false", "bool")
	settingUpdate("advAppDebug", "false", "bool")
}

void settingUpdate(String name, value, String type=null){
	//LogTrace("settingUpdate($name, $value, $type)...")
	if(name){
		if(value == "" || value == null || value == []){
			settingRemove(name)
			return
		}
	}
	if(name && type){ app?.updateSetting(name, [type: "$type", value: value]) }
	else if (name && type == null){ app?.updateSetting(name, value) }
}

void settingRemove(String name){
	//LogTrace("settingRemove($name)...")
	if(name){ app?.clearSetting(name.toString()) }
}

def stateRemove(key){
	//if(state.containsKey(key)){ state.remove(key?.toString()) }
	state.remove(key?.toString())
	return true
}

def setAutomationStatus(Boolean upd=false){
	Boolean myDis = (settings.autoDisabledreq == true)
	Boolean settingsReset = false // (parent.getSettingVal("disableAllAutomations") == true)
	Boolean storAutoType = getAutoType() == "storage"
	if(settingsReset && !storAutoType){
		if(!myDis && settingsReset){ LogAction("setAutomationStatus: Nest Integrations forcing disable", "info", true) }
		myDis = true
	} else if(storAutoType){
		myDis = false
	}
	if(!getIsAutomationDisabled() && myDis){
		LogAction("Automation Disabled at (${getDtNow()})", "info", true)
		state.autoDisabledDt = getDtNow()
	} else if(getIsAutomationDisabled() && !myDis){
		LogAction("Automation Enabled at (${getDtNow()})", "info", true)
		state.autoDisabledDt = null
	}
	state.autoDisabled = myDis
	if(upd){ app.update() }
}

Boolean getIsAutomationDisabled(){
	def dis = state.autoDisabled
	return (dis != null && dis == true) ? true : false
}

// getStartTime("dewTbl", "dewTblYest"))
def getStartTime(tbl1, tbl2=null){
	Integer startTime = 24
	if (state."${tbl1}"?.size()){
		startTime = state."${tbl1}".min{it[0].toInteger()}[0].toInteger()
	}
	if (state."${tbl2}"?.size()){
		startTime = Math.min(startTime, state."${tbl2}".min{it[0].toInteger()}[0].toInteger())
	}
	return startTime
}

String getEDeviceTile(Integer devNum=null, dev){

		if (state."TEnergyTbl${dev.id}"?.size() <= 0){
			return hideChartHtml()
		}

		def level
		String lastReadTime
		def capacity

        List devs = state.devices
		devs.each { mydev ->
			String deviceid = mydev
			String dni = getDeviceDNI(deviceid)
			def deviceData
			def devData
			if(dev?.deviceNetworkId == dni){
				deviceData = state.deviceData
				devData = deviceData[deviceid]
                log.debug(devData)
                level = ((devData.gallons.toFloat()/devData.tank_volume.toFloat())*100).round(2)
                lastReadTime = devData.last_read
				capacity = devData.tank_volume.toFloat()
                battery = devData.battery
                gallons = devData.gallons.toFloat()
			}
		}

        Date curConn = lastReadTime ? Date.parse("yyyy-MM-dd HH:mm:ss", lastReadTime) : null //"Not Available"

		String formatVal = "MMM d, yyyy h:mm a"
		def tf = new SimpleDateFormat(formatVal)
		if(getTimeZone()){ tf.setTimeZone(getTimeZone()) }
		String curConnFmt = curConn!=null ? tf.format(curConn) : "Not Available"

        def gal = gallons
		def t0 = state."TEnergyTbl${dev.id}"
		def t1 = t0?.size() > 2 && (t0[-2])[1].toFloat().round(2) == (t0[-1])[1].toFloat().round(2) ? t0[-3] : null
		t1 = (!t1 && t0?.size() > 1) ? t0[-2] : t1
		def ylevel = t1 ? t1[1] : 0 
		def ygal = (capacity * ylevel/100).toFloat().round(2)
		def used = (ygal-gal).toFloat().round(2)
		used = (used < -2) ? "${used} (refilled)" : used
		def num = 1
		t0 = capacity*0.8
		if(gal >= (t0*0.25)){ num = 2 }
		if(gal >= (t0*0.45)){ num = 3 }
		if(gal >= (t0*0.65)){ num = 4 }
		if(gal >= (t0*0.9)){ num = 5 }

		String url = "https://github.com/dlaporte/Hubitat/raw/main/SmartOilGauge/images/tank-${num}.png".toString()
    
		def mainHtml = """
			<div class="device">
				<div class="container">
					<h4>Tank Details</h4>
					<div class="row">
						<div class="six columns">
							<b>Capacity: </b> ${capacity} <br>
							<b>Tank Level: </b> ${level}% <br>
							<b>Gallons: </b> ${gal} <br>
							<b>Gallons used: </b> ${used} <br>
							<b>Last Updated: </b> ${curConnFmt} <br>
						</div>
						<div class="six columns">
							<img class="offset-by-two eight columns" src="${url}"> <br>
							<h1>${level}</h1>
						</div>
					</div>
				</div>
			</div>

		"""
}

String getDataString(Integer seriesIndex, dev){
	String dataString = ""
	def dataTable = []
	switch (seriesIndex){
		case 1:
			dataTable = state."TEnergyTbl${dev.id}"
			break
	}
	dataTable.each(){
		def dataArray = [it[0],null,null]
		dataArray[seriesIndex] = it[1]
		dataString += dataArray?.toString() + ","
	}
	return dataString
}

def getTimeZone(){
	def tz = null
	if(location?.timeZone){ tz = location?.timeZone }
	if(!tz){ LogAction("getTimeZone: Hub or Nest TimeZone not found", "warn", true) }
	return tz
}

String getDtNow(){
	Date now = new Date()
	return formatDt(now)
}

String formatDt(dt){
	def tf = new SimpleDateFormat("E MMM dd HH:mm:ss z yyyy")
	if(getTimeZone()){ tf.setTimeZone(getTimeZone()) }
	else {
		LogAction("HE TimeZone is not set; Please open your location and Press Save", "warn", true)
		return ""
	}
	return tf.format(dt)
}

Long GetTimeDiffSeconds(String strtDate, String stpDate=null, String methName=null){
	//LogTrace("[GetTimeDiffSeconds] StartDate: $strtDate | StopDate: ${stpDate ?: "Not Sent"} | MethodName: ${methName ?: "Not Sent"})")
	if((strtDate && !stpDate) || (strtDate && stpDate)){
		//if(strtDate?.contains("dtNow")){ return 10000 }
		Date now = new Date()
		String stopVal = stpDate ? stpDate.toString() : formatDt(now)
		Long start = Date.parse("E MMM dd HH:mm:ss z yyyy", strtDate).getTime()
		Long stop = Date.parse("E MMM dd HH:mm:ss z yyyy", stopVal).getTime()
		Long diff = (stop - start) / 1000L
		LogTrace("[GetTimeDiffSeconds] Results for '$methName': ($diff seconds)")
		return diff
	}else{ return null }
}

/************************************************************************************************
|									LOGGING AND Diagnostic									|
*************************************************************************************************/

void LogTrace(String msg, String logSrc=null){
	Boolean trOn = (showDebug && advAppDebug) ? true : false
	if(trOn){
		Logger(msg, "trace", logSrc)
	}
}

void LogAction(String msg, String type=null, Boolean showAlways=false, String logSrc=null){
	String myType = type ?: "debug"
	Boolean isDbg = showDebug ? true : false
	if(showAlways || isDbg){ Logger(msg, myType, logSrc) }
}

void Logger(String msg, String type=null, String logSrc=null, Boolean noLog=false){
	String myType = type ?: "debug"
	if(!noLog){
		if(msg && myType){
			String labelstr = ""
			if(state.dbgAppndName == null){
				def tval = settings.dbgAppndName
				state.dbgAppndName = (tval || tval == null) ? true : false
			}
			String t0 = app.label
			if(state.dbgAppndName){ labelstr = "${app.label} | " }
			String themsg = labelstr+msg
			//log.debug "Logger remDiagTest: $msg | $type | $logSrc"
			switch(myType){
				case "debug":
					log.debug themsg
					break
				case "info":
					log.info '|| '+themsg
					break
				case "trace":
					log.trace '| '+themsg
					break
				case "error":
					log.error '| '+themsg
					break
				case "warn":
					log.warn '| '+themsg
					break
				default:
					log.debug themsg
					break
			}
		} else { log.error "${labelstr}Logger Error - type: ${type} | msg: ${msg} | logSrc: ${logSrc}" }
	}
}
