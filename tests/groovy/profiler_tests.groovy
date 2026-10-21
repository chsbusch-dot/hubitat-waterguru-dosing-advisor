/*
 * Execution tests for the Pump Power Profiler (apps/PumpPowerProfiler.groovy, v1.2.0).
 *
 * The profiler's own methods run against the same stubbed Hubitat runtime as the child app's suite,
 * with a fake meter that follows the hub rule measured on 2026-10-04: a report whose value did not
 * change leaves the reading's date where it was, and reaches only subscribers that opted out of
 * duplicate filtering. runPlug models plug 4680 as configured on 2026-10-14. File Manager is
 * HubitatStub.fileStore, which can be told to fail a listing, a read or a write.
 *
 * The "Gets:" checks count the profiler's refreshes in a 13.5 minute run (the Oct 12 and 13 runs were
 * 814.6 s). To count them for another version of the profiler, run only those checks against its file:
 *
 *   git show 0cab41e:apps/PumpPowerProfiler.groovy > profiler-1.1.1.groovy
 *   docker run --rm -v "$PWD:/w:ro" -w /w -e GET_COUNT_ONLY=1 -e PROFILER_FILE=profiler-1.1.1.groovy \
 *       groovy:4-alpine sh -c 'mkdir -p /tmp/h &&
 *       groovyc -d /tmp/h tests/groovy/HubitatStub.groovy tests/groovy/FakeSwitch.groovy &&
 *       groovy -cp /tmp/h tests/groovy/profiler_tests.groovy'
 *   rm profiler-1.1.1.groovy
 *
 * (Not under tests/mutation/out/: tests/mutation/run.sh takes everything there for a mutant.)
 *
 * Nothing here touches a hub or a device. Run: tests/groovy/run.sh
 */

import java.math.RoundingMode

import groovy.json.JsonSlurper

import org.codehaus.groovy.control.CompilerConfiguration

int checks = 0
int failures = 0
List<String> failureDetail = []
boolean countOnly = System.getenv("GET_COUNT_ONLY") == "1"

def check = { String name, Closure body ->
    if (countOnly && !name.startsWith("Gets:")) return
    checks++
    try {
        body.call()
    } catch (Throwable t) {
        failures++
        failureDetail << "${name}: ${t.message}"
        println "FAIL  ${name}\n        ${t.message}"
    }
}

def expect = { boolean condition, String message ->
    if (!condition) throw new AssertionError(message)
}

/** A ZEN05-like meter. Only a changed value moves a reading's date, as on the live hub. */
class FakeMeter {
    String displayName = "OL BYP Chlorine Pump Single"
    Map vals = [switch: "off", power: 0G, amperage: 0G, voltage: 121.05G, energy: 0G]
    Map dates = [:]
    int refreshCalls = 0
    List<Long> refreshTimes = []
    /** When the plug answers each of the profiler's refreshes (runPlug delivers the answers). */
    List<Long> pendingAnswers = []
    /** runPlug's next periodic power report. */
    Long nextPeriodic = null
    boolean refreshThrows = false
    Closure clock
    /** Runs before each currentState read, so a test can land a report in the middle of a sample. */
    Closure beforeStateRead = null

    FakeMeter(Closure clock) {
        this.clock = clock
        vals.keySet().each { dates[it] = (clock.call() as Long) - 86_400_000L }   // last changed yesterday
    }
    def currentValue(String a, boolean skipCache = false) { vals[a] }
    def currentState(String a, boolean skipCache = false) {
        if (beforeStateRead != null) beforeStateRead.call(a)
        vals.containsKey(a) ? [name: a, value: vals[a], date: new Date(dates[a] as Long)] : null
    }
    def refresh() {
        refreshCalls++
        long t = clock.call() as Long
        refreshTimes << t
        if (refreshThrows) throw new RuntimeException("simulated refresh failure")
        pendingAnswers << t + 500L
        null
    }
    /** Store a report as the hub does; returns whether the value changed. */
    boolean report(String a, def v) {
        if (vals[a]?.toString() == v?.toString()) return false
        vals[a] = v
        dates[a] = clock.call() as Long
        return true
    }
}

def compilerConfig = new CompilerConfiguration()
compilerConfig.scriptBaseClass = HubitatStub.name
def shell = new GroovyShell(HubitatStub.classLoader, new Binding(), compilerConfig)
def profilerFile = new File(System.getenv("PROFILER_FILE") ?: "apps/PumpPowerProfiler.groovy")
def profilerClass = shell.parse(profilerFile).getClass()

def at = { String hhmmss -> Date.parse("yyyy-MM-dd HH:mm:ss.SSS Z", "2026-10-03 ${hhmmss} -0700").time }

/** Z-Wave Gets per refresh() of the ZEN05 driver, from the Oct 14 capture ("about 6 Gets per poll"). */
int GETS_PER_REFRESH = 6

/**
 * A fresh profiler installed at `start`, with the live settings of instance 2214. fallbackSecs and
 * tailSecs are left unset, as on the live instance until Done is pressed after an update, so the
 * defaults apply; sampleSecs 5 and tailSamples 3 are only read by 1.1.x (the GET_COUNT_ONLY run).
 */
def newProfiler = { long start = at("19:48:25.480") ->
    def app = profilerClass.getDeclaredConstructor().newInstance()
    app.run()
    app.clockMs = start
    def meter = new FakeMeter({ app.clockMs })
    app.meterDev = meter
    app.sampleSecs = 5
    app.tailSamples = 3
    app.maxSamples = 600
    app.filePrefix = "chlorine-pump"
    app.logEnable = false
    app.installed()
    return [app, meter]
}

/**
 * A report reaching the hub: stored as the meter keeps it, then delivered as the hub delivers it, a
 * changed value to every subscriber and an unchanged one only to filterEvents:false subscribers.
 */
