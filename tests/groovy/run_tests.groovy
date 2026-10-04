/*
 * Execution tests for the WaterGuru Dosing Advisor child app.
 *
 * These run the app's own methods against a stubbed Hubitat runtime, so they observe
 * BEHAVIOUR. The attribute-contract test next door scans source text, which cannot tell a
 * real sendEvent from a commented-out one, and cannot see that stopDose() once reported
 * "pump stopped" after a failed OFF command.
 *
 * Nothing here touches a hub or a pump. The switch is a fake that can be told to throw, to
 * ignore OFF, or to report nothing at all.
 *
 * Run:  tests/groovy/run.sh      (compiles the stubs, then executes this file)
 */

import java.time.Instant

import org.codehaus.groovy.control.CompilerConfiguration

// --------------------------------------------------------------------------- harness

int checks = 0
int failures = 0
List<String> failureDetail = []

def check = { String name, Closure body ->
    checks++
    try {
        body.call()
    } catch (Throwable t) {
        failures++
        failureDetail << "${name}: ${t.message}"
        println "FAIL  ${name}\n        ${t.message}"
    }
}

def expect = { boolean condition, String message ->
    if (!condition) throw new AssertionError(message)
}

def appFile = new File("apps/WaterGuru-Dosing-Advisor-Child.groovy")
def compilerConfig = new CompilerConfiguration()
compilerConfig.scriptBaseClass = HubitatStub.name
def shell = new GroovyShell(HubitatStub.classLoader, new Binding(), compilerConfig)

/** A fresh app per test: state must not leak between scenarios. */
def newApp = {
    def script = shell.parse(appFile)
    script.run()
    // The app's own sendNotice() fans out to notifyDevices. A fake whose sink IS the stub's
    // notice list means noticesMatching() observes exactly what the app really sent.
    script.notifyDevices = [new FakeNotifier(sink: script.notices)]
    // Bind every fake device's state timestamps to THIS app's fake clock. Without this a device
    // would date its readings on the wall clock, which is years in the future for the fixed app
    // clock and would be correctly rejected as future-dated evidence.
    FakeSwitch.defaultClock = { script.clockMs }
    return script
}

def DAY_MS = 86_400_000L

/** Give an app a tank of the given size/level and a dose history. */
def primeTank = { app, BigDecimal capacityMl, BigDecimal remainingMl, List<List> doses ->
    app.chlorineTankGallons = "15"
    app.state.tankCapacityGallons = "15"
    app.state.tankRemainingMl = remainingMl.toString()
    app.tankLowPercent = "10"
    app.state.tankDoseHistory = doses.collect { [t: it[0], ml: it[1].toString()] }
}

// --------------------------------------------------------------------------- stop path

check("stopDose: an IGNORED OFF does not report stopped, keeps the cutoff armed, retries") {
    def app = newApp()
    def pump = new FakeSwitch("on")
    pump.mode = "ignoresOff"
    app.pumpSwitch = pump
    app.state.activeDose = [ml: "237", seconds: 65, started: app.clockMs - 65_000L, stopAt: app.clockMs]
    app.scheduled["emergencyPumpOff"] = 1200

    app.stopDose()

    expect(pump.offCalls == 1, "the app should have attempted OFF once")
    expect(app.state.activeDose != null, "an unconfirmed stop must NOT forget the active dose")
    expect(!app.unscheduled.contains("emergencyPumpOff"),
           "the independent cutoff must NOT be disarmed while the pump is unconfirmed")
    expect(app.scheduled["verifyPumpOff"] != null, "a verification retry must be scheduled")
    expect(app.noticesMatching("stopped after scheduled").isEmpty(),
           "must NOT claim the pump stopped when it did not")
    expect(!app.noticesMatching("the switch reports ON").isEmpty(),
           "must say the switch reports ON when it does")
    expect(!app.noticesMatching("OFF has not been freshly confirmed").isEmpty() ||
           !app.noticesMatching("the switch reports ON").isEmpty(),
           "and otherwise say OFF is not freshly confirmed")
}

check("stopDose: an OFF that THROWS behaves the same way") {
    def app = newApp()
    def pump = new FakeSwitch("on")
    pump.mode = "offThrows"
    app.pumpSwitch = pump
    app.state.activeDose = [ml: "237", seconds: 65, started: app.clockMs - 65_000L, stopAt: app.clockMs]
    app.scheduled["emergencyPumpOff"] = 1200

    app.stopDose()

    expect(app.state.activeDose != null, "a thrown OFF must not be treated as a stop")
    expect(!app.unscheduled.contains("emergencyPumpOff"), "cutoff must stay armed")
    expect(app.scheduled["verifyPumpOff"] != null, "must schedule a retry")
    expect(app.noticesMatching("stopped after scheduled").isEmpty(), "must not claim stopped")
    expect(app.logLines.any { it.startsWith("ERROR") }, "the thrown command should be logged as an error")
}

check("stopDose: a switch reporting NOTHING is not a confirmed stop") {
    def app = newApp()
    def pump = new FakeSwitch("on")
    pump.mode = "silent"        // currentValue() returns null
    app.pumpSwitch = pump
    app.state.activeDose = [ml: "237", seconds: 65, started: app.clockMs - 65_000L, stopAt: app.clockMs]
    app.scheduled["emergencyPumpOff"] = 1200

    app.stopDose()

    expect(app.state.activeDose != null, "an unknown reading is not a stop")
    expect(!app.unscheduled.contains("emergencyPumpOff"), "cutoff must stay armed on an unknown reading")
}

check("verifyPumpOff: keeps retrying, then re-arms the cutoff rather than giving up silently") {
    def app = newApp()
    def pump = new FakeSwitch("on")
    pump.mode = "ignoresOff"
    app.pumpSwitch = pump
    app.watchdogAnyPumpRun = true
    app.failsafePumpRunMinutes = 20
    app.state.activeDose = [ml: "237", seconds: 65, started: app.clockMs, stopAt: app.clockMs]

    // Assert the behaviour, not a magic number: drive the verifier until the app stops
    // scheduling retries, which is what "bounded" means here. Reading the constant is not
    // possible from outside -- the app's @Field constants are not visible on the script class --
    // and asserting the number would couple this test to a value it cannot see.
    int driven = 0
    while (app.state.activeDose != null && driven < 50) {
        app.scheduled.remove("verifyPumpOff")
        app.verifyPumpOff()
        driven++
        if (app.scheduled["verifyPumpOff"] == null) break   // it has stopped retrying
    }

    expect(app.scheduled["verifyPumpOff"] == null,
           "it must stop retrying rather than schedule another attempt forever")
    expect(driven <= 6, "the retry bound should be small; drove ${driven} attempts")
    expect(app.scheduled["emergencyPumpOff"] != null,
           "the cutoff must be (re-)armed when the retries are exhausted")
    expect(!app.noticesMatching("may need to be stopped by hand").isEmpty(),
           "it must say plainly that the pump may need stopping by hand")
    expect(app.unscheduled.contains("emergencyPumpOff") == false,
           "the cutoff must never be disarmed while unconfirmed")
}

check("stopDose: a healthy switch is confirmed, then forgotten and disarmed") {
    def app = newApp()
    def pump = new FakeSwitch("on")
    app.pumpSwitch = pump
    app.state.activeDose = [ml: "237", seconds: 65, started: app.clockMs - 65_000L, stopAt: app.clockMs]
    app.scheduled["emergencyPumpOff"] = 1200

    app.stopDose()

    expect(app.state.activeDose == null, "a confirmed stop should forget the active dose")
    expect(app.unscheduled.contains("emergencyPumpOff"), "a confirmed stop should disarm the cutoff")
    expect(!app.noticesMatching("stopped after scheduled").isEmpty(), "a confirmed stop should say so")
    expect(app.scheduled["verifyPumpOff"] == null, "no retry is needed when it stopped")
}

check("emergencyPumpOff: does not claim success against a stuck relay, and re-arms") {
    def app = newApp()
    def pump = new FakeSwitch("on")
    pump.mode = "ignoresOff"
    app.pumpSwitch = pump
    app.watchdogAnyPumpRun = true
    app.failsafePumpRunMinutes = 20
    app.state.activeDose = [ml: "237", seconds: 65, started: app.clockMs, stopAt: app.clockMs]

    app.emergencyPumpOff()

    expect(app.state.activeDose != null, "the cutoff must not forget the dose it could not stop")
    expect(!app.noticesMatching("has not been able to turn").isEmpty(),
           "the cutoff must admit it could not stop the pump")
    expect(app.scheduled["emergencyPumpOff"] != null, "the cutoff must re-arm and try again")
    expect(app.noticesMatching("cutoff confirmed").isEmpty(), "must not claim a confirmed stop")
}

// --------------------------------------------------------------------------- telemetry

check("publishTileTelemetry: emits the numeric telemetry with its units") {
    def app = newApp()
    def tile = new FakeSwitch("off")
    app.state.lastDose = [time: app.clockMs - 60_000L, ml: "237", mlRaw: "237.3273375", seconds: 65]
    primeTank(app, 56781.176760G, 56543.8494225G, [[app.clockMs - 10 * DAY_MS, 1000], [app.clockMs, 1000]])

    app.publishTileTelemetry([fcVal: 5.7, fcTarget: 6.0], tile)

    expect(tile.lastSent("tankRemainingMl") != null, "tankRemainingMl must be emitted")
    expect(tile.lastSent("lastDoseMl") != null, "lastDoseMl must be emitted")
    expect(tile.lastSent("lastDoseRuntimeSeconds") == 65, "runtime must be emitted as 65")
    expect(tile.lastSent("freeChlorine") != null && tile.lastSent("targetFreeChlorine") != null,
           "the FC pair must be emitted")
    expect(tile.sentEvents.find { it.name == "tankPercent" }?.unit == "%", "tankPercent carries %")
    expect(tile.sentEvents.find { it.name == "tankRunwayDays" }?.unit == "days", "runway carries days")
}

// --------------------------------------------------------------------------- preview

check("refreshTankLinesInPreview: the tank line follows the numeric attribute after a dose") {
    def app = newApp()
    def tile = new FakeSwitch("off")
    app.__tileDevice = tile
    app.createTile = true
    primeTank(app, 56781.176760G, 56543.8494225G, [[app.clockMs - 10 * DAY_MS, 1000], [app.clockMs, 1000]])
    // The stale text a dose would have left behind: a full tank beside a decremented attribute.
    app.state.lastPreview = [
        "pool header",
        "🧴 Chlorine tank: full 56781 mL (15.00 gal) · remaining 56781 mL (15.00 gal, 100%).",
        "⏳ Tank runway: ~42.1 days until inventory drops below 10% (average 1213 mL/day over 3 dose samples).",
        "footer",
    ].join("\n")

    app.refreshTankLinesInPreview()

    def pushed = tile.lastSent("detail")?.toString()
    expect(pushed != null, "the refreshed detail must be pushed to the tile")
    expect(pushed.contains("56544") || pushed.contains("56543"),
           "the tank line must now show the decremented inventory, got: ${pushed}")
    expect(!pushed.contains("remaining 56781 mL"), "the stale full-tank figure must be gone")
    expect(pushed.contains("footer"), "unrelated preview lines must be left alone")
    expect(pushed.contains("pool header"), "the header must be untouched")
}

