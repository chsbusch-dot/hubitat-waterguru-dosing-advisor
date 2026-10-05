# CLAUDE.md

Hubitat Elevation apps that turn WaterGuru pool readings into chemical dosing advice and,
in AUTO mode, run a liquid chlorine pump. The repo is **public** and ships to other users
through Hubitat Package Manager (`packageManifest.json`). The code here doses real chlorine
into a real pool: treat every pump-related change as safety critical.

## Layout

| Path | What it is | Ships via HPM |
|---|---|---|
| `apps/WaterGuru-Dosing-Advisor.groovy` | Parent app (container) | yes |
| `apps/WaterGuru-Dosing-Advisor-Child.groovy` | Child app "WaterGuru Dosing Advisor Pool": dosing model, pump control, stop lifecycle | yes |
| `drivers/WaterGuru-Dosing-Tile.groovy` | Dashboard tile driver; attribute names are a contract with an external historian | yes |
| `apps/PumpPowerProfiler.groovy` | Pump Power Profiler: samples the pump plug's meters during each run, saves CSVs to File Manager. Read-only toward the device | **no** (not in the manifest; add only if Chris asks) |
| `docs/handoff-2026-10-03.md` | Hub and Z-Wave context as of 2026-10-03 (ZEN05 pump plug, profiler, the missed 19:45 dose) | n/a |
| `tests/` | Attribute contract check and Groovy execution tests against a stubbed hub | n/a |

## Live hub

| Thing | ID |
|---|---|
| Hub | Hubitat C8, 192.168.1.147 |
| Parent app instance | 2198 (app code 1241) |
| Child app instance | 2200 (app code 1242). 2199 was an orphaned clone and was deleted |
| Tile device | 4658 (driver code 3046) |
| Pump Power Profiler | instance 2214 (app code 1250) |
| Pump plug | device 4674, Zooz ZEN05, Z-Wave node 259 |
| PushOver notifier | device 1955 |
| RM rule "POOL 60 seconds" | rule 2207, triggered by virtual button 4609 |
| Maker API | app 2142 |
| ZEN20 power strip | device 150, outlets 151 to 155 (router and AV gear) |

Hub access is the `hubitat-rules` MCP server. Gateway tools (`hub_read_*`, `hub_manage_*`)
list their sub-tools when called with no arguments.

To check the hub against git (read only): `hub_read_apps_code` → `hub_get_source` for app
code 1241/1242/1250 and driver 3046, or from the LAN
`curl -s http://192.168.1.147/app/ajax/code?id=1242` (`/driver/ajax/code?id=3046` for the
driver) and compare the `source` field byte for byte with the file at `origin/main`.

## Safety rules (hard)

- **Never switch the pump plug 4674 without Chris's explicit yes.** It doses real chlorine.
- **`hub_create_backup` before ANY write** to devices, drivers, apps or the radio.
- **Never toggle the ZEN20 outlets** (device 150, children 151 to 155): router and AV gear.
- **Tank inventory corrections and profiler `sampleSecs` changes only after Chris confirms.**
- **Never use `hub_set_native_app` to WRITE settings on the custom apps 2198/2200.** It
  clones the child and orphans the original (that is how 2199 happened). Button presses are
  fine. For a lifecycle refresh use `hub_update_app` with `triggerUpdated`.
- The write-gate key (`bestPracticeKey`) comes from
  `hub_get_tool_guide(section='best_practice_reference')`. Read it fresh; never hard-code it.
- Deploying code to the hub is Chris's call: open the PR, he approves anything that lands on
  the hub.

## Tests

Both must pass before a PR; CI (`.github/workflows/attribute-contract.yml`) runs the same two.

```bash
python3 tests/test_attribute_contract.py   # tile attribute contract, no dependencies
bash tests/groovy/run.sh                    # child app + Pump Power Profiler vs a stubbed hub (needs docker)
```

Neither touches a hub or a pump.

The stub models what was measured on the live hub (2026-10-04): a report whose value did not change
reaches `filterEvents:false` subscribers as an event with a fresh date but never re-dates
`currentState()`, and `FakeSwitch.async` makes a command take effect only when the test delivers the
plug's report. Write new pump tests against that (`hubPlug` in `run_tests.groovy`). The tile fake
also keeps `hubEvents`, the events the hub would store (a changed value, or `isStateChange: true`),
and `collectorDoses` replays the Grafana collector's pairing on them. The profiler's tests
(`profiler_tests.groovy`) use `FakeMeter` and the stub's File Manager (`fileStore`, `hubFilesMode`).

Optional, not in CI (a few minutes): `tests/mutation/run.sh` reruns the 2.4.0 review's mutation set
plus one mutant per 2.4.1 fix (F*) and per 2.4.2 fix (R*); it covers the child app only. The unmutated `base` must pass and every `[killed]` mutant must fail;
anchors are exact strings, so re-anchor a mutant when the code under it changes.

## Branches and PRs

- Branch off `main` as `wgda/<type>/<slug>` (for example `wgda/fix/pump-start-confirmation`).
- One feature per branch; never commit onto another feature's open-PR branch.
- PR body carries `Closes WOR-<n>` (Linear team WOR).
- A release bumps the versions in `packageManifest.json`, the child's `appVersion()` and its
  header changelog (see commit `3462639`, release 2.3.1).