def deliver = { app, meter, String attr, def v ->
    boolean changed = meter.report(attr, v)
    List listeners = app.subscriptions.findAll { it.device.is(meter) && it.attr == attr && (changed || it.options?.filterEvents == false) }
    listeners.each { app."${it.handler}"([name: attr, value: v?.toString(), date: new Date(app.clockMs), isStateChange: changed]) }
    return changed
}

/** The switch changing, as its subscription delivers it. */
def switchTo = { app, meter, String v -> deliver(app, meter, "switch", v) }

/**
 * Plug 4680 (Zooz ZEN05, mesh node 72): a power report on a change of 5 W or more (parameter 9), one every
 * `periodicSecs` (null for none), and an answer to every refresh
 * about 0.5 s later with its switch, power, amperage and voltage. The tests mostly use periodicSecs 60, which
 * sends more power reports than the real plug: its periodic power report (parameter 10) is every 700 minutes,
 * and its once-a-minute report (parameter 16) is voltage (configuration read 2026-10-21). `watts` is the
 * pump's draw as the meter sees it at a time. `otherEverySecs` adds another app's refreshes from `otherFrom` until `otherTo`: the
 * dosing app refreshes every 10 s during a dose. Returns how many refreshes that other app sent.
 */
def runPlug = { app, meter, long untilMs, Map p ->
    int other = 0
    List<Long> otherAnswers = []
    Long nextOther = p.otherFrom as Long
    Long period = p.periodicSecs != null ? (p.periodicSecs as Long) * 1000L : null
    if (period != null && meter.nextPeriodic == null) meter.nextPeriodic = app.clockMs + period
    while (app.clockMs < untilMs) {
        app.runUntil(Math.min(untilMs, app.clockMs + 500L))
        long t = app.clockMs
        BigDecimal w = (p.watts != null ? p.watts.call(t) : 0G) as BigDecimal
        if (((meter.vals.power ?: 0G) as BigDecimal).subtract(w).abs() >= 5G) deliver(app, meter, "power", w)
        if (period != null && t >= meter.nextPeriodic) {
            meter.nextPeriodic = meter.nextPeriodic + period
            deliver(app, meter, "power", w)
        }
        if (p.otherEverySecs && nextOther != null && t >= nextOther && t < (p.otherTo as Long)) {
            other++
            otherAnswers << t + 500L
            nextOther += (p.otherEverySecs as Long) * 1000L
        }
        int due = meter.pendingAnswers.count { it <= t } + otherAnswers.count { it <= t }
        meter.pendingAnswers.removeAll { it <= t }
        otherAnswers.removeAll { it <= t }
        due.times {
            deliver(app, meter, "switch", p.relay != null ? p.relay : meter.vals.switch)
            deliver(app, meter, "power", w)
            meter.report("amperage", (w * 0.013G).setScale(2, RoundingMode.HALF_UP))
            meter.report("voltage", 120.5G)
        }
    }
    return other
}

/** The pump's draw as plug 4680 showed it on Oct 12 and 13: nothing for 2.8 s after the ON, 7.6 W in the
 *  first report, then 7.1 W easing to 6.7 W by the OFF, nothing from 1.5 s after it. */
def pumpWatts = { long on, long off ->
    return { long t ->
        if (t < on + 2_800L || t >= off + 1_500L) return 0G
        if (t < on + 12_000L) return 7.6G
        BigDecimal f = (t - on) / (off - on)
        return (7.1G - 0.4G * f).setScale(1, RoundingMode.HALF_UP)
    }
}

/** A steady draw of `level` watts between the plug's report lag after the ON and 1.5 s after the OFF. */
def steadyWatts = { BigDecimal level -> { long on, long off -> { long t -> (t < on + 2_800L || t >= off + 1_500L) ? 0G : level } } }

/**
 * A run of `secs`: ON, pumping, OFF, 30 s more. By default the dosing app refreshes every 10 s from 2 s
 * after the ON to the OFF, as it does during a dose (dosingApp: false leaves it out), and the plug sends a
 * power report every 60 s (periodicSecs: null for a plug that reports only changes; see runPlug). `watts` is a factory
 * { on, off -> { t -> W } }. Returns how many refreshes the dosing app sent.
 */
def doseRun = { app, meter, BigDecimal secs, Map opts = [:] ->
    long on = app.clockMs
    long off = on + (secs * 1000G).longValue()
    Closure watts = (opts.watts ?: pumpWatts).call(on, off)
    def periodic = opts.containsKey("periodicSecs") ? opts.periodicSecs : 60
    switchTo(app, meter, "on")
    Map plug = [watts: watts, periodicSecs: periodic]
    if (opts.dosingApp != false) plug += [otherEverySecs: 10, otherFrom: on + 2_000L, otherTo: off]
    int other = runPlug(app, meter, off, plug)
    switchTo(app, meter, "off")
    runPlug(app, meter, off + 30_000L, [watts: watts, periodicSecs: periodic])
    return other
}

def runFiles = { app -> app.fileStore.keySet().findAll { it.startsWith("chlorine-pump-2") }.sort() }
def summaryLines = { app -> (app.fileStore["chlorine-pump-summary.csv"] ?: "").readLines() }
def csvRows = { app, String file -> app.fileStore[file].readLines().drop(1).collect { it.split(",", -1) as List } }

/** A 60 s run: ON, power reported 3.1 s later, steady 6.7 to 7.0 W, OFF, 0 W reported 1.5 s after it. */
def normalRun = { app, meter ->
    long on = app.clockMs
    switchTo(app, meter, "on")
    app.runUntil(on + 3_100L)
    deliver(app, meter, "power", 7.0G); meter.report("amperage", 0.09G)
    [10_000L, 20_000L, 30_000L, 40_000L, 50_000L].eachWithIndex { long dt, int i ->
        app.runUntil(on + dt)
        deliver(app, meter, "power", i % 2 == 0 ? 6.7G : 6.9G)
    }
    app.runUntil(on + 60_000L)
    switchTo(app, meter, "off")
    app.runUntil(on + 61_500L)
    deliver(app, meter, "power", 0G); meter.report("amperage", 0G)
    app.runUntil(on + 90_000L)
    return on
}