// --------------------------------------------------------------------------- runway

check("computeTankRunway: measures per ELAPSED DAY, not per dose") {
    def app = newApp()
    // 1000 mL on day 0 and 1000 mL on day 10 = 200 mL/day. The old code divided by the dose
    // count and called it 1000 mL/day -- a 5x overstatement of the runway.
    primeTank(app, 56781.176760G, 50000G,
              [[app.clockMs - 10 * DAY_MS, 1000], [app.clockMs, 1000]])

    def runway = app.computeTankRunway()

    expect(runway.state == "ok", "expected an ok runway, got ${runway.state}")
    expect(Math.abs((runway.dailyUse as BigDecimal).doubleValue() - 200.0d) < 0.01d,
           "expected 200 mL/day, got ${runway.dailyUse}")
    expect(Math.abs((runway.spanDays as BigDecimal).doubleValue() - 10.0d) < 0.01d,
           "the span should be 10 days, got ${runway.spanDays}")
}

check("computeTankRunway: refuses to invent a rate from a single dose") {
    def app = newApp()
    primeTank(app, 56781.176760G, 50000G, [[app.clockMs, 1000]])

    def runway = app.computeTankRunway()

    expect(runway.state == "learning",
           "one dose is no interval to measure a daily rate over; got state=${runway.state}")
}

check("computeTankRunway: two doses minutes apart is still not a daily rate") {
    def app = newApp()
    primeTank(app, 56781.176760G, 50000G,
              [[app.clockMs - 60_000L, 1000], [app.clockMs, 1000]])

    def runway = app.computeTankRunway()

    expect(runway.state == "learning", "a sub-0.5-day span must not yield a daily rate")
}

// --------------------------------------------- STOP button, late OFF, deadlines, dead reads

check("STOP button: a failed OFF must not forget the dose, disarm the cutoff, or claim stopped") {
    ["ignoresOff", "offThrows"].each { failing ->
        def app = newApp()
        def pump = new FakeSwitch("on")
        pump.mode = failing
        app.pumpSwitch = pump
        app.watchdogAnyPumpRun = true
        app.failsafePumpRunMinutes = 20
        app.state.activeDose = [ml: "237", seconds: 65, started: app.clockMs - 65_000L, stopAt: app.clockMs]
        app.scheduled["emergencyPumpOff"] = 1200
        app.state.emergencyDeadline = app.clockMs + 1_200_000L

        app.appButtonHandler("btnStopPump")

        expect(app.state.activeDose != null, "[${failing}] STOP must not forget the dose")
        expect(!app.unscheduled.contains("emergencyPumpOff"), "[${failing}] STOP must not disarm the cutoff")
        expect(app.noticesMatching("Chlorine pump stopped:").isEmpty(),
               "[${failing}] must not claim the pump stopped")
        expect(app.scheduled["verifyPumpOff"] != null, "[${failing}] must schedule a retry")
        expect(!app.noticesMatching("stop requested").isEmpty(),
               "[${failing}] must say the stop was requested, not achieved")
    }
}

check("exhaustion must not postpone a cutoff that is already due sooner") {
    def app = newApp()
    def pump = new FakeSwitch("on")
    pump.mode = "ignoresOff"
    app.pumpSwitch = pump
    app.watchdogAnyPumpRun = true
    app.failsafePumpRunMinutes = 20
    app.state.activeDose = [ml: "237", seconds: 65, started: app.clockMs - 100_000L,
                            stopAt: app.clockMs - 1_000L]
    app.state.stopAttempts = 4                      // one attempt short of the bound
    Long soonDeadline = app.clockMs + 5_000L
    app.state.emergencyDeadline = soonDeadline
    app.scheduled["emergencyPumpOff"] = 5           // 5 seconds left on the cutoff

    app.verifyPumpOff()                             // the final attempt fires

    expect(app.state.emergencyDeadline == soonDeadline,
           "a cutoff due in 5s must keep its deadline, got ${app.state.emergencyDeadline}")
    expect(app.scheduled["emergencyPumpOff"] != 1200,
           "the due-sooner cutoff must not be replaced by a fresh 1200s timer")
}

check("with the watchdog off, exhaustion must not claim a cutoff was armed") {
    def app = newApp()
    def pump = new FakeSwitch("on")
    pump.mode = "ignoresOff"
    app.pumpSwitch = pump
    app.watchdogAnyPumpRun = false
    app.state.activeDose = [ml: "237", seconds: 65, started: app.clockMs, stopAt: app.clockMs]
    app.state.stopAttempts = 4

    app.verifyPumpOff()

    expect(app.scheduled["emergencyPumpOff"] == null,
           "nothing should be scheduled when the watchdog is off")
    expect(app.noticesMatching("has been re-armed").isEmpty(),
           "must not claim a re-arm that did not happen")
    expect(!app.noticesMatching("DISABLED").isEmpty(),
           "must say the cutoff is disabled so a human acts")
}

check("a LATE off event past the planned stop still releases the dose") {
    def app = newApp()
    app.pumpSwitch = new FakeSwitch("off")
    app.state.activeDose = [ml: "237", seconds: 65, stopAt: app.clockMs - 60_000L]
    app.state.stopAttempts = 5

    app.pumpSwitchHandler([value: "off"])

    expect(app.state.activeDose == null,
           "a confirmed off must release the dose whenever it arrives, not only early")
    expect(app.state.stopAttempts == null, "the retry counter must be cleared")
}

// ------------------------------------------- confirmed-stop notice (2.3.2 regression)

check("regression: a delayed OFF announces the final stop once and clears every stop job") {
    def app = newApp()
    def pump = new FakeSwitch("on")
    pump.mode = "ignoresOff"          // the OFF command is accepted and the relay holds ON briefly
    app.pumpSwitch = pump
    app.watchdogAnyPumpRun = true
    app.failsafePumpRunMinutes = 20

    Integer seconds = 65
    app.state.activeDose = [ml: "237", seconds: seconds, started: app.clockMs - seconds * 1000L,
                            stopAt: app.clockMs]
    app.scheduled["stopDose"] = 0
    app.scheduled["verifyPumpOff"] = 15
    app.scheduled["emergencyPumpOff"] = 1200

    // The scheduled stop fires at its deadline; the relay still reports ON.
    app.fire("stopDose")

    expect(app.noticesMatching("stopped after scheduled").isEmpty(),
           "a delayed OFF must not be announced as stopped before the switch confirms")
    expect(!app.noticesMatching("stop requested").isEmpty(),
           "the app should say the stop was requested, not achieved")
    expect(app.state.activeDose != null, "the dose stays active until the switch confirms off")
    expect(app.scheduled["verifyPumpOff"] != null, "verification must stay armed")
    expect(app.scheduled["emergencyPumpOff"] != null, "the independent cutoff must stay armed")

    // ~0.7 s later the relay physically drops out and the hub delivers its OFF event.
    app.clockMs = app.clockMs + 700L
    pump.setReported("off")
    app.pumpSwitchHandler([value: "off"])

    expect(app.noticesMatching("stopped after scheduled").size() == 1,
           "the confirmed OFF must send exactly one final stopped notice")
    expect(app.state.activeDose == null, "the confirmed off must release the active dose")
    expect(app.state.stopAttempts == null, "the retry counter must be cleared")
    expect(app.scheduled["stopDose"] == null, "the scheduled stop job must be gone")
    expect(app.scheduled["verifyPumpOff"] == null, "the verification job must be gone")
    expect(app.scheduled["emergencyPumpOff"] == null, "the cutoff job must be gone")
    expect(app.unscheduled.contains("verifyPumpOff"), "verification must be explicitly unscheduled")
    expect(app.unscheduled.contains("emergencyPumpOff"), "the cutoff must be explicitly disarmed")
}

check("regression: duplicate and idle OFF events do not repeat the final notice") {
    def app = newApp()
    app.pumpSwitch = new FakeSwitch("off")
    app.state.activeDose = [ml: "237", seconds: 65, started: app.clockMs - 65_000L,
                            stopAt: app.clockMs - 1_000L]

    app.pumpSwitchHandler([value: "off"])       // the confirmed, on-time/late OFF
    app.pumpSwitchHandler([value: "off"])       // a duplicate delivery of the same event

    expect(app.noticesMatching("stopped after scheduled").size() == 1,
           "a duplicate OFF must not repeat the final notice")

    def idle = newApp()
    idle.pumpSwitch = new FakeSwitch("off")
    idle.pumpSwitchHandler([value: "off"])      // OFF with no active dose
    expect(idle.notices.isEmpty(), "an idle OFF must stay silent")
}

check("regression: a synchronous confirmed stop followed by an OFF event is announced once") {
    def app = newApp()
    def pump = new FakeSwitch("on")             // healthy: off() actually turns the relay off
    app.pumpSwitch = pump
    app.watchdogAnyPumpRun = true
    app.failsafePumpRunMinutes = 20
    app.state.activeDose = [ml: "237", seconds: 65, started: app.clockMs - 65_000L, stopAt: app.clockMs]
    app.scheduled["stopDose"] = 0
    app.scheduled["emergencyPumpOff"] = 1200

    app.stopDose()                              // confirms off synchronously -> cleanup + notice

    expect(app.noticesMatching("stopped after scheduled").size() == 1,
           "the synchronous confirmed stop sends the final notice")
    expect(app.state.activeDose == null, "and clears the dose")

    app.pumpSwitchHandler([value: "off"])       // the device's own OFF event arrives afterwards

    expect(app.noticesMatching("stopped after scheduled").size() == 1,
           "an OFF event after synchronous cleanup must not repeat the notice")
}

check("regression: an early OFF keeps the early-stop notice and releases the dose") {
    def app = newApp()
    app.pumpSwitch = new FakeSwitch("off")
    app.state.activeDose = [ml: "237", seconds: 65, started: app.clockMs, stopAt: app.clockMs + 60_000L]
    app.scheduled["stopDose"] = 60
    app.scheduled["verifyPumpOff"] = 75
    app.scheduled["emergencyPumpOff"] = 1200

    app.pumpSwitchHandler([value: "off"])       // manual/early stop, well before the planned end

    expect(app.noticesMatching("stopped before the planned dose completed").size() == 1,
           "an early stop keeps its own accurate notice")
    expect(app.noticesMatching("stopped after scheduled").isEmpty(),
           "an early stop must not be described as completing the scheduled dose")
    expect(app.state.activeDose == null, "the early confirmed off releases the dose")
    expect(app.scheduled["stopDose"] == null && app.scheduled["verifyPumpOff"] == null &&
           app.scheduled["emergencyPumpOff"] == null, "and all stop jobs are removed")
}

