/**
 *  Pump Power Profiler
 *
 *  Samples a power-metering switch (watts, amps, volts, kWh) while it runs and
 *  saves each run as a CSV in File Manager, plus one summary row per run in
 *  <prefix>-summary.csv. Read-only toward the device: it only sends refresh(),
 *  it never turns the device on or off.
 *
 *  v1.0.0  2026-10-02  Initial version
 *  v1.0.1  2026-10-02  Saving settings no longer ends a capture in progress
 *  v1.1.0  2026-10-05  Each poll schedules the next one before anything that can fail, and a failed
 *                      refresh or sample is logged instead of ending the capture; an ON during a stale
 *                      or already-ended capture closes it and starts a new one. A summary file is never
 *                      overwritten after a failed read: only a file missing from File Manager starts a
 *                      new one, and a row that could not be saved is kept and written next time.
 *                      Statistics count only samples whose power reading changed after the ON (the
 *                      first sample used to race the plug's ~3 s power report), Wh is integrated from
 *                      the ON to the OFF, and the CSV gives each reading's date. Summary columns are
 *                      appended at the end, so an existing summary file keeps its layout.
 */

import groovy.transform.Field

@Field static final String PROFILER_VERSION = "1.1.0"
// The summary columns: the 1.0.x columns unchanged and in order (the first LEGACY_SUMMARY_KEY_COUNT),
// then the 1.1.0 columns appended. Spelled out in full: the hub's compiler rejects a @Field
// initializer that refers to another @Field (tests/check_hubitat_fields.py guards that).
@Field static final List SUMMARY_KEYS = ["start", "reason", "endReason", "durationSec", "samples", "onSamples",
                                         "wattsMin", "wattsAvg", "wattsMax", "ampsMin", "ampsAvg", "ampsMax",
                                         "voltsAvg", "whIntegrated", "kwhMeterStart", "kwhMeterEnd", "file",
                                         "powerDelaySec", "profilerVersion"]
@Field static final int LEGACY_SUMMARY_KEY_COUNT = 17
// Rows kept for a summary file that could not be read or written, until a later save succeeds.
@Field static final int SUMMARY_BACKLOG_MAX = 60

definition(
    name: "Pump Power Profiler",
    namespace: "chsbusch-dot",
    author: "Christian Busch",
    description: "Samples a power-metering switch during each run and saves a CSV power profile to File Manager.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: false
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Pump Power Profiler", install: true, uninstall: true) {
        section("Name") {
            label title: "Name this profiler", required: false
        }
        section("Device") {
            input "meterDev", "capability.powerMeter", title: "Power-metering switch to profile", required: true
            input "sampleSecs", "number", title: "Sample interval in seconds (2 to 60)", defaultValue: 3, range: "2..60", required: true
            input "tailSamples", "number", title: "Extra samples after the switch turns off", defaultValue: 3, range: "0..20", required: true
            input "maxSamples", "number", title: "Safety cap: maximum samples per capture", defaultValue: 600, range: "10..3000", required: true
            input "filePrefix", "text", title: "File name prefix (letters, digits, dash)", defaultValue: "pump-profile", required: true
        }
        section("Status") {
            paragraph statusText()
            input "btnStart", "button", title: "Start capture now (does not switch the device)"
            input "btnStop", "button", title: "Stop capture and save"
        }
        section("Logging") {
            input "logEnable", "bool", title: "Debug logging (logs every sample)", defaultValue: false
        }
        section {
            paragraph "<small>v${PROFILER_VERSION}</small>"
        }
    }
}

def installed() { initialize() }

def updated() {
    unsubscribe()
    initialize()
    // A settings save (Done) must not end a capture in progress: keep polling.
    if (state.capturing) {
        state.lastPollMs = now()
        runIn(sampleInterval(), "poll", [overwrite: true])
    }
}

def uninstalled() { unschedule() }

void initialize() {
    subscribe(meterDev, "switch", "switchHandler")
    if (state.capturing == null) state.capturing = false
}

String statusText() {
    if (state.capturing) {
        int n = (state.rows ?: []).size()
        return "Capturing since ${fmtTime(state.startMs as Long)} (${state.reason}), ${n} samples so far."
    }
    Map s = state.lastSummary
    String backlog = (state.summaryBacklog instanceof List && state.summaryBacklog) ? " ${state.summaryBacklog.size()} summary row(s) wait to be written to the summary file." : ""
    if (!s) return "Idle. Waiting for the device to turn on.${backlog}"
    return "Idle. Last run: ${s.start}, ${s.durationSec} s, avg ${s.wattsAvg} W, max ${s.wattsMax} W, avg ${s.ampsAvg} A, ${s.whIntegrated} Wh. File: ${s.file}${backlog}"
}

