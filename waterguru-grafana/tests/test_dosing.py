"""Tests for the hub-sourced chlorine dose + tank collector."""

from __future__ import annotations

import json
from pathlib import Path

import pytest
import requests

from waterguru_poller.dosing import (
    EVENT_PAGE_LIMIT,
    EVENT_RECOVERY_DAYS,
    MAX_DOSE_VOLUME_ML,
    MAX_PERCENT,
    MAX_PPM,
    MAX_RUNTIME_SECONDS,
    MAX_RUNWAY_DAYS,
    EventCursor,
    HubError,
    build_points,
    collect,
    event_time_ns,
    events_to_points,
    fetch_device_attributes,
    parse_attributes,
    parse_event_page,
    parse_events,
)

DEVICE = "101"
DOSE_MARKER = "lastDoseEpochMs"
DOSE_VOLUME = "lastDoseMl"
DOSE_RUNTIME = "lastDoseRuntimeSeconds"

# The live tile's recorded dose time (epoch ms), 2026-09-25T19:45:24.739Z.
REAL_DOSE_MS = 1790390724739

# Realistic tile attributes (values taken from the live device).
ATTRS = {
    "lastDoseMl": 237.3273375,
    "lastDoseEpochMs": REAL_DOSE_MS,
    "tankRemainingMl": 56543.8494225,
    "tankCapacityMl": 56781.17676,
    "tankPercent": 99.5820316678,
    "tankRunwayDays": 52.4883227879,
    "status": "RED",
    "recommendation": "Add 1.0 cups chlorine",
}


def _mcp_envelope(attributes: dict) -> dict:
    """Wrap a device payload the way the hub MCP endpoint does."""
    device = {"id": DEVICE, "attributes": [{"name": k, "value": v} for k, v in attributes.items()]}
    return {
        "jsonrpc": "2.0",
        "id": 1,
        "result": {"content": [{"type": "text", "text": json.dumps(device)}]},
    }


def _events_envelope(events: list, **extra: object) -> dict:
    """Wrap a device-events payload the way the hub MCP endpoint does."""
    return {
        "jsonrpc": "2.0",
        "id": 1,
        "result": {"content": [{"type": "text", "text": json.dumps({"events": events, **extra})}]},
    }


# --------------------------------------------------------------------------- parsing


def test_parse_attributes_unwraps_mcp_envelope():
    assert parse_attributes(_mcp_envelope(ATTRS))["lastDoseMl"] == pytest.approx(237.3273375)


def test_parse_attributes_accepts_plain_attributes_list():
    payload = {"attributes": [{"name": "tankPercent", "value": 42.5}]}
    assert parse_attributes(payload) == {"tankPercent": 42.5}


def test_parse_attributes_accepts_attributes_mapping():
    assert parse_attributes({"attributes": {"tankPercent": 1.0}}) == {"tankPercent": 1.0}


def test_parse_attributes_raises_on_jsonrpc_error():
    with pytest.raises(HubError, match="hub returned an error"):
        parse_attributes({"error": {"message": "nope"}})


def test_parse_attributes_raises_when_no_attributes():
    with pytest.raises(HubError, match="no attributes"):
        parse_attributes({"id": DEVICE})


def test_parse_attributes_raises_on_non_object_payload():
    with pytest.raises(HubError, match="expected a JSON object"):
        parse_attributes([1, 2, 3])


# --------------------------------------------------------------------------- event decoding


def test_parse_events_accepts_envelope_and_alternate_keys():
    events = [{"name": "lastDoseMl", "value": 1.0, "date": "2026-09-25T19:45:24+0000"}]
    assert parse_events(_events_envelope(events)) == events
    assert parse_events({"events": events}) == events
    assert parse_events({"deviceEvents": events}) == events
    assert parse_events({"data": events}) == events
    assert parse_events({"result": events}) == events
    assert parse_events(events) == events


def test_parse_events_drops_non_objects_and_accepts_empty():
    assert parse_events({"events": [{"name": "x"}, "junk", None]}) == [{"name": "x"}]
    assert parse_events({"events": []}) == []


def test_parse_events_raises_on_unrecognised_shape():
    with pytest.raises(HubError, match="no event list"):
        parse_events({"job": "done"})


def test_parse_event_page_reads_the_upstream_envelope():
    """Current upstream returns events + count + sinceMode + sinceTimestamp + the bound."""
    payload = _events_envelope(
        DOSE_EVENTS,
        count=2,
        sinceMode="since",
        sinceTimestamp="2026-09-19T00:00:00.000Z",
        since="2026-09-19T00:00:00.000Z",
    )
    page = parse_event_page(payload)
    assert page.events == DOSE_EVENTS
    assert page.count == 2
    assert page.since_mode == "since"
    assert page.since_timestamp == "2026-09-19T00:00:00.000Z"


def test_parse_event_page_bounds_and_defaults():
    assert parse_event_page(_events_envelope([], count=0)).count == 0
    assert parse_event_page(_events_envelope([], count="7")).count is None
    assert parse_event_page({"events": [{"name": "x"}]}).count is None


def test_parse_events_accepts_the_direct_object_content_envelope():
    """``result.content[0]`` may carry the fields directly instead of a JSON string."""
    payload = {
        "jsonrpc": "2.0",
        "id": 1,
        "result": {"content": [{"type": "json", "events": DOSE_EVENTS, "count": 3}]},
    }
    assert parse_events(payload) == DOSE_EVENTS


def test_parse_attributes_accepts_the_direct_object_content_envelope():
    payload = {
        "jsonrpc": "2.0",
        "id": 1,
        "result": {
            "content": [
                {"type": "json", "attributes": [{"name": "tankPercent", "value": 42.5}]}
            ]
        },
    }
    assert parse_attributes(payload) == {"tankPercent": 42.5}


def test_event_time_accepts_epoch_ms_epoch_seconds_and_iso():
    from_ms = event_time_ns({"epoch": REAL_DOSE_MS})
    from_seconds = event_time_ns({"epoch": REAL_DOSE_MS / 1000})
    from_iso = event_time_ns({"date": "2026-09-25T19:45:24+0000"})
    assert from_ms == REAL_DOSE_MS * 1_000_000
    assert from_seconds == pytest.approx(REAL_DOSE_MS * 1_000_000, abs=1_000_000)
    assert from_iso is not None and from_iso > 0


@pytest.mark.parametrize(
    "event",
    [
        {},
        {"epoch": 0},
        {"epoch": -5},
        {"epoch": float("nan")},
        {"epoch": float("inf")},
        {"epoch": 1},  # 1970, an impossible dose time
        {"date": "not-a-date"},
        {"date": "1970-01-01T00:00:01Z"},
    ],
)
def test_event_time_rejects_unusable_times(event: dict) -> None:
    assert event_time_ns(event) is None


