#!/usr/bin/env python3
"""
Mutation check for the Groovy execution suite (tests/groovy).

Each mutant is a copy of apps/ + tests/groovy/ under tests/mutation/out/<name>/ with EXACT text
edits applied to the child app. Every edit must match its anchor exactly `count` times (default 1)
or the build aborts, so a mutation that silently failed to apply can never be reported as a
"surviving" mutant. A mutant is KILLED when the suite fails against it.

Origin: the M01-M21 / PC1-PC2 set from the independent review of 5f218c9 (2.4.0), re-anchored to
the 2.4.1 code; F1-F6 revert one 2.4.1 fix each, R1-R9 one 2.4.2 fix each (WOR-718), W1-W3 one
2.4.3 fix each (WOR-724), X1-X12 one 2.4.4 fix each and XP1-XP3 one Pump Power Profiler 1.1.1 fix
each (WOR-731), Y1-Y2 one 2.4.5 change each (WOR-739), Z1-Z2 one 2.4.6 item each (WOR-740), L1-L8 one
2.4.7 change each, K1-K5 one fix each from the 2.4.7 verification round and J1-J8 one follow-up change each
(WOR-752), and every one of those
must be killed by the tests that guard it.

A mutant edits the child app unless it is declared with mutp(), which edits the profiler. Each mutant
runs the suite that covers its file (MUTATION.txt "suites:"); the base runs both.

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
PROFILER = "apps/PumpPowerProfiler.groovy"
SUITES = {APP: ["run_tests.groovy"], PROFILER: ["profiler_tests.groovy"]}

# name -> (expectation, description, [(old, new, count), ...], target file or None for the base)
V = {}


def mut(name, expect, desc, *edits, target=APP):
    V[name] = (expect, desc, [e if len(e) == 3 else (e[0], e[1], 1) for e in edits], target)


def mutp(name, expect, desc, *edits):
    mut(name, expect, desc, *edits, target=PROFILER)


mut("base", "control", "unmutated apps (must pass every check)", target=None)

# ---- positive controls from the review: guards the 2.4.0 suite already covered ----
mut("PC1", "killed", "readingFresh ignores the start anchor (cached pre-ON reading confirms)",
    ("    if (sinceMs != null && r.at < sinceMs) return false\n", ""))
mut("PC2", "killed", "armEmergencyPumpCutoff always opens a fresh window (postpones a due cutoff)",
    ("    if (existing != null && existing > nowMs) {\n        if (jobPending) {",
     "    if (false && existing != null && existing > nowMs) {\n        if (jobPending) {"))

# ---- stop-evidence anchors on the timer paths ----
mut("M01", "?", "verifyPumpOff accepts a cached pre-request OFF (anchor dropped)",
    ("    Long since = stopEvidenceAnchor(active)\n    try {\n        if (pumpIsOff(since)) {\n"
     "            finishStop(\"Chlorine pump confirmed OFF.\")",
     "    Long since = null   // MUTANT\n    try {\n        if (pumpIsOff(since)) {\n"
     "            finishStop(\"Chlorine pump confirmed OFF.\")"))
mut("M02", "?", "stopDose accepts a cached pre-request OFF (anchor dropped)",
    ("        Long since = stopEvidenceAnchor(active)\n        if (pumpIsOff(since)) {\n"
     "            finishStop(active ? scheduledStopMessage(active) : null)",
     "        Long since = null   // MUTANT\n        if (pumpIsOff(since)) {\n"
     "            finishStop(active ? scheduledStopMessage(active) : null)"))
mut("M03", "?", "emergencyPumpOff accepts a cached pre-request OFF (both anchors dropped)",
    ("    Long anchor = stopEvidenceAnchor(active)\n    if (pumpIsOff(anchor)) {",
     "    Long anchor = null   // MUTANT\n    if (pumpIsOff(anchor)) {"),
    ("    if (pumpIsOff(stopEvidenceAnchor(active))) {\n        finishStop(confirmed)\n",
     "    if (pumpIsOff(null)) {\n        finishStop(confirmed)\n"))

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
    ("        if (active != null && active.startConfirmed == true && active.offRequestedAt == null && powerConfirmationRequired()) {\n"
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

# ---- 2.4.2 (WOR-718): each fix reverted (must be killed) ----
mut("R1", "killed", "fix 1 reverted: a stop with nothing open gets no anchor (a cached OFF confirms it)",
    ("        state.idleStopAt = offAt\n", "        // MUTANT: no idle anchor\n"))
mut("R1b", "killed", "fix 1: an unconfirmed idle stop no longer blocks a new dose",
    ("    if (state.idleStopAt != null) blocks << \"a pump stop is still waiting for the switch to confirm OFF\"\n", ""))
mut("R1c", "killed", "fix 1: an announced stop notice keeps no final word for the OFF still to come",
    ("        state.pendingStopNotice = [confirmed: pending.confirmed ?: \"${pending.requested}: the switch has now confirmed OFF.\".toString(),\n"
     "                                   announced: true]\n", ""))
mut("R2", "killed", "fix 2 reverted: an unchanged ON restarts an aborted start's stop",
    ("                if (unchangedOn && active.offRequestedAt != null) {\n", "                if (false) {\n"))
mut("R2b", "killed", "fix 2 reverted: an unchanged ON restarts a standalone recovery's stop",
    ("        if (unchangedOn && anchor != null) {\n", "        if (false) {\n"))
# Since fix 1 initialize() restores an idle stop's jobs itself, so the reorder shows only when
# initialize() fails part way (it has already cleared the queue by then).
mut("R3", "killed", "fix 3 reverted: updated() stops the pump before the queue reset (the 2.4.1 body)",
    ("    unsubscribe()\n    try {\n        initialize()\n    } catch (e) {\n"
     "        log.error \"WaterGuru Dosing Advisor: initialize failed while saving (${e.message}); stopping the pump anyway. Save the app again once the cause is fixed.\"\n"
     "    }\n    safeStopPump(\"configuration changed\")\n}\n",
     "    safeStopPump(\"configuration changed\")\n    unsubscribe()\n    initialize()\n}\n"))
mut("R3b", "killed", "fix 3 reverted: removal goes through safeStopPump and its cutoff promises",
    ("    stopForRemoval()\n    unsubscribe()\n", "    safeStopPump(\"app removed\")\n    unsubscribe()\n"))
mut("R4", "killed", "fix 4 reverted: an overdue cutoff gets a fresh full window",
    ("    if (existing != null && existing <= nowMs) {\n", "    if (false && existing != null && existing <= nowMs) {\n"))
mut("R5", "killed", "fix 5 reverted: the final retry's EMERGENCY notice is sent the moment OFF is sent",
    ("            deferEmergencyNotice(\"EMERGENCY: chlorine pump OFF not freshly confirmed after ${attempt} OFF attempts\", true, tail,\n"
     "                                 \"Chlorine pump stopped on retry ${attempt}.\")\n",
     "            sendPumpNotice(\"EMERGENCY: chlorine pump OFF not freshly confirmed after ${attempt} OFF attempts (${switchStatusPhrase()}). ${tail}\")\n"))
mut("R5b", "killed", "fix 5 reverted: the cutoff's EMERGENCY notice is sent the moment OFF is sent",
    ("        deferEmergencyNotice(\"EMERGENCY: the cutoff has not been able to turn the chlorine pump off (attempt ${attempt})\", false,\n"
     "                             \"Still trying, but the pump may need to be stopped by hand.\", confirmed)\n",
     "        sendPumpNotice(\"EMERGENCY: the cutoff has not been able to turn the chlorine pump off (attempt ${attempt}). Still trying, but the pump may need to be stopped by hand.\")\n"))
mut("R6", "killed", "fix 6 reverted: the power watch keeps judging a run after its OFF request (all three guards)",
    ("    if (active.offRequestedAt != null) return\n    if (!powerConfirmationRequired()) return\n",
     "    if (!powerConfirmationRequired()) return\n"),
    ("    } else if (active.startConfirmed == true && active.offRequestedAt == null && powerConfirmationRequired()) {\n",
     "    } else if (active.startConfirmed == true && powerConfirmationRequired()) {\n"),
    ("        if (active != null && active.startConfirmed == true && active.offRequestedAt == null && powerConfirmationRequired()) {\n",
     "        if (active != null && active.startConfirmed == true && powerConfirmationRequired()) {\n"))
mut("R6a", "?", "fix 6, one guard only: verifyRunPower ignores offRequestedAt (the event and initialize guards remain)",
    ("    if (active.offRequestedAt != null) return\n    if (!powerConfirmationRequired()) return\n",
     "    if (!powerConfirmationRequired()) return\n"))
mut("R7", "killed", "fix 7 reverted: the cutoff latches nothing for an attempt that was never confirmed",
    ("    if (startUnconfirmed(active)) alertStartUnconfirmed(active, \"the emergency cutoff fired before the start was confirmed\")\n", ""))
mut("R8", "killed", "fix 8 reverted: a confirmed dose's events are not forced",
    ("publishTileTelemetry([fcVal: toBD(current.fcVal), fcTarget: toBD(current.fcTarget)], null, true)",
     "publishTileTelemetry([fcVal: toBD(current.fcVal), fcTarget: toBD(current.fcTarget)], null, false)"))
mut("R8b", "killed", "fix 8 reverted: a correction's events are not forced",
    ("            publishTileTelemetry(null, null, true)\n", "            publishTileTelemetry(null, null, false)\n"))
mut("R8c", "killed", "fix 8 reverted: an unknown tank inventory is left stale on the tile",
    ("    } else {\n        dev.sendEvent(name: \"tankRemainingMl\", value: UNKNOWN_TELEMETRY, unit: \"mL\")\n"
     "        dev.sendEvent(name: \"tankPercent\", value: UNKNOWN_TELEMETRY, unit: \"%\")\n    }\n", "    }\n"))
mut("R8d", "killed", "fix 8 reverted: a missing FC reading is left stale on the tile",
    ("    else                         dev.sendEvent(name: \"freeChlorine\", value: UNKNOWN_TELEMETRY, unit: \"ppm\")\n", ""))
mut("R9", "killed", "fix 9a reverted: Apply tank adjustment does not move the tank",
    ("    debitTank(before - after)                 // a negative debit puts chlorine back\n", ""))
mut("R9b", "killed", "fix 9b reverted: a voided dose stays in the dose history (the FC-loss estimate adds it back)",
    ("    state.tankDoseHistory = tankDoseHistory().findAll { !(it?.t instanceof Number && (it.t as Long) == t) }\n", ""))
mut("R9c", "killed", "fix 9b reverted: voiding the last dose leaves lastDose in place",
    ("    if (wasLast) state.remove(\"lastDose\")\n", ""))
mut("R9d", "killed", "fix 9b reverted: a void is not published to the historian",
    ("    boolean published = publishVoidedDose(t)\n", "    boolean published = false\n"))

# ---- 2.4.3 (WOR-724): each fix reverted (must be killed) ----
mut("W1", "killed", "fix 1 reverted: WaterGuru's maintenance steps pass through as dose advice",
    ("    def skipPhrases = [\"measure again\", \"see the advice\",\n"
     "                       \"cassette\", \"battery\", \"batteries\", \"calibrat\"]\n",
     "    def skipPhrases = [\"measure again\", \"see the advice\"]\n"))
mut("W2", "killed", "fix 2 reverted: the cassette line shows the pad count as tests left",
    ("    if (days) parts << days\n",
     "    parts << \"${attrRaw('CassetteChecksLeft')} tests left\"\n    if (days) parts << days\n"))
mut("W2b", "killed", "fix 2: a cassetteDaysLeft older than WaterGuru's text is still shown",
    ("    if (days != null && days >= 0G && text != null && !sourceStateNewer(\"CassetteTimeLeft\", \"cassetteDaysLeft\")) {\n",
     "    if (days != null && days >= 0G && text != null) {\n"))
mut("W3", "killed", "fix 3 reverted: the FC history is read when LastMeasurement arrives, before freeChlorine",
    ("    runIn(20, \"processNewSample\", [data: [sampleKey: key], overwrite: true])\n",
     "    recordFcSample(key)\n    runIn(20, \"processNewSample\", [data: [sampleKey: key], overwrite: true])\n"),
    ("    state.lastProcessedSample = key\n    recordFcSample(key)\n", "    state.lastProcessedSample = key\n"))
mut("W3b", "killed", "fix 3: readings stored before 2.4.3 still count in the measured loss",
    ("        if (hist[i-1]?.settled != true || hist[i]?.settled != true) continue\n", ""))


# ---- 2.4.4 (WOR-731): each fix reverted (must be killed) ----
mut("X1", "killed", "fix 1 reverted: finishStop ignores the deferred EMERGENCY notice's final word",
    ("    if (!msg && emergency?.confirmed) msg = emergency.confirmed.toString()\n", ""))
mut("X1b", "killed", "fix 1 reverted: an EMERGENCY notice that was sent keeps no final word for the OFF still to come",
    ("        state.pendingEmergencyNotice = [confirmed: pending.confirmed ?: \"${pending.head}: the switch has now confirmed OFF.\".toString(),\n"
     "                                        announced: true]\n", ""))
mut("X2", "killed", "fix 2 reverted: a booked run with no lastDose never reaches the historian",
    ("        publishDoseRecord(historyTime, ranMl, ranSec, \"the booked run\")\n", "        // MUTANT: not published\n"))
mut("X3", "killed", "fix 3 reverted: the cassette status adds no chip and leaves the tile GREEN",
    ("    String cassetteChip = cassetteReplaceChip()\n", "    String cassetteChip = null\n"))
mut("X3b", "killed", "fix 3 reverted: no cassette line without a cassette type",
    ("    if (!base && !parts) return null\n", "    if (!base) return null\n"))
mut("X4", "killed", "fix 4 reverted: an idle stop the switch never answers is never closed",
    ("            if (idleStopOffThroughout()) {\n", "            if (false) {\n"))
mut("X4b", "killed", "fix 4 unsafe: the idle close ignores what the switch reports (closes against an ON)",
    ("    return anchor != null && switchOffUnchangedSince(anchor)\n", "    return anchor != null\n"))
mut("X5", "killed", "fix 5 reverted: a lost ON answered by an unchanged OFF gets 'Requesting OFF' and 'stopped'",
    ("    if (wasActive && startNeverTookEffect(active)) {\n", "    if (false) {\n"))
mut("X5b", "killed", "fix 5 reverted: the start alert says 'Requesting OFF' when the switch has just reported OFF",
    ("    String stop = stopping ? \" Requesting OFF; the independent cutoff stays armed until the switch freshly reports OFF.\" : \"\"\n",
     "    String stop = \" Requesting OFF; the independent cutoff stays armed until the switch freshly reports OFF.\"\n"))
mut("X6", "killed", "fix 6 reverted: a power check long after the previous one judges stale evidence at once",
    ("    if (previousCheck != null && nowMs - previousCheck > 2L * RUN_POWER_CHECK_SECONDS * 1000L && active.powerCheckDeferred != true) {\n",
     "    if (false) {\n"))
mut("X6b", "killed", "fix 6 unsafe: every late power check is put off, so a silent run is never aborted",
    ("    if (previousCheck != null && nowMs - previousCheck > 2L * RUN_POWER_CHECK_SECONDS * 1000L && active.powerCheckDeferred != true) {\n",
     "    if (previousCheck != null && nowMs - previousCheck > 2L * RUN_POWER_CHECK_SECONDS * 1000L) {\n"))
mut("X7", "killed", "fix 7 reverted: a queue reset drops a sample that is waiting to be processed",
    ("        runIn(20, \"processNewSample\", [data: [sampleKey: pendingSample], overwrite: true])\n", ""))
mut("X8", "killed", "fix 8 reverted: every unchanged ON during an outside run re-arms and logs a WARN",
    ("        if (unchangedOn && emergencyCutoffPendingNotDue()) {\n", "        if (false) {\n"))
mut("X9", "killed", "fix 9 reverted: the confirmed-OFF notice carries the app name and no period",
    ("            finishStop(\"Chlorine pump confirmed OFF.\")\n",
     "            finishStop(\"WaterGuru Dosing Advisor: chlorine pump confirmed OFF\")\n"))
mut("X10", "killed", "fix 10 reverted: the status line counts settled samples, the runway line intervals",
    ("    if (m != null) return \"Currently using your measured loss (~${n1(m.rate)} ppm/day ${intervalCountText(m.n as Integer)}).\"\n",
     "    if (m != null) return \"Currently using your measured loss (~${n1(m.rate)} ppm/day from ${n} samples).\"\n"))
mut("X11", "killed", "fix 11 reverted: with a dose on record the tank runway still asks for a completed dose",
    ("            if (((runway.n ?: 0) as Integer) > 0) {\n", "            if (false) {\n"))
mut("X12", "killed", "fix 12 reverted: a day count is shown when WaterGuru's text is blank or unknown",
    ("    if (days != null && days >= 0G && text != null && !sourceStateNewer(\"CassetteTimeLeft\", \"cassetteDaysLeft\")) {\n",
     "    if (days != null && days >= 0G && !(text != null && sourceStateNewer(\"CassetteTimeLeft\", \"cassetteDaysLeft\"))) {\n"))

# ---- Pump Power Profiler 1.1.1 (WOR-731): each fix reverted (must be killed) ----
mutp("XP1", "killed", "profiler fix 1 reverted: a file the listing leaves out is written over (the listing alone decides)",
     ("    if (b != null && b.length > 0) {\n", "    if (present && b != null && b.length > 0) {\n"),
     ("    } else if (b == null && !present) {\n", "    } else if (!present) {\n"))
mutp("XP1b", "killed", "profiler fix 1 reverted: a read that returns 0 bytes is taken as an empty file and written over",
     ("    if (b != null && b.length > 0) {\n", "    if (b != null) {\n"))
mutp("XP2", "killed", "profiler fix 2 reverted: not singleThreaded",
     ("    singleThreaded: true,\n", ""))
mutp("XP3", "killed", "profiler fix 3 reverted: every value is read first and every date second",
     ("    Map sw = readingWithDate(\"switch\")\n    Map w = readingWithDate(\"power\")\n",
      "    Map vals = [sw: meterDev.currentValue(\"switch\", true), w: meterDev.currentValue(\"power\", true),\n"
      "                a: meterDev.currentValue(\"amperage\", true), v: meterDev.currentValue(\"voltage\", true),\n"
      "                e: meterDev.currentValue(\"energy\", true)]\n"
      "    Map sw = readingWithDate(\"switch\")\n    Map w = readingWithDate(\"power\")\n"),
     ("        sw : sw.value,\n        w  : w.value,\n        a  : a.value,\n        v  : v.value,\n        e  : e.value,\n",
      "        sw : vals.sw,\n        w  : vals.w,\n        a  : vals.a,\n        v  : vals.v,\n        e  : vals.e,\n"))

# ---- 2.4.5 (WOR-739): each change reverted (must be killed) ----
mut("Y1", "killed", "change 1 reverted: the plug's first unchanged OFF answer closes a lost ON before the hub's retries",
    ("        if (!hubRetriesSettled(active)) return\n", ""))
mut("Y2", "killed", "change 2 reverted: no second evening fetch when the daily refresh brings no new sample",
    ("    runIn(SOURCE_RETRY_DELAY_SECONDS, \"retryWaterGuruRefresh\",\n"
     "          [data: [before: attrRaw(\"LastMeasurement\") ?: \"\"], overwrite: true])\n", ""))

# ---- 2.4.6 (WOR-740): each item reverted (must be killed) ----
mut("Z1", "killed", "item 1 reverted: a stale or missing subscription stamp no longer blocks a start",
    ("    if (!subscriptionsCurrent()) blocks << \"the app's subscriptions are out of date after a code update; open the app and press Done\"\n", ""))
mut("Z2", "killed", "item 2 reverted: a cutoff equal to or below the maximum runtime no longer blocks a start",
    ("    String cutoffProblem = cutoffSettingProblem()\n    if (cutoffProblem) blocks << cutoffProblem\n", ""))


# ---- 2.4.7 (WOR-752): each change reverted (must be killed) ----
mut("L1", "killed", "skip rule reverted: a rise beyond the app's doses counts as no loss",
    ("        if (fb - (fa + added) >= FC_READING_STEP_PPM) {", "        if (false) {"))
mut("L2", "killed", "reading step dropped: a rise under one step from dose rounding is skipped too",
    ("        if (fb - (fa + added) >= FC_READING_STEP_PPM) {", "        if (fb > fa + added) {"))
mut("L3", "killed", "no-loss intervals dropped again: a balance at or below zero is left out instead of counted as 0",
    ("        BigDecimal balance = fa + added - fb\n",
     "        BigDecimal balance = fa + added - fb\n        if (balance <= 0G) continue\n"))
mut("L4", "killed", "skipped intervals are not counted",
    ("        if (outcomes[j] == null) skipped++\n", "        if (outcomes[j] == null) { }\n"))
mut("L5", "killed", "the summary has no FC loss line under the current readings",
    ("    if (lossLine) out << lossLine\n", ""))
mut("L6", "killed", "the FC loss figure ignores your fcLossPerDay setting",
    ("    if (numSet(fcLossPerDay) && (fcLossPerDay as BigDecimal) > 0) return [rate: fcLossPerDay as BigDecimal, source: \"setting\"]\n", ""))
mut("L7", "killed", "the daily summary notification leaves the FC loss figure out",
    ("pH ${n2(result?.pH)}.${loss}\"", "pH ${n2(result?.pH)}.\""))
mut("L8", "killed", "the FC loss line does not say an interval was skipped",
    ("\"; ${skipped} skipped, FC rose beyond this app's doses\"", "\"\""))

# ---- 2.4.7 verification round (WOR-752): each fix reverted (must be killed) ----
mut("K1", "killed", "the 0.3 ppm tolerance is back: the live hand run's day counts as no loss (0.1 ppm/day)",
    ("        if (fb - (fa + added) >= FC_READING_STEP_PPM) {", "        if (fb - (fa + added) > 0.3G) {"))
mut("K2", "killed", "a rise of exactly one reading step counts as no loss: a hand top-up at a shock reads as a day without loss",
    ("        if (fb - (fa + added) >= FC_READING_STEP_PPM) {", "        if (fb - (fa + added) > FC_READING_STEP_PPM) {"))
mut("K3", "killed", "nothing measured yet but an interval skipped: the line hides the skip",
    ("        if (none > 0) return", "        if (false) return"))
mut("K4", "killed", "the skip label claims chlorine was added outside the app",
    ("FC rose beyond this app's doses)\"\n        // Settled history",
     "chlorine added outside the app)\"\n        // Settled history"))
mut("K5", "killed", "the config page still asks for a couple of days of declines",
    ("(a measured rate replaces the estimate once there are ${FC_LOSS_RULE_TEXT}).",
     "(a couple of days of declines are needed before a measured rate replaces the estimate)."))

# ---- 2.4.7 follow-up round (WOR-752): each change reverted (must be killed) ----
mut("J1", "killed", "the empty FC loss line goes back to the old rule (a day apart with known doses)",
    ("        return \"FC loss: not measured yet (needs ${FC_LOSS_RULE_TEXT})\"",
     "        return \"FC loss: not measured yet (needs two samples a day apart with known doses)\""))
mut("J2", "killed", "the skipped count loses its noun: \"(1 skipped, ...)\"",
    ("(${none} interval${none == 1 ? '' : 's'} skipped,", "(${none} skipped,"))
mut("J3", "killed", "the runway claims no sample history when intervals were skipped",
    ("${hasHistory ? 'not measured yet' : 'no sample history yet'}", "no sample history yet"))
mut("J4", "killed", "the summary's FC loss line is built unguarded",
    ("    String lossLine = safeFcLossText(\"summary line\") { fcLossLine() }\n", "    String lossLine = fcLossLine()\n"))
mut("J5", "killed", "the daily notification's FC loss figure is built unguarded",
    ("    String loss = safeFcLossText(\"daily summary\") { fcLossDigestText() } ?: \"\"\n", "    String loss = fcLossDigestText()\n"))
mut("J6", "killed", "the runway is computed unguarded in computeAdvice",
    ("        try { runway = computeRunway(fc, cya) }\n", "        runway = computeRunway(fc, cya); try { }\n"))
mut("J7", "killed", "the old cutoff default is back (21 min)",
    ("DEFAULT_FAILSAFE_PUMP_RUN_MINUTES = 15G", "DEFAULT_FAILSAFE_PUMP_RUN_MINUTES = 21G"))
mut("J8", "killed", "the old maximum runtime default is back (20 min)",
    ("DEFAULT_MAX_PUMP_RUN_MINUTES = 14G", "DEFAULT_MAX_PUMP_RUN_MINUTES = 20G"))

# ---- 2.4.7 text round (WOR-752): each change reverted (must be killed) ----
mut("T1", "killed", "the config page's status line ignores your set rate",
    ("    if (f?.source == \"setting\") {\n        String rest", "    if (false) {\n        String rest"))
mut("T2", "killed", "the rule text goes back to \"without a rise beyond this app's doses\", FC unnamed",
    ("\"two samples at least 6 hours apart, with no rise in FC beyond this app's doses\"",
     "\"two samples at least 6 hours apart, without a rise beyond this app's doses\""))
mut("T3", "killed", "settled history with no usable interval is described as needing samples",
    ("        if (settledFcSampleCount() >= 2) return \"FC loss: not measured yet (no usable interval yet)\"\n", ""))
mut("T4", "killed", "the runway claims no sample history unless an interval was skipped",
    ("boolean hasHistory = settledFcSampleCount() >= 2", "boolean hasHistory = ((fcLossIntervals().skipped ?: 0) as int) > 0"))


def build(name):
    expect, desc, edits, target = V[name]
    dst = OUT / name
    if dst.exists():
        shutil.rmtree(dst)
    (dst / "tests").mkdir(parents=True)
    shutil.copytree(ROOT / "apps", dst / "apps")
    shutil.copytree(ROOT / "tests" / "groovy", dst / "tests" / "groovy")
    if target is not None:
        app = dst / target
        src = app.read_text()
        for old, new, count in edits:
            n = src.count(old)
            if n != count:
                sys.exit(f"ABORT {name}: anchor matched {n}x, expected {count}x:\n{old}")
            src = src.replace(old, new)
        app.write_text(src)
    suites = SUITES[target] if target is not None else sorted({s for v in SUITES.values() for s in v}, reverse=True)
    (dst / "MUTATION.txt").write_text(f"{name}: {desc}\nexpectation: {expect}\nsuites: {' '.join(suites)}\n")
    print(f"built {name:5s} [{expect:7s}] {desc}")


if __name__ == "__main__":
    for n in sys.argv[1:] or list(V):
        build(n)