// --------------------------------------------------------------------------- fix 10: the poll chain

check("profiler fix 10: a refresh that throws does not end the capture's heartbeat") {
    def (app, meter) = newProfiler()
    long on = app.clockMs
    meter.refreshThrows = true
    switchTo(app, meter, "on")
    app.runUntil(on + 6_000L)                                  // the refresh after the ON throws
    expect(meter.refreshCalls == 1 && app.state.capturing == true && app.dueAt["poll"] != null, "the capture is still running: ${app.dueAt}")
    app.runUntil(on + 100_000L)                                // and so does a fallback poll's refresh
    expect(meter.refreshCalls == 2 && app.dueAt["poll"] != null, "the fallback poll ran and the heartbeat goes on: ${meter.refreshTimes}")
    meter.refreshThrows = false
    switchTo(app, meter, "off")
    app.runUntil(app.clockMs + 60_000L)
    expect(app.state.capturing == false && runFiles(app).size() == 1, "and ends normally, with its file: ${app.fileStore.keySet()}")
    expect(app.logLines.any { it.contains("refresh failed") }, "the failure is logged")
}

check("profiler fix 10: an ON during a stale capture closes it and profiles the new run") {
    def (app, meter) = newProfiler()
    switchTo(app, meter, "on")
    app.unschedule("poll")                                     // the 1.0.1 failure: a capture with no poll left
    app.clockMs += 600_000L
    meter.report("switch", "off")                              // its OFF was never delivered
    switchTo(app, meter, "on")
    expect(runFiles(app).size() == 1, "the stale capture is saved: ${app.fileStore.keySet()}")
    expect(app.state.capturing == true && app.state.reason == "switch on" && app.state.startMs == app.clockMs,
           "and a new capture starts for this run")
    expect(app.state.lastSummary.endReason == "stale capture closed", "with an honest end reason")
    expect(app.dueAt["poll"] != null, "and it polls")
}

check("profiler fix 10: an ON in the tail window after an OFF starts a new capture") {
    def (app, meter) = newProfiler()
    switchTo(app, meter, "on")
    app.runUntil(app.clockMs + 20_000L)
    switchTo(app, meter, "off")
    app.runUntil(app.clockMs + 5_000L)
    switchTo(app, meter, "on")
    expect(runFiles(app).size() == 1 && app.state.lastSummary.endReason == "switch on again", "the first run is saved")
    app.runUntil(app.clockMs + 60_000L)
    switchTo(app, meter, "off")
    app.runUntil(app.clockMs + 30_000L)
    expect(runFiles(app).size() == 2 && app.state.capturing == false, "and the second one too: ${runFiles(app)}")
}

// --------------------------------------------------------------------------- fix 11: the summary file

check("profiler fix 11: a summary file that cannot be read is never overwritten; its row is written later") {
    ["downloadThrows", "downloadNull", "listThrows", "listNull", "uploadThrows"].each { mode ->
        def (app, meter) = newProfiler()
        String old = "start,reason\nrow1\nrow2\nrow3\n"
        app.fileStore["chlorine-pump-summary.csv"] = old
        app.hubFilesMode = mode
        normalRun(app, meter)
        expect(app.fileStore["chlorine-pump-summary.csv"] == old, "[${mode}] the file is untouched")
        expect(app.state.summaryBacklog?.size() == 1, "[${mode}] the row is kept: ${app.state.summaryBacklog}")
        app.hubFilesMode = "ok"
        app.clockMs += 3_600_000L
        normalRun(app, meter)
        List lines = summaryLines(app)
        expect(lines.take(4) == old.readLines() && lines.size() == 6, "[${mode}] both rows are appended to the old file: ${lines.size()} lines")
        expect(app.state.summaryBacklog == null, "[${mode}] and the backlog is cleared")
    }
}

check("profiler fix 11: only a file missing from File Manager starts a new summary") {
    def (app, meter) = newProfiler()
    normalRun(app, meter)
    List lines = summaryLines(app)
    expect(lines.size() == 2 && lines[0].startsWith("start,reason,endReason,durationSec"), "header and one row: ${lines}")
}

check("profiler fix 11: a 1.0.x summary keeps its rows and gains the new columns at the end") {
    def (app, meter) = newProfiler()
    String legacyHeader = "start,reason,endReason,durationSec,samples,onSamples,wattsMin,wattsAvg,wattsMax,ampsMin,ampsAvg,ampsMax,voltsAvg,whIntegrated,kwhMeterStart,kwhMeterEnd,file"
    String oldRow = "2026-10-02 19:45:25,switch on,switch off,281.4,98,93,6.30,6.71,7.20,0.080,0.085,0.090,120.4,0.528,0,0,chlorine-pump-20261002-194525.csv"
    app.fileStore["chlorine-pump-summary.csv"] = "${legacyHeader}\n${oldRow}\n".toString()
    normalRun(app, meter)
    List lines = summaryLines(app)
    expect(lines[0] == legacyHeader + ",powerDelaySec,profilerVersion,steadyWattsAvg,steadySamples,wearMedianWatts,wearDeviationPct,profilerRefreshes,powerEvents",
           "the header is extended at its end: ${lines[0]}")
    expect(lines[1] == oldRow, "the old row is kept as it was")
    List cells = lines[2].split(",", -1) as List
    expect(cells.size() == 25 && cells[18] == "1.2.0", "the new row has every column: ${lines[2]}")
}

// --------------------------------------------------------------------------- 1.1.1 (WOR-731)

