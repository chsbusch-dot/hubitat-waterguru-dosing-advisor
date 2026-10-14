/**
 *  Pump Power Profiler
 *
 *  Records a power-metering switch (watts, amps, volts, kWh) while it runs and saves each run as a
 *  CSV in File Manager, plus one summary row per run in <prefix>-summary.csv. Read-only toward the
 *  device: it only sends refresh(), it never turns the device on or off.
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
 *  v1.1.1  2026-10-06  Each reading's value and date come from the same read, so a report landing in the
 *                      middle of a sample can no longer pair a value from before it with its fresh date (a
 *                      stale 0 W counted as running power). The summary file is read even when File
 *                      Manager's listing leaves it out, and a file known to exist is never written over: one
 *                      that reads as 0 bytes, or that the listing shows but a read cannot return, keeps the
 *                      row in the backlog. The app declares singleThreaded, so the OFF handler and a poll
 *                      can no longer run at the same time.
 *  v1.2.0  2026-10-14  Event-first sampling (WOR-716). A capture subscribes to the device's power reports,
 *                      unchanged ones included, and records a row for each; it sends one refresh 5 s after
 *                      the ON and one 3 s after the OFF, and polls only when no power report has arrived for
 *                      fallbackSecs (default 90 s, longer than the plug's 1 minute report). The fixed poll
 *                      (sampleSecs, about 6 Z-Wave Gets every 5 s, ~740 transmits per 13.5 min dose on the
 *                      Oct 14 capture) is gone; sampleSecs and tailSamples are no longer read. The tail is
 *                      tailSecs after the OFF, a capture ends after 60 minutes, and a fallback poll's answer
 *                      also catches an OFF report that never arrived. Wear monitor: each run's steady-phase
 *                      average (30 s after the ON to 5 s before the OFF) is compared with the median of the
 *                      previous 10 runs, and two runs in a row further from it than wearThresholdPct
 *                      (default 10%) send one alert to the wearNotify devices. The run CSV gains source and
 *                      event_date_ms at its end, the summary steadyWattsAvg, steadySamples, wearMedianWatts,
 *                      wearDeviationPct, profilerRefreshes and powerEvents; an older header is extended.
 */

import groovy.transform.Field

@Field static final String PROFILER_VERSION = "1.2.0"
// The summary columns: the 1.0.x columns unchanged and in order, then the 1.1.0 columns, then the 1.2.0
// columns, each appended at the end. Spelled out in full: the hub's compiler rejects a @Field initializer
// that refers to another @Field (tests/check_hubitat_fields.py guards that).
@Field static final List SUMMARY_KEYS = ["start", "reason", "endReason", "durationSec", "samples", "onSamples",
                                         "wattsMin", "wattsAvg", "wattsMax", "ampsMin", "ampsAvg", "ampsMax",
                                         "voltsAvg", "whIntegrated", "kwhMeterStart", "kwhMeterEnd", "file",
                                         "powerDelaySec", "profilerVersion",
                                         "steadyWattsAvg", "steadySamples", "wearMedianWatts", "wearDeviationPct",
                                         "profilerRefreshes", "powerEvents"]
// Column counts of the released summary layouts (1.0.x and 1.1.x): a file with such a header is extended.
@Field static final List OLDER_SUMMARY_KEY_COUNTS = [17, 19]
// Rows kept for a summary file that could not be read or written, until a later save succeeds.
@Field static final int SUMMARY_BACKLOG_MAX = 60

// 1.2.0 sampling. The plug reports power on its own (4680: on a 5 W change and once a minute), and the
// dosing app refreshes it every 10 s during a dose; the profiler listens and refreshes only around the
// switch changes, or when the device has gone quiet for fallbackSecs.
@Field static final int DEFAULT_FALLBACK_SECS = 90
@Field static final int MIN_FALLBACK_SECS = 15
@Field static final int MAX_FALLBACK_SECS = 900
@Field static final int DEFAULT_TAIL_SECS = 15
@Field static final int MIN_TAIL_SECS = 5
@Field static final int REFRESH_AFTER_ON_SECS = 5
@Field static final int REFRESH_AFTER_OFF_SECS = 3
@Field static final int MAX_CAPTURE_MINUTES = 60