# --------------------------------------------------------------------------- point building


def test_build_points_dose_uses_original_dose_time():
    points = build_points(ATTRS, DEVICE, now_ns=111)
    dose = next(p for p in points if p.measurement == "chlorine_dose")
    assert dose.tags == {"device_id": DEVICE}
    assert dose.fields == {"volume_ml": pytest.approx(237.3273375)}
    # Point time must be the recorded dose time, not now.
    assert dose.timestamp_ns == REAL_DOSE_MS * 1_000_000


def test_build_points_tank_snapshot_at_now():
    points = build_points(ATTRS, DEVICE, now_ns=999)
    tank = next(p for p in points if p.measurement == "chlorine_tank")
    assert tank.timestamp_ns == 999
    assert tank.fields["remaining_ml"] == pytest.approx(56543.8494225)
    assert tank.fields["remaining_percent"] == pytest.approx(99.5820316678)
    assert tank.fields["runway_days"] == pytest.approx(52.4883227879)


def test_missing_fields_are_absent_not_zero():
    """The chart spec requires an absent field to stay empty, not become 0."""
    # Some tank fields present, others absent.
    points = build_points({"tankRemainingMl": 500.0, "tankPercent": 33.0}, DEVICE, now_ns=1)
    tank = next(p for p in points if p.measurement == "chlorine_tank")
    # Absent attributes are carried as None...
    assert tank.fields["capacity_ml"] is None
    assert tank.fields["runway_days"] is None
    # ...and serialization drops them, so no zero is ever written.
    line = tank.to_line()
    assert "capacity_ml" not in line
    assert "runway_days" not in line
    assert "=0" not in line
    assert line.startswith("chlorine_tank,device_id=101 ")
    assert "remaining_ml=500" in line


def test_no_tank_point_when_no_tank_fields_exist():
    points = build_points({"lastDoseMl": 10.0, "lastDoseEpochMs": REAL_DOSE_MS}, DEVICE, now_ns=1)
    assert [p.measurement for p in points] == ["chlorine_dose"]


def test_runway_days_may_be_absent_on_its_own():
    attrs = dict(ATTRS)
    del attrs["tankRunwayDays"]
    tank = next(
        p for p in build_points(attrs, DEVICE, now_ns=1) if p.measurement == "chlorine_tank"
    )
    assert "runway_days" not in tank.to_line()
    assert "remaining_ml=" in tank.to_line()


# ----------------------------------------------------------- commanded runtime + FC target


def test_dose_carries_commanded_runtime_when_present():
    """lastDoseRuntimeSeconds is the runtime the volume was derived from."""
    attrs = dict(ATTRS, lastDoseRuntimeSeconds=65)
    dose = next(
        p for p in build_points(attrs, DEVICE, now_ns=1) if p.measurement == "chlorine_dose"
    )
    assert dose.fields["volume_ml"] == pytest.approx(237.3273375)
    assert dose.fields["runtime_seconds"] == pytest.approx(65.0)
    # Same single point, still at the original dose time -- adding the field must not
    # split the dose into two series or move its timestamp.
    assert dose.timestamp_ns == REAL_DOSE_MS * 1_000_000
    assert "runtime_seconds=65" in dose.to_line()


def test_dose_runtime_absent_stays_absent_not_zero():
    """A driver without the runtime attribute must not record 0 seconds."""
    attrs = {k: v for k, v in ATTRS.items() if k != "lastDoseRuntimeSeconds"}
    dose = next(
        p for p in build_points(attrs, DEVICE, now_ns=1) if p.measurement == "chlorine_dose"
    )
    assert "runtime_seconds" not in dose.fields
    assert "runtime_seconds" not in dose.to_line()


def test_target_point_pairs_reading_with_target():
    """The advisor's FC target is stored with the reading it was computed against."""
    attrs = {"freeChlorine": 5.7, "targetFreeChlorine": 6.0}
    target = next(
        p for p in build_points(attrs, DEVICE, now_ns=4242) if p.measurement == "chlorine_target"
    )
    assert target.tags == {"device_id": DEVICE}
    assert target.fields["measured_ppm"] == pytest.approx(5.7)
    assert target.fields["target_ppm"] == pytest.approx(6.0)
    # No lastCalcEpochMs on the tile -> documented collection-time fallback.
    assert target.timestamp_ns == 4242
    assert target.to_line().startswith("chlorine_target,device_id=101 ")


def test_target_point_uses_last_calc_epoch_when_present():
    """A numeric lastCalcEpochMs timestamps the pair at the calculation instant."""
    attrs = {
        "freeChlorine": 5.7,
        "targetFreeChlorine": 6.0,
        "lastCalcEpochMs": REAL_DOSE_MS,
    }
    target = next(
        p for p in build_points(attrs, DEVICE, now_ns=4242) if p.measurement == "chlorine_target"
    )
    assert target.timestamp_ns == REAL_DOSE_MS * 1_000_000
    assert "5.7" in target.to_line()


def test_target_point_falls_back_when_last_calc_epoch_is_unusable():
    """An invalid/implausible/absent lastCalcEpochMs keeps the collection-time fallback."""
    for bad in (None, "Fri Sep 25 19:45:24 PDT 2026", 1, 0, -1, float("nan")):
        attrs = {"freeChlorine": 5.7, "targetFreeChlorine": 6.0, "lastCalcEpochMs": bad}
        target = next(
            p
            for p in build_points(attrs, DEVICE, now_ns=4242)
            if p.measurement == "chlorine_target"
        )
        assert target.timestamp_ns == 4242, bad


def test_target_fields_are_independently_optional():
    """A driver reporting only one of the pair still stores that one."""
    only_target = next(
        p
        for p in build_points({"targetFreeChlorine": 6.0}, DEVICE, now_ns=1)
        if p.measurement == "chlorine_target"
    )
    assert only_target.fields["target_ppm"] == pytest.approx(6.0)
    assert only_target.fields["measured_ppm"] is None
    assert "measured_ppm" not in only_target.to_line()


def test_no_target_point_when_neither_field_exists():
    attrs = {"tankRemainingMl": 1.0, "lastDoseMl": 1.0, "lastDoseEpochMs": REAL_DOSE_MS}
    assert [p.measurement for p in build_points(attrs, DEVICE, now_ns=1)] == [
        "chlorine_dose",
        "chlorine_tank",
    ]