check("profiler 1.1.1 fix 1: a summary file the listing leaves out, or that reads as 0 bytes, is never written over") {
    String old = "start,reason\nrow1\nrow2\nrow3\n"
    // The listing leaves the file out, but it is there: the row is appended to it (1.1.0: header + row over it).
    def (a1, m1) = newProfiler()
    a1.fileStore["chlorine-pump-summary.csv"] = old
    a1.hubFilesMode = "listEmpty"
    normalRun(a1, m1)
    List lines = summaryLines(a1)
    expect(lines.take(4) == old.readLines() && lines.size() == 5, "[listing leaves it out] the old rows stay: ${lines}")
    expect(a1.state.summaryBacklog == null, "[listing leaves it out] and the row is written")

    // A listed file that reads as 0 bytes may have content: it is left alone and the row is kept for later.
    def (a2, m2) = newProfiler()
    a2.fileStore["chlorine-pump-summary.csv"] = old
    a2.hubFilesMode = "downloadEmpty"
    normalRun(a2, m2)
    expect(a2.fileStore["chlorine-pump-summary.csv"] == old, "[0-byte read] the file is untouched: ${a2.fileStore['chlorine-pump-summary.csv']}")
    expect(a2.state.summaryBacklog?.size() == 1, "[0-byte read] the row is kept: ${a2.state.summaryBacklog}")
    a2.hubFilesMode = "ok"
    a2.clockMs += 3_600_000L
    normalRun(a2, m2)
    expect(summaryLines(a2).take(4) == old.readLines() && summaryLines(a2).size() == 6, "[0-byte read] both rows are appended once it reads: ${summaryLines(a2)}")

    // Control: with the listing leaving it out and no file there, a new summary still starts.
    def (a3, m3) = newProfiler()
    a3.hubFilesMode = "listEmpty"
    normalRun(a3, m3)
    expect(summaryLines(a3).size() == 2 && summaryLines(a3)[0].startsWith("start,reason,endReason"), "[no file] header and row: ${summaryLines(a3)}")
}

check("profiler 1.1.1 fix 2: the profiler declares singleThreaded, so the OFF handler and a poll cannot overlap") {
    def (app, meter) = newProfiler()
    expect(app.definitionArgs?.singleThreaded == true, "definition(singleThreaded: true), as the dosing app has: ${app.definitionArgs}")
}

check("2.5.1: the package manifest ships the profiler as an optional app at its importUrl and version") {
    def (app, meter) = newProfiler()
    String url = "https://raw.githubusercontent.com/chsbusch-dot/hubitat-waterguru-dosing-advisor/main/apps/PumpPowerProfiler.groovy"
    Map manifest = new JsonSlurper().parse(new File("packageManifest.json")) as Map
    List entries = (manifest.apps ?: []) + (manifest.drivers ?: [])
    List prof = entries.findAll { it.name == "Pump Power Profiler" }
    expect(prof.size() == 1, "one profiler entry: ${entries*.name}")
    Map e = prof[0] as Map
    expect(e.required == false && e.oauth == false && e.primary == false, "optional, no OAuth, not the primary app: ${e}")
    expect(e.namespace == "chsbusch-dot" && e.location == url && app.definitionArgs?.importUrl == url,
           "the manifest and definition() point at the same raw main URL: ${e.location} / ${app.definitionArgs?.importUrl}")
    String version = profilerFile.text.find(/PROFILER_VERSION = "([^"]+)"/) { all, v -> v }
    expect(e.version == version && version == "1.2.0", "the manifest version is the profiler's: ${e.version} / ${version}")
    expect(e.id ==~ /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/ && entries.count { it.id == e.id } == 1,
           "its own UUID: ${entries*.id}")
    expect(entries.findAll { it.name != "Pump Power Profiler" }.every { it.required == true }, "the other three stay required: ${entries}")
}

check("profiler 1.1.1 fix 3: a power report landing during a sample is never paired with the value from before it") {
    def (app, meter) = newProfiler()
    long on = app.clockMs
    boolean landed = false
    // The plug's first power report lands while the profiler is reading the meter for the ON's row.
    meter.beforeStateRead = { String a ->
        if (!landed && app.state.capturing == true) {
            landed = true
            meter.report("power", 7.0G)
            meter.report("amperage", 0.09G)
        }
    }
    switchTo(app, meter, "on")
    deliver(app, meter, "power", 7.0G)                         // and then reaches the subscribers, unchanged by now
    [10_000L, 20_000L, 30_000L, 40_000L, 50_000L].eachWithIndex { long dt, int i ->
        app.runUntil(on + dt)
        deliver(app, meter, "power", i % 2 == 0 ? 6.7G : 6.9G)
    }
    app.runUntil(on + 60_000L)
    switchTo(app, meter, "off")
    app.runUntil(on + 61_500L)
    deliver(app, meter, "power", 0G)
    app.runUntil(on + 90_000L)
    expect(landed, "sanity: the report landed during a sample")
    Map s = app.state.lastSummary
    expect(s.wattsMin == 6.70G && s.ampsMin == 0.090G, "no 0 W or 0 A from before the report (1.1.0: 0.00 and 0.000): ${s.wattsMin} W, ${s.ampsMin} A")
    // The ON's row, the unchanged 7.0 W event and the five reports: 7.0, 7.0, 6.7, 6.9, 6.7, 6.9, 6.7 W.
    expect(s.onSamples == 7 && s.wattsAvg == 6.84G, "the ON's row counts with the 7.0 W it saw: ${s.onSamples} at ${s.wattsAvg} W")
    List row = csvRows(app, runFiles(app)[0])[0]
    expect(row[4] == "7.0" && (row[9] as Long) == on, "the ON's row pairs 7.0 W with the report's own date: ${row}")
}

// --------------------------------------------------------------------------- fix 12: statistics

