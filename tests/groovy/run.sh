#!/usr/bin/env bash
#
# Run the Groovy execution tests for the child app and the Pump Power Profiler, then compile every
# app and the driver with Groovy 2.4, the hub's compiler line (tests/groovy/hub_compile.sh).
#
# The app's own methods are executed against a stubbed Hubitat runtime, so this observes
# behaviour (including a pump switch that throws or ignores OFF) without a hub and without
# touching the physical pump. Everything runs in containers, so there is no local Groovy to install.
#
# Exits non-zero if any check fails. HUB_COMPILE=skip leaves the Groovy 2.4 compile to the CI job
# hub-compile, for a machine that cannot pull groovy:2.4-jdk8.

set -euo pipefail
cd "$(dirname "$0")/../.."

# The groovy:4 image accepts things the hub's compiler rejects; check the known one first.
python3 tests/check_hubitat_fields.py

if ! command -v docker >/dev/null 2>&1; then
    echo "docker is required to run these tests" >&2
    exit 2
fi

status=0
docker run --rm -v "$PWD:/w:ro" -w /w groovy:4-alpine sh -c '
    set -e
    mkdir -p /tmp/hclasses
    groovyc -d /tmp/hclasses \
        tests/groovy/HubitatStub.groovy \
        tests/groovy/FakeSwitch.groovy
    status=0
    groovy -cp /tmp/hclasses tests/groovy/run_tests.groovy || status=1
    groovy -cp /tmp/hclasses tests/groovy/profiler_tests.groovy || status=1
    exit $status
' || status=$?

# groovy:4 accepts code the hub refuses, so the same sources also go through Groovy 2.4.
if [ "${HUB_COMPILE:-}" = skip ]; then
    echo "hub compile: SKIPPED (HUB_COMPILE=skip); the CI job hub-compile runs it"
else
    hub=0
    tests/groovy/hub_compile.sh || hub=$?
    [ "$status" -ne 0 ] || status=$hub
fi
exit "$status"
