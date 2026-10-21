from __future__ import annotations

from typing import Any

import pytest

from waterguru_poller.mapping import (
    MEAS_MEASURE,
    MEAS_POD,
    MEAS_STATUS,
    dashboard_to_points,
    parse_iso_ns,
    pod_records_to_points,
)


def test_parse_iso_ns_millis() -> None:
    # 2026-08-18T14:34:18.000Z -> known epoch nanoseconds
    ns = parse_iso_ns("2026-08-18T14:34:18.000Z")
    assert ns == 1787063658 * 1_000_000_000
    # fractional milliseconds preserved
    ns2 = parse_iso_ns("2026-08-18T14:34:18.758Z")
    assert ns2 == 1787063658 * 1_000_000_000 + 758_000_000


def test_pod_records_map_numeric_and_flatten_arrays(managepod_sample: dict[str, Any]) -> None:
    points = pod_records_to_points(managepod_sample)
    # 3 records, but the 3rd has no numeric fields -> skipped
    assert len(points) == 2
    chem = points[0]
    assert chem.measurement == MEAS_POD
    assert chem.tags == {"pod_id": "999001", "water_body_id": "wb-test-0001"}
    # numeric fields present and coerced to float
    assert chem.fields["freeCl"] == 7.6
    assert chem.fields["ph"] == 7.8
    assert isinstance(chem.fields["padReadCnt"], float)
    # RGB arrays flattened to indexed fields
    assert chem.fields["freeClRgb_0"] == 5677.0
    assert chem.fields["freeClRgb_2"] == 8135.0
    # *Dec string duplicates dropped
    assert "freeClDec" not in chem.fields
    assert "phDec" not in chem.fields
    # timestamp
    assert chem.timestamp_ns == parse_iso_ns("2026-08-18T14:34:18.000Z")


def test_pod_records_flow_record_fields(managepod_sample: dict[str, Any]) -> None:
    points = pod_records_to_points(managepod_sample)
    flow = points[1]
    assert flow.fields["flowTempDeltaFahr"] == 10.4
    assert flow.fields["rssi"] == -73.0  # int coerced to float for type consistency
    assert flow.fields["mcuOrient_0"] == 0.0
    assert flow.fields["remoteOrient_1"] == -14.0


def test_dashboard_measure_points(dashboard_sample: dict[str, Any]) -> None:
    points = dashboard_to_points(dashboard_sample, now_ns=123)
    measures = [p for p in points if p.measurement == MEAS_MEASURE]
    by_type = {p.tags["type"]: p for p in measures}
    assert set(by_type) == {"FREE_CL", "PH", "TA", "SKIMMER_FLOW", "PENDING"}

    fc = by_type["FREE_CL"]
    assert fc.fields["value"] == 7.6
    assert fc.fields["target"] == 3.0
    assert fc.fields["status"] == "YELLOW"
    assert fc.fields["status_code"] == 1
    assert fc.fields["first_alert"] == "HIGH"
    assert fc.timestamp_ns == parse_iso_ns("2026-08-18T14:34:18.000Z")

    ta = by_type["TA"]
    assert ta.fields["value"] == 77.0  # intValue coerced to float

    # PENDING has status but no value -> still a point, but with no 'value' field
    pending = by_type["PENDING"]
    assert "value" not in pending.fields
    assert pending.fields["status_code"] == 0


def test_dashboard_status_point(dashboard_sample: dict[str, Any]) -> None:
    points = dashboard_to_points(dashboard_sample, now_ns=123)
    status = [p for p in points if p.measurement == MEAS_STATUS]
    assert len(status) == 1
    s = status[0]
    assert s.tags == {"water_body_id": "wb-test-0001"}
    assert s.fields["water_temp"] == 73.0
    assert s.fields["overall_status"] == "YELLOW"
    assert s.fields["overall_status_code"] == 1
    assert s.fields["battery_pct"] == 82.0
    assert s.fields["cassette_pct"] == 45.0
    assert s.fields["cassette_days_remaining"] == 42  # "6 weeks" -> 42 days
    assert s.fields["rssi"] == -70.0
    assert s.fields["rssi_bars"] == 3.0
    assert s.fields["free_cl_target"] == 3.0
    assert s.fields["ph_target"] == 7.6
    assert s.fields["flow_gpm_target"] == 15.0
    assert s.fields["salt_target"] == 3200.0
    # uses latestMeasureTime, not the now_ns fallback
    assert s.timestamp_ns == parse_iso_ns("2026-08-18T14:34:18.000Z")


