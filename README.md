# WaterGuru Dosing Advisor (Hubitat)

A [Hubitat](https://hubitat.com/) app that reads a WaterGuru pool device and tells
you **how much of which chemical to add**, then sends that recommendation to your
notification device(s) (Pushover, Telegram, etc.).

It is a **companion** to the
[WaterGuru Integration](https://github.com/bdwilson/hubitat/tree/master/WaterGuru)
app, not a replacement. That app creates the device this one reads, and already
handles change-driven alerts, quiet hours, pause switches, recovery messages and
per-metric thresholds. This app deliberately does **not** duplicate any of that.
It adds the two things the integration cannot do:

1. **Free-chlorine dosing that is CYA-aware / SLAM-aware.** WaterGuru targets a
   fixed ~3 ppm free chlorine regardless of stabilizer. For a stabilized or a
   recovering (green) pool that is far too low. This app targets FC as a function
   of cyanuric acid and converts the gap into a real volume of *your* liquid
   chlorine.
2. **Concrete dose amounts for pH / TA / CH / CYA** — passed through verbatim from
   WaterGuru's own product-specific advice, with generic fallback formulas when
   that advice is not available.

> ⚠️ **All doses are estimates.** Always confirm with your own test kit before
> adding chemicals. Add strong oxidizers and acids in stages and retest between
> doses. You are responsible for what goes in your pool.

---

## Requirements

- A Hubitat hub running platform **2.3.0** or newer.
- The **WaterGuru Integration** app + driver installed and working, exposing a
  child device (the *WaterGuru Integration Driver*). This app reads that device's
  attributes — `freeChlorine`, `pH`, `cyanuricAcid`, `totalAlkalinity`,
  `calciumHardness`, the matching `*Target` attributes, `poolVolume`,
  `chlorineProductPct`, `acidType`, and `doseAdvice`.
- One or more `capability.notification` devices to receive the recommendation
  (e.g. a Pushover device).

This is a **parent/child** app: you install one parent (*WaterGuru Dosing
Advisor*) and add one child per pool, so several pools live under a single app
instead of a separate top-level app each.

### Via Hubitat Package Manager (recommended)

Search HPM for **WaterGuru Dosing Advisor** and install — HPM installs the
parent app, the child app, and the tile driver for you.

### Manually

Install **both** app files under **Apps Code → New App** (or use the **Import**
button with the raw file URL), and **Save** each:

1. Parent — [`apps/WaterGuru-Dosing-Advisor.groovy`](apps/WaterGuru-Dosing-Advisor.groovy)
2. Child — [`apps/WaterGuru-Dosing-Advisor-Child.groovy`](apps/WaterGuru-Dosing-Advisor-Child.groovy)

Then install the tile driver under **Drivers Code → New Driver** and **Save** it:

3. Driver — [`drivers/WaterGuru-Dosing-Tile.groovy`](drivers/WaterGuru-Dosing-Tile.groovy)

The driver backs the optional dashboard tile (below). If you don't install it,
the apps still work — only the tile device can't be created.

Then go to **Apps → Add User App → WaterGuru Dosing Advisor** (the parent) and
use **Add a WaterGuru pool** to create an advisor for each pool.

## Configure

Each pool is one child app. Fields that read from the WaterGuru device show the
current live value in their label (e.g. *"Pool volume (gallons) — blank uses
WaterGuru's value (currently 25000)"*), so you can see what "blank" will use
without pinning it.

| Setting | What it does |
| --- | --- |
| **Source** | The WaterGuru pool device to read. |
| **SLAM mode** | When on, FC target = `SLAM factor × CYA` (default factor 0.40, the [TFP](https://www.troublefreepool.com/) SLAM level). Recommended while recovering. |
| **SLAM factor** | The multiplier used in SLAM mode. |
| **Manual FC target override** | Pin the FC target directly (ppm); blank = compute it from CYA. |
| **Pool volume / chlorine strength %** | Blank = read from the device (`poolVolume`, `chlorineProductPct`). |
| **Chlorine runway** | Show an estimate of the days until FC drops below the algae floor. Optional manual daily-loss override (ppm/day); blank = auto (measured from your samples, else a cover-aware estimate). |
| **Use WaterGuru advice** | Pass through WaterGuru's own pH/TA/CH/CYA dose lines. |
| **Also include WaterGuru's chlorine advice** | Off by default — this app computes FC, so WaterGuru's (CYA-blind) chlorine line is dropped to avoid a conflicting recommendation. |
| **Target overrides** | pH / TA / CYA / CH targets; blank = read the device's targets. |
| **Delivery** | The notification device(s) to send to, whether to notify automatically on each new sample, and an optional **daily summary** at a set time. |
| **Refresh WaterGuru once daily** and **Daily WaterGuru fetch time** | Ask the source device to refresh at a set time, so the evening sample is imported promptly instead of waiting for the integration's next poll. Set the time to about 30 minutes after the measurement time configured in the WaterGuru app: the pod needs 12 to 15 minutes to read, and the dose starts as soon as the new reading arrives. The default is 19:45. If that fetch brings no new sample, the device is refreshed once more 45 minutes later (20:30 for a 19:45 fetch), only while the AUTO dosing window is open; a sample it brings in goes through every dosing guard as usual, and if it brings nothing either, one log line and nothing more. The app page shows the time of day WaterGuru has actually been measuring at, taken from the recent samples. |
| **Dashboard tile** | Whether to create/maintain a companion tile device for this pool (on by default). |
| **Automated liquid-chlorine dosing** | The dosing mode and the pump settings, including the start confirmation and the safety limits; see *Dosing modes and pump settings* below. |
| **Run now** | *Calculate & send now* and *Preview (log only)* buttons. |
| **Correct the estimate** | *Adjust tank inventory by* a signed amount of mL, and *Void a recorded dose that never ran*. Each shows what its button will do before you press it; see *Correcting the tank estimate*. |

Both delivery triggers are independent: you can notify on every new sample, send
a once-a-day summary at a fixed time, or both. The daily summary runs the same
calculation and sends it to the same notification device(s).

When **Use WaterGuru advice** is off (or the source device does not report
`doseAdvice`), the app falls back to generic formulas for pH/TA/CH/CYA and uses
the acid type / strength settings.

## Dosing modes and pump settings

The *Automated liquid-chlorine dosing* section decides whether this app can run a liquid
chlorine pump at all, and under which limits. Three modes:

- **ADVISORY** (default): calculate and notify only. The pump is never started by this app.
- **APPROVAL**: a valid new sample queues one dose; you start it yourself with *Run the pending
  chlorine dose now* on the app page. Every safety limit below still applies at that moment.
- **AUTO**: a new, valid WaterGuru sample may start the pump unattended once every safety check
  passes. Daily summaries, previews and manual calculations never start it.

The pump settings, one line each. *Safety* marks the ones that decide whether or how long the
pump may run; the others shape the dose.

| Setting | Default | What it does | Safety |
| --- | --- | --- | --- |
| **Dedicated chlorine pump switch** | none | The switch this app turns on and off. Without one no dose can start. | yes |
| **Pump delivery rate (mL/min)** | 185 | Converts a dose volume into a pump runtime. A wrong rate doses the wrong volume. | yes |
| **Minimum running power (W)** | 3 | On a power-reporting switch, the watts a fresh report must show before a start counts as confirmed. 0, blank or negative uses 3 W; 0 W never confirms a start. | yes |
| **How long to wait for start evidence (s)** | 20 | The start is failed safely if no fresh ON (plus power, where reported) arrives in this time, never past the planned stop. | yes |
| **How long a confirmed run may lose power (s)** | 30 | A confirmed run without fresh, adequate power for this long is stopped and a fault latched. | yes |
| **Pool circulation/filter switch** | none | The interlock: APPROVAL and AUTO dosing need it on. | yes |
| **Require circulation switch to already be on** | on | Whether the interlock switch must report on. | yes |
| **I confirm the circulation pump runs continuously** | off | Replaces the interlock when there is no circulation switch; your statement stands in for a measured one. | yes |
| **Allow automatic dosing when FC is above the minimum but below target** | off | Optional top-ups. Off, only a reading below the minimum doses. | no |
| **Only allow AUTO dosing during an evening time window** (start, end) | off | Keeps a manual daytime measurement from starting the pump; readings outside the window are recorded only. | yes |
| **Limit AUTO mode to one completed dose per calendar day** | on | A second AUTO dose on the same day is blocked, whatever the reading says. A failed attempt counts too. | yes |
| **Maximum WaterGuru sample age (hours)** | 18 | No dose on a reading older than this. | yes |
| **Minimum dose to run (mL)** | 50 | A smaller calculated dose is not run. | no |
| **Maximum single dose (mL)** | 3000 | A larger calculated dose is blocked, not capped. At the default 185 mL/min a 14 minute run is about 2590 mL, so the maximum runtime, not this 3000 mL cap, bounds a single dose unless the pump is faster. | yes |
| **Maximum total dose per day (mL)** | 3500 | Blocks a dose that would take the day's total (including reserved failed attempts) over this. | yes |
| **Absolute maximum pump runtime (minutes)** | 14 | A dose whose runtime would exceed this is blocked. | yes |
| **Block dosing below pH / above pH** | 6.8 / 8.2 | No dose outside this pH range. | yes |
| **Arm an independent emergency cutoff for every pump run** | on | A second, independent OFF timer for every pump start, retried until the switch freshly reports off. The last backstop. | yes |
| **Independent emergency cutoff after this many minutes** | 15 | When the cutoff fires. It must be later than the maximum runtime, or no dose can start and the page shows the problem in red. | yes |
| **Chlorine container capacity** and **low-tank warning percent** | 1 gal, 20 % | The tank estimate: a dose larger than what is estimated to remain is blocked, and a low tank sends a warning. | yes |

Two checks run before every start and are reported like any other block:

- **The cutoff must be later than the maximum runtime.** Equal or lower is refused, because the
  cutoff would stop a dose that is still within its plan.
- **The app's subscriptions must be this version's.** A code update (Hubitat Package Manager, or
  saving new code under *Apps Code*) replaces the code without running the app's setup, so the
  hub may still hold the previous version's subscriptions. Until you open the pool and press
  **Done**, every start is refused with *"the app's subscriptions are out of date after a code
  update; open the app and press Done"*, and the dosing status on the page says the same.

## Dashboard tile

Hubitat apps can't draw on a dashboard, so each pool advisor maintains its own
small companion device — **"Dosing Tile: &lt;pool&gt;"** (driver *WaterGuru Dosing
Tile*) — and refreshes it on every calculation. Add that device to a dashboard
to see, at a glance, what the pool needs. The device exposes:

| Attribute | What it holds |
| --- | --- |
| `status` | `GREEN` / `YELLOW` / `RED` — see the colour key below. Use it to colour the tile. |
| `recommendation` | The concise one-liner, e.g. *"Add 4.8 gal chlorine · 1.2 lb baking soda"* or *"All in range"*. |
| `detail` | The full multi-line recommendation (same text as the notification). |
| `tileHtml` | A formatted HTML snippet (pool, status pill, FC current→target, one-liner, cassette type when reported, timestamp). |
| `lastCalc` | When the tile was last updated. |

**Numeric telemetry (for external history)**

These exist so an external historian — InfluxDB/Grafana, or Maker API — can store actual
pump-started doses and compute durable usage totals. They are written alongside the human
attributes on every calculation. The dose values are recorded only after a start has been
**confirmed** (a fresh ON, plus fresh power above the minimum for a power-capable switch), so
they represent pump runs this app actually observed running — not recommendations, previews, or a
bare `on()` return. The volume is booked at confirmation as the planned estimate and, once OFF is
confirmed, corrected to the observed ON-to-OFF run time if the two differ by more than 3 s (see
*Booking what actually ran* below). It is never a measurement of liquid. An attempt that is never
confirmed still reserves its sample and daily volume, but writes no `lastDose*` value.

| Attribute | Unit | What it holds |
| --- | --- | --- |
| `lastDoseMl` | mL | Estimated liquid volume of the last confirmed pump run: the plan at confirmation, the observed run once OFF is confirmed if they differ. |
| `lastDoseEpochMs` | epoch ms | When that run was recorded — the dose's own time, not the time it was read. A correction keeps it. |
| `lastDoseRuntimeSeconds` | s | The pump runtime that volume corresponds to (planned, or observed after a correction). |
| `tankCapacityMl` | mL | Configured container capacity. |
| `tankRemainingMl` | mL | Estimated remaining inventory: capacity minus recorded app-controlled doses. |
| `tankPercent` | % | `tankRemainingMl` as a percentage of capacity. |
| `tankRunwayDays` | days | Estimated days until inventory reaches the configured low-tank threshold. `-1` while there is not yet enough dose history. |
| `freeChlorine` | ppm | The reading this calculation used. |
| `targetFreeChlorine` | ppm | The FC target this app computed — CYA-aware, or your manual override. |
| `lastCalcEpochMs` | epoch ms | The same instant as `lastCalc`, as epoch milliseconds. `lastCalc` is a formatted date string that cannot be parsed reliably off the hub, so an external tool should use this one. |

Behaviours an external reader has to know:

- **A dose carries its original timestamp**, so re-reading it does not create another dose
  record. A historian can poll as often as it likes without inflating usage totals.
- **A correction keeps that timestamp.** When the observed run differs from the plan, `lastDoseMl`
  and `lastDoseRuntimeSeconds` are republished with the same `lastDoseEpochMs`, so a historian that
  keys doses by that value (the Waterguru-Grafana-Chart collector does) overwrites the point rather
  than counting a second dose.
- **Dose records are forced events.** At confirmation and at a correction the three `lastDose*`
  attributes are sent with `isStateChange: true`. The hub drops an event whose value did not
  change, so without that a dose of the same volume as the previous one, or a corrected volume
  under the unchanged `lastDoseEpochMs`, left a historian nothing to pair. A voided dose (see
  *Correcting the tank estimate*) is republished the same way, as 0 mL under its own timestamp.
  Since 2.4.4 so is a run that is counted against the tank without becoming the last dose (an
  unconfirmed attempt that drew power, or a late ON while a fault is pending): it is published once,
  under its dose-history timestamp, and `lastDose` stays the last confirmed dose.
- **An unknown value reads `-1`, never a stale number and never zero.** When the tank inventory is
  not initialized (or the container size changed and was not marked full), the FC reading or the
  target is missing, or the tank runway is still learning, the attribute is set to `-1`. The
  Waterguru-Grafana-Chart collector drops it instead of storing it, and `-1` is not the same as a
  zero-day runway.

**Colour key**

- 🔴 **RED** — a chemical addition is indicated, or **SLAM mode** is active.
- 🟡 **YELLOW** — only optional / advisory items (an optional top-up, a
  "too-high, consider draining" note), or a reading is missing so the result is
  uncertain.
- 🟢 **GREEN** — everything is in range; nothing to add.

**Adding it to a dashboard** (Hubitat's built-in dashboards):

1. Add the *Dosing Tile: …* device to the dashboard's device list.
2. Add a tile, pick that device, and choose the **Attribute** template.
3. Point it at one of:
   - `recommendation` — a clean one-line summary, or
   - `tileHtml` — the richer formatted layout (the Attribute template renders the HTML), or
   - `status` — just GREEN/YELLOW/RED (handy for driving tile colour with your own dashboard CSS, e.g. keying off the attribute value).

The tile is per-pool: each child advisor creates and updates its own device. Turn
the **Dashboard tile** toggle off to remove this pool's tile device.

## The dosing model

### Free chlorine (computed here)

FC target is derived from CYA rather than a fixed number:

- **SLAM mode on:** `target = slamFactor × CYA` (default `0.40 × CYA`).
- **SLAM mode off (TFP):** `min = 0.075 × CYA`, `target = 0.115 × CYA`.

When FC is below target, the dose of liquid chlorine is:

```
fl oz = (targetFC − FC) × (volume ÷ 10000) × 10.7 × (12.5 ÷ chlorine%)
```

(10.7 fl oz of 12.5% liquid chlorine raises FC by 1 ppm per 10,000 gallons.) The
result is reported in fl oz, cups (÷8) and gallons (÷128). When FC is at or above
target the app advises holding (in SLAM mode, maintaining the SLAM level and
retesting) rather than dosing.

### Chlorine runway (algae forecast)

Algae is held off by keeping FC above a CYA-linked floor — the [TFP](https://www.troublefreepool.com/)
minimum, `floor = 0.075 × CYA`. The runway estimate is:

```
days = (FC − floor) ÷ dailyLoss
```

`dailyLoss` (ppm/day) is, in order of preference:

1. **Your manual override** (Daily FC loss rate), if set.
2. **Measured:** the average daily loss over your recent samples. The app keeps a
   rolling history of each new FC reading. Each interval between two samples at
   least 6 hours apart is a balance: FC before, plus the chlorine this app dosed in
   between (converted to ppm), minus FC after, per day. Since 2.4.7 every such
   interval counts, including one where FC held (a loss of zero). An interval is
   skipped only when FC rose by at least one WaterGuru reading step (0.1 ppm)
   beyond this app's doses, which means chlorine came from somewhere else (a hand
   dose, a shock). The measured loss is the plain average of up to the last 5
   counted intervals. A hand dose smaller than that day's loss cannot be seen in
   the readings and makes the loss read lower. Converting a dose to ppm needs the
   pool volume and the chlorine strength (an override, or the device's own
   value); without them, an interval that contains a dose is left out rather than
   guessed. Each reading is taken when the app processes the sample, 20 seconds
   after it arrives: WaterGuru sends the sample time before the chlorine reading,
   so versions before 2.4.3 mostly stored the previous sample's FC, and those
   readings are not used.
3. **Estimated:** a modeled default (3 ppm/day, scaled to 60% when the device
   reports a cover), used until a loss is measured.

The figure in use is also the first line under the current readings in the
summary, for example `FC loss: 0.2 ppm/day (measured over 2 intervals; 1 skipped,
FC rose beyond this app's doses)` or `FC loss: 0.8 ppm/day (your setting)`. Until
a loss is measured it says `not measured yet`, with the number of skipped
intervals, `no usable interval yet` when there are samples but no interval
counts yet, or the rule it is waiting for (two samples at least 6 hours apart,
with no rise in FC beyond this app's doses). The daily summary notification
carries the same figure after the pH, for example `FC loss 0.2 ppm/day
(measured).` The loss rate only feeds this text, the runway and the tile
footer, never a dose amount.

pH is not part of the clock — it modulates how *effective* a given FC is (high pH
lowers the active-chlorine fraction) rather than how fast FC decays. The estimate
is a planning aid, not a guarantee; confirm with your own test kit.

### pH / TA / CH / CYA

By default these are passed through from WaterGuru's `doseAdvice` — amounts
already computed in the products you actually use. `doseAdvice` also carries
WaterGuru's maintenance steps (replacing the cassette, the battery,
calibration); those are left out, and the cassette line shows days left and
"replace soon" or "replace now" when WaterGuru wants a new cassette. Since 2.4.4
that cassette status also turns the tile YELLOW (never RED) with "Replace
cassette soon" or "Replace cassette now". When that advice is not available,
the app estimates generically:

- **TA up:** baking soda, 1.5 lb per 10 ppm per 10,000 gal.
- **pH / TA down:** muriatic acid, TA-aware (~6.4 fl oz of 31.45% lowers pH 0.1 per
  10,000 gal at TA ≈ 100), or the dry-acid equivalent if that is your acid type.
- **CH up:** calcium chloride, ~1.84 oz per 1 ppm per 10,000 gal.
- **CYA up:** cyanuric acid, 13 oz per 10 ppm per 10,000 gal.
- **CYA / CH too high:** no additive lowers these — the app estimates a partial
  drain/refill percentage instead.

## Pump start confirmation and faults

A returned `on()` command is **not** evidence that the relay closed or that the pump moved
liquid. This app therefore separates "start requested" from "start confirmed":

- **Power-capable switch** (reports a `power` attribute): a start is confirmed only by a **fresh
  ON** *and* **fresh measured power at or above the minimum** (default **3 W**). Both readings
  must postdate the ON request — a cached value from before it never confirms a start. Null,
  missing or throwing readings fail closed.
- **Non-power switch**: a fresh ON confirms the start, with an explicit notice that **power and
  flow are unverified**.
- **No claim of proven liquid flow is ever made.** The confirmed volume remains a planned
  estimate.

The app subscribes to repeated switch and power reports with `filterEvents: false`. Hubitat
delivers a report whose value did not change as an event with a fresh date, but it does not move
the device's stored state date (measured on a C8, October 2026). The app therefore uses the
event's receipt time: a steady wattage keeps a confirmed run alive, and an OFF report from a plug
that was already off still counts as a fresh OFF once it passes the same anchor checks as any
other OFF. The selected driver must publish received reports even when their values are
unchanged. A refresh request alone is never confirmation.
After upgrading, use **Done** once while the pump is idle to install these subscriptions; this
also requests OFF through the normal configuration-change stop path. The app checks this itself:
a code update does not run its setup, so until **Done** is pressed every start is refused with
"the app's subscriptions are out of date after a code update; open the app and press Done" (see
*Dosing modes and pump settings*). Verify the subscription settings before enabling automatic
dosing.

A lost ON is not judged on the plug's first answer. With Hubitat's **Command Retry** enabled for the
plug (device page, or Settings > Command Retry), the hub re-sends a command whose expected report
does not arrive, up to five times; for a switch the first retry goes out 1.5 s after the command and
each later one waits a further 1.5 s times the retry number, so the first three land within 9 to
13.5 s. When the start check's refresh is answered with an unchanged OFF, the attempt therefore keeps
waiting inside the start window, and a retried ON that lands in it, with power, confirms the start
like a prompt one. The attempt is closed as "the ON did not take effect" only 10 s after the ON and
on a second OFF answer; the window below stays the outer bound. The app itself never re-sends an ON.

If a start is not confirmed within the timeout (default **20 s**, never past the planned stop):

- an alert is sent **once**,
- the pump is stopped through the normal safety path, with the independent cutoff kept armed and
  retried until the switch **freshly** reports off,
- a **fault is latched** and stays visible until you press **Acknowledge pump fault**. Acknowledgement
  is refused while a stop is still being recovered or until the switch has freshly reported OFF; it
  releases the fault lock for future eligible doses — it never energises the pump, refunds the
  reserved volume, or resets the sample/day limits. If nothing is being recovered and no OFF report
  has arrived since the fault (for example a fault carried over from 2.4.0), the refusal says so:
  press **STOP chlorine pump now** once, and the plug's answer to that OFF is enough.
- A cached OFF from before the ON request cannot prematurely disarm the new backstop, and a late
  ON after an abort is never reclassified as a successful dose.

While a **power-confirmed** run is active, the app watches for loss of power or missing fresh
power evidence over a grace period (default **30 s**). A persistent loss aborts the run safely,
locks a fault, and leaves the delivery volume marked **uncertain for operator review**; the dose is
not retried. Once OFF is confirmed the tank is booked from the observed ON-to-OFF run time, which
is an upper bound on what an uncertain run delivered.

A real plug answers OFF asynchronously (about half a second). A stop therefore waits a few seconds
for the OFF report before it says anything: a healthy stop sends only the final "stopped" notice,
and "stop requested ... Retrying" goes out only if the OFF is still unconfirmed after that wait.
The retries and the independent cutoff are armed at the moment of the stop request either way.

The same holds for a stop with nothing open: **STOP chlorine pump now** on an idle app, a save
(**Done**), or a run the app did not start. Since 2.4.2 that stop is anchored to its own request, so
an old cached "off" cannot confirm it; until a fresh OFF arrives it says "stop requested", keeps its
retries and cutoff, and holds back a new dose. A notice that said "Retrying" is followed by a final
one when the OFF does arrive. Removing the app sends OFF once and says plainly that nothing will
retry, because removal deletes every job with the app.

Since 2.4.4 such a stop also ends when the switch never answers but has reported off since before
the request (an unpowered or unreachable plug, or a driver that only reports changes): after its
five retries it closes with a plain notice ("OFF sent 6 times with no answer; the switch last
reported off") instead of escalating into EMERGENCY notices and holding every dose. A switch that
reports ON at any point, or cannot be read, still escalates. When the emergency cutoff stops a run
the app did not start, the OFF that answers it brings a final "EMERGENCY cutoff confirmed the
chlorine pump OFF" notice, whether it lands before or after the EMERGENCY warning.

Once a stop is being enforced, a repeated ON report whose value did not change (for example a
stuck relay answering the Pump Power Profiler's 3 s refresh) does not restart it: the retry count
keeps counting up to the EMERGENCY escalation and the cutoff runs as scheduled. A changed ON (the
relay closing again) is still answered with OFF at once. The EMERGENCY notices wait a few seconds
for the plug's answer to the OFF just sent, like the stop notice, and an emergency cutoff that is
overdue (a busy hub, or a save just after its deadline) runs at once instead of being rescheduled.
The running power watch stops as soon as an OFF is requested, so falling power during a stop is
never reported as a power loss, and the cutoff latches the start fault when it stops an attempt
that was never confirmed. A power check that runs more than two intervals after the previous one
(after a hub restart, for example) asks the plug to report and judges on the next check, so readings
from before the gap cannot latch a false power loss; only one check in a row is put off.

All confirmed-OFF paths use the same cleanup. An unconfirmed attempt always retains its fault,
including an OFF report arriving near the planned end before a timer runs. Emergency stops record
their reason before requesting OFF, so a delayed acknowledgement cannot be described as ordinary
completion. Optional dashboard failures cannot prevent the running-power monitor from being armed.

### Attempt reservation vs. success telemetry

Before the ON command, the app reserves the attempt: the sample cannot be dosed again, the
planned volume counts against the daily cap, and the one-AUTO-dose-per-day guard is consumed —
even if the start is never confirmed. Only on confirmation does the app write the `lastDose*`
telemetry and draw the planned volume from the tank estimate. Existing historical `lastDose` and
tank history from earlier versions are preserved as-is.

### Booking what actually ran

When OFF is confirmed, the run is measured from its ON report to its OFF report (report latency at
both ends cancels out):

- **Within 3 s of the plan:** the planned booking stands.
- **Longer (a late OFF):** the extra volume is debited from the tank and added to today's total,
  the final notice gives the observed run time against the plan, and an OFF confirmed more than
  30 s after the planned stop latches a **stop-overrun** fault for review.
- **Shorter (an early stop):** the tank is booked from the observed run; today's total keeps the
  full planned reservation.
- **An unconfirmed attempt that demonstrably ran** (an ON report, plus power above the minimum on
  a power-reporting switch), including a late ON during fault recovery, is counted too: the tank
  from the observed run, today's total beyond what the attempt already reserved, with the window in
  the final notice. It never becomes a start or a `lastDose` record.

### Correcting the tank estimate

Two controls under the tank inventory correct the estimate on any install. Neither runs the pump,
and neither changes today's dose total, the sample lock or the one-AUTO-dose guard. Each value is
saved as you enter it, the line under it says what the button will do, and the button applies it
once and clears the field.

- **Adjust tank inventory by (mL, signed)** + **Apply tank adjustment**: moves the remaining
  estimate up or down, within zero and the container size. It needs an initialized tank.
- **Void a recorded dose that never ran** + **Void the selected dose**: pick one of the ten most
  recent recorded doses. It leaves the dose history (so the measured FC loss no longer adds it
  back), its volume returns to the tank, and the last-dose record is cleared only if it is that
  same dose. The tile republishes it as 0 mL under its own `lastDoseEpochMs`, so a historian
  zeroes its point instead of keeping a dose that never ran; the next calculation puts the actual
  last dose back on the tile. Voiding is refused while a dose or a stop is open.

## How it runs

- **Automatically** on each new WaterGuru sample (it subscribes to the device's
  `LastMeasurement` change), if that toggle is on.
- **As a daily summary** at a time you pick, if that toggle is on (independent of
  new samples — you can run both).
- **On demand** with the *Calculate & send now* button.
- **Preview** with the *Preview (log only)* button, which logs the message without
  sending it.

Every one of these also refreshes the dashboard tile (if enabled).

## License

[Apache License 2.0](LICENSE) — matching the WaterGuru Integration app's license.

WaterGuru is a trademark of its respective owner. This project is not affiliated
with or endorsed by WaterGuru.