check("profiler fix 12: the Oct 3 19:48 run counts only the power reported while it ran") {
    def (app, meter) = newProfiler()
    switchTo(app, meter, "on")                                 // 19:48:25.480
    [["19:48:28.097", "amperage", 0.09G], ["19:48:28.622", "power", 7G], ["19:48:29.677", "voltage", 120.32G],
     ["19:48:31.394", "switch", "off"], ["19:48:32.770", "power", 6.8G], ["19:48:32.932", "voltage", 120.13G],
     ["19:48:33.043", "amperage", 0.08G], ["19:48:35.645", "power", 6.6G], ["19:48:36.094", "voltage", 120.26G],
     ["19:48:37.528", "power", 0G], ["19:48:47.347", "amperage", 0G]].each { e ->
        app.runUntil(at(e[0]))
        deliver(app, meter, e[1], e[2])
    }
    app.runUntil(at("19:49:00.000"))
    Map s = app.state.lastSummary
    expect(s.wattsAvg?.toString() != "0.00", "no stale 0 W average (1.0.1: 0.00): ${s.wattsAvg}")
    // 1.1.1 polled at 28.480 (before the report) and 31.480 (after the OFF) and counted nothing; the report itself is a row now.
    expect(s.onSamples == 1 && s.wattsAvg == 7.00G, "the one report taken while the pump ran counts: ${s.onSamples} at ${s.wattsAvg} W")
    List rows = app.fileStore[runFiles(app)[0]].readLines()
    expect(rows[0].endsWith(",switch_date_ms,watts_date_ms,amps_date_ms,volts_date_ms,kwh_date_ms,source,event_date_ms"), "each reading's date is in the CSV: ${rows[0]}")
    List first = rows[1].split(",", -1) as List                 // the ON's row: 0 W from yesterday
    expect(first[4] == "0" && (first[9] as Long) < at("19:48:25.480") && first[13] == "on", "and shows why that 0 W was stale: ${rows[1]}")
}

check("profiler fix 12: a normal run counts only fresh power, and Wh runs from the ON to the OFF") {
    def (app, meter) = newProfiler()
    long on = normalRun(app, meter)
    Map s = app.state.lastSummary
    expect((s.wattsMin as BigDecimal) >= 6.7G && (s.wattsMax as BigDecimal) <= 7.0G, "only the running power: ${s.wattsMin} to ${s.wattsMax}")
    expect(s.onSamples == 6, "one row per power report after the ON, up to the OFF: ${s.onSamples}")
    expect(s.powerDelaySec == 3.1G, "the plug's power delay is reported: ${s.powerDelaySec}")
    // About 6.8 W for the 60 s from the ON to the OFF; the 0 W tail report adds nothing.
    BigDecimal wh = s.whIntegrated as BigDecimal
    expect(wh > 0.110G && wh < 0.119G, "Wh over the 60 s run only: ${wh}")
    expect(s.profilerVersion == "1.2.0", "rows say which rules made them")
}

check("profiler fix 12: a manual capture of a pump already running counts from the switch's own ON") {
    def (app, meter) = newProfiler()
    meter.report("switch", "on")                               // on before the capture
    app.clockMs += 1_000L
    meter.report("power", 6.8G)
    app.clockMs += 10_000L
    app.appButtonHandler("btnStart")
    app.runUntil(app.clockMs + 30_000L)
    app.appButtonHandler("btnStop")
    Map s = app.state.lastSummary
    expect(s.onSamples > 0 && (s.wattsMin as BigDecimal) == 6.8G, "the running samples count: ${s}")
}

// --------------------------------------------------------------------------- 1.2.0 (WOR-716): event-first sampling

check("Gets: a 13.5 minute dose, the dosing app refreshing every 10 s") {
    def (app, meter) = newProfiler()
    int dosingApp = doseRun(app, meter, 814.6G)
    println "  Gets: 13.5 min dose, dosing app refreshing  : profiler ${meter.refreshCalls} refreshes (${meter.refreshCalls * GETS_PER_REFRESH} Gets); dosing app ${dosingApp} refreshes (${dosingApp * GETS_PER_REFRESH} Gets)"
    if (!countOnly) {
        expect(meter.refreshCalls == 2, "only the refreshes after the ON and the OFF: ${meter.refreshCalls}")
        expect(app.state.lastSummary.profilerRefreshes == 2 && app.state.lastSummary.endReason == "switch off", "the summary counts them: ${app.state.lastSummary}")
    }
}

check("Gets: a 13.5 minute run with no other app refreshing, the plug reporting once a minute") {
    def (app, meter) = newProfiler()
    doseRun(app, meter, 814.6G, [dosingApp: false])
    println "  Gets: 13.5 min run, 1 min reports only      : profiler ${meter.refreshCalls} refreshes (${meter.refreshCalls * GETS_PER_REFRESH} Gets)"
    if (!countOnly) expect(meter.refreshCalls == 2, "the 1 minute report keeps the 90 s fallback quiet: ${meter.refreshTimes}")
}

check("Gets: a 13.5 minute run on a plug that reports only changes") {
    def (app, meter) = newProfiler()
    doseRun(app, meter, 814.6G, [dosingApp: false, periodicSecs: null])
    println "  Gets: 13.5 min run, change reports only     : profiler ${meter.refreshCalls} refreshes (${meter.refreshCalls * GETS_PER_REFRESH} Gets)"
    // After the ON's refresh at +5 s, a poll once 90 s pass without a report (each poll's own answer restarts
    // the clock, so they come 91 s apart: +96 s to +733 s), then the OFF's.
    if (!countOnly) expect(meter.refreshCalls == 10, "2 + 8 fallback polls: ${meter.refreshTimes}")
}

