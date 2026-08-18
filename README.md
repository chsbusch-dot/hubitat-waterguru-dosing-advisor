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

Search HPM for **WaterGuru Dosing Advisor** and install — HPM installs both the
parent and the child app for you.

### Manually

Install **both** app files under **Apps Code → New App** (or use the **Import**
button with the raw file URL), and **Save** each:

1. Parent — [`apps/WaterGuru-Dosing-Advisor.groovy`](apps/WaterGuru-Dosing-Advisor.groovy)
2. Child — [`apps/WaterGuru-Dosing-Advisor-Child.groovy`](apps/WaterGuru-Dosing-Advisor-Child.groovy)

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
| **Use WaterGuru advice** | Pass through WaterGuru's own pH/TA/CH/CYA dose lines. |
| **Also include WaterGuru's chlorine advice** | Off by default — this app computes FC, so WaterGuru's (CYA-blind) chlorine line is dropped to avoid a conflicting recommendation. |
| **Target overrides** | pH / TA / CYA / CH targets; blank = read the device's targets. |
| **Delivery** | The notification device(s) to send to, and whether to notify automatically on each new sample. |
| **Run now** | *Calculate & send now* and *Preview (log only)* buttons. |

When **Use WaterGuru advice** is off (or the source device does not report
`doseAdvice`), the app falls back to generic formulas for pH/TA/CH/CYA and uses
the acid type / strength settings.

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
- **On demand** with the *Calculate & send now* button.
- **Preview** with the *Preview (log only)* button, which logs the message without
  sending it.

## License

[Apache License 2.0](LICENSE) — matching the WaterGuru Integration app's license.

WaterGuru is a trademark of its respective owner. This project is not affiliated
with or endorsed by WaterGuru.
