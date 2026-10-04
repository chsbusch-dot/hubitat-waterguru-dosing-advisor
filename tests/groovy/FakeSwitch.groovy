/**
 * A fake pump switch that can be told to fail in the specific ways that matter.
 *
 * The interesting cases are not "off works". They are:
 *   - the OFF command THROWS (hub/radio error, switch offline),
 *   - the OFF command is accepted and the relay stays ON anyway (the stuck-relay case),
 *   - the device reports nothing at all, which must NOT be read as a confirmed stop.
 *
 * Commands are recorded so a test can assert how many OFF attempts were actually made.
 *
 * Start confirmation (WOR-714) adds the failures on the way IN:
 *   - the ON is accepted and the relay never closes (ignoresOn),
 *   - the hub accepts commands that reach the plug minutes later, in order (deferred): the
 *     Oct 3 case, where the 19:45:24 ON and 19:47:33 OFF acted at 19:48:25 and 19:48:31,
 *   - the relay closes and the pump draws nothing (metered, drawsPower = false).
 */
class FakeSwitch {
    String label = "OL BYP P1 Chlorine Pump"
    private String value
    private final Map<String, Object> attrs = [:]

    /** healthy | offThrows | ignoresOff | silent | readThrows | onThrows | onThrowsThenStuck | ignoresOn | deferred */
    String mode = "healthy"
    int offCalls = 0
    int onCalls = 0
    int refreshCalls = 0

    /** Exposes power/accessory/refresh like the ZEN05 on the jtp10181 driver. Off by default, so
     *  the older scenarios keep modelling a plain switch. */
    boolean metered = false
    /** false: the relay closes but the pump draws nothing (unplugged, dead motor, open wire). */
    boolean drawsPower = true
    BigDecimal runningWatts = 6.9G
    /** Stamps state changes; tests point it at the stub's clock so freshness checks are real. */
    Closure<Long> clock = { 0L }
    private final Map<String, Long> stamps = [:]
    /** deferred mode: commands the hub accepted that the plug has not acted on yet, in order. */
    List<String> pending = []

    FakeSwitch(String initial = "off") { this.value = initial }

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
        return attrs[attribute]
    }

    def on() {
        onCalls++
        if (mode == "onThrows" || mode == "onThrowsThenStuck") {
            // onThrows: the command failed and the relay never closed.
            // onThrowsThenStuck: the contactor closed and THEN the command path errored -- the
            // case that must not be treated as "nothing happened".
            if (mode == "onThrowsThenStuck") value = "on"
            throw new RuntimeException("simulated ON command failure")
        }
        if (mode == "ignoresOn") return            // accepted, relay never closes, no report
        if (mode == "deferred") { pending << "on"; return }
        apply("on")
    }

    def off() {
        offCalls++
        switch (mode) {
            case "offThrows":
                // The command failed; the physical state is unchanged.
                throw new RuntimeException("radio timeout sending off()")
            case "ignoresOff":
            case "silent":
            case "onThrowsThenStuck":   // energised, and now ignoring OFF
                // Accepted by the hub, ignored by the relay: state stays ON.
                return
            case "deferred":
                pending << "off"
                return
            default:
                apply("off")
        }
    }

    /** The relay acts: switch state, and on a metered plug the power it draws, both stamped. */
    private void apply(String v) {
        value = v
        stamps["switch"] = clock.call()
        if (metered) {
            BigDecimal w = (v == "on" && drawsPower) ? runningWatts : 0G
            setAttr("power", w)
            setAttr("accessory", w >= 3G ? "on" : "off")
        }
    }

    /** Let the plug act on the oldest queued command, as the Z-Wave queue finally did on Oct 3. */
    String deliverNext() {
        if (pending.isEmpty()) return null
        String cmd = pending.remove(0)
        apply(cmd)
        return cmd
    }

    void setAttr(String name, Object v, Long at = null) {
        attrs[name] = v
        stamps[name] = at != null ? at : clock.call()
    }

    boolean hasAttribute(String a) { a == "switch" || (metered && (a == "power" || a == "accessory")) }
    boolean hasCommand(String c) { c == "on" || c == "off" || (metered && c == "refresh") }
    def refresh() { refreshCalls++; null }

    /** Hubitat's State: value plus the time it was set. Honours the read failure modes. */
    def currentState(String a) {
        def v = currentValue(a)
        return v == null ? null : [name: a, value: v.toString(), date: new Date((stamps[a] ?: 0L) as Long)]
    }

    /** Simulate the relay physically dropping out later. */
    void setReported(String v) { this.value = v }

    // Enough of a device for tile assertions: records what the app pushes to it.
    List<Map> sentEvents = []
    def sendEvent(Map m) { sentEvents << new LinkedHashMap(m); null }

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
