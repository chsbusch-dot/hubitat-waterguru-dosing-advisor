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
    List<Map> subscriptions = []

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
    /** The app's definition(...) map, so a test can check a declared flag such as singleThreaded. */
    Map definitionArgs
    def definition(Map m) { definitionArgs = new LinkedHashMap(m); null }
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
    /** Set to an attribute name to make subscribing to it throw, as a broken device reference can. */
    String subscribeThrowsFor

    def subscribe(def device, String attr, String handler, Map options = [:]) {
        if (subscribeThrowsFor != null && attr == subscribeThrowsFor) throw new RuntimeException("simulated subscribe failure for ${attr}")
        subscriptions << [device: device, attr: attr, handler: handler, options: options]
        null
    }
    def unsubscribe() { subscriptions.clear(); null }

    /**
     * A device report reaching the hub, modelled on what was MEASURED on the live hub (2026-10-04):
     *
     *   - a changed value updates the stored state, value AND date, and reaches every subscriber;
     *   - an UNCHANGED value is still stored and delivered as an event with isStateChange:false and
     *     a fresh event date, but only to subscribers that opted out of duplicate filtering
     *     (filterEvents:false), and it does NOT advance currentState(attr).date. Live evidence: plug
     *     4674 logged "switch off (digital)" with isStateChange:false at 10:14:09, 11:31:23 and
     *     11:31:56 PDT while currentState('switch').date stayed 2026-10-04T02:48:31Z.
     *
     * Until 2.4.1 this stub re-dated the unchanged value, the opposite of the hub, which is why the
     * suite could not see the acknowledgement lockout or the false power-loss abort.
     */
    def deliverDeviceReport(def device, String attr, def value) {
        if (!(attr in ["switch", "power"])) throw new IllegalArgumentException("Unsupported test report: ${attr}")
        List listeners = subscriptions.findAll { it.device.is(device) && it.attr == attr }
        boolean changed = device.currentValue(attr)?.toString() != value?.toString()
        if (changed) {
            if (attr == "switch") device.setReportedAt(value.toString(), clockMs)
            else device.setPowerAt(value as BigDecimal, clockMs)
        }
        listeners.findAll { changed || it.options.filterEvents == false }.each {
            this."${it.handler}"([name: attr, value: value.toString(), date: new Date(clockMs), isStateChange: changed])
        }
    }
    /**
     * A WaterGuru sample reaching the hub (2.4.3), `batch` being [attribute, value] pairs in the order the
     * WaterGuru Integration writes them (processWaterGuruData: ... CassetteTimeLeft, LastMeasurementHuman,
     * LastMeasurement, CassetteChecksLeft, ..., freeChlorine, pH, ..., doseAdvice, ..., cassetteInfo). It
     * sends a value only when it changed, or every value when its forceUpdate setting is on (`force`).
     *
     * Each event reaches its subscribers as it is written, so a LastMeasurement handler runs while the
     * rest of the sample (freeChlorine, CassetteChecksLeft, doseAdvice) still holds the previous one.
     * That is what the live hub showed: 2.4.2's onNewSample stored the previous sample's FC for 7 of the
     * 8 samples from Sep 29 to Oct 5 (app 2200's state.fcHistory against device 4656's events).
     */
    def deliverSourceBatch(def device, List<List> batch, boolean force = false) {
        batch.each { pair ->
            String attr = pair[0].toString()
            def value = pair[1]
            boolean changed = device.write(attr, value, clockMs)
            if (!changed && !force) return
            subscriptions.findAll { it.device.is(device) && it.attr == attr }.each {
                this."${it.handler}"([name: attr, value: value?.toString(), date: new Date(clockMs), isStateChange: true])
            }
        }
    }
    def sendEvent(Map m) { emitted << new LinkedHashMap(m); null }
    def pause(Number n) { null }

    // --- scheduling -----------------------------------------------------------------
    /** Absolute due time of every pending runIn job, so runUntil() can fire them in time order. */
    Map<String, Long> dueAt = [:]

    def runIn(Number seconds, String handler, Map opts = [:]) {
        scheduled[handler] = seconds
        dueAt[handler] = clockMs + ((seconds as BigDecimal) * 1000G).longValue()
        null
    }
    def schedule(String when, String handler) {
        scheduled[handler] = when
        null
    }
    def runEvery1Minute(String handler) { scheduled[handler] = 60; null }
    def unschedule() { scheduled.clear(); dueAt.clear(); null }
    def unschedule(String handler) { scheduled.remove(handler); dueAt.remove(handler); unscheduled << handler; null }

    /** Move the clock to `t`, firing every runIn job that falls due on the way, in due-time order. */
    void runUntil(Long t) {
        int guard = 0
        while (guard++ < 100_000) {
            def due = dueAt.findAll { it.value <= t }
            if (!due) break
            def next = due.min { it.value }
            clockMs = Math.max(clockMs, next.value)
            fire(next.key)
        }
        clockMs = Math.max(clockMs, t)
    }

    /** runUntil() relative to now. */
    void advance(long ms) { runUntil(clockMs + ms) }

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
    /** app.removeSetting(name) deletes a setting, as InstalledAppWrapper.removeSetting does on the hub. */
    def getApp() {
        def self = this
        [updateLabel: { String s -> }, label: "test", removeSetting: { String n -> self.settings.remove(n); null }]
    }

    // --- File Manager (used by the Pump Power Profiler) ---------------------------------
    /** File name -> contents. (Not named hubFiles: Groovy would read that property through getHubFiles().) */
    Map<String, String> fileStore = [:]
    /**
     * ok | listThrows | listNull | listEmpty | downloadThrows | downloadNull | downloadEmpty | uploadThrows.
     * listEmpty (a listing that leaves out files that exist) and downloadEmpty (0 bytes for a file that has
     * content) have not been seen on the hub; the profiler must not lose its summary if they happen.
     */
    String hubFilesMode = "ok"
    List<String> uploads = []

    /** getHubFiles(): the File Manager listing, as FileManagerEntry-like maps (name, type). */
    def getHubFiles(String folder = "") {
        if (hubFilesMode == "listThrows") throw new RuntimeException("simulated File Manager listing failure")
        if (hubFilesMode == "listNull") return null
        if (hubFilesMode == "listEmpty") return []
        return fileStore.keySet().collect { [name: it, type: "file"] }
    }
    /** downloadHubFile(): bytes, or null when the file is unavailable (firmware 2.5.2.129 docs). */
    byte[] downloadHubFile(String name) {
        if (hubFilesMode == "downloadThrows") throw new RuntimeException("simulated transient File Manager error")
        if (hubFilesMode == "downloadNull") return null
        if (hubFilesMode == "downloadEmpty" && fileStore.containsKey(name)) return new byte[0]
        return fileStore.containsKey(name) ? fileStore[name].getBytes("UTF-8") : null
    }
    void uploadHubFile(String name, byte[] bytes) {
        if (hubFilesMode == "uploadThrows") throw new RuntimeException("simulated File Manager write failure")
        fileStore[name] = new String(bytes, "UTF-8")
        uploads << name
    }
    def getSourceDevice() { settings["sourceDevice"] }
    /** The app reaches its tile device through this Hubitat API, so stub it here rather than
     *  overriding the app's private getTileDevice(), which Groovy resolves directly. */
    def getChildDevice(String dni) { settings["__tileDevice"] }
    def getChildDevices() { settings["__tileDevice"] ? [settings["__tileDevice"]] : [] }
    /**
     * Today's date (in tz, on the fake clock) at the time of day in `t`: "HH:mm", "HH:mm:ss", or the
     * ISO-8601 string a Hubitat time input stores. Anything else is "now", the old behaviour, which
     * made every AUTO window look open.
     */
    def timeToday(def t, TimeZone tz) {
        def m = (t?.toString() ?: "") =~ /(?:T|^)(\d{2}):(\d{2})(?::(\d{2}))?/
        if (!m.find()) return new Date(clockMs)
        Calendar cal = Calendar.getInstance(tz ?: TimeZone.getTimeZone("America/Los_Angeles"))
        cal.setTimeInMillis(clockMs)
        cal.set(Calendar.HOUR_OF_DAY, m.group(1) as Integer)
        cal.set(Calendar.MINUTE, m.group(2) as Integer)
        cal.set(Calendar.SECOND, m.group(3) ? (m.group(3) as Integer) : 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.time
    }
    /**
     * Parse the two shapes toEpochMs() is fed: an epoch-millis string, or ISO-8601. Returning
     * null for everything would make doseSafetyBlocks() reject every dose as "measurement
     * timestamp is unavailable", so no start-path test could reach the ON command.
     */
    def toDateTime(String s) {
        if (s == null) return null
        String t = s.trim()
        if (t ==~ /\d{10,}/) return new Date(t.toLong())
        try { return Date.from(java.time.Instant.parse(t)) } catch (ignored) { }
        return null
    }
    def atomicState = [:]

    /**
     * Model the hub actually firing a scheduled job: the entry leaves the queue, then the handler
     * runs. Without the removal, a test cannot see that a job which fired is no longer pending --
     * which is exactly how the old cutoff could re-arm itself and quietly postpone a deadline.
     */
    Object fire(String handler) {
        scheduled.remove(handler)
        dueAt.remove(handler)
        def m = this.metaClass.getMetaMethod(handler)
        if (m == null) throw new IllegalStateException("no handler named ${handler}")
        return m.invoke(this)
    }

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
