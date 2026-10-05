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
 *   2.4.2 - Follow-ups to 2.4.1 (WOR-718). (1) A stop with nothing open (the STOP button on an idle
 *           app, a save, a removal) is anchored to its own request, so a cached "off" no longer
 *           confirms it before the plug has answered; it says "stop requested" until a fresh OFF
 *           arrives, and a later OFF still produces the final "stopped" notice. (2) An unchanged ON
 *           report (a profiler refresh answering a stuck relay) during a stop already in progress no
 *           longer restarts the stop: no extra OFF, no reset retry count, no postponed verification,
 *           so the escalation and the cutoff run their course. (3) Saving the app rebuilds its jobs
 *           before stopping the pump, so a run the app is not tracking keeps its retries and cutoff;
 *           removal says plainly that nothing will retry. (4) An emergency cutoff that is overdue is
 *           fired at once instead of being pushed a whole window later. (5) The EMERGENCY notices wait
 *           a few seconds for the OFF answer, like the stop notice. (6) The power watch stops once an
 *           OFF is requested, so a stop cannot latch a false power loss. (7) The cutoff latches the
 *           start fault when it stops an attempt that was never confirmed. (8) The tile publishes the
 *           dose record as forced events at confirmation and correction, and -1 for a value that is
 *           no longer known (tank not initialised, FC or target missing, runway still learning).
 *           (9) Tank corrections: a signed inventory adjustment, and voiding a recorded dose that
 *           never ran (history, tank and historian), neither of which touches the pump or today's
 *           dose total.
 *   2.4.1 - Fixes from the independent review of 2.4.0, built on the platform behaviour measured on
 *           the live hub: a report whose value did not change arrives as an event with a fresh date
 *           but never re-dates currentState(). (1) An OFF report accepted by its event date is
 *           remembered, so a plug that was already off can still prove it, and a fault after a lost
 *           ON can be acknowledged again (it previously refused forever). (2) A confirmed run's power
 *           freshness uses the receipt time of the latest power report, so a steady reading no
 *           longer aborts a healthy run as "power LOST". (3) A stop waits a few seconds for the plug's
 *           OFF report before saying anything; "stop requested ... Retrying" is sent only if the OFF
 *           is still unconfirmed. (4) A run is booked from its ON report to its OFF report: a late OFF
 *           debits the extra volume from the tank, adds it to today's total and latches a review fault
 *           when it lands more than 30 s after the planned stop; an early stop books the tank from the
 *           observed run; an unconfirmed attempt that demonstrably ran is counted too. A correction
 *           republishes the dose under its original lastDoseEpochMs. (5) The measured FC loss adds the
 *           app's own doses back between samples, so daily dosing no longer hides the loss.
 *   2.4.0 - Truthful start confirmation. An ON command returning no longer counts as a pump
 *           start. For a power-capable switch a fresh ON plus fresh measured power above a
 *           configurable minimum (default 3 W) must confirm the run before anything is recorded
 *           or announced as started; a non-power switch is accepted with an explicit power/flow-unverified
 *           notice. A start that is not confirmed within the timeout alerts once, goes through
 *           the existing OFF/cutoff safety path, keeps a latched fault until the operator
 *           acknowledges it, and never deducts tank inventory or emits a success dose record.
 *           Attempted sample/day volume is reserved before ON so a failure cannot make another
 *           automatic dose eligible or bypass the daily cap. A confirmed run is watched for
 *           power loss or stale power evidence and aborted safely. No claim of proven liquid
 *           flow is made anywhere.
 *   2.3.2 - Confirmed-stop notice. A dose whose OFF is reported late now sends the one final
 *           "stopped" notification when the switch positively reports off, instead of silently
 *           clearing the dose and its verification. Duplicate or idle OFF events stay silent,
 *           and the early-stop notice is unchanged.
 *   2.3.1 - Stop-lifecycle safety. A stop is only "stopped" once the switch positively reports off,
 *           so a failed or ignored OFF keeps the independent emergency cutoff armed and keeps
 *           retrying, and the notices distinguish "stop requested" from "stopped". A re-arm never
 *           postpones a cutoff that is already due sooner, and a queue reset (saving configuration)
 *           recreates the cutoff JOB using the time remaining instead of leaving a stored deadline
 *           with no timer behind it. A confirmed off releases the dose on every path, so a later
 *           dose is no longer blocked by "a dose is already running". A failed start is routed
 *           through the same confirmed-OFF handling, so a relay that energised before the ON
 *           command errored keeps its dose record and its cutoff.
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

def appVersion() { "2.4.2" }