// 1.2.0 wear monitor. The steady phase leaves out the start transient (on Oct 12 and 13 only the first
// report, 7.6 and 7.8 W at about 3 s, stood out) and the stop.
@Field static final int STEADY_SKIP_START_SECS = 30
@Field static final int STEADY_SKIP_END_SECS = 5
@Field static final int WEAR_MIN_STEADY_SECS = 60
@Field static final int WEAR_MIN_STEADY_SAMPLES = 5
@Field static final int WEAR_MEDIAN_RUNS = 10
@Field static final int WEAR_MIN_REFERENCE_RUNS = 3
@Field static final int WEAR_HISTORY_MAX = 30
@Field static final BigDecimal DEFAULT_WEAR_THRESHOLD_PCT = 10G

definition(
    name: "Pump Power Profiler",
    namespace: "chsbusch-dot",
    author: "Christian Busch",
    description: "Records a power-metering switch during each run, saves a CSV power profile to File Manager and watches the steady power for wear.",
    category: "Convenience",
    // 1.1.1: one handler at a time, as the dosing app runs. Without it the OFF event and a poll in flight could
    // overlap, and whichever saved its state last won: a lost OFF kept a capture polling to the sample cap.
    singleThreaded: true,
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
            input "fallbackSecs", "number", title: "Poll the device only when it has sent no power report for this many seconds (15 to 900)",
                  defaultValue: DEFAULT_FALLBACK_SECS, range: "15..900", required: true
            input "tailSecs", "number", title: "Keep recording for this many seconds after the switch turns off (5 to 120)",
                  defaultValue: DEFAULT_TAIL_SECS, range: "5..120", required: true
            input "maxSamples", "number", title: "Safety cap: maximum samples per capture", defaultValue: 600, range: "10..3000", required: true
            input "filePrefix", "text", title: "File name prefix (letters, digits, dash)", defaultValue: "pump-profile", required: true
        }
        section("Wear monitor") {
            paragraph "Each run's average power in its steady phase (from ${STEADY_SKIP_START_SECS} s after the ON to " +
                      "${STEADY_SKIP_END_SECS} s before the OFF) is compared with the median of the previous ${WEAR_MEDIAN_RUNS} runs. " +
                      "Two runs in a row further from it than the threshold send one alert. Runs with less than " +
                      "${WEAR_MIN_STEADY_SECS} s of steady phase are not judged."
            input "wearNotify", "capability.notification", title: "Send wear alerts to", multiple: true, required: false
            input "wearThresholdPct", "decimal", title: "Alert threshold in percent (1 to 50)", defaultValue: 10, range: "1..50", required: true
            input "btnWearReset", "button", title: "Clear the wear history (after replacing the pump or its tube)"
        }
        section("Status") {
            paragraph statusText()
            paragraph wearStatusText()
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
    // A settings save (Done) must not end a capture in progress: keep its subscription and its heartbeat.
    if (state.capturing) {
        state.lastPollMs = now()
        runIn(nextPollSecs(), "poll", [overwrite: true])
    }
}

def uninstalled() { unschedule() }

void initialize() {
    subscribe(meterDev, "switch", "switchHandler")
    if (state.capturing == null) state.capturing = false
    if (state.capturing) subscribePower()
}

