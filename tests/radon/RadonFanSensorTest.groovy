import harness.HubitatHarness

import static harness.TestRunner.finish
import static harness.TestRunner.test

final String DRIVER = "RadonFanSensor/device/15270-radon-fan.groovy"

test("harness loads the driver and records its metadata") {
    def d = HubitatHarness.load(DRIVER)
    assert d.definition.name == "Radon Fan Sensor"
    assert d.definition.namespace == "dlaporte"
    assert d.capabilities.containsAll(["Sensor", "Battery", "ContactSensor", "Refresh"])
    assert d.attributes*.name.containsAll(["switch", "lastStartedAt", "lastStoppedAt", "fanAlarm"])
    assert d.inputs*.name.contains("alarmAfterMinutes")
}

finish()
