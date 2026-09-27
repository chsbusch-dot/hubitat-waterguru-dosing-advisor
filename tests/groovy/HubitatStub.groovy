/**
 * Minimal stand-in for the Hubitat app runtime.
 *
 * WHY THIS EXISTS
 * ---------------
 * The attribute-contract test scans source text. Text scanning cannot tell a real
 * `sendEvent` from a commented-out one, and it cannot observe behaviour at all -- so a
 * stopDose() that reports "pump stopped" after a FAILED OFF command passes it happily.
 * This class lets the app's own methods be executed off-hub, with devices that can be told
 * to fail, so those paths can be asserted instead of asserted-about.
 *
 * Used as the CompilerConfiguration.scriptBaseClass when parsing the app, so the app's
 * top-level `definition { }` / `preferences { }` blocks and every Hubitat injected method
 * resolve to something harmless here.
 *
 * Nothing in this file talks to a hub, a device or a network.
 */
abstract class HubitatStub extends Script {

    // --- recorded runtime state -----------------------------------------------------
    Map<String, Object> state = [:]
    List<Map> emitted = []                  // every sendEvent payload, in order
    List<String> notices = []               // every sendNotice() message
    List<String> logLines = []
    Map<String, Object> scheduled = [:]     // handler name -> delay/clock spec
    List<String> unscheduled = []           // handler names passed to unschedule(name)

    // Settings the app reads (pumpSwitch, dosingMode, ...), looked up on demand.
    Map<String, Object> settings = [:]

    /** Millisecond clock. Tests move it explicitly; nothing here reads the real time. */
    Long clockMs = 1_700_000_000_000L

    // --- property resolution --------------------------------------------------------
    // The app reads settings as bare properties (e.g. `dosingMode`, `pumpSwitch`) and writes
    // runtime state through `state`. Anything that is not a real field of this class is resolved
    // against `settings`, which is what makes `app.pumpSwitch = fakeSwitch` work.
    private static final Set<String> FIELDS = (
        HubitatStub.declaredFields*.name + HubitatStub.superclass.declaredFields*.name
    ).toSet()

    @Override
    Object getProperty(String name) {
        if (name == "class") return HubitatStub
        if (FIELDS.contains(name)) return super.getProperty(name)
        if (settings.containsKey(name)) return settings[name]
        try {
            return super.getProperty(name)
        } catch (MissingPropertyException ignored) {
            return null
        }
    }

    @Override
    void setProperty(String name, Object value) {
        if (FIELDS.contains(name)) {
            super.setProperty(name, value)
            return
        }
        if (settings.containsKey(name)) {
            settings[name] = value
            return
        }
        try {
            super.getProperty(name)
            super.setProperty(name, value)
        } catch (MissingPropertyException ignored) {
            settings[name] = value
        }
    }

    // --- Hubitat DSL: no-ops --------------------------------------------------------
    def definition(Map m) { null }
    def definition(Closure c) { null }
    def preferences(Closure c) { null }
    def metadata(Closure c) { null }
    def dynamicPage(Map m, Closure c) { null }
    def page(Map m) { null }
    def section(Closure c) { null }
    def section(String s, Closure c) { null }
    def input(Map m) { null }
    def input(String s, String t, String title) { null }
    def paragraph(String s) { null }
    def label(Map m) { null }
    def href(Map m) { null }
    def app(Map m) { null }
    def subscribe(def device, String attr, String handler) { null }
    def unsubscribe() { null }
    def sendEvent(Map m) { emitted << new LinkedHashMap(m); null }
    def pause(Number n) { null }

    // --- scheduling -----------------------------------------------------------------
    def runIn(Number seconds, String handler, Map opts = [:]) {
        scheduled[handler] = seconds
        null
    }
    def schedule(String when, String handler) {
        scheduled[handler] = when
        null
    }
    def runEvery1Minute(String handler) { scheduled[handler] = 60; null }
    def unschedule() { scheduled.clear(); null }
    def unschedule(String handler) { scheduled.remove(handler); unscheduled << handler; null }

    // --- logging & notifications ----------------------------------------------------
    def getLog() {
        def self = this
        return [
            info : { Object m -> self.logLines << "INFO ${m}" },
            warn : { Object m -> self.logLines << "WARN ${m}" },
            error: { Object m -> self.logLines << "ERROR ${m}" },
            debug: { Object m -> self.logLines << "DEBUG ${m}" },
            trace: { Object m -> self.logLines << "TRACE ${m}" },
        ]
    }

    /** The child app's sendPumpNotice() delegates to its own sendNotice(), which fans out to
     *  the devices in notifyDevices. Do NOT define sendNotice here: the app defines it, and a
     *  private override in the app shadows this one, so a recorder here would be dead code that
     *  silently records nothing. Tests attach a FakeNotifier whose sink is this list. */

    // --- environment ----------------------------------------------------------------
    Long now() { clockMs }
    def getLocation() {
        [timeZone: TimeZone.getTimeZone("America/Los_Angeles"), name: "test", temperatureScale: "F"]
    }
    def getParent() { null }
    def getChildApps() { [] }
    def getApp() { [updateLabel: { String s -> }, label: "test"] }
    def getSourceDevice() { settings["sourceDevice"] }
    /** The app reaches its tile device through this Hubitat API, so stub it here rather than
     *  overriding the app's private getTileDevice(), which Groovy resolves directly. */
    def getChildDevice(String dni) { settings["__tileDevice"] }
    def getChildDevices() { settings["__tileDevice"] ? [settings["__tileDevice"]] : [] }
    def timeToday(def t, TimeZone tz) { new Date(clockMs) }
    def toDateTime(String s) { null }
    def atomicState = [:]

    // --- test helpers ---------------------------------------------------------------
    /** Attributes sent, newest last, as name -> value. */
    Map<String, Object> emittedAttributeValues() {
        Map<String, Object> out = [:]
        emitted.each { if (it?.name) out[it.name.toString()] = it.value }
        return out
    }

    /** The unit the app attached to an attribute, or null. */
    String emittedUnit(String attribute) {
        def hit = emitted.reverse().find { it?.name?.toString() == attribute }
        return hit?.unit?.toString()
    }

    List<String> noticesMatching(String needle) {
        return notices.findAll { it != null && it.toLowerCase().contains(needle.toLowerCase()) }
    }
}
