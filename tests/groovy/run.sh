#!/usr/bin/env bash
#
# Run the Groovy execution tests for the child app and the Pump Power Profiler.
#
# The app's own methods are executed against a stubbed Hubitat runtime, so this observes
# behaviour (including a pump switch that throws or ignores OFF) without a hub and without
# touching the physical pump. Uses the same container image as the compile check, so there is
# no local Groovy to install.
#
# Exits non-zero if any check fails.

set -euo pipefail
cd "$(dirname "$0")/../.."

# The groovy:4 image accepts things the hub's compiler rejects; check the known one first.
python3 tests/check_hubitat_fields.py

if ! command -v docker >/dev/null 2>&1; then
    echo "docker is required to run these tests" >&2
    exit 2
fi

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
'