def test_no_dose_point_without_a_timestamp():
    tank_only = {"tankRemainingMl": 10.0}
    assert [p.measurement for p in build_points(tank_only, DEVICE, now_ns=1)] == ["chlorine_tank"]


def test_negative_volume_is_not_a_dose():
    assert (
        build_points({"lastDoseMl": -5.0, "lastDoseEpochMs": REAL_DOSE_MS}, DEVICE, now_ns=1) == []
    )


def test_nothing_at_all_yields_no_points():
    assert build_points({"status": "RED"}, DEVICE, now_ns=1) == []


def test_rereading_the_same_dose_is_idempotent():
    """Same attributes must produce byte-identical line protocol (no duplicates)."""
    a = [p.to_line() for p in build_points(ATTRS, DEVICE, now_ns=5)]
    b = [p.to_line() for p in build_points(ATTRS, DEVICE, now_ns=99)]
    # The dose line is identical (same timestamp); only the tank snapshot differs,
    # because a snapshot legitimately records when it was taken.
    assert a[0] == b[0]


def test_numeric_strings_are_accepted():
    points = build_points(
        {"lastDoseMl": "12.5", "lastDoseEpochMs": str(REAL_DOSE_MS)}, DEVICE, now_ns=1
    )
    assert points[0].fields["volume_ml"] == pytest.approx(12.5)
    assert points[0].timestamp_ns == REAL_DOSE_MS * 1_000_000


# --------------------------------------------------------- malformed values and timestamps


@pytest.mark.parametrize(
    "attrs",
    [
        {"lastDoseMl": float("nan"), "lastDoseEpochMs": REAL_DOSE_MS},
        {"lastDoseMl": float("inf"), "lastDoseEpochMs": REAL_DOSE_MS},
        {"lastDoseMl": float("-inf"), "lastDoseEpochMs": REAL_DOSE_MS},
        {"lastDoseMl": "nan", "lastDoseEpochMs": REAL_DOSE_MS},
        {"lastDoseMl": MAX_DOSE_VOLUME_ML + 1, "lastDoseEpochMs": REAL_DOSE_MS},
        {"lastDoseMl": 12.5, "lastDoseEpochMs": 1},  # 1970 dose time
        {"lastDoseMl": 12.5, "lastDoseEpochMs": float("nan")},
        {"lastDoseMl": 12.5, "lastDoseEpochMs": 4_102_444_800_001},  # just after year 2100
    ],
)
def test_malformed_dose_attributes_produce_no_dose(attrs: dict) -> None:
    doses = [p for p in build_points(attrs, DEVICE, now_ns=1) if p.measurement == "chlorine_dose"]
    assert doses == []


@pytest.mark.parametrize(
    "attrs",
    [
        {"tankRemainingMl": float("nan")},
        {"tankRemainingMl": float("inf")},
        {"tankPercent": 101.0},
        {"tankPercent": -1.0},
        {"tankRunwayDays": MAX_RUNWAY_DAYS + 1},
        {"tankRunwayDays": float("nan")},
        {"tankCapacityMl": 1e12},
    ],
)
def test_implausible_tank_values_are_dropped_not_written(attrs: dict) -> None:
    points = [p for p in build_points(attrs, DEVICE, now_ns=1) if p.measurement == "chlorine_tank"]
    # Either no tank point at all, or a point whose malformed field is absent.
    for point in points:
        line = point.to_line()
        assert "nan" not in line.lower()
        assert "inf" not in line.lower()
        for value in point.fields.values():
            if value is not None:
                assert float(value) == float(value)


@pytest.mark.parametrize("value", [float("nan"), float("inf"), -1.0, MAX_PPM + 1])
def test_implausible_target_values_are_dropped(value: float) -> None:
    points = build_points({"freeChlorine": value}, DEVICE, now_ns=1)
    assert [p.measurement for p in points] == []


def test_boundary_values_are_accepted():
    dose = build_points(
        {"lastDoseMl": MAX_DOSE_VOLUME_ML, "lastDoseEpochMs": REAL_DOSE_MS}, DEVICE, now_ns=1
    )[0]
    assert dose.fields["volume_ml"] == pytest.approx(MAX_DOSE_VOLUME_ML)

    zero = build_points({"lastDoseMl": 0.0, "lastDoseEpochMs": REAL_DOSE_MS}, DEVICE, now_ns=1)[0]
    assert zero.fields["volume_ml"] == 0.0

    tank = next(
        p
        for p in build_points(
            {"tankPercent": MAX_PERCENT, "tankRunwayDays": MAX_RUNWAY_DAYS}, DEVICE, now_ns=1
        )
        if p.measurement == "chlorine_tank"
    )
    assert tank.fields["remaining_percent"] == pytest.approx(MAX_PERCENT)
    assert tank.fields["runway_days"] == pytest.approx(MAX_RUNWAY_DAYS)


def test_runtime_out_of_range_is_dropped_but_dose_survives():
    points = build_points(
        {
            "lastDoseMl": 10.0,
            "lastDoseEpochMs": REAL_DOSE_MS,
            "lastDoseRuntimeSeconds": MAX_RUNTIME_SECONDS + 1,
        },
        DEVICE,
        now_ns=1,
    )
    dose = next(p for p in points if p.measurement == "chlorine_dose")
    assert "runtime_seconds" not in dose.fields


# --------------------------------------------------------------------------- event doses


DOSE_EVENTS = [
    {"name": "lastDoseEpochMs", "value": REAL_DOSE_MS, "date": "2026-09-26T02:45:24.739+0000"},
    {
        "name": "lastDoseMl",
        "value": 237.3273375,
        "unit": "mL",
        "date": "2026-09-26T02:45:24.739+0000",
    },
    {
        "name": "lastDoseRuntimeSeconds",
        "value": 65,
        "unit": "s",
        "date": "2026-09-26T02:45:24.739+0000",
    },
]


def test_events_build_a_dose_at_the_recorded_time():
    points = events_to_points(DOSE_EVENTS, DEVICE)
    assert len(points) == 1
    dose = points[0]
    assert dose.measurement == "chlorine_dose"
    assert dose.tags == {"device_id": DEVICE}
    assert dose.fields["volume_ml"] == pytest.approx(237.3273375)
    assert dose.fields["runtime_seconds"] == pytest.approx(65.0)
    assert dose.timestamp_ns == REAL_DOSE_MS * 1_000_000


def test_events_are_idempotent_across_reads():
    first = [p.to_line() for p in events_to_points(DOSE_EVENTS, DEVICE)]
    second = [p.to_line() for p in events_to_points(list(reversed(DOSE_EVENTS)), DEVICE)]
    expected = (
        "chlorine_dose,device_id=101 "
        "volume_ml=237.3273375,runtime_seconds=65.0 1790390724739000000"
    )
    assert first == second == [expected]


