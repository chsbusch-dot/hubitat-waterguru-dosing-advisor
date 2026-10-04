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
    expect(!app.noticesMatching("still reports").isEmpty(),
           "must say the switch still reports ON")
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
}

def sampleDose = { app ->
    [doseMl: 237G, runSeconds: 65, sampleKey: "${app.clockMs}", pH: 7.4G, fcVal: 5.7G, fcTarget: 6.0G]
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

// ------------------------------------------------------ start confirmation (WOR-714, 2.4.0)

/** A ZEN05-like plug: metered, stamped by the app's clock. */
def meteredPump = { app, String mode = "healthy" ->
    def pump = new FakeSwitch("off")
    pump.mode = mode
    pump.metered = true
    pump.clock = { app.clockMs }
    return pump
}

def advance = { app, long ms -> app.clockMs += ms }

/** Nothing was booked: tank, daily total, duplicate-sample lock and last-dose record untouched. */
def expectNothingBooked = { app, String why ->
    expect(app.state.tankRemainingMl == "56544", "${why}: tank must not be debited, was ${app.state.tankRemainingMl}")
    expect(app.state.lastDosedSample == null, "${why}: the sample must not be marked dosed")
    expect(app.state.lastDose == null, "${why}: no last-dose record")
    expect(app.state.doseMlToday == "0", "${why}: daily total must stay 0, was ${app.state.doseMlToday}")
    expect(app.noticesMatching("Chlorine pump started").isEmpty(), "${why}: must not announce a start")
    expect(app.noticesMatching("stopped after scheduled").isEmpty(), "${why}: must not claim a completed dose")
}

check("WOR-714: an ON the plug ignores is retried once, then abandoned with nothing booked") {
    def app = newApp()
    def pump = meteredPump(app, "ignoresOn")
    primeForStart(app, pump)

    app.startDose(sampleDose(app), "test")

    expect(pump.onCalls == 1, "one ON sent")
    expect(app.state.activeDose?.phase == "starting", "the dose waits for confirmation")
    expect(app.scheduled["verifyPumpStart"] != null, "start verification scheduled")
    expect(app.scheduled["stopDose"] == null, "no stop is scheduled for a start that has not happened")
    expect(app.scheduled["emergencyPumpOff"] != null, "the independent cutoff is armed from the start")
    expectNothingBooked(app, "before confirmation")

    advance(app, 10_000L); app.fire("verifyPumpStart")
    expect(pump.onCalls == 2, "one retry ON, got ${pump.onCalls}")
    expect(app.scheduled["verifyPumpStart"] != null, "verification re-scheduled after the retry")

    advance(app, 10_000L); app.fire("verifyPumpStart")
    expect(pump.onCalls == 2, "no third ON")
    expect(pump.offCalls >= 1, "an abandoned start must send OFF")
    expect(app.state.activeDose == null, "the abandoned dose is released")
    expectNothingBooked(app, "after abandoning")
    expect(app.noticesMatching("NOT confirmed").size() == 1, "exactly one abandon alert, got ${app.notices}")
    expect(app.state.lateStartGuardUntil != null, "the late-start guard is armed")
    expect(app.scheduled["lateStartSweep"] != null, "a precautionary OFF is scheduled")

    int offsBefore = pump.offCalls
    app.fire("lateStartSweep")
    expect(pump.offCalls == offsBefore + 1, "the sweep sends one more OFF")
}

check("WOR-714 regression: the Oct 3 sequence (ON and OFF acted on minutes late) books nothing and stops the late ON") {
    def app = newApp()
    def pump = meteredPump(app, "deferred")
    primeForStart(app, pump)

    app.startDose(sampleDose(app), "test")              // 19:45:24 ON, accepted, not acted on
    // 2.3.2 completed the dose on this reading: the switch still says off, because it never moved.
    app.pumpSwitchHandler([value: "off"])
    expect(app.state.activeDose?.phase == "starting", "an OFF report before confirmation must not end the dose")
    expect(app.noticesMatching("stopped after scheduled").isEmpty(), "and must not claim a completed dose")

    advance(app, 10_000L); app.fire("verifyPumpStart")    // retry ON, also queued
    advance(app, 10_000L); app.fire("verifyPumpStart")    // abandon: OFF queued behind both ONs
    expect(pump.pending == ["on", "on", "off"], "commands queue in order, got ${pump.pending}")
    expect(app.state.activeDose == null, "abandoned")
    expectNothingBooked(app, "after abandoning")

    advance(app, 160_000L)                                 // 19:48:25: the queue drains
    pump.deliverNext()
    app.pumpSwitchHandler([value: "on"])
    expect(!app.noticesMatching("delayed ON").isEmpty(), "the late ON is called out")
    expect(app.state.lateOnStopping == true, "a stop for the late ON is in progress")
    expect(app.scheduled["verifyPumpOff"] != null, "OFF is verified")
    expect(app.scheduled["emergencyPumpOff"] != null, "with the cutoff armed")

    pump.deliverNext(); pump.deliverNext()                 // the retry ON, then the OFF
    app.pumpSwitchHandler([value: "off"])
    expect(app.state.lateOnStopping == null, "the late ON is resolved")
    expect(app.noticesMatching("confirmed OFF after the delayed ON").size() == 1, "and announced once")
    expect(app.scheduled["emergencyPumpOff"] == null, "and the cutoff released on a confirmed off")
    expectNothingBooked(app, "after the late ON")
}

check("WOR-714: a switch report that arrives late but inside the window confirms, times the dose from it, and books once") {
    def app = newApp()
    def pump = meteredPump(app, "deferred")
    primeForStart(app, pump)

    app.startDose(sampleDose(app), "test")
    advance(app, 6_000L)
    pump.deliverNext()                                     // relay closes, pump draws 6.9 W
    app.pumpSwitchHandler([value: "on"])

    def active = app.state.activeDose
    expect(active?.phase == "running" && active?.booked == true, "confirmed and booked, got ${active}")
    expect(app.scheduled["stopDose"] == 65, "stop scheduled for the full run from the confirmation")
    expect((active.stopAt as Long) == app.clockMs + 65_000L, "stopAt counts from the report, not the command")
    expect(app.scheduled["verifyPumpStart"] == null, "start verification cancelled")
    expect(app.state.tankRemainingMl == "56307", "tank debited once by 237 mL, was ${app.state.tankRemainingMl}")
    expect(app.noticesMatching("Chlorine pump started").size() == 1, "one start notice")

    app.pumpPowerHandler([name: "power", value: "6.9", unit: "W"])   // a later power event
    expect(app.state.tankRemainingMl == "56307", "no second debit")
    expect(app.noticesMatching("Chlorine pump started").size() == 1, "no second start notice")
}

check("WOR-714: a switch that reports on while the pump draws no power is abandoned with nothing booked") {
    def app = newApp()
    def pump = meteredPump(app)
    pump.drawsPower = false
    primeForStart(app, pump)

    app.startDose(sampleDose(app), "test")
    expect(app.state.activeDose?.phase == "switchOn", "switch confirmed, power pending")
    expect(app.scheduled["refreshPumpPower"] != null && app.scheduled["verifyPumpPower"] != null,
           "a refresh and a power check are scheduled, queue ${app.scheduled}")
    expectNothingBooked(app, "before power")

    advance(app, 5_000L); app.fire("refreshPumpPower")
    expect(pump.refreshCalls == 1, "the plug is asked for its meters once")
    advance(app, 10_000L); app.fire("verifyPumpPower")

    expect(pump.offCalls >= 1, "OFF sent")
    expect(app.state.activeDose == null, "released on the confirmed off")
    expect(app.scheduled["stopDose"] == null, "the scheduled stop is gone")
    expect(!app.noticesMatching("showed no power").isEmpty(), "the alert says why, got ${app.notices}")
    expectNothingBooked(app, "no power")
}

check("WOR-714: a stale power reading from an earlier run does not confirm a start") {
    def app = newApp()
    def pump = meteredPump(app)
    pump.drawsPower = false
    primeForStart(app, pump)

    app.startDose(sampleDose(app), "test")
    pump.setAttr("power", 6.6G, app.clockMs - 60_000L)     // left over from yesterday
    pump.setAttr("accessory", "on", app.clockMs - 60_000L)
    advance(app, 15_000L); app.fire("verifyPumpPower")

    expect(app.state.activeDose == null, "abandoned")
    expectNothingBooked(app, "stale power")
}

check("WOR-714: power reported before a lost switch report still confirms the start") {
    def app = newApp()
    def pump = meteredPump(app, "ignoresOn")
    primeForStart(app, pump)

    app.startDose(sampleDose(app), "test")
    advance(app, 3_000L)
    app.pumpPowerHandler([name: "power", value: "7.0", unit: "W"])

    expect(app.state.activeDose?.booked == true, "the pump is demonstrably running, so the dose is booked")
    expect(app.scheduled["stopDose"] == 65, "and timed")
    expect(app.noticesMatching("power 7.0 W").size() == 1, "the start notice names the evidence, got ${app.notices}")
}

check("control WOR-714: a healthy metered start is booked at once with all protection jobs") {
    def app = newApp()
    def pump = meteredPump(app)
    primeForStart(app, pump)

    app.startDose(sampleDose(app), "test")

    expect(app.state.activeDose?.booked == true, "booked")
    expect(app.scheduled["stopDose"] != null && app.scheduled["verifyPumpOff"] != null
           && app.scheduled["emergencyPumpOff"] != null, "all three protection jobs, queue ${app.scheduled}")
    expect(app.state.tankRemainingMl == "56307", "debited once")
    expect(app.state.lastDosedSample != null, "sample locked")
}

check("WOR-714: the late-start guard expires, and does not touch a later manual run") {
    def app = newApp()
    def pump = meteredPump(app, "ignoresOn")
    primeForStart(app, pump)

    app.startDose(sampleDose(app), "test")
    advance(app, 10_000L); app.fire("verifyPumpStart")
    advance(app, 10_000L); app.fire("verifyPumpStart")
    int offs = pump.offCalls
    int alerts = app.noticesMatching("delayed ON").size()

    advance(app, 31L * 60_000L)
    app.pumpSwitchHandler([value: "on"])                   // e.g. the 60-second rule, much later
    expect(pump.offCalls == offs, "an ON after the guard window must not be stopped")
    expect(app.noticesMatching("delayed ON").size() == alerts, "and not called a delayed ON")
    expect(app.scheduled["emergencyPumpOff"] != null, "the ordinary outside-start cutoff applies")
}

check("WOR-714: STOP pressed while a start is unconfirmed books nothing and arms the guard") {
    def app = newApp()
    def pump = meteredPump(app, "ignoresOn")
    primeForStart(app, pump)

    app.startDose(sampleDose(app), "test")
    app.appButtonHandler("btnStopPump")

    expect(app.state.activeDose == null, "released")
    expect(app.scheduled["verifyPumpStart"] == null, "start verification cancelled")
    expect(app.state.lateStartGuardUntil != null, "the ON may still be in flight, so the guard is armed")
    expectNothingBooked(app, "STOP during start")
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
