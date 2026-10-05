/*
 * Execution tests for the Pump Power Profiler (apps/PumpPowerProfiler.groovy, v1.1.0).
 *
 * The profiler's own methods run against the same stubbed Hubitat runtime as the child app's suite,
 * with a fake meter that follows the hub rule measured on 2026-10-04: a report whose value did not
 * change leaves the reading's date where it was. File Manager is HubitatStub.fileStore, which can be
 * told to fail a listing, a read or a write.
 *
 * Nothing here touches a hub or a device. Run: tests/groovy/run.sh
 */

import org.codehaus.groovy.control.CompilerConfiguration

int checks = 0
int failures = 0
List<String> failureDetail = []

def check = { String name, Closure body ->
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
    boolean refreshThrows = false
    Closure clock

    FakeMeter(Closure clock) {
        this.clock = clock
        vals.keySet().each { dates[it] = (clock.call() as Long) - 86_400_000L }   // last changed yesterday
    }
    def currentValue(String a, boolean skipCache = false) { vals[a] }
    def currentState(String a, boolean skipCache = false) {
        vals.containsKey(a) ? [name: a, value: vals[a], date: new Date(dates[a] as Long)] : null
    }
    def refresh() {
        refreshCalls++
        if (refreshThrows) throw new RuntimeException("simulated refresh failure")
        null
    }
    void report(String a, def v) {
        if (vals[a]?.toString() != v?.toString()) { vals[a] = v; dates[a] = clock.call() as Long }
    }
}

def compilerConfig = new CompilerConfiguration()
compilerConfig.scriptBaseClass = HubitatStub.name
def shell = new GroovyShell(HubitatStub.classLoader, new Binding(), compilerConfig)
def profilerFile = new File("apps/PumpPowerProfiler.groovy")
def profilerClass = shell.parse(profilerFile).getClass()

def at = { String hhmmss -> Date.parse("yyyy-MM-dd HH:mm:ss.SSS Z", "2026-10-03 ${hhmmss} -0700").time }

/** A fresh profiler with the live settings (sampleSecs 3, 3 tail samples), installed at `start`. */
def newProfiler = { long start = at("19:48:25.480") ->
    def app = profilerClass.getDeclaredConstructor().newInstance()
    app.run()
    app.clockMs = start
    def meter = new FakeMeter({ app.clockMs })
    app.meterDev = meter
    app.sampleSecs = 3
    app.tailSamples = 3
    app.maxSamples = 600
    app.filePrefix = "chlorine-pump"
    app.logEnable = false
    app.installed()
    return [app, meter]
}

/** The switch changing, as its subscription delivers it. */
def switchTo = { app, meter, String v ->
    meter.report("switch", v)
    app.switchHandler([name: "switch", value: v, date: new Date(app.clockMs)])
}

def runFiles = { app -> app.fileStore.keySet().findAll { it.startsWith("chlorine-pump-2") }.sort() }
def summaryLines = { app -> (app.fileStore["chlorine-pump-summary.csv"] ?: "").readLines() }

/** A 60 s run: ON, power reported 3.1 s later, steady 6.7 to 7.0 W, OFF, 0 W reported 1.5 s after it. */
def normalRun = { app, meter ->
    long on = app.clockMs
    switchTo(app, meter, "on")
    app.runUntil(on + 3_100L)
    meter.report("power", 7.0G); meter.report("amperage", 0.09G)
    [10_000L, 20_000L, 30_000L, 40_000L, 50_000L].eachWithIndex { long dt, int i ->
        app.runUntil(on + dt)
        meter.report("power", i % 2 == 0 ? 6.7G : 6.9G)
    }
    app.runUntil(on + 60_000L)
    switchTo(app, meter, "off")
    app.runUntil(on + 61_500L)
    meter.report("power", 0G); meter.report("amperage", 0G)
    app.runUntil(on + 90_000L)
    return on
}

// --------------------------------------------------------------------------- fix 10: the poll chain