def test_repeated_epoch_events_produce_one_dose():
    """A driver that re-emits the same dose must not double-count it."""
    doubled = [DOSE_EVENTS[0], dict(DOSE_EVENTS[1]), DOSE_EVENTS[0]]
    assert len(events_to_points(doubled, DEVICE)) == 1


def test_event_without_a_matching_volume_is_not_an_invented_dose():
    orphan = [{"name": "lastDoseEpochMs", "value": REAL_DOSE_MS, "epoch": REAL_DOSE_MS}]
    assert events_to_points(orphan, DEVICE) == []


def test_volume_event_too_far_from_the_dose_is_not_paired():
    far = [
        {"name": "lastDoseEpochMs", "value": REAL_DOSE_MS, "epoch": REAL_DOSE_MS},
        {"name": "lastDoseMl", "value": 237.3, "epoch": REAL_DOSE_MS + 10 * 60 * 1000},
    ]
    assert events_to_points(far, DEVICE) == []


def test_volume_event_within_tolerance_pairs():
    near = [
        {"name": "lastDoseEpochMs", "value": REAL_DOSE_MS, "epoch": REAL_DOSE_MS},
        {"name": "lastDoseMl", "value": 237.3, "epoch": REAL_DOSE_MS + 2_000},
    ]
    assert len(events_to_points(near, DEVICE)) == 1


def test_implausible_event_volume_is_dropped():
    bad = [
        {"name": "lastDoseEpochMs", "value": REAL_DOSE_MS, "epoch": REAL_DOSE_MS},
        {"name": "lastDoseMl", "value": MAX_DOSE_VOLUME_ML + 1, "epoch": REAL_DOSE_MS},
        {"name": "lastDoseMl", "value": float("nan"), "epoch": REAL_DOSE_MS},
    ]
    assert events_to_points(bad, DEVICE) == []


def test_multiple_doses_are_all_emitted():
    second_ms = REAL_DOSE_MS + 86_400_000
    events = [
        {"name": "lastDoseEpochMs", "value": REAL_DOSE_MS, "epoch": REAL_DOSE_MS},
        {"name": "lastDoseMl", "value": 100.0, "epoch": REAL_DOSE_MS},
        {"name": "lastDoseEpochMs", "value": second_ms, "epoch": second_ms},
        {"name": "lastDoseMl", "value": 200.0, "epoch": second_ms},
    ]
    points = events_to_points(events, DEVICE)
    assert [p.timestamp_ns for p in points] == [REAL_DOSE_MS * 10**6, second_ms * 10**6]
    assert [p.fields["volume_ml"] for p in points] == [100.0, 200.0]


def test_events_ignore_unrelated_attributes():
    events = DOSE_EVENTS + [{"name": "tankPercent", "value": 12.0, "epoch": REAL_DOSE_MS}]
    assert len(events_to_points(events, DEVICE)) == 1


# The advisor republishes a dose under its ORIGINAL lastDoseEpochMs (same epoch value) with a
# newer volume -- a correction (overrun/early-stop) or a void (0 mL). The newest-first listing
# is not guaranteed, so the latest marker (by the event's own hub time) must win in any order.
# ``epoch`` is the event's own hub timestamp; ``value`` is the recorded dose time (the point
# time, identical across the republishes so the point overwrites rather than duplicates).
_REPUBLISH_HUB_MS = REAL_DOSE_MS + 2 * 86_400_000  # the correction/void is booked 2 days later


def _original_and_republish(new_volume_ml: float) -> list[dict]:
    """The original dose events plus a republish of the same epoch at a later hub time."""
    return [
        {"name": DOSE_MARKER, "value": REAL_DOSE_MS, "epoch": REAL_DOSE_MS},
        {"name": DOSE_VOLUME, "value": 100.0, "epoch": REAL_DOSE_MS},
        {"name": DOSE_MARKER, "value": REAL_DOSE_MS, "epoch": _REPUBLISH_HUB_MS},
        {"name": DOSE_VOLUME, "value": new_volume_ml, "epoch": _REPUBLISH_HUB_MS},
    ]


@pytest.mark.parametrize(
    ("new_volume_ml", "expected_ml"),
    [
        pytest.param(150.0, 150.0, id="correction"),  # volume corrected upward
        pytest.param(0.0, 0.0, id="void"),  # dose never ran, republished as 0 mL
    ],
)
def test_latest_marker_wins_regardless_of_event_order(
    new_volume_ml: float, expected_ml: float
) -> None:
    """A republished dose overwrites the original point whatever order the hub lists in.

    Both orderings must yield one point, at the original dose time, carrying the republished
    volume -- the latest marker, not the first-listed one.
    """
    oldest_first = _original_and_republish(new_volume_ml)
    newest_first = list(reversed(oldest_first))

    for order_name, events in (("oldest_first", oldest_first), ("newest_first", newest_first)):
        points = events_to_points(events, DEVICE)
        assert len(points) == 1, order_name
        assert points[0].timestamp_ns == REAL_DOSE_MS * 1_000_000, order_name
        assert points[0].fields["volume_ml"] == pytest.approx(expected_ml), order_name

    # Identical line protocol in both orderings: ordering must not change the written point.
    assert [p.to_line() for p in events_to_points(oldest_first, DEVICE)] == [
        p.to_line() for p in events_to_points(newest_first, DEVICE)
    ]


# --------------------------------------------------------------------------- cursor


def test_cursor_round_trip(tmp_path: Path):
    cursor = EventCursor.load(str(tmp_path), DEVICE)
    assert cursor.since_ns is None
    assert cursor.query_since(0) is None
    cursor.advance(DOSE_EVENTS)
    assert cursor.since_ns == REAL_DOSE_MS * 1_000_000
    cursor.save()

    reloaded = EventCursor.load(str(tmp_path), DEVICE)
    assert reloaded.since_ns == REAL_DOSE_MS * 1_000_000
    assert reloaded.query_since(REAL_DOSE_MS * 1_000_000 + 1) == "2026-09-26T02:45:24.739Z"


def test_cursor_ignores_the_echoed_response_bookmark():
    """The response's ``since`` echoes the caller's old bookmark, not a next cursor."""
    cursor = EventCursor(path="/tmp/does-not-matter.json", device_key=DEVICE, since_ns=None)
    # A newer response ``since`` must not be adopted...
    cursor.since_ns = None
    cursor.advance([])  # no events -> no advancement at all
    assert cursor.since_ns is None
    # ...only the newest returned event's own timestamp moves it.
    cursor.advance(DOSE_EVENTS)
    assert cursor.since_ns == REAL_DOSE_MS * 10**6


