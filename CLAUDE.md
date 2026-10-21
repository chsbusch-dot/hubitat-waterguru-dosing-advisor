# CLAUDE.md

Hubitat Elevation apps that turn WaterGuru pool readings into chemical dosing advice and,
in AUTO mode, run a liquid chlorine pump. The apps ship to other users through Hubitat
Package Manager (`packageManifest.json`).

Facts about one particular hub (device and app IDs, addresses, the operator's process) stay out
of this public file. The maintainer keeps them in a git-ignored `CLAUDE.local.md` beside it.

## Safety

**The code here doses real chlorine into a real pool: treat every pump-related change as safety
critical.** A change to how the pump starts, runs, stops or decides a dose comes with tests
against the stubbed hub (see Tests), and no test may touch a real hub or pump.

## Layout

| Path | What it is | Ships via HPM |
|---|---|---|
| `apps/WaterGuru-Dosing-Advisor.groovy` | Parent app (container) | yes |
| `apps/WaterGuru-Dosing-Advisor-Child.groovy` | Child app "WaterGuru Dosing Advisor Pool": dosing model, pump control, stop lifecycle | yes |
| `drivers/WaterGuru-Dosing-Tile.groovy` | Dashboard tile driver; attribute names are a contract with an external historian | yes |
| `apps/PumpPowerProfiler.groovy` | Pump Power Profiler: samples the pump plug's meters during each run, saves CSVs to File Manager. Read-only toward the device | yes (optional) |
| `tools/zwave_capture.py` | Passive Z-Wave log capture and `--analyze` of a raw log. Reads both zwaveLogsocket formats: per-frame JSON (to 2026-10-09) and the plaintext dump (hub 2.5.2.135 on) | no |
| `docs/handoff-2026-10-03.md` | Hub and Z-Wave context as of 2026-10-03 (ZEN05 pump plug, profiler, the missed 19:45 dose) | n/a |
| `tests/` | Attribute contract check, Groovy execution tests against a stubbed hub, Z-Wave capture parser tests | n/a |
| `waterguru-grafana/` | Optional InfluxDB collector (Python, Docker) and Grafana dashboard; reads the tile attributes. Own MIT license, own tests, CI workflow `waterguru-grafana.yml` | no |

## Tests

All three must pass before a PR; CI (`.github/workflows/attribute-contract.yml`) runs the same three.

```bash
python3 tests/test_attribute_contract.py   # tile attribute contract, no dependencies
python3 tests/test_zwave_capture.py        # tools/zwave_capture.py on sanitized fixtures, no dependencies
bash tests/groovy/run.sh                    # child app + Pump Power Profiler vs a stubbed hub (needs docker)
                                            # (first runs tests/check_hubitat_fields.py: the hub rejects an
                                            #  @Field initializer that names another @Field; groovy:4 does not)
                                            # (then tests/groovy/hub_compile.sh: apps + driver through Groovy 2.4, the
                                            #  hub's compiler; CI job hub-compile. HUB_COMPILE=skip leaves it to CI)
```

None touches a hub or a pump.

The collector in `waterguru-grafana/` has its own gate (CI workflow `waterguru-grafana.yml`):

```bash
cd waterguru-grafana && python3 -m venv .venv && .venv/bin/pip install -e ".[dev]"
.venv/bin/ruff check . && .venv/bin/mypy && .venv/bin/pytest   # Python 3.11 or later (CI runs 3.12)
```

The stub models what was measured on the live hub (2026-10-04): a report whose value did not change
reaches `filterEvents:false` subscribers as an event with a fresh date but never re-dates
`currentState()`, and `FakeSwitch.async` makes a command take effect only when the test delivers the
plug's report. Write new pump tests against that (`hubPlug` in `run_tests.groovy`). Move the clock with
`advance()`/`runUntil()`: since 2.5.0 `fire()` throws when another job is already overdue (a bare `clockMs += N`
used to skip due safety jobs silently), and `restartAfter(ms)` is the one way to model a hub restart. The tile fake
also keeps `hubEvents`, the events the hub would store (a changed value, or `isStateChange: true`),
and `collectorDoses` replays the Grafana collector's pairing on them. The profiler's tests
(`profiler_tests.groovy`) use `FakeMeter` and the stub's File Manager (`fileStore`, `hubFilesMode`;
`listEmpty` and `downloadEmpty` model a listing that misses a file and a 0-byte read, not seen live).
The stub keeps an app's `definition(...)` map as `definitionArgs`.
The WaterGuru source device is `FakeWaterGuru`, and `deliverSourceBatch` replays a sample in the
integration's write order, LastMeasurement before freeChlorine, delivering each event as it is
written (2026-10-06: 2.4.2's handler stored the previous sample's FC for 7 of 8 live samples). Write
source tests against that (`liveWaterGuru`, `wgSample` in `run_tests.groovy`).

Optional, not in CI (a few minutes): `tests/mutation/run.sh` reruns the 2.4.0 review's mutation set
plus one mutant per 2.4.1 fix (F*), per 2.4.2 fix (R*), per 2.4.3 fix (W*), per 2.4.4 fix (X*) and per
profiler 1.1.1 fix (XP*, declared with `mutp`), per profiler 1.2.0 sampling behaviour (E*) and wear rule
(WM*), and PI1 (the dosing app's own refresh); each mutant runs the suite for its file, and the base
runs both. The unmutated `base` must pass and every `[killed]` mutant must fail;
anchors are exact strings, so re-anchor a mutant when the code under it changes.

## No em or en dashes in what users read

Text a user reads carries no em dash (U+2014) or en dash (U+2013): the app pages, notices and
advice text, the tile HTML, `README.md`, `docs/`, and the collector's docs and dashboard in
`waterguru-grafana/`. Use a comma, period, colon or parentheses instead. Code, code comments,
commit messages and PR bodies are exempt. The Groovy suite checks the notices and page text
("2.5.0 change 6"); for the docs run
`rg '[\x{2014}\x{2013}]' README.md docs waterguru-grafana/README.md waterguru-grafana/docs waterguru-grafana/grafana`,
which must print nothing (the collector's Python source is code and exempt).

## Branches and PRs

- Branch off `main` as `wgda/<type>/<slug>` (for example `wgda/fix/pump-start-confirmation`).
- One feature per branch; never commit onto another feature's open-PR branch.
- A release bumps the versions in `packageManifest.json`, the child's `appVersion()` and its
  header changelog (see commit `3462639`, release 2.3.1).
