# Pool chlorine dosing with Hubitat, WaterGuru and a Z-Wave plug

Build a liquid-chlorine dosing system around a **Kamoer KCPA600 peristaltic pump**, a **Zooz ZEN05 800LR outdoor Z-Wave plug**, and **WaterGuru Dosing Advisor for Hubitat**. WaterGuru supplies periodic chemistry measurements; the advisor calculates a bounded dose; the Z-Wave plug switches the pump's 24 V power supply.

**The pump needs no Wi-Fi connection.** Its speed is set locally with the knob. Hubitat controls run duration through Z-Wave. The WaterGuru integration has its own network/cloud requirements; removing pump Wi-Fi does not make the measurement system fully offline.

This tutorial documents the author's completed physical assembly, photographed in IMG_0542 to IMG_0551, and the published control software. The discharge valve is fitted to the end of the tube in the pool near a return fitting. The photos establish that routing and the cap/pump arrangement. Material compatibility, the enclosure rating and protective functions still require the component specifications and commissioning records; they cannot be certified from photographs.

Updated: October 21, 2026. Software reference: [WaterGuru Dosing Advisor 2.5.0, commit 3dd9a71](https://github.com/chsbusch-dot/hubitat-waterguru-dosing-advisor/tree/3dd9a7192682aab1ecde6031e55bdb6431e5b925). This identifies the documentation reviewed, not the version currently installed on every reader's hub.

## Contents

- [Build at a glance](#build-at-a-glance)
- [Bill of materials](#bill-of-materials)
- [Chemical storage and PPE](#chemical-storage-and-ppe)
- [Assembly](#assembly)
- [Complete assembly photo gallery](docs/assembly-photos.md)
- [Diagram gallery and editable sources](docs/diagrams.md)
- [Calibration](#calibration)
- [Hubitat setup](#hubitat-setup)
- [Commissioning and operation](#commissioning-and-operation)
- [Maintenance and troubleshooting](#maintenance-and-troubleshooting)
- [Sources](#sources)

## Build at a glance

| Component or setting | This build |
|---|---|
| Pool volume | Nominal 25,000 US gallons |
| Chemical | Liquid sodium hypochlorite; source configuration uses 12.5% |
| Reservoir | Nominal 15 US gallons; record actual usable fill separately |
| Pump | Kamoer KCPA600-N40, 24 V DC, local potentiometer speed control |
| Pump-head tube | **Norprene, 4 mm ID × 7.2 mm OD** for the manufacturer's N40 configuration |
| External tubing | Black nominal 1/4-inch OD LLDPE |
| Pump delivery rate | **221 mL/min**, reported in the later build record; recalibrate your own installation |
| Power switching | Zooz ZEN05 800LR, controlled by Hubitat over Z-Wave |
| Discharge | Tube-mounted check valve submerged near a pool return; no return-pipe tap shown |
| Measurement | WaterGuru; scheduled samples, not continuous FC monitoring |
| Software | WaterGuru Integration plus WaterGuru Dosing Advisor and optional dosing tile |

Norprene is the correct tube name. It is not neoprene. The former draft's instruction to replace it with PharMed BPT has been removed. Match replacement tubing to the exact pump head and confirm service life and chemical compatibility with the manufacturer. The product name alone does not establish suitability for every chlorine concentration or pressure. [1]

### Physical dosing diagram

![Physical dosing layout: reservoir and suction strainer to Kamoer pump to a submerged check valve near the pool return](diagrams/01-system-layout.svg)

*Figure 1. The valve discharges into the pool beside the return fitting. Internal pickup details are schematic; the figure does not establish tank/pool elevations, component dimensions or installed safety provisions. [PNG version](diagrams/01-system-layout.png).*

### Control and power diagram

![Power and control diagram: WaterGuru and Hubitat command the ZEN05, which powers the adapter and pump](diagrams/03-power-and-control.svg)

*Figure 3. Data, commands and power are separate paths. The three runtime limits are reference settings, not three independent hardware disconnects. [PNG version](diagrams/03-power-and-control.png).*

The diagram shows functional connections, not mains terminals. The ZEN05's local auto-off timer is separate from Hubitat's software timers. All of those controls ultimately depend on the same outlet relay opening; they do not provide protection against every hardware failure.

## Bill of materials

**Affiliate disclosure: As an Amazon Associate I earn from qualifying purchases.** The Amazon links in this BOM are affiliate links. I may receive a commission from qualifying purchases made through them.

All ten supplied Amazon US product links use **hcdp-20**. Quantities below describe the intended build or the listing pack, not a verified purchase count. The repeated bulkhead link in the source list has been consolidated.

| Item | Product and role | Quantity / fit note | Affiliate link |
|---|---|---|---|
| 1 | PEUTIER 5/32-inch thru-bulk plastic hose-barb fittings | Listing: 10 sets. Alternative fitting/vent hardware from the build experiments; not a pass-through for 1/4-inch OD tube. Verify resin, gasket and intended service. | [Amazon: B0CG598P44](https://www.amazon.com/dp/B0CG598P44?tag=hcdp-20) |
| 2 | EISCO 150 mm polypropylene powder funnel | One dedicated chlorine-handling funnel; verify fit and chemical suitability. | [Amazon: B017UYYMKM](https://www.amazon.com/dp/B017UYYMKM?tag=hcdp-20) |
| 3 | Metpure black 1/4-inch OD LLDPE tubing, 25 ft listing | Length depends on the route. Verify tube markings, ID, pressure and chemical rating. | [Amazon: B08FCQD7RB](https://www.amazon.com/dp/B08FCQD7RB?tag=hcdp-20) |
| 4 | Zooz ZEN05 800LR outdoor single Z-Wave plug | One; select the correct electrical supply and Z-Wave region. | [Amazon: B0BHF6MPNK](https://www.amazon.com/dp/B0BHF6MPNK?tag=hcdp-20) |
| 5 | superbobi 1/4-inch UCDBINJ-style injection check valve replacement for Stenner pumps | One shown at the pool outlet; listing contains two pieces. Aftermarket replacement, not an assertion of genuine Stenner manufacture. Confirm tubing connection, thread, cracking pressure and pressure rating. | [Amazon: B0GKFVBMCX](https://www.amazon.com/dp/B0GKFVBMCX?tag=hcdp-20) |
| 6 | Kamoer KCPA600 adjustable-speed peristaltic pump | One, 24 V DC, Norprene N40 configuration. Confirm power adapter is included in the selected package. | [Amazon: B0B9XCPSSS](https://www.amazon.com/dp/B0B9XCPSSS?tag=hcdp-20) |
| 7 | Stenner ST114 1/4-inch suction strainer | One pickup strainer. Do not describe it as a foot/check valve without part-specific evidence. | [Amazon: B00HEASYCA](https://www.amazon.com/dp/B00HEASYCA?tag=hcdp-20) |
| 8 | TR Industrial black UV-resistant 8-inch cable ties | Listing: 100-pack. Use for support; do not substitute them for specified pressure-rated hose clamps. | [Amazon: B01018DC96](https://www.amazon.com/dp/B01018DC96?tag=hcdp-20) |
| 9 | XHF nylon NPT cable-gland assortment | Listing: 48 pieces. Select by cable/tube OD and mounting specification. Electrical glands are not automatically approved chlorine-tank fittings. | [Amazon: B07FPCH344](https://www.amazon.com/dp/B07FPCH344?tag=hcdp-20) |
| 10 | 3M multipurpose reusable respirator, medium listing | Fit and cartridge selection are individual. Verify included cartridge numbers and the approved assembly; see PPE below. | [Amazon: B002MB4MUY](https://www.amazon.com/dp/B002MB4MUY?tag=hcdp-20) |

Product identities were reconciled from the supplied links, screenshots, accessible listings and manufacturer information. The US page for item 5 could not be independently retrieved during this review; its identity is supported by the supplied screenshot and matching ASIN listing. The tubing listing also needs a final check against the supplied spool. A listing title is not a material-compatibility certificate.

### Additional parts and prerequisites

The ten shopping links are not a complete turnkey kit. Also provide:

- A Hubitat hub and WaterGuru sensor with suitable consumables and connectivity.
- A reservoir approved for the chemical, its correct bung seals and manufacturer-approved venting, plus dedicated secondary containment.
- A matched 24 V power adapter if the pump package does not include it.
- Correct adapters between the pump's 4 mm ID tube and the external 1/4-inch OD tube. Record both sides' dimensions and materials.
- A secure mount for the pool-side tube and check valve, with backflow and anti-siphon measures matched to the actual reservoir and pool elevations.
- Suitable outdoor mounting/enclosure, strain relief, tubing supports and a protected electrical supply. Keep electrical equipment away from the reservoir vent and possible leaks.
- Independent no-flow/low-flow feed interruption appropriate to the installation. An app checkbox that circulation runs continuously is not a flow sensor.
- Dedicated measuring equipment, an independent pool test kit, chemical-resistant gloves, splash eye/face protection and the PPE specified by the chemical SDS.

The ZEN05 supplies the local maximum-runtime function described below. Confirm it works on the actual device; an app setting alone does not set the plug's local timer.

### Buying the whole BOM

Use the individual links until exact variants, quantities and optional items are settled. Amazon Business offers a [Shared Cart](https://business.amazon.com/en/solutions/streamlined-purchasing/shared-cart) for organizational purchasing. This README does not promise a public one-click cart or affiliate attribution for a multi-item cart. Do not replace the BOM with an untested cart URL.

## Chemical storage and PPE

### Storage and carboy safety

The earlier illustrations show a ten-foot minimum. The sources reviewed do not establish that as a universal rule for this installation. A chosen separation distance must also prevent incompatible spills from reaching each other. Follow each product label/SDS and applicable storage requirements; distance alone does not prevent spills from mixing.

Give each chemical a dedicated location. Keep liquid chlorine separate from acids, stabilized chlorine tablets and other incompatible products, with separate containment and no common spill route. Store liquid containers low, securely supported, shaded and protected from heat. Prevent rain and contamination from entering dry chemicals. Keep chemicals in original labeled packaging; do not routinely transfer cal-hypo into an ordinary bucket. Follow manufacturer instructions if packaging is damaged. [5, 6]

For the dosing reservoir, verify the container, fittings and venting are approved for the intended chemical service. Do not improvise an airtight closure for a reservoir that requires venting. Keep the reservoir secured against tipping and access by children or animals.

#### Never mix these chemicals

| Combination | Hazard and prevention |
|---|---|
| Liquid chlorine + muriatic acid or sodium bisulfate (“pH Down”) | Can release highly toxic chlorine gas. Maintain separate storage, containment and handling tools. |
| Liquid chlorine + trichlor or dichlor | Can cause violent reaction and toxic gas release. Never mix different chlorine products. |
| Calcium hypochlorite + trichlor/dichlor | Can cause fire or explosion. Never share containers, scoops or feeders. |
| Calcium hypochlorite + organic contamination or improper wetting | Heat, toxic gases and fire can result. Keep dirt, leaves, oils and contaminated tools away; prevent water entering stored product. |

These warnings concern concentrated products and accidental mixing. Follow product labels for pool treatment. Cal-hypo is a reactive oxidizer; keep it separate from other chlorine products and contamination. [6, 7]

If chemicals mix, become hot or release fumes, evacuate, keep others away and call emergency services from a safe location. Do not approach to investigate or attempt to neutralize an unknown mixture.

### Respirator and other PPE selection

**An air-purifying cartridge respirator is not equipment for responding to an accidental chlorine release.** It is unsuitable for unknown concentrations, oxygen deficiency or immediately dangerous conditions. A full-facepiece does not remove these limitations. [8]

Protection depends on the cartridge, compatible facepiece, fit, service life and exposure conditions.

#### Cartridges

- N95 and P100 particulate filters alone do not remove chlorine or hydrogen chloride gas.
- Select a NIOSH-approved cartridge/facepiece combination whose approval and manufacturer instructions cover the specific gases, including chlorine and hydrogen chloride where those hazards are present.
- Gas/vapor cartridges commonly use treated sorbents such as activated carbon. A generic carbon mask or nuisance-odor filter is not equivalent to an approved gas cartridge.
- Add approved particulate filtration when mist/aerosol protection is also required.
- Label color is only an identification aid. Common 3M codes include white for acid gas, yellow for organic vapor/acid gas and olive for multi-gas/vapor. Verify the exact approved chemicals and configuration. Names or approved abbreviations may be used; literal “HCl” and “Cl₂” stamps are not mandatory.
- Confirm the cartridges actually supplied with BOM item 10. The product title alone does not establish suitability. [8, 9]

#### Facepiece and fit

Use a compatible reusable elastomeric half-facepiece or full-facepiece in a size that passes fit testing. Silicone, rubber or thermoplastic elastomer may be used, but material alone is not proof of protection.

Follow the manufacturer's user seal-check procedure every time the respirator is donned. A seal check does not replace fit testing. Facial hair or other interference at the sealing surface prevents a dependable tight fit. [10]

A half-facepiece leaves the eyes exposed. Use chemical splash goggles selected for the SDS and compatible with the respirator. Do not assume splash goggles, including unvented goggles, provide protection from hazardous gas concentrations. A full-facepiece offers integrated eye coverage only within its approved use conditions.

#### Cartridge service life

Establish a manufacturer-supported change schedule for the chemical and conditions of use. Do not wait for odor to indicate replacement. Leave the area immediately if irritation, smell or suspected leakage occurs. Record opening and replacement dates and follow storage instructions. [11]

Respirators do not replace ventilation, spill prevention, suitable gloves or eye/face protection.

## Assembly

### 1. Lay out the reservoir and components

Keep the reservoir secure, shaded and in dedicated containment. Plan access for filling without reaching over live electrical equipment. Use manufacturer-approved closures and venting; the reservoir must not be made airtight if the container/chemical requires venting.

<img src="images/assembled-reservoir.jpg" alt="Blue reservoir beside the covered pump equipment tote" width="400">

*Completed-assembly photo, IMG_0546. The reservoir and adjacent covered equipment are visible. No secondary containment tray is visible in this frame; provide the dedicated containment described in the storage section.*

### 2. Fit the suction pickup

The intended arrangement uses continuous external tubing through a suitable cap fitting down to the suction strainer. Keep the pickup positioned as the equipment instructions require and protect the tube from kinking or abrasion.

![Reservoir cutaway showing a continuous suction tube through a gland, separate fill and vent cap, and internal suction strainer](diagrams/02-reservoir-cutaway.svg)

*Figure 2. The cap roles match the documented build. Internal geometry and gland section are explanatory; use the actual component specifications for mounting holes, seals, venting and pickup placement. [PNG version](diagrams/02-reservoir-cutaway.png).*

<img src="images/npt-gland-pass-through.jpg" alt="Black tubing passing continuously through a white cable gland before cap installation" width="400">

*Tube pass-through detail. The continuous black tube passes through the white cable gland, shown here before installation in the reservoir cap.*

Two connection types must not be confused:

| Connection | How it works | Dimension that matters |
|---|---|---|
| Hose barb | Tube fits **over** the barb; liquid flows inside the fitting. | Tube **inside diameter** and the specified barb/clamp combination. |
| Tube pass-through | An unbroken tube passes through the cap fitting; liquid stays inside the tube. | Tube **outside diameter**, fitting grip range, seal and cap-mounting specification. |

The 5/32-inch barb listing does not mean it has a bore large enough for a 1/4-inch OD tube to pass through. Likewise, “1/4 NPT” describes a nominal pipe-thread size, not a 1/4-inch hole or a guaranteed grip range.

<img src="images/reservoir-pickup-cap.jpg" alt="Suction tube through the installed compression gland in the reservoir cap" width="720">

*Installed pickup-cap assembly, IMG_0547. The black tube passes through the white gland. The photo now documents the finished cap penetration; exact seal materials and the internal pickup are not visible.*

For any tank penetration, obtain chemical-service approval for the body and seal materials. An IP68 ingress rating does not certify compatibility with concentrated sodium hypochlorite or its vapors. The XHF assortment remains in the BOM as supplied hardware; it is not endorsed here as a certified tank fitting.

Use the actual fitting manufacturer's mounting-hole specification and gasket arrangement. Remove the cap for fabrication, keep swarf out of the tank, and inspect the finished seal. The old diagrams' 3/8-inch, 9/16-inch and 1/16-inch drilling instructions have been withdrawn. An oversized hole and a fender washer are not a validated sealing design. Do not use masking tape as a chemical seal.

### 3. Provide filling access and proper venting

The completed assembly uses one bung cap for the suction-tube pass-through and the other as the fill/vent position. IMG_0545 shows a small open hole in the latter cap. Identify each cap by its actual thread and gasket; the photos do not establish a universal left/right or fine/coarse-thread assignment.

<img src="images/reservoir-fill-vent-cap.jpg" alt="Open hole in the center of the separate fill and vent cap" width="400">

*Fill/vent cap, IMG_0545. This records the hole present in the build. No hole diameter, vent-flow capacity or chemical-service approval is inferred from the image.*

Use a closure/vent assembly specified for the tank and chemical. Keep the vent unobstructed and its discharge away from people and electrical equipment. Do not treat a casually drilled 1/16-inch hole as a universal vent design. Disable dosing and isolate pump power before refilling or servicing the pickup, then restore the proper closure and check for leaks.

### 4. Mount and connect the Kamoer pump

Mount the pump in a suitable protected location. Connect the suction and discharge sides according to its indicated direction. Use the correct Norprene pump-head assembly and suitable transitions to the 1/4-inch OD LLDPE line. Do not infer a reducer size from the outside diameter alone.

Set the manual speed knob, record its position and leave it fixed after calibration. Moving the knob changes the delivery rate and invalidates the Hubitat runtime calculation. Recalibrate after changing the tube, plumbing, speed or discharge conditions.

The manufacturer's reference configuration uses a 24 V, 1 A adapter and quotes 180 to 520 mL/min. Those reference figures do not replace a measurement on the installed system. [1]

<img src="images/kamoer-pump-connections.jpg" alt="Kamoer pump front, manual speed knob and connected tubing" width="400">

*Pump connections, IMG_0549. The right-hand adapter has a metal-looking hex nut. It may be external retaining hardware; identify the fitting and actual wetted parts before making an all-plastic-path claim. Do not copy the visible cable tie as a specified pressure seal.*

<img src="images/kamoer-direction-markings.jpg" alt="Owner markings on the pump showing OUT and 200 ml" width="720">

*Owner markings, IMG_0550. OUT identifies the intended outlet. Verify actual flow direction during safe commissioning. The handwritten 200 ml has no time unit and does not replace the 221 mL/min calibration record.*

### 5. Connect the discharge

The completed assembly uses the UCDBINJ-style check valve at the end of the black discharge tube, immersed in the pool near a wall return fitting. The new photos resolve the earlier ambiguity: this build discharges directly into the pool and does not show a threaded connection into the pressurized return pipe.

<img src="images/check-valve-tube-connection.jpg" alt="Check valve with discharge tip at left and tube compression connection at right" width="720">

*Valve and tube connection, IMG_0543. The threaded valve body remains exposed. Liquid enters from the tubing side and leaves through the nozzle, subject to the valve's indicated flow direction.*

<img src="images/check-valve-pool-outlet.jpg" alt="Tube-mounted check valve submerged beside a pool wall return fitting" width="400">

*Pool outlet, IMG_0544. The valve is in the pool near the return; it is not attached to that return fitting. A permanent outlet support is not visible in the photo.*

The [physical dosing diagram](#physical-dosing-diagram) shows this direct-discharge arrangement. Follow the valve's actual flow marking; the drawing does not specify its internal design or a mounting orientation.

Use the valve's specified tubing connection and retention parts, then verify the pump can open the valve and deliver the calibrated volume at the actual outlet depth. Direct discharge removes the pool-return pipe pressure from this arrangement, but valve opening pressure, tube losses and immersion depth still matter.

Secure the tube and valve against movement, abrasion and contact with swimmers. Validate backflow and siphon prevention for the actual tank and pool levels: the photographed submerged outlet has no air gap, and a directional check valve does not by itself establish protection against forward siphoning. Provide the physical no/low-flow feed interruption described in the controls section. [5]

### 6. Connect power

Connect the pump's matched 24 V adapter to the ZEN05 outlet and the adapter's DC output to the pump. Follow all equipment instructions for outdoor use, GFCI protection, mounting and weather exposure. Keep connections protected from spills and chemical vapor. A cable gland does not by itself make the pump or enclosure suitable for outdoor exposure.

The pump has no network setup step. Include and configure the ZEN05 in Hubitat as described below. Keep pump power physically isolated during setup so a test command cannot dose chlorine unexpectedly.

<img src="images/pump-adapter-inside-cover.jpg" alt="Kamoer pump and DC power adapter inside the open black equipment tote" width="720">

*Open equipment cover, IMG_0551. The pump and adapter sit inside a black storage tote; IMG_0548 in the gallery shows the yellow lid closed. The ZEN05 is not visible. This photo does not establish a weatherproof electrical-enclosure rating, sealed cable entries, strain relief or upstream GFCI protection. Specify those features rather than treating a closed storage lid as proof of them.*

## Calibration

Use the pump manufacturer's safe commissioning/calibration method. Keep automation disabled. Record the liquid, knob position, tubing, suction lift, outlet conditions, time and collected volume. A water-only open-outlet test is a useful commissioning record, but it may not match chlorine delivery against a different backpressure. Do not disconnect a pressurized chemical line to measure it.

```text
flow_mL_per_min = collected_mL / elapsed_minutes
runtime_seconds = ceil(required_mL / flow_mL_per_min * 60)
```

The later build record uses **221 mL/min**. The earlier 184.8 mL/min value in the old diagram came from a different calibration/configuration and must not be mixed with it. The new photo shows a handwritten **200 ml** on the pump, without a duration. Retain 221 mL/min as the reported rate until a dated calibration establishes a replacement, then update Hubitat and the housing label together.

| Example dose | Runtime at 221 mL/min, rounded up |
|---|---|
| 237 mL | 65 seconds |
| 1,000 mL | 272 seconds, 4 min 32 s |
| 3,000 mL | 815 seconds, 13 min 35 s |

WaterGuru Dosing Advisor's published calculation uses:

```text
dose_fl_oz = FC_deficit_ppm * (pool_US_gallons / 10000) * 10.7 * (12.5 / product_percent)
dose_mL = dose_fl_oz * 29.5735
```

For its 25,000-gallon, 12.5% example, a 0.3 ppm deficit gives about **237.3 mL**, or **65 seconds** at 221 mL/min. This documents the software's approximation; it is not proof of the achieved FC increase. Verify pool volume, the product's concentration basis and current strength, then check the result with an independent test. Weight-percent sodium hypochlorite and available chlorine concentration are not interchangeable without the appropriate conversion. [3]

## Hubitat setup

### 1. Install the existing integrations

These components already exist; this tutorial does not require inventing new drivers:

| Component | Purpose and source |
|---|---|
| WaterGuru Integration | Creates the pool device and imports chemistry attributes. Follow [its installation instructions](https://github.com/bdwilson/hubitat/tree/master/WaterGuru). |
| WaterGuru Dosing Advisor | Parent and pool-child apps that calculate and control dosing. [Repository and HPM instructions](https://github.com/chsbusch-dot/hubitat-waterguru-dosing-advisor). |
| WaterGuru Dosing Tile | Optional dashboard driver, included in the advisor package. |
| Zooz plug driver | The build record uses **Zooz ZEN Plugs Advanced by jtp10181**, installed through Hubitat Package Manager. Verify current ZEN05 800LR support and fresh switch/power reports. |

In HPM, search for **WaterGuru Dosing Advisor** and install its components. For manual installation, save both apps under **Apps Code** and the tile under **Drivers Code**:

1. [Parent app: WaterGuru-Dosing-Advisor.groovy](https://github.com/chsbusch-dot/hubitat-waterguru-dosing-advisor/blob/main/apps/WaterGuru-Dosing-Advisor.groovy)
2. [Pool child app: WaterGuru-Dosing-Advisor-Child.groovy](https://github.com/chsbusch-dot/hubitat-waterguru-dosing-advisor/blob/main/apps/WaterGuru-Dosing-Advisor-Child.groovy)
3. [Tile driver: WaterGuru-Dosing-Tile.groovy](https://github.com/chsbusch-dot/hubitat-waterguru-dosing-advisor/blob/main/drivers/WaterGuru-Dosing-Tile.groovy)

Then open **Apps → Add User App → WaterGuru Dosing Advisor** and add a pool. Select the WaterGuru source device, the dedicated ZEN05 pump device and the desired notification device. Do not copy someone else's numeric device IDs.

### 2. Configure the ZEN05

Include the plug using the manufacturer's Hubitat instructions, select the appropriate driver and configure it. With the pump safely isolated, check ON/OFF, fresh power reports and power-restoration behavior.

| ZEN05 parameter | Tutorial setting | Purpose |
|---|---|---|
| 2: Auto-off timer | **20 minutes** | Local plug backstop, independent of Hubitat scheduling. |
| 4: Auto-on timer | **0, disabled** | Prevent a recurring autonomous ON timer. |
| 6: Power-failure recovery | **0, always off** | Prevent restart after power restoration. |
| 8: Manual button control | **0, disabled** in the source build | Prevent accidental button starts; provide an accessible physical power disconnect. |
| 13: Disable watt reports | **0, watt reports enabled** | Permit power-based start confirmation. |

These parameter meanings are documented by Zooz. Read back settings and test the local auto-off function safely; driver labels and available settings can vary by firmware. [2]

A minute-scale periodic power report cannot by itself satisfy a 30-second running-power freshness check. Verify that the selected driver answers refreshes and emits repeated reports even when wattage is unchanged. The advisor uses those reports; a cached power value is insufficient. [3]

### 3. Configure the pool advisor

Start in **ADVISORY** mode. Enter the measured pump flow, pool volume, product concentration and actual reservoir inventory. For routine dosing, explicitly check that **SLAM mode is off**; the reviewed software documents a default of on. A routine tutorial should not silently inherit a recovery-treatment target.

| Setting | Reference value / action |
|---|---|
| Pump rate | 221 mL/min for the reported build; use your calibration |
| Pool volume and product strength | 25,000 US gal and 12.5% in the build; verify against your pool and label |
| Reservoir | 15 US gal nominal, about 56,781 mL; initialize to the actual fill, not automatically brim-full |
| FC target | The build used 6.0 ppm; choose appropriately for current CYA and treatment guidance |
| Maximum sample age | 18 hours in the reference configuration |
| Minimum / maximum single dose | 50 / 3,000 mL |
| Maximum daily dose | 3,500 mL |
| One AUTO dose per calendar day | Enabled |
| AUTO window | Source build: 19:30 to 23:00; use the hub's correct local time zone |
| Maximum planned runtime | **14 minutes**, the reviewed software default |
| App emergency cutoff | **15 minutes**, strictly later than the maximum planned runtime |
| ZEN05 local cutoff | **20 minutes**, configured separately on the plug |
| Minimum running power | 3 W software default; confirm against the actual pump's measured power |
| Start-evidence timeout | 20 seconds software default |
| Running-power loss grace | 30 seconds software default |
| pH guard | 6.8 to 8.2 in the reference settings; these are automation bounds, not a recommended swimming range |
| Low-tank alert | Example: 20% of usable initialized capacity |

The runtime ordering is **planned maximum < app emergency cutoff < plug local cutoff**. The old 40-minute app limit with a 20-minute cutoff is superseded in this tutorial. At 221 mL/min, 3,000 mL fits inside 14 minutes. If your pump is slower, calculate the effect rather than loosening every limit.

The 20-minute plug limit is a final runtime backstop, not a daily-volume limiter. At 221 mL/min it could permit about 4,420 mL during a fault, more than the example daily cap. Select all limits for the actual pool and risk, and verify them during commissioning.

### 4. Configure measurement timing and circulation

WaterGuru measures periodically. Its app's measurement schedule, the integration's cloud polling and the dosing advisor's refresh are separate events. The source build intended a 19:20 measurement and 19:45 fetch; confirm actual timestamps before relying on that gap. The reviewed advisor can perform a later refresh if the first fetch brings no new sample. [3, 4]

Select a real circulation interlock where available. The software also offers a confirmation that circulation runs continuously. That records an operator assertion; neither it nor a circulation-switch ON report proves water flow. The build's earlier documentation used the continuous-circulation option. A physical flow cutoff remains unverified in the completed-assembly photos. Record its installation and test result separately from the circulation checkbox.

After installation or an update, open the pool child app and press **Done** while the pump is idle. The current software requires its subscriptions to be refreshed before it will start a dose. Saving can request OFF; do not update during a live dose. [3]

### 5. Understand the dosing sequence

![Dosing sequence: guards, reservation, start confirmation, monitored run, OFF confirmation and fault recovery](diagrams/04-dosing-sequence.svg)

*Figure 4. A dosing-attempt overview for the power-reporting ZEN05. Failed start and stop recovery paths are shown separately from normal operation. [PNG version](diagrams/04-dosing-sequence.png).*

This is an overview of the published controller, not replacement code. It distinguishes an ON request from evidence the pump is drawing power. Power evidence still does not measure chemical flow. Dose volume and tank level remain estimates derived from calibrated rate and observed run time.

Use **APPROVAL** for supervised dosing once commissioning is complete. Enable **AUTO** only after validating the installation and failure behavior. Advisory calculations, previews and scheduled summaries do not start the pump. Do not run a separate Rule Machine timer or other dosing automation against the same outlet without a deliberate coordination plan. The legacy 60-second rule in earlier notes is not part of this tutorial's control path. [3]

### 6. Dashboard and optional history

Add the dosing tile device to the Hubitat dashboard and select the **Attribute** template with `recommendation`, `tileHtml` or `status`. Notifications can report calculated dose, confirmed run status, faults and tank estimate.

The source work includes an InfluxDB/Grafana history integration. It is optional for dosing. Preserve the advisor's published attribute names, including `lastDoseMl`, `lastDoseEpochMs`, `lastDoseRuntimeSeconds`, `tankRemainingMl`, `tankCapacityMl`, `freeChlorine` and `targetFreeChlorine`. Do not sum repeated readings as separate doses; corrections can reuse the same dose timestamp. Follow the current software documentation for the complete telemetry contract. [3]

## Commissioning and operation

### Before introducing chlorine

Use a manufacturer-approved safe test setup and keep the chemical source isolated. Confirm:

- No leaks, correct pump direction, secure tubing and functioning backflow/siphon protection.
- A repeatable calibration under representative outlet conditions.
- Normal start evidence, planned stop and a fresh OFF report.
- Plug-local auto-off with Hubitat unavailable, and OFF after a power cycle.
- The feed stops when circulation is lost, including the physical interlock.
- Stale/duplicate samples, missing power reports, excess dose/runtime and low inventory prevent or stop dosing as documented.
- A failed or uncertain attempt cannot silently cause a duplicate automatic dose.
- Hub restart, code update, manual disable and emergency disconnect behave as expected.

Do not induce a hazardous chemical condition to test a guard. Use isolated hardware or controlled inputs, with the pump disconnected from the chemical supply. Record the app version, plug firmware, driver, settings and test outcome.

### Normal operation and refilling

Check the independent water test, sample timestamp, reservoir and tubing routinely. Let the current chemistry and product instructions determine treatment, rather than blindly copying the example 6.0 ppm target.

Before refilling, disable dosing and isolate power. Use dedicated clean handling tools and the SDS-specified PPE. Restore the approved closure/vent and check for leaks. Record how much was actually added. Use **Mark chlorine tank full** only when the configured usable volume is truly present; use the advisor's inventory-adjustment controls for a partial refill. An estimate does not detect a physical leak or empty pickup.

If a dose is uncertain, investigate and independently test before correcting inventory or dosing again. Do not acknowledge a pump fault merely to force another attempt. Never add acids or tablet chlorine to this reservoir or dosing line.

## Maintenance and troubleshooting

Inspect the Norprene tube, connections, reservoir, vent, strainer and outlet for wear, restriction and leaks. Follow the component manufacturers' replacement intervals and recalibrate after service. No blanket 6 to 12 month tubing life is claimed for this build.

| Symptom | Check |
|---|---|
| Plug ON requested, pump does not run | Fresh switch/power reports, plug connection, DC adapter and pump knob; review start-fault reason. |
| Pump runs but liquid delivery is low | Actual volume, suction air leak, pickup level, worn tube, kink or outlet restriction. Wattage alone cannot establish flow. |
| Healthy run trips a power-evidence fault | Driver refresh behavior, unchanged reports, Z-Wave communication and the measured power threshold. |
| No AUTO dose after an update | Open the pool child app and press Done while idle; check mode, subscriptions and guard reason. |
| Dose is blocked | Sample age, already-used sample/day, window, circulation, pH, runtime/volume limit or inventory. |
| Tank estimate drifts | Refill record, calibration, partial/uncertain runs and manual outlet operation. Reconcile against physical inventory. |
| Liquid moves while power is off | Isolate safely and investigate siphon/backflow protection; do not rely on another software OFF. |

The completed assembly is documented in the [ten-photo gallery](docs/assembly-photos.md). Image pixels are unchanged; location and camera metadata were removed from the publication copies. The old AI-generated diagrams have been replaced by four editable SVG diagrams, with matching PNG exports and a reproducible source script. See the [diagram gallery](docs/diagrams.md). See [image review](docs/image-review.md) for the image-by-image disposition and remaining verification items.

## Sources

1. [Kamoer KCPA600 technical specifications](https://www.kamoer.com/us/product/params.html?id=10005) and [product overview](https://www.kamoer.com/us/product/detail.html?id=10005).
2. [Zooz ZEN05 advanced settings](https://www.support.getzooz.com/kb/article/1118-zen05-outdoor-plug-advanced-settings/).
3. [WaterGuru Dosing Advisor documentation at reviewed commit](https://github.com/chsbusch-dot/hubitat-waterguru-dosing-advisor/blob/3dd9a7192682aab1ecde6031e55bdb6431e5b925/README.md).
4. [WaterGuru measurement workflow](https://support.waterguru.com/hc/en-us/articles/1260804808989-How-do-I-perform-a-Water-Measurement-of-my-pool-water).
5. [CDC: Pool Chemical Safety](https://www.cdc.gov/healthy-swimming/toolkit/pool-chemical-safety.html).
6. [EPA: Safe Storage and Handling of Swimming Pool Chemicals](https://nepis.epa.gov/Exe/ZyPURL.cgi?Dockey=P1002ZKY.TXT).
7. [EPA: Swimming Pool Chemicals advisory](https://www.epa.gov/sites/production/files/2013-11/documents/chlor.pdf).
8. [3M Organic Vapor/Acid Gas Cartridge 6003: approved hazards and limitations](https://www.3m.com/3M/en_US/p/dc/v000093369/).
9. [3M Cartridge and Filter Guide](https://multimedia.3m.com/mws/media/565214O/cartridge-and-filter-guide-brochure-70-0714-8870-7.pdf).
10. [NIOSH: User Seal Check](https://stacks.cdc.gov/view/cdc/53108).
11. [3M Cartridge Change FAQs](https://multimedia.3m.com/mws/media/259820O/3m-cartridge-change.pdf).

Build-specific evidence: the author's supplied BOM, original manual, completed-assembly photos IMG_0542 to IMG_0551, earlier parts photos and calibration notes, and October 3, 2026 handoff. Older settings have been identified where they conflict with the current public software documentation. This review did not operate the pump or inspect the live hub.
