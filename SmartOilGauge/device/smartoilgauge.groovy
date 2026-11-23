/*
 *  Smart Oil Gauge
 *
 *  Child device of Smart Oil Gauge (Connect): one per tank. The app polls the Droplet Fuel
 *  API and sends this device its readings and derived metrics; it has no settings of its own.
 *  Documentation: https://github.com/dlaporte/Hubitat/blob/main/SmartOilGauge/README.md
 *  Based on: the Tank Utility driver from EricS
 *
 *  Changelog:
 *  v0.2.0 (2026-11-23) - runOutDate attribute; lowFuel declared as a true/false enum.
 *  v0.1.0 (2026-11-20) - healthStatus; version aligned with Smart Oil Gauge (Connect).
 *
 *  Copyright 2021 David LaPorte
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 */

import groovy.transform.Field

@Field static final String VERSION = "0.2.0"

metadata {
    definition(
        name: "Smart Oil Gauge",
        namespace: "dlaporte",
        author: "David LaPorte",
        importUrl: "https://raw.githubusercontent.com/dlaporte/Hubitat/main/SmartOilGauge/device/smartoilgauge.groovy"
    ) {
        capability "Energy Meter"
        capability "Refresh"
        capability "Sensor"
        capability "Battery"
        capability "Relative Humidity Measurement"

        attribute "lastreading", "date"
        attribute "capacity", "number"
        attribute "level", "number"
        attribute "gallons", "number"
        attribute "humidity", "number"

        // v0.0.5 additions — derived metrics surfaced from the parent app
        attribute "accountNumber", "string"
        attribute "usageRate", "number"           // gallons per day (smoothed)
        attribute "daysRemaining", "number"       // gallons / usageRate
        attribute "runOutDate", "string"          // yyyy-MM-dd: today + daysRemaining
        attribute "lowFuel", "enum", ["true", "false"]   // vs the low-fuel threshold
        attribute "lastRefillAt", "string"        // time of the last detected refill (yyyy-MM-dd HH:mm:ss, hub time)
        attribute "lastRefillGallons", "number"   // delta gallons at the refill
        attribute "healthStatus", "enum", ["online", "offline"]   // offline: API failing, or no reading for 96 h
    }
}
    
def refresh() {
    parent.pollChildren()
}

// Kept as a backward-compat alias for any rules that called poll()
// directly when the Polling capability was declared.
void poll() {
    refresh()
}

// Called by the parent app with one or more attribute: value pairs.
def generateEvent(Map results) {
    // This device has no settings page or initialize(), so record the running
    // version here, where the app reaches it every poll.
    if (device.getDataValue("driverVersion") != VERSION) device.updateDataValue("driverVersion", VERSION)
    results.each { name, value ->
        sendEvent(name: name, value: value)
    }
    return
}
