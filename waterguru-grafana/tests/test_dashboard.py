"""Structural checks on the committed Grafana dashboard.

The dashboard is file-provisioned and Grafana fails *silently* on invalid JSON (it keeps
serving the previous version), so a parse/structure test is the only cheap guard. The dose
vs runtime panel in particular is checked for its axis override: the query ``keep``\\ s only
``_time``/``_value``, so a ``byName`` matcher on ``runtime_seconds`` can never match and the
right-hand seconds axis silently stays in mL.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

GRAFANA = Path(__file__).resolve().parents[1] / "grafana"
# The provisioned dashboard is deployment config and stays out of a public copy of this repo
# (see .public-exclude); there the importable export is the dashboard these checks guard.
DASHBOARD = next(
    path
    for path in (GRAFANA / "waterguru-dashboard.json", GRAFANA / "waterguru-dashboard.import.json")
    if path.exists()
)


def _dashboard() -> dict[str, Any]:
    with open(DASHBOARD, encoding="utf-8") as fh:
        data: dict[str, Any] = json.load(fh)
    return data


def test_dashboard_json_parses() -> None:
    data = _dashboard()
    assert data["uid"] == "waterguru-pool"
    assert data["title"]
    assert data["panels"]


def test_panel_ids_are_unique() -> None:
    ids = [panel["id"] for panel in _dashboard()["panels"]]
    assert len(ids) == len(set(ids))
    assert all(isinstance(panel_id, int) for panel_id in ids)


def test_panels_stay_inside_the_grid_and_do_not_overlap() -> None:
    panels = [panel for panel in _dashboard()["panels"] if "gridPos" in panel]
    rects = []
    for panel in panels:
        grid = panel["gridPos"]
        assert grid["x"] + grid["w"] <= 24, panel["title"]
        assert grid["y"] >= 0 and grid["h"] > 0 and grid["w"] > 0, panel["title"]
        rects.append((grid["x"], grid["y"], grid["x"] + grid["w"], grid["y"] + grid["h"]))
    for i, first in enumerate(rects):
        for second in rects[i + 1 :]:
            assert (
                first[2] <= second[0]
                or second[2] <= first[0]
                or first[3] <= second[1]
                or second[3] <= first[1]
            ), f"overlapping panels at {first} and {second}"


def test_dose_runtime_panel_uses_a_frame_ref_matcher_for_the_seconds_axis() -> None:
    panel = next(p for p in _dashboard()["panels"] if p.get("id") == 24)
    assert panel["title"] == "Dose vs commanded pump runtime"

    ref_ids = [target["refId"] for target in panel["targets"]]
    assert ref_ids == ["A", "B"]
    volume_query, runtime_query = (target["query"] for target in panel["targets"])
    assert '_field == "volume_ml"' in volume_query
    assert '_field == "runtime_seconds"' in runtime_query

    # Both queries drop `_field` via keep(columns: ["_time", "_value"]), so a byName matcher
    # on "runtime_seconds" is unreliable. The override must match the frame by refId...
    runtime_field_retained = "_field" in _kept_columns(runtime_query)
    overrides = panel["fieldConfig"]["overrides"]
    matchers = [override["matcher"] for override in overrides]
    if runtime_field_retained:
        assert any(
            m["id"] == "byName" and m["options"] == "runtime_seconds" for m in matchers
        ) or any(m["id"] == "byFrameRefID" and m["options"] == "B" for m in matchers)
    else:
        assert {"id": "byFrameRefID", "options": "B"} in matchers, matchers
        assert not any(m["id"] == "byName" for m in matchers), matchers

    # ...and the override must keep moving query B to the right axis in seconds.
    runtime_override = next(
        override
        for override in overrides
        if override["matcher"] == {"id": "byFrameRefID", "options": "B"}
    )
    properties = {prop["id"]: prop["value"] for prop in runtime_override["properties"]}
    assert properties["unit"] == "s"
    assert properties["custom.axisPlacement"] == "right"

    # The panel's own default unit/visualisation are unchanged (mL, bars).
    assert panel["fieldConfig"]["defaults"]["unit"] == "suffix:mL"
    assert panel["fieldConfig"]["defaults"]["custom"]["drawStyle"] == "bars"


def test_dose_panels_name_their_series() -> None:
    # keep(columns: ["_time", "_value"]) leaves Grafana nothing to name a series by, so
    # without a displayName the legend and tooltip read "Value" for every series.
    panels = {p["id"]: p for p in _dashboard()["panels"]}
    assert panels[19]["fieldConfig"]["defaults"]["displayName"] == "Dose volume"

    names = {
        override["matcher"]["options"]: prop["value"]
        for override in panels[24]["fieldConfig"]["overrides"]
        if override["matcher"]["id"] == "byFrameRefID"
        for prop in override["properties"]
        if prop["id"] == "displayName"
    }
    assert names == {"A": "Dose volume", "B": "Commanded runtime"}


def _kept_columns(query: str) -> str:
    marker = "keep(columns: ["
    start = query.find(marker)
    assert start != -1, query
    end = query.find("]", start)
    return query[start:end]


def test_every_override_matcher_uses_a_known_matcher_id() -> None:
    known = {"byName", "byFrameRefID", "byRegexp", "byType"}
    for panel in _dashboard()["panels"]:
        for override in panel.get("fieldConfig", {}).get("overrides", []) or []:
            assert override["matcher"]["id"] in known, override