void switchHandler(evt) {
    Long at = eventMs(evt)
    if (evt.value == "on") {
        // 1.1.0: an ON while a capture is open used to be ignored. A capture that already saw its OFF
        // belongs to the previous run, and one whose polls stopped can never finish on its own (1.0.1
        // rescheduled the poll last, so one exception left it open forever and every later ON was lost).
        if (state.capturing && (state.offAtMs != null || captureStale())) {
            finishCapture(state.offAtMs != null ? "switch on again" : "stale capture closed")
        }
        if (!state.capturing) {
            startCapture("switch on", at)
        } else if (state.onAtMs == null) {
            state.onAtMs = at   // a manual capture sees the run begin
        }
    } else if (evt.value == "off" && state.capturing && state.offAtMs == null) {
        state.offAtMs = at
        state.tailLeft = (tailSamples ?: 0) as Integer
        if (logEnable) log.debug "Switch off, taking ${state.tailLeft} tail samples"
    }
}

void appButtonHandler(String btn) {
    if (btn == "btnStart" && !state.capturing) startCapture("manual", null)
    if (btn == "btnStop" && state.capturing) finishCapture("manual stop")
}

void startCapture(String reason, Long onAt) {
    // A manual start on a pump that is already running takes the switch's own ON time.
    if (onAt == null && meterDev.currentValue("switch", true) == "on") onAt = readingMs("switch")
    state.capturing = true
    state.reason = reason
    state.startMs = now()
    state.onAtMs = onAt
    state.offAtMs = null
    state.tailLeft = null
    state.rows = []
    state.lastPollMs = now()
    state.kwhStart = meterDev.currentValue("energy", true)
    log.info "Capture started (${reason}) on ${meterDev.displayName}"
    // 1.1.0: the poll chain is scheduled before anything that can throw.
    runIn(sampleInterval(), "poll", [overwrite: true])
    try { recordRow() } catch (e) { log.error "Pump Power Profiler: first sample failed: ${e.message}" }
    requestRefresh()
}

void poll() {
    if (!state.capturing) return
    state.lastPollMs = now()
    // 1.1.0: first, so a failed sample or refresh below can no longer end the chain.
    runIn(sampleInterval(), "poll", [overwrite: true])
    try { recordRow() } catch (e) { log.error "Pump Power Profiler: sample failed: ${e.message}" }
    int n = (state.rows ?: []).size()
    if (n >= ((maxSamples ?: 600) as Integer)) {
        finishCapture("max samples reached")
        return
    }
    if (state.tailLeft != null) {
        if ((state.tailLeft as Integer) <= 0) {
            finishCapture("switch off")
            return
        }
        state.tailLeft = (state.tailLeft as Integer) - 1
    }
    requestRefresh()
}

void requestRefresh() {
    try { meterDev.refresh() }
    catch (e) { log.warn "Pump Power Profiler: refresh failed (${e.message}); the capture continues" }
}

void recordRow() {
    // Each reading with the date the hub attached to it. The hub moves that date only when the value
    // changes (measured 2026-10-04), so a steady reading keeps the date of its last change.
    Map row = [
        t  : now(),
        sw : meterDev.currentValue("switch", true),
        w  : meterDev.currentValue("power", true),
        a  : meterDev.currentValue("amperage", true),
        v  : meterDev.currentValue("voltage", true),
        e  : meterDev.currentValue("energy", true),
        swAt: readingMs("switch"),
        wAt : readingMs("power"),
        aAt : readingMs("amperage"),
        vAt : readingMs("voltage"),
        eAt : readingMs("energy")
    ]
    List rows = state.rows ?: []
    rows << row
    state.rows = rows
    if (logEnable) log.debug "Sample: ${row}"
}

