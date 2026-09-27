/*
 * WaterGuru Dosing Advisor — Pool (child app)
 *
 * One instance of this child = one WaterGuru pool. It is created and managed by
 * the "WaterGuru Dosing Advisor" parent app ("Add a WaterGuru pool"); it is not
 * installed on its own.
 *
 * It reads a WaterGuru pool device and turns the reading into an actual
 * "add X of chemical Y" recommendation, sent to your notification device(s).
 *
 * It is COMPLEMENTARY to the WaterGuru Integration app (which creates the
 * device this reads). That app already handles change-driven alerts, quiet
 * hours, pause switches, recovery messages and per-metric thresholds — this
 * app deliberately does NOT duplicate any of that. Its only job is the two
 * things the integration cannot do:
 *
 *   1. Free-chlorine dosing that is CYA-aware / SLAM-aware. WaterGuru targets
 *      a fixed ~3 ppm FC regardless of stabilizer; for a stabilized or
 *      recovering pool that is far too low. This app targets FC as a function
 *      of CYA (FC = slamFactor x CYA in SLAM mode; the TFP min/target model
 *      otherwise) and converts the gap into a real volume of your own liquid
 *      chlorine.
 *   2. Concrete dose amounts for pH / TA / CH / CYA — passed through verbatim
 *      from WaterGuru's own product-specific advice (doseAdvice), with generic
 *      fallback formulas when that advice is not present (non-WaterGuru source).
 *
 * All doses are ESTIMATES. Always confirm with your own test kit before adding.
 *
 * Version history
 *   2.3.0 - Expose lastCalcEpochMs beside lastCalc so an external historian can timestamp a
 *           target snapshot at its calculation time rather than at collection time, and
 *           re-render the tank lines in the stored preview after a dose so the human-readable
 *           detail cannot lag the numeric attributes.
 *   2.2.0 - Publish numeric dose, tank, and FC telemetry on the companion tile
 *           device so an external historian such as InfluxDB/Grafana can store
 *           actual pump-started doses and calculate durable usage totals.
 *   2.1.9 - Match the WaterGuru driver's capitalized raw cassette-life
 *           attribute names so checks, days and percent render in the preview.
 *   2.1.8 - Add the exact date/time beside sample age, show WaterGuru cassette
 *           checks/days remaining, and estimate chlorine-tank runway from
 *           recorded app-controlled dose volumes.
 *   2.1.7 - Move the current FC/pH/CYA/TA/CH snapshot from the Source section
 *           into the top preview, immediately above its sample-age line.
 *   2.1.6 - Remove the duplicate remaining-tank paragraph below the top
 *           preview; the preview itself retains the full/remaining line.
 *   2.1.5 - Move the detailed preview to the top of the app page, repeat the
 *           remaining-tank estimate below it, and show the next measurement
 *           and guarded automatic-dosing times.
 *   2.1.4 - Show full and remaining chlorine-tank amounts in every detailed
 *           preview, and regenerate the preview immediately after a refill.
 *   2.1.3 - Add a one-AUTO-dose-per-day guard so extra/manual WaterGuru
 *           measurements cannot cause a second automatic dose that day.
 *   2.1.2 - Restrict AUTO pump starts to a configurable daily time window so
 *           manual morning measurements can update history without dosing.
 *   2.1.1 - Add one daily, user-selected WaterGuru refresh so an evening
 *           measurement is imported promptly instead of waiting for the
 *           integration's next interval poll.
 *   1.0.0 - Initial release (parent/child dosing advisor).
 *   1.1.0 - At-a-glance features: a per-pool dashboard tile (a companion
 *           "WaterGuru Dosing Tile" device with status/recommendation/detail/
 *           tileHtml/lastCalc attributes) and an optional daily summary
 *           notification at a chosen time.
 *   1.2.0 - Show the WaterGuru cassette type (C2/C5) — when the source device's
 *           driver reports it — in the message/tile-detail header and as a badge
 *           on the tile (degrades silently on older drivers). Also redesigns the
 *           dashboard tile as a card: a status-colored header band and a free-
 *           chlorine-vs-target progress bar, theme-robust for light/dark boards.
 *   2.1.0 - Track chlorine-tank inventory for common container sizes, warn at a
 *           configurable low level, and block doses larger than the estimate.
 *   2.0.4 - Send PushOver notices for pump start/stop and arm an independent,
 *           configurable emergency cutoff for every pump start.
 *   2.0.3 - Explicitly support a continuously running circulation pump when no
 *           Hubitat interlock switch exists; approval/auto dosing requires this
 *           confirmation or a live circulation-switch interlock.
 *   2.0.2 - Concise PushOver summary follows each new daily WaterGuru sample;
 *           the scheduled time is a once-per-day fallback if the event is missed.
 *   2.0.1 - Concise scheduled PushOver summary always reports the calculated
 *           chlorine amount and pump runtime, including a zero-dose result.
 *   2.0.0 - Guarded liquid-chlorine pump automation: advisory, approval and
 *           automatic modes; 185 mL/min runtime conversion; freshness, pH,
 *           per-dose, daily-dose and runtime limits; duplicate-sample lock;
 *           circulation interlock; scheduled stop and emergency watchdog.
 *   1.3.0 - Chlorine runway (algae forecast): estimate days until free chlorine
 *           falls below the algae-prevention floor (0.075 x CYA). Learns your
 *           pool's daily FC loss from a rolling history of samples (ignoring
 *           chlorine additions); until a few days accumulate it uses a cover-
 *           aware estimate, or a manual ppm/day override. Shown in the message,
 *           the tile detail, and the tile footer.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

import groovy.transform.Field

def appVersion() { "2.3.0" }

definition(
    name:        "WaterGuru Dosing Advisor Pool",
    namespace:   "chsbusch-dot",
    author:      "Chris Busch",
    parent:      "chsbusch-dot:WaterGuru Dosing Advisor",
    description: "One WaterGuru pool's dosing advisor (child of the WaterGuru Dosing Advisor app).",
    category:    "Convenience",
    iconUrl:     "",
    iconX2Url:   "",
    importUrl:   "https://raw.githubusercontent.com/chsbusch-dot/hubitat-waterguru-dosing-advisor/main/apps/WaterGuru-Dosing-Advisor-Child.groovy"
)

preferences {
    page(name: "mainPage")
}

// ---------------------------------------------------------------------------
// Constants (the published dosing model; documented in the README)
// ---------------------------------------------------------------------------

// Liquid chlorine: fl oz of 12.5% product that raises FC by 1 ppm per 10,000 gal.
@Field static final BigDecimal CL_FLOZ_PER_PPM_PER_10K_AT_12_5 = 10.7G
@Field static final BigDecimal ML_PER_FLOZ = 29.5735G
@Field static final BigDecimal ML_PER_US_GALLON = 3785.411784G
@Field static final BigDecimal DEFAULT_PUMP_RATE_ML_MIN = 185G
// WaterGuru's configured schedule. Hubitat refreshes the integration at the
// user-selected sourceRefreshTime (currently 19:45) after this 19:20 reading.
@Field static final int WATERGURU_MEASUREMENT_HOUR = 19
@Field static final int WATERGURU_MEASUREMENT_MINUTE = 20
@Field static final int DEFAULT_DOSING_CHECK_HOUR = 19
@Field static final int DEFAULT_DOSING_CHECK_MINUTE = 45
// TFP FC/CYA model (SLAM off): FC min and target as a fraction of CYA.
@Field static final BigDecimal TFP_MIN_FACTOR    = 0.075G
@Field static final BigDecimal TFP_TARGET_FACTOR = 0.115G
// Generic fallback formulas (only used when WaterGuru doseAdvice is absent).
@Field static final BigDecimal BAKING_SODA_LB_PER_10PPM_TA_PER_10K = 1.5G
@Field static final BigDecimal CAL_CL_OZ_PER_PPM_CH_PER_10K        = 1.84G
@Field static final BigDecimal CYA_OZ_PER_10PPM_PER_10K            = 13G
// Muriatic acid (31.45%): fl oz that lowers pH 0.1 per 10k gal at TA ~100.
@Field static final BigDecimal MURIATIC_FLOZ_PER_0_1PH_PER_10K_TA100 = 6.4G
@Field static final BigDecimal MURIATIC_BASE_PCT = 31.45G
// Dry acid (sodium bisulfate) baseline strength and volume->weight factor.
@Field static final BigDecimal DRY_ACID_BASE_PCT       = 93.2G
@Field static final BigDecimal DRY_ACID_OZ_PER_MURIATIC_FLOZ = 1.12G

// Chlorine-runway forecast: how many FC samples to keep, and the modeled daily
// FC loss (ppm/day) used until enough measured decay history has accumulated.
// A cover slows UV burn-off, so the modeled loss is scaled down when the source
// device reports one.
@Field static final int        RUNWAY_HISTORY_MAX      = 30
@Field static final int        TANK_DOSE_HISTORY_MAX   = 30
@Field static final BigDecimal FC_LOSS_MODELED_DEFAULT = 3.0G
@Field static final BigDecimal FC_LOSS_COVER_FACTOR    = 0.6G

// ---------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------

def mainPage() {
    dynamicPage(name: "mainPage", title: "WaterGuru Dosing Advisor — pool", uninstall: true, install: true) {

        section("<b>Current dosing preview</b>") {
            paragraph nextCycleHtml()
            if (state.lastPreview) {
                paragraph "<pre style='white-space:pre-wrap'>${state.lastPreview}</pre>"
            } else {
                paragraph "No preview has been calculated yet."
            }
        }

        section("<b>Source</b>") {
            input "sourceDevice", "capability.pHMeasurement",
                title: "WaterGuru pool device (the WaterGuru Integration Driver child device)",
                required: true, multiple: false, submitOnChange: true
            if (sourceDevice) {
                input "refreshSourceDaily", "bool",
                    title: "Refresh WaterGuru once daily after its scheduled measurement",
                    defaultValue: false, submitOnChange: true
                if (refreshSourceDaily == true) {
                    input "sourceRefreshTime", "time",
                        title: "Daily WaterGuru refresh time",
                        required: true
                    paragraph "Choose a time after WaterGuru finishes measuring. A new sample triggers the normal duplicate lock and every dosing safety check."
                }
            }
        }

        section("<b>Free chlorine target (this app's core value-add)</b>") {
            input "slamMode", "bool",
                title: "SLAM mode — target FC as a multiple of CYA (recommended while recovering)",
                defaultValue: true, submitOnChange: true
            if (slamMode != false) {
                paragraph slamPageBanner()
                input "slamFactor", "decimal",
                    title: "SLAM factor (FC target = factor x CYA). TFP SLAM level is 0.40.",
                    defaultValue: 0.40, required: false
            } else {
                paragraph "SLAM off: FC min = ${TFP_MIN_FACTOR} x CYA, target = ${TFP_TARGET_FACTOR} x CYA (TFP model)."
            }
            input "fcTargetOverride", "decimal",
                title: "Manual FC target override (ppm) — blank = compute from CYA above",
                required: false
        }

        section("<b>Chlorine runway (algae forecast)</b>") {
            input "showRunway", "bool",
                title: "Show a chlorine-runway estimate — days until FC drops below the algae floor",
                defaultValue: true, submitOnChange: true
            if (showRunway != false) {
                input "fcLossPerDay", "decimal",
                    title: "Daily FC loss rate (ppm/day) — blank = auto (measured from samples, else estimated)",
                    required: false
                paragraph "The floor is the algae-prevention minimum (${TFP_MIN_FACTOR} × CYA). " +
                          "Runway = (FC − floor) ÷ daily loss. The loss rate is measured from your own " +
                          "samples once a few have accumulated; until then a cover-aware estimate is used. " +
                          runwayStatusLine()
            }
        }

        section("<b>Pool volume &amp; product strengths</b>") {
            input "volumeOverride", "decimal",
                title: "Pool volume (gallons)" + hint("poolVolume"), required: false
            input "chlorinePctOverride", "decimal",
                title: "Liquid chlorine strength %" + hint("chlorineProductPct", "12.5"), required: false
        }

        section("<b>Liquid-chlorine tank inventory</b>") {
            input "chlorineTankGallons", "enum",
                title: "Chlorine container capacity (US gallons)",
                options: ["1":"1 gal", "2.5":"2.5 gal", "5":"5 gal", "10":"10 gal", "15":"15 gal", "30":"30 gal", "55":"55 gal"],
                defaultValue: "1", required: true, submitOnChange: true
            input "tankLowPercent", "decimal",
                title: "PushOver low-tank warning at remaining percent",
                defaultValue: 20, required: true
            paragraph tankStatusHtml()
        }

        section("<b>Automated liquid-chlorine dosing</b>") {
            input "dosingMode", "enum",
                title: "Dosing mode",
                options: ["ADVISORY", "APPROVAL", "AUTO"],
                defaultValue: "ADVISORY", required: true, submitOnChange: true
            paragraph dosingModeHelp()

            input "pumpSwitch", "capability.switch",
                title: "Dedicated chlorine pump switch",
                required: false, multiple: false, submitOnChange: true
            input "pumpRateMlPerMin", "decimal",
                title: "Pump delivery rate (mL/min)",
                defaultValue: 185, required: true

            input "circulationSwitch", "capability.switch",
                title: "Pool circulation/filter switch (recommended interlock)",
                required: false, multiple: false
            if (circulationSwitch) {
                input "requireCirculationOn", "bool",
                    title: "Require circulation/filter switch to already be on",
                    defaultValue: true
            } else {
                input "circulationAlwaysOn", "bool",
                    title: "I confirm the circulation/filter pump runs continuously (24/7)",
                    defaultValue: false
                paragraph "Select a circulation switch if one becomes available. APPROVAL/AUTO dosing is blocked unless a switch reports on or continuous circulation is explicitly confirmed."
            }

            input "doseOptionalTopUps", "bool",
                title: "Allow automatic dosing when FC is above the minimum but below target",
                defaultValue: false

            input "limitAutoDoseWindow", "bool",
                title: "Only allow AUTO dosing during an evening time window",
                defaultValue: false, submitOnChange: true
            if (limitAutoDoseWindow == true) {
                input "autoDoseWindowStart", "time",
                    title: "AUTO dosing window starts",
                    required: true
                input "autoDoseWindowEnd", "time",
                    title: "AUTO dosing window ends",
                    required: true
                paragraph "New samples outside this window are recorded but cannot start the pump. APPROVAL mode remains available for an intentional dose at another time."
            }
            input "oneAutoDosePerDay", "bool",
                title: "Limit AUTO mode to one completed dose per calendar day",
                defaultValue: true

            paragraph "<b>Safety limits</b> — every limit must pass before the pump can start. A blocked dose is logged and notified."
            input "maxSampleAgeHours", "decimal", title: "Maximum WaterGuru sample age (hours)", defaultValue: 18, required: true
            input "minDoseMl", "decimal", title: "Minimum dose to run (mL)", defaultValue: 50, required: true
            input "maxSingleDoseMl", "decimal", title: "Maximum single dose (mL)", defaultValue: 3000, required: true
            input "maxDailyDoseMl", "decimal", title: "Maximum total dose per day (mL)", defaultValue: 3500, required: true
            input "maxPumpRunMinutes", "decimal", title: "Absolute maximum pump runtime (minutes)", defaultValue: 20, required: true
            input "minSafePh", "decimal", title: "Block dosing below pH", defaultValue: 6.8, required: true
            input "maxSafePh", "decimal", title: "Block dosing above pH", defaultValue: 8.2, required: true
            input "watchdogAnyPumpRun", "bool",
                title: "Arm an independent emergency cutoff for every pump run",
                defaultValue: true, submitOnChange: true
            if (watchdogAnyPumpRun != false) {
                input "failsafePumpRunMinutes", "decimal",
                    title: "Independent emergency cutoff after this many minutes",
                    defaultValue: 20, required: true
            }

            paragraph dosingStatusHtml()
        }

        section("<b>WaterGuru advice pass-through (pH / TA / CH / CYA)</b>") {
            input "useWgAdvice", "bool",
                title: "Use WaterGuru's own dose advice for pH / TA / CH / CYA",
                defaultValue: true, submitOnChange: true
            if (useWgAdvice != false) {
                input "wgAdviceIncludeChlorine", "bool",
                    title: "Also include WaterGuru's chlorine advice (off = we compute FC, WaterGuru covers the rest)",
                    defaultValue: false
            } else {
                paragraph "Generic fallback formulas will be used for pH / TA / CH / CYA. Set the acid type and target overrides below."
                input "acidTypeOverride", "enum",
                    title: "Acid type for pH/TA down" + hint("acidType"),
                    options: ["MURIATIC", "BISULFATE"], required: false
                input "muriaticPctOverride", "decimal",
                    title: "Muriatic acid strength %" + hint("acidMuriaticPct", "31.45"), required: false
                input "bisulfatePctOverride", "decimal",
                    title: "Dry acid (sodium bisulfate) strength %" + hint("acidBisulfatePct", "93.2"), required: false
            }
        }

        section("<b>Target overrides</b>") {
            input "phTargetOverride",  "decimal", title: "pH target"  + hint("pHTarget", "7.5"),  required: false
            input "taTargetOverride",  "decimal", title: "Total alkalinity target ppm" + hint("totalAlkalinityTarget", "80"),  required: false
            input "cyaTargetOverride", "decimal", title: "CYA target ppm" + hint("cyanuricAcidTarget", "40"), required: false
            input "chTargetOverride",  "decimal", title: "Calcium hardness target ppm" + hint("calciumHardnessTarget", "300"), required: false
        }

        section("<b>Delivery</b>") {
            input "notifyDevices", "capability.notification",
                title: "Notification device(s) to send the recommendation to",
                required: false, multiple: true
            input "autoRun", "bool",
                title: "Notify automatically on each new WaterGuru sample",
                defaultValue: true
            input "dailyDigest", "bool",
                title: "Send a concise daily chlorine amount + pump runtime summary",
                defaultValue: false, submitOnChange: true
            if (dailyDigest == true) {
                input "digestTime", "time",
                    title: "Fallback summary time (only if today's sample did not trigger one)",
                    required: true
            }
        }

        section("<b>Dashboard tile</b>") {
            input "createTile", "bool",
                title: "Create/maintain a dashboard tile device for this pool",
                defaultValue: true, submitOnChange: true
            if (createTile != false) {
                def tdev = getTileDevice()
                if (tdev) {
                    paragraph "Tile device: <b>${tdev.displayName}</b> — current status " +
                              "<b>${tdev.currentValue('status') ?: '—'}</b>. Add it to a dashboard as an " +
                              "<b>Attribute</b> tile bound to <code>recommendation</code> (one-liner), " +
                              "<code>tileHtml</code> (formatted), or <code>status</code> " +
                              "(GREEN / YELLOW / RED, for coloring)."
                } else {
                    paragraph "A device named \"Dosing Tile: …\" is created for this pool when you save. " +
                              "It needs the <b>WaterGuru Dosing Tile</b> driver installed under " +
                              "<i>Drivers Code</i> first."
                }
            }
        }

        section("<b>Run now</b>") {
            input name: "btnCalcNow", type: "button", title: "Calculate &amp; send now"
            input name: "btnPreview", type: "button", title: "Preview (log only)"
            input name: "btnDailySummaryNow", type: "button", title: "Send concise daily summary now"
            input name: "btnTankFull", type: "button", title: "Mark chlorine tank full / reset inventory"
            if (dosingMode == "APPROVAL" && state.pendingDose) {
                input name: "btnRunPending", type: "button", title: "Run the pending chlorine dose now"
            }
            if (pumpSwitch) {
                input name: "btnStopPump", type: "button", title: "STOP chlorine pump now"
            }
        }

        section("<b>Name</b>") {
            label title: "Name for this pool advisor (shown in the parent app's list)", required: false
        }

        section {
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: true
            paragraph "<small>v${appVersion()} — doses are estimates; always confirm with your own test kit before adding chemicals.</small>"
        }
    }
}

/**
 * A field hint that shows the current live value read from the source device,
 * so the user can see what "blank" will use without pinning it. Falls back to a
 * documented default when the device does not report the attribute.
 */