check("regression: a LATE OFF past the planned stop also announces the final notice once") {
    def app = newApp()
    app.pumpSwitch = new FakeSwitch("off")
    app.state.activeDose = [ml: "237", seconds: 65, started: app.clockMs - 125_000L,
                            stopAt: app.clockMs - 60_000L]
    app.state.stopAttempts = 3

    app.pumpSwitchHandler([value: "off"])

    expect(app.noticesMatching("stopped after scheduled").size() == 1,
           "a late OFF is still a confirmed completion and must announce it once")
    expect(app.state.activeDose == null && app.state.stopAttempts == null,
           "and release the dose and retry counter")
}

check("the cutoff finding the switch ALREADY off releases the dose") {
    def app = newApp()
    app.pumpSwitch = new FakeSwitch("off")
    app.state.activeDose = [ml: "237", seconds: 65, stopAt: app.clockMs]
    app.state.stopAttempts = 5

    app.emergencyPumpOff()

    expect(app.state.activeDose == null,
           "already-off is a confirmed off; leaving the dose set blocks every later dose")
    expect(app.state.stopAttempts == null, "the retry counter must be cleared")
}

check("a THROWING switch read must not abort any stop path") {
    def app = newApp()
    def pump = new FakeSwitch("on")
    pump.mode = "readThrows"
    app.pumpSwitch = pump
    app.watchdogAnyPumpRun = true
    app.failsafePumpRunMinutes = 20
    app.state.activeDose = [ml: "237", seconds: 65, started: app.clockMs, stopAt: app.clockMs]
    app.scheduled["emergencyPumpOff"] = 1200
    app.state.emergencyDeadline = app.clockMs + 1_200_000L

    app.stopDose()                                   // must not propagate
    expect(app.scheduled["verifyPumpOff"] != null, "stopDose must still schedule a retry")

    app.verifyPumpOff()                              // must not propagate
    expect(app.state.activeDose != null, "an unreadable switch is not a confirmed stop")

    app.fire("emergencyPumpOff")                     // fired job leaves the queue, then runs
    expect(app.scheduled["emergencyPumpOff"] != null,
           "the cutoff must re-arm even when the switch cannot be read")
    expect(app.state.activeDose != null, "and must not pretend the pump is confirmed off")
}

check("computeTankRunway: ongoing zero-dose days lower the measured rate") {
    def app = newApp()
    primeTank(app, 56781.176760G, 50000G,
              [[app.clockMs - 10 * DAY_MS, 1000], [app.clockMs, 1000]])

    def before = app.computeTankRunway()
    app.clockMs = app.clockMs + (10 * DAY_MS)        // ten more days, no dose
    def after = app.computeTankRunway()

    expect(Math.abs((before.dailyUse as BigDecimal).doubleValue() - 200.0d) < 0.01d,
           "sanity: the first measurement should be 200 mL/day, got ${before.dailyUse}")
    expect(Math.abs((after.dailyUse as BigDecimal).doubleValue() - 100.0d) < 0.01d,
           "ten zero-dose days should halve the rate, got ${after.dailyUse}")
    expect((after.days as BigDecimal) > (before.days as BigDecimal),
           "and the runway should lengthen")
}

// ------------------------------------------------------- queue resets (initialize/updated)

check("a queue reset must RECREATE the cutoff job, keeping the original deadline") {
    def app = newApp()
    def pump = new FakeSwitch("on")
    pump.mode = "ignoresOff"
    app.pumpSwitch = pump
    app.watchdogAnyPumpRun = true
    app.failsafePumpRunMinutes = 20
    app.createTile = false
    app.state.activeDose = [ml: "237", seconds: 65, started: app.clockMs, stopAt: app.clockMs]
    // 5 minutes left of a 20-minute window: enough to distinguish "remaining" from "restarted".
    app.state.emergencyDeadline = app.clockMs + 300_000L
    app.state.emergencyJobScheduled = true
    app.scheduled["emergencyPumpOff"] = 300

    app.updated()      // safeStopPump -> unsubscribe -> initialize, which clears the queue

    expect(app.scheduled["emergencyPumpOff"] != null,
           "the cutoff timer must exist after the reset; queue was ${app.scheduled}")
    expect(app.state.emergencyDeadline == app.clockMs + 300_000L,
           "the original deadline must be preserved, got ${app.state.emergencyDeadline}")
    expect(app.scheduled["emergencyPumpOff"] == 300,
           "recreated with the REMAINING 300s, not a fresh window; got ${app.scheduled['emergencyPumpOff']}")
}

check("after a queue reset, exhausting the retries must leave a REAL armed job") {
    def app = newApp()
    def pump = new FakeSwitch("on")
    pump.mode = "ignoresOff"
    app.pumpSwitch = pump
    app.watchdogAnyPumpRun = true
    app.failsafePumpRunMinutes = 20
    app.createTile = false
    app.state.activeDose = [ml: "237", seconds: 65, started: app.clockMs, stopAt: app.clockMs]
    app.state.emergencyDeadline = app.clockMs + 300_000L
    app.state.emergencyJobScheduled = true
    app.scheduled["emergencyPumpOff"] = 300

    app.updated()

    // Fire the verification retries the way the hub would: advance the clock by the scheduled
    // delay, remove the job, then run the handler.
    int fired = 0
    while (fired < 12 && app.scheduled["verifyPumpOff"] != null) {
        app.clockMs = app.clockMs + (((app.scheduled["verifyPumpOff"] ?: 20) as Integer) * 1000L)
        app.fire("verifyPumpOff")
        fired++
    }

    expect((app.state.stopAttempts as Integer) >= 5,
           "the retries should be exhausted, got ${app.state.stopAttempts}")
    expect(app.scheduled["emergencyPumpOff"] != null,
           "a claim that the cutoff is armed must be backed by a job; queue was ${app.scheduled}")
    expect(app.state.emergencyJobScheduled == true,
           "and the app should know a job is pending")
}

check("control: a queue reset with a healthy switch clears state and queue") {
    def app = newApp()
    def pump = new FakeSwitch("on")
    app.pumpSwitch = pump
    app.watchdogAnyPumpRun = true
    app.failsafePumpRunMinutes = 20
    app.createTile = false
    app.state.activeDose = [ml: "237", seconds: 65, started: app.clockMs, stopAt: app.clockMs]
    app.state.emergencyDeadline = app.clockMs + 1_200_000L
    app.state.emergencyJobScheduled = true
    app.scheduled["emergencyPumpOff"] = 1200

    app.updated()

    expect(app.state.activeDose == null, "a confirmed stop clears the dose")
    expect(app.state.emergencyDeadline == null, "and the deadline")
    expect(app.scheduled["emergencyPumpOff"] == null, "and the cutoff job")
    expect(!app.noticesMatching("Chlorine pump stopped: configuration changed").isEmpty(),
           "and says so")
}

// ---------------------------------------------------------------- failed starts (ON throws)

/** Enough settings for startDose() to clear doseSafetyBlocks and actually reach the ON command. */
def primeForStart = { app, pump ->
    app.pumpSwitch = pump
    app.dosingMode = "APPROVAL"        // skips the AUTO window/daily-total checks
    app.pumpRateMlPerMin = 221
    app.minDoseMl = 50
    app.maxSingleDoseMl = 3000
    app.maxDailyDoseMl = 3500
    app.maxPumpRunMinutes = 40
    app.minSafePh = 6.8
    app.maxSafePh = 8.2
    app.maxSampleAgeHours = 18
    app.circulationAlwaysOn = true     // satisfies the interlock requirement
    app.chlorineTankGallons = "15"
    app.tankLowPercent = "10"
    app.createTile = false
    app.watchdogAnyPumpRun = true
    app.failsafePumpRunMinutes = 20
    app.state.tankCapacityGallons = "15"
    app.state.tankRemainingMl = "56544"
    app.state.doseMlToday = "0"
    // 2.4.0 start-confirmation defaults, explicit so a test does not depend on the app's fallbacks.
    app.startPowerMinWatts = 3
    app.startConfirmTimeoutSeconds = 20
    app.powerLossGraceSeconds = 30
}

/**
 * Bind a fake switch's state timestamps to the app's fake clock. New start-confirmation tests
 * use this so a reading can be made deliberately stale (or fresh) relative to the app clock;
 * the pre-timestamp tests leave it unbound and read the wall clock, which is always "fresh".
 */
def bindClock = { app, pump ->
    pump.clock = { app.clockMs }
    return pump
}

def sampleDose = { app ->
    [doseMl: 237G, runSeconds: 65, sampleKey: "${app.clockMs}", pH: 7.4G, fcVal: 5.7G, fcTarget: 6.0G]
}

/**
 * Drive the real code into a STANDALONE fault recovery: an ignored ON times out and is cleaned
 * up, then a late ON arrives while the switch now ignores OFF too. activeDose is null; the fault
 * and faultStopAt carry the stop recovery.
 */
def startStandaloneRecovery = { app, pump ->
    primeForStart(app, pump)
    pump.mode = "ignoresOn"
    app.startDose(sampleDose(app), "test")
    app.clockMs += 21_000L
    app.fire("verifyStartConfirmation")       // faults; OFF works -> cleanup, fault remains
    pump.mode = "ignoresAll"
    pump.setReportedAt("on", app.clockMs)
    app.pumpSwitchHandler([name: "switch", value: "on", date: new Date(app.clockMs)])
    return app
}

check("a FAILED start must not leave the arming flag set with no job") {
    def app = newApp()
    def pump = new FakeSwitch("off")
    pump.mode = "onThrows"             // ON fails, the relay never closed, OFF works
    primeForStart(app, pump)

    app.startDose(sampleDose(app), "test")

    expect(pump.onCalls == 1, "the ON should have been attempted")
    expect(app.state.activeDose == null, "confirmed off: no dose should remain")
    expect(app.scheduled["emergencyPumpOff"] == null, "and no cutoff timer should remain")
    expect(app.state.emergencyJobScheduled != true,
           "the pending flag must not outlive the job; an orphan suppresses the next arm")
    expect(app.state.emergencyDeadline == null, "nor should the deadline survive")

    // The consequence that matters: protection must still be armable afterwards. Turn the relay
    // on as well as changing its behaviour -- setting the mode alone leaves it reporting off, and
    // verifyPumpOff would simply confirm and return.
    pump.mode = "ignoresOff"
    pump.setReported("on")
    app.state.activeDose = [ml: "237", seconds: 65, started: app.clockMs, stopAt: app.clockMs]
    app.verifyPumpOff()
    expect(app.scheduled["emergencyPumpOff"] != null,
           "an unconfirmed stop must arm protection; queue was ${app.scheduled}")
    expect(app.scheduled["verifyPumpOff"] != null, "and keep retrying; queue was ${app.scheduled}")
}