void finishCapture(String why) {
    unschedule("poll")
    state.capturing = false
    List rows = state.rows ?: []
    if (rows.isEmpty()) {
        log.warn "Capture ended (${why}) with no samples"
        return
    }
    long t0 = state.startMs as Long
    Long onAt = state.onAtMs as Long
    Long offAt = state.offAtMs as Long
    long tEnd = (offAt ?: rows[-1].t) as Long
    String stamp = new Date(t0).format("yyyyMMdd-HHmmss", location.timeZone)
    String prefix = (filePrefix ?: "pump-profile").replaceAll("[^A-Za-z0-9_-]", "")
    String fileName = "${prefix}-${stamp}.csv"

    StringBuilder csv = new StringBuilder("epoch_ms,local_time,seconds_from_start,switch,watts,amps,volts,kwh," +
                                          "switch_date_ms,watts_date_ms,amps_date_ms,volts_date_ms,kwh_date_ms\n")
    rows.each { r ->
        long t = r.t as Long
        csv << "${t},${fmtTime(t)},${round((t - t0) / 1000.0, 1)},${r.sw},${nz(r.w)},${nz(r.a)},${nz(r.v)},${nz(r.e)}," +
               "${nz(r.swAt)},${nz(r.wAt)},${nz(r.aAt)},${nz(r.vAt)},${nz(r.eAt)}\n"
    }

    // 1.1.0: a sample counts when the switch read on, it was taken before the OFF, and its power
    // reading CHANGED after the ON. The first sample after the ON raced the plug's power report (about
    // 3 s on the ZEN05) and recorded the previous run's 0 W; on Oct 3 that alone made the average 0.00 W.
    // Without a known ON (a legacy state), the first sample is skipped as before.
    List counted = []
    rows.eachWithIndex { r, i ->
        boolean on = r.sw == "on" && (offAt == null || (r.t as Long) <= offAt)
        boolean fresh = onAt != null ? (r.w != null && r.wAt != null && (r.wAt as Long) >= onAt) : i > 0
        if (on && fresh) counted << r
    }
    List watts = counted.findAll { it.w != null }.collect { it.w as BigDecimal }
    List amps  = counted.findAll { it.a != null }.collect { it.a as BigDecimal }
    List volts = counted.findAll { it.v != null }.collect { it.v as BigDecimal }

    // Energy for this run, from the ON to the OFF only: the trapezoid rule between counted samples, with
    // the first counted reading held back to the ON (the plug's meter lags the relay) and the last one
    // held to the OFF. Samples before the ON and tail samples after the OFF add nothing. The plug's own
    // kWh counter only moves in 0.01 kWh steps, too coarse for a short run.
    BigDecimal wh = 0
    List powered = counted.findAll { it.w != null }
    for (int i = 1; i < powered.size(); i++) {
        Map p = powered[i - 1]
        Map c = powered[i]
        BigDecimal hours = ((c.t as Long) - (p.t as Long)) / 3600000.0
        wh += (((p.w as BigDecimal) + (c.w as BigDecimal)) / 2) * hours
    }
    if (powered && onAt != null) wh += (powered[0].w as BigDecimal) * Math.max(0L, (powered[0].t as Long) - onAt) / 3600000.0
    if (powered && offAt != null) wh += (powered[-1].w as BigDecimal) * Math.max(0L, offAt - (powered[-1].t as Long)) / 3600000.0

    Map s = [
        start        : fmtTime(t0),
        reason       : state.reason,
        endReason    : why,
        durationSec  : round((tEnd - t0) / 1000.0, 1),
        samples      : rows.size(),
        onSamples    : counted.size(),
        wattsMin     : watts ? round(watts.min(), 2) : "",
        wattsAvg     : watts ? round(watts.sum() / watts.size(), 2) : "",
        wattsMax     : watts ? round(watts.max(), 2) : "",
        ampsMin      : amps ? round(amps.min(), 3) : "",
        ampsAvg      : amps ? round(amps.sum() / amps.size(), 3) : "",
        ampsMax      : amps ? round(amps.max(), 3) : "",
        voltsAvg     : volts ? round(volts.sum() / volts.size(), 1) : "",
        whIntegrated : round(wh, 3),
        kwhMeterStart: nz(state.kwhStart),
        kwhMeterEnd  : nz(rows[-1].e),
        file         : fileName,
        // Seconds from the ON to the first power change after it: how long the plug took to report.
        powerDelaySec: (onAt != null && powered) ? round(((powered[0].wAt as Long) - onAt) / 1000.0, 1) : "",
        profilerVersion: PROFILER_VERSION
    ]
    state.lastSummary = s
    List hist = state.history ?: []
    hist << s
    if (hist.size() > 60) hist = hist.drop(hist.size() - 60)
    state.history = hist
    state.rows = []

    try {
        uploadHubFile(fileName, csv.toString().getBytes("UTF-8"))
    } catch (e) {
        log.error "Could not save ${fileName}: ${e}"
    }
    appendSummary(prefix, s)
    log.info "Capture saved (${why}): ${s}"
}