String statusText() {
    if (state.capturing) {
        int n = (state.rows ?: []).size()
        return "Capturing since ${fmtTime(state.startMs as Long)} (${state.reason}), ${n} samples so far, " +
               "${state.powerEvents ?: 0} power reports, ${state.refreshes ?: 0} refreshes sent."
    }
    Map s = state.lastSummary
    String backlog = (state.summaryBacklog instanceof List && state.summaryBacklog) ? " ${state.summaryBacklog.size()} summary row(s) wait to be written to the summary file." : ""
    if (!s) return "Idle. Waiting for the device to turn on.${backlog}"
    String steady = s.steadyWattsAvg != null && s.steadyWattsAvg != "" ? ", steady ${s.steadyWattsAvg} W" : ""
    String sent = s.profilerRefreshes != null ? ", ${s.profilerRefreshes} refreshes sent" : ""
    return "Idle. Last run: ${s.start}, ${s.durationSec} s, avg ${s.wattsAvg} W${steady}, max ${s.wattsMax} W, avg ${s.ampsAvg} A, ${s.whIntegrated} Wh${sent}. File: ${s.file}${backlog}"
}

String wearStatusText() {
    List runs = state.wearRuns instanceof List ? state.wearRuns : []
    String alert = state.lastWearAlert instanceof Map ? " Last alert ${fmtTime(state.lastWearAlert.at as Long)}: ${state.lastWearAlert.msg}" : ""
    if (!runs) return "Wear check: no run long enough to judge yet.${alert}"
    Map r = runs[-1]
    if (r.pct == null) {
        return "Wear check: building a baseline, ${runs.size()} run(s) recorded (last ${fmtW(r.w)} W). Runs are judged once ${WEAR_MIN_REFERENCE_RUNS} are recorded.${alert}"
    }
    return "Wear check: last run ${fmtW(r.w)} W in its steady phase, ${pctText(r.pct)} the ${fmtW(r.median)} W median of the runs before.${alert}"
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
        state.lastPollMs = now()
        // 1.2.0: one refresh shortly after the OFF, then the tail runs to tailSecs after it.
        state.refreshDue = true
        runIn(REFRESH_AFTER_OFF_SECS, "poll", [overwrite: true])
        try { recordRow("off", null) } catch (e) { log.error "Pump Power Profiler: sample at the OFF failed: ${e.message}" }
        if (logEnable) log.debug "Switch off, recording until ${tailMs() / 1000} s after it"
    }
}

/**
 * 1.2.0: a power report during a capture. Every report counts, unchanged ones included (the subscription
 * opts out of duplicate filtering), because a steady pump answers each refresh with the same watts.
 */
void powerHandler(evt) {
    if (!state.capturing) return
    long nowMs = now()
    state.powerEvents = ((state.powerEvents ?: 0) as Integer) + 1
    state.quietFromMs = nowMs
    state.lastPollMs = nowMs
    try { recordRow("power", eventMs(evt)) } catch (e) { log.error "Pump Power Profiler: sample failed: ${e.message}" }
    if ((state.rows ?: []).size() >= maxSampleCount()) finishCapture("max samples reached")
}

void appButtonHandler(String btn) {
    if (btn == "btnStart" && !state.capturing) startCapture("manual", null)
    if (btn == "btnStop" && state.capturing) finishCapture("manual stop")
    if (btn == "btnWearReset") {
        state.remove("wearRuns")
        state.remove("wearStreak")
        state.remove("lastWearAlert")
        log.info "Pump Power Profiler: wear history cleared"
    }
}

void startCapture(String reason, Long onAt) {
    // A manual start on a pump that is already running takes the switch's own ON time.
    if (onAt == null) {
        Map sw = readingWithDate("switch")
        if (sw.value?.toString() == "on") onAt = sw.at
    }
    long nowMs = now()
    state.capturing = true
    state.reason = reason
    state.startMs = nowMs
    state.onAtMs = onAt
    state.offAtMs = null
    state.remove("tailLeft")   // 1.1.x tail counter
    state.rows = []
    state.lastPollMs = nowMs
    state.quietFromMs = nowMs
    state.refreshDue = true
    state.refreshes = 0
    state.powerEvents = 0
    state.fallbackPolls = 0
    state.kwhStart = meterDev.currentValue("energy", true)
    log.info "Capture started (${reason}) on ${meterDev.displayName}"
    // 1.1.0: the heartbeat is scheduled before anything that can throw. Its first run sends the refresh after the ON.
    runIn(REFRESH_AFTER_ON_SECS, "poll", [overwrite: true])
    subscribePower()
    try { recordRow(reason == "manual" ? "manual" : "on", null) } catch (e) { log.error "Pump Power Profiler: first sample failed: ${e.message}" }
}

