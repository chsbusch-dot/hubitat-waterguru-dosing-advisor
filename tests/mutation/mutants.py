#!/usr/bin/env python3
"""
Mutation check for the Groovy execution suite (tests/groovy).

Each mutant is a copy of apps/ + tests/groovy/ under tests/mutation/out/<name>/ with EXACT text
edits applied to the child app. Every edit must match its anchor exactly `count` times (default 1)
or the build aborts, so a mutation that silently failed to apply can never be reported as a
"surviving" mutant. A mutant is KILLED when the suite fails against it.

Origin: the M01-M21 / PC1-PC2 set from the independent review of 5f218c9 (2.4.0), re-anchored to
the 2.4.1 code and asserted (killed) since 2.5.0, M21 retired as equivalent; F1-F6 revert one 2.4.1 fix each, R1-R9 one 2.4.2 fix each (WOR-718), W1-W3 one
2.4.3 fix each (WOR-724), X1-X12 one 2.4.4 fix each and XP1-XP3 one Pump Power Profiler 1.1.1 fix
each (WOR-731), Y1-Y2 one 2.4.5 change each (WOR-739), Z1-Z2 one 2.4.6 item each (WOR-740), L1-L8 one
2.4.7 change each, K1-K5 one fix each from the 2.4.7 verification round and J1-J8 one follow-up change each
(WOR-752), S1-S16 one 2.4.8 SLAM-cap behaviour each, TG1-TG12 one SLAM plausibility-guard rule each
(WOR-755; TG, since T1-T4 were taken), for WOR-716 PI1 (the dosing app's own refresh), E1-E16 one
Pump Power Profiler 1.2.0 sampling behaviour each and WM1-WM17 one wear-monitor rule each, and for WOR-741
U1-U4 one 2.5.0 save rule each, N1-N6 one notice-slot or stopOpen() rule each and D1-D5 one dash put back each
(D5 in the parent app), and for WOR-857 C1-C13 one 2.5.1 behaviour each (caps outside SLAM, SLAM off by
default, the profiler in the manifest; S1-S3, TG1-TG7 re-anchored to doseCap/fcReadingProblem, S2 retired),
and for WOR-921 H1-H16 one 2.5.2 FC-ceiling rule each (the 10.0 reading, the WaterGuru very high signals, the
plain high alert and the decrease advice kept out, the target warning, two dashes put back, and the 0.0 / big
drop reason winning over the ceiling);
every one of those must be killed by the tests that guard it.

A mutant edits the child app unless it is declared with mutp(), which edits the profiler, or with
target=PARENT or target=MANIFEST, which edit the parent app or packageManifest.json. Each mutant
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
PARENT = "apps/WaterGuru-Dosing-Advisor.groovy"
MANIFEST = "packageManifest.json"
SUITES = {APP: ["run_tests.groovy"], PROFILER: ["profiler_tests.groovy"], PARENT: ["run_tests.groovy"],
          MANIFEST: ["profiler_tests.groovy"]}

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
mut("M01", "killed", "verifyPumpOff accepts a cached pre-request OFF (anchor dropped)",
    ("    Long since = stopEvidenceAnchor(active)\n    try {\n        if (pumpIsOff(since)) {\n"
     "            finishStop(\"Chlorine pump confirmed OFF.\")",
     "    Long since = null   // MUTANT\n    try {\n        if (pumpIsOff(since)) {\n"
     "            finishStop(\"Chlorine pump confirmed OFF.\")"))
# Re-anchored in 2.5.0: the planned stop, STOP, a save, the retries and the cutoff send their OFF through one tail.
mut("M02", "killed", "the shared stop tail accepts a cached pre-request OFF (anchor dropped after every OFF)",
    ("    if (pumpIsOff(stopEvidenceAnchor(active))) {\n        finishStop(confirmed)\n        return true\n",
     "    if (pumpIsOff(null)) {\n        finishStop(confirmed)\n        return true\n"))
mut("M03", "killed", "emergencyPumpOff's check before its OFF accepts a cached pre-request OFF (anchor dropped)",
    ("    Long anchor = stopEvidenceAnchor(active)\n    if (pumpIsOff(anchor)) {",
     "    Long anchor = null   // MUTANT\n    if (pumpIsOff(anchor)) {"))

# ---- doseSafetyBlocks interlocks ----
mut("M04", "killed", "no 'a dose is already running' interlock",
    ("    if (state.activeDose) blocks << \"a dose is already running\"\n", ""))
mut("M05", "killed", "no 'pump switch is already on' interlock",
    ("    if (pumpSwitch?.currentValue(\"switch\")?.toString() == \"on\") blocks << \"pump switch is already on\"\n", ""))
mut("M06", "killed", "no 'stop is still being recovered' (faultStopAt) gate",
    ("    if (state.faultStopAt != null) blocks << \"a pump stop is still being recovered\"\n", ""))
mut("M07", "killed", "no AUTO dosing-window block",
    ("    if ((dosingMode ?: \"ADVISORY\").toString() == \"AUTO\" && !autoDoseWindowOpen()) {\n"
     "        blocks << \"outside the configured AUTO dosing window\"\n    }\n", ""))
mut("M08", "killed", "no pH safety block",
    ("    if (ph == null) blocks << \"pH reading is unavailable\"\n"
     "    else if ((loPh != null && ph < loPh) || (hiPh != null && ph > hiPh)) blocks << \"pH ${n2(ph)} is outside ${n2(loPh)} to ${n2(hiPh)}\"\n",
     "    // MUTANT: pH guard removed\n"))
mut("M09", "killed", "no sample-age block",
    ("    else if (maxAge != null && (now() - measured) > (maxAge * 3600000G)) blocks << \"WaterGuru sample is older than ${n1(maxAge)} hours\"\n", ""))
mut("M10", "killed", "no insufficient-tank block",
    ("    } else if (ml != null && tankRemaining != null && ml > tankRemaining) {\n"
     "        blocks << \"chlorine tank has only ${n0(tankRemaining)} mL remaining; dose needs ${n0(ml)} mL\"\n    }\n",
     "    }\n"))
mut("M11", "killed", "no circulation interlock (switch or 24/7 confirmation)",
    ("    if (circulationSwitch && requireCirculationOn != false && circulationSwitch.currentValue(\"switch\")?.toString() != \"on\") {\n"
     "        blocks << \"circulation/filter switch is not on\"\n    }\n"
     "    if (!circulationSwitch && circulationAlwaysOn != true) {\n"
     "        blocks << \"no circulation interlock or 24/7 circulation confirmation\"\n    }\n", ""))
mut("M12", "killed", "no single-dose volume limit and no runtime limit",
    ("    if (ml != null && maxSingle != null && ml > maxSingle) blocks << \"${n0(ml)} mL exceeds the ${n0(maxSingle)} mL single-dose limit\"\n", ""),
    ("    if (seconds != null && maxMinutes != null && seconds > (maxMinutes * 60G)) blocks << \"${formatDuration(seconds)} exceeds the ${n1(maxMinutes)} minute runtime limit\"\n", ""))

# ---- start / run / fault lifecycle ----
mut("M13", "killed", "power-capable start confirmed WITHOUT a fresh ON (power alone)",
    ("        if (switchOnFresh && powerFresh && watts != null && watts > 0G && watts >= startPowerMinWatts()) {",
     "        if (powerFresh && watts != null && watts > 0G && watts >= startPowerMinWatts()) {"))
mut("M14", "killed", "power loss latches a fault but never requests OFF",
    ("    safeStopPump(\"the confirmed run lost power\", false)\n", "    // MUTANT: no stop on power loss\n"))
mut("M15", "killed", "acknowledgement ignores an active (unfinished) stop",
    ("    if (state.activeDose != null) {\n"
     "        sendPumpNotice(\"Cannot acknowledge the pump fault yet: a stop is still being recovered. Wait until the switch freshly reports OFF.\")\n"
     "        return\n    }\n", ""))
mut("M16", "killed", "acknowledgement accepts an OFF of any age (anchor dropped)",
    ("    if (!pumpIsOff(requireAfter)) {", "    if (!pumpIsOff(null)) {"))
mut("M17", "killed", "late ON during an aborted (active) attempt no longer requests OFF",
    ("                stopOnlyForPendingFault(\"an aborted start is still unconfirmed\")",
     "                // MUTANT: no immediate OFF"))
mut("M18", "killed", "pumpIsOff accepts a future-dated OFF",
    ("        if (futureDated(at)) return false   // future-dated OFF\n", ""))
# M19 (initialize does not recreate the running power watch) is retired in 2.5.0 with the queue reset it
# undid: nothing clears the power watch at a save any more, and U1 puts the clearing back.
mut("M20", "killed", "verifyStartConfirmation ignores an OFF request (startStopRequested guard removed)",
    ("    if (startStopRequested(active)) return   // an OFF was requested; a late report must not confirm\n", ""))
# M21 (power event ignores an OFF request) is retired in 2.5.0 as an equivalent mutant: every path that requests
# OFF for an unconfirmed attempt latches its fault first, and verifyStartConfirmation, where the event would go
# next, repeats the startStopRequested guard. No test can tell it from the base.

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
# Re-anchored in 2.5.0: stop and EMERGENCY notices share one slot, so this also covers X1b.
mut("R1c", "killed", "fix 1: an announced stop or EMERGENCY notice keeps no final word for the OFF still to come",
    ("        state.pendingStopNotice = [confirmed: pending.confirmed ?: \"${head}: the switch has now confirmed OFF.\".toString(),\n"
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
    ("            deferNotice(\"EMERGENCY: chlorine pump OFF not freshly confirmed after ${attempt} OFF attempts\",\n"
     "                        \"Chlorine pump stopped on retry ${attempt}.\", tail, true)\n",
     "            sendPumpNotice(\"EMERGENCY: chlorine pump OFF not freshly confirmed after ${attempt} OFF attempts (${switchStatusPhrase()}). ${tail}\")\n"))
mut("R5b", "killed", "fix 5 reverted: the cutoff's EMERGENCY notice is sent the moment OFF is sent",
    ("        deferNotice(\"EMERGENCY: the cutoff has not been able to turn the chlorine pump off (attempt ${attempt})\", confirmed,\n"
     "                    \"Still trying, but the pump may need to be stopped by hand.\")\n",
     "        sendPumpNotice(\"EMERGENCY: the cutoff has not been able to turn the chlorine pump off (attempt ${attempt}). Still trying, but the pump may need to be stopped by hand.\")\n"))
mut("R6", "killed", "fix 6 reverted: the power watch keeps judging a run after its OFF request (both guards; the initialize one went in 2.5.0)",
    ("    if (active.offRequestedAt != null) return\n    if (!powerConfirmationRequired()) return\n",
     "    if (!powerConfirmationRequired()) return\n"),
    ("    } else if (active.startConfirmed == true && active.offRequestedAt == null && powerConfirmationRequired()) {\n",
     "    } else if (active.startConfirmed == true && powerConfirmationRequired()) {\n"))
mut("R6a", "killed", "fix 6, one guard only: verifyRunPower ignores offRequestedAt (a check queued behind the STOP latches a false power loss)",
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
# Re-anchored in 2.5.0 to the one notice slot; X1b (an EMERGENCY that was sent keeps no final word) is the same
# line as R1c now and is retired.
mut("X1", "killed", "fix 1 reverted: finishStop ignores the deferred notice's final word (stop or EMERGENCY)",
    ("    if (!msg && pending?.confirmed) msg = pending.confirmed.toString()\n", ""))
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
# X7 (a queue reset drops a sample waiting to be processed) is retired in 2.5.0: the processing job survives a
# save, so there is no rescheduling left to revert. U1 (the queue reset back) drops the sample again.
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


# ---- 2.4.8: SLAM doses capped to the limits instead of blocked (must be killed) ----
# S1-S3 re-anchored in 2.5.1 (doseCap caps outside SLAM too). S2 ("the cap also applies outside SLAM") is retired:
# that is 2.5.1's behaviour, and C1 asserts its reverse.
mut("S1", "killed", "SLAM is never capped: a deficit over a limit is blocked whole again (the Oct 10 incident)",
    ("            if (pumpDosingMode()) cap = doseCap(doseMl, pumpRate, slam)\n",
     "            if (!slam && pumpDosingMode()) cap = doseCap(doseMl, pumpRate, slam)\n"))
mut("S3", "killed", "the cap also shrinks the ADVISORY advice",
    ("            if (pumpDosingMode()) cap = doseCap(doseMl, pumpRate, slam)\n",
     "            if (true) cap = doseCap(doseMl, pumpRate, slam)\n"))
mut("S4", "killed", "the single-dose limit is left out of the cap",
    ("    if (maxSingle != null) limits << [maxSingle, \"single-dose limit\"]\n", ""))
mut("S5", "killed", "the daily limit ignores what was dosed today",
    ("        limits << [maxDaily - today, ", "        limits << [maxDaily, "))
mut("S6", "killed", "the runtime limit is left out of the cap",
    ("    if (maxMinutes != null) limits << [maxMinutes * rate, \"runtime limit\"]\n", ""))
mut("S7", "killed", "the tank is left out of the cap",
    ("    if (tank != null) limits << [tank, \"chlorine left in the tank\"]\n", ""))
mut("S8", "killed", "the capped runtime is rounded up past a runtime limit that is not a whole second",
    ("    if (maxMinutes != null && seconds > maxMinutes * 60G) {", "    if (false && maxMinutes != null && seconds > maxMinutes * 60G) {"))
mut("S9", "killed", "a capped dose under the minimum is still capped (blocked as below the minimum instead)",
    ("(minMl != null && ml < minMl)) return null", "false) return null"))
mut("S10", "killed", "the runtime is not recomputed from the capped volume",
    ("            runSeconds = cap.seconds as Integer\n", ""))
mut("S11", "killed", "the FREE CHLORINE block does not say the dose is capped",
    ("            lines << \"  ${cap.note}\"\n", ""))
mut("S12", "killed", "the start notice does not say the dose is capped",
    ("(${trigger}). ${capNoteText(result)}Waiting", "(${trigger}). Waiting"))
mut("S13", "killed", "the APPROVAL queue notice does not say the dose is capped",
    ("${capNoteText(result)}Open the dosing app", "Open the dosing app"))
mut("S14", "killed", "the daily summary line does not say the dose is capped",
    ("(${kind}). ${capNoteText(result)}${readings}", "(${kind}). ${readings}"))
mut("S15", "killed", "the pump plan does not show the capped volume",
    ("${cap != null ? ' for ' + n0(doseMl) + ' mL' : ''}.", "."))
mut("S16", "killed", "a tank-bound cap does not say the rest waits for a refill",
    ("why.contains(\"chlorine left in the tank\") ?", "false ?"))


# ---- 2.4.8: SLAM plausibility guard, a 0.0 reading or a drop of more than 4 ppm is not pumped (must be killed) ----
# TG1, TG2, TG4, TG5 and TG7 re-anchored in 2.5.1: the check is fcReadingProblem(fc, slam), shared with the
# outside-SLAM cap.
mut("TG1", "killed", "the SLAM guard also applies outside SLAM (doseSuspect: an APPROVAL dose is not queued)",
    ("        if (slam && pumpDosingMode()) suspect = fcReadingProblem(fc, true)\n",
     "        if (pumpDosingMode()) suspect = fcReadingProblem(fc, true)\n"))
mut("TG2", "killed", "the guard also applies in ADVISORY",
    ("        if (slam && pumpDosingMode()) suspect = fcReadingProblem(fc, true)\n",
     "        if (slam) suspect = fcReadingProblem(fc, true)\n"))
mut("TG3", "killed", "a 0.0 reading is not implausible",
    ("    if (fc < FC_READING_STEP_PPM) {\n", "    if (false) {\n"))
mut("TG4", "killed", "a drop of exactly 4.0 ppm is already implausible",
    ("    if (drop > FC_MAX_DROP_PPM) {\n", "    if (drop >= FC_MAX_DROP_PPM) {\n"))
mut("TG5", "killed", "the drop rule is gone",
    ("    if (drop > FC_MAX_DROP_PPM) {\n", "    if (false) {\n"))
mut("TG6", "killed", "the sample is compared with itself (processNewSample recorded it first)",
    ("        if (t < current && (previousT == null || t > previousT))", "        if (t <= current && (previousT == null || t > previousT))"))
mut("TG7", "killed", "the drop rule also compares with a sample more than 48 h old",
    ("    if (current - previousT > FC_PREVIOUS_SAMPLE_MAX_HOURS * 3600000L) return null\n", ""))
mut("TG8", "killed", "the oldest earlier sample is taken instead of the newest",
    ("        if (t < current && (previousT == null || t > previousT))", "        if (t < current && (previousT == null || t < previousT))"))
mut("TG9", "killed", "doseSafetyBlocks does not refuse an implausible reading (AUTO pumps it)",
    ("    if (result?.doseSuspect) blocks << result.doseSuspect.toString()\n", ""))
mut("TG10", "killed", "APPROVAL queues an implausible reading",
    ("        if (result.doseSuspect) {\n", "        if (false) {\n"))
mut("TG11", "killed", "the FREE CHLORINE block does not say why the dose is not pumped",
    ("        if (suspect != null) lines << \"  ⚠ Not pumped: ${suspect}.\"\n", ""))
mut("TG12", "killed", "the daily summary of an implausible reading reads as a capped dose (live: autoRun off, digest on)",
    ("    if (result?.doseSuspect) return \"Daily pool chlorine: not pumped.", "    if (false) return \"Daily pool chlorine: not pumped."))


# ---- Pump Power Profiler 1.2.0 (WOR-716): the dosing app's evidence is its own (must be killed) ----
mut("PI1", "killed", "the dosing app never sends its own refresh, so its run watch would need another app's (the profiler's 1.1.x poll)",
    ("        if (supported) pumpSwitch.refresh()\n    } catch (e) {\n"
     "        // A refresh that throws must not abort start verification or strand stop protection.\n",
     "        // MUTANT: no refresh\n    } catch (e) {\n"
     "        // A refresh that throws must not abort start verification or strand stop protection.\n"))


# ---- Pump Power Profiler 1.2.0 (WOR-716): event-first sampling, each behaviour reverted (must be killed) ----
mutp("E1", "killed", "a capture does not subscribe to power reports (1.1.x: the switch only)",
     ("    subscribePower()\n    try { recordRow(reason == \"manual\" ? \"manual\" : \"on\", null) }",
      "    try { recordRow(reason == \"manual\" ? \"manual\" : \"on\", null) }"))
mutp("E2", "killed", "the power subscription filters out unchanged reports",
     ("subscribe(meterDev, \"power\", \"powerHandler\", [filterEvents: false])", "subscribe(meterDev, \"power\", \"powerHandler\")"))
mutp("E3", "killed", "no refresh after the ON",
     ("    state.refreshDue = true\n    state.refreshes = 0\n", "    state.refreshDue = false\n    state.refreshes = 0\n"))
mutp("E4", "killed", "no refresh after the OFF",
     ("        state.refreshDue = true\n        runIn(REFRESH_AFTER_OFF_SECS", "        state.refreshDue = false\n        runIn(REFRESH_AFTER_OFF_SECS"))
mutp("E5", "killed", "the refresh after the ON goes out 1 s after it, inside the plug's own report delay",
     ("REFRESH_AFTER_ON_SECS = 5\n", "REFRESH_AFTER_ON_SECS = 1\n"))
mutp("E6", "killed", "a power report does not restart the quiet clock: the fallback polls every fallbackSecs regardless",
     ("    state.quietFromMs = nowMs\n    state.lastPollMs = nowMs\n    try { recordRow(\"power\"",
      "    state.lastPollMs = nowMs\n    try { recordRow(\"power\""))
mutp("E7", "killed", "the fallback never polls a quiet device",
     ("    boolean fallback = !due && offAt == null && nowMs - quietFrom() >= fallbackMs()\n", "    boolean fallback = false\n"))
mutp("E8", "killed", "the fallbackSecs setting is ignored",
     ("    int s = fallbackSecs != null ? (fallbackSecs as Integer) : DEFAULT_FALLBACK_SECS\n", "    int s = DEFAULT_FALLBACK_SECS\n"))
mutp("E9", "killed", "the default fallback is 30 s, shorter than the plug's 1 minute report",
     ("DEFAULT_FALLBACK_SECS = 90\n", "DEFAULT_FALLBACK_SECS = 30\n"))
mutp("E10", "killed", "the tailSecs setting is ignored",
     ("    int s = tailSecs != null ? (tailSecs as Integer) : DEFAULT_TAIL_SECS\n", "    int s = DEFAULT_TAIL_SECS\n"))
mutp("E11", "killed", "a capture never ends at 60 minutes",
     ("    if (startMs != null && nowMs - startMs >= MAX_CAPTURE_MINUTES * 60_000L) {\n", "    if (false) {\n"))
mutp("E12", "killed", "the power subscription outlives the capture",
     ("    state.capturing = false\n    dropPowerSubscription()\n", "    state.capturing = false\n"))
mutp("E13", "killed", "a Done during a capture drops its power subscription",
     ("    if (state.capturing) subscribePower()\n", ""))
mutp("E14", "killed", "amps from before the ON count again",
     ("    List amps  = counted.findAll { it.a != null && (onAt == null || (it.aAt != null && (it.aAt as Long) >= onAt)) }",
      "    List amps  = counted.findAll { it.a != null }"))
mutp("E15", "killed", "a 1.1.x summary header is not extended",
     ("OLDER_SUMMARY_KEY_COUNTS = [17, 19]\n", "OLDER_SUMMARY_KEY_COUNTS = [17]\n"))
mutp("E16", "killed", "the run CSV rows lose their source and event date",
     (",${nz(r.src)},${nz(r.evAt)}\\n\"", "\\n\""))

# ---- Pump Power Profiler 1.2.0 (WOR-716): wear monitor, each rule reverted (must be killed) ----
mutp("WM1", "killed", "the steady phase keeps the start transient",
     ("    long from = onAt + STEADY_SKIP_START_SECS * 1000L\n", "    long from = onAt\n"))
mutp("WM2", "killed", "the steady phase keeps the stop",
     ("    long to = offAt - STEADY_SKIP_END_SECS * 1000L\n", "    long to = offAt\n"))
mutp("WM3", "killed", "a run with under 60 s of steady phase is judged",
     ("    if (to - from < WEAR_MIN_STEADY_SECS * 1000L) return null\n", ""))
mutp("WM4", "killed", "a run without its OFF is judged",
     ("    if (onAt == null || offAt == null) return null\n    long from", "    if (onAt == null) return null\n    long from"))
mutp("WM5", "killed", "the median is taken over the whole history, not the last 10 runs",
     ("    List recent = runs.size() > WEAR_MEDIAN_RUNS ? runs.drop(runs.size() - WEAR_MEDIAN_RUNS) : runs\n",
      "    List recent = runs\n"))
mutp("WM6", "killed", "the run is part of its own median",
     ("    List recent = runs.size() > WEAR_MEDIAN_RUNS ? runs.drop(runs.size() - WEAR_MEDIAN_RUNS) : runs\n",
      "    List withRun = runs + [[w: steady.w]]\n"
      "    List recent = withRun.size() > WEAR_MEDIAN_RUNS ? withRun.drop(withRun.size() - WEAR_MEDIAN_RUNS) : withRun\n"))
mutp("WM7", "killed", "runs are judged from the first one, with no baseline",
     ("WEAR_MIN_REFERENCE_RUNS = 3\n", "WEAR_MIN_REFERENCE_RUNS = 1\n"))
mutp("WM8", "killed", "a single run off the median alerts",
     ("        if (streak == 2) {\n", "        if (streak >= 1) {\n"))
mutp("WM9", "killed", "every run of a streak alerts",
     ("        if (streak == 2) {\n", "        if (streak >= 2) {\n"))
mutp("WM10", "killed", "a run within the threshold does not end the streak",
     ("        int streak = deviates ? ((state.wearStreak ?: 0) as Integer) + 1 : 0\n",
      "        int streak = deviates ? ((state.wearStreak ?: 0) as Integer) + 1 : ((state.wearStreak ?: 0) as Integer)\n"))
mutp("WM11", "killed", "the wearThresholdPct setting is ignored",
     ("    BigDecimal v = wearThresholdPct != null ? (wearThresholdPct as BigDecimal) : DEFAULT_WEAR_THRESHOLD_PCT\n",
      "    BigDecimal v = DEFAULT_WEAR_THRESHOLD_PCT\n"))
mutp("WM12", "killed", "only runs below the median count",
     ("    boolean deviates = pct != null && pct.abs() > wearThreshold()\n", "    boolean deviates = pct != null && pct.negate() > wearThreshold()\n"))
mutp("WM13", "killed", "a deviation of exactly the threshold counts",
     ("    boolean deviates = pct != null && pct.abs() > wearThreshold()\n", "    boolean deviates = pct != null && pct.abs() >= wearThreshold()\n"))
mutp("WM14", "killed", "the alert reaches no notification device",
     ("        try { d.deviceNotification(msg) }\n", "        try { }\n"))
mutp("WM15", "killed", "the wear history is not capped",
     ("    if (runs.size() > WEAR_HISTORY_MAX) runs = runs.drop(runs.size() - WEAR_HISTORY_MAX)\n", ""))
mutp("WM16", "killed", "Clear leaves the wear history",
     ("        state.remove(\"wearRuns\")\n", ""))
mutp("WM17", "killed", "the alert is not logged or kept for the page",
     ("    state.lastWearAlert = [at: now(), msg: msg]\n    log.warn \"Pump Power Profiler: ${msg}\"\n", ""))


# ---- 2.5.0 (WOR-741): a save keeps the queue (must be killed) ----
mut("U1", "killed", "the blanket unschedule() is back at the top of initialize(): a save clears every job",
    ("def initialize() {\n", "def initialize() {\n    unschedule()\n"))
mut("U2", "killed", "a save with the daily refresh switched off leaves the fetch and the second fetch scheduled",
    ("        unschedule(\"refreshWaterGuruSource\")\n        unschedule(\"retryWaterGuruRefresh\")\n", ""))
mut("U3", "killed", "a save with the daily summary switched off leaves it scheduled",
    ("    } else {\n        unschedule(\"dailyDigestHandler\")\n    }\n", "    }\n"))
mut("U4", "killed", "a save no longer checks an open stop's cutoff (an overdue one waits for its late job)",
    ("    if (stopOpen()) armEmergencyPumpCutoff(\"a save found a stop still open\")\n", ""))

# ---- 2.5.0 (WOR-741): one notice slot for stop and EMERGENCY notices (must be killed) ----
mut("N1", "killed", "a stop notice replaces an EMERGENCY not yet said (the STOP's 'Retrying' is said instead)",
    ("    if (held != null && held.announced != true && held.tail != null && tail == null) {\n", "    if (false) {\n"))
mut("N2", "killed", "an EMERGENCY takes the final word from a stop notice",
    ("    String word = (tail == null && confirmed) ? confirmed : (held?.confirmed ?: confirmed)\n",
     "    String word = confirmed ?: held?.confirmed\n"))
mut("N3", "killed", "a stop notice's own final word does not outrank an EMERGENCY's held one",
    ("    String word = (tail == null && confirmed) ? confirmed : (held?.confirmed ?: confirmed)\n",
     "    String word = held?.confirmed ?: confirmed\n"))
mut("N4", "killed", "an EMERGENCY 2.4.8 left pending is dropped when its job fires under 2.5.0",
    ("    if (old != null && old.announced != true) deferNotice(", "    if (false) deferNotice("))
mut("N5", "killed", "a stop notice 2.4.8 left pending is said with no head (`requested` not read)",
    ("    String head = (pending.head ?: pending.requested)?.toString()", "    String head = pending.head?.toString()"))
mut("N6", "killed", "stopOpen() leaves out a stop with nothing behind it (idleStopAt): a stale OFF can close it",
    ("    return state.activeDose != null || state.faultStopAt != null || state.idleStopAt != null\n",
     "    return state.activeDose != null || state.faultStopAt != null\n"))

# ---- 2.5.0 (WOR-741): positive controls for the dash checks, a dash put back (must be killed) ----
mut("D1", "killed", "an em dash is back in the unconfirmed-stop notice",
    ("never confirmed, so no scheduled dose is claimed; the ${active.ml} mL attempt remains reserved and unreconciled",
     "never confirmed \u2014 no scheduled dose is claimed; the ${active.ml} mL attempt remains reserved and unreconciled"))
mut("D2", "killed", "an em dash is back in the SLAM banner of the advice text",
    ("SLAM MODE ACTIVE. Free chlorine is held high on purpose", "SLAM MODE ACTIVE \u2014 free chlorine is held high on purpose"))
mut("D3", "killed", "an em dash is back in the SLAM input title (page text the stub never runs)",
    ("title: \"SLAM mode: target FC as a multiple of CYA", "title: \"SLAM mode \u2014 target FC as a multiple of CYA"))
mut("D4", "killed", "an en dash is back in the pH block reason",
    ("is outside ${n2(loPh)} to ${n2(hiPh)}", "is outside ${n2(loPh)}\u2013${n2(hiPh)}"))
mut("D5", "killed", "an em dash is back in the parent app's page text (parent 2.0.1)",
    ("paragraph \"Complementary to the WaterGuru Integration app: it does not", "paragraph \"Complementary to the WaterGuru Integration app \u2014 it does not"),
    target=PARENT)


# ---- 2.5.1 (WOR-857): over-limit doses capped outside SLAM, SLAM off by default, the profiler shipped (must be killed) ----
mut("C1", "killed", "outside SLAM an over-limit dose is blocked whole again (2.5.0; Oct 20: 3164 mL asked, nothing pumped)",
    ("            if (pumpDosingMode()) cap = doseCap(doseMl, pumpRate, slam)\n",
     "            if (slam && pumpDosingMode()) cap = doseCap(doseMl, pumpRate, slam)\n"))
mut("C2", "killed", "outside SLAM the cap also shrinks the ADVISORY advice",
    ("            if (pumpDosingMode()) cap = doseCap(doseMl, pumpRate, slam)\n",
     "            if (pumpDosingMode() || !slam) cap = doseCap(doseMl, pumpRate, slam)\n"))
mut("C3", "killed", "outside SLAM the tank also caps the dose (instead of blocking it with the tank reason)",
    ("    BigDecimal tank = slam ? tankRemainingMl() : null\n", "    BigDecimal tank = tankRemainingMl()\n"))
mut("C4", "killed", "outside SLAM the reading is not checked before capping (a 0.0 or a 4.1 ppm drop pumps the capped amount)",
    ("        if (cap != null && !slam) {\n", "        if (false) {\n"))
mut("C5", "killed", "outside SLAM a 0.0 reading is not implausible (the 0.0 rule only in SLAM)",
    ("    if (fc < FC_READING_STEP_PPM) {\n", "    if (slam && fc < FC_READING_STEP_PPM) {\n"))
mut("C6", "killed", "outside SLAM a drop of more than 4 ppm is not implausible (the drop rule only in SLAM)",
    ("    if (drop > FC_MAX_DROP_PPM) {\n", "    if (slam && drop > FC_MAX_DROP_PPM) {\n"))
mut("C7", "killed", "the outside-SLAM cap note says SLAM",
    ("\"${slam ? 'SLAM: the' : 'The'} pump dose is capped at", "\"SLAM: the pump dose is capped at"))
mut("C8", "killed", "the advice does not say why an over-limit dose was not capped",
    ("        if (uncapped != null) lines << \"  ⚠ Not capped to the pump limits: ${uncapped}.\"\n", ""))
mut("C9", "killed", "a dose inside the limits is checked too (the reading check is not tied to a cap)",
    ("        if (cap != null && !slam) {\n", "        if (!slam) {\n"))
mut("C10", "killed", "a slamMode never saved reads as SLAM on (slamMode != false, as before 2.5.1)",
    ("        if (slamMode == true) {\n            BigDecimal factor", "        if (slamMode != false) {\n            BigDecimal factor"))
mut("C11", "killed", "the slamMode input defaults to on (page text the stub never runs)",
    ("(recommended while recovering)\",\n                defaultValue: false,", "(recommended while recovering)\",\n                defaultValue: true,"))
mut("C12", "killed", "the manifest makes the profiler required",
    ("            \"version\": \"1.2.0\",\n            \"required\": false,", "            \"version\": \"1.2.0\",\n            \"required\": true,"),
    target=MANIFEST)
mut("C13", "killed", "the manifest lists another profiler version than the code's",
    ("            \"version\": \"1.2.0\",\n            \"required\": false,", "            \"version\": \"1.1.1\",\n            \"required\": false,"),
    target=MANIFEST)


# ---- 2.5.2 (WOR-921): no pump on an FC reading WaterGuru cannot confirm below the target (must be killed) ----
mut("H1", "killed", "a reading of 10.0 or more pumps again (Oct 22: 10.0 stored, 16.2 real, 3000 mL pumped)",
    ("    if (slam ? fc.compareTo(FC_READING_CEILING_PPM) == 0 : fc >= FC_READING_CEILING_PPM) {\n", "    if (false) {\n"))
mut("H2", "?", "outside SLAM only a reading above 10.0 holds (since 2.5.3 the target is capped at 10, so 10.0 is no dose anyway)",
    (": fc >= FC_READING_CEILING_PPM) {\n", ": fc > FC_READING_CEILING_PPM) {\n"))
mut("H3", "killed", "a WaterGuru 'Free Chlorine very high' alert no longer holds the pump",
    ("        if (l.contains(\"free chlorine\") && l.contains(\"very high\")) {\n", "        if (false) {\n"))
mut("H4", "killed", "a plain 'Free Chlorine high' alert holds again (graded against WaterGuru's own 3.0 target)",
    ("        if (l.contains(\"free chlorine\") && l.contains(\"very high\")) {\n",
     "        if (l.contains(\"free chlorine\") && l.contains(\"high\")) {\n"))
mut("H5", "killed", "a RED grade holds the pump on either side (a very low FC is not dosed)",
    ("?.equalsIgnoreCase(\"RED\") && fc >= wgTarget) {\n", "?.equalsIgnoreCase(\"RED\")) {\n"))
mut("H6", "killed", "a RED grade above WaterGuru's own target no longer holds the pump",
    ("?.equalsIgnoreCase(\"RED\") && fc >= wgTarget) {\n", "?.equalsIgnoreCase(\"RED\") && false) {\n"))
mut("H7", "killed", "any 'very high' alert holds the pump (a pH very high alert blocks the chlorine dose)",
    ("        if (l.contains(\"free chlorine\") && l.contains(\"very high\")) {\n", "        if (l.contains(\"very high\")) {\n"))
mut("H8", "killed", "WaterGuru's advice to decrease chlorine holds the pump again (graded against its own 3.0 target)",
    ("    return null\n}\n\n/** APPROVAL and AUTO run the pump",
     "    for (String line : (attrRaw(\"doseAdvice\") ?: \"\").split(\"\\n\")) {\n"
     "        String l = line.toLowerCase()\n"
     "        if (l.contains(\"decrease\") && l.contains(\"chlorine\")) return \"WaterGuru advises to decrease chlorine dosing\"\n"
     "    }\n"
     "    return null\n}\n\n/** APPROVAL and AUTO run the pump"))
mut("H9", "killed", "the ceiling check is computed but never holds the pump",
    ("        if (suspect == null && pumpDosingMode()) suspect = ceiling\n", "        if (false) suspect = ceiling\n"))
mut("H10", "killed", "ADVISORY says 'Not pumped' too (the hold is not tied to a mode that pumps)",
    ("        if (suspect == null && pumpDosingMode()) suspect = ceiling\n", "        if (suspect == null) suspect = ceiling\n"))
mut("H11", "killed", "ADVISORY does not show why the dose could not be confirmed",
    ("        else if (ceiling != null) lines << \"  ⚠ ${ceiling}.\"\n", ""))
mut("H13", "killed", "a target above 10 is not warned on the page",
    ("    if (target == null || target <= FC_TARGET_CAP_PPM) return null\n", "    return null\n"))
mut("H14", "killed", "an em dash is back in the page warning",
    ("Outside SLAM no dose aims above \" +", "Outside SLAM no dose aims above \u2014 \" +"))
mut("H15", "killed", "an em dash is back in the advice warning",
    ("ppm: outside SLAM no dose aims above", "ppm \u2014 outside SLAM no dose aims above"))
mut("H16", "killed", "the ceiling reason wins over the 0.0 / big drop reason when both apply",
    ("        if (suspect == null && pumpDosingMode()) suspect = ceiling\n",
     "        if (ceiling != null && pumpDosingMode()) suspect = ceiling\n"))


# ---- 2.5.3 (WOR-939): FC 10 or more doses only in SLAM (must be killed) ----
# H12 (the advice warning on a target above 10) retired: Q9 removes the same warning at its new place.
mut("Q1", "killed", "outside SLAM a target above 10 is not capped (a saved 14 doses toward 14 again)",
    ("    if (!slam && target > FC_TARGET_CAP_PPM) {\n", "    if (false) {\n"))
mut("Q2", "killed", "the SLAM target is capped too (SLAM 13.2 becomes 10, FC 12.0 is not dosed)",
    ("    if (!slam && target > FC_TARGET_CAP_PPM) {\n", "    if (target > FC_TARGET_CAP_PPM) {\n"))
mut("Q3", "killed", "in SLAM any reading of 10 or more holds again (12.0 under 13.2 is not dosed)",
    ("fc.compareTo(FC_READING_CEILING_PPM) == 0 :", "fc.compareTo(FC_READING_CEILING_PPM) >= 0 :"))
mut("Q4", "killed", "in SLAM an exact 10.0 doses (the clipped value can mean more)",
    ("slam ? fc.compareTo(FC_READING_CEILING_PPM) == 0 :", "slam ? false :"))
mut("Q5", "killed", "in SLAM the very high alert and RED hold the pump again",
    ("    if (slam) return null\n", ""))
mut("Q6", "killed", "the daily summary does not say the target was capped",
    ("\" (capped from ${n1(result.fcTargetAsked)} ppm outside SLAM)\" : \"\"", "\"\" : \"\""))
mut("Q7", "killed", "the page warns in SLAM (the TFP target at CYA 100 is 11.5)",
    ("    else if (slamMode == true) return null\n", ""))
mut("Q8", "killed", "the page warns on a target of exactly 10",
    ("target == null || target <= FC_TARGET_CAP_PPM) return null", "target == null || target < FC_TARGET_CAP_PPM) return null"))
mut("Q9", "killed", "the advice does not warn that the target was capped",
    ("        warnings << \"FC target ${n1(targetAsked)} ppm is capped to ${n1(target)} ppm: outside SLAM no dose aims above ${n0(FC_TARGET_CAP_PPM)} ppm.\"\n", ""))
mut("Q10", "killed", "a target of exactly 10 counts as capped",
    ("    if (!slam && target > FC_TARGET_CAP_PPM) {\n", "    if (!slam && target >= FC_TARGET_CAP_PPM) {\n"))
mut("Q11", "killed", "slamMode on with a manual target counts as SLAM (a saved 14 is not capped)",
    ("    if (!slam && target > FC_TARGET_CAP_PPM) {\n", "    if (slamMode != true && target > FC_TARGET_CAP_PPM) {\n"))
mut("Q12", "killed", "in SLAM the very high alert holds the pump again (RED alone still does not)",
    ("    if (slam) return null\n    for (String line", "    for (String line"),
    ("    BigDecimal wgTarget = attrNum(\"freeChlorineTarget\") ?: 3G\n",
     "    if (slam) return null\n    BigDecimal wgTarget = attrNum(\"freeChlorineTarget\") ?: 3G\n"))
mut("Q13", "killed", "in SLAM a RED grade holds the pump again",
    ("    if (slam) return null\n",
     "    if (slam && !attrRaw(\"freeChlorineStatus\")?.trim()?.equalsIgnoreCase(\"RED\")) return null\n"))
mut("Q14", "killed", "the tile's freeChlorine attribute is clamped to 10",
    ("value: r.fcVal as BigDecimal, unit: \"ppm\")", "value: (r.fcVal as BigDecimal).min(FC_TARGET_CAP_PPM), unit: \"ppm\")"))
mut("Q15", "killed", "the advice, page and notice FC line is clamped to 10",
    ("lines << \"  FC ${n1(fc)} ppm  →  target", "lines << \"  FC ${n1(fc.min(FC_TARGET_CAP_PPM))} ppm  →  target"))
mut("Q16", "killed", "the tile card's FC number is clamped to 10",
    ("Free chlorine <b>${n1(r.fcVal)}</b>", "Free chlorine <b>${n1((r.fcVal as BigDecimal).min(FC_TARGET_CAP_PPM))}</b>"))
mut("Q17", "killed", "the daily summary's FC is clamped to 10",
    ("\"FC ${n1(result?.fcVal)} ppm; target", "\"FC ${n1((result?.fcVal as BigDecimal)?.min(FC_TARGET_CAP_PPM))} ppm; target"))


def build(name):
    expect, desc, edits, target = V[name]
    dst = OUT / name
    if dst.exists():
        shutil.rmtree(dst)
    (dst / "tests").mkdir(parents=True)
    shutil.copytree(ROOT / "apps", dst / "apps")
    shutil.copytree(ROOT / "tests" / "groovy", dst / "tests" / "groovy")
    shutil.copy2(ROOT / MANIFEST, dst / MANIFEST)   # the profiler suite reads it (2.5.1)
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