# ------------------------------------------------ malformed values and impossible timestamps


def _pod_response(records: list[dict[str, Any]]) -> dict[str, Any]:
    return {"queryDataHistoryRsp": {"data": records}}


def test_impossible_pod_timestamp_is_skipped_without_losing_valid_records() -> None:
    points = pod_records_to_points(
        _pod_response(
            [
                {"time": "1970-01-01T00:00:01.000Z", "podId": 1, "freeCl": 1.0},
                {"time": "3000-01-01T00:00:00.000Z", "podId": 1, "freeCl": 2.0},
                {"time": "not-a-time", "podId": 1, "freeCl": 3.0},
                {"time": "2026-08-18T14:34:18.000Z", "podId": 1, "freeCl": 7.6},
            ]
        )
    )
    assert [p.fields["freeCl"] for p in points] == [7.6]


def test_non_finite_pod_fields_are_dropped() -> None:
    points = pod_records_to_points(
        _pod_response(
            [
                {
                    "time": "2026-08-18T14:34:18.000Z",
                    "podId": 1,
                    "freeCl": float("nan"),
                    "ph": float("inf"),
                    "rssi": -70,
                }
            ]
        )
    )
    assert len(points) == 1
    assert points[0].fields == {"rssi": -70.0}
    line = points[0].to_line()
    assert "nan" not in line.lower()
    assert "inf" not in line.lower()


def test_record_with_only_non_finite_fields_is_skipped() -> None:
    points = pod_records_to_points(
        _pod_response([{"time": "2026-08-18T14:34:18.000Z", "podId": 1, "freeCl": float("nan")}])
    )
    assert points == []


def test_measurement_with_impossible_timestamp_is_skipped() -> None:
    dashboard = {
        "waterBodies": [
            {
                "waterBodyId": "wb",
                "measurements": [
                    {"type": "FREE_CL", "measureTime": "1970-01-01T00:00:01Z", "floatValue": 7.6},
                    {"type": "PH", "measureTime": "2026-08-18T14:34:18.000Z", "floatValue": 7.8},
                ],
            }
        ]
    }
    measures = [
        p for p in dashboard_to_points(dashboard, now_ns=1) if p.measurement == MEAS_MEASURE
    ]
    assert [p.tags["type"] for p in measures] == ["PH"]


def test_measurement_non_finite_value_leaves_the_field_absent() -> None:
    dashboard = {
        "waterBodies": [
            {
                "waterBodyId": "wb",
                "measurements": [
                    {
                        "type": "FREE_CL",
                        "measureTime": "2026-08-18T14:34:18.000Z",
                        "floatValue": float("nan"),
                        "status": "GREEN",
                    }
                ],
            }
        ]
    }
    measures = [
        p for p in dashboard_to_points(dashboard, now_ns=1) if p.measurement == MEAS_MEASURE
    ]
    assert len(measures) == 1
    assert "value" not in measures[0].fields
    assert measures[0].fields["status_code"] == 0


def test_status_point_drops_non_finite_fields() -> None:
    dashboard = {
        "waterBodies": [
            {
                "waterBodyId": "wb",
                "waterTemp": float("nan"),
                "status": "GREEN",
                "phTargetEffective": float("inf"),
                "latestMeasureTime": "2026-08-18T14:34:18.000Z",
            }
        ]
    }
    status = [p for p in dashboard_to_points(dashboard, now_ns=1) if p.measurement == MEAS_STATUS]
    assert len(status) == 1
    assert "water_temp" not in status[0].fields
    assert "ph_target" not in status[0].fields
    assert status[0].fields["overall_status_code"] == 0


def test_status_point_falls_back_to_collection_time_when_timestamp_is_invalid() -> None:
    dashboard = {
        "waterBodies": [
            {
                "waterBodyId": "wb",
                "status": "GREEN",
                "latestMeasureTime": "1970-01-01T00:00:01Z",
            }
        ]
    }
    status = [
        p for p in dashboard_to_points(dashboard, now_ns=4242) if p.measurement == MEAS_STATUS
    ]
    assert status[0].timestamp_ns == 4242


@pytest.mark.parametrize("value", [float("nan"), float("inf"), float("-inf")])
def test_point_serialization_refuses_non_finite_floats(value: float) -> None:
    from waterguru_poller.lineprotocol import Point

    with pytest.raises(ValueError, match="Non-finite"):
        Point(MEAS_POD, fields={"bad": value}).to_line()