private String hint(String attr, String fallback = null) {
    def v = sourceDevice?.currentValue(attr)
    if (v != null) return " — blank uses WaterGuru's value (currently ${v})"
    if (fallback)  return " — blank uses WaterGuru's value (fallback ${fallback})"
    return " — blank uses WaterGuru's value"
}

/** Loud red banner shown on the config page whenever SLAM mode is on. */
private String slamPageBanner() {
    def cyaNow = sourceDevice?.currentValue("cyanuricAcid")
    BigDecimal fac = numSet(slamFactor) ? (slamFactor as BigDecimal) : 0.40G
    String tgt = ""
    if (!numSet(fcTargetOverride) && cyaNow != null) {
        tgt = " Current target &asymp; ${n1((cyaNow as BigDecimal) * fac)} ppm (${n2(fac)} &times; CYA ${n0(cyaNow as BigDecimal)})."
    }
    return "<div style='background:#c0392b;color:#ffffff;padding:10px;border-radius:6px;font-weight:bold'>" +
           "&#128680; SLAM MODE IS ON &mdash; free-chlorine target is deliberately elevated to clear algae.${tgt} " +
           "Switch it off once the pool is clear and holds chlorine overnight.</div>"
}

private String dosingModeHelp() {
    switch ((dosingMode ?: "ADVISORY").toString()) {
        case "AUTO":
            return "<b>AUTO:</b> a new, valid WaterGuru sample may start the pump after every safety check passes. Daily summaries and manual calculations never start it."
        case "APPROVAL":
            return "<b>APPROVAL:</b> a valid new sample queues a dose. Open this page and press <i>Run the pending chlorine dose now</i>."
        default:
            return "<b>ADVISORY:</b> calculate and notify only. The pump will never be started by this app."
    }
}