check("profiler fix 10: a refresh that throws mid-capture does not end the poll chain") {
    def (app, meter) = newProfiler()
    switchTo(app, meter, "on")
    meter.report("power", 6.7G)
    app.runUntil(app.clockMs + 7_000L)
    meter.refreshThrows = true
    app.runUntil(app.clockMs + 3_100L)                         // one poll's refresh throws
    meter.refreshThrows = false
    expect(app.state.capturing == true && app.dueAt["poll"] != null, "the capture is still polling: ${app.dueAt}")
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
    expect(lines[0] == legacyHeader + ",powerDelaySec,profilerVersion", "the header is extended at its end: ${lines[0]}")
    expect(lines[1] == oldRow, "the old row is kept as it was")
    List cells = lines[2].split(",", -1) as List
    expect(cells.size() == 19 && cells[18] == "1.1.0", "the new row has every column: ${lines[2]}")
}

// --------------------------------------------------------------------------- fix 12: statistics

check("profiler fix 12: the Oct 3 19:48 run no longer averages a stale 0 W") {
    def (app, meter) = newProfiler()
    switchTo(app, meter, "on")                                 // 19:48:25.480
    [["19:48:28.097", "amperage", 0.09G], ["19:48:28.622", "power", 7G], ["19:48:29.677", "voltage", 120.32G],
     ["19:48:31.394", "switch", "off"], ["19:48:32.770", "power", 6.8G], ["19:48:32.932", "voltage", 120.13G],
     ["19:48:33.043", "amperage", 0.08G], ["19:48:35.645", "power", 6.6G], ["19:48:36.094", "voltage", 120.26G],
     ["19:48:37.528", "power", 0G], ["19:48:47.347", "amperage", 0G]].each { e ->
        app.runUntil(at(e[0]))
        if (e[1] == "switch") switchTo(app, meter, e[2]) else meter.report(e[1], e[2])
    }
    app.runUntil(at("19:49:00.000"))
    Map s = app.state.lastSummary
    expect(s.wattsAvg != 0.00G && s.wattsAvg?.toString() != "0.00", "no stale 0 W average (1.0.1: 0.00): ${s.wattsAvg}")
    expect(s.onSamples == 0, "no sample caught a power reading taken after the ON while the switch was on: ${s.onSamples}")
    String csv = app.fileStore[runFiles(app)[0]]
    List rows = csv.readLines()
    expect(rows[0].endsWith(",switch_date_ms,watts_date_ms,amps_date_ms,volts_date_ms,kwh_date_ms"), "each reading's date is in the CSV: ${rows[0]}")
    List second = rows[2].split(",", -1) as List                // the 19:48:28.480 sample: 0 W from yesterday
    expect(second[4] == "0" && (second[9] as Long) < at("19:48:25.480"), "and shows why that 0 W was stale: ${rows[2]}")
}

check("profiler fix 12: a normal run counts only fresh power, and Wh runs from the ON to the OFF") {
    def (app, meter) = newProfiler()
    long on = normalRun(app, meter)
    Map s = app.state.lastSummary
    expect((s.wattsMin as BigDecimal) >= 6.7G && (s.wattsMax as BigDecimal) <= 7.0G, "only the running power: ${s.wattsMin} to ${s.wattsMax}")
    expect(s.onSamples == 19, "the samples after the first power report, up to the OFF: ${s.onSamples}")
    expect(s.powerDelaySec == 3.1G, "the plug's power delay is reported: ${s.powerDelaySec}")
    // About 6.8 W for the 60 s from the ON to the OFF; tail samples (7 W shown until the 0 W report) add nothing.
    BigDecimal wh = s.whIntegrated as BigDecimal
    expect(wh > 0.110G && wh < 0.119G, "Wh over the 60 s run only: ${wh}")
    expect(s.profilerVersion == "1.1.0", "rows say which rules made them")
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

println ""
println "  harness : ${HubitatStub.simpleName} + FakeMeter (no hub, no device)"
println "  app     : ${profilerFile.name}"
println "  result  : ${checks - failures}/${checks} checks passed"
if (failures) {
    println ""
    failureDetail.each { println "  - ${it}" }
}
System.exit(failures ? 1 : 0)
