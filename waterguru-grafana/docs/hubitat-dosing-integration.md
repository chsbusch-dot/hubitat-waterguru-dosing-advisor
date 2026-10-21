# Hubitat dosing integration

The dashboard's chlorine and tank panels need data the WaterGuru cloud does not have: how much
liquid chlorine was dosed, when, and how much is left in the tank. The
[WaterGuru Dosing Advisor](https://github.com/chsbusch-dot/hubitat-waterguru-dosing-advisor)
Hubitat app keeps those values as attributes of its dashboard tile device ("WaterGuru Dosing
Tile" driver). This collector reads that device through the hub's
[MCP Rule Server](https://github.com/kingpanther13/Hubitat-local-MCP-server) app and writes three
InfluxDB measurements: `chlorine_dose`, `chlorine_tank` and `chlorine_target`.

The step is optional. Without `HUBITAT_MCP_TOKEN` the collector skips it and writes only the
WaterGuru chemistry.

## The data contract

Attribute names are read **by exact string**. A renamed attribute produces no error, only empty
panels, so treat this table as a contract between the dosing app and the charts.

| Attribute on the tile device | Measurement | Field | Notes |
|---|---|---|---|
| `lastDoseMl` | `chlorine_dose` | `volume_ml` | mL, estimated from the commanded pump runtime. Tagged `device_id=<WG_DOSING_DEVICE_ID>`. |
| `lastDoseEpochMs` | `chlorine_dose` | *(timestamp)* | epoch **ms**, converted to ns and used as the point time |
| `lastDoseRuntimeSeconds` | `chlorine_dose` | `runtime_seconds` | the commanded runtime the volume was derived from |
| `tankRemainingMl` | `chlorine_tank` | `remaining_ml` | mL |
| `tankCapacityMl` | `chlorine_tank` | `capacity_ml` | mL |
| `tankPercent` | `chlorine_tank` | `remaining_percent` | 0 to 100 |
| `tankRunwayDays` | `chlorine_tank` | `runway_days` | days, per the app's own low-inventory threshold |
| `freeChlorine` | `chlorine_target` | `measured_ppm` | the reading the target was computed against |
| `targetFreeChlorine` | `chlorine_target` | `target_ppm` | the advisor's CYA-aware (or manually set) FC target |
| `lastCalcEpochMs` | `chlorine_target` | *(timestamp)* | epoch **ms**, from tile driver 1.3.0 on. The point time when present and valid, otherwise the collection time |

Rules the collector enforces, which the charts rely on:

* **Dose points carry the original dose time**, not the collection time. Reading the same dose
  again rewrites the identical series and timestamp (InfluxDB overwrites on measurement, tag set
  and timestamp) instead of adding a duplicate.
* **Tank points are snapshots at collection time.** The queries take the latest within 90 days.
* **Target points use `lastCalcEpochMs`** when the driver exposes a valid value, so the FC
  reading and its target are stored at the instant they were computed. When the attribute is
  absent, not numeric or implausible, the pair falls back to the collection time. The
  locale-formatted `lastCalc` string is never parsed.
* **A missing attribute stays absent.** It is never written as zero. `runway_days` in
  particular is absent while the app is still learning consumption, and the charts show absent
  as empty, which is not the same as zero.
* A dose is written only when `volume_ml >= 0` and its timestamp is present and positive.

## How the collector reads the hub

* **Endpoint.** `HUBITAT_MCP_URL`, the MCP Rule Server's endpoint
  (`http://<hub-ip>/apps/api/<app-id>/mcp`). Each read is a bare JSON-RPC `tools/call` with the
  hub token, with no session handshake and no change on the hub.
* **Snapshot.** `hub_get_device` with `{deviceId: <WG_DOSING_DEVICE_ID>, mode: "summary"}`
  returns the current attributes: the tank inventory, the FC target and the latest dose.
* **Event history.** `hub_list_device_events` is the primary dose source. The snapshot holds
  only the latest dose, so a daily run that read just the snapshot would lose every dose between
  two runs. See "Event ingestion" below.
* **Why not Maker API.** Maker API serves only the devices selected in that app. The MCP Rule
  Server reads any device with one token and needs no change to an existing Maker API instance.
* **Read only.** The collector only reads attributes and events and writes time-series points.
  It has no command path and cannot drive the pump.
* **Opt in.** With `HUBITAT_MCP_TOKEN` unset the step is skipped, and the run is healthy.
* **Soft failing, but visible.** A hub error logs a warning and the run continues, so a hub
  outage cannot cost the WaterGuru chemistry history. A WaterGuru cloud failure does not stop
  the hub telemetry either. Each side is reported on the run result (`waterguru_ok`,
  `hubitat_ok`) and in the summary line, and any failed or degraded configured source makes the
  run exit **1**. A configuration error exits **2**.

## Event ingestion (dose history)

The upstream tool is `toolGetDeviceHistory` in the MCP Rule Server
(`libraries/mcp-devices-lib.groovy`). Its contract as relied on here:

* rows come from `/device/eventsJson/<id>` **newest first**;
* the `attribute` and time filters are applied while the result is built: the tool walks the
  native rows, keeps each row that matches the attribute and time window, and stops once the
  kept rows reach `limit` (default **100**, capped at **500**). The limit bounds **matched
  rows**, not the raw rows of the native endpoint, which has its own cap and retention;
* the response carries `events`, `count`, `sinceMode`, `sinceTimestamp`, plus whichever bound
  the caller sent (`hoursBack` or `since`);
* the response's `since` is an **echo of the caller's bookmark, not a next cursor**.

The collector therefore makes **three filtered reads per run**, one per dosing attribute
(`lastDoseEpochMs`, `lastDoseMl`, `lastDoseRuntimeSeconds`), each with `attribute=<name>`,
`limit=500` and the same bound: `since`, or `hoursBack` limited to the hub's **seven-day** event
retention when there is no bookmark yet. Filtering by attribute means a busy tile's other
traffic cannot use up the matched-row budget and push older dose rows out of the page. The
bookmark lives in `WG_STATE_DIR` (`hubitat-events-cursor.json`, keyed by device id).

**Cursor rule.** The cursor advances from the newest returned event `date`, never from the
response's `since`, and only when every *required* read (`lastDoseEpochMs`, `lastDoseMl`)
completed. It is saved only after the points were written, so a failed write re-reads the same
window. That is harmless: dose points carry their original timestamps and InfluxDB overwrites
them.

**Saturation rule.** A required read that comes back saturated (`count >= 500`) counts as
incomplete: the run warns, does **not** advance the cursor, and writes only the snapshot dose
rather than present a truncated window as full history. A saturated or failed read of the
optional runtime attribute only leaves `runtime_seconds` off otherwise complete doses and does
not hold the cursor. Any of these marks the run degraded, so the scheduler sees a non-zero exit.

**Accepted schema.** The parser is defensive. It accepts a JSON object (or a JSON-RPC
`result.content[0].text` JSON string, or a direct `result.content[0]` object) with the events
under `events`, `deviceEvents`, `data`, `items` or `result`, typically:

```json
{"events": [
  {"name": "lastDoseEpochMs", "value": 1790390724739,
   "date": "2026-09-26T02:45:24.739+0000"},
  {"name": "lastDoseMl", "value": 237.3273375, "unit": "mL", "epoch": 1790390724739}
],
 "count": 2,
 "sinceMode": "since",
 "sinceTimestamp": "2026-09-19T00:00:00.000Z",
 "since": "2026-09-19T00:00:00.000Z"}
```

Each event needs `name` (or `attribute`), `value`, and a time from `epoch`, `epochMs`,
`timestamp` or `time` (numeric milliseconds, or seconds when small) or from `date`, `dateTime`
or `isoDate` (ISO 8601). Non-finite values, implausible values and impossible timestamps are
dropped, not written. `count`, `sinceMode` and `sinceTimestamp` are read for saturation and
diagnostics only.

**Pairing and deduplication.** A `chlorine_dose` point is written only when a
`lastDoseEpochMs` event has a `lastDoseMl` event within 60 s (the app writes one dose's
attributes together). The dose time is the `lastDoseEpochMs` value, and
`lastDoseRuntimeSeconds` is optional. A lone attribute never becomes an invented dose. When the
snapshot's `lastDoseEpochMs` equals a dose already taken from the history, the two become
**one** point (event fields win, a missing runtime is filled from the snapshot). The snapshot
dose stands alone only when the history is unavailable or does not hold that timestamp. Tank
inventory and the FC target always come from the snapshot.

**Seven-day limit.** The hub keeps device events for seven days. When the collector has not run
for longer than that (an older cursor is clamped to the window edge), the doses in the gap
cannot be recovered and the run logs a warning. Tank and target snapshots are not affected.

When the response has a shape the parser does not accept, the run logs
`Hubitat dose history incomplete (...)` and falls back to the snapshot's single dose, so the
charts degrade to one dose per run rather than going empty.

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `HUBITAT_MCP_TOKEN` | unset | the MCP Rule Server's access token; unset skips the step |
| `HUBITAT_MCP_URL` | none, required with the token | `http://<hub-ip>/apps/api/<app-id>/mcp` |
| `WG_DOSING_DEVICE_ID` | none, required with the token | device id of the dosing advisor's tile |
| `HUBITAT_TIMEOUT` | `30` | request timeout, seconds |

With the token set, a missing URL or device id stops the run with a configuration error (exit
2), also in a dry run. The Grafana import asks for the same device id (see the README).

## Example output

Line protocol for one dose and one tank snapshot, with device id `101`:

```
chlorine_dose,device_id=101 volume_ml=237.3273375,runtime_seconds=65 1790390724739000000
chlorine_tank,device_id=101 remaining_ml=56543.8494225,capacity_ml=56781.17676,remaining_percent=99.5820316678,runway_days=52.4883227879 1790395814186208000
```

The calendar panels convert at **3,785.411784 mL per US gallon** and aggregate by calendar day,
month and year in `America/Los_Angeles`, DST included. Missing periods are not filled with
zeros. [`grafana/CHLORINE-CHARTS.md`](../grafana/CHLORINE-CHARTS.md) has the panel list and the
standalone Flux queries.