/**
 * Add the run's row to <prefix>-summary.csv. 1.0.1 treated ANY failed read as "no file yet" and wrote a
 * fresh file over the old one. Now only a file that File Manager's listing does not show starts a new
 * file; a listing or read that fails leaves the file alone and keeps the row in state.summaryBacklog,
 * which is written ahead of the next row once a save succeeds.
 */
void appendSummary(String prefix, Map s) {
    String name = "${prefix}-summary.csv"
    List backlog = (state.summaryBacklog instanceof List) ? new ArrayList(state.summaryBacklog) : []
    backlog << SUMMARY_KEYS.collect { s[it] == null ? "" : s[it] }.join(",")
    if (backlog.size() > SUMMARY_BACKLOG_MAX) backlog = backlog.drop(backlog.size() - SUMMARY_BACKLOG_MAX)

    Boolean present = hubFilePresent(name)
    String existing
    if (present == null) {
        keepSummaryBacklog(backlog, "File Manager could not be listed, so ${name} was not touched")
        return
    }
    if (present) {
        byte[] b = null
        try { b = downloadHubFile(name) } catch (e) { log.warn "Pump Power Profiler: reading ${name} failed: ${e.message}" }
        if (b == null) {
            keepSummaryBacklog(backlog, "${name} exists but could not be read, so it was not overwritten")
            return
        }
        existing = new String(b, "UTF-8")
    } else {
        existing = ""
    }

    StringBuilder out = new StringBuilder()
    if (!existing) {
        out << SUMMARY_KEYS.join(",") << "\n"
    } else {
        List lines = existing.split("\n", -1) as List
        // A 1.0.x header gains the new columns at its end; its rows simply lack them.
        if (lines[0].trim() == SUMMARY_KEYS.take(LEGACY_SUMMARY_KEY_COUNT).join(",")) lines[0] = SUMMARY_KEYS.join(",")
        out << lines.join("\n")
        if (!out.toString().endsWith("\n")) out << "\n"
    }
    backlog.each { out << it << "\n" }
    try {
        uploadHubFile(name, out.toString().getBytes("UTF-8"))
        state.remove("summaryBacklog")
    } catch (e) {
        keepSummaryBacklog(backlog, "writing ${name} failed (${e.message})")
    }
}

void keepSummaryBacklog(List backlog, String why) {
    state.summaryBacklog = backlog
    log.error "Pump Power Profiler: ${why}; ${backlog.size()} summary row(s) kept and written with the next run"
}

/** True / false from File Manager's own listing; null when the listing itself failed. */
Boolean hubFilePresent(String name) {
    try {
        def entries = getHubFiles()
        if (entries == null) return null
        return entries.any { it?.name == name && (it?.type == null || it.type == "file") }
    } catch (e) {
        log.warn "Pump Power Profiler: listing File Manager failed: ${e.message}"
        return null
    }
}

/** A capture whose polls stopped: nothing has polled for several intervals. */
boolean captureStale() {
    Long last = (state.lastPollMs ?: state.startMs) as Long
    long limit = Math.max(60_000L, 4L * sampleInterval() * 1000L)
    return last == null || (now() - last) > limit
}

int sampleInterval() {
    return (sampleSecs ?: 3) as Integer
}

/** The date the hub attached to one reading, as epoch ms, or null. */
Long readingMs(String attr) {
    try {
        def d = meterDev.currentState(attr, true)?.date
        if (d instanceof Date) return d.time
        if (d instanceof Number) return d as Long
    } catch (ignored) { }
    return null
}

Long eventMs(evt) {
    try {
        if (evt?.date instanceof Date) return evt.date.time
    } catch (ignored) { }
    return now()
}

String fmtTime(Long ms) {
    return ms ? new Date(ms).format("yyyy-MM-dd HH:mm:ss", location.timeZone) : ""
}

def round(def val, int places) {
    if (val == null) return ""
    return (val as BigDecimal).setScale(places, BigDecimal.ROUND_HALF_UP)
}

def nz(def v) { v == null ? "" : v }
