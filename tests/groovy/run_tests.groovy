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