check("a start that energises THEN fails, with OFF ignored, must keep protection") {
    def app = newApp()
    def pump = new FakeSwitch("off")
    pump.mode = "onThrowsThenStuck"     // relay closed, command errored, and OFF is ignored
    primeForStart(app, pump)

    app.startDose(sampleDose(app), "test")

    expect(pump.currentValue("switch") == "on", "the relay is physically on")
    expect(app.state.activeDose != null,
           "a pump that may be running must stay recorded, or nothing blocks a second dose")
    expect(app.scheduled["emergencyPumpOff"] != null,
           "and it must have scheduled protection; queue was ${app.scheduled}")
    expect(app.state.emergencyJobScheduled == true, "with the bookkeeping to match")
    expect(app.scheduled["verifyPumpOff"] != null, "and a stop retry")
}

check("control: a healthy start leaves all three protection jobs present") {
    def app = newApp()
    def pump = new FakeSwitch("off")
    primeForStart(app, pump)

    app.startDose(sampleDose(app), "test")

    expect(app.state.activeDose != null, "the dose is running")
    expect(app.scheduled["stopDose"] != null, "scheduled stop present")
    expect(app.scheduled["verifyPumpOff"] != null, "verification present")
    expect(app.scheduled["emergencyPumpOff"] != null, "independent cutoff present")
    expect(app.state.emergencyJobScheduled == true, "and the flag agrees with the queue")
}

// --------------------------------------------------------------------------- start confirmation (2.4.0)

check("start: an ON that never energises is not announced as started; the attempt is reserved and faulted") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.mode = "ignoresOn"          // on() returns, the relay never reports on
    def dose = sampleDose(app)

    app.startDose(dose, "test")

    expect(app.noticesMatching("pump started").isEmpty(),
           "a start request must not be announced as started")
    expect(!app.noticesMatching("start requested").isEmpty(),
           "it should be truthful that a start was requested")
    expect(app.state.activeDose != null && app.state.activeDose.startConfirmed == false,
           "the dose is tracked as unconfirmed")
    expect(app.state.doseMlToday == "237", "the planned volume must be reserved before ON")
    expect(app.state.lastAttempt != null, "the attempt must be recorded")
    expect(app.state.lastDose == null, "no success telemetry before confirmation")
    expect(app.state.tankRemainingMl == "56544", "tank must not be drawn before confirmation")

    app.clockMs += 21_000L
    app.fire("verifyStartConfirmation")

    expect(app.noticesMatching("pump started").isEmpty(), "still no started claim")
    expect(app.noticesMatching("start not confirmed:").size() == 1, "the failure must alert exactly once")
    expect(app.state.startFault?.kind == "start-unconfirmed", "the fault must latch for the operator")
    expect(app.state.activeDose == null, "the switch confirmed off, so the dose is released")
    expect(app.state.doseMlToday == "237", "the reservation must survive the failure")
    expect(app.state.lastDose == null, "no success record on a failed start")
    expect(app.state.tankRemainingMl == "56544", "no tank draw on a failed start")
    expect(pump.onCalls == 1, "the ON must never be retried automatically")
}

check("start: a cached pre-attempt power reading never confirms a new start") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.powerCapable = true
    // The ON stamps a fresh switch reading, but the only power report predates the request.
    pump.setPowerAt(7.2G, app.clockMs - 60_000L)

    app.startDose(sampleDose(app), "test")
    app.fire("verifyStartConfirmation")

    expect(app.noticesMatching("pump started").isEmpty(),
           "a cached pre-attempt power reading must not confirm a new start")
    expect(app.state.activeDose?.startConfirmed == false, "the start stays unconfirmed")

    // An ON reading dated before the request is likewise not fresh evidence.
    def app2 = newApp()
    def pump2 = bindClock(app2, new FakeSwitch("off"))
    primeForStart(app2, pump2)
    app2.startDose(sampleDose(app2), "test")
    pump2.setSwitchAt("on", app2.clockMs - 60_000L)
    app2.fire("verifyStartConfirmation")
    expect(app2.noticesMatching("pump started").isEmpty(),
           "a stale ON timestamp must not confirm a new start")
}

check("start: power appearing later confirms once, books once and never double-draws the tank") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.powerCapable = true
    app.startDose(sampleDose(app), "test")

    app.fire("verifyStartConfirmation")   // fresh ON but no power yet
    expect(app.noticesMatching("pump started").isEmpty(),
           "switch-only evidence must not confirm a power-capable start")

    pump.setPower(7.2G)
    app.fire("verifyStartConfirmation")

    expect(app.noticesMatching("pump started").size() == 1, "fresh ON plus power confirms exactly once")
    expect(app.state.activeDose?.startConfirmed == true, "the dose is marked confirmed")
    expect(app.state.lastDose != null, "success telemetry is booked")
    expect(app.state.tankRemainingMl == "56307", "the planned volume is drawn once")
    expect(app.state.doseMlToday == "237", "the reservation is not added a second time")

    app.verifyStartConfirmation()   // a replayed check or a late event must change nothing
    expect(app.noticesMatching("pump started").size() == 1, "a duplicate confirmation must not re-announce")
    expect(app.state.tankRemainingMl == "56307", "and must not draw the tank twice")
}

check("start: 0 W, a missing power value and a throwing power read all fail closed") {
    [["zero", 0G], ["missing", null], ["throwing", "throw"]].each { label, watts ->
        def app = newApp()
        def pump = bindClock(app, new FakeSwitch("off"))
        primeForStart(app, pump)
        pump.powerCapable = true
        app.startDose(sampleDose(app), "test")
        if (watts == "throw")       pump.powerMode = "powerReadThrows"
        else if (watts == null)     pump.powerMode = "powerMissing"
        else                        pump.setPower(watts as BigDecimal)

        app.fire("verifyStartConfirmation")
        expect(app.noticesMatching("pump started").isEmpty(), "[${label}] must not confirm")

        app.clockMs += 21_000L
        app.fire("verifyStartConfirmation")
        expect(app.state.startFault != null, "[${label}] must latch a fault")
        expect(app.state.lastDose == null, "[${label}] must not book a dose")
    }
}

check("start: a non-power switch confirms with an explicit power/flow-unverified notice") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.powerCapable = false
    app.startDose(sampleDose(app), "test")
    app.fire("verifyStartConfirmation")

    expect(app.noticesMatching("pump started").size() == 1, "switch-only confirmation is allowed")
    expect(!app.noticesMatching("unverified").isEmpty(), "and must say power/flow are unverified")
    expect(app.scheduled["verifyRunPower"] == null, "no power watch without a power device")
}

check("start: a short dose's start deadline is bounded by its planned stop") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.powerCapable = true
    def dose = sampleDose(app)
    dose.runSeconds = 5
    app.startDose(dose, "test")

    expect(app.state.activeDose.startDeadline == app.state.activeDose.stopAt,
           "the start deadline must never exceed the planned stop")
    app.clockMs += 6_000L
    app.fire("verifyStartConfirmation")
    expect(app.state.startFault != null, "an unconfirmed short dose must fault at its planned stop")
}

check("run: power loss during a confirmed run aborts safely, latches a fault and keeps protection") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.powerCapable = true
    pump.mode = "ignoresOff"        // the stop cannot be confirmed, so protection must persist
    app.startDose(sampleDose(app), "test")
    pump.setPower(7.2G)
    app.fire("verifyStartConfirmation")
    expect(app.state.activeDose?.startConfirmed == true, "sanity: confirmed")
    expect(app.state.tankRemainingMl == "56307", "sanity: drawn once")

    pump.setPower(0G)
    app.clockMs += 10_000L
    app.fire("verifyRunPower")
    expect(app.state.activeDose.fault == null, "a short dip must be tolerated inside the grace period")

    app.clockMs += 35_000L
    app.fire("verifyRunPower")
    expect(app.state.startFault?.kind == "power-loss", "the loss must latch a fault")
    expect(app.noticesMatching("power LOST").size() == 1,
           "the loss must alert once; got ${app.noticesMatching('power LOST').size()} :: ${app.notices}")
    expect(app.state.activeDose != null, "an unconfirmed stop must keep the dose")
    expect(app.scheduled["verifyPumpOff"] != null, "stop retries must continue")
    expect(app.scheduled["emergencyPumpOff"] != null, "the cutoff must stay armed")
    expect(app.state.tankRemainingMl == "56307", "no automatic inventory refund")
}

check("start: a cached OFF does not disarm a failed-start backstop, and a late ON is not reclassified") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.mode = "ignoresAll"        // dead switch: nothing the app sends changes its cached state
    pump.setSwitchAt("off", app.clockMs - 120_000L)
    def dose = sampleDose(app)

    app.startDose(dose, "test")
    app.clockMs += 21_000L
    app.fire("verifyStartConfirmation")

    expect(app.state.startFault != null, "the start fault must latch")
    expect(app.state.activeDose != null,
           "a cached pre-attempt OFF must not prematurely disarm the new backstop")
    expect(app.scheduled["verifyPumpOff"] != null, "stop retries must continue")
    expect(app.scheduled["emergencyPumpOff"] != null, "the cutoff must stay armed")

    // The relay reports ON late, after the abort. It must not become a successful dose.
    pump.setReportedAt("on", app.clockMs)
    app.pumpSwitchHandler([name: "switch", value: "on", date: new Date(app.clockMs)])
    expect(app.state.activeDose.startConfirmed == false, "a late ON must not reclassify the attempt")
    expect(app.state.startFault != null, "and must not clear the fault")
    expect(app.noticesMatching("pump started").isEmpty(), "and must not announce a start")

    // A genuinely fresh OFF confirms the stop but keeps the fault until acknowledged.
    pump.setReportedAt("off", app.clockMs)
    app.pumpSwitchHandler([name: "switch", value: "off", date: new Date(app.clockMs)])
    expect(app.state.activeDose == null, "a fresh OFF releases the dose")
    expect(app.state.startFault != null, "the fault stays visible until acknowledged")
}

check("stop: the final notice never claims a completed scheduled dose when the start was unconfirmed") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.mode = "ignoresOn"
    app.startDose(sampleDose(app), "test")
    app.clockMs += 21_000L
    app.fire("verifyStartConfirmation")

    expect(app.noticesMatching("stopped after scheduled").isEmpty(),
           "must not claim a completed scheduled dose when the start was never confirmed")
    expect(app.noticesMatching("never confirmed").size() == 1,
           "the stop notice must say the start was never confirmed")
}

