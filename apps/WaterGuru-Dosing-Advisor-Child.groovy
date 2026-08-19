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

def appVersion() { "1.3.0" }

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
@Field static final BigDecimal FC_LOSS_MODELED_DEFAULT = 3.0G
@Field static final BigDecimal FC_LOSS_COVER_FACTOR    = 0.6G

// ---------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------

def mainPage() {
    dynamicPage(name: "mainPage", title: "WaterGuru Dosing Advisor — pool", uninstall: true, install: true) {

        section("<b>Source</b>") {
            input "sourceDevice", "capability.pHMeasurement",
                title: "WaterGuru pool device (the WaterGuru Integration Driver child device)",
                required: true, multiple: false, submitOnChange: true
            if (sourceDevice) {
                paragraph currentReadingsSummary()
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
                title: "Also send a daily summary at a set time (independent of new samples)",
                defaultValue: false, submitOnChange: true
            if (dailyDigest == true) {
                input "digestTime", "time",
                    title: "Daily summary time",
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
            if (state.lastPreview) {
                paragraph "<b>Last preview:</b>\n<pre style='white-space:pre-wrap'>${state.lastPreview}</pre>"
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

private String currentReadingsSummary() {
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
    return "Current: ${s}${sampled ? "  (sampled ${sampled})" : ''}"
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

def installed() { initialize() }

def updated() {
    unsubscribe()
    initialize()
}

def uninstalled() { removeTileDevice() }

def initialize() {
    unschedule()   // clear any previous daily-digest job before (re)scheduling

    // Give the child a meaningful name in the parent's list if unnamed.
    if (sourceDevice && (!app.label || app.label == "WaterGuru Dosing Advisor Pool")) {
        app.updateLabel("Dosing: ${sourceDevice.displayName}")
    }

    // Companion dashboard-tile device (created/removed per the toggle).
    ensureTileDevice()

    if (autoRun != false && sourceDevice) {
        // New WaterGuru sample => LastMeasurement (a DATE attribute) changes.
        subscribe(sourceDevice, "LastMeasurement", "onNewSample")
        logDebug "Subscribed to new-sample events on ${sourceDevice.displayName}"
    }

    if (dailyDigest == true && digestTime) {
        // A time-of-day input schedules a daily recurring job at that clock time.
        schedule(digestTime, "dailyDigestHandler")
        logDebug "Scheduled daily summary at ${digestTime}"
    }

    // Populate the tile right away so it is never blank after a save.
    refreshTile()
}

def onNewSample(evt) {
    logDebug "New WaterGuru sample (${evt?.value}); computing dose advice"
    recordFcSample(evt)
    runAndDeliver(true)
}

def dailyDigestHandler() {
    logDebug "Daily summary firing"
    runAndDeliver(true)
}

void appButtonHandler(String btn) {
    switch (btn) {
        case "btnCalcNow": runAndDeliver(true);  break
        case "btnPreview": runAndDeliver(false); break
    }
}

// ---------------------------------------------------------------------------
// Orchestration
// ---------------------------------------------------------------------------

private void runAndDeliver(boolean send) {
    if (!sourceDevice) { log.warn "WaterGuru Dosing Advisor: no source device selected"; return }
    def result = computeAdvice()
    def text = result.text
    state.lastPreview = text

    // Refresh the dashboard tile on every calculation (send or preview).
    updateTileDevice(result)

    if (!send) {
        log.info "WaterGuru Dosing Advisor (preview):\n${text}"
        return
    }

    log.info "WaterGuru Dosing Advisor:\n${text}"
    if (!notifyDevices) { log.warn "WaterGuru Dosing Advisor: no notification device selected — nothing sent"; return }
    notifyDevices.each { dev ->
        try { dev.deviceNotification(text) }
        catch (e) { log.error "WaterGuru Dosing Advisor: failed to notify ${dev?.displayName} — ${e.message}" }
    }
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
    def sampled = attrRaw("LastMeasurementHuman") ?: attrRaw("LastMeasurement")
    if (sampled) out << "Sampled: ${sampled}"
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

    // Determine the FC target.
    BigDecimal target
    BigDecimal minFc = null
    String basis
    if (numSet(fcTargetOverride)) {
        target = fcTargetOverride as BigDecimal
        basis = "manual target ${n1(target)} ppm"
    } else if (cya != null && cya > 0) {
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
        lines << "  ➕ ${verb}: ${flozUnits(floz)} of liquid chlorine (${n1(chlorinePct)}%) to raise FC ${n1(ppmGap)} ppm to target."
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
    } catch (e) {
        log.warn "WaterGuru Dosing Advisor: could not update the tile device — ${e.message}"
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
    if (info && info.trim() && !info.trim().equalsIgnoreCase("unknown")) return info.trim()
    def type = attrRaw("cassetteType")
    if (type && type.trim() && !type.trim().equalsIgnoreCase("unknown")) return type.trim()
    return null
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
