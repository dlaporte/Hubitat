package harness

/**
 * Loads a Hubitat driver into plain Groovy so its logic can be unit-tested
 * off the hub. It's a fake, not an emulator: it models the parts of the
 * Hubitat sandbox our drivers use (events, schedules, settings, state,
 * device data, logs, a settable clock, and Z-Wave command objects), not
 * Hubitat's full behaviour. The hub's compile-on-save remains the check for
 * Groovy 2.4 syntax. See STYLE.md, "Testing".
 *
 * Usage:
 *     def d = HubitatHarness.load("RadonFanSensor/device/15270-radon-fan.groovy")
 *     d.call("sensorValueEvent", 0)
 *     assert d.device.current.contact == "open"
 */
class HubitatHarness {

    /** Z-Wave command classes the drivers reference: "package.Class" -> property names. */
    static final Map<String, List<String>> ZWAVE_STUBS = [
        "basicv1.BasicReport"                             : ["value"],
        "basicv1.BasicSet"                                : ["value"],
        "switchbinaryv1.SwitchBinaryReport"               : ["value"],
        "sensorbinaryv1.SensorBinaryReport"               : ["sensorValue", "sensorType"],
        "sensoralarmv1.SensorAlarmReport"                 : ["sensorState", "sensorType"],
        "notificationv3.NotificationReport"               : ["notificationType", "event", "v1AlarmType",
                                                             "v1AlarmLevel", "eventParameter"],
        "wakeupv1.WakeUpNotification"                     : [],
        "wakeupv1.WakeUpIntervalReport"                   : ["seconds", "nodeid"],
        "batteryv1.BatteryReport"                         : ["batteryLevel"],
        "manufacturerspecificv2.ManufacturerSpecificReport": ["manufacturerId", "productTypeId", "productId"],
    ]

    static Driver load(String path) {
        // The hub's Groovy has no String.takeRight(int) (found on a hub at deploy).
        // Make it fail here too. The closure's typed parameter is what lets it
        // beat the built-in GDK method.
        String.metaClass.takeRight = { int n -> throw new MissingMethodException("takeRight", String, [n] as Object[]) }
        GroovyClassLoader gcl = new GroovyClassLoader(HubitatHarness.classLoader)
        gcl.parseClass("""package hubitat.zwave
            class Command {
                String toString() { getClass().simpleName + properties.findAll { it.key != 'class' } }
            }""", "Command.groovy")
        ZWAVE_STUBS.each { String type, List<String> props ->
            String pkg = type.substring(0, type.lastIndexOf('.'))
            String cls = type.substring(type.lastIndexOf('.') + 1)
            String fields = props.collect { "def ${it}" }.join("\n")
            gcl.parseClass("package hubitat.zwave.commands.${pkg}\nclass ${cls} extends hubitat.zwave.Command {\n${fields}\n}",
                    "${cls}.groovy")
        }
        File file = new File(path)
        Class scriptClass = gcl.parseClass(file.getText("UTF-8"), file.name)
        Driver d = new Driver(gcl)
        d.script = (Script) scriptClass.getDeclaredConstructor().newInstance()
        d.script.binding = d.binding
        d.script.run()          // runs metadata { ... } against the recording stubs
        d.binding.inputNames.addAll(d.inputs*.name)
        d.reset()
        return d
    }
}

/** Binding that resolves bare preference names (e.g. alarmAfterMinutes) from settings, as Hubitat does. */
class DriverBinding extends Binding {
    Map settings
    Set<String> inputNames = [] as Set

    @Override
    Object getVariable(String name) {
        if (super.hasVariable(name)) return super.getVariable(name)
        if (settings != null && (settings.containsKey(name) || inputNames.contains(name))) return settings[name]
        throw new MissingPropertyException(name, DriverBinding)
    }

    @Override
    boolean hasVariable(String name) {
        super.hasVariable(name) || (settings != null && (settings.containsKey(name) || inputNames.contains(name)))
    }
}

class FakeDevice {
    String displayName = "Test Device"
    Map<String, Object> current = [:]       // attribute -> latest value
    Map<String, Object> staleValues = [:]   // attribute -> value currentValue() keeps returning (simulates lag)
    Map<String, String> data = [:]
    List<String> removedSettings = []
    List<Map> settingUpdates = []
    Date lastActivity
    Map settings