check("accounting: a failed attempt still blocks its sample, the daily cap and the one-AUTO-dose guard") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.mode = "ignoresOn"
    app.maxDailyDoseMl = 300
    def dose = sampleDose(app)
    app.startDose(dose, "test")
    app.clockMs += 21_000L
    app.fire("verifyStartConfirmation")

    expect(app.autoDoseAlreadyRanToday() == true,
           "a failed attempt must consume the one-AUTO-dose day; attempt=${app.state.lastAttempt} day=${app.state.doseDay} today=${new Date().format('yyyy-MM-dd', app.location.timeZone)}")
    def blocks = app.doseSafetyBlocks(dose)
    expect(blocks.any { it.toLowerCase().contains("already dosed") },
           "the reserved sample must block a redose; got ${blocks}")
    def other = [doseMl: 237G, runSeconds: 65, sampleKey: "${app.clockMs}", pH: 7.4G, fcVal: 5.7G, fcTarget: 6.0G]
    def otherBlocks = app.doseSafetyBlocks(other)
    expect(otherBlocks.any { it.toLowerCase().contains("daily total") || it.contains("above the 300") },
           "the reserved volume must count against the daily cap; got ${otherBlocks}")
}

check("telemetry: an unconfirmed start emits no lastDose* success record") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.mode = "ignoresOn"
    def tile = new FakeSwitch("off")
    app.__tileDevice = tile
    app.createTile = true
    app.startDose(sampleDose(app), "test")
    app.clockMs += 21_000L
    app.fire("verifyStartConfirmation")

    expect(tile.sentEventsFor("lastDoseMl").isEmpty(), "no lastDoseMl on an unconfirmed start")
    expect(tile.sentEventsFor("lastDoseEpochMs").isEmpty(), "no lastDoseEpochMs on an unconfirmed start")
    expect(app.state.lastDose == null, "no lastDose success record either")
}

check("fault: acknowledgement is refused until recovery completes, then clears without changing reservations") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.mode = "ignoresAll"
    pump.setSwitchAt("off", app.clockMs - 120_000L)   // a real cached reading, not a lazy one
    def dose = sampleDose(app)
    app.startDose(dose, "test")
    app.clockMs += 21_000L
    app.fire("verifyStartConfirmation")
    expect(app.state.startFault != null, "sanity: faulted")
    expect(app.state.activeDose != null, "sanity: stop unconfirmed")
    def reserved = app.state.doseMlToday

    // The switch has NOT freshly reported OFF, so the fault must stay and acknowledgement refuse.
    app.appButtonHandler("btnAckFault")
    expect(app.state.startFault != null, "acknowledgement must be refused before a fresh OFF")
    expect(!app.noticesMatching("Cannot acknowledge").isEmpty(), "and it must say why")

    // A genuinely fresh OFF arrives; the dose clears, then acknowledgement is allowed.
    pump.setReportedAt("off", app.clockMs)
    app.pumpSwitchHandler([name: "switch", value: "off", date: new Date(app.clockMs)])
    expect(app.state.activeDose == null, "the fresh OFF releases the dose")

    app.appButtonHandler("btnAckFault")
    expect(app.state.startFault == null, "acknowledgement clears the fault after recovery")
    expect(app.state.doseMlToday == reserved, "and must keep the attempt reservation")
    expect(app.state.lastDosedSample == dose.sampleKey, "and keep the sample reservation")
    expect(pump.onCalls == 1, "acknowledgement must not energize the pump")
}

check("fault: acknowledging when nothing is pending is a no-op") {
    def app = newApp()
    app.appButtonHandler("btnAckFault")
    expect(app.state.startFault == null, "still no fault")
    expect(!app.noticesMatching("No pump fault").isEmpty(), "it should say there is nothing to acknowledge")
}

check("initialize: a pending start keeps its verification and reservation across a queue reset") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.powerCapable = true
    app.startDose(sampleDose(app), "test")
    def reserved = app.state.doseMlToday

    app.initialize()

    expect(app.scheduled["verifyStartConfirmation"] != null,
           "the start check must be recreated after a queue reset")
    expect(app.state.activeDose != null && app.state.activeDose.startConfirmed == false,
           "the unconfirmed dose must survive the reset")
    expect(app.state.doseMlToday == reserved, "the reservation must survive the reset")
    expect(app.scheduled["emergencyPumpOff"] != null, "the cutoff must be re-armed")
}

check("initialize: a latched fault stays visible and is not resurrected as a start") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.mode = "ignoresAll"
    pump.setSwitchAt("off", app.clockMs - 120_000L)
    app.startDose(sampleDose(app), "test")
    app.clockMs += 21_000L
    app.fire("verifyStartConfirmation")
    expect(app.state.startFault != null, "sanity: faulted")

    app.initialize()

    expect(app.state.startFault != null, "the fault must survive a queue reset")
    expect(app.state.activeDose != null && app.state.activeDose.fault != null,
           "the aborted attempt must not be resurrected for confirmation")
    expect(app.scheduled["verifyPumpOff"] != null, "stop protection must be recreated")
}

check("start: refresh is requested at most about every 10 s while confirming") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.powerCapable = true
    pump.refreshSupported = true
    app.startDose(sampleDose(app), "test")

    app.fire("verifyStartConfirmation")
    expect(pump.refreshCalls == 1, "the first check asks for a refresh")
    app.clockMs += 2_000L
    app.fire("verifyStartConfirmation")
    expect(pump.refreshCalls == 1, "a second check inside the throttle must not refresh again")
    app.clockMs += 9_000L
    app.fire("verifyStartConfirmation")
    expect(pump.refreshCalls == 2, "past the throttle a refresh is requested again")
}

check("run: a throwing power read cannot strand a confirmed pump with no stop jobs") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.powerCapable = true
    pump.mode = "ignoresOff"
    app.startDose(sampleDose(app), "test")
    pump.setPower(7.2G)
    app.fire("verifyStartConfirmation")
    expect(app.state.activeDose?.startConfirmed == true, "sanity: confirmed")

    pump.powerMode = "powerReadThrows"
    app.clockMs += 10_000L
    app.fire("verifyRunPower")
    expect(app.state.activeDose != null, "a throwing read must not drop the active dose")
    expect(app.scheduled["verifyRunPower"] != null, "the power watch must keep running")
    expect(app.scheduled["emergencyPumpOff"] != null, "and the cutoff must stay armed")

    app.clockMs += 35_000L
    app.fire("verifyRunPower")
    expect(app.state.startFault?.kind == "power-loss", "a persistent unreadable value must latch a fault")
    expect(app.scheduled["verifyPumpOff"] != null, "and stop retries must continue")
    expect(app.scheduled["emergencyPumpOff"] != null, "with the cutoff still armed")
}

check("run: a refresh that throws does not strand stop protection") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.powerCapable = true
    pump.refreshSupported = true
    pump.refreshThrows = true
    app.startDose(sampleDose(app), "test")
    pump.setPower(7.2G)
    app.fire("verifyStartConfirmation")
    expect(app.state.activeDose?.startConfirmed == true, "sanity: confirmed")

    app.clockMs += 10_000L
    app.fire("verifyRunPower")   // healthy -> asks for a refresh, which throws

    expect(app.state.activeDose?.startConfirmed == true, "a throwing refresh must not fail a healthy run")
    expect(app.scheduled["verifyRunPower"] != null, "the watch must reschedule")
    expect(app.scheduled["emergencyPumpOff"] != null, "the cutoff must stay armed")
    expect(app.scheduled["verifyPumpOff"] != null, "and the stop retry must exist")
    expect(app.logLines.any { it.startsWith("ERROR") && it.contains("refresh") },
           "the refresh failure should be logged")
}

check("migration: a failed start does not rewrite the existing lastDose or tank history") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.mode = "ignoresOn"
    long oldTime = app.clockMs - 3_600_000L
    app.state.lastDose = [time: oldTime, ml: "500", mlRaw: "500", seconds: 120, sample: "old"]
    app.state.tankDoseHistory = [[t: oldTime, ml: "500"]]
    app.state.tankRemainingMl = "56044"          // 56544 - the historical 500

    app.startDose(sampleDose(app), "test")
    app.clockMs += 21_000L
    app.fire("verifyStartConfirmation")

    expect(app.state.lastDose.time == oldTime, "the historical lastDose must not be rewritten")
    expect(app.state.lastDose.ml == "500", "nor its volume")
    expect(app.state.tankDoseHistory.size() == 1, "nor the tank history")
    expect(app.state.tankDoseHistory[0].t == oldTime, "nor its entries")
    expect(app.state.tankRemainingMl == "56044", "nor the tank inventory")
    expect(app.state.doseMlToday == "237", "but the new attempt is reserved")
}

check("updated: renewing configuration during a pending start keeps stop protection and the reservation") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.mode = "ignoresAll"
    pump.setSwitchAt("off", app.clockMs - 120_000L)
    app.startDose(sampleDose(app), "test")
    def reserved = app.state.doseMlToday

    app.updated()   // safeStopPump -> unsubscribe -> initialize

    expect(app.state.activeDose != null, "an unconfirmed stop must survive a configuration change")
    expect(app.scheduled["verifyPumpOff"] != null, "stop retries must be recreated")
    expect(app.scheduled["emergencyPumpOff"] != null, "the cutoff must be re-armed")
    expect(app.state.doseMlToday == reserved, "the reservation must survive")
}

check("start: pressing STOP during a pending start aborts it and no later report can reclassify it") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.mode = "ignoresOff"
    app.startDose(sampleDose(app), "test")
    expect(app.state.activeDose?.startConfirmed == false, "sanity: still unconfirmed")

    app.appButtonHandler("btnStopPump")

    expect(app.scheduled["verifyStartConfirmation"] == null, "STOP must cancel the pending start check")
    expect(app.state.activeDose?.offRequestedAt != null, "and record that an OFF was requested")

    // A late fresh ON and power must not resurrect it as a started dose.
    pump.setReportedAt("on", app.clockMs)
    pump.setPowerAt(7.2G, app.clockMs)
    app.pumpSwitchHandler([name: "power", value: 7.2])
    app.pumpSwitchHandler([name: "switch", value: "on", date: new Date(app.clockMs)])
    expect(app.noticesMatching("pump started").isEmpty(), "no started announcement after STOP")
    expect(app.state.activeDose?.startConfirmed == false, "and it stays unconfirmed")
}

// --------------------------------------------------- independent-review findings (2.4.0 repair)
//
// NOTE ON THE STUB: Hubitat's `singleThreaded: true` declaration is what serialises handlers on a
// real hub. The off-hub stub does NOT model concurrency, so the exactly-once tests below simulate
// an overlapping/replayed handler by calling the verifier/booking path directly with a COPY of the
// attempt map taken before the first call. The idempotency guard itself is fully exercised.