private String dosingStatusHtml() {
    String sw = pumpSwitch ? (pumpSwitch.currentValue("switch") ?: "unknown") : "not selected"
    String active = state.activeDose ? "RUNNING ${state.activeDose.ml} mL; scheduled stop ${state.activeDose.stopAt}" : "idle"
    String pending = state.pendingDose ? "${state.pendingDose.ml} mL / ${formatDuration((state.pendingDose.seconds ?: 0) as Integer)} from sample ${state.pendingDose.sample}" : "none"
    String last = state.lastDose ? "${state.lastDose.ml} mL on ${new Date((state.lastDose.time ?: 0) as Long)}" : "none"
    String circulation = circulationSwitch ? "${circulationSwitch.displayName} (${circulationSwitch.currentValue('switch') ?: 'unknown'})" :
                         (circulationAlwaysOn == true ? "confirmed continuous (24/7)" : "not confirmed")
    return "Pump: <b>${pumpSwitch?.displayName ?: 'not selected'}</b> (${sw}) · controller: <b>${active}</b><br>" +
           "Circulation: <b>${circulation}</b><br>" +
           "Pending dose: <b>${pending}</b><br>Last started dose: <b>${last}</b>"
}

private String tankStatusHtml() {
    BigDecimal cap = tankCapacityMl()
    BigDecimal remaining = tankRemainingMl()
    if (cap == null) return "Select a chlorine container size."
    if (remaining == null) {
        return "Tank inventory is <b>not initialized</b>. Press <i>Mark chlorine tank full / reset inventory</i> after confirming the container is full. AUTO dosing will remain blocked until then."
    }
    BigDecimal pct = cap > 0 ? (remaining * 100G / cap) : 0G
    return "Estimated remaining: <b>${n0(remaining)} mL / ${n2(remaining / ML_PER_US_GALLON)} gal (${n0(pct)}%)</b>. " +
           "This estimate subtracts pump-planned volume; reset it whenever the container is replaced or refilled."
}

private String nextCycleHtml() {
    Date measurement = nextDailyTime(null, WATERGURU_MEASUREMENT_HOUR, WATERGURU_MEASUREMENT_MINUTE)
    Date dosing = nextDailyTime(sourceRefreshTime, DEFAULT_DOSING_CHECK_HOUR, DEFAULT_DOSING_CHECK_MINUTE)
    TimeZone tz = location?.timeZone ?: TimeZone.getDefault()
    String measurementText = measurement.format("EEE, MMM d 'at' h:mm a", tz)
    String dosingText = dosing.format("EEE, MMM d 'at' h:mm a", tz)
    return "Next WaterGuru measurement: <b>${measurementText}</b><br>" +
           "Next dosing time: <b>${dosingText}</b> (only if the new reading needs chlorine and every safety check passes)."
}

