/*
 *
 * Modified by David LaPorte
 *  Based on the Tank Utility driver from EricS
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

metadata {
	definition (name: "Smart Oil Gauge", namespace: "dlaporte" , author: "David LaPorte") {
		capability "Energy Meter"
		capability "Refresh"
		capability "Sensor"
		capability "Power Meter"
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
		attribute "lowFuel", "string"             // "true" / "false" vs threshold
		attribute "lastRefillAt", "string"        // ISO timestamp of last detected refill
		attribute "lastRefillGallons", "number"   // delta gallons at the refill
	}
}
    
void installed() {
	log.debug "installed()"
}

// parse events into attributes
def parse(String description) {
	log.debug "Parsing '${description}'"
}

def refresh() {
	parent.pollChildren()
}

// Kept as a backward-compat alias for any rules that called poll()
// directly when the Polling capability was declared.
void poll() {
	refresh()
}

def generateEvent(Map results) {
	results.each { name, value ->
		sendEvent(name: name, value: value)
	}
	return
}