    def currentValue(String name, boolean skipCache = false) {
        staleValues.containsKey(name) ? staleValues[name] : current[name]
    }
    def currentState(String name) { current.containsKey(name) ? [value: current[name]] : null }
    String getDataValue(String key) { data[key] }
    void updateDataValue(String key, String value) { data[key] = value }
    void removeDataValue(String key) { data.remove(key) }
    List<String> deletedStates = []
    void deleteCurrentState(String name) { deletedStates << name; current.remove(name) }
    void removeSetting(String name) { removedSettings << name; settings?.remove(name) }
    void updateSetting(String name, Map value) {
        settingUpdates << [name: name] + value
        def v = value.value
        settings?.put(name, value.type == "bool" ? (v.toString() == "true") : v)
    }
}

/** Stand-in for Hubitat's AsyncResponse. `data` is the raw body string, as on the hub. */
class FakeResponse {
    Integer status = 200
    String data
    String errorMessage
    Map headers = [:]
    boolean hasError() { errorMessage != null || (status != null && status >= 400) }
    String getErrorMessage() { errorMessage }
    def getJson() { data == null ? null : new groovy.json.JsonSlurper().parseText(data) }
}

class FakeLog {
    List<List<String>> lines = []
    void debug(msg) { lines << ["debug", msg.toString()] }
    void info(msg)  { lines << ["info", msg.toString()] }
    void warn(msg)  { lines << ["warn", msg.toString()] }
    void error(msg) { lines << ["error", msg.toString()] }
    void trace(msg) { lines << ["trace", msg.toString()] }
    List<String> at(String level) { lines.findAll { it[0] == level }.collect { it[1] } }
}

/** zwave.batteryV1.batteryGet() -> "batteryV1.batteryGet()"; zwave.parse() -> whatever `next` holds. */
class FakeZwave {
    def next
    def parse(String description, Map versions = [:]) { def n = next; next = null; n }
    def propertyMissing(String commandClass) { new FakeCommandClass(name: commandClass) }
}

class FakeCommandClass {
    String name
    def methodMissing(String cmd, args) {
        List a = (args as List)
        "${name}.${cmd}(${a ? a.collect { it.toString() }.join(', ') : ''})".toString()
    }
}

class Driver {
    final GroovyClassLoader loader
    Script script
    final Map settings = [:]
    final Map state = [:]
    final FakeDevice device = new FakeDevice()
    final FakeLog log = new FakeLog()
    final FakeZwave zwave = new FakeZwave()
    final DriverBinding binding = new DriverBinding()

    long clock = 1_800_000_000_000L          // ms; tests move it with advance()
    final List<Map> events = []             // every sendEvent, in order
    final Map<String, Number> schedules = [:]   // pending runIn: method -> seconds
    final List<List> runIns = []            // every runIn call: [seconds, method]
    final List<String> unschedules = []     // every unschedule call ("*" = all)
    final List<Map> http = []               // every async HTTP call: [method, callback, params, data]

    // Recorded from metadata { }
    Map definition = [:]
    final List<String> capabilities = []
    final List<Map> attributes = []
    final List<String> commands = []
    final List<Map> inputs = []
    final List<Map> fingerprints = []