check("start: evidence at the deadline is rejected before any booking") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.powerCapable = true
    app.startDose(sampleDose(app), "test")

    app.clockMs += 20_000L          // deadline is requestedAt + 20s
    pump.setPower(7.2G)
    app.fire("verifyStartConfirmation")

    expect(app.noticesMatching("pump started").isEmpty(), "late evidence must not book a start")
    expect(app.state.startFault != null, "it must fault instead")
    expect(app.state.lastDose == null && app.state.tankRemainingMl == "56544", "and book nothing")
}

check("start: evidence first seen after the planned stop cannot book (short dose)") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.powerCapable = true
    def dose = sampleDose(app)
    dose.runSeconds = 5
    app.startDose(dose, "test")
    expect(app.state.activeDose.startDeadline == app.state.activeDose.stopAt,
           "the deadline is bounded by the planned stop")

    app.clockMs += 6_000L
    pump.setPower(7.2G)
    app.fire("verifyStartConfirmation")
    expect(app.noticesMatching("pump started").isEmpty(), "post-stop evidence must not confirm")
    expect(app.state.startFault != null, "it faults instead")
}

check("start: a future-dated reading is rejected as evidence") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.powerCapable = true
    app.startDose(sampleDose(app), "test")
    pump.setSwitchAt("on", app.clockMs + 60_000L)
    pump.setPowerAt(7.2G, app.clockMs + 60_000L)
    app.fire("verifyStartConfirmation")
    expect(app.noticesMatching("pump started").isEmpty(), "future-dated evidence must not confirm")
    expect(app.state.activeDose?.startConfirmed == false, "the start stays unconfirmed")
}

check("start: a rejected stale OFF neither cancels the attempt nor manufactures a stop/fault") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    app.startDose(sampleDose(app), "test")
    def before = new LinkedHashMap(app.scheduled)
    app.pumpSwitchHandler([name: "switch", value: "off", date: new Date(app.clockMs - 60_000L)])
    expect(app.state.activeDose != null, "the attempt must survive a pre-request OFF")
    expect(app.state.activeDose.startConfirmed == false, "and stay unconfirmed")
    expect(app.state.startFault == null, "no failure is manufactured from an old report")
    expect(app.scheduled == before, "and the existing start/stop schedule is unchanged")
}

check("start: a missing-date OFF report cannot cancel an unconfirmed attempt") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    app.startDose(sampleDose(app), "test")
    def before = new LinkedHashMap(app.scheduled)
    app.pumpSwitchHandler([name: "switch", value: "off"])
    expect(app.state.activeDose != null, "an undated OFF must not cancel an unconfirmed attempt")
    expect(app.state.startFault == null, "nor latch a fault")
    expect(app.scheduled == before, "nor change the schedule")
}

check("run: a stale OFF report cannot stop a confirmed run") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.powerCapable = true
    app.startDose(sampleDose(app), "test")
    pump.setPower(7.2G)
    app.fire("verifyStartConfirmation")
    expect(app.state.activeDose?.startConfirmed == true, "sanity: confirmed")

    app.pumpSwitchHandler([name: "switch", value: "off", date: new Date(app.clockMs - 60_000L)])
    expect(app.state.activeDose != null && app.state.activeDose.startConfirmed == true,
           "a stale OFF must not stop a running dose")
    expect(app.state.lastDose != null, "and its telemetry stays")
}

check("fault: a late ON after failed-start cleanup is stop-only, never a new run window") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.mode = "ignoresOn"
    app.startDose(sampleDose(app), "test")
    app.clockMs += 21_000L
    app.fire("verifyStartConfirmation")
    expect(app.state.activeDose == null && app.state.startFault != null, "sanity: faulted and stopped")

    def offCalls = pump.offCalls
    pump.setReportedAt("on", app.clockMs)
    app.pumpSwitchHandler([name: "switch", value: "on", date: new Date(app.clockMs)])
    expect(pump.offCalls > offCalls, "a late ON must trigger an immediate OFF")
    expect(app.noticesMatching("no new dose").size() == 1, "with a stop-only notice")
    expect(app.state.startFault != null, "and the fault stays until acknowledged")
    expect(app.noticesMatching("pump started").isEmpty(), "and no start is announced")
}

check("start: the scheduled stop before confirmation latches a fault and claims no dose") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.powerCapable = true
    def dose = sampleDose(app)
    dose.runSeconds = 5
    app.startDose(dose, "test")
    app.clockMs += 5_000L
    app.fire("stopDose")

    expect(app.state.startFault?.kind == "start-unconfirmed", "the unconfirmed termination must latch")
    expect(app.noticesMatching("start NOT confirmed").size() == 1, "alert exactly once")
    expect(app.noticesMatching("pump started").isEmpty(), "no start claim")
    expect(app.state.lastDose == null && app.state.tankRemainingMl == "56544",
           "no success telemetry or tank draw")
}

check("start: an early OFF before confirmation latches a fault and claims no dose") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    app.startDose(sampleDose(app), "test")
    app.clockMs += 3_000L
    pump.setReportedAt("off", app.clockMs)
    app.pumpSwitchHandler([name: "switch", value: "off", date: new Date(app.clockMs)])

    expect(app.state.startFault?.kind == "start-unconfirmed", "an early unconfirmed OFF must latch")
    expect(app.noticesMatching("start NOT confirmed").size() == 1, "alert once")
    expect(app.noticesMatching("stopped before the planned dose").size() == 1, "early stop notice")
    expect(app.state.lastDose == null, "no success telemetry")
}

check("start: stop-then-timeout (either order) never duplicates the start alert") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.powerCapable = true
    def dose = sampleDose(app)
    dose.runSeconds = 5
    app.startDose(dose, "test")
    app.clockMs += 5_000L
    app.fire("stopDose")
    def before = app.noticesMatching("start NOT confirmed").size()
    app.verifyStartConfirmation()   // a late timeout job must no-op
    expect(before == 1, "exactly one alert")
    expect(app.noticesMatching("start NOT confirmed").size() == 1, "a late timeout must not re-alert")
}

check("stop: recovery requests a bounded refresh, and a refresh alone is not evidence") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("on"))
    primeForStart(app, pump)
    pump.mode = "ignoresOff"
    pump.refreshSupported = true
    app.state.activeDose = [attemptId: "x", ml: "237", seconds: 65, requestedAt: app.clockMs,
                            stopAt: app.clockMs + 65_000L, startConfirmed: true, fault: null]
    app.clockMs += 65_000L
    app.stopDose()

    expect(pump.refreshCalls >= 1, "stop recovery must ask the device to re-report")
    expect(app.state.activeDose != null, "a refresh request alone must not confirm the stop")
    expect(app.scheduled["verifyPumpOff"] != null, "and recovery must keep retrying")

    pump.setReportedAt("off", app.clockMs)   // the refreshed report finally arrives
    app.verifyPumpOff()
    expect(app.state.activeDose == null, "a fresh OFF after the refresh confirms the stop")
}

check("cutoff: confirming OFF cleans up every start/run job and latches uncertainty for a confirmed run") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("on"))
    primeForStart(app, pump)
    app.state.activeDose = [attemptId: "x", ml: "237", mlRaw: "237", seconds: 65,
                            requestedAt: app.clockMs - 1_000L, stopAt: app.clockMs + 60_000L,
                            startConfirmed: true, fault: null, confirmedAt: app.clockMs - 1_000L,
                            lastPowerReportAt: app.clockMs - 1_000L]
    app.state.emergencyDeadline = app.clockMs + 300_000L
    app.state.emergencyJobScheduled = true
    app.scheduled["emergencyPumpOff"] = 300
    app.scheduled["verifyStartConfirmation"] = 2
    app.scheduled["verifyRunPower"] = 10

    app.fire("emergencyPumpOff")

    expect(app.state.activeDose == null, "the cutoff must fully release the dose")
    expect(app.scheduled["verifyStartConfirmation"] == null, "no start job may survive the cutoff")
    expect(app.scheduled["verifyRunPower"] == null, "no power job may survive the cutoff")
    expect(app.state.startFault?.kind == "emergency-stop",
           "a cutoff of a confirmed run latches uncertainty for review")
}

check("start: a zero or negative minimum still requires strictly positive power") {
    [0G, -5G].each { setting ->
        def app = newApp()
        def pump = bindClock(app, new FakeSwitch("off"))
        primeForStart(app, pump)
        pump.powerCapable = true
        app.startPowerMinWatts = setting
        app.startDose(sampleDose(app), "test")
        pump.setPower(0G)
        app.fire("verifyStartConfirmation")
        expect(app.noticesMatching("pump started").isEmpty(), "[${setting}] 0 W must never confirm")
    }
}

check("start: power below the minimum fails and power at the minimum confirms") {
    [2G: false, 3G: true].each { watts, confirms ->
        def app = newApp()
        def pump = bindClock(app, new FakeSwitch("off"))
        primeForStart(app, pump)
        pump.powerCapable = true
        app.startDose(sampleDose(app), "test")
        pump.setPower(watts)
        app.fire("verifyStartConfirmation")
        expect(app.noticesMatching("pump started").isEmpty() == !confirms, "[${watts} W] expectation")
    }
}

check("start: there is no setting that bypasses power confirmation for a power-capable switch") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.powerCapable = true
    app.startConfirmMode = "DISABLED"     // the removed option must be ignored entirely
    app.startDose(sampleDose(app), "test")
    app.fire("verifyStartConfirmation")   // fresh ON but no power
    expect(app.noticesMatching("pump started").isEmpty(), "power must remain required")
}

check("accounting: overlapping confirmation handlers book exactly once") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.powerCapable = true
    app.startDose(sampleDose(app), "test")
    pump.setPower(7.2G)
    def active = app.state.activeDose
    def stale = new LinkedHashMap(active)
    app.confirmDoseStart(active, "first handler", active.lastRefreshAt)
    app.confirmDoseStart(stale, "overlapping handler", stale.lastRefreshAt)
    app.recordConfirmedDose(stale)
    expect(app.noticesMatching("pump started").size() == 1, "only one announcement")
    expect(app.state.tankRemainingMl == "56307", "only one tank draw")
    expect(app.state.doseMlToday == "237", "the reservation is unchanged")
}

check("accounting: a stale attempt map cannot book or revive a dose after cleanup") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.mode = "ignoresOn"
    app.startDose(sampleDose(app), "test")
    def stale = new LinkedHashMap(app.state.activeDose)
    app.clockMs += 21_000L
    app.fire("verifyStartConfirmation")
    expect(app.state.activeDose == null, "sanity: cleaned up")
    def tankAfter = app.state.tankRemainingMl

    app.confirmDoseStart(stale, "stale", null)
    app.recordConfirmedDose(stale)
    expect(app.state.activeDose == null, "a stale map must not revive the dose")
    expect(app.state.tankRemainingMl == tankAfter, "nor draw the tank")
    expect(app.state.lastDose == null, "nor book success")
    expect(app.noticesMatching("pump started").isEmpty(), "nor announce a start")
}

