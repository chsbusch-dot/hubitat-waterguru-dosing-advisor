"""Map WaterGuru API payloads to InfluxDB points.

Two sources feed three InfluxDB measurements:

* ``waterguru_pod``     — full-resolution SmartPod telemetry from ``prod-managePod``'s
                          ``queryDataHistoryRsp.data`` (freeCl, ph, waterTempFahr, the raw
                          flow signal, battery volts, rssi, ...), one point per real reading.
* ``waterguru_measure`` — processed measurements from ``prod-getDashboardView`` (FREE_CL, PH,
                          plus the lab-cassette values TA/CH/CYA/SALT/... that never appear in
                          the pod history), each at its ``measureTime`` with value/target/status.
* ``waterguru_status``  — a per-water-body snapshot: battery %, cassette %, overall status, targets.

The pod mapping is deliberately generic: it maps *every* numeric field the API returns
(and flattens numeric arrays like the RGB calibration triples) rather than a hardcoded
subset, so new firmware fields show up in Grafana automatically.
"""

from __future__ import annotations

import logging
from datetime import UTC, datetime
from typing import Any

from .lineprotocol import Point, finite_float, is_plausible_timestamp_ns

_LOGGER = logging.getLogger(__name__)

MEAS_POD = "waterguru_pod"
MEAS_MEASURE = "waterguru_measure"
MEAS_STATUS = "waterguru_status"

STATUS_CODE = {"GREEN": 0, "YELLOW": 1, "RED": 2}

# Keys handled specially (as tags / timestamp) or that are string duplicates of a numeric.
_POD_SKIP_KEYS = {"recType", "podId", "waterBodyId", "time", "cloudTime"}

_EPOCH = datetime(1970, 1, 1, tzinfo=UTC)


def parse_iso_ns(ts: str) -> int:
    """Parse a WaterGuru ISO-8601 timestamp (``...Z``) to integer nanoseconds since epoch."""
    dt = datetime.fromisoformat(ts.replace("Z", "+00:00"))
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=UTC)
    delta = dt - _EPOCH
    return (delta.days * 86400 + delta.seconds) * 1_000_000_000 + delta.microseconds * 1000


def _numeric_fields(record: dict[str, Any]) -> dict[str, object]:
    """Extract every numeric field from a record, flattening numeric arrays.

    All numbers are coerced to float so a given field key keeps one consistent
    InfluxDB type across records (some records report rssi as int, some fields as float).
    Non-finite values (``NaN``/infinity) are dropped: they are a malformed payload, not a
    reading, and InfluxDB would reject or pollute the series with them.
    """
    fields: dict[str, object] = {}
    for key, value in record.items():
        if key in _POD_SKIP_KEYS or key.endswith("Dec"):
            continue
        if isinstance(value, bool):
            fields[key] = value
            continue
        number = finite_float(value)
        if number is not None:
            fields[key] = number
            continue
        if isinstance(value, list) and value:
            numbers = [finite_float(item) for item in value]
            if all(item is not None for item in numbers):
                for i, item in enumerate(numbers):
                    fields[f"{key}_{i}"] = item
        # strings / nested objects / empty or mixed lists are intentionally skipped
    return fields


def _record_time_ns(raw: Any) -> int | None:
    """Parse a WaterGuru timestamp string, or ``None`` when it is unusable."""
    if not isinstance(raw, str) or not raw:
        return None
    try:
        ts_ns = parse_iso_ns(raw)
    except (ValueError, OverflowError):
        return None
    if not is_plausible_timestamp_ns(ts_ns):
        return None
    return ts_ns


def pod_records_to_points(history_response: dict[str, Any]) -> list[Point]:
    """Convert a ``prod-managePod`` response into ``waterguru_pod`` points."""
    rsp = history_response.get("queryDataHistoryRsp", {})
    records = rsp.get("data", []) or []
    points: list[Point] = []
    for rec in records:
        ts = rec.get("time")
        if not ts:
            continue
        ts_ns = _record_time_ns(ts)
        if ts_ns is None:
            _LOGGER.warning("Skipping pod record with an unusable timestamp: %r", ts)
            continue
        fields = _numeric_fields(rec)
        if not fields:
            continue
        tags = {
            "pod_id": str(rec.get("podId", "")),
            "water_body_id": str(rec.get("waterBodyId", "")),
        }
        points.append(Point(MEAS_POD, tags=tags, fields=fields, timestamp_ns=ts_ns))
    return points


