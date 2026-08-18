/*
 * WaterGuru Dosing Advisor
 *
 * A Hubitat app that turns a WaterGuru reading into an actual "add X of
 * chemical Y" recommendation and sends it to your notification device(s).
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
 *   1.0.0 - Initial release: SLAM/CYA-aware FC dosing, WaterGuru doseAdvice
 *           pass-through, generic fallback formulas, single-message delivery to
 *           capability.notification devices, notify-on-new-sample + manual
 *           calculate/preview buttons.
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

import java.math.RoundingMode
import groovy.transform.Field

def appVersion() { "1.0.0" }

definition(
    name:        "WaterGuru Dosing Advisor",
    namespace:   "chsbusch-dot",
    author:      "Chris Busch",
    description: "Turns a WaterGuru reading into an actual dose (how much of which chemical) and notifies you. SLAM/CYA-aware free-chlorine math; passes through WaterGuru's own pH/TA/CH/CYA advice.",
    category:    "Convenience",
    iconUrl:     "",
    iconX2Url:   "",
    importUrl:   "https://raw.githubusercontent.com/chsbusch-dot/hubitat-waterguru-dosing-advisor/main/apps/WaterGuru-Dosing-Advisor.groovy",
    singleInstance: false
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

// ---------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------

def mainPage() {
    dynamicPage(name: "mainPage", title: "WaterGuru Dosing Advisor", uninstall: true, install: true) {

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

        section("<b>Pool volume &amp; product strengths</b> <small>(blank = read from the WaterGuru device)</small>") {
            input "volumeOverride", "decimal",
                title: "Pool volume (gallons) — blank uses the device's poolVolume", required: false
            input "chlorinePctOverride", "decimal",
                title: "Liquid chlorine strength % — blank uses the device's chlorineProductPct (fallback 12.5)", required: false
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
                    title: "Acid type for pH/TA down (blank uses the device's acidType)",
                    options: ["MURIATIC", "BISULFATE"], required: false
                input "muriaticPctOverride", "decimal",
                    title: "Muriatic acid strength % (fallback 31.45)", required: false
                input "bisulfatePctOverride", "decimal",
                    title: "Dry acid (sodium bisulfate) strength % (fallback 93.2)", required: false
            }
        }

        section("<b>Target overrides</b> <small>(blank = read from the WaterGuru device)</small>") {
            input "phTargetOverride",  "decimal", title: "pH target — blank uses device pHTarget (fallback 7.5)",  required: false
            input "taTargetOverride",  "decimal", title: "Total alkalinity target ppm — blank uses device totalAlkalinityTarget (fallback 80)",  required: false
            input "cyaTargetOverride", "decimal", title: "CYA target ppm — blank uses device cyanuricAcidTarget (fallback 40)", required: false
            input "chTargetOverride",  "decimal", title: "Calcium hardness target ppm — blank uses device calciumHardnessTarget (fallback 300)", required: false
        }

        section("<b>Delivery</b>") {
            input "notifyDevices", "capability.notification",
                title: "Notification device(s) to send the recommendation to",
                required: false, multiple: true
            input "autoRun", "bool",
                title: "Notify automatically on each new WaterGuru sample",
                defaultValue: true
        }

        section("<b>Run now</b>") {
            input name: "btnCalcNow", type: "button", title: "Calculate &amp; send now"
            input name: "btnPreview", type: "button", title: "Preview (log only)"
            if (state.lastPreview) {
                paragraph "<b>Last preview:</b>\n<pre style='white-space:pre-wrap'>${state.lastPreview}</pre>"
            }
        }

        section {
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: true
            paragraph "<small>v${appVersion()} — doses are estimates; always confirm with your own test kit before adding chemicals.</small>"
        }
    }
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

def initialize() {
    if (autoRun != false && sourceDevice) {
        // New WaterGuru sample => LastMeasurement (a DATE attribute) changes.
        subscribe(sourceDevice, "LastMeasurement", "onNewSample")
        logDebug "Subscribed to new-sample events on ${sourceDevice.displayName}"
    }
}

def onNewSample(evt) {
    logDebug "New WaterGuru sample (${evt?.value}); computing dose advice"
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

    def out = []
    def warnings = []
    boolean anyAction = false

    out << "🌊 WaterGuru Dosing Advisor — ${label}"
    def sampled = attrRaw("LastMeasurementHuman") ?: attrRaw("LastMeasurement")
    if (sampled) out << "Sampled: ${sampled}"
    out << ""

    if (!volume || volume <= 0) {
        out << "⚠ Pool volume unknown. Set a volume override or point at a device that reports poolVolume."
        return [text: out.join("\n"), anyAction: false]
    }

    // ---- 1) FREE CHLORINE — computed here, SLAM/CYA aware -------------------
    out << "FREE CHLORINE (computed)"
    def fcLines = computeFc(fc, cya, volume, chlorinePct, warnings)
    out.addAll(fcLines.lines)
    anyAction = anyAction || fcLines.action
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
        } else {
            out << "  • None — WaterGuru reports no pH / TA / CH / CYA adjustment needed."
        }
    } else {
        out << "pH / TA / CH / CYA (generic estimates)"
        def gen = computeGeneric(ph, ta, ch, cya, phTarget, taTarget, chTarget, cyaTarget, volume, warnings)
        if (gen.lines) {
            out.addAll(gen.lines)
            anyAction = anyAction || gen.action
        } else {
            out << "  • All within target (or readings unavailable) — nothing to add."
        }
    }

    // ---- Footer ------------------------------------------------------------
    out << ""
    warnings.unique().each { out << "⚠ ${it}" }
    out << "⚠ Estimates only — confirm with your own test kit before adding chemicals."
    if (!anyAction) out << "✅ No chemical additions indicated right now."

    return [text: out.join("\n"), anyAction: anyAction]
}

/** Free-chlorine dose: SLAM/CYA-aware target, converted to liquid chlorine. */
private Map computeFc(BigDecimal fc, BigDecimal cya, BigDecimal volume, BigDecimal chlorinePct, List warnings) {
    def lines = []
    boolean action = false

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
        return [lines: lines, action: false]
    }

    lines << "  FC ${n1(fc)} ppm  →  target ${n1(target)} ppm  (${basis})"

    if (fc < target) {
        BigDecimal ppmGap = target - fc
        BigDecimal pctFactor = (chlorinePct && chlorinePct > 0) ? (12.5G / chlorinePct) : 1G
        BigDecimal floz = ppmGap * (volume / 10000G) * CL_FLOZ_PER_PPM_PER_10K_AT_12_5 * pctFactor
        boolean optional = (minFc != null && fc >= minFc)   // non-SLAM: above min but below target
        String verb = optional ? "Optional top-up" : "Add"
        lines << "  ➕ ${verb}: ${flozUnits(floz)} of liquid chlorine (${n1(chlorinePct)}%) to raise FC ${n1(ppmGap)} ppm to target."
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
    return [lines: lines, action: action]
}