check("profiler 1.2.0: one refresh 5 s after the ON and one 3 s after the OFF, none while reports arrive") {
    def (app, meter) = newProfiler()
    long on = app.clockMs
    doseRun(app, meter, 814.6G)
    expect(meter.refreshTimes == [on + 5_000L, on + 814_600L + 3_000L], "refreshes at +5 s and OFF + 3 s: ${meter.refreshTimes.collect { (it - on) / 1000 }}")
    expect(app.state.lastSummary.endReason == "switch off" && app.state.capturing == false, "and the capture ends: ${app.state.lastSummary}")
}

check("profiler 1.2.0: every power report during a capture is a row, unchanged ones included") {
    def (app, meter) = newProfiler()
    doseRun(app, meter, 814.6G)
    Map s = app.state.lastSummary
    List rows = csvRows(app, runFiles(app)[0])
    List power = rows.findAll { it[13] == "power" }
    // 81 answers to the dosing app's refreshes, 13 minute reports, the ON's change report, the two refresh answers and the 0 W.
    expect(power.size() == s.powerEvents && s.powerEvents >= 95, "a row per report: ${power.size()} rows, ${s.powerEvents} reports")
    int unchanged = power.count { (it[14] as Long) != (it[9] as Long) }
    expect(unchanged >= 30, "reports that repeat the stored value still reach the profiler: ${unchanged}")
    expect(power.every { it[14] != "" } && rows.findAll { it[13] != "power" }*.getAt(13) == ["on", "off"], "each row says where it came from: ${rows*.getAt(13).unique()}")
    def sub = app.subscriptions.find { it.attr == "power" }
    expect(sub == null, "the power subscription ended with the capture")
}

check("profiler 1.2.0: power reports reach the profiler only during a capture") {
    def (app, meter) = newProfiler()
    expect(app.subscriptions*.attr == ["switch"], "idle: the switch only: ${app.subscriptions*.attr}")
    deliver(app, meter, "power", 1.2G)
    expect(app.state.capturing == false && (app.state.rows ?: []).isEmpty(), "an idle report records nothing")
    switchTo(app, meter, "on")
    def sub = app.subscriptions.find { it.attr == "power" }
    expect(sub?.handler == "powerHandler" && sub.options?.filterEvents == false, "capturing: power too, unchanged reports included: ${app.subscriptions}")
    app.runUntil(app.clockMs + 20_000L)
    switchTo(app, meter, "off")
    app.runUntil(app.clockMs + 30_000L)
    expect(app.state.capturing == false && app.subscriptions*.attr == ["switch"], "after the capture: the switch only again: ${app.subscriptions*.attr}")
}

check("profiler 1.2.0: a plug that goes quiet is polled after fallbackSecs of silence, and only then") {
    def (app, meter) = newProfiler()
    app.fallbackSecs = 30
    long on = app.clockMs
    switchTo(app, meter, "on")
    app.runUntil(on + 50_000L)                                 // no reports at all: the ON's refresh (+5 s), then +35 s
    expect(meter.refreshTimes == [on + 5_000L, on + 35_000L], "a poll 30 s after the last refresh: ${meter.refreshTimes.collect { (it - on) / 1000 }}")
    expect(app.state.rows.count { it.src == "poll" } == 1, "the poll records a row")
    deliver(app, meter, "power", 6.8G)                         // a report at +50 s restarts the quiet clock
    app.runUntil(on + 79_000L)
    expect(meter.refreshCalls == 2, "nothing before 30 s after it: ${meter.refreshTimes.collect { (it - on) / 1000 }}")
    app.runUntil(on + 81_000L)
    expect(meter.refreshCalls == 3 && meter.refreshTimes[-1] == on + 80_000L, "and a poll at +80 s: ${meter.refreshTimes.collect { (it - on) / 1000 }}")
}

check("profiler 1.2.0: the default fallback is 90 s") {
    def (app, meter) = newProfiler()
    long on = app.clockMs
    switchTo(app, meter, "on")
    app.runUntil(on + 200_000L)
    expect(meter.refreshTimes == [on + 5_000L, on + 95_000L, on + 185_000L], "every 90 s of silence: ${meter.refreshTimes.collect { (it - on) / 1000 }}")
}

check("profiler 1.2.0: the tail ends tailSecs after the OFF and keeps the 0 W report") {
    def (app, meter) = newProfiler()
    app.tailSecs = 10
    long on = app.clockMs
    long off = on + 120_000L
    Closure watts = pumpWatts(on, off)
    switchTo(app, meter, "on")
    runPlug(app, meter, off, [watts: watts, periodicSecs: 60, otherEverySecs: 10, otherFrom: on + 2_000L, otherTo: off])
    switchTo(app, meter, "off")
    runPlug(app, meter, off + 9_500L, [watts: watts, periodicSecs: 60])
    expect(app.state.capturing == true, "still recording 9.5 s after the OFF")
    runPlug(app, meter, off + 10_500L, [watts: watts, periodicSecs: 60])
    expect(app.state.capturing == false && app.uploads.size() == 2, "saved 10 s after it (the default is 15 s): ${app.uploads}")
    Map s = app.state.lastSummary
    expect(s.endReason == "switch off" && s.durationSec == 120.0G, "the run ends at its OFF: ${s}")
    List rows = csvRows(app, runFiles(app)[0])
    expect(rows.any { it[4] == "0" && (it[0] as Long) > off }, "the 0 W after the OFF is in the file")
}

check("profiler 1.2.0: a capture ends after 60 minutes") {
    def (app, meter) = newProfiler()
    long start = app.clockMs
    app.appButtonHandler("btnStart")
    runPlug(app, meter, start + 3_599_000L, [periodicSecs: 60])
    expect(app.state.capturing == true, "still capturing at 59:59")
    runPlug(app, meter, start + 3_600_000L, [periodicSecs: 60])
    expect(app.state.capturing == false && app.state.lastSummary.endReason == "max duration reached", "ended at 60:00: ${app.state.lastSummary}")
}

