# WaterGuru Dosing Advisor (Hubitat)

A [Hubitat](https://hubitat.com/) app that reads a WaterGuru pool device and tells
you **how much of which chemical to add**, then sends that recommendation to your
notification device(s) (Pushover, Telegram, etc.).

It is a **companion** to the
[WaterGuru Integration](https://github.com/bdwilson/hubitat/tree/master/WaterGuru)
app, not a replacement. That app creates the device this one reads, and already
handles change-driven alerts, quiet hours, pause switches, recovery messages and
per-metric thresholds. This app does **not** duplicate those features.
It adds two things the integration cannot do:

1. **Free-chlorine dosing that is CYA-aware / SLAM-aware.** WaterGuru targets a
   fixed ~3 ppm free chlorine regardless of stabilizer. For a stabilized or a
   recovering (green) pool that is far too low. This app targets FC as a function
   of cyanuric acid and converts the gap into a volume of the liquid
   chlorine you use.
2. **Dose amounts for pH / TA / CH / CYA**, passed through verbatim from
   WaterGuru's own product-specific advice, with generic fallback formulas when
   that advice is not available.

> ⚠️ **This app can run a chlorine pump, and every dose it gives is an estimate.**
> Read [Safety](#safety) before you connect a pump, and the [Disclaimer](#disclaimer)
> before you use the app.

---

## Documentation

| Guide | What it covers |
| --- | --- |
| [Software guide](docs/software.md) | Components, install, every setting, dosing modes, the dashboard tile, the dosing model, pump start confirmation and faults, the Pump Power Profiler. |
| [Hardware guide](docs/hardware.md) | One complete pump build: bill of materials, chemical storage and PPE, assembly, calibration, Hubitat setup, commissioning and troubleshooting. |
| [Assembly photos](docs/assembly-photos.md) | The finished build, with what the photos show and what they cannot. |
| [Diagrams](docs/diagrams.md) | The layout, reservoir, power and dosing-sequence diagrams, with their editable source. |

## Components

| Component | Source | In this package |
| --- | --- | --- |
| WaterGuru Integration (app + driver) | [bdwilson/hubitat, WaterGuru](https://github.com/bdwilson/hubitat/tree/master/WaterGuru) | No. Install it first, this app reads its device. |
| WaterGuru Dosing Advisor (parent + one pool child per pool) | [`apps/`](apps/) | Yes |
| WaterGuru Dosing Tile (dashboard driver) | [`drivers/`](drivers/) | Yes |
| Pump Power Profiler (optional power and wear recorder) | [`apps/PumpPowerProfiler.groovy`](apps/PumpPowerProfiler.groovy) | No. Install by hand. |

## Quick start

1. Install and set up the [WaterGuru Integration](https://github.com/bdwilson/hubitat/tree/master/WaterGuru).
2. In Hubitat Package Manager, search for **WaterGuru Dosing Advisor** and install it. The
   [software guide](docs/software.md#install) has the manual install.
3. Go to **Apps > Add User App > WaterGuru Dosing Advisor** and use **Add a WaterGuru pool**. Select
   the WaterGuru device and a notification device.
4. The pool starts in **ADVISORY** mode: it calculates and notifies, and never starts a pump. Before
   you connect one, read [Safety](#safety) and the [hardware guide](docs/hardware.md).


## Safety

This app can switch a pump that puts liquid chlorine into your pool. It checks a lot before and
during every run (see [Dosing modes and pump settings](docs/software.md#dosing-modes-and-pump-settings)
and [Pump start confirmation and faults](docs/software.md#pump-start-confirmation-and-faults) in the
software guide), but software can fail, and so can a relay, a radio link, a sensor or a pump. Set it
up the way you would set up any pool equipment that handles chemicals and mains power.

### Doses are estimates

Always confirm with your own test kit before adding chemicals. Add strong oxidizers and acids in
stages and retest between doses. You are responsible for what goes in your pool.

### Before you use AUTO

The dosing mode defaults to **ADVISORY**: the app calculates and notifies, and never starts the
pump. Once the pump is connected, use **APPROVAL**: each dose waits until you press *Run the pending
chlorine dose now*, so you can be there for it. Watch several doses that way, compare the next
readings and the tank level with what the app expected, and only then switch to **AUTO**.

### Layer the runtime limits

Three separate limits end a pump run, and each must allow more time than the one before it:

1. **Absolute maximum pump runtime** (default 14 minutes). A dose that would run longer is blocked
   (in SLAM mode it is capped to this runtime).
2. **Independent emergency cutoff** (default 15 minutes). A second OFF timer armed for every pump
   start and retried until the switch reports off. No dose starts unless it is later than the
   maximum runtime.
3. **The smart plug's own auto-off timer.** This one lives in the plug, not in the app, so it still
   turns the pump off when the hub is busy, restarting or has lost contact with the plug. The app
   cannot set it: you have to, in the plug's settings (parameter 2 on a Zooz ZEN05, see the plug's
   manual for its unit). Set it longer than the emergency cutoff. It is the last layer, so use a
   plug that has one. The [hardware guide](docs/hardware.md#2-configure-the-zen05) shows one ZEN05 setup.

Keep maximum runtime < emergency cutoff < plug auto-off. I run them at 17, 18 and 19 minutes. If
you raise the maximum runtime, raise the other two with it.

### Calibrate the pump rate

**Pump delivery rate** turns a dose volume into a runtime, so a wrong rate gives a wrong dose. Do
not rely on the pump's rating. Catch one real run in a container and weigh it:

1. Weigh the empty container, catch a run of known length, and weigh it again.
2. Convert the net weight to volume with the product's specific gravity (SG): mL = grams ÷ SG.
   Liquid chlorine at 10 to 12.5% has an SG of about 1.16 to 1.21. The label or the safety data
   sheet gives the exact figure. Never read grams as mL: that overstates the volume by 16 to 21%.
3. Divide the volume by the run time in minutes, and enter that as the pump rate.

Example: 2293 g net at an SG of about 1.19 is about 1930 mL. Over a 580 second run that is about
200 mL/min.

A run measured into an open bottle has no back pressure, so the rate in service can only be equal
or lower. Treat the measured figure as an upper bound.

### Check the plug's radio link

The app only knows what the plug reports. On my install, a Z-Wave plug paired as Z-Wave
Long Range with S2 security stopped decoding after a hub restart. The scheduled dose went
unanswered and the app latched a fault. That is the safe outcome, but the dose was missed.
Re-pairing the plug as a plain mesh device without security, and turning on Hubitat's **Command
Retry** for it, fixed it there. That is one install's experience, not a rule. Whatever pairing you
use, check after a hub restart that the plug still answers (press **Refresh** on its device page
and look for a fresh event) before the next scheduled dose.

### Chemicals and wiring

- Liquid chlorine (sodium hypochlorite) must never be mixed with acid (pH down, muriatic acid) or
  any other chemical. Store it and pump it separately, with its own container, tubing and pump.
- Wear eye and skin protection when you handle it, fill the tank or catch a calibration run.
- Follow the product label and your local codes.
- Put an outdoor pump on a GFCI-protected circuit (an RCD outside North America), behind a plug
  rated for the load.

## Disclaimer

This is a summary. The [license](LICENSE) is the binding text.

- **No warranty.** As far as the law allows, this software is provided "as is", without
  warranties or conditions of any kind. See sections 7 (Disclaimer of Warranty) and 8 (Limitation
  of Liability) of the [Apache License 2.0](LICENSE). This summary does not change them.
- **Your responsibility.** You are responsible for your pool's water chemistry, for your equipment
  and its wiring, and for following local law and the labels of the products you use.
- **Estimates, not professional advice.** The app's estimates and advice are not
  professional pool-service advice. Test your water independently before you add chemicals.
- **Not affiliated.** This project is not affiliated with or endorsed by WaterGuru, Hubitat or
  Zooz. WaterGuru, Hubitat and Zooz are trademarks of their respective owners.

## License

[Apache License 2.0](LICENSE), matching the WaterGuru Integration app's license. See the
[Disclaimer](#disclaimer) above.