definition(
    name:        "WaterGuru Dosing Advisor Pool",
    namespace:   "chsbusch-dot",
    author:      "Chris Busch",
    parent:      "chsbusch-dot:WaterGuru Dosing Advisor",
    description: "One WaterGuru pool's dosing advisor (child of the WaterGuru Dosing Advisor app).",
    category:    "Convenience",
    // Hubitat runs an app's handlers one at a time when this is set, so a power event and a timer
    // cannot interleave inside the confirmation/booking path. Booking is ALSO idempotent by
    // attempt id, because a stale handler closure must never be able to book twice.
    singleThreaded: true,
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

// Stop verification. A stop is only "stopped" once the switch reports it, so these bound the
// RETRIES, never the confirmation: the independent emergency cutoff stays armed until the
// switch actually reads off. See stopDose()/verifyPumpOff().
@Field static final int        STOP_MAX_ATTEMPTS       = 5
@Field static final int        STOP_RETRY_SECONDS      = 20

// Pump start confirmation (2.4.0). An ON command returning is not evidence the relay closed
// or the pump is moving liquid, so a fresh reading must confirm it before the result is recorded
// or announced as started. Power is required only for a device that reports it; a plain switch is accepted
// with an explicit notice that power/flow are unverified. Liquid flow is never claimed.
@Field static final BigDecimal DEFAULT_START_POWER_MIN_WATTS = 3G
@Field static final int        DEFAULT_START_TIMEOUT_SECONDS = 20
@Field static final int        DEFAULT_POWER_LOSS_GRACE_SECONDS = 30
// A start is checked quickly so a short dose can still be confirmed; refreshes are throttled to
// roughly 10 s so a device is not polled into the ground during a run.
@Field static final int        START_CHECK_SECONDS          = 2
@Field static final int        START_REFRESH_THROTTLE_SECONDS = 10
@Field static final int        RUN_POWER_CHECK_SECONDS      = 10
// Stop notice and run booking (2.4.1). A real plug answers OFF asynchronously (about 450 ms on
// Oct 2), so the "stop requested" notice waits this long for the OFF report before it speaks.
@Field static final int        STOP_CONFIRM_SECONDS         = 4
// A run within this much of its plan (ON report to OFF report) is booked at the planned volume;
// report timing jitter is not worth a correction.
@Field static final long       RUN_MATCH_TOLERANCE_MS       = 3000L
// An OFF confirmed later than this after the planned stop latches a stop-overrun review fault.
@Field static final int        STOP_OVERRUN_FAULT_SECONDS   = 30
// 2.4.2: an unchanged ON report during a stop that is already being enforced is logged at most this
// often. The profiler's 3 s refresh produces one per poll while a stuck relay stays on.
@Field static final int        REPEATED_ON_LOG_SECONDS      = 60
// 2.4.2: the value a numeric tile attribute takes once the app no longer knows it (tank not
// initialised, FC or target missing, runway still learning), so nothing keeps showing a stale number.
// It is below every floor of the Waterguru-Grafana-Chart collector, which drops it instead of
// storing it (its tests pin -1 for tankPercent and freeChlorine).
@Field static final BigDecimal UNKNOWN_TELEMETRY            = -1G
// 2.4.2: how many recent dose-history entries the "void a recorded dose" control offers.
@Field static final int        VOID_CHOICES_MAX             = 10
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

            // 2.4.2: corrections for any install. Each value is saved on change, the line under it says
            // what the button will do, and the button applies it once. Neither control can run the
            // pump or change today's dose total.
            paragraph "<b>Correct the estimate</b> (never runs the pump and never changes today's dose total)"
            input "tankAdjustMl", "decimal",
                title: "Adjust tank inventory by (mL, signed: positive adds, negative removes)",
                required: false, submitOnChange: true
            String adjustPreview = tankAdjustmentPreview()
            if (adjustPreview) paragraph adjustPreview
            input name: "btnTankAdjust", type: "button", title: "Apply tank adjustment"
            Map voidChoices = voidDoseChoices()
            if (voidChoices) {
                input "voidDoseKey", "enum",
                    title: "Void a recorded dose that never ran",
                    options: voidChoices, required: false, submitOnChange: true
                String voidPreview = voidDosePreview()
                if (voidPreview) paragraph voidPreview
                input name: "btnVoidDose", type: "button", title: "Void the selected dose"
            }
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

            paragraph startConfirmHelp()
            input "startPowerMinWatts", "decimal",
                title: "Minimum running power (W) to confirm a start (0 or blank uses 3 W)",
                defaultValue: 3, required: false
            input "startConfirmTimeoutSeconds", "decimal",
                title: "How long to wait for start evidence (seconds, never past the planned stop)",
                defaultValue: 20, required: false
            input "powerLossGraceSeconds", "decimal",
                title: "How long a confirmed run may lose power before it is stopped (seconds)",
                defaultValue: 30, required: false

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
            if (state.startFault) {
                input name: "btnAckFault", type: "button",
                    title: "Acknowledge pump fault (allows future doses; does not run the pump)"
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

/**
 * Short, user-facing explanation of how a start is confirmed. The switch is detected
 * automatically: if it reports power, power is required; otherwise a fresh switch reading is used
 * with an explicit note that power and flow are unverified.
 */
private String startConfirmHelp() {
    if (pumpReportsPower()) {
        return "This switch reports power, so a start is confirmed only when a fresh ON report is " +
               "accompanied by fresh power at or above the minimum (default ${n1(DEFAULT_START_POWER_MIN_WATTS)} W). " +
               "A reading from before the ON request never confirms a start."
    }
    return "This switch does not report power, so a fresh ON report confirms a start. " +
           "Power and flow cannot be verified. Readings from before the ON request never confirm a start."
}

/** True when a start must be backed by a fresh power reading. Power-capable => always required. */
private boolean powerConfirmationRequired() {
    return pumpReportsPower()
}

/**
 * Whether the selected switch reports electrical power. Capability/attribute detection only:
 * a null power VALUE on a power-capable device must fail closed, not fall back to switch-only.
 */
private boolean pumpReportsPower() {
    if (!pumpSwitch) return false
    try { return pumpSwitch.hasAttribute("power") } catch (Throwable ignored) { }
    try { return pumpSwitch.hasCapability("PowerMeter") } catch (Throwable ignored) { }
    return false
}

/** The minimum running power, always strictly positive so 0 W can never confirm a start. */
private BigDecimal startPowerMinWatts() {
    BigDecimal v = firstNum(startPowerMinWatts, DEFAULT_START_POWER_MIN_WATTS)
    return (v == null || v <= 0G) ? DEFAULT_START_POWER_MIN_WATTS : v
}

private int startTimeoutSeconds() {
    BigDecimal v = firstNum(startConfirmTimeoutSeconds, DEFAULT_START_TIMEOUT_SECONDS)
    if (v == null || v <= 0G) return DEFAULT_START_TIMEOUT_SECONDS
    return Math.max(1, Math.round(v.doubleValue()) as Integer)
}

private int powerLossGraceSeconds() {
    BigDecimal v = firstNum(powerLossGraceSeconds, DEFAULT_POWER_LOSS_GRACE_SECONDS)
    if (v == null || v <= 0G) return DEFAULT_POWER_LOSS_GRACE_SECONDS
    return Math.max(1, Math.round(v.doubleValue()) as Integer)
}

private String dosingStatusHtml() {
    String sw = pumpSwitch ? (pumpSwitch.currentValue("switch") ?: "unknown") : "not selected"
    Map active = state.activeDose instanceof Map ? state.activeDose : null
    String activeText
    if (active == null && state.faultStopAt != null) {
        activeText = "STOPPING — recovering a stop after a fault; waiting for a fresh OFF report"
    } else if (active == null && state.idleStopAt != null) {
        activeText = "STOPPING: an OFF was requested; waiting for the switch to confirm it"
    } else if (active == null) {
        activeText = "idle"
    } else if (active.fault != null) {
        activeText = "FAULT (${active.fault}) — stop protection stays armed until the switch confirms off"
    } else if (active.startConfirmed == true) {
        activeText = "RUNNING — start confirmed; ${active.ml} mL; scheduled stop ${new Date((active.stopAt ?: 0L) as Long)}"
    } else {
        activeText = "STARTING — waiting for a fresh ON report before it is reported as running; ${active.ml} mL planned"
    }
    String pending = state.pendingDose ? "${state.pendingDose.ml} mL / ${formatDuration((state.pendingDose.seconds ?: 0) as Integer)} from sample ${state.pendingDose.sample}" : "none"
    String basis = state.lastDose?.observed == true ? "booked from the observed ON-to-OFF run time, not measured delivery" : "planned estimate, not measured delivery"
    String last = state.lastDose ? "${state.lastDose.ml} mL on ${new Date((state.lastDose.time ?: 0) as Long)} (${basis})" : "none"
    String fault = state.startFault ? "<b>UNACKNOWLEDGED: ${state.startFault.reason}</b> — press <i>Acknowledge pump fault</i> when reviewed (does not run the pump, and the attempt stays reserved)" : "none"
    String confirm = powerConfirmationRequired()
        ? "fresh ON report + power ≥ ${n1(startPowerMinWatts())} W (within ${startTimeoutSeconds()}s, never past the planned stop)"
        : "fresh ON report only (this switch does not report power) — power and flow are unverified"
    String circulation = circulationSwitch ? "${circulationSwitch.displayName} (${circulationSwitch.currentValue('switch') ?: 'unknown'})" :
                         (circulationAlwaysOn == true ? "confirmed continuous (24/7)" : "not confirmed")
    return "Pump: <b>${pumpSwitch?.displayName ?: 'not selected'}</b> (${sw}) · controller: <b>${activeText}</b><br>" +
           "Start confirmation: <b>${confirm}</b><br>" +
           "Pump fault: <b>${fault}</b><br>" +
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
    // 2.4.2: rebuild the subscriptions and the job queue FIRST, then stop. initialize() clears every
    // job and restores protection only for the stops it knows about, so stopping before it threw away
    // the retry and the cutoff of a run the app was not tracking (a manual run), and a lost OFF then
    // left the pump on with nothing scheduled to stop it. The stop must happen even if initialize()
    // fails part way: its first step already cleared the queue.
    unsubscribe()
    try {
        initialize()
    } catch (e) {
        log.error "WaterGuru Dosing Advisor: initialize failed while saving (${e.message}); stopping the pump anyway. Save the app again once the cause is fixed."
    }
    safeStopPump("configuration changed")
}

def uninstalled() {
    stopForRemoval()
    unsubscribe()
    clearScheduledJobs("uninstalled")
    removeTileDevice()
}

/**
 * The one OFF a removal can send (2.4.2). Removal deletes every job and subscription with the app, so
 * no retry, cutoff or deferred check can follow, and the notice must not promise one (safeStopPump's
 * notices do). It speaks only when something was open or the switch last reported ON.
 */
private void stopForRemoval() {
    if (!pumpSwitch) return
    boolean open = state.activeDose != null || state.faultStopAt != null || state.idleStopAt != null
    Map sw = readDeviceReading("switch")
    boolean reportsOn = sw.ok == true && sw.value?.toString() == "on"
    try { pumpSwitch.off() }
    catch (e) { log.error "WaterGuru Dosing Advisor: the OFF command on removal failed: ${e.message}" }
    if (!open && !reportsOn) return
    String last = reportsOn ? ", which last reported ON" : ""
    String msg = "This pool advisor was removed while the chlorine pump was not confirmed off. OFF was sent to ${pumpSwitch.displayName ?: 'the pump'}${last}, " +
                 "but the removed app can no longer confirm it, retry it or run its emergency cutoff. Check the pump."
    log.warn "WaterGuru Dosing Advisor: ${msg}"
    sendPumpNotice(msg)
}

/**
 * Clear every scheduled job AND the record of what was scheduled.
 *
 * unschedule() alone is not enough. state.emergencyDeadline survives it, and a surviving deadline
 * that still reads as "armed" is how protection came to depend on a timestamp instead of a timer:
 * after a queue reset the app believed a cutoff was in place while the queue was empty. Anything
 * that clears the queue goes through here so the two cannot disagree.
 */
private void clearScheduledJobs(String why) {
    unschedule()
    state.remove("emergencyJobScheduled")
    logDebug "Cleared every scheduled job (${why})"
}

def initialize() {
    clearScheduledJobs("initialize")   // digest, sample-delay, dose and watchdog jobs are rebuilt below

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
        // Preserve repeated equal reports: freshness measures receipt, not a change in watts or
        // switch value. Hubitat otherwise filters unchanged events and leaves their dates stale.
        subscribe(pumpSwitch, "switch", "pumpSwitchHandler", [filterEvents: false])
        if (pumpReportsPower()) subscribe(pumpSwitch, "power", "pumpSwitchHandler", [filterEvents: false])
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

    // unschedule() at the top of this method clears EVERY job, including an armed cutoff or a
    // pending stop verification. A configuration change must not be the reason the last backstop
    // disappears, so if a dose is still recorded as active, put the protection back before
    // anything else assumes the pump is safe.
    if (state.activeDose != null) {
        Map active = state.activeDose instanceof Map ? state.activeDose : null
        log.warn "WaterGuru Dosing Advisor: initialize found a dose still recorded as active; re-arming stop protection"
        armEmergencyPumpCutoff("initialize found a dose still recorded as active")
        runIn(STOP_RETRY_SECONDS, "verifyPumpOff", [overwrite: true])
        // A start that was still being confirmed loses its verification timer in the queue reset.
        // Recreate it (the deadline and the reservation live in state, not in the job) unless the
        // attempt has already faulted, in which case only the stop/backstop jobs above apply.
        if (active != null && active.startConfirmed != true && active.fault == null) {
            runIn(START_CHECK_SECONDS, "verifyStartConfirmation", [overwrite: true])
            log.warn "WaterGuru Dosing Advisor: recreated the start-confirmation check after the queue reset"
        }
        // 2.4.2: not once an OFF has been requested: the watch would read the falling power of the
        // stop itself and latch a false power loss.
        if (active != null && active.startConfirmed == true && active.offRequestedAt == null && powerConfirmationRequired()) {
            runIn(RUN_POWER_CHECK_SECONDS, "verifyRunPower", [overwrite: true])
            log.warn "WaterGuru Dosing Advisor: recreated the running power watch after the queue reset"
        }
    } else if (state.startFault != null && state.faultStopAt != null) {
        // A standalone fault recovery (a late ON after the dose was cleaned up) has no activeDose,
        // so the block above would miss it and the queue reset would silently drop its stop jobs.
        // Recreate the stop verification and re-arm the ORIGINAL emergency deadline. Do NOT
        // recreate the start-confirmation or power-watch jobs: this is stop-only.
        log.warn "WaterGuru Dosing Advisor: initialize found a standalone fault recovery; re-arming stop protection"
        armEmergencyPumpCutoff("initialize found a standalone fault recovery")
        runIn(STOP_RETRY_SECONDS, "verifyPumpOff", [overwrite: true])
    } else if (state.idleStopAt != null) {
        // 2.4.2: a stop with nothing open (STOP on an idle app, an earlier save) that is still
        // unconfirmed keeps its retry and its cutoff too.
        log.warn "WaterGuru Dosing Advisor: initialize found an unconfirmed stop; re-arming stop protection"
        armEmergencyPumpCutoff("initialize found an unconfirmed stop")
        runIn(STOP_RETRY_SECONDS, "verifyPumpOff", [overwrite: true])
    }
    // The deferred notices lost their jobs in the queue reset too. Recreate them while a stop is
    // still open; with nothing open there is nothing left to say. A stop notice that was already sent
    // only waits for the OFF, to give the final word, so its check is not run a second time.
    boolean stopOpen = state.activeDose != null || state.faultStopAt != null || state.idleStopAt != null
    if (state.pendingStopNotice != null) {
        if (!stopOpen) state.remove("pendingStopNotice")
        else if (state.pendingStopNotice.announced != true) runIn(STOP_CONFIRM_SECONDS, "verifyStopRequest", [overwrite: true])
    }
    if (state.pendingEmergencyNotice != null) {
        if (stopOpen) runIn(STOP_CONFIRM_SECONDS, "verifyEmergencyNotice", [overwrite: true])
        else state.remove("pendingEmergencyNotice")
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
    String today = nowDate().format("yyyy-MM-dd", location.timeZone)
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
        case "btnAckFault":  acknowledgePumpFault(); break
        case "btnTankAdjust": applyTankAdjustment(); break
        case "btnVoidDose":  voidRecordedDose(); break
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
        // 2.4.2: the reading is still known; the target is not (the tile shows -1 for it).
        return [text: out.join("\n"), anyAction: false, status: "YELLOW",
                headline: "Set pool volume", fcSummary: null, fcVal: fc, fcTarget: null,
                sampled: sampled, label: label]
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
    Long requestedAt = now()
    Long stopAt = requestedAt + (seconds * 1000L)
    Long startDeadline = Math.min(requestedAt + (startTimeoutSeconds() * 1000L), stopAt)

    // Reserve the attempt BEFORE the relay is asked to move. The sample, the planned volume and
    // the daily total are SAFETY accounting, not telemetry: a start that is never confirmed must
    // still consume them, or a failed attempt would make another automatic dose eligible and
    // could let the real total cross the daily cap. Success telemetry (lastDose) and tank
    // inventory are deliberately NOT touched here.
    reserveDoseAttempt(ml, sample, seconds)

    // Establish the stop job and state before energizing the physical output. The dose is active
    // but UNCONFIRMED: the relay may have energised, so it is tracked and stopped exactly like a
    // running dose until the switch confirms off.
    Map active = [attemptId: nextAttemptId(),
                  ml: n0(ml), mlRaw: ml.toString(), seconds: seconds,
                  sample: sample, started: requestedAt, stopAt: stopAt,
                  requestedAt: requestedAt, startDeadline: startDeadline,
                  startConfirmed: false, fault: null,
                  lastRefreshAt: null,
                  fcVal: result.fcVal?.toString(), fcTarget: result.fcTarget?.toString(),
                  trigger: trigger]
    state.activeDose = active

    runIn(seconds, "stopDose", [overwrite: true])
    runIn(seconds + 15, "verifyPumpOff", [overwrite: true])
    armEmergencyPumpCutoff("app-started dose")
    runIn(START_CHECK_SECONDS, "verifyStartConfirmation", [overwrite: true])

    try {
        pumpSwitch.on()
    } catch (e) {
        // A failed ON says nothing about whether the relay energised: the command can throw AFTER
        // the contactor closed. So this is a stop, not a tidy-up -- it goes through the same
        // confirmed-OFF path as every other stop. The old handler cancelled all three stop jobs,
        // cleared activeDose and left emergencyJobScheduled=true, which has two failures in it:
        // the orphaned flag makes the next arm believe a cutoff is pending (so no timer is ever
        // recreated), and a pump that did energise is left running with no dose recorded and
        // nothing scheduled to stop it.
        active.fault = "start-command-error"
        state.activeDose = active
        latchStartFault("start-command-error", "the ON command reported an error: ${e.message}", active)
        String msg = "Chlorine pump failed to start: ${e.message}. The relay may still have energised; requesting OFF and keeping the independent cutoff armed. The ${n0(ml)} mL attempt stays reserved."
        log.error "WaterGuru Dosing Advisor: ${msg}"
        sendPumpNotice(msg)
        safeStopPump("the ON command reported an error", false)
        return
    }

    state.remove("pendingDose")
    String msg = "Chlorine pump start requested: ${n0(ml)} mL for ${formatDuration(seconds)} at ${n1(firstNum(pumpRateMlPerMin, DEFAULT_PUMP_RATE_ML_MIN))} mL/min (${trigger}). Waiting for a fresh reading to confirm the pump is actually running before reporting success."
    log.warn "WaterGuru Dosing Advisor: ${msg}"
    sendPumpNotice(msg)
}

// ---------------------------------------------------------------------------
// Start confirmation and running power watch (2.4.0)
// ---------------------------------------------------------------------------

/**
 * Bounded asynchronous check that the pump actually started.
 *
 * Confirmation never comes from on() returning, nor from a reading that predates the ON request.
 * For a power-capable switch it takes BOTH a fresh ON and fresh power at or above the minimum; a
 * cached value, a null value or a throwing read fails closed. A plain switch is confirmed by a
 * fresh ON with an explicit note that power/flow are unverified. This reschedules itself until
 * the deadline (bounded by the planned stop), then fails the start safely. There is no blocking
 * sleep and no automatic retry of the ON command.
 */
def verifyStartConfirmation() {
    Map active = state.activeDose instanceof Map ? state.activeDose : null
    if (active == null || active.startConfirmed == true || active.fault != null) return
    if (startStopRequested(active)) return   // an OFF was requested; a late report must not confirm

    Long nowMs = now()
    boolean wantPower = powerConfirmationRequired()
    Map sw = readDeviceReading("switch")
    boolean switchOnFresh = readingFresh(sw, active.requestedAt) && sw.value?.toString() == "on"
    Map pw = wantPower ? readDeviceReading("power") : null
    boolean powerFresh = wantPower && readingFresh(pw, active.requestedAt)
    BigDecimal watts = wantPower ? toBD(pw.value) : null
    // Note what the device showed even when it is too late to confirm anything: a run that did
    // happen is counted once its OFF is confirmed (bookObservedRun), it just never becomes a start.
    boolean noted = false
    if (switchOnFresh) noted = noteRunOn(active, sw.at as Long, active.requestedAt as Long) || noted
    if (powerFresh) noted = notePowerSeen(active, pw.at as Long, watts, active.requestedAt as Long) || noted
    if (noted) state.activeDose = active

    Long deadline = active.startDeadline as Long
    // The deadline is checked before anything is confirmed. A late callback whose evidence only
    // appeared after the start window (or after the planned stop) must not book and announce a
    // success, even if the device state now happens to look right.
    if (deadline != null && nowMs >= deadline) {
        String why = wantPower
            ? "the start window ended before a fresh ON report and power at or above ${n1(startPowerMinWatts())} W were both seen"
            : "the start window ended before a fresh ON report was seen"
        failDoseStart(active, why)
        return
    }

    if (wantPower) {
        if (switchOnFresh && powerFresh && watts != null && watts > 0G && watts >= startPowerMinWatts()) {
            confirmDoseStart(active, "a fresh ON report plus ${n1(watts)} W", pw.at, sw.at)
            return
        }
    } else if (switchOnFresh) {
        confirmDoseStart(active, "a fresh ON report (power and flow unverified)", null, sw.at)
        return
    }

    requestPumpRefresh(active)
    runIn(START_CHECK_SECONDS, "verifyStartConfirmation", [overwrite: true])
}

/**
 * A reading is usable only when it has a real value and a real timestamp that is not in the
 * future and, when a start anchor is supplied, is not older than that anchor. A missing/null/
 * throwing read fails closed rather than counting as evidence.
 */
private boolean readingFresh(Map r, Long sinceMs) {
    if (r == null || r.ok != true || r.at == null) return false
    if (futureDated(r.at)) return false   // a future-dated reading is not evidence
    if (sinceMs != null && r.at < sinceMs) return false
    return true
}

/**
 * One strict timestamp rule everywhere: a reading is unusable when it is dated in the future.
 * Hubitat device events are stamped on the same host clock as the app, so there is no skew to
 * forgive -- an invented allowance is exactly what let a future OFF disarm a running pump.
 */
private boolean futureDated(Long at) {
    return at != null && at > now()
}

/**
 * Read one attribute together with the timestamp the device attached to it. A throwing read is
 * caught and reported as not-ok, so it can never be mistaken for a fresh value.
 */
private Map readDeviceReading(String attr) {
    Map out = [value: null, at: null, ok: false, error: null]
    if (!pumpSwitch) return out
    try {
        def st = null
        try { st = pumpSwitch.currentState(attr, true) } catch (Throwable ignored) { st = null }
        if (st != null) {
            out.value = st.value
            out.at = stateDateToMs(st.date)
        } else {
            out.value = pumpSwitch.currentValue(attr)
        }
        out.ok = out.value != null
    } catch (e) {
        out.error = e.message
    }
    return out
}

private Long stateDateToMs(def d) {
    if (d == null) return null
    if (d instanceof Date) return d.time
    if (d instanceof Number) return d as Long
    return toEpochMs(d)
}

private Long eventDateMs(def evt) {
    if (evt == null) return null
    try { return stateDateToMs(evt.date) } catch (Throwable ignored) { return null }
}

/** Ask a supporting device to refresh, throttled to roughly 10 s during an active run. */
private void requestPumpRefresh(Map active) {
    if (pumpSwitch == null) return
    Long nowMs = now()
    Long last = active.lastRefreshAt as Long
    if (last != null && (nowMs - last) < (START_REFRESH_THROTTLE_SECONDS * 1000L)) return
    active.lastRefreshAt = nowMs
    state.activeDose = active
    try {
        boolean supported = false
        try { supported = pumpSwitch.hasCommand("refresh") } catch (Throwable ignored) { supported = false }
        if (supported) pumpSwitch.refresh()
    } catch (e) {
        // A refresh that throws must not abort start verification or strand stop protection.
        log.error "WaterGuru Dosing Advisor: pump refresh failed — ${e.message}"
    }
}

/**
 * Record a CONFIRMED start exactly once. The attempt was already reserved before ON, so only the
 * success telemetry and the tank draw happen here. Idempotency is enforced against the CURRENT
 * state record by attempt id, so a stale handler map can never draw the tank twice.
 */
private void confirmDoseStart(Map active, String evidence, Long powerAt, Long onAt = null) {
    Map current = state.activeDose instanceof Map ? state.activeDose : null
    if (current == null || current.attemptId == null || current.attemptId != active.attemptId) return
    if (current.startConfirmed == true || current.booked == true) return
    current.startConfirmed = true
    current.booked = true
    current.confirmedAt = now()
    current.powerLowSince = null
    current.lastPowerReportAt = (powerAt != null && !futureDated(powerAt)) ? powerAt : now()
    // The ON report starts the observed run that is booked when the OFF report ends it (2.4.1).
    noteRunOn(current, onAt ?: (current.requestedAt as Long), current.requestedAt as Long)
    state.activeDose = current
    unschedule("verifyStartConfirmation")
    // Safety monitoring must survive optional dashboard or notification failures.
    scheduleRunPowerWatch(current)

    recordConfirmedDose(current)
    try {
        // 2.4.2: forced dose events, so a dose of the same volume as the last one still reaches the historian.
        publishTileTelemetry([fcVal: toBD(current.fcVal), fcTarget: toBD(current.fcTarget)], null, true)
    } catch (e) {
        log.error "WaterGuru Dosing Advisor: start confirmed but dashboard telemetry failed — ${e.message}"
    }
    String msg = "Chlorine pump started: ${current.ml} mL for ${formatDuration((current.seconds ?: 0) as Integer)} " +
                 "at ${n1(firstNum(pumpRateMlPerMin, DEFAULT_PUMP_RATE_ML_MIN))} mL/min (${current.trigger}). " +
                 "Start confirmed by ${evidence}. The volume is the planned estimate, not measured delivery. ${tankSummaryPlain()}"
    log.warn "WaterGuru Dosing Advisor: ${msg}"
    sendPumpNotice(msg)
}

/**
 * A start that was not confirmed. Latches the fault (once) and requests OFF through the existing
 * stop safety path, which keeps the cutoff armed and retries until the switch freshly reports OFF.
 * The operator acknowledgement is required before the fault notice clears. Nothing is refunded or
 * retried automatically.
 */
private void failDoseStart(Map active, String reason) {
    Map current = state.activeDose instanceof Map ? state.activeDose : null
    if (current == null || current.attemptId == null || current.attemptId != active.attemptId) return
    unschedule("verifyStartConfirmation")
    alertStartUnconfirmed(current, reason)
    safeStopPump("the start was not confirmed", false)
}

/**
 * Mark an unconfirmed attempt as faulted, once. Returns true only for the call that actually
 * latched it, so callers can send the single start-fault alert without duplicates.
 */
private boolean markStartUnconfirmed(Map active, String reason) {
    if (active == null || active.startConfirmed == true) return false
    if (active.fault != null) return false
    active.fault = "start-unconfirmed"
    state.activeDose = active
    latchStartFault("start-unconfirmed", reason, active)
    return true
}

/** Send the one start-fault alert, if this call is the one that latched the fault. */
private void alertStartUnconfirmed(Map active, String reason) {
    if (!markStartUnconfirmed(active, reason)) return
    String msg = "Chlorine pump start NOT confirmed: ${reason}. Requesting OFF; the independent cutoff stays armed until the switch freshly reports OFF. " +
                 "The ${active.ml} mL attempt stays reserved and is not counted as delivered."
    log.error "WaterGuru Dosing Advisor: ${msg}"
    sendPumpNotice(msg)
}

/** Record the latch that keeps a fault visible until the operator acknowledges it. */
private void latchStartFault(String kind, String reason, Map active) {
    // offFrom is the oldest OFF report that may acknowledge this fault: one belonging to the stop
    // effort the fault belongs to (its OFF request, or the ON request before one exists). A fault
    // latched AT the confirmed OFF (an early or unconfirmed stop, a stop-overrun) would otherwise
    // demand an OFF newer than the one that ended the run, and a plug that stays off never sends one.
    Long offFrom = active != null ? ((active.offRequestedAt ?: active.requestedAt) as Long) : null
    state.startFault = [kind: kind, at: now(), reason: reason,
                        attemptId: active?.attemptId,
                        ml: active?.ml, sample: active?.sample, stopAt: active?.stopAt,
                        offFrom: offFrom ?: now()]
    state.remove("faultStopAlerted")
}

/** Monotonic id so an idempotent booking can be tied to one attempt. */
private String nextAttemptId() {
    Long seq = ((state.attemptSeq ?: 0L) as Long) + 1L
    state.attemptSeq = seq
    return "att-${seq}"
}

private boolean startUnconfirmed(Map active) {
    return active != null && active.startConfirmed == false
}

/** True once an OFF has been requested for an attempt that was never confirmed. */
private boolean startStopRequested(Map active) {
    return active != null && active.startConfirmed != true && active.offRequestedAt != null
}

/**
 * The timestamp an OFF must postdate to count as belonging to the current stop effort. It covers
 * both a tracked dose (offRequestedAt, else the ON request) and a fault recovery that has no
 * activeDose at all (a late ON after the dose was already cleaned up). Without the latter, a stale
 * OFF delivered while the switch reports ON would clear the new recovery jobs.
 */
private Long stopEvidenceAnchor(Map active) {
    if (active != null) return (active.offRequestedAt ?: active.requestedAt) as Long
    if (state.startFault != null && state.faultStopAt != null) return state.faultStopAt as Long
    // 2.4.2: a stop requested with nothing open has its own anchor (safeStopPump).
    return state.idleStopAt instanceof Number ? (state.idleStopAt as Long) : null
}

/**
 * Remember the receipt time of an OFF report that passed the anchor checks (2.4.1).
 *
 * Hubitat stores a report whose value did not change as an event with a fresh date, but it does NOT
 * advance currentState('switch').date. Measured on the live hub on 2026-10-04: three "off" reports
 * answering OFF commands to an idle plug while the stored date stayed at 2026-10-04T02:48:31Z. A plug
 * that is already off therefore answers every OFF without ever producing a fresh state date, and the
 * fault acknowledgement waited for one forever. The recorded time is evidence only while the switch
 * still reads off: any ON in between changes the value and so re-dates the state past it.
 */
private void noteOffEvidence(Long at) {
    if (at == null || futureDated(at)) return
    Map sw = readDeviceReading("switch")
    if (sw.ok == true && sw.value?.toString() == "on") return   // the current state contradicts it
    Long prior = offEvidenceAt()
    if (prior == null || at > prior) state.lastOffEvidenceAt = at
}

/** The recorded OFF receipt time; never usable if it reads as future (a hub clock stepped back). */
private Long offEvidenceAt() {
    Long at = state.lastOffEvidenceAt instanceof Number ? (state.lastOffEvidenceAt as Long) : null
    return futureDated(at) ? null : at
}

private Long latestOf(Long a, Long b) {
    if (a == null) return b
    if (b == null) return a
    return Math.max(a, b)
}

/**
 * Record the first ON report that belongs to a run: not before `floor` (the ON request) and not in
 * the future. Returns true when the run map changed, so the caller can persist it.
 */
private boolean noteRunOn(Map run, Long at, Long floor) {
    if (run == null || at == null || futureDated(at) || (floor != null && at < floor)) return false
    Long prior = run.onReportAt instanceof Number ? (run.onReportAt as Long) : null
    if (prior != null && prior <= at) return false
    run.onReportAt = at
    return true
}

/** Record the first power reading at or above the minimum that belongs to a run (see noteRunOn). */
private boolean notePowerSeen(Map run, Long at, BigDecimal watts, Long floor) {
    if (run == null || at == null || futureDated(at) || (floor != null && at < floor)) return false
    if (watts == null || watts <= 0G || watts < startPowerMinWatts()) return false
    Long prior = run.powerSeenAt instanceof Number ? (run.powerSeenAt as Long) : null
    if (prior != null && prior <= at) return false
    run.powerSeenAt = at
    return true
}

private void scheduleRunPowerWatch(Map active) {
    if (!powerConfirmationRequired()) return
    runIn(RUN_POWER_CHECK_SECONDS, "verifyRunPower", [overwrite: true])
}

/**
 * While a power-confirmed run is active, watch for loss of power or missing fresh power evidence.
 * There are two independent bounds, each referenced to a real observation:
 *   - a LOW reading starts a clock at the first low power reading and aborts after the grace;
 *   - MISSING evidence is measured from the last usable power reading, so a device that stops
 *     reporting is aborted after the same grace, not after a second full grace.
 * The polling cadence is RUN_POWER_CHECK_SECONDS (10 s), well inside the 30 s default. A read or
 * refresh exception cannot strand the pump: the stop jobs and cutoff are already armed and the
 * watch keeps rescheduling until the bound is reached or the run ends.
 */
def verifyRunPower() {
    Map active = state.activeDose instanceof Map ? state.activeDose : null
    if (active == null || active.startConfirmed != true || active.fault != null) return
    // 2.4.2: once an OFF has been requested (STOP, a save, a power-loss abort) the stop lifecycle owns
    // the run. Power falling is then the expected result, and judging it here latched a false power loss.
    if (active.offRequestedAt != null) return
    if (!powerConfirmationRequired()) return
    Long stopAt = active.stopAt as Long
    if (stopAt != null && now() >= stopAt) return   // the stop lifecycle owns it now

    Long nowMs = now()
    Long graceMs = (powerLossGraceSeconds() as long) * 1000L
    Map pw = readDeviceReading("power")
    BigDecimal watts = toBD(pw.value)
    boolean usable = pw.ok == true && pw.at != null && !futureDated(pw.at)
    // 2.4.1: currentState('power').date only moves when the VALUE changes, so a steady pump reporting
    // the same 6.7 W keeps an old date while its reports keep arriving. The receipt time of the latest
    // power event (handlePumpPowerEvent) counts too; the value is still the stored one.
    Long seenAt = usable ? latestOf(pw.at as Long, active.lastPowerEventAt instanceof Number ? (active.lastPowerEventAt as Long) : null) : null
    boolean freshEvidence = usable && (nowMs - seenAt) <= graceMs

    if (freshEvidence) {
        // A fresh report: good power clears the low clock, low power starts one. The last-usable
        // timestamp is the single reference for the "missing reports" bound.
        if (active.lastPowerReportAt == null || seenAt > (active.lastPowerReportAt as Long)) active.lastPowerReportAt = seenAt
        if (watts != null && watts > 0G && watts >= startPowerMinWatts()) {
            active.powerLowSince = null
        } else {
            if (active.powerLowSince == null) active.powerLowSince = nowMs
            if (nowMs - (active.powerLowSince as Long) >= graceMs) {
                failRunPower(active, "power stayed below ${n1(startPowerMinWatts())} W")
                return
            }
        }
    } else {
        // No FRESH evidence (missing, unreadable, or older than the grace): one bound measured
        // from the last usable reading -- never a second, fresh grace on top of the first.
        Long anchor = (active.lastPowerReportAt ?: active.confirmedAt ?: active.requestedAt) as Long
        if (nowMs - anchor >= graceMs) {
            failRunPower(active, "no fresh power reading for ${formatDuration(powerLossGraceSeconds())}")
            return
        }
    }
    state.activeDose = active

    requestPumpRefresh(active)
    runIn(RUN_POWER_CHECK_SECONDS, "verifyRunPower", [overwrite: true])
}

private void failRunPower(Map active, String reason) {
    Map current = state.activeDose instanceof Map ? state.activeDose : null
    if (current == null || current.attemptId == null || current.attemptId != active.attemptId) return
    if (current.fault != null) return
    current.fault = "power-loss"
    state.activeDose = current
    unschedule("verifyRunPower")
    latchStartFault("power-loss", "Power was lost during a confirmed run: ${reason}", current)
    String msg = "Chlorine pump power LOST during a confirmed run (${reason}). Aborting safely; requesting OFF; the independent cutoff stays armed until the switch freshly reports OFF. " +
                 "The ${current.ml} mL attempt stays reserved and delivery is now UNCERTAIN — operator review required. Once OFF is confirmed the tank is booked from the observed ON-to-OFF run time, an upper bound on what ran."
    log.error "WaterGuru Dosing Advisor: ${msg}"
    sendPumpNotice(msg)
    safeStopPump("the confirmed run lost power", false)
}

def stopDose() {
    Map active = state.activeDose instanceof Map ? state.activeDose : null
    Long offAt = now()
    if (active != null) { active.offRequestedAt = offAt; state.activeDose = active }
    unschedule("verifyRunPower")
    unschedule("verifyStartConfirmation")

    // A scheduled stop firing before the start was confirmed terminates an UNCONFIRMED attempt.
    // It must latch the start fault and use the unverified accounting notice, not quietly clear
    // the dose and cancel the timeout job with no trace.
    if (startUnconfirmed(active)) {
        alertStartUnconfirmed(active, "the planned stop time arrived before the start was confirmed")
    }

    try {
        try { if (pumpSwitch) pumpSwitch.off() }
        catch (e) { log.error "WaterGuru Dosing Advisor: pump OFF command failed — ${e.message}" }

        // The switch has been ASKED to stop, which is not the same as stopped. Nothing is
        // disarmed and no dose is forgotten until it FRESHLY reports off: a relay that ignores
        // OFF is exactly the case the independent cutoff exists for, so cancelling it here on the
        // failure path would remove the last backstop at the moment it is most needed.
        Long since = stopEvidenceAnchor(active)
        if (pumpIsOff(since)) {
            finishStop(active ? scheduledStopMessage(active) : null)
            return
        }

        if (active) {
            requestStopRefresh(active)
            deferStopNotice("Chlorine pump stop requested after its scheduled ${formatDuration((active.seconds ?: 0) as Integer)} dose (${active.ml} mL planned)",
                            scheduledStopMessage(active))
        }
    } catch (e) {
        // Anything unexpected -- a device read throwing, a notification device failing -- must not
        // end this with nothing scheduled while the pump may still be running.
        log.error "WaterGuru Dosing Advisor: scheduled stop hit an unexpected error — ${e.message}; retrying"
    }

    state.stopAttempts = 0
    runIn(STOP_RETRY_SECONDS, "verifyPumpOff", [overwrite: true])
    armEmergencyPumpCutoff("scheduled stop not confirmed")
}

/**
 * A user-facing description of the switch's stop state that does not claim ON unless it is
 * actually known. `switchStatusPhrase()` reads the device again.
 */
private String switchStatusPhrase() {
    Map sw = readDeviceReading("switch")
    if (sw.ok == true && sw.value?.toString() == "on") return "the switch reports ON"
    return "its OFF has not been freshly confirmed"
}

/**
 * Hold the "stop requested" notice back for STOP_CONFIRM_SECONDS (2.4.1).
 *
 * off() returns before the plug answers: on Oct 2 the command went out at 19:48:38.297 and the OFF
 * report arrived at 38.745. Checking the switch straight after off() therefore announced every
 * healthy stop as "the switch reports ON. Retrying". The OFF report normally completes the stop
 * through pumpSwitchHandler well inside this window, and finishStop() discards the notice. Only the
 * notice moves: the retry job and the independent cutoff are armed by the caller exactly as before.
 * `confirmed` is the final notice if the switch is found off when the check runs.
 */
private void deferStopNotice(String requested, String confirmed) {
    state.pendingStopNotice = [requested: requested, confirmed: confirmed]
    runIn(STOP_CONFIRM_SECONDS, "verifyStopRequest", [overwrite: true])
}

/**
 * The deferred half of a stop request. It never sends a command and never disarms anything except
 * through finishStop() on a confirmed OFF, which is the same rule every other stop path follows.
 */
def verifyStopRequest() {
    Map pending = state.pendingStopNotice instanceof Map ? state.pendingStopNotice : null
    if (pending == null || pending.announced == true) return   // already said; it waits for the OFF
    state.remove("pendingStopNotice")
    Map active = state.activeDose instanceof Map ? state.activeDose : null
    try {
        if (pumpIsOff(stopEvidenceAnchor(active))) {
            finishStop(pending.confirmed?.toString())
            return
        }
        String msg = "${pending.requested}, but ${switchStatusPhrase()}. Retrying; the independent cutoff stays armed."
        log.warn "WaterGuru Dosing Advisor: ${msg}"
        sendPumpNotice(msg)
        // 2.4.2: keep a final word for the OFF that is still to come. Without it a stop that carries no
        // message of its own (STOP on an idle app, a save, a fault recovery) ended in silence when its
        // OFF landed after this notice, and "Retrying" was the last thing the user heard.
        state.pendingStopNotice = [confirmed: pending.confirmed ?: "${pending.requested}: the switch has now confirmed OFF.".toString(),
                                   announced: true]
    } catch (e) {
        log.error "WaterGuru Dosing Advisor: the stop notice check hit an unexpected error: ${e.message}"
    }
}

/**
 * Hold an EMERGENCY notice back for STOP_CONFIRM_SECONDS (2.4.2), exactly like deferStopNotice().
 *
 * The final retry and the cutoff checked the switch straight after their off(), so the notice went out
 * although the plug's OFF answer landed 0.5 to 1.6 s later. Only the notice moves: every command and
 * re-arm has already happened. `head` + the switch's status (when `withSwitch`) + `tail` is the text;
 * `confirmed` is the final word if the check finds the switch off.
 */
private void deferEmergencyNotice(String head, boolean withSwitch, String tail, String confirmed) {
    state.pendingEmergencyNotice = [head: head, withSwitch: withSwitch, tail: tail, confirmed: confirmed]
    runIn(STOP_CONFIRM_SECONDS, "verifyEmergencyNotice", [overwrite: true])
}

/** The deferred half of an EMERGENCY notice. Like verifyStopRequest, it never sends a command. */
def verifyEmergencyNotice() {
    Map pending = state.pendingEmergencyNotice instanceof Map ? state.pendingEmergencyNotice : null
    state.remove("pendingEmergencyNotice")
    if (pending == null) return
    Map active = state.activeDose instanceof Map ? state.activeDose : null
    try {
        if (pumpIsOff(stopEvidenceAnchor(active))) {
            finishStop(pending.confirmed?.toString())
            return
        }
        String status = pending.withSwitch == true ? " (${switchStatusPhrase()})" : ""
        String msg = "${pending.head}${status}. ${pending.tail}"
        log.error "WaterGuru Dosing Advisor: ${msg}"
        sendPumpNotice(msg)
    } catch (e) {
        log.error "WaterGuru Dosing Advisor: the EMERGENCY notice check hit an unexpected error: ${e.message}"
    }
}

/** Ask a supporting device to re-report during stop recovery, throttled. A refresh is a request,
 *  never evidence: stop confirmation still requires a fresh reading to arrive. */
private void requestStopRefresh(Map active) {
    if (pumpSwitch == null) return
    Long nowMs = now()
    Long last = (active != null ? active.lastRefreshAt : state.stopRefreshAt) as Long
    if (last != null && (nowMs - last) < (START_REFRESH_THROTTLE_SECONDS * 1000L)) return
    if (active != null) active.lastRefreshAt = nowMs else state.stopRefreshAt = nowMs
    try {
        boolean supported = false
        try { supported = pumpSwitch.hasCommand("refresh") } catch (Throwable ignored) { supported = false }
        if (supported) pumpSwitch.refresh()
    } catch (e) {
        log.error "WaterGuru Dosing Advisor: pump refresh failed — ${e.message}"
    }
}

/**
 * The final stop notice for a scheduled dose. If the start was never confirmed, the notice must
 * NOT claim a completed dose: the relay may have run, but nothing here proved the pump moved the
 * planned volume, so the attempt is left reserved and marked for operator review. The tank line is
 * appended by finishStop(), after the observed run has been booked (2.4.1).
 */
private String scheduledStopMessage(Map active) {
    if (startUnconfirmed(active)) {
        return "Chlorine pump stopped, but the start was never confirmed — no scheduled dose is claimed; the ${active.ml} mL attempt remains reserved and unreconciled for operator review."
    }
    return "Chlorine pump stopped after scheduled ${formatDuration((active.seconds ?: 0) as Integer)} dose (${active.ml} mL planned, an estimate not measured delivery)."
}

/**
 * Confirm the pump is actually off, retrying until it is.
 *
 * The retries are bounded so this cannot spin forever, but the bound applies to the retrying,
 * NOT to the safety net: the emergency cutoff is disarmed only on a confirmed-off reading. If
 * the bound is reached with the switch still reporting ON, a cutoff is (re-)armed and the notice
 * says plainly that the automation has lost its grip on the relay and it may need stopping by
 * hand. Reporting "stopped" here without checking is the bug this replaces.
 */
def verifyPumpOff() {
    if (!pumpSwitch) return
    Map active = state.activeDose instanceof Map ? state.activeDose : null
    if (startUnconfirmed(active)) {
        alertStartUnconfirmed(active, "the stop was being verified before the start was confirmed")
    }
    Long since = stopEvidenceAnchor(active)
    try {
        if (pumpIsOff(since)) {
            finishStop("WaterGuru Dosing Advisor: chlorine pump confirmed OFF")
            return
        }

        Integer attempt = ((state.stopAttempts ?: 0) as Integer) + 1
        state.stopAttempts = attempt
        try { pumpSwitch.off() }
        catch (e) { log.error "WaterGuru Dosing Advisor: retry ${attempt} OFF command failed — ${e.message}" }
        requestStopRefresh(active)

        if (pumpIsOff(since)) {
            finishStop("Chlorine pump stopped on retry ${attempt}.")
            return
        }

        if (attempt >= STOP_MAX_ATTEMPTS) {
            // Only claim a re-arm if one actually happened. With the watchdog off nothing is
            // scheduled, and saying otherwise is the same lie moved somewhere new.
            boolean armed = armEmergencyPumpCutoff("stop still unconfirmed after ${attempt} OFF attempts")
            String tail = armed
                ? "The independent cutoff is armed, but the pump may need to be stopped by hand."
                : "The independent cutoff is DISABLED (watchdogAnyPumpRun is off), so nothing will retry automatically — stop the pump by hand."
            log.error "WaterGuru Dosing Advisor: chlorine pump OFF not freshly confirmed after ${attempt} OFF attempts; the EMERGENCY notice waits ${STOP_CONFIRM_SECONDS}s for the OFF answer"
            // 2.4.2: the OFF sent just above is answered 0.5 to 1.6 s later on the live plug, so the
            // notice waits for that answer like the stop notice does.
            deferEmergencyNotice("EMERGENCY: chlorine pump OFF not freshly confirmed after ${attempt} OFF attempts", true, tail,
                                 "Chlorine pump stopped on retry ${attempt}.")
            return
        }
    } catch (e) {
        log.error "WaterGuru Dosing Advisor: pump verification hit an unexpected error — ${e.message}; retrying"
    }

    log.warn "WaterGuru Dosing Advisor: chlorine pump OFF not freshly confirmed; retrying in ${STOP_RETRY_SECONDS}s"
    runIn(STOP_RETRY_SECONDS, "verifyPumpOff", [overwrite: true])
    // Re-assert the backstop on EVERY unconfirmed outcome, not only at exhaustion. The cutoff can
    // be missing for reasons this method did not cause -- it was never armed, a queue reset dropped
    // it, the watchdog was switched on after the dose started -- and protection should not depend
    // on which path arrived here. armEmergencyPumpCutoff never postpones an earlier deadline.
    armEmergencyPumpCutoff("stop still unconfirmed (attempt ${state.stopAttempts})")
}

/**
 * True only when the switch POSITIVELY reports off.
 *
 * Deliberately not `!= "on"`: a device that reports nothing, or something unexpected, has not
 * told us the pump stopped, and treating silence as confirmation is how a stuck relay goes
 * unnoticed. An unreadable switch keeps the cutoff armed and keeps retrying, which is the safe
 * direction to fail in.
 *
 * A read that THROWS is handled here rather than left to propagate. Previously it escaped into
 * stopDose/verifyPumpOff/emergencyPumpOff, aborting them before they rescheduled anything -- and
 * for the cutoff, which the scheduler had already removed from the queue, that meant the last
 * backstop disappeared while a dose was still recorded as running.
 */
private boolean pumpIsOff(Long since = null) {
    if (!pumpSwitch) return true
    try {
        String value
        Long at = null
        def st = null
        try { st = pumpSwitch.currentState("switch", true) } catch (Throwable ignored) { st = null }
        if (st != null) {
            value = st.value?.toString()
            at = stateDateToMs(st.date)
        } else {
            value = pumpSwitch.currentValue("switch")?.toString()
        }
        if (value != "off") return false
        if (futureDated(at)) return false   // future-dated OFF
        // When a fresh OFF was requested, a reading that is undated or predates the request has
        // not confirmed anything: a cached pre-attempt OFF must never disarm the new backstop.
        // 2.4.1: an unchanged "off" report never re-dates the state, so the receipt time recorded by
        // noteOffEvidence() counts too. It was accepted against its own anchor when it arrived, and
        // while the switch still reads off it can only be newer than the state's own date.
        Long seen = latestOf(at, offEvidenceAt())
        if (since != null && (seen == null || seen < since)) return false
        return true
    } catch (e) {
        log.error "WaterGuru Dosing Advisor: could not read the pump switch state \u2014 ${e.message}"
        return false
    }
}

/**
 * Disarm everything and forget the dose. Called ONLY on a confirmed-off reading, so the safety
 * net is never removed on the strength of an unverified command. The latched start/power FAULT is
 * deliberately NOT cleared here -- it stays visible until the operator acknowledges it.
 */
private void finishStop(String msg) {
    Map active = state.activeDose instanceof Map ? state.activeDose : null
    // Every confirmed-OFF path comes here. Latch an unconfirmed attempt before discarding it,
    // including an OFF event arriving at the planned end before either timer has fired.
    alertStartUnconfirmed(active, "the pump reported OFF before the start was confirmed")
    if (active?.startConfirmed == true && active.fault != null) {
        String reason = active.fault == "emergency-stop" ? "the emergency cutoff" : active.fault.toString()
        msg = "Chlorine pump confirmed OFF after ${reason}. ${active.ml} mL was planned; delivery is UNCERTAIN. Review and acknowledge the pump fault before future dosing."
    }
    // A deferred "stop requested" notice is moot now, but its "stopped" text is the right final word
    // when the path that confirmed the OFF brought none of its own (an idle or recovery OFF report).
    Map pending = state.pendingStopNotice instanceof Map ? state.pendingStopNotice : null
    if (!msg && pending?.confirmed) msg = pending.confirmed.toString()
    // Book what actually ran while the run's ON/OFF evidence is still at hand (2.4.1). A booking
    // failure must not be able to skip the cleanup below.
    String booked = null
    try { booked = bookObservedRun(active) }
    catch (e) { log.error "WaterGuru Dosing Advisor: could not book the observed run: ${e.message}" }
    unschedule("stopDose")
    unschedule("verifyPumpOff")
    unschedule("emergencyPumpOff")
    unschedule("verifyStartConfirmation")
    unschedule("verifyRunPower")
    unschedule("verifyStopRequest")
    unschedule("verifyEmergencyNotice")
    state.remove("emergencyJobScheduled")
    state.remove("activeDose")
    state.remove("stopAttempts")
    state.remove("emergencyAttempts")
    state.remove("emergencyDeadline")
    // The stop effort is over: clear its anchor so a later unrelated OFF is judged on its own.
    state.remove("faultStopAt")
    state.remove("idleStopAt")
    state.remove("stopRefreshAt")
    state.remove("pendingStopNotice")
    state.remove("pendingEmergencyNotice")
    state.remove("repeatedOnLoggedAt")
    state.remove("faultRun")
    List parts = []
    if (msg) parts << msg
    if (booked) parts << booked
    // The tank line comes last, after the booking moved it. An unconfirmed attempt drew nothing
    // unless its observed run was booked just now.
    if (parts && (booked != null || (active != null && active.startConfirmed != false))) parts << tankSummaryPlain()
    if (parts) {
        String text = parts.join(" ")
        log.info "WaterGuru Dosing Advisor: ${text}"
        sendPumpNotice(text)
    }
}

/**
 * Stop the pump for a reason outside the dose schedule: the STOP button, a configuration change,
 * app removal.
 *
 * The same rule as a scheduled stop: the switch is ASKED, and it is not "stopped" until it says
 * so. The previous version unscheduled the dose, the verification AND the emergency cutoff,
 * cleared the active dose, and announced "pump stopped" unconditionally -- so a STOP press against
 * a stuck relay removed every protection at once and reported success.
 */
private void safeStopPump(String reason, boolean notify = false) {
    boolean wasActive = state.activeDose != null
    Map active = state.activeDose instanceof Map ? state.activeDose : null
    Long offAt = now()
    if (active != null) {
        active.offRequestedAt = offAt
        state.activeDose = active
    } else if (state.startFault != null) {
        // A fault recovery with no active dose (a late ON after cleanup) needs its own anchor: a
        // stale OFF dated before this moment must not be able to clear the new recovery jobs.
        if (state.faultStopAt == null) state.faultStopAt = offAt
    } else {
        // 2.4.2: nothing is open (STOP on an idle app, a save, a run the app did not start). With no
        // anchor any cached "off", even yesterday's, confirmed the stop at once, and the STOP button
        // said "stopped" before the plug had answered. Anchor it to this request like every other stop.
        state.idleStopAt = offAt
    }

    // The planned end of the dose is no longer wanted ...
    unschedule("stopDose")
    unschedule("verifyRunPower")
    unschedule("verifyStartConfirmation")
    // ... but the cutoff is deliberately NOT cancelled here: it is the backstop for this moment.

    // If the attempt was never confirmed, stopping it is itself a faulted termination: latch and
    // alert once, and keep the unverified accounting semantics, whatever the reason for the stop.
    if (startUnconfirmed(active)) {
        alertStartUnconfirmed(active, "the pump was stopped (${reason}) before the start was confirmed")
    }

    try {
        if (pumpSwitch) pumpSwitch.off()
    } catch (e) {
        log.error "WaterGuru Dosing Advisor: unable to send the stop command — ${e.message}"
    }

    String stopMsg
    if (active != null && startUnconfirmed(active)) {
        stopMsg = "Chlorine pump stopped; the start was never confirmed — no scheduled dose is claimed; the ${active.ml} mL attempt remains reserved."
    } else if (notify || wasActive) {
        stopMsg = "Chlorine pump stopped: ${reason}."
    } else {
        stopMsg = null
    }
    if (pumpIsOff(stopEvidenceAnchor(active))) {
        finishStop(stopMsg)
        return
    }

    requestStopRefresh(active)
    deferStopNotice("Chlorine pump stop requested (${reason})", stopMsg)
    state.stopAttempts = 0
    runIn(STOP_RETRY_SECONDS, "verifyPumpOff", [overwrite: true])
    armEmergencyPumpCutoff("stop unconfirmed (${reason})")
}

/**
 * Device reports trigger the same verifiers as timers. OFF evidence must pass the timestamp and
 * contradictory-ON checks before shared cleanup can release the active dose.
 */
def pumpSwitchHandler(evt) {
    String name = evt?.name?.toString() ?: "switch"
    String value = evt?.value?.toString()
    if (name == "power") { handlePumpPowerEvent(evt); return }
    if (value == "on")  { handlePumpOnEvent(evt);  return }
    if (value == "off") { handlePumpOffEvent(evt); return }
}

/**
 * A power event during a run is a fast path for the timer-driven checks, never a way to skip
 * them: it only triggers the same bounded verifiers, which read the timestamped state.
 */
private void handlePumpPowerEvent(evt) {
    Map active = state.activeDose instanceof Map ? state.activeDose : null
    Long evtAt = eventDateMs(evt)
    BigDecimal watts = toBD(evt?.value)
    if (active == null) {
        // A late ON during fault recovery is counted only if the pump drew power (2.4.1).
        Map run = state.faultRun instanceof Map ? state.faultRun : null
        if (run != null && notePowerSeen(run, evtAt ?: now(), watts, run.onReportAt as Long)) state.faultRun = run
        return
    }
    // Power that an unconfirmed attempt drew, even after it faulted, is evidence that it actually ran.
    // It is used only to count the observed run once OFF is confirmed, never to confirm a start.
    if (active.startConfirmed != true && notePowerSeen(active, evtAt ?: now(), watts, active.requestedAt as Long)) {
        state.activeDose = active
    }
    if (active.fault != null || startStopRequested(active)) return
    if (active.startConfirmed == false) {
        verifyStartConfirmation()
    } else if (active.startConfirmed == true && active.offRequestedAt == null && powerConfirmationRequired()) {
        // 2.4.1: the receipt time of every power report counts as freshness. An unchanged reading
        // arrives as an event with a fresh date but leaves currentState('power').date where it was,
        // so a steady pump looked silent and a healthy run was aborted as "power LOST". Only a dated
        // report from after confirmation that is not in the future counts.
        Long since = (active.confirmedAt ?: active.requestedAt) as Long
        if (evtAt != null && !futureDated(evtAt) && (since == null || evtAt >= since) &&
                (active.lastPowerEventAt == null || evtAt > (active.lastPowerEventAt as Long))) {
            active.lastPowerEventAt = evtAt
            state.activeDose = active
        }
        if (watts == null || watts < startPowerMinWatts()) verifyRunPower()
    }
}

/**
 * An ON report. While a start is still being confirmed it is only a hint: the verifier decides,
 * using a FRESH ON plus power. Once a start has faulted (or an OFF was requested), a late/replayed
 * ON must NOT reclassify it as a successful dose -- the fault and stop protection stay in place.
 * With no active attempt at all but an unacknowledged fault pending, this is a stop-only state: OFF
 * is sent immediately rather than arming a fresh full window and calling it a start.
 */
private void handlePumpOnEvent(evt) {
    Map active = state.activeDose instanceof Map ? state.activeDose : null
    // 2.4.2: Hubitat delivers a report whose value did not change with isStateChange false. A stuck
    // relay answers every refresh (the profiler polls every 3 s) with such an ON. A test event that
    // leaves the flag out is treated as a change, which is the conservative reading.
    boolean unchangedOn = evt?.isStateChange == false
    if (active != null) {
        if (active.startConfirmed == false) {
            // The first fresh ON report starts the observed run of an attempt that is still
            // unconfirmed, including a late ON after it faulted (2.4.1).
            if (noteRunOn(active, eventDateMs(evt) ?: now(), active.requestedAt as Long)) state.activeDose = active
            if (active.fault != null || startStopRequested(active)) {
                if (unchangedOn && active.offRequestedAt != null) {
                    // 2.4.2: the stop is already being enforced. Restarting it here sent a fresh OFF,
                    // reset the attempt count and pushed the verification back on every report: about
                    // 95 OFFs in 5 minutes and no EMERGENCY escalation, ever. The running verify,
                    // escalation and cutoff chain does the work.
                    noteRepeatedOnDuringStop("an aborted start")
                    return
                }
                log.warn "WaterGuru Dosing Advisor: pump reported ON after the start was aborted; keeping the fault and stop protection"
                stopOnlyForPendingFault("an aborted start is still unconfirmed")
            } else {
                verifyStartConfirmation()
            }
        }
        return
    }
    if (state.startFault != null) {
        Long evtAt = eventDateMs(evt)
        Long anchor = state.faultStopAt instanceof Number ? (state.faultStopAt as Long) : null
        Map cur = readDeviceReading("switch")
        boolean positivelyOff = cur.ok == true && cur.value?.toString() == "off" && !futureDated(cur.at)
        boolean staleOnEvent = evtAt != null && anchor != null && evtAt < anchor
        if (staleOnEvent && positivelyOff) {
            log.warn "WaterGuru Dosing Advisor: ignoring a stale ON superseded by newer recovery evidence"
            return
        }
        // Remember when this late ON began, so the run it caused is counted once OFF is confirmed.
        Map run = state.faultRun instanceof Map ? state.faultRun : [:]
        if (noteRunOn(run, evtAt ?: now(), null)) state.faultRun = run
        if (unchangedOn && anchor != null) {
            // 2.4.2: the same loop for a standalone recovery: its stop is already being enforced.
            noteRepeatedOnDuringStop("a fault recovery")
            return
        }
        // Advance the recovery evidence anchor to THIS observation, so an OFF reported before it
        // cannot confirm the recovery. This does not touch the emergency deadline, and arming
        // never postpones an earlier one.
        state.faultStopAt = now()
        log.warn "WaterGuru Dosing Advisor: pump reported ON while a fault is pending; treating it as stop-only"
        stopOnlyForPendingFault("an unacknowledged fault is pending")
        return
    }
    if (watchdogAnyPumpRun == true) {
        armEmergencyPumpCutoff("pump start outside this app")
    }
}

/**
 * A faulted attempt is a stop-only state: request OFF immediately and keep protection until a
 * fresh OFF arrives. Never arm a fresh full cut-off window as if this were a new commanded run.
 */
private void stopOnlyForPendingFault(String why) {
    if (state.faultStopAlerted != true) {
        state.faultStopAlerted = true
        sendPumpNotice("Chlorine pump reported ON while an unacknowledged fault is pending. Requesting OFF now; no new dose will start until the fault is acknowledged.")
    }
    safeStopPump(why, false)
}

/**
 * An unchanged ON report during a stop that is already being enforced (2.4.2). Nothing is sent and no
 * job is touched; the log line is throttled to one per REPEATED_ON_LOG_SECONDS. The user hears from the
 * stop chain itself: the deferred "stop requested" notice, the EMERGENCY escalation, the cutoff.
 */
private void noteRepeatedOnDuringStop(String what) {
    Long last = state.repeatedOnLoggedAt instanceof Number ? (state.repeatedOnLoggedAt as Long) : null
    if (last != null && now() - last < REPEATED_ON_LOG_SECONDS * 1000L) return
    state.repeatedOnLoggedAt = now()
    log.warn "WaterGuru Dosing Advisor: the switch still reports ON during the stop of ${what}; the stop already in progress keeps retrying and escalating (this line repeats at most every ${REPEATED_ON_LOG_SECONDS}s)"
}

/**
 * An OFF report. A device off is confirmed ONLY when it belongs to this attempt: either the
 * current switch state is freshly off at/after the attempt anchor (the ON request, or the OFF
 * request once one was sent), or the event itself is dated after that anchor. An older OFF event
 * is rejected when a newer ON state contradicts it, so a stale event cannot cancel protection
 * while the switch reports ON.
 */
private void handlePumpOffEvent(evt) {
    Map active = state.activeDose instanceof Map ? state.activeDose : null
    boolean wasActive = active != null
    // 2.4.2: a stop requested with nothing open (idleStopAt) is held to its anchor too.
    boolean recovering = wasActive || (state.startFault != null && state.faultStopAt != null) || state.idleStopAt != null
    Long anchor = stopEvidenceAnchor(active)
    Long evtAt = eventDateMs(evt)

    if (recovering && anchor != null) {
        Map cur = readDeviceReading("switch")
        Long curAt = cur.at
        boolean curOffFresh = cur.ok == true && cur.value?.toString() == "off" &&
                              curAt != null && curAt >= anchor && !futureDated(curAt)
        boolean evtFresh = evtAt != null && evtAt >= anchor && !futureDated(evtAt)
        boolean newerOnContradicts = cur.ok == true && cur.value?.toString() == "on" && curAt != null &&
                                      (evtAt == null || curAt >= evtAt)
        if (!curOffFresh && (!evtFresh || newerOnContradicts)) {
            // An old report is NOT evidence of a stop. Ignore it: do not manufacture a stop
            // attempt, latch a fault, or re-arm anything from it. The existing start/run/stop/
            // cutoff jobs (or an already-running recovery) continue unchanged.
            log.warn "WaterGuru Dosing Advisor: ignoring a stale or out-of-order OFF report; existing protection unchanged"
            return
        }
    }
    // The report passed the anchor checks. Keep its receipt time: if the value did not change, this
    // event is the ONLY trace of it, because the stored switch date does not move (2.4.1).
    noteOffEvidence(evtAt)

    Long stopAt = ((active?.stopAt ?: 0L) as Long)
    boolean early = wasActive && now() + 3000L < stopAt
    if (early) {
        // An early, unconfirmed stop terminates an unconfirmed attempt: latch and say so.
        String tail = ""
        if (startUnconfirmed(active)) {
            alertStartUnconfirmed(active, "the pump reported OFF before the start was confirmed")
            tail = " The start was never confirmed, so no scheduled dose is claimed."
        }
        finishStop("Chlorine pump stopped before the planned dose completed.${tail}")
    } else if (wasActive) {
        // A confirmed off at or after the planned stop is the normal end of a dose: the relay
        // simply reported its off a little late. finishStop() clears activeDose, so any duplicate
        // event or an off that follows synchronous cleanup is already idle and stays silent. If
        // the start was never confirmed, the notice says so and does not claim the dose ran.
        finishStop(scheduledStopMessage(active))
    } else {
        // No tracked dose, but a fresh OFF confirms a fault recovery's or an idle stop's effort.
        finishStop(null)
    }
}

/**
 * Arm the independent cutoff -- without moving a deadline that is already due sooner.
 *
 * The cutoff is the last backstop, so arming it must be additive and never a postponement: the
 * previous version called runIn(1200s) unconditionally, so a cutoff due in 5 seconds was replaced
 * by a fresh 20-minute timer, delaying the only automatic attempt that was about to run.
 *
 * Returns true when a cutoff is armed (new, or one already due sooner), false when the watchdog is
 * disabled -- so callers can say which of those is true instead of claiming a re-arm either way.
 */
private boolean armEmergencyPumpCutoff(String reason) {
    if (watchdogAnyPumpRun == false) {
        log.warn "WaterGuru Dosing Advisor: ${reason}, but the independent cutoff is disabled (watchdogAnyPumpRun is off)"
        return false
    }
    Integer fullSeconds = Math.max(60, Math.round((firstNum(failsafePumpRunMinutes, 20G) * 60G).doubleValue()) as Integer)
    Long nowMs = now()
    Long existing = state.emergencyDeadline as Long
    boolean jobPending = state.emergencyJobScheduled == true

    if (existing != null && existing <= nowMs) {
        // 2.4.2: the deadline has passed and the cutoff has not run: its job is late (a busy hub's
        // scheduler) or a queue reset dropped it. A fresh window here pushed a cutoff that was already
        // due a whole window later (review probe: due at +120 s, moved to +245 s by the +125 s retry).
        // Fire it now instead. The deadline becomes that moment, so a further arm within the second
        // leaves the job alone, and emergencyPumpOff() clears it when it runs, so this cannot loop.
        state.emergencyDeadline = nowMs + 1000L
        state.emergencyJobScheduled = true
        runIn(1, "emergencyPumpOff", [overwrite: true])
        log.warn "WaterGuru Dosing Advisor: ${reason}; the cutoff was due ${Math.round((nowMs - existing) / 1000L)}s ago and has not run, so it runs now (${jobPending ? 'its job was late' : 'its job was missing'})"
        return true
    }

    if (existing != null && existing > nowMs) {
        if (jobPending) {
            // A timer is genuinely pending and it is not later than a fresh window, so leave it:
            // arming must never postpone an attempt that is already about to run.
            log.warn "WaterGuru Dosing Advisor: ${reason}; cutoff already armed and due sooner (in ${Math.round((existing - nowMs) / 1000L)}s), leaving it in place"
            return true
        }
        // The deadline survived but the timer did not, which is what a queue reset (initialize via
        // updated()/save) leaves behind. A stored deadline is NOT protection, so recreate the job
        // for the time actually LEFT rather than restarting the whole window.
        long remaining = Math.max(1L, Math.round((existing - nowMs) / 1000.0d))
        state.emergencyJobScheduled = true
        runIn(remaining as Integer, "emergencyPumpOff", [overwrite: true])
        log.warn "WaterGuru Dosing Advisor: ${reason}; the cutoff JOB was missing (queue was reset) so it has been recreated with the ${remaining}s still remaining on the original deadline"
        return true
    }

    state.emergencyDeadline = nowMs + (fullSeconds * 1000L)
    state.emergencyJobScheduled = true
    runIn(fullSeconds, "emergencyPumpOff", [overwrite: true])
    log.warn "WaterGuru Dosing Advisor: ${reason}; independent emergency cutoff armed for ${formatDuration(fullSeconds)}"
    return true
}

/**
 * The independent cutoff. This is the last backstop, so it also refuses to claim success it has
 * not verified: if the OFF command throws or the switch keeps reporting ON, it re-arms and tries
 * again rather than reporting a stop that did not happen. Notices are throttled so a genuinely
 * stuck relay is loud without being unreadable.
 */
def emergencyPumpOff() {
    // This job has fired, so its deadline is spent and it is no longer pending. Clearing both here
    // is what lets a re-arm below schedule a fresh window rather than deferring to a timestamp for
    // a timer that no longer exists.
    state.remove("emergencyDeadline")
    state.remove("emergencyJobScheduled")
    if (!pumpSwitch) return
    Map active = state.activeDose instanceof Map ? state.activeDose : null
    // Record why we are stopping BEFORE asking the relay. Real drivers confirm asynchronously;
    // the eventual OFF event/retry must preserve the same fault as an immediate OFF response.
    if (active?.startConfirmed == true && active.fault == null) {
        active.fault = "emergency-stop"
        state.activeDose = active
        latchStartFault("emergency-stop", "The emergency cutoff was needed; delivery and completion are uncertain and require operator review.", active)
    }
    Long anchor = stopEvidenceAnchor(active)
    if (pumpIsOff(anchor)) {
        // Already off. Complete the full cleanup (not a hand-clearing of activeDose), so no
        // verification or power-watch job can survive the cutoff and later emit a success.
        alertStartUnconfirmed(active, "the emergency cutoff found the pump already off before the start was confirmed")
        finishStop(null)
        return
    }

    Long offAt = now()
    if (active != null) { active.offRequestedAt = offAt; state.activeDose = active }
    // 2.4.2: stopping an attempt that was never confirmed is a faulted termination here as on every
    // other stop path. Latched after offRequestedAt is set, so the fault's offFrom is this OFF request
    // and the plug's answer to it can acknowledge the fault.
    if (startUnconfirmed(active)) alertStartUnconfirmed(active, "the emergency cutoff fired before the start was confirmed")
    Integer attempt = ((state.emergencyAttempts ?: 0) as Integer) + 1
    state.emergencyAttempts = attempt
    try { pumpSwitch.off() }
    catch (e) { log.error "WaterGuru Dosing Advisor: cutoff OFF command failed — ${e.message}" }
    requestStopRefresh(active)

    String confirmed = "EMERGENCY cutoff confirmed the chlorine pump OFF after ${n1(firstNum(failsafePumpRunMinutes, 20G))} minutes."
    if (pumpIsOff(stopEvidenceAnchor(active))) {
        finishStop(confirmed)
        return
    }

    if (attempt <= 3 || attempt % 10 == 0) {
        // 2.4.2: deferred like the stop notice: the plug's answer to the OFF above lands 0.5 to 1.6 s later.
        deferEmergencyNotice("EMERGENCY: the cutoff has not been able to turn the chlorine pump off (attempt ${attempt})", false,
                             "Still trying, but the pump may need to be stopped by hand.", confirmed)
    }
    armEmergencyPumpCutoff("cutoff OFF unconfirmed (attempt ${attempt})")
}

private List doseSafetyBlocks(Map result) {
    def blocks = []
    if (!pumpSwitch) blocks << "no dedicated pump switch selected"
    // A latched fault is a SAFETY GATE, not a UI notice: no start may be attempted (and no new
    // attempt may be reserved) while a fault or a standalone stop recovery is unreviewed.
    if (state.startFault != null) blocks << "an unacknowledged pump fault must be reviewed first"
    if (state.faultStopAt != null) blocks << "a pump stop is still being recovered"
    // 2.4.2: an unconfirmed stop with nothing open still runs its retries, which would turn a new dose off.
    if (state.idleStopAt != null) blocks << "a pump stop is still waiting for the switch to confirm OFF"
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
    String today = nowDate().format("yyyy-MM-dd", location.timeZone)
    List times = []
    if (state.lastDose?.time instanceof Number) times << (state.lastDose.time as Long)
    // A FAILED attempt must also block a second AUTO dose today: the reservation is safety
    // accounting, so "eligible" cannot depend on whether the start was confirmed.
    if (state.lastAttempt?.time instanceof Number) times << (state.lastAttempt.time as Long)
    return times.any { new Date(it).format("yyyy-MM-dd", location.timeZone) == today }
}

/**
 * Reserve the safety accounting for an attempt BEFORE the relay is asked to move.
 *
 * The sample, the planned volume and the daily total are LIMITS, not telemetry: a start that is
 * never confirmed must still consume them, or a failed attempt would make another automatic dose
 * eligible and could push the real total over the daily cap. Success telemetry (lastDose) and
 * tank inventory are deliberately NOT touched here.
 */
private void reserveDoseAttempt(BigDecimal ml, String sample, Integer seconds) {
    addDoseMlToday(ml)
    state.lastDosedSample = sample
    state.lastAttempt = [time: now(), ml: n0(ml), mlRaw: ml.toString(),
                         seconds: seconds, sample: sample]
}

/**
 * Record a CONFIRMED start. The attempt was already reserved before ON, so only the success
 * telemetry and the tank draw happen here. Idempotent by attempt id: an overlapping or replayed
 * handler cannot draw the tank or reset lastDose a second time.
 */
private void recordConfirmedDose(Map active) {
    Map current = state.activeDose instanceof Map ? state.activeDose : null
    if (current == null || current.attemptId == null || current.attemptId != active.attemptId) return
    if (current.startConfirmed != true) return
    if (state.lastBookedAttemptId == active.attemptId) return
    String attemptId = active.attemptId?.toString()
    BigDecimal ml = toBD(active.mlRaw) ?: toBD(active.ml)
    state.lastDose = [time: now(), ml: active.ml, mlRaw: active.mlRaw,
                      seconds: active.seconds, sample: active.sample,
                      planned: true, confirmed: true,
                      attemptId: attemptId, startRequestedAt: active.requestedAt]
    state.lastBookedAttemptId = attemptId
    if (ml != null && ml > 0) recordTankUse(ml)
}

/** Add volume to today's safety total, starting a new day's total when the date has changed. */
private void addDoseMlToday(BigDecimal ml) {
    if (ml == null || ml <= 0G) return
    String day = doseDayKey()
    BigDecimal prior = (state.doseDay == day) ? (toBD(state.doseMlToday) ?: 0G) : 0G
    state.doseDay = day
    state.doseMlToday = (prior + ml).toString()
}

/**
 * When the run that just ended stopped: the switch's own change to off when it postdates the run's
 * ON report, else the receipt time of the accepted OFF report, else now (an upper bound).
 */
private Long observedOffAt(Long onAt) {
    Map sw = readDeviceReading("switch")
    Long at = sw.at as Long
    if (sw.ok == true && sw.value?.toString() == "off" && at != null && !futureDated(at) && (onAt == null || at >= onAt)) return at
    Long seen = offEvidenceAt()
    if (seen != null && !futureDated(seen) && (onAt == null || seen >= onAt)) return seen
    return now()
}

/**
 * Book what actually ran (2.4.1), once, as a confirmed OFF ends the run.
 *
 * Until now every confirmed run was booked at its planned volume, even when its OFF landed minutes
 * late (review probe: OFF 180 s late, about 1134 mL ran, 475 mL booked, and a same-day 3000 mL dose
 * then passed the 3500 mL cap). The run is measured from its ON report to its OFF report, so the
 * report latency at either end cancels out.
 *
 *   - A confirmed run within RUN_MATCH_TOLERANCE_MS of its plan keeps the planned booking.
 *   - Longer (a late OFF): the extra volume is debited from the tank and added to today's total, and
 *     an OFF confirmed more than STOP_OVERRUN_FAULT_SECONDS after the planned stop latches a review
 *     fault (unless one is already latched for this run).
 *   - Shorter (an early stop): the tank is booked from the observed run, while today's total keeps
 *     the full planned reservation, which is the conservative direction for the cap.
 *   - An unconfirmed attempt, or a late ON during fault recovery, that demonstrably ran (an ON report,
 *     plus power above the minimum on a power-reporting switch) is counted as well: the tank from the
 *     observed run, today's total beyond what the attempt already reserved. It never becomes a
 *     start or a lastDose record.
 *
 * A correction rewrites lastDose under its ORIGINAL time and republishes it. The historian
 * (Waterguru-Grafana-Chart, dosing.py) keys a chlorine_dose point by the lastDoseEpochMs value and
 * writes it at that timestamp, so the corrected volume overwrites the same point rather than
 * counting a second dose. Since 2.4.2 the republish is forced (isStateChange), because the hub drops
 * an event whose value did not change, and the unchanged lastDoseEpochMs is what the historian pairs
 * the corrected volume with.
 *
 * Returns a sentence for the final notice, or null when nothing changed.
 */
private String bookObservedRun(Map active) {
    boolean standalone = active == null
    Map run = standalone ? (state.faultRun instanceof Map ? state.faultRun : null) : active
    if (run == null) return null
    Long onAt = run.onReportAt instanceof Number ? (run.onReportAt as Long) : null
    if (onAt == null) return null
    boolean confirmed = !standalone && active.startConfirmed == true
    // An unconfirmed relay that never drew power did not pump; on a switch without a power meter the
    // ON report is all the evidence there is, as it is for confirming a start.
    if (!confirmed && powerConfirmationRequired() && run.powerSeenAt == null) return null
    BigDecimal rate = firstNum(pumpRateMlPerMin, DEFAULT_PUMP_RATE_ML_MIN)
    if (rate == null || rate <= 0G) return null

    Long offAt = observedOffAt(onAt)
    long ranMs = Math.max(0L, (offAt - onAt) as long)
    BigDecimal ranMl = (ranMs as BigDecimal) * rate / 60000G
    Integer ranSec = Math.round(ranMs / 1000.0d) as Integer
    BigDecimal plannedMl = standalone ? 0G : (toBD(active.mlRaw) ?: toBD(active.ml) ?: 0G)
    Integer plannedSec = standalone ? 0 : ((active.seconds ?: 0) as Integer)
    if (confirmed && Math.abs(ranMs - (plannedSec * 1000L)) <= RUN_MATCH_TOLERANCE_MS) return null   // ran as planned

    BigDecimal alreadyBooked = confirmed ? plannedMl : 0G   // the tank was drawn at confirmation
    BigDecimal reserved = standalone ? 0G : plannedMl         // today's total took it before ON
    boolean matchesLastDose = confirmed && state.lastDose instanceof Map &&
                              state.lastDose.attemptId?.toString() == active.attemptId?.toString()
    Long doseTime = matchesLastDose ? (state.lastDose.time as Long) : (confirmed ? (active.confirmedAt as Long) : onAt)
    setDoseHistoryVolume(doseTime ?: onAt, ranMl)
    debitTank(ranMl - alreadyBooked)
    BigDecimal beyondReservation = ranMl - reserved
    if (beyondReservation > 0G) addDoseMlToday(beyondReservation)

    if (matchesLastDose) {
        // Same time (the dose's identity for the historian), observed volume and run time.
        state.lastDose = (state.lastDose as Map) + [ml: n0(ranMl), mlRaw: ranMl.toString(), seconds: ranSec,
                                                    planned: false, observed: true,
                                                    plannedMl: active.mlRaw ?: active.ml, plannedSeconds: plannedSec]
        try {
            // 2.4.2: forced, so the unchanged lastDoseEpochMs is sent again beside the corrected volume
            // and the historian pairs the two (it drops a volume with no dose time beside it).
            publishTileTelemetry(null, null, true)
        } catch (e) {
            log.error "WaterGuru Dosing Advisor: the observed run was booked but dashboard telemetry failed: ${e.message}"
        }
    }

    String volume = "about ${n0(ranMl)} mL at ${n1(rate)} mL/min"
    if (standalone) {
        return "While the fault was pending the switch reported ON for ${formatDuration(ranSec)} before OFF was confirmed${evidenceNote()}: " +
               "${volume} is counted against the tank and today's total."
    }
    if (!confirmed) {
        String total = beyondReservation > 0G
            ? ", and the ${n0(beyondReservation)} mL beyond the ${n0(reserved)} mL reservation is added to today's total."
            : "; today's total already reserves the ${n0(reserved)} mL attempt."
        return "The switch reported ON for ${formatDuration(ranSec)} before the confirmed OFF${evidenceNote()}: ${volume} is counted against the tank${total}"
    }
    if (ranMl < plannedMl) {
        return "It ran ${formatDuration(ranSec)} of the planned ${formatDuration(plannedSec)} (ON report to OFF report): " +
               "${volume} is booked against the tank instead of ${n0(plannedMl)} mL, and today's total keeps the full ${n0(plannedMl)} mL reservation."
    }
    String text = "It ran ${formatDuration(ranSec)} from its ON report to its OFF report against a plan of ${formatDuration(plannedSec)}: " +
                  "${volume} instead of ${n0(plannedMl)} mL. The extra ${n0(ranMl - plannedMl)} mL is debited from the tank and added to today's total."
    Long stopAt = active.stopAt as Long
    long lateMs = stopAt != null ? (offAt - stopAt) as long : 0L
    if (lateMs > STOP_OVERRUN_FAULT_SECONDS * 1000L && state.startFault == null) {
        Integer lateSec = Math.round(lateMs / 1000.0d) as Integer
        latchStartFault("stop-overrun",
                        "The OFF was confirmed ${formatDuration(lateSec)} after the planned stop: the pump ran ${formatDuration(ranSec)} of a planned ${formatDuration(plannedSec)}, about ${n0(ranMl)} mL instead of ${n0(plannedMl)} mL.",
                        active)
        text += " The OFF was confirmed ${formatDuration(lateSec)} after the planned stop, so a stop-overrun fault is latched: review it and press Acknowledge pump fault before the next dose."
    }
    return text
}

/** How a counted, unconfirmed run was evidenced. */
private String evidenceNote() {
    return powerConfirmationRequired() ? " with power above the minimum" : " (power and flow unverified)"
}

/**
 * Clear the latched start/power fault after operator review.
 *
 * Releases the fault gate only once stop recovery has finished and the switch has freshly
 * reported OFF after the fault. It never energises the pump, never
 * removes an active/unconfirmed stop, never refunds the attempt reservation, and never resets the
 * daily/sample limits -- so acknowledging can never be a way to get an extra dose.
 */
private void acknowledgePumpFault() {
    Map f = state.startFault instanceof Map ? state.startFault : null
    if (f == null) {
        sendPumpNotice("No pump fault is pending acknowledgement.")
        return
    }
    if (state.activeDose != null) {
        sendPumpNotice("Cannot acknowledge the pump fault yet: a stop is still being recovered. Wait until the switch freshly reports OFF.")
        return
    }
    // A 2.4.0 fault has no offFrom; its latch time is the floor, as before.
    Long faultAt = f.offFrom instanceof Number ? (f.offFrom as Long) : (f.at instanceof Number ? (f.at as Long) : null)
    Long recoveryAnchor = state.faultStopAt instanceof Number ? (state.faultStopAt as Long) : null
    Long requireAfter = (recoveryAnchor != null && (faultAt == null || recoveryAnchor > faultAt)) ? recoveryAnchor : faultAt
    if (!pumpIsOff(requireAfter)) {
        // Only claim a recovery is running when one is. With nothing open, nothing will produce a new
        // OFF report on its own, so say how to get one.
        String next = state.faultStopAt != null
            ? "Stop recovery stays active."
            : "Press STOP chlorine pump now to ask the plug for a fresh OFF report, then acknowledge again."
        sendPumpNotice("Cannot acknowledge the pump fault yet: the switch has not freshly reported OFF since the fault. ${next}")
        return
    }
    // The switch is freshly OFF. Run the FULL stop cleanup first, so no verifyPumpOff or
    // emergencyPumpOff timer can survive the acknowledgement and later fire against a new run.
    finishStop(null)
    state.remove("startFault")
    state.remove("faultStopAt")
    state.remove("stopRefreshAt")
    state.remove("faultStopAlerted")
    state.remove("faultRun")
    sendPumpNotice("Pump fault acknowledged (${f.reason ?: f.kind}). The attempt stays reserved and normal sample/day limits are unchanged.")
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

// ---------------------------------------------------------------------------
// Tank corrections (2.4.2). Generic for any install, and deliberately narrow: they move the tank
// estimate and the dose history only. Neither can energise the pump, and neither touches today's dose
// total, the sample lock or the one-AUTO-dose guard, so a correction can never be a way to dose again.
// ---------------------------------------------------------------------------

/** What "Apply tank adjustment" will do with the amount entered, for the line under the input. */
private String tankAdjustmentPreview() {
    BigDecimal delta = toBD(tankAdjustMl)
    if (delta == null || delta.compareTo(0G) == 0) return null
    BigDecimal cap = tankCapacityMl()
    BigDecimal before = tankRemainingMl()
    if (cap == null || before == null) return "The tank inventory is not initialized, so there is nothing to adjust. Mark the tank full first."
    BigDecimal after = clampTank(before + delta, cap)
    String limited = (after - before).compareTo(delta) != 0 ? ", limited to the ${n0(cap)} mL container" : ""
    return "Apply will change the estimate from <b>${n0(before)} mL</b> to <b>${n0(after)} mL</b> (${signedMl(after - before)}${limited})."
}

/** Move the tank estimate by the signed amount entered, within 0 and the container size. */
private void applyTankAdjustment() {
    BigDecimal delta = toBD(tankAdjustMl)
    if (delta == null || delta.compareTo(0G) == 0) {
        sendPumpNotice("No tank adjustment applied: enter a positive or negative amount in mL first.")
        return
    }
    BigDecimal cap = tankCapacityMl()
    BigDecimal before = tankRemainingMl()
    if (cap == null || before == null) {
        sendPumpNotice("No tank adjustment applied: the tank inventory is not initialized. Mark the tank full first.")
        clearSetting("tankAdjustMl")
        return
    }
    BigDecimal after = clampTank(before + delta, cap)
    debitTank(before - after)                 // a negative debit puts chlorine back
    rearmLowTankAlert()
    state.lastTankCorrection = [kind: "adjust", at: now(), requestedMl: delta.toString(),
                                beforeMl: before.toString(), afterMl: after.toString()]
    // Applied once: a second press with the field still filled in would apply it twice.
    clearSetting("tankAdjustMl")
    publishTankTelemetryNow()
    String limited = (after - before).compareTo(delta) != 0 ? " (limited to the ${n0(cap)} mL container)" : ""
    String msg = "Chlorine tank estimate adjusted by ${signedMl(after - before)}${limited}: ${n0(before)} mL to ${n0(after)} mL (${n0(after * 100G / cap)}%). The pump and today's dose total are unchanged."
    log.info "WaterGuru Dosing Advisor: ${msg}"
    sendPumpNotice(msg)
}

/** The recent dose-history entries, newest first, as the void control's options: time -> label. */
private Map voidDoseChoices() {
    List entries = tankDoseHistory().findAll { it?.t instanceof Number && toBD(it?.ml) != null }
    Map out = [:]
    entries.sort { a, b -> (b.t as Long) <=> (a.t as Long) }.take(VOID_CHOICES_MAX).each {
        out[(it.t as Long).toString()] = "${doseTimeText(it.t as Long)} · ${n0(toBD(it.ml))} mL".toString()
    }
    return out
}

/** The dose-history entry the void control points at, or null. */
private Map selectedVoidEntry() {
    Long t = null
    try { t = voidDoseKey != null ? (voidDoseKey.toString() as Long) : null } catch (ignored) { t = null }
    if (t == null) return null
    return tankDoseHistory().find { it?.t instanceof Number && (it.t as Long) == t } as Map
}

/** What "Void the selected dose" will do, for the line under the selection. */
private String voidDosePreview() {
    Map entry = selectedVoidEntry()
    if (entry == null) return null
    Long t = entry.t as Long
    BigDecimal ml = toBD(entry.ml) ?: 0G
    BigDecimal cap = tankCapacityMl()
    BigDecimal before = tankRemainingMl()
    String tank = (cap != null && before != null)
        ? "return ${n0(clampTank(before + ml, cap) - before)} mL to the tank (${n0(before)} to ${n0(clampTank(before + ml, cap))} mL)"
        : "leave the uninitialized tank estimate alone"
    String last = isLastDose(t) ? ", and clear it as the last recorded dose" : ""
    return "Void will remove the ${doseTimeText(t)} dose (${n0(ml)} mL) from the dose history, ${tank}${last}. The pump and today's dose total are unchanged."
}

/**
 * Void a recorded dose that never ran (the Oct 3 lost-ON pattern): remove it from the dose history so
 * the FC-loss estimate stops adding it back, return its volume to the tank, clear lastDose only if it
 * is that same dose, and publish it to the historian as a 0 mL correction under its own time.
 */
private void voidRecordedDose() {
    if (!voidDoseKey) {
        sendPumpNotice("No dose voided: choose a recorded dose first.")
        return
    }
    // While a dose or a stop is open, booking and its corrections may still be writing the same records.
    if (state.activeDose != null || state.faultStopAt != null || state.idleStopAt != null) {
        sendPumpNotice("No dose voided: the pump is running or a stop is still being confirmed. Try again once the pump is idle.")
        return
    }
    Map entry = selectedVoidEntry()
    if (entry == null) {
        sendPumpNotice("No dose voided: the selected dose is no longer in the recent dose history.")
        clearSetting("voidDoseKey")
        return
    }
    Long t = entry.t as Long
    BigDecimal ml = toBD(entry.ml) ?: 0G
    boolean wasLast = isLastDose(t)
    state.tankDoseHistory = tankDoseHistory().findAll { !(it?.t instanceof Number && (it.t as Long) == t) }
    // Left in place, lastDose would be added back by appDoseEvents() and re-seed tankDoseHistory().
    if (wasLast) state.remove("lastDose")
    BigDecimal cap = tankCapacityMl()
    BigDecimal before = tankRemainingMl()
    BigDecimal after = before
    if (cap != null && before != null && ml > 0G) {
        after = clampTank(before + ml, cap)
        debitTank(before - after)
        rearmLowTankAlert()
    }
    List voided = (state.voidedDoses instanceof List ? state.voidedDoses : []) + [[t: t, ml: ml.toString(), at: now()]]
    if (voided.size() > VOID_CHOICES_MAX) voided = voided[(voided.size() - VOID_CHOICES_MAX)..-1]
    state.voidedDoses = voided
    clearSetting("voidDoseKey")
    boolean published = publishVoidedDose(t)
    publishTankTelemetryNow()
    String tank = (cap != null && before != null)
        ? " ${n0(after - before)} mL went back to the tank (${n0(before)} to ${n0(after)} mL)."
        : " The tank estimate is not initialized, so it was not changed."
    String last = wasLast ? " It was the last recorded dose, so that record is cleared." : ""
    String historian = published ? " The dashboard tile republishes it as 0 mL." : ""
    String msg = "Recorded dose voided: ${doseTimeText(t)}, ${n0(ml)} mL, removed from the dose history.${tank}${last}${historian} The pump and today's dose total are unchanged."
    log.info "WaterGuru Dosing Advisor: ${msg}"
    sendPumpNotice(msg)
}

private boolean isLastDose(Long t) {
    return t != null && state.lastDose instanceof Map && state.lastDose.time instanceof Number && (state.lastDose.time as Long) == t
}

private BigDecimal clampTank(BigDecimal ml, BigDecimal cap) {
    if (ml < 0G) return 0G
    return ml > cap ? cap : ml
}

/** A correction that lifted the tank above the low threshold lets a later low warning go out again. */
private void rearmLowTankAlert() {
    BigDecimal cap = tankCapacityMl()
    BigDecimal remaining = tankRemainingMl()
    if (cap == null || remaining == null) return
    BigDecimal lowPct = firstNum(tankLowPercent, 20G)
    if (lowPct < 0) lowPct = 0G
    if (lowPct > 100) lowPct = 100G
    if (remaining > cap * lowPct / 100G) state.tankLowAlerted = false
}

private String signedMl(BigDecimal ml) {
    return "${ml < 0G ? '-' : '+'}${n0(ml.abs())} mL"
}

private String doseTimeText(Long t) {
    return new Date(t).format("MMM d, yyyy h:mm:ss a z", location?.timeZone ?: TimeZone.getDefault())
}

/** Clear an input once its button has used it. A failure only leaves the value on the page. */
private void clearSetting(String name) {
    try { app.removeSetting(name) }
    catch (e) { log.warn "WaterGuru Dosing Advisor: could not clear the ${name} setting: ${e.message}" }
}

private void recordTankUse(BigDecimal ml) {
    if (ml == null || ml <= 0) return
    // The dose history is kept whether or not the tank is tracked (2.4.1): the FC-loss estimate needs
    // every app dose to tell decay from chlorine the app added.
    setDoseHistoryVolume(state.lastDose?.time != null ? (state.lastDose.time as Long) : now(), ml)
    debitTank(ml)
}

/**
 * The dose history entry at `t` becomes `ml` (a correction replaces it, a new time adds one; zero
 * removes it). Kept in time order and bounded to TANK_DOSE_HISTORY_MAX entries.
 */
private void setDoseHistoryVolume(Long t, BigDecimal ml) {
    if (t == null) return
    List history = tankDoseHistory().findAll { !(it?.t instanceof Number && (it.t as Long) == t) }
    if (ml != null && ml > 0G) history << [t: t, ml: ml.toString()]
    history = history.sort { a, b -> ((a?.t ?: 0L) as Long) <=> ((b?.t ?: 0L) as Long) }
    if (history.size() > TANK_DOSE_HISTORY_MAX) history = history[(history.size() - TANK_DOSE_HISTORY_MAX)..-1]
    state.tankDoseHistory = history
}

/** Take `ml` out of the tracked tank (a negative amount puts it back), within 0 and capacity. */
private void debitTank(BigDecimal ml) {
    BigDecimal cap = tankCapacityMl()
    BigDecimal before = tankRemainingMl()
    if (cap == null || before == null || ml == null || ml.compareTo(0G) == 0) return
    BigDecimal after = before - ml
    if (after < 0) after = 0G
    if (after > cap) after = cap
    state.tankRemainingMl = after.toString()

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

    // Display-only, but confirmDoseStart() calls recordTankUse() as part of the confirmed-start
    // bookkeeping, so this must not be able to throw into the ledger bookkeeping that precedes it.
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
 * This splices the two tank lines rather than calling computeAdvice() to regenerate the whole
 * text. Note that neither route is free of side effects: both reach computeTankRunway(), which
 * calls tankDoseHistory() and thereby initialises state.tankDoseHistory. The splice is used
 * because the chemistry lines are still as-of the last WaterGuru sample, which a dose does not
 * change, so regenerating them would be churn at best. Only the two lines that actually moved
 * are replaced.
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

    // Elapsed days, not dose count. An average of mL per DOSE is not mL per DAY, and skipped
    // dosing days make it overstate the runway. Total delivered volume over the span those
    // doses were delivered across is a time-weighted average, so irregular intervals are handled
    // correctly and a week of skipped doses lowers the rate instead of being ignored.
    List entries = tankDoseHistory().findAll {
        it?.t instanceof Number && toBD(it?.ml) != null && toBD(it?.ml) > 0
    }
    if (!entries) return [state: "learning", pct: lowPct, n: 0]

    BigDecimal volume = entries.collect { toBD(it.ml) }.sum(0G)
    Long firstT = entries.collect { it.t as Long }.min()
    Long lastT = entries.collect { it.t as Long }.max()
    // Elapsed time runs to NOW, not to the last dose. Ten days without a dose is ten days in which
    // the tank was not drawn down, and the rate has to reflect that -- measuring only first-dose to
    // last-dose made ongoing skipped days invisible.
    Long windowEnd = Math.max(lastT, now())
    BigDecimal spanDays = (windowEnd - firstT) / 86400000G

    // Under about half a day there is no interval worth measuring a daily rate over -- one dose,
    // or two inside the same hour, would otherwise produce a wildly high rate and a runway near
    // zero. "Learning" is the honest answer until real time has passed.
    if (volume <= 0 || spanDays < 0.5G) {
        return [state: "learning", pct: lowPct, n: entries.size(), spanDays: spanDays]
    }

    BigDecimal dailyUse = volume / spanDays
    BigDecimal days = (remaining - threshold) / dailyUse
    return [state: "ok", pct: lowPct, days: days, dailyUse: dailyUse,
            n: entries.size(), spanDays: spanDays]
}

private String tankRunwayLine(Map runway) {
    switch (runway?.state) {
        case "below":
            return "⏳ Tank runway: inventory is at/below the ${n0(runway.pct)}% low-tank threshold."
        case "learning":
            return "⏳ Tank runway: learning — a completed app-controlled dose is needed to estimate days until ${n0(runway.pct)}%."
        case "ok":
            String samples = runway.n == 1 ? "dose sample" : "dose samples"
            return "⏳ Tank runway: ~${n1(runway.days)} days until inventory drops below ${n0(runway.pct)}% (average ${n0(runway.dailyUse)} mL/day measured over ${n1(runway.spanDays)} days)."
        default:
            return ""
    }
}

private BigDecimal doseMlToday() {
    if (state.doseDay != doseDayKey()) return 0G
    return toBD(state.doseMlToday) ?: 0G
}

private String doseDayKey() {
    return nowDate().format("yyyy-MM-dd", location?.timeZone ?: TimeZone.getDefault())
}

/** The clock the app uses everywhere. `now()` is Hubitat's injected clock, so this is exactly
 *  `new Date()` on a hub but follows the test harness's fake clock off-hub. */
private Date nowDate() { new Date(now()) }

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
        dev.sendEvent(name: "lastCalc",       value: nowDate())
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
 * dose is recorded only after a start has been CONFIRMED (a fresh ON, plus fresh power above the
 * minimum for a power-capable switch), so external totals represent pump runs this app actually
 * observed running rather than recommendations, previews, or a bare on() return. The volume is
 * still the planned estimate, not a measurement of liquid delivery.
 */
private void publishTileTelemetry(Map r = null, def tile = null, boolean doseRecord = false) {
    if (createTile == false) return
    def dev = tile ?: getTileDevice()
    if (!dev) return
    publishDoseTelemetry(dev, doseRecord)
    publishTankTelemetry(dev)
    // FC values come only with a calculation; a booking or a correction carries none.
    if (r != null) publishFcTelemetry(dev, r)
}

/**
 * The last dose. `doseRecord` (2.4.2) forces the three events, for a confirmation and a correction: the
 * hub drops an event whose value did not change, so a dose of the same volume as the one before, or a
 * corrected volume under the unchanged lastDoseEpochMs, never reached the historian, which pairs each
 * lastDoseEpochMs event with a lastDoseMl event (Waterguru-Grafana-Chart, dosing.py). The routine
 * publish on every calculation stays unforced, so it creates no events of its own.
 */
private void publishDoseTelemetry(def dev, boolean doseRecord) {
    Map last = state.lastDose instanceof Map ? state.lastDose : [:]
    if (doseRecord) {
        if (last.time != null)       dev.sendEvent(name: "lastDoseEpochMs", value: last.time as Long, isStateChange: true)
        if (last.mlRaw != null || last.ml != null)
            dev.sendEvent(name: "lastDoseMl", value: toBD(last.mlRaw ?: last.ml), unit: "mL", isStateChange: true)
        if (last.seconds != null)    dev.sendEvent(name: "lastDoseRuntimeSeconds", value: last.seconds as Integer, unit: "s", isStateChange: true)
        return
    }
    if (last.time != null)       dev.sendEvent(name: "lastDoseEpochMs", value: last.time as Long)
    if (last.mlRaw != null || last.ml != null)
        dev.sendEvent(name: "lastDoseMl", value: toBD(last.mlRaw ?: last.ml), unit: "mL")
    if (last.seconds != null)    dev.sendEvent(name: "lastDoseRuntimeSeconds", value: last.seconds as Integer, unit: "s")
}

/**
 * Tank inventory and runway. 2.4.2: a value the app no longer knows (no container size, inventory not
 * initialised or the container changed, runway still learning) is published as UNKNOWN_TELEMETRY
 * instead of being skipped, which left the last number on the tile and the historian kept storing it.
 */
private void publishTankTelemetry(def dev) {
    BigDecimal cap = tankCapacityMl()
    BigDecimal remaining = tankRemainingMl()
    Map tankRunway = computeTankRunway()
    if (cap != null)             dev.sendEvent(name: "tankCapacityMl", value: cap, unit: "mL")
    else                         dev.sendEvent(name: "tankCapacityMl", value: UNKNOWN_TELEMETRY, unit: "mL")
    if (remaining != null) {
        dev.sendEvent(name: "tankRemainingMl", value: remaining, unit: "mL")
        dev.sendEvent(name: "tankPercent", value: cap > 0 ? remaining * 100G / cap : 0G, unit: "%")
    } else {
        dev.sendEvent(name: "tankRemainingMl", value: UNKNOWN_TELEMETRY, unit: "mL")
        dev.sendEvent(name: "tankPercent", value: UNKNOWN_TELEMETRY, unit: "%")
    }
    if (tankRunway?.days != null)
        dev.sendEvent(name: "tankRunwayDays", value: tankRunway.days as BigDecimal, unit: "days")
    else
        dev.sendEvent(name: "tankRunwayDays", value: UNKNOWN_TELEMETRY, unit: "days")
}

/** The reading and the target of a calculation; 2.4.2: UNKNOWN_TELEMETRY when either is missing. */
private void publishFcTelemetry(def dev, Map r) {
    if (r.fcVal != null)         dev.sendEvent(name: "freeChlorine", value: r.fcVal as BigDecimal, unit: "ppm")
    else                         dev.sendEvent(name: "freeChlorine", value: UNKNOWN_TELEMETRY, unit: "ppm")
    if (r.fcTarget != null)      dev.sendEvent(name: "targetFreeChlorine", value: r.fcTarget as BigDecimal, unit: "ppm")
    else                         dev.sendEvent(name: "targetFreeChlorine", value: UNKNOWN_TELEMETRY, unit: "ppm")
}

/** The tank values alone, after a correction (2.4.2). Never the dose record: see publishVoidedDose(). */
private void publishTankTelemetryNow() {
    if (createTile == false) return
    def dev = getTileDevice()
    if (!dev) return
    try { publishTankTelemetry(dev) }
    catch (e) { log.warn "WaterGuru Dosing Advisor: could not update the tile's tank values: ${e.message}" }
}

/**
 * A voided dose, as a correction to 0 mL under its own time (2.4.2): the same identity 2.4.1 corrections
 * use, so the historian overwrites its point with 0 mL instead of keeping a dose that never ran. Forced,
 * and sent alone: the historian pairs events by time, so this handler must not publish another dose record
 * beside it. The next calculation puts the actual last dose (if any) back on the tile.
 */
private boolean publishVoidedDose(Long t) {
    if (createTile == false || t == null) return false
    def dev = getTileDevice()
    if (!dev) return false
    try {
        dev.sendEvent(name: "lastDoseEpochMs", value: t, isStateChange: true)
        dev.sendEvent(name: "lastDoseMl", value: 0G, unit: "mL", isStateChange: true)
        dev.sendEvent(name: "lastDoseRuntimeSeconds", value: 0, unit: "s", isStateChange: true)
        return true
    } catch (e) {
        log.warn "WaterGuru Dosing Advisor: could not publish the voided dose to the tile: ${e.message}"
        return false
    }
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
    String ts    = nowDate().format("EEE h:mm a", location?.timeZone ?: TimeZone.getDefault())
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

/** Average daily FC loss over recent decay intervals between consecutive samples at
 *  least ~6 h apart. Uses up to the last 5 such intervals. Returns [rate, n] or null.
 *
 *  2.4.1: the app's own doses between two samples are added back as ppm before the
 *  loss is measured. Counting only intervals where FC fell made daily dosing hide the
 *  loss (review probe: a true 0.64 ppm/day measured as 0.2, a runway about 3x too
 *  long). When the pool volume or the product strength is unknown, a dose cannot be
 *  converted, so an interval that contains one is skipped instead of guessed. */
private Map measuredFcLoss() {
    def hist = (state.fcHistory instanceof List) ? state.fcHistory : []
    if (hist.size() < 2) return null
    BigDecimal mlPerPpm = doseMlPerPpm()
    List doses = appDoseEvents()
    def rates = []
    for (int i = 1; i < hist.size(); i++) {
        BigDecimal fa = toBD(hist[i-1]?.fc), fb = toBD(hist[i]?.fc)
        def ta = hist[i-1]?.t, tb = hist[i]?.t
        if (fa == null || fb == null || !(ta instanceof Number) || !(tb instanceof Number)) continue
        double dtDays = ((tb as Long) - (ta as Long)) / 86400000.0d
        if (dtDays < 0.25d) continue     // too close together to be a fresh sample
        List inside = doses.findAll { (it.t as Long) > (ta as Long) && (it.t as Long) <= (tb as Long) }
        BigDecimal added = 0G
        if (inside) {
            if (mlPerPpm == null) continue   // a dose happened but cannot be converted to ppm
            added = (inside.collect { toBD(it.ml) }.sum(0G) as BigDecimal) / mlPerPpm
        }
        BigDecimal lost = fa + added - fb
        if (lost <= 0G) continue         // FC held or rose beyond what the app added: chlorine came from elsewhere
        rates << lost.doubleValue() / dtDays
    }
    if (!rates) return null
    def recent = rates.size() > 5 ? rates[-5..-1] : rates
    double avg = recent.sum() / recent.size()
    return [rate: avg as BigDecimal, n: recent.size()]
}

/** mL of the configured liquid chlorine that raises this pool's FC by 1 ppm: computeFc's dose
 *  formula for a 1 ppm gap. Null when the pool volume or the product strength is unknown; the
 *  12.5% advice fallback is a guess and is not used to reconstruct what a dose added. */
private BigDecimal doseMlPerPpm() {
    BigDecimal volume = firstNum(volumeOverride, attrNum("poolVolume"))
    BigDecimal pct = firstNum(chlorinePctOverride, attrNum("chlorineProductPct"))
    if (volume == null || volume <= 0G || pct == null || pct <= 0G) return null
    return CL_FLOZ_PER_PPM_PER_10K_AT_12_5 * (volume / 10000G) * (12.5G / pct) * ML_PER_FLOZ
}

/** Every app-controlled dose on record as [t, ml], read-only: the dose history plus the last dose
 *  when an older install never wrote it to the history. */
private List appDoseEvents() {
    List out = ((state.tankDoseHistory instanceof List) ? state.tankDoseHistory : []).findAll {
        it?.t instanceof Number && toBD(it?.ml) != null && toBD(it.ml) > 0G
    }
    Map last = state.lastDose instanceof Map ? state.lastDose : null
    if (last?.time instanceof Number && !out.any { (it.t as Long) == (last.time as Long) }) {
        BigDecimal ml = toBD(last.mlRaw ?: last.ml)
        if (ml != null && ml > 0G) out = out + [[t: last.time as Long, ml: ml.toString()]]
    }
    return out
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