check("profiler 1.2.0: a Done during a capture keeps the power subscription and the heartbeat") {
    def (app, meter) = newProfiler()
    long on = app.clockMs
    switchTo(app, meter, "on")
    app.runUntil(on + 20_000L)
    app.updated()
    def sub = app.subscriptions.find { it.attr == "power" }
    expect(app.state.capturing == true && sub?.options?.filterEvents == false && app.dueAt["poll"] != null, "still capturing: ${app.subscriptions} / ${app.dueAt}")
    deliver(app, meter, "power", 6.9G)
    expect(app.state.rows.count { it.src == "power" } == 1, "and recording reports")
    switchTo(app, meter, "off")
    app.runUntil(app.clockMs + 30_000L)
    expect(app.state.capturing == false && runFiles(app).size() == 1, "the run is saved")
}

check("profiler 1.2.0: amps count only when reported after the ON") {
    def (app, meter) = newProfiler()
    normalRun(app, meter)                                      // the first power report lands before its amps
    Map s = app.state.lastSummary
    expect(s.ampsMin == 0.090G && s.ampsAvg == 0.090G, "no 0 A from before the ON: ${s.ampsMin} / ${s.ampsAvg}")
}

check("profiler 1.2.0: a 1.1.x summary header gains the 1.2.0 columns; the run file gains two at its end") {
    def (app, meter) = newProfiler()
    String header = "start,reason,endReason,durationSec,samples,onSamples,wattsMin,wattsAvg,wattsMax,ampsMin,ampsAvg,ampsMax,voltsAvg,whIntegrated,kwhMeterStart,kwhMeterEnd,file,powerDelaySec,profilerVersion"
    String oldRow = "2026-10-13 19:45:26,switch on,switch off,814.6,162,157,6.60,6.84,7.80,0.090,0.090,0.100,119.4,1.548,0.002,0.004,chlorine-pump-20261013-194526.csv,3.1,1.1.1"
    app.fileStore["chlorine-pump-summary.csv"] = "${header}\n${oldRow}\n".toString()
    normalRun(app, meter)
    List lines = summaryLines(app)
    expect(lines[0] == header + ",steadyWattsAvg,steadySamples,wearMedianWatts,wearDeviationPct,profilerRefreshes,powerEvents", "extended: ${lines[0]}")
    expect(lines[1] == oldRow && lines.size() == 3, "the old row stays")
    List cells = lines[2].split(",", -1) as List
    expect(cells[23] == "2" && cells[24] == "7", "two refreshes sent (after the ON and the OFF), seven reports: ${lines[2]}")
    List row = app.fileStore[runFiles(app)[0]].readLines()[0].split(",", -1) as List
    expect(row.size() == 15 && row[12] == "kwh_date_ms", "the run file keeps its 13 columns in place: ${row}")
}

// --------------------------------------------------------------------------- 1.2.0 (WOR-716): wear monitor

/** Seed the wear history with runs of these steady watts, as earlier runs would have left it. */
def seedWear = { app, List ws ->
    app.state.wearRuns = ws.withIndex().collect { w, i -> [t: app.clockMs - (ws.size() - i) * 86_400_000L, w: w as BigDecimal, n: 70,
                                                          median: 6.85G, pct: 0.0G, deviates: false] }
}

/** A 2 minute dose at a steady `watts`, one hour after the previous one. */
def wearRun = { app, meter, BigDecimal watts ->
    app.runUntil(app.clockMs + 3_600_000L)
    doseRun(app, meter, 120G, [watts: steadyWatts(watts)])
    return app.state.lastSummary
}

check("profiler 1.2.0 wear: the steady phase leaves out the start transient and the stop") {
    def (app, meter) = newProfiler()
    long on = app.clockMs
    long off = on + 296_000L
    // 9.6 W for the first 30 s, 6.8 W, and 3.0 W in the last 5 s before the OFF.
    Closure shape = { long o, long f -> { long t -> t < o + 2_800L || t >= f + 1_500L ? 0G : (t < o + 30_000L ? 9.6G : (t >= f - 5_000L ? 3.0G : 6.8G)) } }
    doseRun(app, meter, 296G, [watts: shape])
    Map s = app.state.lastSummary
    expect(s.steadyWattsAvg == 6.80G, "the steady phase is 6.8 W: ${s.steadyWattsAvg}")
    expect((s.wattsMax as BigDecimal) == 9.6G && (s.wattsMin as BigDecimal) == 3.0G, "while the run's own statistics keep both ends: ${s.wattsMin} to ${s.wattsMax}")
    expect(s.steadySamples >= 25, "from the samples between +30 s and OFF - 5 s: ${s.steadySamples}")
}

check("profiler 1.2.0 wear: a run with under 60 s of steady phase, or without both ends, is not judged") {
    def (app, meter) = newProfiler()
    doseRun(app, meter, 94G, [watts: steadyWatts(6.8G)])        // 94 - 30 - 5 = 59 s
    expect(app.state.lastSummary.steadyWattsAvg == "" && app.state.wearRuns == null, "59 s is too short: ${app.state.lastSummary}")
    app.runUntil(app.clockMs + 3_600_000L)
    doseRun(app, meter, 96G, [watts: steadyWatts(6.8G)])        // 61 s
    expect(app.state.lastSummary.steadyWattsAvg == 6.80G && app.state.wearRuns?.size() == 1, "61 s is judged: ${app.state.lastSummary}")
    app.runUntil(app.clockMs + 3_600_000L)
    app.appButtonHandler("btnStart")                           // a manual capture stopped by hand has no OFF
    runPlug(app, meter, app.clockMs + 300_000L, [periodicSecs: 60, watts: { long t -> 6.8G }])
    app.appButtonHandler("btnStop")
    expect(app.state.lastSummary.endReason == "manual stop" && app.state.lastSummary.steadyWattsAvg == "" && app.state.wearRuns.size() == 1,
           "a run without its OFF is not judged: ${app.state.lastSummary}")
}

