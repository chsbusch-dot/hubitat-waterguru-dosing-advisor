#!/usr/bin/env python3
"""
Mutation check for the Groovy execution suite (tests/groovy).

Each mutant is a copy of apps/ + tests/groovy/ under tests/mutation/out/<name>/ with EXACT text
edits applied to the child app. Every edit must match its anchor exactly `count` times (default 1)
or the build aborts, so a mutation that silently failed to apply can never be reported as a
"surviving" mutant. A mutant is KILLED when the suite fails against it.

Origin: the M01-M21 / PC1-PC2 set from the independent review of 5f218c9 (2.4.0), re-anchored to
the 2.4.1 code; F1-F6 revert one 2.4.1 fix each and must be killed by the tests that guard it.

  python3 tests/mutation/mutants.py              # build every mutant
  python3 tests/mutation/mutants.py M04 F1       # build only these
  tests/mutation/run.sh                          # build all, run all, print the table

Anchors are exact strings, so they rot as the app changes. A failed anchor aborts loudly; re-anchor
the mutant (or retire it) rather than loosening the match.
"""
import pathlib
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "tests" / "mutation" / "out"
APP = "apps/WaterGuru-Dosing-Advisor-Child.groovy"

# name -> (expectation, description, [(old, new, count), ...])
V = {}


def mut(name, expect, desc, *edits):
    V[name] = (expect, desc, [e if len(e) == 3 else (e[0], e[1], 1) for e in edits])


mut("base", "control", "unmutated app (must pass every check)")

# ---- positive controls from the review: guards the 2.4.0 suite already covered ----
mut("PC1", "killed", "readingFresh ignores the start anchor (cached pre-ON reading confirms)",
    ("    if (sinceMs != null && r.at < sinceMs) return false\n", ""))
mut("PC2", "killed", "armEmergencyPumpCutoff always opens a fresh window (postpones a due cutoff)",
    ("    if (existing != null && existing > nowMs) {\n        if (jobPending) {",
     "    if (false && existing != null && existing > nowMs) {\n        if (jobPending) {"))

# ---- stop-evidence anchors on the timer paths ----
mut("M01", "?", "verifyPumpOff accepts a cached pre-request OFF (anchor dropped)",
    ("    Long since = stopEvidenceAnchor(active)\n    try {\n        if (pumpIsOff(since)) {\n"
     "            finishStop(\"WaterGuru Dosing Advisor: chlorine pump confirmed OFF\")",
     "    Long since = null   // MUTANT\n    try {\n        if (pumpIsOff(since)) {\n"
     "            finishStop(\"WaterGuru Dosing Advisor: chlorine pump confirmed OFF\")"))
mut("M02", "?", "stopDose accepts a cached pre-request OFF (anchor dropped)",
    ("        Long since = stopEvidenceAnchor(active)\n        if (pumpIsOff(since)) {\n"
     "            finishStop(active ? scheduledStopMessage(active) : null)",
     "        Long since = null   // MUTANT\n        if (pumpIsOff(since)) {\n"
     "            finishStop(active ? scheduledStopMessage(active) : null)"))
mut("M03", "?", "emergencyPumpOff accepts a cached pre-request OFF (both anchors dropped)",
    ("    Long anchor = stopEvidenceAnchor(active)\n    if (pumpIsOff(anchor)) {",
     "    Long anchor = null   // MUTANT\n    if (pumpIsOff(anchor)) {"),
    ("    if (pumpIsOff(stopEvidenceAnchor(active))) {\n        finishStop(\"EMERGENCY cutoff",
     "    if (pumpIsOff(null)) {\n        finishStop(\"EMERGENCY cutoff"))

# ---- doseSafetyBlocks interlocks ----
mut("M04", "?", "no 'a dose is already running' interlock",
    ("    if (state.activeDose) blocks << \"a dose is already running\"\n", ""))