def _measure_value(m: dict[str, Any]) -> float | None:
    val = m.get("floatValue")
    if val is None:
        val = m.get("intValue")
    return finite_float(val)


def _measurement_points(water_body: dict[str, Any]) -> list[Point]:
    wb_id = str(water_body.get("waterBodyId", ""))
    points: list[Point] = []
    for m in water_body.get("measurements", []) or []:
        mtype = m.get("type")
        mtime = m.get("measureTime")
        if not mtype or not mtime:
            continue
        ts_ns = _record_time_ns(mtime)
        if ts_ns is None:
            _LOGGER.warning("Skipping measurement %r with an unusable timestamp: %r", mtype, mtime)
            continue
        fields: dict[str, object] = {}
        value = _measure_value(m)
        if value is not None:
            fields["value"] = value
        target = finite_float(m.get("target"))
        if target is not None:
            fields["target"] = target
        status = m.get("status")
        if status is not None:
            fields["status"] = str(status)
            if status in STATUS_CODE:
                fields["status_code"] = STATUS_CODE[status]
        if m.get("firstAlertCondition") is not None:
            fields["first_alert"] = str(m["firstAlertCondition"])
        if not fields:
            continue
        tags = {"water_body_id": wb_id, "type": str(mtype)}
        points.append(Point(MEAS_MEASURE, tags=tags, fields=fields, timestamp_ns=ts_ns))
    return points


def _cassette_days_remaining(time_left_text: str | None) -> int | None:
    if not time_left_text:
        return None
    parts = time_left_text.split()
    if not parts or not parts[0].isdigit():
        return None
    number = int(parts[0])
    if "week" in time_left_text:
        number *= 7
    elif "month" in time_left_text:
        number *= 30
    return number


def _status_point(water_body: dict[str, Any], fallback_ns: int) -> Point | None:
    wb_id = str(water_body.get("waterBodyId", ""))
    fields: dict[str, object] = {}

    water_temp = finite_float(water_body.get("waterTemp"))
    if water_temp is not None:
        fields["water_temp"] = water_temp
    status = water_body.get("status")
    if status is not None:
        fields["overall_status"] = str(status)
        if status in STATUS_CODE:
            fields["overall_status_code"] = STATUS_CODE[status]

    for src, dst in (
        ("freeClTargetEffective", "free_cl_target"),
        ("phTargetEffective", "ph_target"),
        ("flowGpmTargetEffective", "flow_gpm_target"),
        ("saltTargetEffective", "salt_target"),
    ):
        target = finite_float(water_body.get(src))
        if target is not None:
            fields[dst] = target

    pods = water_body.get("pods") or []
    if pods:
        pod = pods[0]
        rssi_info = pod.get("rssiInfo") or {}
        rssi = finite_float(rssi_info.get("rssi"))
        if rssi is not None:
            fields["rssi"] = rssi
        bars = finite_float(rssi_info.get("bars"))
        if bars is not None:
            fields["rssi_bars"] = bars
        for refill in pod.get("refillables", []) or []:
            if refill.get("type") == "BATT":
                pct = finite_float(refill.get("pctLeft"))
                if pct is not None:
                    fields["battery_pct"] = pct
            if refill.get("type") == "LAB":
                pct = finite_float(refill.get("pctLeft"))
                if pct is not None:
                    fields["cassette_pct"] = pct
                days = _cassette_days_remaining(refill.get("timeLeftText"))
                if days is not None:
                    fields["cassette_days_remaining"] = days

    if not fields:
        return None

    # A status snapshot prefers the water body's own latest measurement time, but falls back
    # to the collection time when that is absent or implausible (documented behaviour; the
    # field set is a snapshot, not a measurement series).
    ts = water_body.get("latestMeasureTime")
    ts_ns = _record_time_ns(ts) if ts else None
    if ts_ns is None:
        if ts:
            _LOGGER.warning("Status snapshot had an unusable latestMeasureTime: %r", ts)
        ts_ns = fallback_ns
    return Point(MEAS_STATUS, tags={"water_body_id": wb_id}, fields=fields, timestamp_ns=ts_ns)


def dashboard_to_points(dashboard: dict[str, Any], now_ns: int) -> list[Point]:
    """Convert a ``prod-getDashboardView`` response into measure + status points."""
    points: list[Point] = []
    for wb in dashboard.get("waterBodies", []) or []:
        points.extend(_measurement_points(wb))
        status = _status_point(wb, now_ns)
        if status is not None:
            points.append(status)
    return points