def test_cursor_does_not_move_backwards():
    cursor = EventCursor(path="/tmp/x.json", device_key=DEVICE, since_ns=REAL_DOSE_MS * 10**6)
    cursor.advance(DOSE_EVENTS)
    assert cursor.since_ns == REAL_DOSE_MS * 10**6


def test_cursor_clamps_to_the_seven_day_window():
    from waterguru_poller.mapping import parse_iso_ns

    now_ns = REAL_DOSE_MS * 10**6 + 30 * 86_400 * 10**9
    cursor = EventCursor(path="/tmp/x.json", device_key=DEVICE, since_ns=REAL_DOSE_MS * 10**6)
    clamped = cursor.query_since(now_ns)
    assert clamped is not None
    returned_ns = parse_iso_ns(clamped)
    oldest_ns = now_ns - EVENT_RECOVERY_DAYS * 86_400 * 10**9
    assert abs(returned_ns - oldest_ns) < 10**9


def test_cursor_ignores_a_future_bookmark():
    now_ns = REAL_DOSE_MS * 10**6
    cursor = EventCursor(path="/tmp/x.json", device_key=DEVICE, since_ns=now_ns + 10**14)
    assert cursor.query_since(now_ns) is None


def test_cursor_preserves_other_devices(tmp_path: Path):
    first = EventCursor.load(str(tmp_path), "1111")
    first.advance(DOSE_EVENTS)
    first.save()
    other = EventCursor.load(str(tmp_path), "2222")
    other.since_ns = REAL_DOSE_MS * 10**6 + 5
    other.save()
    assert EventCursor.load(str(tmp_path), "1111").since_ns == REAL_DOSE_MS * 10**6
    assert EventCursor.load(str(tmp_path), "2222").since_ns == REAL_DOSE_MS * 10**6 + 5


# --------------------------------------------------------------------------- transport


class _Response:
    def __init__(self, status_code=200, payload=None, text=None):
        self.status_code = status_code
        self._payload = payload
        self.text = text if text is not None else json.dumps(payload)

    def json(self):
        return self._payload


def _real_response(text: str, status_code: int = 200, headers: dict | None = None):
    """A genuine ``requests.Response`` -- ``.json()`` really fails on SSE text."""
    response = requests.Response()
    response.status_code = status_code
    response._content = text.encode("utf-8")
    response.encoding = "utf-8"
    response.url = "http://hub.example.test/apps/api/1/mcp"
    response.headers.update(headers or {})
    return response


class _Session:
    def __init__(self, response=None, error=None):
        self.response = response
        self.error = error
        self.calls = []

    def post(self, url, json=None, headers=None, timeout=None):  # noqa: A002 - mirror requests
        self.calls.append({"url": url, "json": json, "headers": headers, "timeout": timeout})
        if self.error:
            raise self.error
        return self.response


class _UpstreamHub:
    """Models the real upstream history tool for tests.

    Real behaviour (Hubitat-local-MCP-server ``toolGetDeviceHistory``): rows come from
    ``/device/eventsJson/<id>`` newest-first, the ``attribute``/``limit`` filters are applied
    **client-side**, ``limit`` defaults to 100 and caps at 500, and the response carries
    ``events``/``count``/``sinceMode``/``sinceTimestamp`` plus the caller's bounding value
    (and echoes an explicit ``since``).
    """

    LIMIT_DEFAULT = 100
    LIMIT_CAP = 500

    def __init__(
        self,
        pool=(),
        attributes=None,
        history_error=None,
        device_error=None,
        attribute_errors=None,
        saturated=(),
    ):
        self.pool = [dict(event) for event in pool]
        self.attributes = {} if attributes is None else dict(attributes)
        self.history_error = history_error
        self.device_error = device_error
        self.attribute_errors = {} if attribute_errors is None else attribute_errors
        self.saturated = set(saturated)
        self.calls = []

    def page_for(self, attribute, limit):
        """The upstream client-side filter: attribute match, newest-first, then cap."""
        matched = [e for e in self.pool if attribute is None or e.get("name") == attribute]
        matched.sort(key=lambda event: event_time_ns(event) or 0, reverse=True)
        return matched[: min(int(limit), self.LIMIT_CAP)]

    def history_calls(self):
        return [call for call in self.calls if call["name"] == "hub_list_device_events"]

    def arguments_for(self, attribute):
        return [
            call["arguments"]
            for call in self.history_calls()
            if call["arguments"].get("attribute") == attribute
        ]

    def post(self, url, json=None, headers=None, timeout=None):  # noqa: A002 - mirror requests
        name = json["params"]["name"]
        arguments = json["params"]["arguments"]
        self.calls.append({"name": name, "arguments": arguments, "headers": headers})
        if name == "hub_get_device":
            if isinstance(self.device_error, Exception):
                raise self.device_error
            return _Response(payload=_mcp_envelope(self.attributes))
        if isinstance(self.history_error, Exception):
            raise self.history_error
        attribute = arguments.get("attribute")
        error = self.attribute_errors.get(attribute)
        if isinstance(error, Exception):
            raise error
        limit = int(arguments.get("limit", self.LIMIT_DEFAULT))
        page = self.page_for(attribute, limit)
        extra = {
            "count": EVENT_PAGE_LIMIT if attribute in self.saturated else len(page),
            "sinceMode": "since" if "since" in arguments else "hoursBack",
            "sinceTimestamp": "2026-09-19T00:00:00.000Z",
        }
        if "since" in arguments:
            extra["since"] = arguments["since"]  # echo of the caller's old bookmark
        return _Response(payload=_events_envelope(page, **extra))


class _BadHistorySession:
    """Returns a shape we do not understand for every history read."""

    def __init__(self, attributes):
        self.attributes = attributes
        self.calls = []

    def post(self, url, json=None, headers=None, timeout=None):  # noqa: A002 - mirror requests
        arguments = json["params"]["arguments"]
        self.calls.append({"name": json["params"]["name"], "arguments": arguments})
        if json["params"]["name"] == "hub_get_device":
            return _Response(payload=_mcp_envelope(self.attributes))
        return _Response(payload={"ok": True, "job": "done"})