check("run: missing power evidence is bounded once from the last usable reading") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.powerCapable = true
    app.startDose(sampleDose(app), "test")
    pump.setPower(7.2G)
    app.fire("verifyStartConfirmation")

    app.clockMs += 25_000L
    app.fire("verifyRunPower")
    expect(app.state.activeDose.fault == null, "25 s < the 30 s bound must be tolerated")

    app.clockMs += 20_000L
    app.fire("verifyRunPower")
    expect(app.state.startFault?.kind == "power-loss", "45 s from the last usable reading must abort")
}

check("run: a low reading is bounded from the first low observation, with fresh reports") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.powerCapable = true
    app.startDose(sampleDose(app), "test")
    pump.setPower(7.2G)
    app.fire("verifyStartConfirmation")

    app.clockMs += 10_000L
    pump.setPower(1G)
    app.fire("verifyRunPower")
    expect(app.state.activeDose.fault == null, "a fresh low reading starts the grace")

    app.clockMs += 20_000L
    pump.setPower(1G)
    app.fire("verifyRunPower")
    expect(app.state.activeDose.fault == null, "20 s of low power is inside the grace")

    app.clockMs += 15_000L
    pump.setPower(1G)
    app.fire("verifyRunPower")
    expect(app.state.startFault?.kind == "power-loss", "35 s of low power must abort")
}

check("run: the confirming power report is accepted as fresh without re-dating") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.powerCapable = true
    def powerAt = app.clockMs
    app.startDose(sampleDose(app), "test")
    pump.setPower(7.2G)              // reported at powerAt
    app.clockMs += 2_000L
    app.fire("verifyStartConfirmation")
    expect(app.state.activeDose?.startConfirmed == true, "sanity: confirmed")

    app.clockMs += 10_000L
    app.fire("verifyRunPower")
    expect(app.state.activeDose.lastPowerReportAt == powerAt,
           "the confirming report keeps its own timestamp")
    expect(app.state.activeDose.powerLowSince == null, "and counts as fresh evidence")
    expect(app.state.activeDose.fault == null, "so the run is not aborted")
}

check("stop: an unreadable switch is described as OFF not freshly confirmed, never as ON") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("on"))
    primeForStart(app, pump)
    pump.mode = "readThrows"
    app.state.activeDose = [attemptId: "x", ml: "237", seconds: 65, requestedAt: app.clockMs,
                            stopAt: app.clockMs + 65_000L, startConfirmed: true, fault: null]
    app.clockMs += 65_000L
    app.stopDose()
    expect(!app.noticesMatching("OFF has not been freshly confirmed").isEmpty(),
           "an unreadable OFF must be described as not freshly confirmed")
    expect(app.noticesMatching("the switch reports ON").isEmpty(),
           "it must not claim ON when the reading is unreadable")
}

check("run: a future-dated OFF report cannot stop a running pump") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.powerCapable = true
    app.startDose(sampleDose(app), "test")
    pump.setPower(7.2G)
    app.fire("verifyStartConfirmation")
    expect(app.state.activeDose?.startConfirmed == true, "sanity: confirmed")
    def before = new LinkedHashMap(app.scheduled)

    app.pumpSwitchHandler([name: "switch", value: "off", date: new Date(app.clockMs + 60_000L)])
    expect(app.state.activeDose != null && app.state.activeDose.startConfirmed == true,
           "a future-dated OFF must not stop the run")
    expect(app.scheduled == before, "and must not change any job")
}

check("fault recovery: a stale OFF cannot clear the new stop jobs while the switch reports ON") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.mode = "ignoresOn"
    app.startDose(sampleDose(app), "test")
    app.clockMs += 21_000L
    app.fire("verifyStartConfirmation")          // faults; OFF accepted -> cleanup, fault remains
    expect(app.state.activeDose == null && app.state.startFault != null, "sanity: fault with no active dose")

    // A late ON after cleanup, and now the switch ignores OFF too.
    pump.mode = "ignoresAll"
    pump.setReportedAt("on", app.clockMs)
    app.pumpSwitchHandler([name: "switch", value: "on", date: new Date(app.clockMs)])
    expect(app.scheduled["verifyPumpOff"] != null, "the late ON must start a stop recovery")
    expect(app.state.faultStopAt != null, "and set a persistent recovery anchor")

    // An OFF event from BEFORE the late ON must not clear the recovery jobs.
    app.pumpSwitchHandler([name: "switch", value: "off", date: new Date(app.clockMs - 120_000L)])
    expect(app.scheduled["verifyPumpOff"] != null, "a stale OFF must not clear recovery")
    expect(app.scheduled["emergencyPumpOff"] != null, "nor disarm the cutoff")
    expect(app.state.startFault != null, "nor clear the fault")

    // A genuinely fresh OFF does clear the recovery, but keeps the fault for acknowledgement.
    pump.setReportedAt("off", app.clockMs)
    app.pumpSwitchHandler([name: "switch", value: "off", date: new Date(app.clockMs)])
    expect(app.scheduled["verifyPumpOff"] == null, "a fresh OFF ends the recovery")
    expect(app.state.startFault != null, "but the fault still waits for acknowledgement")
}

check("run: a rejected stale OFF during a valid run does not stop it early or add a stop attempt") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    app.startDose(sampleDose(app), "test")
    app.fire("verifyStartConfirmation")          // switch-only confirmation
    expect(app.state.activeDose?.startConfirmed == true, "sanity: confirmed switch-only")
    def before = new LinkedHashMap(app.scheduled)

    app.pumpSwitchHandler([name: "switch", value: "off", date: new Date(app.clockMs - 60_000L)])
    expect(app.state.activeDose != null, "the valid run must continue")
    expect(app.scheduled == before, "and no early stop attempt or cutoff is manufactured")
}

check("harness: an unchanged switch read is not freshly stamped on each read") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    def first = pump.currentState("switch").date.time
    app.clockMs += 60_000L
    def second = pump.currentState("switch").date.time
    expect(first == second, "reading the same state must not make it look fresh")
}

check("initialize: a standalone fault recovery keeps real stop jobs and the original deadline") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    startStandaloneRecovery(app, pump)
    expect(app.state.activeDose == null && app.state.startFault != null, "sanity: fault-only recovery")
    expect(app.state.faultStopAt != null, "sanity: recovery anchor")
    def deadline = app.state.emergencyDeadline
    def reserved = app.state.doseMlToday

    app.initialize()

    expect(app.scheduled["verifyPumpOff"] != null, "the stop verification must be recreated")
    expect(app.scheduled["emergencyPumpOff"] != null, "the cutoff job must be recreated")
    expect(app.state.emergencyDeadline == deadline, "the ORIGINAL deadline must be preserved")
    expect(app.scheduled["verifyStartConfirmation"] == null, "no start job for a fault-only recovery")
    expect(app.scheduled["verifyRunPower"] == null, "nor a power-watch job")
    expect(app.state.startFault != null, "the fault stays")
    expect(app.state.doseMlToday == reserved, "and the reservation stays")
}

check("updated: renewal does not lose a standalone fault recovery or its deadline") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    startStandaloneRecovery(app, pump)
    def deadline = app.state.emergencyDeadline
    def reserved = app.state.doseMlToday

    app.updated()

    expect(app.scheduled["verifyPumpOff"] != null, "stop verification survives the renewal")
    expect(app.scheduled["emergencyPumpOff"] != null, "the cutoff job survives the renewal")
    expect(app.state.emergencyDeadline == deadline, "the original deadline is preserved")
    expect(app.state.startFault != null && app.state.faultStopAt != null, "the fault and anchor survive")
    expect(app.state.doseMlToday == reserved, "the reservation survives")
}

check("fault acknowledgement: refused on a stale OFF, then full cleanup on a fresh OFF") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    startStandaloneRecovery(app, pump)
    expect(app.state.faultStopAt != null && app.state.activeDose == null, "sanity: standalone recovery")
    def reserved = app.state.doseMlToday
    def anchor = app.state.faultStopAt

    // A stale OFF (dated before the recovery anchor) must not be accepted.
    app.pumpSwitchHandler([name: "switch", value: "off", date: new Date(anchor - 120_000L)])
    app.appButtonHandler("btnAckFault")
    expect(app.state.startFault != null, "acknowledgement is refused while recovery is unconfirmed")
    expect(app.state.faultStopAt != null, "the recovery anchor stays")
    expect(app.scheduled["verifyPumpOff"] != null, "and recovery jobs stay")

    // A fresh OFF completes recovery; acknowledgement then clears the fault with full cleanup.
    pump.setReportedAt("off", app.clockMs)
    app.pumpSwitchHandler([name: "switch", value: "off", date: new Date(app.clockMs)])
    app.appButtonHandler("btnAckFault")
    expect(app.state.startFault == null, "acknowledgement clears the fault")
    expect(app.state.faultStopAt == null, "and the recovery anchor")
    expect(app.scheduled["verifyPumpOff"] == null, "with no orphan verification job")
    expect(app.scheduled["emergencyPumpOff"] == null, "and no orphan cutoff job")
    expect(app.state.doseMlToday == reserved, "reservations unchanged")
}

check("fault recovery: a second late ON advances the anchor so an in-between OFF is rejected") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    startStandaloneRecovery(app, pump)
    def t1 = app.state.faultStopAt

    app.clockMs += 30_000L
    pump.setReportedAt("on", app.clockMs)
    app.pumpSwitchHandler([name: "switch", value: "on", date: new Date(app.clockMs)])
    def t3 = app.state.faultStopAt
    expect(t3 > t1, "the anchor must advance to the second ON")

    // An OFF dated between the two ONs must not confirm the recovery.
    app.pumpSwitchHandler([name: "switch", value: "off", date: new Date(app.clockMs - 10_000L)])
    expect(app.scheduled["verifyPumpOff"] != null, "recovery jobs stay")
    expect(app.state.startFault != null, "the fault stays")

    // A fresh OFF after the second ON confirms the recovery.
    app.clockMs += 3_000L
    pump.setReportedAt("off", app.clockMs)
    app.pumpSwitchHandler([name: "switch", value: "off", date: new Date(app.clockMs)])
    expect(app.scheduled["verifyPumpOff"] == null, "a fresh OFF ends recovery")
    expect(app.state.startFault != null, "and the fault waits for acknowledgement")
}

check("ui: the status shows STOPPING during a standalone fault recovery") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    startStandaloneRecovery(app, pump)
    def html = app.dosingStatusHtml()
    expect(html.contains("STOPPING"), "the status must show STOPPING during fault recovery; got ${html}")
}