    Driver(GroovyClassLoader loader) {
        this.loader = loader
        device.settings = settings
        binding.settings = settings
        Map vars = [
            settings      : settings,
            state         : state,
            device        : device,
            log           : log,
            zwave         : zwave,
            zwaveHubNodeId: 1,
            location      : [timeZone: TimeZone.getTimeZone("America/New_York")],
        ]
        vars.each { k, v -> binding.setVariable(k, v) }

        // Platform methods.
        binding.setVariable("now", { -> clock })
        binding.setVariable("sendEvent", { Map evt ->
            events << new LinkedHashMap(evt)
            device.current[evt.name as String] = evt.value
        })
        binding.setVariable("createEvent", { Map evt -> new LinkedHashMap(evt) })
        binding.setVariable("runIn", { Number sec, String method, Map opts = null ->
            schedules[method] = sec
            runIns << [sec, method]
        })
        binding.setVariable("unschedule", { String method = null ->
            if (method == null) { schedules.clear(); unschedules << "*" }
            else { schedules.remove(method); unschedules << method }
        })
        ["runEvery1Minute", "runEvery5Minutes", "runEvery10Minutes", "runEvery15Minutes",
         "runEvery30Minutes", "runEvery1Hour", "runEvery3Hours"].each { String name ->
            binding.setVariable(name, { String method, Map opts = null -> schedules[method] = name })
        }
        binding.setVariable("schedule", { Object cron, String method, Map opts = null -> schedules[method] = "cron:${cron}".toString() })
        binding.setVariable("asynchttpPost", { String cb, Map params, Map data = null ->
            http << [method: "POST", callback: cb, params: params, data: data]
        })
        binding.setVariable("asynchttpGet", { String cb, Map params, Map data = null ->
            http << [method: "GET", callback: cb, params: params, data: data]
        })
        binding.setVariable("response", { cmd -> "response:${cmd}".toString() })
        binding.setVariable("updateDataValue", { String k, String v -> device.updateDataValue(k, v) })
        binding.setVariable("getDataValue", { String k -> device.getDataValue(k) })

        // metadata { } DSL: record everything, run nested closures.
        def runNested = { Object[] args -> args.find { it instanceof Closure }?.call() }
        binding.setVariable("metadata", runNested)
        binding.setVariable("preferences", runNested)
        binding.setVariable("section", runNested)
        binding.setVariable("definition", { Object[] args ->
            definition = (Map) args.find { it instanceof Map } ?: [:]
            args.find { it instanceof Closure }?.call()
        })
        binding.setVariable("capability", { String name -> capabilities << name })
        binding.setVariable("attribute", { Object[] args ->
            attributes << [name: args[0], type: args.length > 1 ? args[1] : null, values: args.length > 2 ? args[2] : null]
        })
        binding.setVariable("command", { Object[] args -> commands << (String) args[0] })
        binding.setVariable("fingerprint", { Map m -> fingerprints << m })
        binding.setVariable("input", { Object[] args ->
            Map opts = (Map) (args.find { it instanceof Map } ?: [:])
            List<String> strings = args.findAll { it instanceof String } as List<String>
            inputs << ([name: opts.name ?: strings[0], type: opts.type ?: strings[1]] + opts)
        })
    }

    /** Invoke a driver method (private ones too). */
    def call(String method, Object... args) { script.invokeMethod(method, args) }

    /** Build a Z-Wave command stub, e.g. zw("basicv1.BasicSet", value: 0xFF). */
    def zw(Map props = [:], String type) {
        String fqcn = type == "Command" ? "hubitat.zwave.Command" : "hubitat.zwave.commands.${type}"
        def obj = loader.loadClass(fqcn).getDeclaredConstructor().newInstance()
        props.each { k, v -> obj."${k}" = v }
        obj
    }

    /**
     * Answer a recorded async HTTP call by invoking its callback, as the hub would.
     * Pass json: (a List/Map, sent as a JSON string body), or error: "message".
     */
    def reply(Map opts, Map req) {
        def resp = new FakeResponse(status: (opts.status ?: (opts.error ? null : 200)) as Integer,
                errorMessage: opts.error as String,
                data: opts.containsKey("json") ? groovy.json.JsonOutput.toJson(opts.json) : opts.body as String)
        http.remove(req)
        call(req.callback as String, resp, req.data)
    }

    /** Async HTTP calls whose first Reolink-style cmd (or URI) contains `text`. */
    List<Map> httpMatching(String text) { http.findAll { it.params.toString().contains(text) } }

    void advance(long ms) { clock += ms }

    /** Format a clock value the way our drivers stamp ISO timestamps (yyyy-MM-dd'T'HH:mm:ssZ). */
    String iso(long ms) {
        def fmt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")
        fmt.setTimeZone(TimeZone.getTimeZone("America/New_York"))
        fmt.format(new Date(ms))
    }

    List<Map> eventsNamed(String name) { events.findAll { it.name == name } }

    /** Forget recorded calls (keeps settings, state, device attributes and schedules). */
    void reset() {
        events.clear(); runIns.clear(); unschedules.clear(); log.lines.clear(); http.clear()
    }
}
