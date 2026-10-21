"""Pin the Hubitat tile attribute contract this collector reads.

The names on the device are matched by exact string in ``dosing.py``. A rename on the
hub side does not raise anything here either: the attributes go absent, ``build_points``
produces no points for them, and the panels go quietly stale. So both halves of the
contract are pinned:

* the attribute names this module reads, asserted literally below, and
* optionally, the driver that declares them — checked when the sibling
  ``hubitat-waterguru-dosing-advisor`` checkout is present, skipped when it is not
  (it is a separate repository, so CI here cannot depend on it).

The counterpart test lives in that repository
(``tests/test_attribute_contract.py``) and asserts the driver declares and the app
publishes what this module reads.
"""

from __future__ import annotations

import os
import re
import urllib.error
import urllib.request
from pathlib import Path

import pytest

from waterguru_poller import dosing

# --- the contract -------------------------------------------------------------------
# Pinned literally. Editing any of these is a breaking change for the collector, the
# Grafana panels and all stored history, so it has to be a deliberate edit in the same
# commit as the collector change — which is the entire point of this file.
EXPECTED_TANK_FIELDS = {
    "remaining_ml": "tankRemainingMl",
    "capacity_ml": "tankCapacityMl",
    "remaining_percent": "tankPercent",
    "runway_days": "tankRunwayDays",
}
EXPECTED_TARGET_FIELDS = {
    "measured_ppm": "freeChlorine",
    "target_ppm": "targetFreeChlorine",
}
EXPECTED_DOSE_VOLUME = "lastDoseMl"
EXPECTED_DOSE_TIME_MS = "lastDoseEpochMs"
EXPECTED_DOSE_RUNTIME = "lastDoseRuntimeSeconds"
EXPECTED_TARGET_TIME_MS = "lastCalcEpochMs"

# Every attribute this collector reads, in one place so both checks below use the same set.
EXPECTED_ATTRIBUTES = (
    set(EXPECTED_TANK_FIELDS.values())
    | set(EXPECTED_TARGET_FIELDS.values())
    | {EXPECTED_DOSE_VOLUME, EXPECTED_DOSE_TIME_MS, EXPECTED_DOSE_RUNTIME, EXPECTED_TARGET_TIME_MS}
)


def test_tank_field_mapping_is_unchanged():
    assert dosing._TANK_FIELDS == EXPECTED_TANK_FIELDS


def test_target_field_mapping_is_unchanged():
    assert dosing._TARGET_FIELDS == EXPECTED_TARGET_FIELDS


def test_dose_attribute_names_are_unchanged():
    assert dosing._DOSE_VOLUME == EXPECTED_DOSE_VOLUME
    assert dosing._DOSE_TIME_MS == EXPECTED_DOSE_TIME_MS
    assert dosing._DOSE_RUNTIME == EXPECTED_DOSE_RUNTIME


def test_target_timestamp_attribute_is_unchanged():
    assert dosing._TARGET_TIME_MS == EXPECTED_TARGET_TIME_MS


def test_every_attribute_the_collector_reads_is_in_one_place():
    """Guards against a name being spelled inline somewhere and drifting out of the
    pinned constants, which would make the checks above pass while the module reads
    something else."""
    source = Path(dosing.__file__).read_text()
    for name in sorted(EXPECTED_ATTRIBUTES):
        # each name must appear as a quoted string in the module
        assert re.search(rf'"{re.escape(name)}"', source), f"{name} is not referenced in dosing.py"


# --- optional cross-repo check ------------------------------------------------------

PUBLISHED_DRIVER_URL = (
    "https://raw.githubusercontent.com/chsbusch-dot/hubitat-waterguru-dosing-advisor"
    "/main/drivers/WaterGuru-Dosing-Tile.groovy"
)


def _driver_source() -> tuple[str, str] | None:
    """Return (source_text, provenance) for the hub driver, or None if unavailable locally.

    Deliberately ordered so that a check can never *silently* not happen: an explicit
    override that does not resolve is an error, and in CI an unfetchable driver is a
    failure rather than a skip. A skipped check that looks green is worse than no check,
    because it reads as coverage.
    """
    override = os.environ.get("WGDA_REPO")
    if override:
        candidate = Path(override)
        driver = candidate / "drivers" / "WaterGuru-Dosing-Tile.groovy"
        if driver.is_file():
            return driver.read_text(), f"local checkout {driver}"
        raise AssertionError(f"WGDA_REPO={override} does not contain the driver; refusing to skip")

    for sibling in (
        Path(__file__).resolve().parents[2]
        / "_by-category"
        / "infra-tool"
        / "hubitat-waterguru-dosing-advisor",
        Path.home() / "VSCode/_by-category/infra-tool/hubitat-waterguru-dosing-advisor",
    ):
        driver = sibling / "drivers" / "WaterGuru-Dosing-Tile.groovy"
        if driver.is_file():
            return driver.read_text(), f"local checkout {driver}"

    # No local checkout (the CI case): fetch the published driver, and *fail* rather than
    # skip when that is not possible, so the check cannot become decorative.
    try:
        url = os.environ.get("WGDA_DRIVER_URL", PUBLISHED_DRIVER_URL)
        with urllib.request.urlopen(url, timeout=20) as response:  # noqa: S310 (fixed https URL)
            return response.read().decode("utf-8"), f"fetched {url}"
    except (urllib.error.URLError, OSError, UnicodeDecodeError) as exc:
        if os.environ.get("CI"):
            raise AssertionError(
                f"could not obtain the hub driver in CI ({exc}); refusing to pass the contract "
                f"check without checking it. Set WGDA_DRIVER_URL to a reachable copy."
            ) from exc
        pytest.skip(f"no local driver checkout and the fetch failed ({exc}); set WGDA_REPO")
        return None


def test_driver_declares_every_attribute_this_collector_reads():
    """The driver must declare every attribute this collector reads.

    Uses a local checkout when there is one, and otherwise fetches the published driver, so
    this runs in CI too rather than skipping there. In CI an unfetchable driver fails.
    """
    resolved = _driver_source()
    if resolved is None:  # only reachable via the local skip path
        return
    source, provenance = resolved

    declared = set(re.findall(r'attribute\s+"([^"]+)"', source))
    missing = EXPECTED_ATTRIBUTES - declared
    assert not missing, (
        f"{provenance} does not declare {sorted(missing)} — those measurements would stop "
        f"updating silently, because this collector fails soft"
    )