check("gate: an unacknowledged fault blocks every start path before ON or reservation") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.mode = "ignoresOn"
    long oldTime = app.clockMs - 3_600_000L
    app.state.lastDose = [time: oldTime, ml: "500", mlRaw: "500", seconds: 120, sample: "old"]
    app.startDose(sampleDose(app), "test")
    app.clockMs += 21_000L
    app.fire("verifyStartConfirmation")
    expect(app.state.startFault != null, "sanity: faulted")
    def reserved = app.state.doseMlToday
    def lastDoseTime = app.state.lastDose.time
    def onCalls = pump.onCalls

    // AUTO, next calendar day, new sample
    app.dosingMode = "AUTO"
    app.oneAutoDosePerDay = true
    app.clockMs += 86_400_000L
    app.startDose([doseMl: 237G, runSeconds: 65, sampleKey: "${app.clockMs}", pH: 7.4G, fcVal: 5.7G, fcTarget: 6.0G], "next-day AUTO")
    expect(pump.onCalls == onCalls, "AUTO next day must not reach ON")
    expect(app.state.doseMlToday == reserved, "and must not reserve a new attempt")
    expect(app.state.lastDose.time == lastDoseTime, "historical lastDose stays intact")

    // AUTO with the once-per-day guard disabled, another sample
    app.oneAutoDosePerDay = false
    app.clockMs += 1_000L
    app.startDose([doseMl: 237G, runSeconds: 65, sampleKey: "${app.clockMs}", pH: 7.4G, fcVal: 5.7G, fcTarget: 6.0G], "AUTO once/day off")
    expect(pump.onCalls == onCalls, "AUTO (once/day off) must not reach ON")

    // APPROVAL, another sample
    app.dosingMode = "APPROVAL"
    app.clockMs += 1_000L
    app.startDose([doseMl: 237G, runSeconds: 65, sampleKey: "${app.clockMs}", pH: 7.4G, fcVal: 5.7G, fcTarget: 6.0G], "APPROVAL")
    expect(pump.onCalls == onCalls, "APPROVAL must not reach ON")
    expect(!app.noticesMatching("unacknowledged pump fault").isEmpty(),
           "the block must name the unacknowledged fault")

    // A standalone stop recovery (no activeDose) is gated too.
    def app2 = newApp()
    def pump2 = bindClock(app2, new FakeSwitch("off"))
    startStandaloneRecovery(app2, pump2)
    expect(app2.state.faultStopAt != null, "sanity: standalone recovery")
    def standaloneBlocks = app2.doseSafetyBlocks(sampleDose(app2))
    expect(standaloneBlocks.any { it.toLowerCase().contains("stop is still being recovered") || it.toLowerCase().contains("unacknowledged") },
           "a standalone recovery must block a start; got ${standaloneBlocks}")

    // A valid acknowledgement clears the gate; ordinary sample/day limits still apply.
    pump.setReportedAt("off", app.clockMs)
    app.appButtonHandler("btnAckFault")
    expect(app.state.startFault == null, "acknowledged")
    def afterBlocks = app.doseSafetyBlocks([doseMl: 237G, runSeconds: 65, sampleKey: "${app.clockMs}", pH: 7.4G, fcVal: 5.7G, fcTarget: 6.0G])
    expect(!afterBlocks.any { it.toLowerCase().contains("unacknowledged") || it.toLowerCase().contains("stop is still") },
           "after acknowledgement the fault gate is gone; got ${afterBlocks}")
}

check("run: an OFF contradicted by an equal-timestamp ON cannot stop the pump") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    app.startDose(sampleDose(app), "test")
    app.fire("verifyStartConfirmation")          // switch-only confirmation
    expect(app.state.activeDose?.startConfirmed == true, "sanity: confirmed")

    def t = app.clockMs
    pump.setReportedAt("on", t)                 // current state ON at t
    def before = new LinkedHashMap(app.scheduled)
    app.pumpSwitchHandler([name: "switch", value: "off", date: new Date(t)])
    expect(app.state.activeDose != null, "an equal-timestamp OFF must not stop the run")
    expect(app.scheduled == before, "and must not change any job")
}

// Boundary ordering and optional side-effect failures found in the October 4 review.
[-3000L, -1L, 0L, 1L].each { offset ->
    check("unconfirmed OFF at planned end ${offset}ms retains fault and stops a later ON") {
        def app = newApp()
        def pump = bindClock(app, new FakeSwitch("off"))
        primeForStart(app, pump)
        pump.mode = "ignoresOn"
        def dose = sampleDose(app)
        dose.runSeconds = 15
        app.startDose(dose, "boundary test")
        def reserved = app.state.doseMlToday
        app.clockMs = (app.state.activeDose.stopAt as Long) + offset
        pump.setReportedAt("off", app.clockMs)
        app.pumpSwitchHandler([name: "switch", value: "off", date: new Date(app.clockMs)])

        expect(app.state.startFault?.kind == "start-unconfirmed", "every unconfirmed termination must latch")
        expect(app.state.activeDose == null, "a fresh OFF still finishes cleanup")
        expect(app.state.lastDose == null && app.state.tankRemainingMl == "56544", "no success accounting")
        expect(app.state.doseMlToday == reserved, "the failed attempt stays reserved")
        expect(app.noticesMatching("start NOT confirmed").size() == 1, "one fault alert")
        expect(app.noticesMatching("stopped after scheduled").isEmpty(), "no completed-dose claim")

        int offBefore = pump.offCalls
        app.clockMs += 1000L
        pump.setReportedAt("on", app.clockMs)
        app.pumpSwitchHandler([name: "switch", value: "on", date: new Date(app.clockMs)])
        expect(pump.offCalls == offBefore + 1, "a late ON must request OFF immediately")
        expect(app.state.startFault != null, "the fault remains until acknowledgement")
    }
}

["event", "retry", "cutoff", "synchronous"].each { completion ->
    check("emergency stop retains its fault and uncertain outcome through ${completion} confirmation") {
        def app = newApp()
        def pump = bindClock(app, new FakeSwitch("off"))
        primeForStart(app, pump)
        pump.powerCapable = true
        app.startDose(sampleDose(app), "cutoff test")
        pump.setPower(7G)
        app.fire("verifyStartConfirmation")
        def remaining = app.state.tankRemainingMl
        def reserved = app.state.doseMlToday
        app.clockMs += 1200000L
        if (completion != "synchronous") pump.mode = "ignoresOff"
        app.fire("emergencyPumpOff")
        expect(app.state.startFault?.kind == "emergency-stop", "fault must be recorded before async OFF")

        if (completion != "synchronous") {
            expect(app.state.activeDose != null, "unconfirmed OFF cannot clear the active dose")
            app.clockMs += 1000L
            pump.setReportedAt("off", app.clockMs)
            if (completion == "event") {
                app.pumpSwitchHandler([name: "switch", value: "off", date: new Date(app.clockMs)])
            } else if (completion == "retry") {
                app.verifyPumpOff()
            } else {
                app.fire("emergencyPumpOff")
            }
        }
        expect(app.state.activeDose == null, "fresh OFF completes cleanup")
        expect(app.state.startFault?.kind == "emergency-stop", "all completion paths preserve the fault")
        expect(app.noticesMatching("delivery is UNCERTAIN").size() == 1, "one truthful final outcome")
        expect(app.noticesMatching("stopped after scheduled").isEmpty(), "emergency is not ordinary completion")
        expect(app.scheduled["verifyRunPower"] == null && app.scheduled["emergencyPumpOff"] == null, "stop jobs cleaned up")
        expect(app.state.doseMlToday == reserved && app.state.tankRemainingMl == remaining, "no refund or double accounting")
    }
}

check("a throwing dashboard cannot suppress running power protection or duplicate dose accounting") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.powerCapable = true
    def tile = new FakeSwitch("off")
    tile.throwOnEvent = "lastDoseMl"
    app.__tileDevice = tile
    app.createTile = true
    app.startDose(sampleDose(app), "telemetry failure")
    pump.setPower(7G)
    app.fire("verifyStartConfirmation")
    expect(app.state.activeDose?.startConfirmed == true, "start remains confirmed")
    expect(app.scheduled["verifyRunPower"] != null, "power monitoring is established despite telemetry failure")
    expect(app.scheduled["stopDose"] != null && app.scheduled["emergencyPumpOff"] != null, "original protection remains")
    expect(app.noticesMatching("pump started").size() == 1, "the operator still receives start confirmation")
    expect(app.logLines.any { it.contains("dashboard telemetry failed") }, "the optional failure is reported")
    def remaining = app.state.tankRemainingMl
    app.verifyStartConfirmation()
    expect(app.state.tankRemainingMl == remaining, "confirmation cannot book twice")

    // With reports lost, the still-scheduled watch must actually execute the stop path.
    pump.powerMode = "powerMissing"
    app.clockMs += 31000L
    app.fire("verifyRunPower")
    expect(pump.offCalls > 0 && app.state.startFault?.kind == "power-loss", "missing power still causes OFF and a fault")
    expect(app.state.tankRemainingMl == remaining, "unknown delivery is not automatically refunded")
}

check("unchanged power reports sustain a healthy run, while silence still fails closed") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    pump.powerCapable = true
    app.initialize()
    def dose = sampleDose(app)
    dose.runSeconds = 180
    app.startDose(dose, "constant power")
    app.deliverDeviceReport(pump, "power", 7G)
    expect(app.state.activeDose?.startConfirmed == true, "first power report confirms")

    6.times {
        app.clockMs += 10000L
        app.deliverDeviceReport(pump, "power", 7G)
        app.fire("verifyRunPower")
        expect(app.state.startFault == null && pump.offCalls == 0, "unchanged received power is still live evidence")
        expect(pump.currentState("power").date.time == app.clockMs, "repeat report is retained")
    }
    app.clockMs += 31000L
    app.fire("verifyRunPower")
    expect(app.state.startFault?.kind == "power-loss" && pump.offCalls > 0, "actual silence still causes a stop")
}

check("a repeated OFF report completes failed-start recovery even when its value never changed") {
    def app = newApp()
    def pump = bindClock(app, new FakeSwitch("off"))
    primeForStart(app, pump)
    app.initialize()
    pump.mode = "ignoresAll"
    app.clockMs += 1000L
    app.startDose(sampleDose(app), "ignored ON")
    app.clockMs += 21000L
    app.fire("verifyStartConfirmation")
    expect(app.state.activeDose != null, "cached pre-start OFF cannot finish recovery")
    app.clockMs += 1000L
    app.deliverDeviceReport(pump, "switch", "off")
    expect(app.state.activeDose == null, "a new equal OFF report completes recovery")
    expect(app.state.startFault != null, "the fault still requires acknowledgement")
    expect(app.scheduled["emergencyPumpOff"] == null, "fresh OFF permits cleanup")
}

// --------------------------------------------------------------------------- summary

println ""
println "  harness : ${HubitatStub.simpleName} + ${FakeSwitch.simpleName} (no hub, no pump)"
println "  app     : ${appFile.name}"
println "  result  : ${checks - failures}/${checks} checks passed"
if (failures) {
    println ""
    failureDetail.each { println "  - ${it}" }
}
System.exit(failures ? 1 : 0)