mut("M05", "?", "no 'pump switch is already on' interlock",
    ("    if (pumpSwitch?.currentValue(\"switch\")?.toString() == \"on\") blocks << \"pump switch is already on\"\n", ""))
mut("M06", "?", "no 'stop is still being recovered' (faultStopAt) gate",
    ("    if (state.faultStopAt != null) blocks << \"a pump stop is still being recovered\"\n", ""))
mut("M07", "?", "no AUTO dosing-window block",
    ("    if ((dosingMode ?: \"ADVISORY\").toString() == \"AUTO\" && !autoDoseWindowOpen()) {\n"
     "        blocks << \"outside the configured AUTO dosing window\"\n    }\n", ""))
mut("M08", "?", "no pH safety block",
    ("    if (ph == null) blocks << \"pH reading is unavailable\"\n"
     "    else if ((loPh != null && ph < loPh) || (hiPh != null && ph > hiPh)) blocks << \"pH ${n2(ph)} is outside ${n2(loPh)}\u2013${n2(hiPh)}\"\n",
     "    // MUTANT: pH guard removed\n"))
mut("M09", "?", "no sample-age block",
    ("    else if (maxAge != null && (now() - measured) > (maxAge * 3600000G)) blocks << \"WaterGuru sample is older than ${n1(maxAge)} hours\"\n", ""))
mut("M10", "?", "no insufficient-tank block",
    ("    } else if (ml != null && tankRemaining != null && ml > tankRemaining) {\n"
     "        blocks << \"chlorine tank has only ${n0(tankRemaining)} mL remaining; dose needs ${n0(ml)} mL\"\n    }\n",
     "    }\n"))
mut("M11", "?", "no circulation interlock (switch or 24/7 confirmation)",
    ("    if (circulationSwitch && requireCirculationOn != false && circulationSwitch.currentValue(\"switch\")?.toString() != \"on\") {\n"
     "        blocks << \"circulation/filter switch is not on\"\n    }\n"
     "    if (!circulationSwitch && circulationAlwaysOn != true) {\n"
     "        blocks << \"no circulation interlock or 24/7 circulation confirmation\"\n    }\n", ""))
mut("M12", "?", "no single-dose volume limit and no runtime limit",
    ("    if (ml != null && maxSingle != null && ml > maxSingle) blocks << \"${n0(ml)} mL exceeds the ${n0(maxSingle)} mL single-dose limit\"\n", ""),
    ("    if (seconds != null && maxMinutes != null && seconds > (maxMinutes * 60G)) blocks << \"${formatDuration(seconds)} exceeds the ${n1(maxMinutes)} minute runtime limit\"\n", ""))

# ---- start / run / fault lifecycle ----
mut("M13", "?", "power-capable start confirmed WITHOUT a fresh ON (power alone)",
    ("        if (switchOnFresh && powerFresh && watts != null && watts > 0G && watts >= startPowerMinWatts()) {",
     "        if (powerFresh && watts != null && watts > 0G && watts >= startPowerMinWatts()) {"))
mut("M14", "?", "power loss latches a fault but never requests OFF",
    ("    safeStopPump(\"the confirmed run lost power\", false)\n", "    // MUTANT: no stop on power loss\n"))
mut("M15", "?", "acknowledgement ignores an active (unfinished) stop",
    ("    if (state.activeDose != null) {\n"
     "        sendPumpNotice(\"Cannot acknowledge the pump fault yet: a stop is still being recovered. Wait until the switch freshly reports OFF.\")\n"
     "        return\n    }\n", ""))
mut("M16", "?", "acknowledgement accepts an OFF of any age (anchor dropped)",
    ("    if (!pumpIsOff(requireAfter)) {", "    if (!pumpIsOff(null)) {"))
mut("M17", "?", "late ON during an aborted (active) attempt no longer requests OFF",
    ("                stopOnlyForPendingFault(\"an aborted start is still unconfirmed\")",
     "                // MUTANT: no immediate OFF"))
