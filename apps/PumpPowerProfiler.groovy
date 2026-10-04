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
 */

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
    }
}

def installed() { initialize() }

def updated() {
    unsubscribe()
    initialize()
    // A settings save (Done) must not end a capture in progress: keep polling.
    if (state.capturing) runIn((sampleSecs ?: 3) as Integer, "poll", [overwrite: true])
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
    if (!s) return "Idle. Waiting for the device to turn on."
    return "Idle. Last run: ${s.start}, ${s.durationSec} s, avg ${s.wattsAvg} W, max ${s.wattsMax} W, avg ${s.ampsAvg} A, ${s.whIntegrated} Wh. File: ${s.file}"
}

void switchHandler(evt) {
    if (evt.value == "on" && !state.capturing) {
        startCapture("switch on")
    } else if (evt.value == "off" && state.capturing && state.offAtMs == null) {
        state.offAtMs = now()
        state.tailLeft = (tailSamples ?: 0) as Integer
        if (logEnable) log.debug "Switch off, taking ${state.tailLeft} tail samples"
    }
}

void appButtonHandler(String btn) {
    if (btn == "btnStart" && !state.capturing) startCapture("manual")
    if (btn == "btnStop" && state.capturing) finishCapture("manual stop")
}

void startCapture(String reason) {
    state.capturing = true
    state.reason = reason
    state.startMs = now()
    state.offAtMs = null
    state.tailLeft = null
    state.rows = []
    state.kwhStart = meterDev.currentValue("energy", true)
    log.info "Capture started (${reason}) on ${meterDev.displayName}"
    recordRow()
    meterDev.refresh()
    runIn((sampleSecs ?: 3) as Integer, "poll", [overwrite: true])
}

void poll() {
    if (!state.capturing) return
    recordRow()
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
    meterDev.refresh()
    runIn((sampleSecs ?: 3) as Integer, "poll", [overwrite: true])
}

void recordRow() {
    Map row = [
        t : now(),
        sw: meterDev.currentValue("switch", true),
        w : meterDev.currentValue("power", true),
        a : meterDev.currentValue("amperage", true),
        v : meterDev.currentValue("voltage", true),
        e : meterDev.currentValue("energy", true)
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
    long tEnd = (state.offAtMs ?: rows[-1].t) as Long
    String stamp = new Date(t0).format("yyyyMMdd-HHmmss", location.timeZone)
    String prefix = (filePrefix ?: "pump-profile").replaceAll("[^A-Za-z0-9_-]", "")
    String fileName = "${prefix}-${stamp}.csv"

    StringBuilder csv = new StringBuilder("epoch_ms,local_time,seconds_from_start,switch,watts,amps,volts,kwh\n")
    rows.each { r ->
        long t = r.t as Long
        csv << "${t},${fmtTime(t)},${round((t - t0) / 1000.0, 1)},${r.sw},${nz(r.w)},${nz(r.a)},${nz(r.v)},${nz(r.e)}\n"
    }

    // Statistics over samples taken while the switch reported on, skipping the
    // first sample (taken before the first refresh returned).
    List onRows = rows.drop(1).findAll { it.sw == "on" }
    List watts = onRows.findAll { it.w != null }.collect { it.w as BigDecimal }
    List amps  = onRows.findAll { it.a != null }.collect { it.a as BigDecimal }
    List volts = onRows.findAll { it.v != null }.collect { it.v as BigDecimal }

    // Energy for this run from the watt samples (trapezoid rule). The plug's own
    // kWh counter only moves in 0.01 kWh steps, too coarse for a short run.
    BigDecimal wh = 0
    for (int i = 1; i < rows.size(); i++) {
        Map p = rows[i - 1]
        Map c = rows[i]
        if (p.w != null && c.w != null) {
            BigDecimal hours = ((c.t as Long) - (p.t as Long)) / 3600000.0
            wh += (((p.w as BigDecimal) + (c.w as BigDecimal)) / 2) * hours
        }
    }

    Map s = [
        start       : fmtTime(t0),
        reason      : state.reason,
        endReason   : why,
        durationSec : round((tEnd - t0) / 1000.0, 1),
        samples     : rows.size(),
        onSamples   : onRows.size(),
        wattsMin    : watts ? round(watts.min(), 2) : "",
        wattsAvg    : watts ? round(watts.sum() / watts.size(), 2) : "",
        wattsMax    : watts ? round(watts.max(), 2) : "",
        ampsMin     : amps ? round(amps.min(), 3) : "",
        ampsAvg     : amps ? round(amps.sum() / amps.size(), 3) : "",
        ampsMax     : amps ? round(amps.max(), 3) : "",
        voltsAvg    : volts ? round(volts.sum() / volts.size(), 1) : "",
        whIntegrated: round(wh, 3),
        kwhMeterStart: nz(state.kwhStart),
        kwhMeterEnd : nz(rows[-1].e),
        file        : fileName
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

void appendSummary(String prefix, Map s) {
    String name = "${prefix}-summary.csv"
    List keys = ["start", "reason", "endReason", "durationSec", "samples", "onSamples",
                 "wattsMin", "wattsAvg", "wattsMax", "ampsMin", "ampsAvg", "ampsMax",
                 "voltsAvg", "whIntegrated", "kwhMeterStart", "kwhMeterEnd", "file"]
    String existing = ""
    try {
        byte[] b = downloadHubFile(name)
        if (b) existing = new String(b, "UTF-8")
    } catch (e) {
        existing = ""
    }
    StringBuilder out = new StringBuilder(existing)
    if (!existing) out << keys.join(",") << "\n"
    out << keys.collect { s[it] }.join(",") << "\n"
    try {
        uploadHubFile(name, out.toString().getBytes("UTF-8"))
    } catch (e) {
        log.error "Could not update ${name}: ${e}"
    }
}

String fmtTime(Long ms) {
    return ms ? new Date(ms).format("yyyy-MM-dd HH:mm:ss", location.timeZone) : ""
}

def round(def val, int places) {
    if (val == null) return ""
    return (val as BigDecimal).setScale(places, BigDecimal.ROUND_HALF_UP)
}

def nz(def v) { v == null ? "" : v }
