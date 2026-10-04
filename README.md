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
| **Dashboard tile** | Whether to create/maintain a companion tile device for this pool (on by default). |
| **Run now** | *Calculate & send now* and *Preview (log only)* buttons. |

Both delivery triggers are independent: you can notify on every new sample, send
a once-a-day summary at a fixed time, or both. The daily summary runs the same
calculation and sends it to the same notification device(s).

When **Use WaterGuru advice** is off (or the source device does not report
`doseAdvice`), the app falls back to generic formulas for pH/TA/CH/CYA and uses
the acid type / strength settings.

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
attributes on every calculation. The dose values are recorded only after the pump ON command
succeeds, so they represent commanded pump runs, not recommendations.

| Attribute | Unit | What it holds |
| --- | --- | --- |
| `lastDoseMl` | mL | Estimated liquid volume of the last commanded pump run. |
| `lastDoseEpochMs` | epoch ms | When that run was recorded — the dose's own time, not the time it was read. |
| `lastDoseRuntimeSeconds` | s | The pump runtime that volume was derived from. |
| `tankCapacityMl` | mL | Configured container capacity. |
| `tankRemainingMl` | mL | Estimated remaining inventory: capacity minus recorded app-controlled doses. |
| `tankPercent` | % | `tankRemainingMl` as a percentage of capacity. |
| `tankRunwayDays` | days | Estimated days until inventory reaches the configured low-tank threshold. May be absent until there is dose history. |
| `freeChlorine` | ppm | The reading this calculation used. |
| `targetFreeChlorine` | ppm | The FC target this app computed — CYA-aware, or your manual override. |
| `lastCalcEpochMs` | epoch ms | The same instant as `lastCalc`, as epoch milliseconds. `lastCalc` is a formatted date string that cannot be parsed reliably off the hub, so an external tool should use this one. |

Two behaviours an external reader has to know:

- **A dose carries its original timestamp**, so re-reading it does not create another dose
  record. A historian can poll as often as it likes without inflating usage totals.
- **Absent values stay absent rather than becoming zero.** `tankRunwayDays` may be missing while
  the app is still learning consumption, and that is not the same as a zero-day runway.

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

1. **Your manual override**, if set.
2. **Measured** — the average decline across your recent samples. The app keeps a
   rolling history of each new FC reading and averages the intervals where FC
   *fell* (intervals where FC rose are chlorine additions and are skipped). This
   needs a couple of days of samples to appear.
3. **Estimated** — a modeled default (3 ppm/day, scaled to 60% when the device
   reports a cover) used until measured history exists.

pH is not part of the clock — it modulates how *effective* a given FC is (high pH
lowers the active-chlorine fraction) rather than how fast FC decays. The estimate
is a planning aid, not a guarantee; confirm with your own test kit.

### pH / TA / CH / CYA

By default these are passed through from WaterGuru's `doseAdvice` — amounts
already computed in the products you actually use. When that advice is not
available, the app estimates generically:

- **TA up:** baking soda, 1.5 lb per 10 ppm per 10,000 gal.
- **pH / TA down:** muriatic acid, TA-aware (~6.4 fl oz of 31.45% lowers pH 0.1 per
  10,000 gal at TA ≈ 100), or the dry-acid equivalent if that is your acid type.
- **CH up:** calcium chloride, ~1.84 oz per 1 ppm per 10,000 gal.
- **CYA up:** cyanuric acid, 13 oz per 10 ppm per 10,000 gal.
- **CYA / CH too high:** no additive lowers these — the app estimates a partial
  drain/refill percentage instead.

## How it runs

- **Automatically** on each new WaterGuru sample (it subscribes to the device's
  `LastMeasurement` change), if that toggle is on.
- **As a daily summary** at a time you pick, if that toggle is on (independent of
  new samples — you can run both).
- **On demand** with the *Calculate & send now* button.
- **Preview** with the *Preview (log only)* button, which logs the message without
  sending it.

Every one of these also refreshes the dashboard tile (if enabled).

### Pump start confirmation (2.4.0)

In APPROVAL and AUTO mode an ON command is not taken as a running pump. The pump
switch must report on within 10 seconds; if it does not, ON is sent once more,
and after another 10 seconds the start is abandoned. On a plug that reports
power, the pump must then show power (the driver's `accessory` attribute on, or
`power` at or above *Minimum pump power that confirms a start*, default 3 W)
within 15 seconds, with one `refresh()` along the way. Set that minimum to 0 to
confirm by the switch report only.

The dose is timed from the confirmed start, and only a confirmed start is booked
against the tank, the daily total and the duplicate-sample lock. An abandoned
start sends OFF, books nothing and sends an alert. Because a Z-Wave ON can still
reach the plug minutes later, any ON seen within 30 minutes of an abandoned start
is treated as that delayed ON and stopped at once (this also stops a manual run
started in that window), and one precautionary OFF follows after 5 minutes.

## License

[Apache License 2.0](LICENSE) — matching the WaterGuru Integration app's license.

WaterGuru is a trademark of its respective owner. This project is not affiliated
with or endorsed by WaterGuru.