def test_fetch_sends_bearer_token_and_bare_tools_call():
    session = _Session(_Response(payload=_mcp_envelope(ATTRS)))
    attrs = fetch_device_attributes("http://hub/mcp", "secret-token", DEVICE, session=session)
    assert attrs["lastDoseMl"] == pytest.approx(237.3273375)
    sent = session.calls[0]
    assert sent["headers"]["Authorization"] == "Bearer secret-token"
    assert sent["json"]["method"] == "tools/call"
    assert sent["json"]["params"]["name"] == "hub_get_device"
    assert sent["json"]["params"]["arguments"]["deviceId"] == DEVICE


def test_fetch_parses_sse_using_a_real_requests_response():
    """Regression: the JSON is on the SSE `data:` line, not the event-stream itself."""
    payload = _mcp_envelope(ATTRS)
    text = f"event: message\ndata: {json.dumps(payload)}\n\n"
    response = _real_response(text, headers={"Content-Type": "text/event-stream"})
    with pytest.raises(ValueError):
        response.json()  # proves .json() on the raw stream fails

    session = _Session(response)
    attrs = fetch_device_attributes("http://hub/mcp", "t", DEVICE, session=session)
    assert attrs["tankPercent"] == pytest.approx(99.5820316678)


def test_fetch_uses_the_last_sse_event():
    first = _mcp_envelope({"tankPercent": 1.0})
    last = _mcp_envelope(ATTRS)
    text = (
        f"event: message\ndata: {json.dumps(first)}\n\n"
        f"event: message\ndata: {json.dumps(last)}\n\n"
    )
    session = _Session(_real_response(text, headers={"Content-Type": "text/event-stream"}))
    attrs = fetch_device_attributes("http://hub/mcp", "t", DEVICE, session=session)
    assert attrs["tankPercent"] == pytest.approx(99.5820316678)


def test_fetch_handles_sse_framed_body():
    payload = _mcp_envelope(ATTRS)
    session = _Session(
        _Response(payload=payload, text=f"event: message\ndata: {json.dumps(payload)}\n\n")
    )
    assert fetch_device_attributes("http://hub/mcp", "t", DEVICE, session=session)[
        "tankPercent"
    ] == pytest.approx(99.5820316678)


def test_fetch_raises_on_http_error():
    session = _Session(_Response(status_code=401, payload={"error": "bad token"}, text="bad token"))
    with pytest.raises(HubError, match="HTTP 401"):
        fetch_device_attributes("http://hub/mcp", "t", DEVICE, session=session)


def test_fetch_raises_on_connection_error():
    session = _Session(error=requests.ConnectionError("refused"))
    with pytest.raises(HubError, match="hub request failed"):
        fetch_device_attributes("http://hub/mcp", "t", DEVICE, session=session)


def test_fetch_raises_usefully_on_garbage_body():
    response = _real_response("<html>not json</html>", headers={"Content-Type": "text/html"})
    session = _Session(response)
    with pytest.raises(HubError, match="was not JSON"):
        fetch_device_attributes("http://hub/mcp", "t", DEVICE, session=session)


def test_fetch_bounds_the_body_in_its_error():
    session = _Session(_real_response("x" * 5000, headers={"Content-Type": "text/plain"}))
    with pytest.raises(HubError) as excinfo:
        fetch_device_attributes("http://hub/mcp", "t", DEVICE, session=session)
    assert len(str(excinfo.value)) < 1000


# --------------------------------------------------------------------------- collect


class _Writer:
    def __init__(self):
        self.points = []

    def write(self, points):
        self.points.extend(points)
        return len(points)


def test_collect_writes_both_measurements():
    writer = _Writer()
    hub = _UpstreamHub(attributes=ATTRS)
    result = collect(
        url="http://hub/mcp", token="t", device_id=DEVICE, writer=writer, session=hub
    )
    assert (result.dose_points, result.tank_points, result.written) == (1, 1, 2)
    assert {p.measurement for p in writer.points} == {"chlorine_dose", "chlorine_tank"}


def test_collect_queries_each_dosing_attribute_with_the_cap():
    """Exact MCP arguments: per-attribute filter + limit 500 + the bounding argument."""
    hub = _UpstreamHub(attributes=ATTRS)
    collect(url="http://hub/mcp", token="t", device_id=DEVICE, writer=_Writer(), session=hub)

    history = hub.history_calls()
    assert [call["arguments"]["attribute"] for call in history] == [
        DOSE_MARKER,
        DOSE_VOLUME,
        DOSE_RUNTIME,
    ]
    for call in history:
        assert call["arguments"] == {
            "deviceId": DEVICE,
            "attribute": call["arguments"]["attribute"],
            "limit": EVENT_PAGE_LIMIT,
            "hoursBack": EVENT_RECOVERY_DAYS * 24,
        }
    assert [call["name"] for call in hub.calls].count("hub_get_device") == 1


def test_collect_dry_run_writes_nothing():
    writer = _Writer()
    hub = _UpstreamHub(attributes=ATTRS)
    result = collect(
        url="http://hub/mcp",
        token="t",
        device_id=DEVICE,
        writer=writer,
        dry_run=True,
        session=hub,
    )
    assert result.written == 0
    assert result.dry_run is True
    assert writer.points == []


def test_collect_with_no_usable_fields_is_not_an_error():
    hub = _UpstreamHub(attributes={"status": "GREEN"})
    result = collect(
        url="http://hub/mcp", token="t", device_id=DEVICE, writer=_Writer(), session=hub
    )
    assert result.written == 0


def test_collect_merges_the_snapshot_dose_into_the_event_dose():
    """One physical dose must produce one point, not a snapshot + event duplicate."""
    attributes = dict(ATTRS, freeChlorine=5.7, targetFreeChlorine=6.0, lastCalcEpochMs=REAL_DOSE_MS)
    hub = _UpstreamHub(pool=DOSE_EVENTS, attributes=attributes)
    writer = _Writer()
    result = collect(
        url="http://hub/mcp",
        token="t",
        device_id=DEVICE,
        writer=writer,
        session=hub,
        now_ns=REAL_DOSE_MS * 10**6 + 3_600 * 10**9,
    )
    assert result.dose_event_points == 1
    assert result.dose_points == 1  # not 2: the snapshot dose was merged away
    assert result.tank_points == 1
    assert result.target_points == 1
    assert result.written == len(writer.points) == 3
    doses = [p for p in writer.points if p.measurement == "chlorine_dose"]
    assert len(doses) == 1
    assert doses[0].timestamp_ns == REAL_DOSE_MS * 10**6
    assert doses[0].fields["runtime_seconds"] == pytest.approx(65.0)