mut("M18", "?", "pumpIsOff accepts a future-dated OFF",
    ("        if (futureDated(at)) return false   // future-dated OFF\n", ""))
mut("M19", "?", "initialize does not recreate the running power watch",
    ("        if (active != null && active.startConfirmed == true && powerConfirmationRequired()) {\n"
     "            runIn(RUN_POWER_CHECK_SECONDS, \"verifyRunPower\", [overwrite: true])\n"
     "            log.warn \"WaterGuru Dosing Advisor: recreated the running power watch after the queue reset\"\n"
     "        }\n", ""))
mut("M20", "?", "verifyStartConfirmation ignores an OFF request (startStopRequested guard removed)",
    ("    if (startStopRequested(active)) return   // an OFF was requested; a late report must not confirm\n", ""))
# Re-anchored: 2.4.1 split handlePumpPowerEvent's guard so evidence is noted before it.
mut("M21", "?", "power event ignores an OFF request (startStopRequested guard removed)",
    ("    if (active.fault != null || startStopRequested(active)) return\n",
     "    if (active.fault != null) return\n"))

# ---- 2.4.1: each fix reverted (must be killed) ----
mut("F1", "killed", "fix 1 reverted: pumpIsOff ignores the recorded OFF receipt time",
    ("        Long seen = latestOf(at, offEvidenceAt())\n", "        Long seen = at\n"))
mut("F1b", "killed", "fix 1 reverted: a fault's acknowledgement floor is its latch time again",
    ("                        offFrom: offFrom ?: now()]\n", "                        offFrom: now()]\n"))
mut("F2", "killed", "fix 2 reverted: run power freshness ignores the receipt time of power events",
    ("    Long seenAt = usable ? latestOf(pw.at as Long, active.lastPowerEventAt instanceof Number ? (active.lastPowerEventAt as Long) : null) : null\n",
     "    Long seenAt = usable ? (pw.at as Long) : null\n"))
mut("F3", "killed", "fix 3 reverted: the stop notice is checked the moment OFF is sent",
    ("    runIn(STOP_CONFIRM_SECONDS, \"verifyStopRequest\", [overwrite: true])\n}\n",
     "    verifyStopRequest()\n}\n"))
mut("F4", "killed", "fix 4 reverted: every run stays booked at its planned volume",
    ("    if (run == null) return null\n    Long onAt = run.onReportAt",
     "    if (true) return null\n    Long onAt = run.onReportAt"))
mut("F5", "killed", "fix 5 reverted: the FC-loss estimate ignores the app's own doses",
    ("    List doses = appDoseEvents()\n", "    List doses = []\n"))
mut("F6", "killed", "noteOffEvidence keeps a future-dated OFF report",
    ("    if (at == null || futureDated(at)) return\n    Map sw = readDeviceReading(\"switch\")",
     "    if (at == null) return\n    Map sw = readDeviceReading(\"switch\")"))


def build(name):
    expect, desc, edits = V[name]
    dst = OUT / name
    if dst.exists():
        shutil.rmtree(dst)
    (dst / "tests").mkdir(parents=True)
    shutil.copytree(ROOT / "apps", dst / "apps")
    shutil.copytree(ROOT / "tests" / "groovy", dst / "tests" / "groovy")
    app = dst / APP
    src = app.read_text()
    for old, new, count in edits:
        n = src.count(old)
        if n != count:
            sys.exit(f"ABORT {name}: anchor matched {n}x, expected {count}x:\n{old}")
        src = src.replace(old, new)
    app.write_text(src)
    (dst / "MUTATION.txt").write_text(f"{name}: {desc}\nexpectation: {expect}\n")
    print(f"built {name:5s} [{expect:7s}] {desc}")


if __name__ == "__main__":
    for n in sys.argv[1:] or list(V):
        build(n)