private Date nextDailyTime(def configuredTime, int fallbackHour, int fallbackMinute) {
    TimeZone tz = location?.timeZone ?: TimeZone.getDefault()
    Date scheduled = null
    if (configuredTime) {
        try { scheduled = timeToday(configuredTime, tz) }
        catch (ignored) { scheduled = null }
    }
    Calendar cal = Calendar.getInstance(tz)
    if (scheduled) {
        cal.setTime(scheduled)
    } else {
        cal.setTime(new Date())
        cal.set(Calendar.HOUR_OF_DAY, fallbackHour)
        cal.set(Calendar.MINUTE, fallbackMinute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }
    if (cal.timeInMillis <= now()) cal.add(Calendar.DATE, 1)
    return cal.time
}

private String currentReadingsSummary(boolean includeSampleTime = true) {
    def d = sourceDevice
    if (!d) return ""
    def parts = []
    def add = { String label, String attr, String unit ->
        def v = d.currentValue(attr)
        if (v != null) parts << "${label} ${v}${unit ?: ''}"
    }
    add("FC", "freeChlorine", " ppm")
    add("pH", "pH", "")
    add("CYA", "cyanuricAcid", " ppm")
    add("TA", "totalAlkalinity", " ppm")
    add("CH", "calciumHardness", " ppm")
    def sampled = d.currentValue("LastMeasurementHuman") ?: d.currentValue("LastMeasurement")
    def s = parts ? parts.join(" · ") : "no readings yet"
    return "Current: ${s}${includeSampleTime && sampled ? "  (sampled ${sampled})" : ''}"
}

private String sampleTimestampText() {
    Long epoch = toEpochMs(attrRaw("LastMeasurement"))
    if (epoch == null) return null
    TimeZone tz = location?.timeZone ?: TimeZone.getDefault()
    return new Date(epoch).format("MMM d, yyyy h:mm a z", tz)
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

def installed() { initialize() }

def updated() {
    safeStopPump("configuration changed")
    unsubscribe()
    initialize()
}

def uninstalled() {
    safeStopPump("app removed")
    unsubscribe()
    unschedule()
    removeTileDevice()
}

def initialize() {
    unschedule()   // clear previous digest, sample-delay, dose and watchdog jobs

    // Give the child a meaningful name in the parent's list if unnamed.
    if (sourceDevice && (!app.label || app.label == "WaterGuru Dosing Advisor Pool")) {
        app.updateLabel("Dosing: ${sourceDevice.displayName}")
    }

    // Companion dashboard-tile device (created/removed per the toggle).
    ensureTileDevice()

    if (sourceDevice) {
        // New WaterGuru sample => LastMeasurement (a DATE attribute) changes.
        // Always subscribe when dosing is enabled, even if advice notifications are disabled.
        if (autoRun != false || dailyDigest == true || (dosingMode ?: "ADVISORY") != "ADVISORY") {
            subscribe(sourceDevice, "LastMeasurement", "onNewSample")
            logDebug "Subscribed to new-sample events on ${sourceDevice.displayName}"
        }
    }
    if (pumpSwitch) {
        subscribe(pumpSwitch, "switch", "pumpSwitchHandler")
    }

    if (dailyDigest == true && digestTime) {
        // A time-of-day input schedules a daily recurring job at that clock time.
        schedule(digestTime, "dailyDigestHandler")
        logDebug "Scheduled daily summary at ${digestTime}"
    }
    if (refreshSourceDaily == true && sourceRefreshTime) {
        if (sourceDevice?.hasCommand("refresh")) {
            schedule(sourceRefreshTime, "refreshWaterGuruSource")
            logDebug "Scheduled daily WaterGuru refresh at ${sourceRefreshTime}"
        } else {
            log.warn "WaterGuru Dosing Advisor: selected source device has no refresh command"
        }
    }

    // Populate the tile right away so it is never blank after a save.
    refreshTile()
}

def refreshWaterGuruSource() {
    if (!sourceDevice) {
        log.warn "WaterGuru Dosing Advisor: scheduled refresh skipped; no source device is configured"
        return
    }
    if (!sourceDevice.hasCommand("refresh")) {
        log.warn "WaterGuru Dosing Advisor: scheduled refresh skipped; ${sourceDevice.displayName} has no refresh command"
        return
    }
    log.info "WaterGuru Dosing Advisor: refreshing ${sourceDevice.displayName} after its scheduled measurement"
    sourceDevice.refresh()
}

def onNewSample(evt) {
    logDebug "New WaterGuru sample (${evt?.value}); waiting briefly for all attributes"
    recordFcSample(evt)
    String key = evt?.value?.toString() ?: attrRaw("LastMeasurement") ?: now().toString()
    runIn(20, "processNewSample", [data: [sampleKey: key], overwrite: true])
}

def processNewSample(Map data = [:]) {
    String key = attrRaw("LastMeasurement") ?: data?.sampleKey?.toString()
    if (key && state.lastProcessedSample == key) {
        logDebug "Ignoring duplicate WaterGuru sample ${key}"
        return
    }
    state.lastProcessedSample = key
    if (dailyDigest == true && autoDoseWindowOpen()) {
        sendDailyChlorineSummary(false, "new WaterGuru sample")
    } else if (dailyDigest == true) {
        logDebug "Skipping new-sample chlorine summary outside the AUTO dosing window"
    }
    runAndDeliver(autoRun != false, true, "new WaterGuru sample")
}

def dailyDigestHandler() {
    sendDailyChlorineSummary(false, "scheduled fallback")
}

private void sendDailyChlorineSummary(boolean force = false, String trigger = "daily") {
    String today = new Date().format("yyyy-MM-dd", location.timeZone)
    if (!force && state.lastDailySummaryDate == today) {
        logDebug "Skipping ${trigger} chlorine summary; already sent for ${today}"
        return
    }
    logDebug "Daily chlorine summary firing (${trigger})"
    if (!sourceDevice) {
        sendNotice("Daily pool chlorine: no WaterGuru source device is configured.")
        state.lastDailySummaryDate = today
        return
    }
    Map result = computeAdvice()
    state.lastPreview = result.text
    updateTileDevice(result)
    String msg = dailyChlorineSummary(result)
    log.info "WaterGuru Dosing Advisor: ${msg}"
    sendNotice(msg)
    state.lastDailySummaryDate = today
}

private String dailyChlorineSummary(Map result) {
    String sampled = result?.sampled ? " Sample: ${result.sampled}." : ""
    String readings = "FC ${n1(result?.fcVal)} ppm; target ${n1(result?.fcTarget)} ppm; pH ${n2(result?.pH)}."
    String tank = tankSummaryPlain()
    if (result?.doseMl != null && result?.runSeconds != null) {
        BigDecimal rate = firstNum(pumpRateMlPerMin, DEFAULT_PUMP_RATE_ML_MIN)
        String kind = result.doseOptional == true ? "optional top-up" : "dose"
        return "Daily pool chlorine: add ${n0(result.doseMl)} mL of ${n1(firstNum(chlorinePctOverride, attrNum('chlorineProductPct'), 12.5G))}% liquid chlorine; run ${pumpSwitch?.displayName ?: 'the pump'} for ${formatDuration(result.runSeconds as Integer)} at ${n1(rate)} mL/min (${kind}). ${readings} ${tank}${sampled}"
    }
    return "Daily pool chlorine: add 0 mL; pump runtime 0 seconds. ${readings} ${tank}${sampled}"
}

void appButtonHandler(String btn) {
    switch (btn) {
        case "btnCalcNow":   runAndDeliver(true, false, "manual calculation");  break
        case "btnPreview":   runAndDeliver(false, false, "preview"); break
        case "btnDailySummaryNow": sendDailyChlorineSummary(true, "manual test"); break
        case "btnTankFull": resetTankFull(); break
        case "btnRunPending": runPendingDose(); break
        case "btnStopPump":  safeStopPump("STOP button pressed", true); break
    }
}

// ---------------------------------------------------------------------------
// Orchestration
// ---------------------------------------------------------------------------

private Map runAndDeliver(boolean send, boolean allowDose = false, String trigger = "manual") {
    if (!sourceDevice) { log.warn "WaterGuru Dosing Advisor: no source device selected"; return null }
    Map result = computeAdvice()
    String text = result.text
    state.lastPreview = text

    // Refresh the dashboard tile on every calculation (send or preview).
    updateTileDevice(result)

    if (allowDose) handleDoseDecision(result, trigger)

    if (!send) {
        log.info "WaterGuru Dosing Advisor (preview):\n${text}"
        return result
    }

    log.info "WaterGuru Dosing Advisor:\n${text}"
    sendNotice(text)
    return result
}

// ---------------------------------------------------------------------------
// Dosing engine
// ---------------------------------------------------------------------------

private Map computeAdvice() {
    def d = sourceDevice
    def label = d?.label ?: d?.displayName ?: "pool"

    // ---- Resolve configuration (override -> device attribute -> fallback) --
    BigDecimal volume       = firstNum(volumeOverride, attrNum("poolVolume"))
    BigDecimal chlorinePct  = firstNum(chlorinePctOverride, attrNum("chlorineProductPct"), 12.5G)
    BigDecimal phTarget     = firstNum(phTargetOverride,  attrNum("pHTarget"),               7.5G)
    BigDecimal taTarget     = firstNum(taTargetOverride,  attrNum("totalAlkalinityTarget"),  80G)
    BigDecimal cyaTarget    = firstNum(cyaTargetOverride, attrNum("cyanuricAcidTarget"),     40G)
    BigDecimal chTarget     = firstNum(chTargetOverride,  attrNum("calciumHardnessTarget"),  300G)

    // ---- Readings ----------------------------------------------------------
    BigDecimal fc  = attrNum("freeChlorine")
    BigDecimal ph  = attrNum("pH")
    BigDecimal cya = attrNum("cyanuricAcid")
    BigDecimal ta  = attrNum("totalAlkalinity")
    BigDecimal ch  = attrNum("calciumHardness")

    // Chlorine-runway forecast (days until FC hits the CYA-based algae floor).
    Map runway = (showRunway != false) ? computeRunway(fc, cya) : null

    def out = []
    def warnings = []
    def chips = []
    boolean anyAction = false
    boolean red = false      // a chemical is needed, or SLAM is active
    boolean yellow = false   // optional / advisory / uncertain

    out << "🌊 WaterGuru Dosing Advisor — ${label}"
    out << currentReadingsSummary(false)
    def sampled = attrRaw("LastMeasurementHuman") ?: attrRaw("LastMeasurement")
    if (sampled) {
        String exactSample = sampleTimestampText()
        out << "Sampled: ${sampled}${exactSample ? ' · ' + exactSample : ''}"
    }
    def cassette = cassetteText()
    if (cassette) out << "Cassette: ${cassette}"

    if (!volume || volume <= 0) {
        out << ""
        out << "⚠ Pool volume unknown. Set a volume override or point at a device that reports poolVolume."
        return [text: out.join("\n"), anyAction: false, status: "YELLOW",
                headline: "Set pool volume", fcSummary: null, sampled: sampled, label: label]
    }

    // ---- 1) FREE CHLORINE — computed here, SLAM/CYA aware -------------------
    def fcResult = computeFc(fc, cya, volume, chlorinePct, warnings)
    if (fcResult.banner) {
        out << ""
        out << fcResult.banner
    }
    out << ""
    out << "FREE CHLORINE (computed)${fcResult.slam ? ' — 🚨 SLAM MODE' : ''}"
    out.addAll(fcResult.lines)
    anyAction = anyAction || fcResult.action
    red    = red    || fcResult.required || fcResult.slam
    yellow = yellow || fcResult.optional || (fc == null)
    if (fcResult.chip) chips << fcResult.chip
    else if (fcResult.slam) chips << "SLAM: hold FC level"
    out << ""

    // ---- 2) pH / TA / CH / CYA --------------------------------------------
    String doseAdvice = attrRaw("doseAdvice")
    boolean haveWg = (useWgAdvice != false) && doseAdvice != null

    if (haveWg) {
        out << "WATERGURU RECOMMENDATIONS (pH / TA / CH / CYA)"
        def kept = filterWgAdvice(doseAdvice)
        if (kept) {
            kept.each { out << "  • ${it}" }
            anyAction = true
            red = true
            kept.each { chips << shortenWg(it) }
        } else {
            out << "  • None — WaterGuru reports no pH / TA / CH / CYA adjustment needed."
        }
    } else {
        out << "pH / TA / CH / CYA (generic estimates)"
        def gen = computeGeneric(ph, ta, ch, cya, phTarget, taTarget, chTarget, cyaTarget, volume, warnings)
        if (gen.lines) {
            out.addAll(gen.lines)
            anyAction = anyAction || gen.action
            red    = red    || gen.additions
            yellow = yellow || gen.advisories
            chips.addAll(gen.chips)
        } else {
            out << "  • All within target (or readings unavailable) — nothing to add."
        }
    }

    // ---- Footer ------------------------------------------------------------
    out << ""
    out << tankInventoryLine()
    Map tankRunway = computeTankRunway()
    if (tankRunway) out << tankRunwayLine(tankRunway)
    out << ""
    if (runway) { out << runwayLine(runway); out << "" }
    warnings.unique().each { out << "⚠ ${it}" }
    out << "⚠ Estimates only — confirm with your own test kit before adding chemicals."
    if (!anyAction) out << "✅ No chemical additions indicated right now."

    // ---- At-a-glance status + one-liner (for the tile & digest) ------------
    if (warnings) yellow = true
    String status = red ? "RED" : (yellow ? "YELLOW" : "GREEN")
    String headline
    if (chips) headline = chips.join(" · ")
    else if (status == "GREEN") headline = "All in range"
    else headline = "Review details"

    return [text: out.join("\n"), anyAction: anyAction, status: status,
            headline: headline, fcSummary: fcResult.fcSummary,
            fcVal: fcResult.fcVal, fcTarget: fcResult.target,
            doseMl: fcResult.doseMl, runSeconds: fcResult.runSeconds,
            doseRequired: fcResult.required, doseOptional: fcResult.optional,
            pH: ph, sampleKey: attrRaw("LastMeasurement"),
            runway: runway, sampled: sampled, label: label]
}

/** Free-chlorine dose: SLAM/CYA-aware target, converted to liquid chlorine. */
private Map computeFc(BigDecimal fc, BigDecimal cya, BigDecimal volume, BigDecimal chlorinePct, List warnings) {
    def lines = []
    boolean action = false
    boolean slam = false
    boolean required = false   // a dose that should be added now (drives RED)
    boolean optional = false   // above the TFP minimum but below target (drives YELLOW)
    String chip = null         // short fragment for the one-liner, e.g. "Add 4.8 gal chlorine"
    String banner = null
    BigDecimal doseMl = null
    Integer runSeconds = null

    // Determine the FC target.
    BigDecimal target
    BigDecimal minFc = null
    String basis
    BigDecimal manualTarget = numSet(fcTargetOverride) ? toBD(fcTargetOverride) : null
    if (manualTarget != null && manualTarget > 0 && manualTarget <= 50G) {
        target = manualTarget
        basis = "manual target ${n1(target)} ppm"
    } else if (cya != null && cya > 0) {
        if (manualTarget != null) warnings << "Ignored invalid manual FC target ${manualTarget}; target must be greater than 0 and at most 50 ppm."
        if (slamMode != false) {
            BigDecimal factor = numSet(slamFactor) ? (slamFactor as BigDecimal) : 0.40G
            target = (cya * factor)
            basis = "SLAM ${n2(factor)} x CYA ${n0(cya)}"
            slam = true
            banner = "🚨 SLAM MODE ACTIVE — free chlorine is held high on purpose: target ${n1(target)} ppm (${n2(factor)} × CYA ${n0(cya)}). Maintain it and retest often; turn SLAM off once the pool is clear and holds chlorine overnight."
        } else {
            minFc  = cya * TFP_MIN_FACTOR
            target = cya * TFP_TARGET_FACTOR
            basis = "TFP ${TFP_TARGET_FACTOR} x CYA ${n0(cya)}; min ${n1(minFc)}"
        }
    } else {
        // No CYA and no manual target — fall back to the device's own FC target.
        target = attrNum("freeChlorineTarget") ?: 3G
        basis = "CYA unavailable — using WaterGuru FC target ${n1(target)} ppm (not CYA-aware)"
        warnings << "CYA reading unavailable; FC target is not CYA-aware. Add a manual FC target or check the sensor."
    }

    if (fc == null) {
        lines << "  • Free chlorine reading unavailable from the source device."
        lines << "  • Would target ${n1(target)} ppm (${basis})."
        return [lines: lines, action: false, slam: slam, banner: banner,
                required: false, optional: false, chip: null, fcVal: null, target: target,
                doseMl: null, runSeconds: null,
                fcSummary: "FC — → ${n1(target)} ppm"]
    }

    lines << "  FC ${n1(fc)} ppm  →  target ${n1(target)} ppm  (${basis})"

    if (fc < target) {
        BigDecimal ppmGap = target - fc
        BigDecimal pctFactor = (chlorinePct && chlorinePct > 0) ? (12.5G / chlorinePct) : 1G
        BigDecimal floz = ppmGap * (volume / 10000G) * CL_FLOZ_PER_PPM_PER_10K_AT_12_5 * pctFactor
        optional = (minFc != null && fc >= minFc)   // non-SLAM: above min but below target
        required = !optional
        String verb = optional ? "Optional top-up" : "Add"
        doseMl = floz * ML_PER_FLOZ
        BigDecimal pumpRate = firstNum(pumpRateMlPerMin, DEFAULT_PUMP_RATE_ML_MIN)
        if (pumpRate != null && pumpRate > 0) {
            runSeconds = Math.max(1, Math.ceil((doseMl / pumpRate * 60G).doubleValue()) as Integer)
        }
        lines << "  ➕ ${verb}: ${flozUnits(floz)} / ${n0(doseMl)} mL of liquid chlorine (${n1(chlorinePct)}%) to raise FC ${n1(ppmGap)} ppm to target."
        if (runSeconds != null) lines << "  ⏱ Pump plan: ${formatDuration(runSeconds)} at ${n0(pumpRate)} mL/min."
        chip = "${optional ? 'Top up' : 'Add'} ${shortVol(floz)} chlorine"
        action = true
        if (ppmGap > 8G) warnings << "Large chlorine addition (+${n1(ppmGap)} ppm) — add in stages and retest between doses."
        if (floz > 640G) warnings << "Very large chlorine dose (${n0(floz)} fl oz) — double-check pool volume and the FC reading."
    } else {
        if (slamMode != false && !numSet(fcTargetOverride)) {
            lines << "  ✔ FC ${n1(fc)} ≥ SLAM target ${n1(target)} ppm — maintain SLAM level, retest, do not add."
        } else {
            lines << "  ✔ FC ${n1(fc)} ≥ target ${n1(target)} ppm — hold, no chlorine needed."
        }
    }
    return [lines: lines, action: action, slam: slam, banner: banner,
            required: required, optional: optional, chip: chip, fcVal: fc, target: target,
            doseMl: doseMl, runSeconds: runSeconds,
            fcSummary: "FC ${n1(fc)} → ${n1(target)} ppm"]
}

/** Keep WaterGuru advice lines; drop chlorine lines unless the user opts in. */
private List filterWgAdvice(String doseAdvice) {
    if (!doseAdvice || doseAdvice.trim().equalsIgnoreCase("None")) return []
    def lines = doseAdvice.split("\n").collect { it.trim() }.findAll { it }
    // Drop WaterGuru's non-actionable placeholder lines (e.g. "Measure again to
    // see the advice") — they carry no dose and just add noise to tile/message.
    def skipPhrases = ["measure again", "see the advice"]
    lines = lines.findAll { line -> def ll = line.toLowerCase(); !skipPhrases.any { ll.contains(it) } }
    if (wgAdviceIncludeChlorine == true) return lines.unique()
    // We compute FC ourselves — strip WaterGuru's chlorine/shock lines so the
    // user does not get two conflicting chlorine recommendations.
    def clWords = ["chlorine", "bleach", "cal hypo", "cal-hypo", "calcium hypochlorite", "liquid chlorine", "shock", "dichlor", "trichlor"]
    return lines.findAll { line ->
        def l = line.toLowerCase()
        !clWords.any { l.contains(it) }
    }.unique()
}

/** Generic fallback dosing when WaterGuru doseAdvice is not available. */
private Map computeGeneric(BigDecimal ph, BigDecimal ta, BigDecimal ch, BigDecimal cya,
                           BigDecimal phTarget, BigDecimal taTarget, BigDecimal chTarget, BigDecimal cyaTarget,
                           BigDecimal volume, List warnings) {
    def lines = []
    def chips = []
    boolean additions  = false   // a chemical should be added (drives RED)
    boolean advisories = false   // in-water advice with no additive / drain (drives YELLOW)
    BigDecimal v10k = volume / 10000G

    // pH / TA down via acid (also used when pH high).
    if (ph != null && ph > phTarget) {
        BigDecimal steps = (ph - phTarget) / 0.1G
        BigDecimal taFactor = (ta != null && ta > 0) ? (ta / 100G) : 1G
        BigDecimal muriaticFloz = steps * MURIATIC_FLOZ_PER_0_1PH_PER_10K_TA100 * taFactor * v10k
        String acidType = (acidTypeOverride ?: attrRaw("acidType") ?: "MURIATIC").toString().toUpperCase()
        if (acidType.contains("BISULFATE")) {
            BigDecimal pct = firstNum(bisulfatePctOverride, attrNum("acidBisulfatePct"), DRY_ACID_BASE_PCT)
            BigDecimal oz = muriaticFloz * DRY_ACID_OZ_PER_MURIATIC_FLOZ * (DRY_ACID_BASE_PCT / pct)
            lines << "  ➕ pH ${n1(ph)} → target ${n1(phTarget)}: add ~${n1(oz)} oz dry acid / sodium bisulfate (${n1(pct)}%)."
            chips << "${chipOz(oz)} dry acid"
        } else {
            BigDecimal pct = firstNum(muriaticPctOverride, attrNum("acidMuriaticPct"), MURIATIC_BASE_PCT)
            BigDecimal floz = muriaticFloz * (MURIATIC_BASE_PCT / pct)
            lines << "  ➕ pH ${n1(ph)} → target ${n1(phTarget)}: add ~${flozUnits(floz)} of muriatic acid (${n1(pct)}%)."
            chips << "${shortVol(floz)} muriatic acid"
        }
        additions = true
    } else if (ph != null && ph < phTarget) {
        lines << "  • pH ${n1(ph)} low (target ${n1(phTarget)}): aerate to raise, or add soda ash per product directions (no amount estimated — base is easy to overshoot)."
        chips << "raise pH"
        additions = true
    }

    // TA low -> baking soda.
    if (ta != null && ta < taTarget) {
        BigDecimal lb = ((taTarget - ta) / 10G) * BAKING_SODA_LB_PER_10PPM_TA_PER_10K * v10k
        lines << "  ➕ TA ${n0(ta)} → target ${n0(taTarget)}: add ~${n2(lb)} lb baking soda (sodium bicarbonate)."
        chips << "${n1(lb)} lb baking soda"
        additions = true
    } else if (ta != null && ta > taTarget + 20G) {
        lines << "  • TA ${n0(ta)} high (target ${n0(taTarget)}): lower by adding acid and aerating (this also lowers pH); repeat gradually."
        chips << "lower TA"
        advisories = true
    }

    // CH low -> calcium chloride; CH very high -> partial drain.
    if (ch != null && ch < chTarget) {
        BigDecimal oz = (chTarget - ch) * CAL_CL_OZ_PER_PPM_CH_PER_10K * v10k
        lines << "  ➕ CH ${n0(ch)} → target ${n0(chTarget)}: add ~${ozLbUnits(oz)} calcium chloride."
        chips << "${chipOz(oz)} calcium"
        additions = true
    } else if (ch != null && ch > chTarget + 50G) {
        BigDecimal frac = (1G - (chTarget / ch)) * 100G
        lines << "  • CH ${n0(ch)} high (target ${n0(chTarget)}): no additive lowers CH — partial drain/refill ~${n0(frac)}% (~${n0(volume * frac / 100G)} gal)."
        chips << "CH high: partial drain"
        advisories = true
    }

    // CYA low -> stabilizer; CYA high -> partial drain.
    if (cya != null && cya < cyaTarget) {
        BigDecimal oz = ((cyaTarget - cya) / 10G) * CYA_OZ_PER_10PPM_PER_10K * v10k
        lines << "  ➕ CYA ${n0(cya)} → target ${n0(cyaTarget)}: add ~${ozLbUnits(oz)} cyanuric acid (stabilizer)."
        chips << "${chipOz(oz)} stabilizer"
        additions = true
    } else if (cya != null && cya > cyaTarget) {
        BigDecimal frac = (1G - (cyaTarget / cya)) * 100G
        lines << "  • CYA ${n0(cya)} high (target ${n0(cyaTarget)}): no additive lowers CYA — partial drain/refill ~${n0(frac)}% (~${n0(volume * frac / 100G)} gal)."
        chips << "CYA high: partial drain"
        advisories = true
    }

    return [lines: lines, action: (additions || advisories),
            additions: additions, advisories: advisories, chips: chips]
}

// ---------------------------------------------------------------------------
// Chlorine pump controller
// ---------------------------------------------------------------------------

private void handleDoseDecision(Map result, String trigger) {
    if (result?.doseMl == null || result?.runSeconds == null) {
        state.remove("pendingDose")
        return
    }
    if (result.doseOptional == true && doseOptionalTopUps != true) {
        log.info "WaterGuru Dosing Advisor: optional FC top-up not pumped (doseOptionalTopUps is off)"
        state.remove("pendingDose")
        return
    }

    String mode = (dosingMode ?: "ADVISORY").toString()
    if (mode == "ADVISORY") {
        state.remove("pendingDose")
        log.info "WaterGuru Dosing Advisor: advisory mode — calculated ${n0(result.doseMl)} mL but pump is locked out"
        return
    }
    if (mode == "APPROVAL") {
        state.pendingDose = doseState(result)
        sendPumpNotice("WaterGuru queued ${n0(result.doseMl)} mL chlorine (${formatDuration(result.runSeconds as Integer)}). Open the dosing app to approve it.")
        return
    }
    if (mode == "AUTO") startDose(result, trigger)
}

private Map doseState(Map result) {
    return [ml: n0(result.doseMl), mlRaw: (result.doseMl as BigDecimal).toString(),
            seconds: result.runSeconds as Integer, sample: result.sampleKey?.toString(),
            fc: result.fcVal?.toString(), target: result.fcTarget?.toString(),
            pH: result.pH?.toString(), created: now()]
}

private void runPendingDose() {
    Map pending = state.pendingDose instanceof Map ? state.pendingDose : null
    if (!pending) {
        sendPumpNotice("No pending chlorine dose is available.")
        return
    }
    Map current = computeAdvice()
    if (!current?.sampleKey || current.sampleKey.toString() != pending.sample?.toString()) {
        state.remove("pendingDose")
        sendPumpNotice("Pending chlorine dose cancelled because the WaterGuru sample changed.")
        return
    }
    startDose(current, "approved from app")
}

private void startDose(Map result, String trigger) {
    List blocks = doseSafetyBlocks(result)
    if (blocks) {
        String msg = "Chlorine dose BLOCKED: ${blocks.join('; ')}"
        log.warn "WaterGuru Dosing Advisor: ${msg}"
        sendPumpNotice(msg)
        return
    }

    Integer seconds = result.runSeconds as Integer
    BigDecimal ml = result.doseMl as BigDecimal
    String sample = result.sampleKey?.toString()
    Long stopAt = now() + (seconds * 1000L)

    // Establish the stop job and state before energizing the physical output.
    state.activeDose = [ml: n0(ml), mlRaw: ml.toString(), seconds: seconds,
                        sample: sample, started: now(), stopAt: stopAt]
    runIn(seconds, "stopDose", [overwrite: true])
    runIn(seconds + 15, "verifyPumpOff", [overwrite: true])
    armEmergencyPumpCutoff("app-started dose")
    try {
        pumpSwitch.on()
    } catch (e) {
        unschedule("stopDose")
        unschedule("verifyPumpOff")
        unschedule("emergencyPumpOff")
        state.remove("activeDose")
        try { pumpSwitch.off() } catch (ignored) { }
        String msg = "Chlorine pump failed to start: ${e.message}"
        log.error "WaterGuru Dosing Advisor: ${msg}"
        sendPumpNotice(msg)
        return
    }

    recordDoseLedger(ml, sample, seconds)
    publishTileTelemetry(result)
    state.remove("pendingDose")
    String msg = "Chlorine pump started: ${n0(ml)} mL for ${formatDuration(seconds)} at ${n1(firstNum(pumpRateMlPerMin, DEFAULT_PUMP_RATE_ML_MIN))} mL/min (${trigger}). ${tankSummaryPlain()}"
    log.warn "WaterGuru Dosing Advisor: ${msg}"
    sendPumpNotice(msg)
}

def stopDose() {
    Map active = state.activeDose instanceof Map ? state.activeDose : null
    try { if (pumpSwitch) pumpSwitch.off() }
    catch (e) { log.error "WaterGuru Dosing Advisor: pump OFF command failed — ${e.message}" }
    unschedule("emergencyPumpOff")
    state.remove("activeDose")
    if (active) {
        String msg = "Chlorine pump stopped after scheduled ${formatDuration((active.seconds ?: 0) as Integer)} dose (${active.ml} mL planned). ${tankSummaryPlain()}"
        log.info "WaterGuru Dosing Advisor: ${msg}"
        sendPumpNotice(msg)
    }
}

def verifyPumpOff() {
    if (!pumpSwitch) return
    if (pumpSwitch.currentValue("switch")?.toString() == "on") {
        try { pumpSwitch.off() } catch (ignored) { }
        unschedule("emergencyPumpOff")
        state.remove("activeDose")
        String msg = "EMERGENCY: chlorine pump was still on after its stop time; another OFF command was sent."
        log.error "WaterGuru Dosing Advisor: ${msg}"
        sendPumpNotice(msg)
    }
}

private void safeStopPump(String reason, boolean notify = false) {
    unschedule("stopDose")
    unschedule("verifyPumpOff")
    unschedule("emergencyPumpOff")
    try { if (pumpSwitch && pumpSwitch.currentValue("switch")?.toString() == "on") pumpSwitch.off() }
    catch (e) { log.error "WaterGuru Dosing Advisor: unable to stop chlorine pump — ${e.message}" }
    boolean wasActive = state.activeDose != null
    state.remove("activeDose")
    if (notify || wasActive) sendPumpNotice("Chlorine pump stopped: ${reason}.")
}

def pumpSwitchHandler(evt) {
    if (evt?.value?.toString() == "on") {
        if (state.activeDose) return
        if (watchdogAnyPumpRun == true) {
            armEmergencyPumpCutoff("pump start outside this app")
        }
    } else if (evt?.value?.toString() == "off") {
        unschedule("emergencyPumpOff")
        if (state.activeDose) {
            Long stopAt = (state.activeDose.stopAt ?: 0) as Long
            if (now() + 3000L < stopAt) {
                unschedule("stopDose")
                unschedule("verifyPumpOff")
                state.remove("activeDose")
                sendPumpNotice("Chlorine pump stopped before the planned dose completed.")
            }
        }
    }
}

private void armEmergencyPumpCutoff(String reason) {
    if (watchdogAnyPumpRun == false) return
    Integer seconds = Math.max(60, Math.round((firstNum(failsafePumpRunMinutes, 20G) * 60G).doubleValue()) as Integer)
    runIn(seconds, "emergencyPumpOff", [overwrite: true])
    log.warn "WaterGuru Dosing Advisor: ${reason}; independent emergency cutoff armed for ${formatDuration(seconds)}"
}

def emergencyPumpOff() {
    if (!pumpSwitch || pumpSwitch.currentValue("switch")?.toString() != "on") return
    try { pumpSwitch.off() } catch (ignored) { }
    state.remove("activeDose")
    String msg = "EMERGENCY cutoff stopped the chlorine pump after ${n1(firstNum(failsafePumpRunMinutes, 20G))} minutes."
    log.error "WaterGuru Dosing Advisor: ${msg}"
    sendPumpNotice(msg)
}

private List doseSafetyBlocks(Map result) {
    def blocks = []
    if (!pumpSwitch) blocks << "no dedicated pump switch selected"
    if (state.activeDose) blocks << "a dose is already running"
    if (pumpSwitch?.currentValue("switch")?.toString() == "on") blocks << "pump switch is already on"
    if ((dosingMode ?: "ADVISORY").toString() == "AUTO" && !autoDoseWindowOpen()) {
        blocks << "outside the configured AUTO dosing window"
    }
    if ((dosingMode ?: "ADVISORY").toString() == "AUTO" && oneAutoDosePerDay != false && autoDoseAlreadyRanToday()) {
        blocks << "an AUTO dose already ran today"
    }

    BigDecimal ml = toBD(result?.doseMl)
    Integer seconds = result?.runSeconds != null ? (result.runSeconds as Integer) : null
    BigDecimal rate = firstNum(pumpRateMlPerMin, DEFAULT_PUMP_RATE_ML_MIN)
    BigDecimal minMl = firstNum(minDoseMl, 50G)
    BigDecimal maxSingle = firstNum(maxSingleDoseMl, 3000G)
    BigDecimal maxDaily = firstNum(maxDailyDoseMl, 3500G)
    BigDecimal maxMinutes = firstNum(maxPumpRunMinutes, 20G)

    if (rate == null || rate <= 0) blocks << "pump rate must be greater than zero"
    if (ml == null || ml <= 0) blocks << "calculated dose is not positive"
    if (ml != null && minMl != null && ml < minMl) blocks << "${n0(ml)} mL is below the ${n0(minMl)} mL minimum"
    if (ml != null && maxSingle != null && ml > maxSingle) blocks << "${n0(ml)} mL exceeds the ${n0(maxSingle)} mL single-dose limit"
    if (seconds == null || seconds <= 0) blocks << "pump runtime is invalid"
    if (seconds != null && maxMinutes != null && seconds > (maxMinutes * 60G)) blocks << "${formatDuration(seconds)} exceeds the ${n1(maxMinutes)} minute runtime limit"

    BigDecimal ph = toBD(result?.pH)
    BigDecimal loPh = firstNum(minSafePh, 6.8G), hiPh = firstNum(maxSafePh, 8.2G)
    if (ph == null) blocks << "pH reading is unavailable"
    else if ((loPh != null && ph < loPh) || (hiPh != null && ph > hiPh)) blocks << "pH ${n2(ph)} is outside ${n2(loPh)}–${n2(hiPh)}"

    Long measured = toEpochMs(result?.sampleKey)
    BigDecimal maxAge = firstNum(maxSampleAgeHours, 18G)
    if (measured == null) blocks << "measurement timestamp is unavailable"
    else if (maxAge != null && (now() - measured) > (maxAge * 3600000G)) blocks << "WaterGuru sample is older than ${n1(maxAge)} hours"

    if (result?.sampleKey && state.lastDosedSample?.toString() == result.sampleKey.toString()) blocks << "this WaterGuru sample was already dosed"

    BigDecimal today = doseMlToday()
    if (ml != null && maxDaily != null && today + ml > maxDaily) blocks << "daily total would be ${n0(today + ml)} mL, above the ${n0(maxDaily)} mL limit"

    BigDecimal tankCap = tankCapacityMl()
    BigDecimal tankRemaining = tankRemainingMl()
    if (tankCap != null && tankRemaining == null) {
        blocks << "chlorine tank inventory is not initialized; mark the tank full"
    } else if (ml != null && tankRemaining != null && ml > tankRemaining) {
        blocks << "chlorine tank has only ${n0(tankRemaining)} mL remaining; dose needs ${n0(ml)} mL"
    }

    if (circulationSwitch && requireCirculationOn != false && circulationSwitch.currentValue("switch")?.toString() != "on") {
        blocks << "circulation/filter switch is not on"
    }
    if (!circulationSwitch && circulationAlwaysOn != true) {
        blocks << "no circulation interlock or 24/7 circulation confirmation"
    }
    return blocks
}

private boolean autoDoseWindowOpen() {
    if (limitAutoDoseWindow != true) return true
    if (!autoDoseWindowStart || !autoDoseWindowEnd) return false
    try {
        Date start = timeToday(autoDoseWindowStart, location.timeZone)
        Date end = timeToday(autoDoseWindowEnd, location.timeZone)
        long current = now(), startMs = start.time, endMs = end.time
        return startMs <= endMs ? (current >= startMs && current <= endMs) :
                                  (current >= startMs || current <= endMs)
    } catch (e) {
        log.error "WaterGuru Dosing Advisor: invalid AUTO dosing window — ${e.message}"
        return false
    }
}

private boolean autoDoseAlreadyRanToday() {
    Long lastRun = state.lastDose?.time != null ? (state.lastDose.time as Long) : null
    if (lastRun == null) return false
    String today = new Date().format("yyyy-MM-dd", location.timeZone)
    String runDay = new Date(lastRun).format("yyyy-MM-dd", location.timeZone)
    return runDay == today
}

private void recordDoseLedger(BigDecimal ml, String sample, Integer seconds) {
    String day = doseDayKey()
    BigDecimal prior = (state.doseDay == day) ? (toBD(state.doseMlToday) ?: 0G) : 0G
    state.doseDay = day
    state.doseMlToday = (prior + ml).toString()
    state.lastDosedSample = sample
    state.lastDose = [time: now(), ml: n0(ml), mlRaw: ml.toString(), seconds: seconds, sample: sample]
    recordTankUse(ml)
}

private BigDecimal tankCapacityMl() {
    BigDecimal gallons = toBD(chlorineTankGallons)
    return gallons != null && gallons > 0 ? gallons * ML_PER_US_GALLON : null
}

private BigDecimal tankRemainingMl() {
    BigDecimal selected = toBD(chlorineTankGallons)
    BigDecimal initialized = toBD(state.tankCapacityGallons)
    BigDecimal cap = tankCapacityMl()
    BigDecimal remaining = toBD(state.tankRemainingMl)
    if (selected == null || initialized == null || selected.compareTo(initialized) != 0 || cap == null || remaining == null) return null
    if (remaining < 0) return 0G
    return remaining > cap ? cap : remaining
}

private String tankSummaryPlain() {
    BigDecimal cap = tankCapacityMl()
    BigDecimal remaining = tankRemainingMl()
    if (cap == null) return "Tank capacity not configured."
    if (remaining == null) return "Tank inventory not initialized."
    BigDecimal pct = cap > 0 ? remaining * 100G / cap : 0G
    return "Tank ${n0(remaining)} mL remaining (${n0(pct)}%)."
}

private String tankInventoryLine() {
    BigDecimal cap = tankCapacityMl()
    BigDecimal remaining = tankRemainingMl()
    if (cap == null) return "🧴 Chlorine tank: capacity not configured."
    if (remaining == null) return "🧴 Chlorine tank: full ${n0(cap)} mL (${n2(cap / ML_PER_US_GALLON)} gal) · remaining not initialized."
    BigDecimal pct = cap > 0 ? remaining * 100G / cap : 0G
    return "🧴 Chlorine tank: full ${n0(cap)} mL (${n2(cap / ML_PER_US_GALLON)} gal) · remaining ${n0(remaining)} mL (${n2(remaining / ML_PER_US_GALLON)} gal, ${n0(pct)}%)."
}

private void resetTankFull() {
    BigDecimal gallons = toBD(chlorineTankGallons)
    BigDecimal cap = tankCapacityMl()
    if (gallons == null || cap == null) {
        sendPumpNotice("Cannot reset tank inventory: select a valid container size.")
        return
    }
    state.tankCapacityGallons = gallons.toString()
    state.tankRemainingMl = cap.toString()
    state.tankLastRefill = now()
    state.tankLowAlerted = false
    Map refreshed = computeAdvice()
    state.lastPreview = refreshed.text
    updateTileDevice(refreshed)
    String msg = "Chlorine tank marked full: ${n2(gallons)} gal / ${n0(cap)} mL available."
    log.info "WaterGuru Dosing Advisor: ${msg}"
    sendPumpNotice(msg)
}

private void recordTankUse(BigDecimal ml) {
    BigDecimal cap = tankCapacityMl()
    BigDecimal before = tankRemainingMl()
    if (cap == null || before == null || ml == null || ml <= 0) return
    BigDecimal after = before - ml
    if (after < 0) after = 0G
    state.tankRemainingMl = after.toString()

    Long doseTime = state.lastDose?.time != null ? (state.lastDose.time as Long) : now()
    def history = tankDoseHistory()
    boolean duplicate = history.any { h ->
        h?.t instanceof Number && (h.t as Long) == doseTime && toBD(h?.ml)?.compareTo(ml) == 0
    }
    if (!duplicate) history << [t: doseTime, ml: ml.toString()]
    if (history.size() > TANK_DOSE_HISTORY_MAX) history = history[(history.size() - TANK_DOSE_HISTORY_MAX)..-1]
    state.tankDoseHistory = history

    BigDecimal lowPct = firstNum(tankLowPercent, 20G)
    if (lowPct < 0) lowPct = 0G
    if (lowPct > 100) lowPct = 100G
    BigDecimal threshold = cap * lowPct / 100G
    if (after <= threshold && state.tankLowAlerted != true) {
        state.tankLowAlerted = true
        String msg = "Chlorine tank LOW: ${n0(after)} mL remaining (${n0(after * 100G / cap)}%) in the ${n2(toBD(chlorineTankGallons))}-gal container. Refill or replace it, then press Mark chlorine tank full."
        log.warn "WaterGuru Dosing Advisor: ${msg}"
        sendPumpNotice(msg)
    }

    // Display-only, but this runs on the dose-completion path after the pump has already run,
    // so it must not be able to throw into the ledger bookkeeping above it.
    try {
        refreshTankLinesInPreview()
    } catch (e) {
        logDebug "refreshTankLinesInPreview failed: ${e.message}"
    }
}

/**
 * Re-render the tank-derived lines in the stored preview, then re-push `detail`.
 *
 * The preview embeds the tank inventory and the tank runway, and both are derived from state
 * THIS APP changes when a dose runs. Without this the numeric attributes advance while the
 * human-readable detail still advertises the pre-dose inventory - a full tank beside an
 * attribute that is one dose short of it.
 *
 * This splices the two tank lines rather than calling computeAdvice() to rebuild the whole text.
 * computeAdvice() is a pure computation -- it reads devices and writes no state, and dosing is
 * gated separately by runAndDeliver(allowDose) -- so either approach would be correct. The
 * splice is used because the chemistry lines are still as-of the last WaterGuru sample, which a
 * dose does not change, so regenerating them would be churn at best. Only the two lines that
 * actually moved are replaced.
 */
private void refreshTankLinesInPreview() {
    String prior = state.lastPreview
    if (!(prior instanceof String) || !prior.contains("Chlorine tank:")) return

    String inventory = tankInventoryLine()
    String runway = tankRunwayLine(computeTankRunway())
    List lines = prior.split("\n", -1) as List
    boolean changed = false
    for (int i = 0; i < lines.size(); i++) {
        String line = (String) lines[i]
        if (line.contains("Chlorine tank:")) { lines[i] = inventory; changed = true }
        else if (line.contains("Tank runway:")) { lines[i] = runway; changed = true }
    }
    if (!changed) return

    state.lastPreview = lines.join("\n")
    def dev = getTileDevice()
    if (dev) dev.sendEvent(name: "detail", value: clip((String) state.lastPreview, 1000))
    logDebug "Refreshed the tank lines in the stored preview after a dose"
}

private List tankDoseHistory() {
    def history = (state.tankDoseHistory instanceof List) ? state.tankDoseHistory : []
    if (!history && state.lastDose instanceof Map) {
        Long t = state.lastDose.time != null ? (state.lastDose.time as Long) : null
        BigDecimal ml = toBD(state.lastDose.mlRaw ?: state.lastDose.ml)
        if (t != null && ml != null && ml > 0) {
            history = [[t: t, ml: ml.toString()]]
            state.tankDoseHistory = history
        }
    }
    return history
}

private Map computeTankRunway() {
    BigDecimal cap = tankCapacityMl()
    BigDecimal remaining = tankRemainingMl()
    if (cap == null || remaining == null || cap <= 0) return null

    BigDecimal lowPct = firstNum(tankLowPercent, 20G)
    if (lowPct < 0) lowPct = 0G
    if (lowPct > 100) lowPct = 100G
    BigDecimal threshold = cap * lowPct / 100G
    if (remaining <= threshold) return [state: "below", pct: lowPct, days: 0G]

    def doses = tankDoseHistory().collect { toBD(it?.ml) }.findAll { it != null && it > 0 }
    if (!doses) return [state: "learning", pct: lowPct, n: 0]
    BigDecimal dailyUse = doses.sum(0G) / doses.size()
    if (dailyUse <= 0) return [state: "learning", pct: lowPct, n: doses.size()]
    BigDecimal days = (remaining - threshold) / dailyUse
    return [state: "ok", pct: lowPct, days: days, dailyUse: dailyUse, n: doses.size()]
}

private String tankRunwayLine(Map runway) {
    switch (runway?.state) {
        case "below":
            return "⏳ Tank runway: inventory is at/below the ${n0(runway.pct)}% low-tank threshold."
        case "learning":
            return "⏳ Tank runway: learning — a completed app-controlled dose is needed to estimate days until ${n0(runway.pct)}%."
        case "ok":
            String samples = runway.n == 1 ? "dose sample" : "dose samples"
            return "⏳ Tank runway: ~${n1(runway.days)} days until inventory drops below ${n0(runway.pct)}% (average ${n0(runway.dailyUse)} mL/day over ${runway.n} ${samples})."
        default:
            return ""
    }
}

private BigDecimal doseMlToday() {
    if (state.doseDay != doseDayKey()) return 0G
    return toBD(state.doseMlToday) ?: 0G
}

private String doseDayKey() {
    return new Date().format("yyyy-MM-dd", location?.timeZone ?: TimeZone.getDefault())
}

private void sendNotice(String msg) {
    if (!notifyDevices) {
        log.warn "WaterGuru Dosing Advisor: no notification device selected — ${msg}"
        return
    }
    notifyDevices.each { dev ->
        try { dev.deviceNotification(msg) }
        catch (e) { log.error "WaterGuru Dosing Advisor: failed to notify ${dev?.displayName} — ${e.message}" }
    }
}

private void sendPumpNotice(String msg) { sendNotice("WaterGuru dosing: ${msg}") }

private String formatDuration(Integer seconds) {
    if (seconds == null) return "?"
    int mins = (int)(seconds / 60)
    int secs = seconds % 60
    return mins > 0 ? "${mins}m ${secs}s" : "${secs}s"
}

// ---------------------------------------------------------------------------
// Dashboard tile (companion device)
// ---------------------------------------------------------------------------

private String tileDni()   { "wgda-tile-${app.id}" }
private def    getTileDevice() { getChildDevice(tileDni()) }
private String tileLabel()     { "Dosing Tile: ${sourceDevice?.displayName ?: (app?.label ?: 'pool')}" }

/** Create the companion tile device (or remove it when the toggle is off). */
private void ensureTileDevice() {
    if (createTile == false) { removeTileDevice(); return }
    if (getTileDevice()) return
    try {
        addChildDevice("chsbusch-dot", "WaterGuru Dosing Tile", tileDni(),
            [name: "WaterGuru Dosing Tile", label: tileLabel(), isComponent: false])
        log.info "WaterGuru Dosing Advisor: created tile device '${tileLabel()}'"
    } catch (e) {
        log.error "WaterGuru Dosing Advisor: could not create the tile device — ${e.message}. " +
                  "Install the 'WaterGuru Dosing Tile' driver under Drivers Code, then save this pool again."
    }
}

private void removeTileDevice() {
    if (getTileDevice()) {
        try { deleteChildDevice(tileDni()); log.info "WaterGuru Dosing Advisor: removed tile device" }
        catch (e) { log.warn "WaterGuru Dosing Advisor: could not remove the tile device — ${e.message}" }
    }
}

/** Recompute and push to the tile without sending a notification (used on save). */
private void refreshTile() {
    if (createTile == false || !sourceDevice || !getTileDevice()) return
    try { updateTileDevice(computeAdvice()) }
    catch (e) { logDebug "refreshTile failed: ${e.message}" }
}

/** Push the latest advice into the tile device's attributes. */
private void updateTileDevice(Map r) {
    if (createTile == false) return
    def dev = getTileDevice()
    if (!dev) { ensureTileDevice(); dev = getTileDevice() }
    if (!dev) return   // driver missing — ensureTileDevice already logged why
    try {
        dev.sendEvent(name: "status",         value: (r.status ?: "YELLOW"))
        dev.sendEvent(name: "recommendation", value: clip(r.headline ?: "—", 190))
        dev.sendEvent(name: "detail",         value: clip(r.text ?: "", 1000))
        dev.sendEvent(name: "tileHtml",       value: clip(buildTileHtml(r), 1024))
        dev.sendEvent(name: "lastCalc",       value: new Date())
        // The same instant in epoch milliseconds. lastCalc is a locale-formatted string that
        // cannot be parsed reliably off-hub, so an external historian reading the tile would
        // otherwise have to stamp a target snapshot at collection time instead of at the
        // moment it was calculated (which can be hours earlier, or days if a run is missed).
        dev.sendEvent(name: "lastCalcEpochMs", value: now())
        publishTileTelemetry(r, dev)
    } catch (e) {
        log.warn "WaterGuru Dosing Advisor: could not update the tile device — ${e.message}"
    }
}

/**
 * Publish machine-readable values separately from the human preview. The last
 * dose is recorded only after the pump ON command succeeds, so external totals
 * represent commanded pump runs rather than recommendations or previews.
 */
private void publishTileTelemetry(Map r = null, def tile = null) {
    if (createTile == false) return
    def dev = tile ?: getTileDevice()
    if (!dev) return

    Map last = state.lastDose instanceof Map ? state.lastDose : [:]
    BigDecimal cap = tankCapacityMl()
    BigDecimal remaining = tankRemainingMl()
    Map tankRunway = computeTankRunway()

    if (last.time != null)       dev.sendEvent(name: "lastDoseEpochMs", value: last.time as Long)
    if (last.mlRaw != null || last.ml != null)
        dev.sendEvent(name: "lastDoseMl", value: toBD(last.mlRaw ?: last.ml), unit: "mL")
    if (last.seconds != null)    dev.sendEvent(name: "lastDoseRuntimeSeconds", value: last.seconds as Integer, unit: "s")
    if (cap != null)             dev.sendEvent(name: "tankCapacityMl", value: cap, unit: "mL")
    if (remaining != null) {
        dev.sendEvent(name: "tankRemainingMl", value: remaining, unit: "mL")
        dev.sendEvent(name: "tankPercent", value: cap > 0 ? remaining * 100G / cap : 0G, unit: "%")
    }
    if (tankRunway?.days != null)
        dev.sendEvent(name: "tankRunwayDays", value: tankRunway.days as BigDecimal, unit: "days")
    if (r?.fcVal != null)        dev.sendEvent(name: "freeChlorine", value: r.fcVal as BigDecimal, unit: "ppm")
    if (r?.fcTarget != null)     dev.sendEvent(name: "targetFreeChlorine", value: r.fcTarget as BigDecimal, unit: "ppm")
}

/**
 * Compact, dashboard-friendly HTML card for an "Attribute" tile bound to
 * tileHtml: a status-colored header band, a free-chlorine-vs-target progress
 * bar, and a cassette badge. Single-quoted HTML attributes keep it terse; the
 * whole card stays well under Hubitat's 1024-char attribute limit. The header
 * band forces white text (readable on any color), while the body inherits the
 * dashboard's own text color so the card looks right on light AND dark
 * dashboards. pH / CYA aren't shown here — the dashboard already has dedicated
 * number tiles for those.
 */
private String buildTileHtml(Map r) {
    String color = tileColor(r.status)
    String word  = (r.status ?: "").toString()
    String ts    = new Date().format("EEE h:mm a", location?.timeZone ?: TimeZone.getDefault())
    String label = clip((r.label ?: "pool").toString(), 24)
    String head  = clip((r.headline ?: "").toString(), 46)
    String cass  = attrRaw("cassetteType")
    boolean haveCass = cass && cass.trim() && !cass.trim().equalsIgnoreCase("unknown")

    def sb = new StringBuilder()
    sb << "<div style='font-family:sans-serif;border:1px solid #8884;border-radius:12px;overflow:hidden;line-height:1.3'>"
    sb << "<div style='background:${color};color:#fff;padding:8px 11px;display:flex;justify-content:space-between;align-items:center'>"
    sb << "<b style='font-size:15px'>🌊 ${esc(label)}</b>"
    sb << "<span style='background:#fff4;padding:1px 8px;border-radius:9px;font-size:11px;font-weight:700'>${esc(word)}</span></div>"
    sb << "<div style='padding:9px 11px'>"
    if (head) sb << "<div style='font-size:13px;margin-bottom:8px'>${esc(head)}</div>"
    if (r.fcVal != null && r.fcTarget != null && (r.fcTarget as BigDecimal) > 0) {
        int pct = Math.max(0, Math.min(100, (int) Math.round(
            (r.fcVal as BigDecimal).doubleValue() / (r.fcTarget as BigDecimal).doubleValue() * 100)))
        sb << "<div style='font-size:12px;opacity:.75'>Free chlorine <b>${n1(r.fcVal)}</b> → ${n1(r.fcTarget)} ppm</div>"
        sb << "<div style='height:6px;background:#8884;border-radius:3px;margin:5px 0 9px'><div style='width:${pct}%;height:100%;background:${color};border-radius:3px'></div></div>"
    }
    if (haveCass)
        sb << "<div style='margin-bottom:7px'><span style='background:#8884;padding:2px 8px;border-radius:9px;font-size:11px'>🧪 ${esc(cass.trim())}</span></div>"
    String foot
    if (r.runway?.state == "ok" && r.runway.days != null)
        foot = "⏳ ~${n1(r.runway.days)} d to algae floor · ${esc(ts)}"
    else if (r.runway?.state == "below")
        foot = "⏳ FC below algae floor · ${esc(ts)}"
    else
        foot = "Updated ${esc(ts)}${r.sampled ? ' · sampled ' + esc(r.sampled) : ''}"
    sb << "<div style='font-size:10px;opacity:.5'>${foot}</div>"
    sb << "</div></div>"
    return sb.toString()
}

private String tileColor(String status) {
    switch (status) {
        case "RED":   return "#c0392b"
        case "GREEN": return "#2e7d32"
        default:      return "#e08600"   // YELLOW / unknown
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Short single-unit volume for the one-liner: gal, else cups, else fl oz. */
private String shortVol(BigDecimal floz) {
    if (floz == null) return "?"
    if (floz >= 128G) return "${n1(floz / 128G)} gal"
    if (floz >= 8G)   return "${n1(floz / 8G)} cups"
    return "${n0(floz)} fl oz"
}

/** Short dry weight for the one-liner: lb when large, else oz. */
private String chipOz(BigDecimal oz) {
    if (oz == null) return "?"
    return oz >= 16G ? "${n1(oz / 16G)} lb" : "${n0(oz)} oz"
}

/** Trim a WaterGuru advice line down to a one-liner fragment. */
private String shortenWg(String line) {
    String s = (line ?: "").trim().replaceAll(/^[-•*\s]+/, "")
    return s.length() > 42 ? (s.substring(0, 41) + "…") : s
}

/** Minimal HTML escaping for values placed into tileHtml. */
private String esc(def s) {
    if (s == null) return ""
    return s.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/** Cap a string to n characters (Hubitat truncates attribute values at ~1024). */
private String clip(String s, int n) {
    if (s == null) return ""
    return s.length() > n ? (s.substring(0, n - 1) + "…") : s
}

/** Format a liquid volume in fl oz, adding cups and gallons when meaningful. */
private String flozUnits(BigDecimal floz) {
    def parts = ["${n0(floz)} fl oz"]
    if (floz >= 8G)   parts << "${n1(floz / 8G)} cups"
    if (floz >= 128G) parts << "${n1(floz / 128G)} gal"
    return parts.join(" / ")
}

/** Format a dry weight in oz, adding lb when large. */
private String ozLbUnits(BigDecimal oz) {
    if (oz >= 16G) return "${n1(oz)} oz (${n2(oz / 16G)} lb)"
    return "${n1(oz)} oz"
}

private boolean numSet(def v) { v != null && v.toString().trim() != "" }

/** First of the supplied values that is a usable number, else null. */
private BigDecimal firstNum(Object... candidates) {
    for (c in candidates) {
        if (c == null) continue
        if (c instanceof BigDecimal) return c
        def bd = toBD(c)
        if (bd != null) return bd
    }
    return null
}

private BigDecimal attrNum(String attr) { toBD(sourceDevice?.currentValue(attr)) }
private String     attrRaw(String attr) { def v = sourceDevice?.currentValue(attr); v == null ? null : v.toString() }

/** WaterGuru cassette descriptor for the header/tile, or null when the source
 *  driver (older WaterGuru Integration) doesn't report it or it's unknown.
 *  Prefers the richer cassetteInfo (e.g. "C5 · installed Aug 12, 2026 ·
 *  28/30 pads") and falls back to the bare cassetteType. */
private String cassetteText() {
    def info = attrRaw("cassetteInfo")
    def type = attrRaw("cassetteType")
    String base = (info && info.trim() && !info.trim().equalsIgnoreCase("unknown")) ? info.trim() :
                  ((type && type.trim() && !type.trim().equalsIgnoreCase("unknown")) ? type.trim() : null)
    if (!base) return null
    def parts = [base]
    def checks = attrRaw("CassetteChecksLeft")
    def timeLeft = attrRaw("CassetteTimeLeft")
    def percent = attrRaw("CassettePercent")
    if (checks && checks.trim() && !checks.trim().equalsIgnoreCase("unknown")) parts << "${checks.trim()} tests left"
    if (timeLeft && timeLeft.trim() && !timeLeft.trim().equalsIgnoreCase("unknown")) parts << timeLeft.trim()
    if (percent && percent.trim() && !percent.trim().equalsIgnoreCase("unknown")) parts << "${percent.trim()}%"
    return parts.join(" · ")
}

// ---------------------------------------------------------------------------
// Chlorine runway (algae forecast)
// ---------------------------------------------------------------------------

/** Append the current free-chlorine reading to the rolling sample history the
 *  runway forecast learns its decay rate from. Keyed by the sample's
 *  measurement time so repeated polls of the same sample don't double-count;
 *  bounded to RUNWAY_HISTORY_MAX entries. */
private void recordFcSample(evt) {
    BigDecimal fc = attrNum("freeChlorine")
    if (fc == null) return
    Long t = toEpochMs(evt?.value) ?: now()
    def hist = (state.fcHistory instanceof List) ? state.fcHistory : []
    if (hist && hist[-1]?.t == t) return   // same sample already recorded
    hist << [t: t, fc: fc.toString()]
    if (hist.size() > RUNWAY_HISTORY_MAX) hist = hist[(hist.size() - RUNWAY_HISTORY_MAX)..-1]
    state.fcHistory = hist
    logDebug "Recorded FC sample ${fc} ppm @ ${t} (${hist.size()} in history)"
}

/** Days until free chlorine falls below the algae-prevention floor
 *  (TFP_MIN_FACTOR x CYA). Loss rate: manual override > measured (from the
 *  sample history) > a cover-aware modeled default. Returns null when the floor
 *  can't be placed (no CYA) or FC is unknown. */
private Map computeRunway(BigDecimal fc, BigDecimal cya) {
    if (fc == null || cya == null || cya <= 0) return null
    BigDecimal floor = cya * TFP_MIN_FACTOR

    BigDecimal loss
    String basis
    boolean measured = false
    if (numSet(fcLossPerDay) && (fcLossPerDay as BigDecimal) > 0) {
        loss  = fcLossPerDay as BigDecimal
        basis = "your set rate ${n1(loss)} ppm/day"
    } else {
        def m = measuredFcLoss()
        if (m != null) {
            loss = m.rate; measured = true
            basis = "measured ${n1(loss)} ppm/day over ${m.n} sample${m.n == 1 ? '' : 's'}"
        } else {
            loss  = hasCover() ? (FC_LOSS_MODELED_DEFAULT * FC_LOSS_COVER_FACTOR) : FC_LOSS_MODELED_DEFAULT
            basis = "estimated ${n1(loss)} ppm/day${hasCover() ? ' (cover)' : ''} — no sample history yet"
        }
    }

    if (fc <= floor) return [state: "below",   floor: floor, loss: loss, basis: basis, measured: measured, days: 0d]
    if (loss <= 0)   return [state: "holding", floor: floor, loss: loss, basis: basis, measured: measured, days: null]
    double days = (fc - floor).doubleValue() / loss.doubleValue()
    return [state: "ok", floor: floor, loss: loss, basis: basis, measured: measured, days: days]
}

/** Average daily FC loss over recent decay intervals — consecutive samples where
 *  FC fell (i.e. not across a chlorine addition) and that are at least ~6 h apart.
 *  Uses up to the last 5 such intervals. Returns [rate, n] or null. */
private Map measuredFcLoss() {
    def hist = (state.fcHistory instanceof List) ? state.fcHistory : []
    if (hist.size() < 2) return null
    def rates = []
    for (int i = 1; i < hist.size(); i++) {
        BigDecimal fa = toBD(hist[i-1]?.fc), fb = toBD(hist[i]?.fc)
        def ta = hist[i-1]?.t, tb = hist[i]?.t
        if (fa == null || fb == null || !(ta instanceof Number) || !(tb instanceof Number)) continue
        double dtDays = ((tb as Long) - (ta as Long)) / 86400000.0d
        if (dtDays < 0.25d) continue     // too close together to be a fresh sample
        if (fb >= fa) continue           // FC rose = chlorine added, not decay
        rates << (fa - fb).doubleValue() / dtDays
    }
    if (!rates) return null
    def recent = rates.size() > 5 ? rates[-5..-1] : rates
    double avg = recent.sum() / recent.size()
    return [rate: avg as BigDecimal, n: recent.size()]
}

/** Whether the source device reports a pool cover (slows chlorine burn-off). */
private boolean hasCover() {
    def eq = attrRaw("equipment")
    return eq != null && eq.toLowerCase().contains("cover")
}

/** One-line runway summary for the notification / tile-detail text. */
private String runwayLine(Map r) {
    switch (r?.state) {
        case "below":   return "⏳ Chlorine runway: FC is at/below the algae floor (${n1(r.floor)} ppm) — add chlorine now."
        case "holding": return "⏳ Chlorine runway: FC is holding or rising — nothing to project (floor ${n1(r.floor)} ppm)."
        case "ok":      return "⏳ Chlorine runway: ~${n1(r.days)} days until FC drops below the ${n1(r.floor)} ppm algae floor (${r.basis})."
        default:        return ""
    }
}

/** Config-page status: how much sample history the forecast has learned from. */
private String runwayStatusLine() {
    def hist = (state.fcHistory instanceof List) ? state.fcHistory : []
    def m = measuredFcLoss()
    if (m != null) return "Currently using your measured loss (~${n1(m.rate)} ppm/day from ${hist.size()} samples)."
    return "Samples recorded so far: ${hist.size()} (a couple of days of declines are needed before a measured rate replaces the estimate)."
}

/** Parse a WaterGuru/Hubitat ISO-8601 timestamp string to epoch millis, or null. */
private Long toEpochMs(def s) {
    if (!s) return null
    try { return toDateTime(s.toString())?.getTime() } catch (ignored) { return null }
}

private BigDecimal toBD(def v) {
    if (v == null) return null
    try { return new BigDecimal(v.toString().trim()) } catch (ignored) { return null }
}

private String n0(def v) { fmt(v, 0) }
private String n1(def v) { fmt(v, 1) }
private String n2(def v) { fmt(v, 2) }
private String fmt(def v, int dp) {
    if (v == null) return "?"
    // String.format rounds HALF_UP and avoids the java.math.RoundingMode import,
    // which keeps the app well within Hubitat's Groovy sandbox.
    try { return String.format("%.${dp}f", (v as BigDecimal).toDouble()) }
    catch (ignored) { return v.toString() }
}

private void logDebug(String msg) { if (logEnable != false) log.debug msg }