def test_collect_fills_a_missing_event_runtime_from_the_snapshot():
    attributes = dict(ATTRS, lastDoseRuntimeSeconds=65)
    # History has the marker + volume but no runtime row at all.
    hub = _UpstreamHub(
        pool=[e for e in DOSE_EVENTS if e["name"] != DOSE_RUNTIME], attributes=attributes
    )
    writer = _Writer()
    result = collect(
        url="http://hub/mcp",
        token="t",
        device_id=DEVICE,
        writer=writer,
        session=hub,
        now_ns=REAL_DOSE_MS * 10**6 + 3_600 * 10**9,
    )
    assert result.dose_points == 1
    dose = next(p for p in writer.points if p.measurement == "chlorine_dose")
    assert dose.fields["runtime_seconds"] == pytest.approx(65.0)


def test_collect_keeps_the_snapshot_dose_when_history_is_offline():
    hub = _UpstreamHub(attributes=ATTRS, history_error=requests.ConnectionError("hub busy"))
    writer = _Writer()
    result = collect(
        url="http://hub/mcp", token="t", device_id=DEVICE, writer=writer, session=hub
    )
    assert result.dose_points == 1  # snapshot fallback, still a real dose
    assert result.dose_event_points == 0
    assert result.tank_points == 1


def test_collect_advances_and_reuses_the_cursor(tmp_path: Path):
    now_ns = REAL_DOSE_MS * 10**6 + 3_600 * 10**9
    hub = _UpstreamHub(pool=DOSE_EVENTS, attributes=ATTRS)
    collect(
        url="http://hub/mcp",
        token="t",
        device_id=DEVICE,
        writer=_Writer(),
        session=hub,
        state_dir=str(tmp_path),
        now_ns=now_ns,
    )
    assert (tmp_path / EventCursor.FILE_NAME).exists()

    hub.calls.clear()
    collect(
        url="http://hub/mcp",
        token="t",
        device_id=DEVICE,
        writer=_Writer(),
        session=hub,
        state_dir=str(tmp_path),
        now_ns=now_ns,
    )
    for call in hub.history_calls():
        assert call["arguments"]["since"] == "2026-09-26T02:45:24.739Z"
        assert "hoursBack" not in call["arguments"]
        assert call["arguments"]["limit"] == EVENT_PAGE_LIMIT


def test_collect_marks_events_degraded_on_an_unrecognised_payload_but_keeps_the_snapshot():
    """A hub response shape we do not understand must degrade, not break, the run."""
    hub = _BadHistorySession(ATTRS)
    writer = _Writer()
    result = collect(
        url="http://hub/mcp", token="t", device_id=DEVICE, writer=writer, session=hub
    )
    assert result.events_ok is False
    assert result.snapshot_ok is True
    assert result.events_error is not None and "no event list" in result.events_error
    assert result.tank_points == 1
    assert result.dose_points == 1  # snapshot fallback
    assert {p.measurement for p in writer.points} == {"chlorine_dose", "chlorine_tank"}


def test_collect_falls_back_to_the_snapshot_when_events_fail(tmp_path: Path):
    hub = _UpstreamHub(attributes=ATTRS, history_error=requests.ConnectionError("hub busy"))
    writer = _Writer()
    result = collect(
        url="http://hub/mcp",
        token="t",
        device_id=DEVICE,
        writer=writer,
        session=hub,
        state_dir=str(tmp_path),
    )
    assert result.tank_points == 1
    assert result.dose_points == 1  # snapshot fallback
    assert result.dose_event_points == 0
    assert result.events_ok is False
    assert not (tmp_path / EventCursor.FILE_NAME).exists()


def test_collect_still_writes_events_when_the_snapshot_fails():
    hub = _UpstreamHub(
        pool=DOSE_EVENTS, device_error=requests.ConnectionError("hub busy")
    )
    writer = _Writer()
    result = collect(
        url="http://hub/mcp", token="t", device_id=DEVICE, writer=writer, session=hub
    )
    assert result.dose_event_points == 1
    assert result.tank_points == 0
    assert result.snapshot_ok is False
    assert [p.measurement for p in writer.points] == ["chlorine_dose"]


def test_collect_does_not_advance_the_cursor_without_a_successful_write(tmp_path: Path):
    class FailingWriter:
        def write(self, points):
            raise RuntimeError("influx down")

    hub = _UpstreamHub(pool=DOSE_EVENTS, attributes=ATTRS)
    with pytest.raises(RuntimeError):
        collect(
            url="http://hub/mcp",
            token="t",
            device_id=DEVICE,
            writer=FailingWriter(),
            session=hub,
            state_dir=str(tmp_path),
            now_ns=REAL_DOSE_MS * 10**6 + 3_600 * 10**9,
        )
    assert not (tmp_path / EventCursor.FILE_NAME).exists()


def test_collect_dry_run_does_not_save_the_cursor(tmp_path: Path):
    hub = _UpstreamHub(pool=DOSE_EVENTS, attributes=ATTRS)
    result = collect(
        url="http://hub/mcp",
        token="t",
        device_id=DEVICE,
        writer=None,
        dry_run=True,
        session=hub,
        state_dir=str(tmp_path),
        now_ns=REAL_DOSE_MS * 10**6 + 3_600 * 10**9,
    )
    assert result.written == 0
    assert result.points
    assert not (tmp_path / EventCursor.FILE_NAME).exists()


# ------------------------------------------------------- truncation / saturation safety


def test_unrelated_tile_events_cannot_consume_the_dose_history_budget():
    """>100 unrelated events must not push dose rows out of a default-limit page.

    The old unfiltered read defaulted to 100 newest-first rows, so a busy tile could return
    zero dose rows. Attribute-filtered reads at limit 500 keep every dose row.
    """
    unrelated = [
        {
            "name": "switch",
            "value": "on",
            # Newer than the dose rows, so an unfiltered newest-first page is all unrelated.
            "epoch": REAL_DOSE_MS + index * 1_000,
        }
        for index in range(150)
    ]
    hub = _UpstreamHub(pool=unrelated + [dict(e) for e in DOSE_EVENTS], attributes=ATTRS)

    # Counterfactual: what the old default/unfiltered read would have returned.
    unfiltered_default = hub.page_for(None, hub.LIMIT_DEFAULT)
    assert len(unfiltered_default) == hub.LIMIT_DEFAULT
    assert not [e for e in unfiltered_default if e["name"] in {DOSE_MARKER, DOSE_VOLUME}]

    writer = _Writer()
    result = collect(
        url="http://hub/mcp",
        token="t",
        device_id=DEVICE,
        writer=writer,
        session=hub,
        now_ns=REAL_DOSE_MS * 10**6 + 3_600 * 10**9,
    )
    for call in hub.history_calls():
        assert call["arguments"]["attribute"] in {DOSE_MARKER, DOSE_VOLUME, DOSE_RUNTIME}
        assert call["arguments"]["limit"] == EVENT_PAGE_LIMIT
    assert result.events_ok is True  # not saturated: the filter scoped the read
    assert result.dose_event_points == 1
    assert result.dose_points == 1