/** Keep WaterGuru advice lines; drop chlorine lines unless the user opts in. */
private List filterWgAdvice(String doseAdvice) {
    if (!doseAdvice || doseAdvice.trim().equalsIgnoreCase("None")) return []
    def lines = doseAdvice.split("\n").collect { it.trim() }.findAll { it }
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
    boolean action = false
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
        } else {
            BigDecimal pct = firstNum(muriaticPctOverride, attrNum("acidMuriaticPct"), MURIATIC_BASE_PCT)
            BigDecimal floz = muriaticFloz * (MURIATIC_BASE_PCT / pct)
            lines << "  ➕ pH ${n1(ph)} → target ${n1(phTarget)}: add ~${flozUnits(floz)} of muriatic acid (${n1(pct)}%)."
        }
        action = true
    } else if (ph != null && ph < phTarget) {
        lines << "  • pH ${n1(ph)} low (target ${n1(phTarget)}): aerate to raise, or add soda ash per product directions (no amount estimated — base is easy to overshoot)."
        action = true
    }

    // TA low -> baking soda.
    if (ta != null && ta < taTarget) {
        BigDecimal lb = ((taTarget - ta) / 10G) * BAKING_SODA_LB_PER_10PPM_TA_PER_10K * v10k
        lines << "  ➕ TA ${n0(ta)} → target ${n0(taTarget)}: add ~${n2(lb)} lb baking soda (sodium bicarbonate)."
        action = true
    } else if (ta != null && ta > taTarget + 20G) {
        lines << "  • TA ${n0(ta)} high (target ${n0(taTarget)}): lower by adding acid and aerating (this also lowers pH); repeat gradually."
        action = true
    }

    // CH low -> calcium chloride; CH very high -> partial drain.
    if (ch != null && ch < chTarget) {
        BigDecimal oz = (chTarget - ch) * CAL_CL_OZ_PER_PPM_CH_PER_10K * v10k
        lines << "  ➕ CH ${n0(ch)} → target ${n0(chTarget)}: add ~${ozLbUnits(oz)} calcium chloride."
        action = true
    } else if (ch != null && ch > chTarget + 50G) {
        BigDecimal frac = (1G - (chTarget / ch)) * 100G
        lines << "  • CH ${n0(ch)} high (target ${n0(chTarget)}): no additive lowers CH — partial drain/refill ~${n0(frac)}% (~${n0(volume * frac / 100G)} gal)."
        action = true
    }

    // CYA low -> stabilizer; CYA high -> partial drain.
    if (cya != null && cya < cyaTarget) {
        BigDecimal oz = ((cyaTarget - cya) / 10G) * CYA_OZ_PER_10PPM_PER_10K * v10k
        lines << "  ➕ CYA ${n0(cya)} → target ${n0(cyaTarget)}: add ~${ozLbUnits(oz)} cyanuric acid (stabilizer)."
        action = true
    } else if (cya != null && cya > cyaTarget) {
        BigDecimal frac = (1G - (cyaTarget / cya)) * 100G
        lines << "  • CYA ${n0(cya)} high (target ${n0(cyaTarget)}): no additive lowers CYA — partial drain/refill ~${n0(frac)}% (~${n0(volume * frac / 100G)} gal)."
        action = true
    }

    return [lines: lines, action: action]
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

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

private BigDecimal toBD(def v) {
    if (v == null) return null
    try { return new BigDecimal(v.toString().trim()) } catch (ignored) { return null }
}

private String n0(def v) { fmt(v, 0) }
private String n1(def v) { fmt(v, 1) }
private String n2(def v) { fmt(v, 2) }
private String fmt(def v, int dp) {
    if (v == null) return "?"
    try { return (v as BigDecimal).setScale(dp, RoundingMode.HALF_UP).toString() }
    catch (ignored) { return v.toString() }
}

private void logDebug(String msg) { if (logEnable != false) log.debug msg }
