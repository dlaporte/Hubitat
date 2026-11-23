/*
 *  LG TV (Glasshouse) Notifier
 *
 *  Child of LG TV (Glasshouse): shows each Hubitat notification on the TV as LG's
 *  standard toast. It has no settings of its own; the TV device sends the message.
 *  Documentation: https://github.com/dlaporte/Hubitat/blob/main/LGglasshouse/README.md
 *
 *  Changelog:
 *  v0.12.0 (2026-11-22) - No changes; version follows LG TV (Glasshouse).
 *  v0.11.0 (2026-11-20) - Version aligned with LG TV (Glasshouse); standard header.
 *  v0.9 - 2026-11-14 - initial release.
 *
 *  Copyright 2026 David LaPorte
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

@Field static final String VERSION = "0.12.0"

// Created from the TV device: turn on Notification device on its Preferences
// page and Save. Turning that off deletes this device.
metadata {
    definition(
        name: "LG TV (Glasshouse) Notifier",
        namespace: "dlaporte",
        author: "David LaPorte",
        importUrl: "https://raw.githubusercontent.com/dlaporte/Hubitat/main/LGglasshouse/device/LGTV-Glasshouse-Notifier.groovy"
    ) {
        capability "Notification"
    }
}

void installed() {
    log.info "${device.displayName} installed"
    device.updateDataValue("driverVersion", VERSION)
}

void updated() {
    log.info "${device.displayName} updated"
    device.updateDataValue("driverVersion", VERSION)
}

// The TV device checks the text (blank, over 120 characters) and sends it.
void deviceNotification(text) {
    if (parent == null) {
        log.error "${device.displayName} has no parent TV device. Create it from the LG TV (Glasshouse) device: turn on Notification device and Save Preferences."
        return
    }
    parent.sendToast(text?.toString())
}
