/**
 * A fake pump switch that can be told to fail in the specific ways that matter.
 *
 * The interesting cases are not "off works". They are:
 *   - the OFF command THROWS (hub/radio error, switch offline),
 *   - the OFF command is accepted and the relay stays ON anyway (the stuck-relay case),
 *   - the device reports nothing at all, which must NOT be read as a confirmed stop.
 *
 * Commands are recorded so a test can assert how many OFF attempts were actually made.
 */
class FakeSwitch {
    String label = "OL BYP P1 Chlorine Pump"
    private String value
    private final Map<String, Object> attrs = [:]

    /** healthy | offThrows | ignoresOff | silent | readThrows */
    String mode = "healthy"
    int offCalls = 0
    int onCalls = 0

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
        value = "on"
    }

    def off() {
        offCalls++
        switch (mode) {
            case "offThrows":
                // The command failed; the physical state is unchanged.
                throw new RuntimeException("radio timeout sending off()")
            case "ignoresOff":
            case "silent":
                // Accepted by the hub, ignored by the relay: state stays ON.
                return
            default:
                value = "off"
        }
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
