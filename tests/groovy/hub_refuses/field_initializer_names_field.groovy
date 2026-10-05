// expect: Apparent variable 'LEGACY_SUMMARY_KEYS' was found in a static scope
//
// The hub refused to save Pump Power Profiler 1.1.0 (d1519cc, line 29) with this message on
// 2026-10-05, after groovy:4 had compiled it and every test had passed. Positive control for
// tests/groovy/hub_compile.sh: Groovy 2.4 must keep refusing it.
import groovy.transform.Field

@Field static final List LEGACY_SUMMARY_KEYS = ["start", "reason"]
@Field static final List SUMMARY_KEYS = LEGACY_SUMMARY_KEYS + ["powerDelaySec"]
