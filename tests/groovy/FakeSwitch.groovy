/**
 * A fake pump switch that can be told to fail in the specific ways that matter.
 *
 * The interesting cases are not "off works". They are:
 *   - the OFF command THROWS (hub/radio error, switch offline),
 *   - the OFF command is accepted and the relay stays ON anyway (the stuck-relay case),
 *   - the device reports nothing at all, which must NOT be read as a confirmed stop,
 *   - the ON command returns but the pump never draws power / never reports a fresh ON,
 *   - power is present at the command but disappears mid-run.
 *
 * Commands are recorded so a test can assert how many OFF attempts were actually made.
 *
 * TIMESTAMPS: every state change is stamped with `nowMs()`. By default that is the wall
 * clock, which keeps the pre-timestamp tests working (their readings are always "fresh").
 * A start-confirmation test binds `clock = { app.clockMs }` so a reading can be made
 * deliberately stale relative to the app's own clock.
 */
class FakeSwitch {
    String label = "OL BYP P1 Chlorine Pump"
    /** Hubitat apps read displayName for user-facing text. */
    String getDisplayName() { label }
    private String value
    private final Map<String, Object> attrs = [:]

    /**
     * healthy | offThrows | ignoresOff | ignoresOn | ignoresAll | silent | readThrows |
     * onThrows | onThrowsThenStuck
     */
    String mode = "healthy"

    /** True when this device reports `power` (capability.powerMeter). */
    boolean powerCapable = false
    /** Set true to make hasCommand("refresh") false. */
    boolean refreshSupported = true
    /** Set true to make refresh() throw, as a driver read error would. */
    boolean refreshThrows = false

    /** Injected power failure modes: none | powerReadThrows | powerMissing. */
    String powerMode = "none"

    int offCalls = 0
    int onCalls = 0
    int refreshCalls = 0

    /** Millisecond source for state timestamps. Bound to a fake app clock when it matters. */
    Closure clock

    /**
     * The clock new FakeSwitch instances capture unless they are explicitly bound. The test
     * harness sets this to the app's fake clock before each instance is built, so every reading
     * is dated on the SAME clock as the app and no test depends on wall-clock future timestamps.
     */
    static Closure defaultClock

    private Long switchDate
    private BigDecimal powerValue
    private Long powerDate

    FakeSwitch(String initial = "off") {
        this.value = initial
        this.clock = FakeSwitch.defaultClock
        // Stamp the initial state ONCE. A read must not make an unchanged value look fresh.
        this.switchDate = nowMs()
    }

    private Long nowMs() { clock != null ? (clock.call() as Long) : System.currentTimeMillis() }

    /** A Date at `at`. The state always carries a real stamp (set in the constructor or a write). */
    private Date dateAt(Long at) { new Date(at != null ? at : nowMs()) }

    def currentValue(String attribute) {
        if (attribute == "switch") {
            if (mode == "readThrows") {
                // An injected runtime exception from the read path itself. Not an observed hub or
                // device failure -- it exists to prove the safety paths cannot be aborted by one.
                throw new RuntimeException("simulated currentValue() failure reading switch")
            }
            if (mode == "silent") return null      // no reading at all
            return value
        }
        if (attribute == "power") {
            if (powerMode == "powerReadThrows") {
                throw new RuntimeException("simulated currentValue() failure reading power")
            }
            if (powerMode == "powerMissing") return null
            return powerValue
        }
        return attrs[attribute]
    }

    /**
     * Hubitat exposes a per-attribute DeviceState with `.value` and `.date`. The app uses the
     * date to reject a cached reading that predates a new ON/OFF request.
     */
    def currentState(String attribute, boolean skipCache = false) {
        if (attribute == "switch") {
            if (mode == "readThrows") {
                throw new RuntimeException("simulated currentState() failure reading switch")
            }
            if (mode == "silent") return null
            return [name: "switch", value: value, date: dateAt(switchDate)]
        }
        if (attribute == "power") {
            if (powerMode == "powerReadThrows") {
                throw new RuntimeException("simulated currentState() failure reading power")
            }
            if (powerMode == "powerMissing") return null
            if (powerValue == null) return null
            return [name: "power", value: powerValue, date: dateAt(powerDate)]
        }
        def v = attrs[attribute]
        return v == null ? null : [name: attribute, value: v, date: null]
    }