check("profiler 1.2.0 wear: the first three runs build the baseline, the fourth is judged against their median") {
    def (app, meter) = newProfiler()
    [6.8G, 7.0G, 6.9G].each { wearRun(app, meter, it) }
    expect(app.state.wearRuns*.pct == [null, null, null] && app.state.lastSummary.wearDeviationPct == "", "no judgement yet: ${app.state.wearRuns}")
    Map s = wearRun(app, meter, 7.59G)
    expect(s.wearMedianWatts == 6.900G && s.wearDeviationPct == 10.0G, "+10.0% against the 6.90 W median: ${s.wearMedianWatts} / ${s.wearDeviationPct}")
    expect(app.state.wearRuns[-1].deviates == false, "10.0% is not more than 10%")
}

check("profiler 1.2.0 wear: one run off the median alerts nobody; two in a row send one alert") {
    def (app, meter) = newProfiler()
    def phone = new FakeNotifier()
    app.wearNotify = [phone]
    seedWear(app, [6.85G] * 10)
    Map s = wearRun(app, meter, 6.0G)
    expect(s.wearDeviationPct == -12.4G && app.state.wearStreak == 1 && phone.sink.isEmpty(), "one run 12.4% low: no alert: ${s.wearDeviationPct} / ${phone.sink}")
    wearRun(app, meter, 6.1G)
    expect(phone.sink.size() == 1, "the second sends one: ${phone.sink}")
    String msg = phone.sink[0]
    expect(msg.contains("6.00 W and then 6.10 W") && msg.contains("12.4% below and 10.9% below") && msg.contains("threshold is 10%") &&
           msg.contains("OL BYP Chlorine Pump Single"), "and says what it saw: ${msg}")
    expect(app.wearStatusText().contains("Last alert"), "the page shows it: ${app.wearStatusText()}")
}

check("profiler 1.2.0 wear: a third run off the median sends no second alert; a run within the threshold ends the streak") {
    def (app, meter) = newProfiler()
    def phone = new FakeNotifier()
    app.wearNotify = [phone]
    seedWear(app, [6.85G] * 10)
    [6.0G, 6.0G, 6.0G].each { wearRun(app, meter, it) }
    expect(phone.sink.size() == 1 && app.state.wearStreak == 3, "one alert for the streak: ${phone.sink.size()}")
    wearRun(app, meter, 6.4G)                                  // 6.6% below the 6.85 W median
    expect(app.state.wearStreak == 0, "a run within the threshold ends it: ${app.state.wearRuns[-1]}")
    wearRun(app, meter, 7.6G)                                  // 10.9% above
    expect(phone.sink.size() == 1 && app.state.wearStreak == 1, "a new streak starts at one: ${app.state.wearRuns[-1]}")
    wearRun(app, meter, 7.6G)
    expect(phone.sink.size() == 2 && phone.sink[1].contains("above"), "and alerts at two, above the median too: ${phone.sink}")
}

check("profiler 1.2.0 wear: the threshold setting is honoured") {
    [[null, 0], [5, 1]].each { setting, alerts ->
        def (app, meter) = newProfiler()
        def phone = new FakeNotifier()
        app.wearNotify = [phone]
        if (setting != null) app.wearThresholdPct = setting
        seedWear(app, [6.85G] * 10)
        wearRun(app, meter, 7.33G)                             // +7.0%
        wearRun(app, meter, 7.33G)
        expect(phone.sink.size() == alerts, "threshold ${setting ?: 'default 10'}%: ${alerts} alert(s): ${phone.sink}")
    }
}

check("profiler 1.2.0 wear: the median is taken over the previous 10 runs only") {
    def (app, meter) = newProfiler()
    def phone = new FakeNotifier()
    app.wearNotify = [phone]
    seedWear(app, ([8.0G] * 15) + ([6.85G] * 10))              // the median of all 25 would be 8.0 W
    Map s = wearRun(app, meter, 6.85G)
    wearRun(app, meter, 6.85G)
    expect(s.wearMedianWatts == 6.850G && phone.sink.isEmpty(), "6.85 W against the last ten: ${s.wearMedianWatts} / ${phone.sink}")
}

check("profiler 1.2.0 wear: the history keeps the last 30 runs; Clear empties it") {
    def (app, meter) = newProfiler()
    seedWear(app, [6.85G] * 30)
    long oldest = app.state.wearRuns[0].t as Long
    wearRun(app, meter, 6.85G)
    expect(app.state.wearRuns.size() == 30 && (app.state.wearRuns[0].t as Long) > oldest, "capped at 30, the oldest dropped: ${app.state.wearRuns.size()}")
    app.state.wearStreak = 1
    app.appButtonHandler("btnWearReset")
    expect(app.state.wearRuns == null && app.state.wearStreak == null, "cleared")
    expect(app.wearStatusText().startsWith("Wear check: no run long enough"), "${app.wearStatusText()}")
}

check("profiler 1.2.0 wear: without a notification device the alert is logged and shown") {
    def (app, meter) = newProfiler()
    seedWear(app, [6.85G] * 10)
    wearRun(app, meter, 7.6G)
    wearRun(app, meter, 7.6G)
    expect(app.logLines.count { it.startsWith("WARN") && it.contains("Pump wear check") } == 1, "logged once: ${app.logLines.findAll { it.contains('wear') }}")
    expect(app.state.lastWearAlert?.msg?.contains("10.9% above and 10.9% above"), "and kept for the page: ${app.state.lastWearAlert}")
}

println ""
println "  harness : ${HubitatStub.simpleName} + FakeMeter (no hub, no device)"
println "  app     : ${profilerFile.name}${countOnly ? ' (Gets: checks only)' : ''}"
println "  result  : ${checks - failures}/${checks} checks passed"
if (failures) {
    println ""
    failureDetail.each { println "  - ${it}" }
}
System.exit(failures ? 1 : 0)