def test_saturated_required_history_does_not_advance_the_cursor(tmp_path: Path):
    hub = _UpstreamHub(
        pool=DOSE_EVENTS, attributes=ATTRS, saturated={DOSE_MARKER}
    )
    writer = _Writer()
    now_ns = REAL_DOSE_MS * 10**6 + 3_600 * 10**9
    result = collect(
        url="http://hub/mcp",
        token="t",
        device_id=DEVICE,
        writer=writer,
        session=hub,
        state_dir=str(tmp_path),
        now_ns=now_ns,
    )

    assert result.events_ok is False
    assert result.events_error is not None and "saturated" in result.events_error
    # Truncated history is not claimed as lossless: the snapshot dose is the answer.
    assert result.dose_event_points == 0
    assert result.dose_points == 1
    assert result.written == len(writer.points)
    assert not (tmp_path / EventCursor.FILE_NAME).exists()

    # A follow-up run re-reads the same window rather than skipping it.
    hub.calls.clear()
    collect(
        url="http://hub/mcp",
        token="t",
        device_id=DEVICE,
        writer=_Writer(),
        session=hub,
        state_dir=str(tmp_path),
        now_ns=now_ns,
    )
    for call in hub.history_calls():
        assert "since" not in call["arguments"]
        assert call["arguments"]["hoursBack"] == EVENT_RECOVERY_DAYS * 24


def test_saturated_volume_history_also_blocks_the_cursor(tmp_path: Path):
    hub = _UpstreamHub(pool=DOSE_EVENTS, attributes=ATTRS, saturated={DOSE_VOLUME})
    result = collect(
        url="http://hub/mcp",
        token="t",
        device_id=DEVICE,
        writer=_Writer(),
        session=hub,
        state_dir=str(tmp_path),
        now_ns=REAL_DOSE_MS * 10**6 + 3_600 * 10**9,
    )
    assert result.dose_event_points == 0
    assert result.dose_points == 1  # snapshot fallback
    assert not (tmp_path / EventCursor.FILE_NAME).exists()


def test_failed_required_attribute_blocks_the_cursor_but_keeps_the_snapshot(tmp_path: Path):
    hub = _UpstreamHub(
        pool=DOSE_EVENTS,
        attributes=ATTRS,
        attribute_errors={DOSE_VOLUME: requests.ConnectionError("boom")},
    )
    result = collect(
        url="http://hub/mcp",
        token="t",
        device_id=DEVICE,
        writer=_Writer(),
        session=hub,
        state_dir=str(tmp_path),
        now_ns=REAL_DOSE_MS * 10**6 + 3_600 * 10**9,
    )
    assert result.events_ok is False
    assert result.dose_event_points == 0
    assert result.dose_points == 1
    assert not (tmp_path / EventCursor.FILE_NAME).exists()


def test_runtime_failure_keeps_the_dose_and_may_advance_the_cursor(tmp_path: Path):
    """Runtime is best-effort: marker+volume still form a coherent dose."""
    hub = _UpstreamHub(
        pool=DOSE_EVENTS,
        attributes=ATTRS,
        attribute_errors={DOSE_RUNTIME: requests.ConnectionError("boom")},
    )
    writer = _Writer()
    result = collect(
        url="http://hub/mcp",
        token="t",
        device_id=DEVICE,
        writer=writer,
        session=hub,
        state_dir=str(tmp_path),
        now_ns=REAL_DOSE_MS * 10**6 + 3_600 * 10**9,
    )
    assert result.events_ok is False  # degraded, so the CLI reports it
    assert result.dose_event_points == 1
    assert result.dose_points == 1
    # No runtime event was available, so the field is absent rather than zero.
    dose = next(p for p in writer.points if p.measurement == "chlorine_dose")
    assert "runtime_seconds" not in dose.fields
    assert (tmp_path / EventCursor.FILE_NAME).exists()


def test_runtime_saturation_omits_runtime_without_blocking_the_cursor(tmp_path: Path):
    hub = _UpstreamHub(pool=DOSE_EVENTS, attributes=ATTRS, saturated={DOSE_RUNTIME})
    writer = _Writer()
    result = collect(
        url="http://hub/mcp",
        token="t",
        device_id=DEVICE,
        writer=writer,
        session=hub,
        state_dir=str(tmp_path),
        now_ns=REAL_DOSE_MS * 10**6 + 3_600 * 10**9,
    )
    assert result.events_ok is False
    assert result.dose_event_points == 1
    dose = next(p for p in writer.points if p.measurement == "chlorine_dose")
    assert "runtime_seconds" not in dose.fields
    assert (tmp_path / EventCursor.FILE_NAME).exists()


def test_history_pages_are_served_newest_first():
    """The upstream page order is newest-first, regardless of insertion order."""
    oldest = {"name": DOSE_MARKER, "value": REAL_DOSE_MS, "epoch": REAL_DOSE_MS}
    newest = {
        "name": DOSE_MARKER,
        "value": REAL_DOSE_MS + 86_400_000,
        "epoch": REAL_DOSE_MS + 86_400_000,
    }
    hub = _UpstreamHub(pool=[oldest, newest])
    page = hub.page_for(DOSE_MARKER, EVENT_PAGE_LIMIT)
    assert [event["value"] for event in page] == [REAL_DOSE_MS + 86_400_000, REAL_DOSE_MS]


def test_newest_first_history_pairs_regardless_of_input_order():
    """Newest-first multi-attribute rows must still pair into one coherent dose."""
    older = {"name": DOSE_MARKER, "value": REAL_DOSE_MS, "epoch": REAL_DOSE_MS}
    newer = {"name": DOSE_VOLUME, "value": 100.0, "epoch": REAL_DOSE_MS + 1_000}
    hub = _UpstreamHub(pool=[newer, older], attributes=ATTRS)
    writer = _Writer()
    result = collect(
        url="http://hub/mcp",
        token="t",
        device_id=DEVICE,
        writer=writer,
        session=hub,
        now_ns=REAL_DOSE_MS * 10**6 + 3_600 * 10**9,
    )
    assert result.dose_event_points == 1
    assert result.dose_points == 1
    dose = next(p for p in writer.points if p.measurement == "chlorine_dose")
    assert dose.timestamp_ns == REAL_DOSE_MS * 10**6
    assert dose.fields["volume_ml"] == pytest.approx(100.0)
