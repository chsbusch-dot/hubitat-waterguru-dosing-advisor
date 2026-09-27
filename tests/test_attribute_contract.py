"""Enforce the WaterGuru Dosing Tile attribute contract.

An external historian (the InfluxDB/Grafana collector in Waterguru-Grafana-Chart)
reads this device's attributes **by exact string**. A rename does not raise
anything: it produces empty panels, because the collector fails soft by design. So
the failure mode this test exists to prevent is a silent one.

This runs in two ways:

    python tests/test_attribute_contract.py     # standalone, no dependencies
    pytest tests/test_attribute_contract.py     # or under pytest in CI

It is deliberately plain: no third-party imports, no fixtures, no network. The
contract is a list of strings and the sources are text on disk, so the whole check
is regex over two files.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DRIVER = ROOT / "drivers" / "WaterGuru-Dosing-Tile.groovy"
CHILD = ROOT / "apps" / "WaterGuru-Dosing-Advisor-Child.groovy"

# The human-facing attributes. Dashboards bind to these.
HUMAN_ATTRIBUTES = [
    "status",
    "recommendation",
    "detail",
    "tileHtml",
    "lastCalc",
]

# The machine-readable telemetry an external historian depends on, with the unit
# the app attaches. Changing a name or a unit here is a breaking change for the
# collector, the Grafana panels, and any stored history -- so it must be a
# deliberate edit to this list, in the same commit that changes the collector.
TELEMETRY_ATTRIBUTES = {
    "lastDoseMl": "mL",
    "lastDoseEpochMs": None,
    "lastDoseRuntimeSeconds": "s",
    "tankCapacityMl": "mL",
    "tankRemainingMl": "mL",
    "tankPercent": "%",
    "tankRunwayDays": "days",
    "freeChlorine": "ppm",
    "targetFreeChlorine": "ppm",
    "lastCalcEpochMs": None,
}

EXPECTED_ATTRIBUTES = set(HUMAN_ATTRIBUTES) | set(TELEMETRY_ATTRIBUTES)


def _declared_attributes() -> set[str]:
    """Attribute names declared in the driver's metadata block."""
    return set(re.findall(r'attribute\s+"([^"]+)"', DRIVER.read_text()))


def _sent_attributes() -> set[str]:
    """Attribute names the app sends with sendEvent(name: "...")."""
    return set(re.findall(r'sendEvent\(\s*name:\s*"([^"]+)"', CHILD.read_text()))


def _sent_units() -> dict[str, str | None]:
    """attribute -> unit, for every sendEvent call that specifies one.

    Matches per line to the end of the call rather than stopping at the first ')' —
    several calls wrap their value in a helper, e.g. `value: toBD(last.mlRaw), unit: "mL"`,
    and a naive non-greedy group would stop inside toBD() and never reach the unit.
    """
    out: dict[str, str | None] = {}
    for call in re.findall(r"sendEvent\((.*?)\)\s*$", CHILD.read_text(), re.M):
        name = re.search(r'name:\s*"([^"]+)"', call)
        if not name:
            continue
        unit = re.search(r'unit:\s*"([^"]*)"', call)
        out[name.group(1)] = unit.group(1) if unit else None
    return out


# --------------------------------------------------------------------------- checks


def test_the_expected_contract_is_exactly_declared():
    """The driver declares exactly the attributes we intend to support.

    Both directions matter. A removal breaks the collector; an addition means a
    new attribute exists that the contract, the collector and the docs do not
    mention yet, which should be a deliberate edit here.
    """
    declared = _declared_attributes()
    missing = EXPECTED_ATTRIBUTES - declared
    unexpected = declared - EXPECTED_ATTRIBUTES
    assert not missing, f"driver no longer declares: {sorted(missing)}"
    assert not unexpected, (
        f"driver declares attributes the contract does not list: {sorted(unexpected)} "
        "-- add them to TELEMETRY_ATTRIBUTES/HUMAN_ATTRIBUTES and to the collector"
    )


def test_every_attribute_the_app_sends_is_declared():
    """An undeclared attribute is one a future hub restore could still deliver,
    but a dashboard cannot select it and a typo in the app would go unnoticed."""
    undeclared = _sent_attributes() - _declared_attributes()
    assert not undeclared, f"the app sends attributes the driver does not declare: {sorted(undeclared)}"


def test_every_telemetry_attribute_is_actually_published():
    """Declared but never sent is the quieter half of the same bug: the panel
    exists, the attribute is selectable, and it stays empty forever."""
    unpublished = set(TELEMETRY_ATTRIBUTES) - _sent_attributes()
    assert not unpublished, f"declared telemetry the app never sends: {sorted(unpublished)}"


def test_telemetry_units_are_unchanged():
    """Units are part of the contract: a mL value silently becoming L would not
    error anywhere, it would just plot 1000x wrong."""
    sent = _sent_units()
    wrong = []
    for name, expected in TELEMETRY_ATTRIBUTES.items():
        if expected is None:
            continue  # deliberately unitless (epoch millis)
        actual = sent.get(name)
        if actual != expected:
            wrong.append(f"{name}: expected {expected!r}, app sends {actual!r}")
    assert not wrong, "; ".join(wrong)


def test_the_dose_attributes_the_collector_reads_are_present():
    """The subset the collector reads for chlorine_dose, named explicitly so a
    failure says which measurement breaks rather than dumping the whole set."""
    for name in ("lastDoseMl", "lastDoseEpochMs", "lastDoseRuntimeSeconds"):
        assert name in _declared_attributes(), f"{name} is missing; chlorine_dose stops updating"


# --------------------------------------------------------------------------- runner


def main() -> int:
    checks = [
        test_the_expected_contract_is_exactly_declared,
        test_every_attribute_the_app_sends_is_declared,
        test_every_telemetry_attribute_is_actually_published,
        test_telemetry_units_are_unchanged,
        test_the_dose_attributes_the_collector_reads_are_present,
    ]
    failures = 0
    for check in checks:
        try:
            check()
        except AssertionError as exc:
            failures += 1
            print(f"FAIL  {check.__name__}\n      {exc}")
        else:
            print(f"ok    {check.__name__}")
    print(f"\n{len(checks) - failures}/{len(checks)} checks passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
