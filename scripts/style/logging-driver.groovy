// ---- BEGIN standard logging (driver) v1: keep identical in every driver, see STYLE.md ----

void logsOff() {
    log.info "Debug logging turned off"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

private void logDebug(String msg) {
    if (settings.logEnable) log.debug msg
}

private void logDesc(String msg) {
    if (settings.txtEnable != false) log.info msg
}

// Sends the event; logs its descriptionText only when the value actually changes.
private void emitEvent(Map evt) {
    boolean changed = device.currentValue(evt.name as String)?.toString() != evt.value?.toString()
    sendEvent(evt)
    if (changed && evt.descriptionText) logDesc(evt.descriptionText as String)
}

// ---- END standard logging ----