/**
 * The capture's heartbeat. It sends the refresh that follows the ON or the OFF, polls when the device has
 * sent no power report for fallbackSecs, and ends the capture tailSecs after the OFF or after
 * MAX_CAPTURE_MINUTES. Between those it only reschedules itself; the samples come from the power reports.
 */
void poll() {
    if (!state.capturing) return
    long nowMs = now()
    state.lastPollMs = nowMs
    Long offAt = state.offAtMs as Long
    Long startMs = state.startMs as Long
    if (offAt != null && nowMs >= offAt + tailMs()) {
        finishCapture("switch off")
        return
    }
    if (startMs != null && nowMs - startMs >= MAX_CAPTURE_MINUTES * 60_000L) {
        finishCapture("max duration reached")
        return
    }
    boolean due = state.refreshDue == true
    boolean fallback = !due && offAt == null && nowMs - quietFrom() >= fallbackMs()
    state.refreshDue = false
    if (due || fallback) state.quietFromMs = nowMs
    // 1.1.0: the next run is scheduled first, so a failed sample or refresh below can no longer end the chain.
    runIn(nextPollSecs(), "poll", [overwrite: true])
    if (fallback) {
        state.fallbackPolls = ((state.fallbackPolls ?: 0) as Integer) + 1
        try { recordRow("poll", null) } catch (e) { log.error "Pump Power Profiler: sample failed: ${e.message}" }
        if ((state.rows ?: []).size() >= maxSampleCount()) {
            finishCapture("max samples reached")
            return
        }
    }
    if (due || fallback) requestRefresh()
}

/** Seconds to the heartbeat's next run: a refresh still due, the end of the tail after an OFF, otherwise the fallback poll. */
int nextPollSecs() {
    if (state.refreshDue == true) return 1
    long nowMs = now()
    Long offAt = state.offAtMs as Long
    long due = offAt != null ? offAt + tailMs() : quietFrom() + fallbackMs()
    Long startMs = state.startMs as Long
    if (startMs != null) due = Math.min(due, startMs + MAX_CAPTURE_MINUTES * 60_000L)
    long secs = (long) Math.ceil((due - nowMs) / 1000.0d)
    return (int) Math.max(1L, secs)
}

void requestRefresh() {
    state.refreshes = ((state.refreshes ?: 0) as Integer) + 1
    try { meterDev.refresh() }
    catch (e) { log.warn "Pump Power Profiler: refresh failed (${e.message}); the capture continues" }
}

/** 1.2.0: power reports reach the profiler only during a capture, unchanged ones included. */
void subscribePower() {
    try { subscribe(meterDev, "power", "powerHandler", [filterEvents: false]) }
    catch (e) { log.warn "Pump Power Profiler: could not subscribe to power reports (${e.message}); the capture polls every ${fallbackMs() / 1000} s instead" }
}

void dropPowerSubscription() {
    try {
        unsubscribe()
        subscribe(meterDev, "switch", "switchHandler")
    } catch (e) {
        log.error "Pump Power Profiler: could not reset the subscriptions (${e.message}); press Done in the app to restore them"
    }
}

