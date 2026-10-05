#!/usr/bin/env bash
#
# Compile the apps and the driver with Groovy 2.4, the line the hub runs.
#
# The execution tests run on groovy:4, which accepts code the hub refuses. On 2026-10-05 the hub would
# not save Pump Power Profiler 1.1.0 (d1519cc): a static @Field initializer named another @Field.
# groovy:4 compiled it and every test passed; groovyc 2.4 refuses it with the hub's own message. So
# every file under apps/ and drivers/ is compiled here on its own, as a plain script. This answers
# "would the hub's compiler take it"; behaviour is still run.sh's job.
#
# Positive control first: each fixture in tests/groovy/hub_refuses/ must FAIL to compile, with the
# message on its "// expect:" line. A compiler that accepts everything (another image, an empty mount)
# would otherwise look exactly like a clean pass.
#
#   tests/groovy/hub_compile.sh           # the working tree (tests/groovy/run.sh runs this too)
#   tests/groovy/hub_compile.sh d1519cc   # apps/ and drivers/ as of a commit
#
# Exit: 0 clean, 1 a file does not compile, 2 no docker or no image, 3 the control or harness failed.
#
# groovy:2.4-jdk8 is Groovy 2.4.16 on OpenJDK 8, the newest official 2.4 image; Hubitat documents
# "Groovy 2.4". Not modelled: the hub's sandbox (which classes may be imported), which only the hub
# checks. Nothing here touches a hub.

set -euo pipefail
cd "$(dirname "$0")/../.."

IMAGE=groovy:2.4-jdk8
rev="${1:-}"

if ! command -v docker >/dev/null 2>&1; then
    echo "docker is required for the Groovy 2.4 compile check" >&2
    exit 2
fi
if ! docker image inspect "$IMAGE" >/dev/null 2>&1 && ! docker pull -q "$IMAGE" >/dev/null; then
    echo "$IMAGE is not available here and could not be pulled; CI job hub-compile runs this check" >&2
    exit 2
fi

# The working tree is mounted at /w. For a commit, its apps/ and drivers/ arrive as a tar on stdin
# and unpack to /tmp/rev; the control fixtures always come from the working tree.
check='
    set -u
    src=/w
    if [ -n "$REV" ]; then
        mkdir -p /tmp/rev && tar -xf - -C /tmp/rev || exit 2
        src=/tmp/rev
    fi
    version=$(groovyc --version 2>&1 | head -n 1)
    case "$version" in
        *" 2.4."*) echo "compiler : $version" ;;
        *) echo "HARNESS  not Groovy 2.4: $version"; exit 3 ;;
    esac

    n=0
    for f in /w/tests/groovy/hub_refuses/*.groovy; do
        [ -f "$f" ] || { echo "HARNESS  no fixtures in tests/groovy/hub_refuses"; exit 3; }
        want=$(sed -n "s|^// expect: ||p" "$f" | head -n 1)
        [ -n "$want" ] || { echo "HARNESS  ${f#/w/} has no // expect: line"; exit 3; }
        n=$((n + 1)); mkdir -p "/tmp/control/$n"
        if out=$(groovyc -d "/tmp/control/$n" "$f" 2>&1); then
            echo "CONTROL  ${f#/w/} compiled: this compiler is not the hub one"; exit 3
        fi
        case "$out" in
            *"$want"*) echo "control  ${f#/w/}: refused, as on the hub" ;;
            *) echo "CONTROL  ${f#/w/} failed for another reason:"; echo "$out"; exit 3 ;;
        esac
    done

    cd "$src"
    status=0; count=0; failed=0
    for f in apps/*.groovy drivers/*.groovy; do
        [ -f "$f" ] || continue
        count=$((count + 1)); mkdir -p "/tmp/out/$count"
        if out=$(groovyc -d "/tmp/out/$count" "$f" 2>&1); then
            echo "PASS  $f"
        else
            echo "FAIL  $f"; echo "$out" | sed "s/^/      /"
            failed=$((failed + 1)); status=1
        fi
    done
    [ "$count" -gt 0 ] || { echo "HARNESS  no apps/*.groovy or drivers/*.groovy under $src"; exit 3; }
    echo "hub compile: $count files, $failed refused by Groovy 2.4"
    exit $status
'

rc=0
if [ -n "$rev" ]; then
    git rev-parse --verify --quiet "$rev^{commit}" >/dev/null || { echo "not a commit: $rev" >&2; exit 2; }
    echo "source   : apps/ and drivers/ at $rev"
    git archive "$rev" apps drivers \
        | docker run --rm -i -e REV=1 -v "$PWD:/w:ro" "$IMAGE" sh -c "$check" || rc=$?
else
    echo "source   : working tree"
    docker run --rm -e REV= -v "$PWD:/w:ro" "$IMAGE" sh -c "$check" || rc=$?
fi
case "$rc" in
    0|1|2|3) exit "$rc" ;;
    *) echo "docker run failed (exit $rc)" >&2; exit 2 ;;
esac
