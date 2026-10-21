# Chlorine usage and tank charts

`waterguru-dashboard.import.json` is the complete dashboard (UID `waterguru-pool`),
with the chemistry panels first and the chlorine usage and tank section below them.
Display labels use colons or hyphens, never long dashes.

Two stale display rules are corrected to match the handoff: the standalone FC
chart no longer uses fixed 1-4 ppm green bands, and pH turns red above 7.8 rather
than at 7.9. The separate CYA comparison retains its 11.5% optimal and 7.5% minimum
reference lines. The pH upper thresholds are placed 0.000001 above 7.7 and 7.8 so
the boundary values themselves remain green and acceptable, respectively.

The dashboard and the Flux queries only read InfluxDB. They do not enable the
collector, modify Hubitat, or change any pump control.

## Required data

These charts require three additional measurements in the collector's bucket
(default `waterguru`). The WaterGuru cloud does not provide them. The collector's
optional Hubitat step writes them (see
[`docs/hubitat-dosing-integration.md`](../docs/hubitat-dosing-integration.md)).
Importing this dashboard alone does not populate these measurements.

| Measurement | Device tag | Numeric fields | Point time |
| --- | --- | --- | --- |
| `chlorine_dose` | `device_id` | `volume_ml`, `runtime_seconds` | Original recorded dose time |
| `chlorine_tank` | `device_id` | `remaining_ml`, `capacity_ml`, `remaining_percent`, `runway_days` | Inventory snapshot time |
| `chlorine_target` | `device_id` | `measured_ppm`, `target_ppm` | `lastCalcEpochMs` (epoch ms) when valid, else collection time |

`runtime_seconds` is the commanded pump runtime the volume was derived from, so the pair can be
plotted against each other to expose drift in the configured delivery rate. `target_ppm` is the
advisor's own CYA-aware (or manually overridden) FC target, which WaterGuru does not publish (it
targets a flat ~3 ppm regardless of stabilizer). `measured_ppm` is the reading that target was
computed against. All three are optional in the same way as the tank fields: absent attributes stay
absent rather than becoming zero, so an older driver degrades to the previous behaviour.

`volume_ml` is estimated liquid volume from a commanded pump run. It is not a
recommendation, a flow-meter measurement, or evidence of full physical delivery.
The charts sum this field only. They do not infer dosing from FC changes or tank
refills, and they exclude unrecorded manual additions.

`chlorine_target` is timestamped from the tile's numeric `lastCalcEpochMs` when the driver
exposes a valid value, so the reading and its target sit at the instant they were computed; when
it is absent or implausible the pair falls back to the collection time. The locale-formatted
`lastCalc` string is never parsed.

Use the collector's established tag set and original timestamps unchanged.
Re-reading a dose must not create another dose record. Extra tags can be present;
the queries group matching records for the selected device. They do not repair
duplicate writes from a broken collector.

The hidden constant dashboard variable `dosing_device` holds the dosing tile's
device id, which the import asks for. To change it later, edit that variable
under Dashboard settings, Variables. It applies to the chlorine queries only. The
chemistry queries and their source tags do not use it.

`runway_days` is the app's estimate until its configured low-inventory threshold.
It may be absent while the app learns consumption. All other tank fields are
independently optional; an absent field remains empty rather than becoming zero.

## Panels and time behavior

| Panel | Unit | Time scope |
| --- | --- | --- |
| Recorded this month | US gallons | Start of the current Pacific calendar month to now |
| Recorded this year | US gallons | January 1 in Pacific time to now |
| Tank remaining | US gallons | Latest snapshot within 90 days |
| Tank remaining (%) | Percent | Latest snapshot within 90 days |
| Tank runway to low level | Days | Latest estimate within 90 days |
| Tank data age | Elapsed time | Age of the latest remaining-volume snapshot within 90 days |
| Chlorine per recorded dose | mL | Dashboard time range |
| Tank inventory and full capacity | mL | Dashboard time range |
| Free chlorine vs advisor target | ppm | Dashboard time range |
| Dose vs commanded pump runtime | mL (left), seconds (right) | Dashboard time range |
| Recorded usage by month | US gallons | Calendar months overlapping the dashboard range |
| Recorded usage by year | US gallons | Calendar years overlapping the dashboard range |

The month/year stat panels always end at now, regardless of the time picker.
Historical monthly/yearly bars extend the query start back to the beginning of
the first selected calendar month/year, so that first bar is not a mid-period
subtotal. Their end is the selected range end, so the final bar can be partial.
These are categorical bars, so a period label remains visible even when its
start predates the selected range.

All calendar aggregation uses `America/Los_Angeles`, including daylight saving
time. Stored timestamps are not modified. Conversion uses 3,785.411784 mL per US
gallon. Missing periods are not filled with zeros. Even nonempty totals represent
only the available recorded history; these charts cannot establish collector
completeness or reconstruct earlier usage.

Tank percentage colors are red below 20%, orange from 20% to below 30%, and green
at 30% or more. These are display thresholds, not pump controls. Data age turns
orange after 48 hours and red after 72 hours, appropriate for daily collection.
Volume and runway values remain estimates and should be read with the age panel.

All twelve new queries are also provided separately in [`queries/chlorine/`](queries/chlorine/).
They contain the Grafana interpolation `"${dosing_device}"`. The id is interpolated
*inside quotes* so it is always a Flux string. The earlier `${dosing_device:json}` form is
avoided because a constant variable can interpolate as a bare number, and comparing a string tag
to an int silently matches nothing (no error, empty panel). For standalone
Influx query use, replace it with the quoted device ID and replace any
`v.timeRangeStart` / `v.timeRangeStop` values with the desired timestamps.

## Checking a deployment

1. Back up any dashboard you are about to replace, outside Grafana's watched
   dashboard folder, and compare more recent edits before replacing it.
2. Verify that the collector is already writing the measurements and fields
   above into its bucket.
3. Import or provision the dashboard as the README describes. A file provider
   reloads dashboard changes on its own.
4. Open the WaterGuru Pool dashboard and inspect the chlorine section. A lone
   dose or inventory snapshot should remain visible because points are enabled.
5. Compare a recorded dose with its Hubitat ledger entry and check Tank data age.
   Refreshing the dashboard must not increase the totals. A tank refill must not
   increase usage totals. If collection has not begun, expect no recorded data.

Nothing here touches infrastructure, container settings, firewall rules, proxy
configuration or authentication.

## Query validation

The queries were executed read-only against an InfluxDB 2 query
engine with synthetic `array.from` inputs. No fixture points were written.
Checks covered Pacific midnight across month/year boundaries, exclusion of a
different pool, full first-period grouping, US gallon conversion, missing-data
behavior, and inventory snapshot age. Repository Ruff, mypy, and pytest checks
also apply. A rendered production dashboard remains a post-deployment check.

Flux reference: [`date.truncate`](https://docs.influxdata.com/flux/v0/stdlib/date/truncate/),
[`aggregateWindow`](https://docs.influxdata.com/flux/v0/stdlib/universe/aggregatewindow/),
[`timezone.location`](https://docs.influxdata.com/flux/v0/stdlib/timezone/location/).