    def hasAttribute(String attribute) { attribute == "power" && powerCapable }
    def hasCapability(String capability) { capability == "PowerMeter" && powerCapable }
    def hasCommand(String command) { command == "refresh" && refreshSupported }

    def refresh() {
        refreshCalls++
        if (refreshThrows) throw new RuntimeException("simulated refresh() failure")
        return null
    }

    def on() {
        onCalls++
        if (mode == "ignoresOn" || mode == "ignoresAll") {
            // The command is accepted but the relay never reports on (dead/offline switch).
            return null
        }
        if (mode == "onThrows" || mode == "onThrowsThenStuck") {
            // onThrows: the command failed and the relay never closed.
            // onThrowsThenStuck: the contactor closed and THEN the command path errored -- the
            // case that must not be treated as "nothing happened".
            if (mode == "onThrowsThenStuck") {
                value = "on"
                switchDate = nowMs()
            }
            throw new RuntimeException("simulated ON command failure")
        }
        value = "on"
        switchDate = nowMs()
        return null
    }

    def off() {
        offCalls++
        switch (mode) {
            case "offThrows":
                // The command failed; the physical state is unchanged.
                throw new RuntimeException("radio timeout sending off()")
            case "ignoresOff":
            case "ignoresAll":
            case "silent":
            case "onThrowsThenStuck":   // energised, and now ignoring OFF
                // Accepted by the hub, ignored by the relay: state stays ON (or unchanged).
                return
            default:
                value = "off"
                switchDate = nowMs()
        }
        return null
    }

    /** Simulate the relay physically dropping out later. */
    void setReported(String v) {
        this.value = v
        this.switchDate = nowMs()
    }

    void setReportedAt(String v, Long at) {
        this.value = v
        this.switchDate = at
    }

    /** A fresh power report at the current clock. */
    void setPower(BigDecimal watts) {
        this.powerValue = watts
        this.powerDate = nowMs()
    }

    /** A power report explicitly stamped, for stale-reading tests. */
    void setPowerAt(BigDecimal watts, Long at) {
        this.powerValue = watts
        this.powerDate = at
    }

    void setSwitchAt(String v, Long at) {
        this.value = v
        this.switchDate = at
    }

    // Enough of a device for tile assertions: records what the app pushes to it.
    List<Map> sentEvents = []
    String throwOnEvent
    def sendEvent(Map m) {
        if (m.name == throwOnEvent) throw new RuntimeException("simulated tile event failure: ${m.name}")
        sentEvents << new LinkedHashMap(m)
        null
    }

    Object lastSent(String attribute) {
        def hit = sentEvents.reverse().find { it?.name?.toString() == attribute }
        return hit?.value
    }

    List<Map> sentEventsFor(String attribute) {
        return sentEvents.findAll { it?.name?.toString() == attribute }
    }
}

/**
 * Stands in for a PushOver-style notification device.
 *
 * The app defines sendNotice() itself, so a recorder on the stub would be shadowed and would
 * silently record nothing. Pointing notifyDevices at this instead means the app's real fan-out
 * path runs, and the sink can be any list the test wants to read -- typically the stub's own.
 */
class FakeNotifier {
    String displayName = "PushOver (fake)"
    List<String> sink = []

    def deviceNotification(String msg) {
        sink << msg.toString()
        return null
    }

    List<String> matching(String needle) {
        return sink.findAll { it != null && it.toLowerCase().contains(needle.toLowerCase()) }
    }
}
