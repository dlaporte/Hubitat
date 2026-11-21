// ---- BEGIN standard logging (app) v1: keep identical in every app, see STYLE.md ----

void logsOff() {
    log.info "Debug logging turned off"
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}

private void logDebug(String msg) {
    if (settings.logEnable) log.debug msg
}

// ---- END standard logging ----