void recordRow(String src, Long evAt) {
    // Each reading with the date the hub attached to it. The hub moves that date only when the value
    // changes (measured 2026-10-04), so a steady reading keeps the date of its last change.
    // 1.1.1: each value and its date from the same read. 1.1.0 read every value, then every date, so a
    // report landing in between paired the value from before it with the report's fresh date: a stale 0 W
    // that counted as running power.
    Map sw = readingWithDate("switch")
    Map w = readingWithDate("power")
    Map a = readingWithDate("amperage")
    Map v = readingWithDate("voltage")
    Map e = readingWithDate("energy")
    Map row = [
        t  : now(),
        sw : sw.value,
        w  : w.value,
        a  : a.value,
        v  : v.value,
        e  : e.value,
        swAt: sw.at,
        wAt : w.at,
        aAt : a.at,
        vAt : v.at,
        eAt : e.at,
        src : src,     // 1.2.0: on, manual, power, poll or off
        evAt: evAt     // 1.2.0: the power report's own date (an unchanged report leaves wAt where it was)
    ]
    List rows = state.rows ?: []
    rows << row
    state.rows = rows
    if (logEnable) log.debug "Sample: ${row}"
}

void finishCapture(String why) {
    unschedule("poll")
    state.capturing = false
    dropPowerSubscription()
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

    // 1.2.0: source and event_date_ms are appended, so the 1.1.x columns keep their places.
    StringBuilder csv = new StringBuilder("epoch_ms,local_time,seconds_from_start,switch,watts,amps,volts,kwh," +
                                          "switch_date_ms,watts_date_ms,amps_date_ms,volts_date_ms,kwh_date_ms,source,event_date_ms\n")
    rows.each { r ->
        long t = r.t as Long
        csv << "${t},${fmtTime(t)},${round((t - t0) / 1000.0, 1)},${r.sw},${nz(r.w)},${nz(r.a)},${nz(r.v)},${nz(r.e)}," +
               "${nz(r.swAt)},${nz(r.wAt)},${nz(r.aAt)},${nz(r.vAt)},${nz(r.eAt)},${nz(r.src)},${nz(r.evAt)}\n"
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
    // 1.2.0: amps count only when reported after the ON. A row taken at the plug's first power report comes
    // before the amps that the refresh after the ON brings, and would add the previous run's 0 A.
    List amps  = counted.findAll { it.a != null && (onAt == null || (it.aAt != null && (it.aAt as Long) >= onAt)) }.collect { it.a as BigDecimal }
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

    Map steady = steadyPower(counted, onAt, offAt)
    Map wear = null
    try { wear = checkWear(steady, t0) } catch (e) { log.error "Pump Power Profiler: wear check failed: ${e.message}" }

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
        profilerVersion: PROFILER_VERSION,
        steadyWattsAvg : steady ? round(steady.w, 2) : "",
        steadySamples  : steady ? steady.n : "",
        wearMedianWatts: wear?.median != null ? wear.median : "",
        wearDeviationPct: wear?.pct != null ? wear.pct : "",
        profilerRefreshes: (state.refreshes ?: 0) as Integer,
        powerEvents    : (state.powerEvents ?: 0) as Integer
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
 * 1.2.0: the run's average power in its steady phase, from STEADY_SKIP_START_SECS after the ON to
 * STEADY_SKIP_END_SECS before the OFF, over the counted samples taken in it. Null when the run has no
 * known ON or OFF (a manual capture or stop), its steady phase is shorter than WEAR_MIN_STEADY_SECS, or
 * it holds fewer than WEAR_MIN_STEADY_SAMPLES samples.
 */
Map steadyPower(List counted, Long onAt, Long offAt) {
    if (onAt == null || offAt == null) return null
    long from = onAt + STEADY_SKIP_START_SECS * 1000L
    long to = offAt - STEADY_SKIP_END_SECS * 1000L
    if (to - from < WEAR_MIN_STEADY_SECS * 1000L) return null
    List w = counted.findAll { it.w != null && (it.t as Long) >= from && (it.t as Long) <= to }.collect { it.w as BigDecimal }
    if (w.size() < WEAR_MIN_STEADY_SAMPLES) return null
    return [w: w.sum() / w.size(), n: w.size()]
}

/**
 * 1.2.0 wear monitor: the run's steady average against the median of the previous WEAR_MEDIAN_RUNS runs
 * (the run itself is not in it). Two judged runs in a row further from their median than the threshold, in
 * either direction, send one alert; a run within it ends the streak. The first runs only build the baseline.
 * Returns the run as stored in state.wearRuns, or null for a run too short to judge.
 */
Map checkWear(Map steady, long runStart) {
    if (steady == null) return null
    List runs = (state.wearRuns instanceof List) ? new ArrayList(state.wearRuns) : []
    List recent = runs.size() > WEAR_MEDIAN_RUNS ? runs.drop(runs.size() - WEAR_MEDIAN_RUNS) : runs
    BigDecimal w = steady.w as BigDecimal
    BigDecimal median = recent.size() >= WEAR_MIN_REFERENCE_RUNS ? medianOf(recent.collect { it.w as BigDecimal }) : null
    BigDecimal pct = (median != null && median > 0G) ? (w - median) * 100G / median : null
    boolean deviates = pct != null && pct.abs() > wearThreshold()
    Map run = [t: runStart, w: round(w, 3), n: steady.n, median: median != null ? round(median, 3) : null,
               pct: pct != null ? round(pct, 1) : null, deviates: deviates]
    runs << run
    if (runs.size() > WEAR_HISTORY_MAX) runs = runs.drop(runs.size() - WEAR_HISTORY_MAX)
    state.wearRuns = runs
    if (pct != null) {
        int streak = deviates ? ((state.wearStreak ?: 0) as Integer) + 1 : 0
        state.wearStreak = streak
        if (streak == 2) {
            Map previous = runs.take(runs.size() - 1).reverse().find { it.deviates == true }
            sendWearAlert(previous ?: run, run, median)
        }
    }
    return run
}

void sendWearAlert(Map previous, Map run, BigDecimal median) {
    String device = meterDev?.displayName ?: "The device"
    String msg = "Pump wear check: ${device} drew ${fmtW(previous.w)} W and then ${fmtW(run.w)} W in the steady phase of its last two runs, " +
                 "${pctText(previous.pct)} and ${pctText(run.pct)} the median of the runs before (now ${fmtW(median)} W). " +
                 "The alert threshold is ${wearThreshold().stripTrailingZeros().toPlainString()}%."
    state.lastWearAlert = [at: now(), msg: msg]
    log.warn "Pump Power Profiler: ${msg}"
    (wearNotify ?: []).each { d ->
        try { d.deviceNotification(msg) }
        catch (e) { log.error "Pump Power Profiler: the wear alert to ${d} failed: ${e.message}" }
    }
}

BigDecimal medianOf(List values) {
    List v = values.collect { it as BigDecimal }.sort()
    int n = v.size()
    return n % 2 == 1 ? v[(int) (n / 2)] : (v[(int) (n / 2) - 1] + v[(int) (n / 2)]) / 2
}

String pctText(def pct) {
    BigDecimal p = pct as BigDecimal
    return "${p.abs().setScale(1, BigDecimal.ROUND_HALF_UP)}% ${p < 0G ? 'below' : 'above'}"
}

String fmtW(def w) { w == null || w == "" ? "?" : (w as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP).toString() }

/**
 * Add the run's row to <prefix>-summary.csv. 1.0.1 treated ANY failed read as "no file yet" and wrote a
 * fresh file over the old one. Now a listing or read that fails leaves the file alone and keeps the row in
 * state.summaryBacklog, which is written ahead of the next row once a save succeeds.
 *
 * 1.1.1: a new file starts only when neither File Manager's listing nor a read knows the file. 1.1.0 trusted
 * the listing alone, and took a read that returned 0 bytes as an empty file, so either one wrote a header
 * over the whole history. A file known to exist (listed, or returned by a read) is never written over:
 * whatever a read returns is appended to, and a read with no content (null or 0 bytes) keeps the backlog.
 */
void appendSummary(String prefix, Map s) {
    String name = "${prefix}-summary.csv"
    List backlog = (state.summaryBacklog instanceof List) ? new ArrayList(state.summaryBacklog) : []
    backlog << SUMMARY_KEYS.collect { s[it] == null ? "" : s[it] }.join(",")
    if (backlog.size() > SUMMARY_BACKLOG_MAX) backlog = backlog.drop(backlog.size() - SUMMARY_BACKLOG_MAX)

    Boolean present = hubFilePresent(name)
    if (present == null) {
        keepSummaryBacklog(backlog, "File Manager could not be listed, so ${name} was not touched")
        return
    }
    // Read even when the listing leaves the file out: a listing can miss a file that is there.
    byte[] b = null
    try { b = downloadHubFile(name) } catch (e) { log.warn "Pump Power Profiler: reading ${name} failed: ${e.message}" }
    String existing
    if (b != null && b.length > 0) {
        existing = new String(b, "UTF-8")
    } else if (b == null && !present) {
        existing = ""   // not listed and nothing to read: there is no file yet
    } else {
        String what = b == null ? "exists but could not be read" :
            "read as 0 bytes (if it really is empty, delete it in File Manager and the next run starts a new one)"
        keepSummaryBacklog(backlog, "${name} ${what}, so it was not overwritten")
        return
    }

    StringBuilder out = new StringBuilder()
    if (!existing) {
        out << SUMMARY_KEYS.join(",") << "\n"
    } else {
        List lines = existing.split("\n", -1) as List
        // A 1.0.x or 1.1.x header gains the new columns at its end; its rows simply lack them.
        List header = lines[0].trim().split(",", -1) as List
        if (header.size() in OLDER_SUMMARY_KEY_COUNTS && SUMMARY_KEYS.take(header.size()) == header) lines[0] = SUMMARY_KEYS.join(",")
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

/** A capture whose heartbeat stopped: no poll and no power report for several fallback intervals. */
boolean captureStale() {
    Long last = (state.lastPollMs ?: state.startMs) as Long
    long limit = Math.max(120_000L, 3L * fallbackMs())
    return last == null || (now() - last) > limit
}

/** When the device last showed it is alive: its last power report, or the last refresh sent. */
long quietFrom() {
    return ((state.quietFromMs ?: state.lastPollMs ?: state.startMs ?: now()) as Long)
}

long fallbackMs() {
    int s = fallbackSecs != null ? (fallbackSecs as Integer) : DEFAULT_FALLBACK_SECS
    return Math.min(MAX_FALLBACK_SECS, Math.max(MIN_FALLBACK_SECS, s)) * 1000L
}

long tailMs() {
    int s = tailSecs != null ? (tailSecs as Integer) : DEFAULT_TAIL_SECS
    return Math.max(MIN_TAIL_SECS, s) * 1000L
}

int maxSampleCount() {
    return (maxSamples ?: 600) as Integer
}

BigDecimal wearThreshold() {
    BigDecimal v = wearThresholdPct != null ? (wearThresholdPct as BigDecimal) : DEFAULT_WEAR_THRESHOLD_PCT
    return v < 1G ? 1G : v
}

/** One reading's value and the date the hub attached to it (epoch ms, or null), from the same read (1.1.1). */
Map readingWithDate(String attr) {
    def st = null
    try { st = meterDev.currentState(attr, true) } catch (ignored) { st = null }
    if (st == null) return [value: meterDev.currentValue(attr, true), at: null]
    def d = st.date
    Long at = d instanceof Date ? d.time : (d instanceof Number ? d as Long : null)
    return [value: st.value, at: at]
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
