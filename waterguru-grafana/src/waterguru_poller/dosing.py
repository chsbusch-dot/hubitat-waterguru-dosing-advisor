"""Chlorine dosing + tank inventory collector.

The Grafana chlorine/tank panels need measurements that the WaterGuru cloud
poller cannot supply (see ``grafana/CHLORINE-CHARTS.md``):

===================  =============  ==================================================
Measurement          Tag            Fields
===================  =============  ==================================================
``chlorine_dose``    ``device_id``  ``volume_ml``, ``runtime_seconds``
``chlorine_tank``    ``device_id``  ``remaining_ml``, ``capacity_ml``,
                                    ``remaining_percent``, ``runway_days``
``chlorine_target``  ``device_id``  ``measured_ppm``, ``target_ppm``
===================  =============  ==================================================

The values live on the Hubitat hub, on the ``WaterGuru Dosing Tile`` device that
the WaterGuru Dosing Advisor app maintains. This module reads that device and
writes the three measurements.

Read path
---------
Hubitat Maker API would be the obvious REST source, but it only serves devices
explicitly selected in the app, and the tile is not selected -- it returns
``404 Device not found or not authorized``. Adding it means rewriting the
settings of a *system* app, so instead this uses the already-authorised MCP Rule
Server endpoint, which answers a bare JSON-RPC ``tools/call`` with no session
handshake. That requires no change on the hub at all.

Two MCP tools are used:

* ``hub_get_device`` — the device's **current** attribute snapshot. Used for the
  tank inventory, the advisor FC target, and as a fallback dose reading.
* ``hub_list_device_events`` — the device's **append-only event history**. This is the
  primary dose source: the snapshot only ever carries the single ``last dose``, so a
  poller that reads just the snapshot silently loses every dose between two runs.
  Events are queried with a persisted ``since`` bookmark (``WG_STATE_DIR``) and are
  limited to the hub's **seven-day** event retention (``EVENT_RECOVERY_DAYS``); a gap
  longer than that is unrecoverable and is logged as a warning.

Because the hub default/cap for this tool is 100/500 rows and it filters
**client-side**, a single unfiltered call can return the newest 100 events of a busy
tile and silently drop older doses. The collector therefore issues **three
deliberately filtered reads** — one per dosing attribute
(``lastDoseEpochMs``, ``lastDoseMl``, ``lastDoseRuntimeSeconds``) — each with
``attribute=<name>`` and ``limit=500``, and combines them before pairing. A
required-attribute response that is saturated (``count >= 500``) is treated as
incomplete: the run warns, does **not** advance the cursor, and falls back to the
snapshot dose rather than presenting a truncated history as lossless.

Both tokens live in Bitwarden Secrets Manager (``HUBITAT_TOKEN`` for Maker API,
``MCP_HUBITAT_TOKEN`` for this endpoint); they are read from the environment
only, never written to disk or logged.

Accepted event schema
---------------------
The event listing returned by ``hub_list_device_events`` is parsed defensively,
because the live wire response still needs post-deploy validation against the
deployed server version. The collector accepts a JSON object (or a JSON-RPC
``result.content[0].text`` string containing one, or a direct
``result.content[0]`` object) with the events under ``events``/``deviceEvents``/
``data``/``items``/``result``, for example::

    {"events": [
        {"name": "lastDoseEpochMs", "value": 1790390724739,
         "date": "2026-09-26T02:45:24.739+0000"},
        {"name": "lastDoseMl",      "value": 237.3273375, "unit": "mL"}
    ],
     "count": 2,
     "sinceMode": "since",
     "sinceTimestamp": "2026-09-19T00:00:00.000Z",
     "since": "2026-09-19T00:00:00.000Z"}

``count``, ``sinceMode`` and ``sinceTimestamp`` are the current upstream envelope
fields. The response's ``since`` is an **echo of the caller's own old explicit
bookmark, not a next cursor** — the cursor is advanced from the newest returned
event ``date`` instead, and only when every *required* history read completed.

Each event needs ``name`` (or ``attribute``) plus a time in ``epoch``/``epochMs``/
``timestamp``/``time`` (numeric) or ``date``/``dateTime``/``isoDate`` (ISO-8601).
A dose is emitted **only** when a ``lastDoseEpochMs`` event has a matching
``lastDoseMl`` event within ``EVENT_PAIR_TOLERANCE_NS``: a lone attribute is never
turned into an invented dose. ``lastDoseRuntimeSeconds`` is optional.

Why dose points are safe to re-read
-----------------------------------
A dose point is written at the *original* recorded dose time
(``lastDoseEpochMs``, or the event's own timestamp), so re-reading the same dose
rewrites the identical series+timestamp instead of appending a duplicate. That
satisfies the chart spec's requirement that re-reading a dose must not create
another dose record. Tank points use the current time and are plain snapshots;
the queries take the latest.

Nothing here can command the pump. This module only reads device attributes and
events, and writes time-series points.
"""

from __future__ import annotations

import json
import logging
import math
import os
import re
import time
from dataclasses import dataclass, field, replace
from datetime import UTC, datetime
from typing import Any

import requests

from .lineprotocol import Point, is_plausible_timestamp_ns
from .mapping import parse_iso_ns

_LOGGER = logging.getLogger(__name__)

# Tile attribute -> destination field. Missing attributes stay absent rather than
# becoming zero, as the chart spec requires for independently optional values.
_TANK_FIELDS = {
    "remaining_ml": "tankRemainingMl",
    "capacity_ml": "tankCapacityMl",
    "remaining_percent": "tankPercent",
    "runway_days": "tankRunwayDays",
}
_DOSE_VOLUME = "lastDoseMl"
_DOSE_TIME_MS = "lastDoseEpochMs"
_DOSE_RUNTIME = "lastDoseRuntimeSeconds"

# The advisor's own free-chlorine target, which WaterGuru does not publish: the app derives
# it from CYA (or a manual override) so a stabilized or recovering pool is not dosed to
# WaterGuru's flat ~3 ppm. The reading it was computed against is stored alongside it, so a
# panel can show the pair without joining another measurement.
_TARGET_FIELDS = {
    "measured_ppm": "freeChlorine",
    "target_ppm": "targetFreeChlorine",
}
# When the driver exposes the calculation instant as epoch milliseconds, the target pair is
# timestamped there instead of at collection time. ``lastCalc`` is a locale-formatted string
# and is deliberately NOT parsed.
_TARGET_TIME_MS = "lastCalcEpochMs"

MS_PER_NS = 1_000_000

# Conservative plausibility bounds. A value outside these ranges is a malformed payload, not
# a reading: the charts must not be poisoned by a corrupt attribute, and a bad value must
# never suppress the unrelated WaterGuru history sharing the run.
MAX_DOSE_VOLUME_ML = 10_000.0  # 10 L in one commanded dose
MAX_RUNTIME_SECONDS = 86_400.0  # more than a day of pump runtime is not a dose
MAX_TANK_ML = 1_000_000.0  # 1000 L tank
MAX_PERCENT = 100.0
MAX_RUNWAY_DAYS = 3_650.0
MAX_PPM = 100.0

# Per-field upper bound for the tank snapshot (every field is lower-bounded at zero).
_TANK_BOUNDS = (
    ("remaining_ml", _TANK_FIELDS["remaining_ml"], MAX_TANK_ML),
    ("capacity_ml", _TANK_FIELDS["capacity_ml"], MAX_TANK_ML),
    ("remaining_percent", _TANK_FIELDS["remaining_percent"], MAX_PERCENT),
    ("runway_days", _TANK_FIELDS["runway_days"], MAX_RUNWAY_DAYS),
)

# The hub keeps device events for seven days; a longer gap cannot be recovered.
EVENT_RECOVERY_DAYS = 7
# ``hub_list_device_events`` defaults to 100 rows and caps at 500, filtering client-side.
# Always ask for both an explicit attribute and the cap, so unrelated tile traffic cannot
# consume the budget for the dosing attributes.
EVENT_PAGE_LIMIT = 500
# Attribute events from one dose are emitted together, so their timestamps differ by well
# under a second. A minute of slack absorbs clock jitter without pairing unrelated events.
EVENT_PAIR_TOLERANCE_NS = 60 * 1_000_000_000

_EVENT_LIST_KEYS = ("events", "deviceEvents", "device_events", "data", "items", "result")
_EVENT_TIME_NUMERIC_KEYS = ("epoch", "epochMs", "timestamp", "time")
_EVENT_TIME_STRING_KEYS = ("date", "dateTime", "isoDate", "created")
_DOSE_EVENT_NAMES = {_DOSE_TIME_MS, _DOSE_VOLUME, _DOSE_RUNTIME}
# Required for a coherent dose; runtime is best-effort.
_REQUIRED_DOSE_ATTRIBUTES = (_DOSE_TIME_MS, _DOSE_VOLUME)
_OPTIONAL_DOSE_ATTRIBUTES = (_DOSE_RUNTIME,)

_MAX_DIAGNOSTIC = 300


class HubError(RuntimeError):
    """Raised when the hub endpoint cannot be read or returns an error."""


@dataclass(frozen=True)
class DosingResult:
    """Outcome of one collection pass.

    ``events_ok`` and ``snapshot_ok`` report per-source health: both are fetched
    independently, so a failure in one still publishes the other. They let the caller
    distinguish "the hub had nothing to say" from "the hub could not be read".
    """

    device_id: str
    dose_points: int
    tank_points: int
    written: int
    dry_run: bool
    target_points: int = 0
    dose_event_points: int = 0
    points: list[Point] = field(default_factory=list)
    events_ok: bool = True
    snapshot_ok: bool = True
    events_error: str | None = None
    snapshot_error: str | None = None


@dataclass(frozen=True)
class EventPage:
    """One ``hub_list_device_events`` response: the events plus the upstream envelope.

    ``count`` is the server's returned-row count (capped at ``EVENT_PAGE_LIMIT``), used to
    detect a saturated/incomplete history. ``since``/``sinceTimestamp`` are echoed back for
    diagnostics only: they are an echo of the caller's old bookmark, **not** a next cursor.
    """

    events: list[dict[str, Any]] = field(default_factory=list)
    count: int | None = None
    since_mode: str | None = None
    since_timestamp: Any = None


def _bounded_text(value: Any, limit: int = _MAX_DIAGNOSTIC) -> str:
    """Bound a diagnostic string. Never include tokens or whole response bodies."""
    text = str(value)
    return text if len(text) <= limit else text[:limit] + "..."


def _num(value: Any) -> float | None:
    """Coerce a hub attribute to a finite float, or None when absent/unusable."""
    if value is None or isinstance(value, bool):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def _bounded(value: Any, *, low: float, high: float) -> float | None:
    """Coerce to a finite float inside ``[low, high]``, else None."""
    number = _num(value)
    if number is None or number < low or number > high:
        return None
    return number


def _epoch_ms_to_ns(value: Any) -> int | None:
    """Convert epoch milliseconds to a plausible nanosecond timestamp, else None."""
    ms = _num(value)
    if ms is None or ms <= 0:
        return None
    timestamp_ns = int(ms) * MS_PER_NS
    return timestamp_ns if is_plausible_timestamp_ns(timestamp_ns) else None


def _format_since(timestamp_ns: int) -> str:
    """Format a nanosecond epoch as the ISO-8601 UTC bookmark sent to the hub."""
    return datetime.fromtimestamp(timestamp_ns / 1e9, tz=UTC).strftime("%Y-%m-%dT%H:%M:%S.%f")[
        :-3
    ] + "Z"


# --------------------------------------------------------------------------- decoding


def _extract_json(response: requests.Response) -> Any:
    """Decode a hub response, including Streamable-HTTP SSE framing.

    The MCP endpoint answers with ``text/event-stream``: the JSON-RPC payload is the
    ``data:`` line of the last event, so calling ``response.json()`` on the raw
    event-stream text fails. Parse the extracted ``data:`` body instead.
    """
    text = getattr(response, "text", "") or ""
    data = _sse_data(text)
    if data is None:
        stripped = text.strip()
        data = stripped if stripped.startswith(("{", "[")) else None
    if data is not None:
        try:
            return json.loads(data)
        except json.JSONDecodeError as exc:
            raise HubError(
                f"hub response was not JSON: {exc}: {_bounded_text(data)}"
            ) from exc
    try:
        return response.json()
    except ValueError as exc:
        raise HubError(f"hub response was not JSON: {exc}: {_bounded_text(text)}") from exc


def _sse_data(text: str) -> str | None:
    """Return the payload of the last SSE event, or None when the text is not SSE."""
    if "data:" not in text:
        return None
    for block in reversed(re.split(r"\r?\n\r?\n", text)):
        parts = [
            line[len("data:") :].lstrip()
            for line in block.splitlines()
            if line.startswith("data:")
        ]
        if parts:
            return "\n".join(parts)
    return None


def _unwrap_content(payload: Any) -> Any:
    """Unwrap a JSON-RPC ``result.content[0]`` envelope when present.

    Both shapes the server has produced are accepted: a ``content[0].text`` JSON string,
    and a ``content[0]`` object carrying the device/event fields directly.
    """
    if not isinstance(payload, dict):
        return payload
    if "error" in payload:
        raise HubError(f"hub returned an error: {_bounded_text(payload['error'])}")
    if "result" not in payload:
        return payload
    result = payload["result"]
    if isinstance(result, dict):
        content = result.get("content")
        if isinstance(content, list) and content:
            first = content[0]
            if isinstance(first, dict):
                text = first.get("text")
                if isinstance(text, str):
                    try:
                        return json.loads(text)
                    except json.JSONDecodeError as exc:
                        raise HubError(f"hub content was not JSON: {exc}") from exc
                # Direct-object envelope (``content[0]`` is the payload itself).
                if any(key in first for key in (*_EVENT_LIST_KEYS, "attributes")):
                    return first
        return result
    return result


def parse_attributes(payload: Any) -> dict[str, Any]:
    """Extract ``{attribute_name: value}`` from a hub device payload.

    The MCP endpoint wraps the device JSON in ``result.content[0].text`` as a
    string; a Maker API style payload carries ``attributes`` as a list of
    ``{name, value, currentValue}``. All shapes are accepted.
    """
    inner = _unwrap_content(payload)
    if isinstance(inner, str):
        try:
            inner = json.loads(inner)
        except json.JSONDecodeError as exc:
            raise HubError(f"hub content was not JSON: {exc}") from exc
    if not isinstance(inner, dict):
        raise HubError(f"expected a JSON object from the hub, got {type(inner).__name__}")

    attributes = inner.get("attributes")
    if isinstance(attributes, dict):
        return dict(attributes)
    if isinstance(attributes, list):
        out: dict[str, Any] = {}
        for entry in attributes:
            if isinstance(entry, dict) and "name" in entry:
                out[str(entry["name"])] = entry.get("currentValue", entry.get("value"))
        return out
    raise HubError("hub payload contained no attributes")


def event_time_ns(event: dict[str, Any]) -> int | None:
    """Return an event's timestamp in nanoseconds, or None when it cannot be read."""
    for key in _EVENT_TIME_NUMERIC_KEYS:
        if key in event:
            value = _num(event[key])
            if value is None or value <= 0:
                continue
            # Hubitat epochs are milliseconds; tolerate seconds for robustness. Keep integer
            # milliseconds in integer arithmetic -- a float multiply by 1e6 loses ns precision.
            if value > 1e11:
                ns = int(value) * MS_PER_NS if value.is_integer() else int(value * MS_PER_NS)
            else:
                ns = int(round(value * 1_000_000_000))
            return ns if is_plausible_timestamp_ns(ns) else None
    for key in _EVENT_TIME_STRING_KEYS:
        raw = event.get(key)
        if isinstance(raw, str) and raw:
            try:
                ns = parse_iso_ns(raw)
            except (ValueError, OverflowError):
                return None
            return ns if is_plausible_timestamp_ns(ns) else None
    return None


def parse_events(payload: Any) -> list[dict[str, Any]]:
    """Extract the event list from a ``hub_list_device_events`` payload."""
    return parse_event_page(payload).events


def parse_event_page(payload: Any) -> EventPage:
    """Extract the event list plus the upstream envelope fields from a payload.

    Current upstream (``toolGetDeviceHistory``) returns ``events`` with ``count``,
    ``sinceMode`` and ``sinceTimestamp``, plus whichever bounding argument the caller
    sent (``hoursBack`` or ``since``). Unrecognised keys are ignored.
    """
    inner = _unwrap_content(payload)
    if isinstance(inner, list):
        return EventPage(events=[event for event in inner if isinstance(event, dict)])
    if not isinstance(inner, dict):
        raise HubError(f"expected a hub events object or list, got {type(inner).__name__}")

    found: list[Any] | None = None
    for key in _EVENT_LIST_KEYS:
        value = inner.get(key)
        if isinstance(value, list):
            found = value
            break
    if found is None:
        keys = ", ".join(sorted(str(key) for key in inner)[:12])
        raise HubError(f"hub events payload had no event list (keys: {keys})")

    count = inner.get("count")
    since_mode = inner.get("sinceMode")
    return EventPage(
        events=[event for event in found if isinstance(event, dict)],
        count=int(count) if isinstance(count, int) and not isinstance(count, bool) else None,
        since_mode=str(since_mode) if isinstance(since_mode, str) else None,
        since_timestamp=inner.get("sinceTimestamp"),
    )


# --------------------------------------------------------------------------- transport


def _post_tool(
    url: str,
    token: str,
    name: str,
    arguments: dict[str, Any],
    *,
    timeout: float,
    session: requests.Session | None,
) -> Any:
    """Call one MCP tool and return the decoded JSON payload.

    A bare ``tools/call`` is enough: the endpoint answered one with HTTP 200 and
    no session handshake, so there is no initialize/teardown dance to maintain.
    """
    body = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments},
    }
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    }
    http = session or requests.Session()
    try:
        response = http.post(url, json=body, headers=headers, timeout=timeout)
    except requests.RequestException as exc:
        raise HubError(f"hub request failed: {exc}") from exc
    if response.status_code != 200:
        raise HubError(
            f"hub returned HTTP {response.status_code}: {_bounded_text(response.text)}"
        )
    return _extract_json(response)


def fetch_device_attributes(
    url: str,
    token: str,
    device_id: str,
    *,
    timeout: float = 30.0,
    session: requests.Session | None = None,
) -> dict[str, Any]:
    """Read one device's current attribute snapshot from the hub MCP endpoint."""
    payload = _post_tool(
        url,
        token,
        "hub_get_device",
        {"deviceId": str(device_id), "mode": "summary"},
        timeout=timeout,
        session=session,
    )
    return parse_attributes(payload)


def fetch_device_events(
    url: str,
    token: str,
    device_id: str,
    *,
    attribute: str | None = None,
    since: str | None = None,
    hours_back: int | None = None,
    limit: int | None = EVENT_PAGE_LIMIT,
    timeout: float = 30.0,
    session: requests.Session | None = None,
) -> Any:
    """Read a device's event history from the hub MCP endpoint.

    The hub filters **client-side** and defaults to 100 rows (cap 500), newest-first, so an
    unfiltered call can drop older doses. Always pass ``attribute`` to scope the read and
    ``limit`` to request the cap. Pass either ``since`` (an absolute bookmark) or
    ``hours_back`` (bounded by the hub's seven-day retention).
    """
    arguments: dict[str, Any] = {"deviceId": str(device_id)}
    if attribute:
        arguments["attribute"] = str(attribute)
    if since:
        arguments["since"] = since
    elif hours_back is not None:
        arguments["hoursBack"] = int(hours_back)
    if limit is not None:
        arguments["limit"] = int(limit)
    return _post_tool(
        url,
        token,
        "hub_list_device_events",
        arguments,
        timeout=timeout,
        session=session,
    )


# --------------------------------------------------------------------------- cursor


class EventCursor:
    """The persisted ``since`` bookmark for one device's event history.

    Stored under ``WG_STATE_DIR`` so a restart resumes where the last successful run left
    off. Only advanced after the points have been written, so a failed write re-reads the
    same window (idempotent, because dose points carry their original timestamps).
    """

    FILE_NAME = "hubitat-events-cursor.json"

    def __init__(self, path: str, device_key: str, since_ns: int | None) -> None:
        self.path = path
        self.device_key = device_key
        self.since_ns = since_ns

    @classmethod
    def load(cls, state_dir: str, device_key: str) -> EventCursor:
        path = os.path.join(state_dir, cls.FILE_NAME)
        since_ns: int | None = None
        try:
            with open(path, encoding="utf-8") as fh:
                data = json.load(fh)
            devices = data.get("devices") if isinstance(data, dict) else None
            entry = devices.get(str(device_key)) if isinstance(devices, dict) else None
            raw = entry.get("since_ns") if isinstance(entry, dict) else None
            if isinstance(raw, int) and not isinstance(raw, bool):
                since_ns = raw
        except (OSError, ValueError):
            since_ns = None
        return cls(path=path, device_key=str(device_key), since_ns=since_ns)

    def query_since(self, now_ns: int) -> str | None:
        """Return the bookmark to send, clamped to the hub's event-retention window."""
        if self.since_ns is None:
            return None
        oldest = now_ns - EVENT_RECOVERY_DAYS * 86_400 * 1_000_000_000
        if self.since_ns > now_ns + 3_600 * 1_000_000_000:
            _LOGGER.warning("Hubitat event cursor is in the future; ignoring it for this run")
            return None
        if self.since_ns < oldest:
            _LOGGER.warning(
                "Hubitat event cursor is older than the %d-day event window; "
                "re-reading the available window",
                EVENT_RECOVERY_DAYS,
            )
            return _format_since(oldest)
        return _format_since(self.since_ns)

    def advance(self, events: list[dict[str, Any]]) -> None:
        """Move the cursor to the newest returned event's own timestamp.

        The response's ``since`` is an echo of the caller's old bookmark, not a next cursor,
        so it is deliberately not consulted. Callers only advance when every *required*
        history read completed, so a truncated window cannot skip older events.
        """
        newest: int | None = None
        for event in events:
            timestamp_ns = event_time_ns(event)
            if timestamp_ns is not None and (newest is None or timestamp_ns > newest):
                newest = timestamp_ns
        if newest is not None and (self.since_ns is None or newest > self.since_ns):
            self.since_ns = newest

    def save(self) -> None:
        """Persist the cursor atomically, preserving other devices' entries."""
        if self.since_ns is None:
            return
        data: dict[str, Any] = {}
        try:
            with open(self.path, encoding="utf-8") as fh:
                loaded = json.load(fh)
            if isinstance(loaded, dict):
                data = loaded
        except (OSError, ValueError):
            data = {}
        devices = data.get("devices")
        if not isinstance(devices, dict):
            devices = {}
        devices[self.device_key] = {
            "since_ns": self.since_ns,
            "since_iso": _format_since(self.since_ns),
        }
        data["devices"] = devices
        directory = os.path.dirname(self.path)
        if directory:
            os.makedirs(directory, exist_ok=True)
        tmp_path = f"{self.path}.tmp"
        with open(tmp_path, "w", encoding="utf-8") as fh:
            json.dump(data, fh, indent=2, sort_keys=True)
            fh.write("\n")
        os.replace(tmp_path, self.path)


# --------------------------------------------------------------------------- point building


def build_points(
    attributes: dict[str, Any],
    device_id: str,
    *,
    now_ns: int | None = None,
) -> list[Point]:
    """Turn a tile attribute snapshot into the chart measurements.

    This is the *snapshot* path: the tank inventory, the advisor FC target, and a dose
    fallback from the current ``lastDose*`` attributes. Historical doses come from
    :func:`events_to_points`; the fallback keeps a single dose visible if event history is
    unavailable. ``chlorine_target`` is timestamped at ``lastCalcEpochMs`` when that is a
    valid epoch, and at collection time otherwise.
    """
    points: list[Point] = []
    tags = {"device_id": str(device_id)}
    collection_ns = now_ns if now_ns is not None else time.time_ns()

    volume = _bounded(attributes.get(_DOSE_VOLUME), low=0.0, high=MAX_DOSE_VOLUME_ML)
    dose_ns = _epoch_ms_to_ns(attributes.get(_DOSE_TIME_MS))
    runtime = _bounded(attributes.get(_DOSE_RUNTIME), low=0.0, high=MAX_RUNTIME_SECONDS)
    # A dose needs both a plausible volume and its recorded time; neither alone is a dose.
    if volume is not None and dose_ns is not None:
        dose_fields: dict[str, Any] = {"volume_ml": volume}
        # The commanded pump runtime is what the volume is derived from, so carrying both
        # makes a drift in the pump rate visible. Optional: an older driver that omits it
        # leaves the field absent rather than zero.
        if runtime is not None:
            dose_fields["runtime_seconds"] = runtime
        points.append(
            Point(
                measurement="chlorine_dose",
                tags=tags,
                fields=dose_fields,
                timestamp_ns=dose_ns,
            )
        )

    tank = {
        field: _bounded(attributes.get(attr), low=0.0, high=high)
        for field, attr, high in _TANK_BOUNDS
    }
    if any(value is not None for value in tank.values()):
        points.append(
            Point(
                measurement="chlorine_tank",
                tags=tags,
                fields=tank,
                timestamp_ns=collection_ns,
            )
        )

    # The advisor's target plus the reading it was computed against. Both are independently
    # optional, so a driver reporting only one still stores that one.
    target = {
        field: _bounded(attributes.get(attr), low=0.0, high=MAX_PPM)
        for field, attr in _TARGET_FIELDS.items()
    }
    if any(value is not None for value in target.values()):
        target_ns = _epoch_ms_to_ns(attributes.get(_TARGET_TIME_MS)) or collection_ns
        points.append(
            Point(
                measurement="chlorine_target",
                tags=tags,
                fields=target,
                timestamp_ns=target_ns,
            )
        )

    return points


def _closest_value(
    records: list[tuple[str, int, float]], name: str, around_ns: int
) -> float | None:
    """Return the value of ``name`` whose event time is closest to ``around_ns``."""
    best: tuple[int, float] | None = None
    for record_name, timestamp_ns, value in records:
        if record_name != name:
            continue
        distance = abs(timestamp_ns - around_ns)
        if distance > EVENT_PAIR_TOLERANCE_NS:
            continue
        if best is None or distance < best[0]:
            best = (distance, value)
    return best[1] if best is not None else None


def events_to_points(
    events: list[dict[str, Any]],
    device_id: str,
    *,
    now_ns: int | None = None,
) -> list[Point]:
    """Turn append-only hub events into ``chlorine_dose`` points at their original time.

    Only a coherent dose is emitted: a ``lastDoseEpochMs`` event must be accompanied by a
    ``lastDoseMl`` event within :data:`EVENT_PAIR_TOLERANCE_NS`, otherwise no dose is
    written (a lone attribute is not evidence of a dose). ``lastDoseRuntimeSeconds`` is
    optional. Tank/target snapshots are not derived from events; the current snapshot path
    handles those.

    When one dose carries several ``lastDoseEpochMs`` events -- the advisor republishes a
    dose under its *original* epoch to correct the volume (an overrun/early-stop booked from
    the observed run) or to void it (republished as 0 mL) -- the **latest** marker wins, by
    the event's own hub timestamp rather than its position in the list. The correction/void
    thus anchors the pairing and, because every marker for that dose shares the one epoch,
    overwrites the original dose's point at its original timestamp rather than adding a
    second point. The result does not depend on the hub's newest-first listing order.
    """
    records: list[tuple[str, int, float]] = []
    for event in events:
        name = event.get("name", event.get("attribute"))
        if name not in _DOSE_EVENT_NAMES:
            continue
        timestamp_ns = event_time_ns(event)
        value = _num(event.get("value", event.get("currentValue")))
        if timestamp_ns is None or value is None:
            continue
        records.append((str(name), timestamp_ns, value))

    markers: dict[int, int] = {}
    for name, timestamp_ns, value in records:
        if name != _DOSE_TIME_MS:
            continue
        dose_ns = _epoch_ms_to_ns(value)
        if dose_ns is None:
            continue
        # The event's own time is the pairing anchor; the attribute time is the point time.
        # When one dose has several markers -- the advisor republishes a dose under its
        # original ``lastDoseEpochMs`` to correct the volume or to void it as 0 mL -- the
        # LATEST marker wins (by the event's own hub timestamp, not list position), so the
        # correction/void anchors the pairing and overwrites the original dose's point.
        # Depending on ``setdefault`` keeping the first-seen entry is wrong: it only gave the
        # right answer because the hub happens to list events newest-first, which nothing
        # guarantees.
        existing = markers.get(dose_ns)
        if existing is None or timestamp_ns > existing:
            markers[dose_ns] = timestamp_ns

    points: list[Point] = []
    for dose_ns in sorted(markers):
        anchor_ns = markers[dose_ns]
        volume = _closest_value(records, _DOSE_VOLUME, anchor_ns)
        if volume is None:
            _LOGGER.warning(
                "Hubitat reported a dose at %s with no matching volume event; skipping it",
                _format_since(dose_ns),
            )
            continue
        if not 0.0 <= volume <= MAX_DOSE_VOLUME_ML:
            _LOGGER.warning("Ignoring an implausible dose volume from the hub event history")
            continue
        fields: dict[str, Any] = {"volume_ml": volume}
        runtime = _closest_value(records, _DOSE_RUNTIME, anchor_ns)
        if runtime is not None and 0.0 <= runtime <= MAX_RUNTIME_SECONDS:
            fields["runtime_seconds"] = runtime
        points.append(
            Point(
                measurement="chlorine_dose",
                tags={"device_id": str(device_id)},
                fields=fields,
                timestamp_ns=dose_ns,
            )
        )
    return points


# --------------------------------------------------------------------------- collection


def _is_saturated(page: EventPage) -> bool:
    """True when the server returned as many rows as it can, so history may be truncated."""
    if page.count is not None and page.count >= EVENT_PAGE_LIMIT:
        return True
    return len(page.events) >= EVENT_PAGE_LIMIT


def _dedupe_snapshot_dose(event_points: list[Point], snapshot_points: list[Point]) -> list[Point]:
    """Merge the snapshot dose into event doses so one physical dose is one point.

    Event fields stay authoritative; a missing optional runtime is filled from the snapshot.
    Only snapshot points that are not duplicates of an event dose are kept, so an
    unavailable/truncated event history still leaves the snapshot fallback intact.
    """
    snapshot_doses = {
        point.timestamp_ns: point
        for point in snapshot_points
        if point.measurement == "chlorine_dose"
    }
    merged: list[Point] = []
    event_dose_timestamps: set[int | None] = set()
    for point in event_points:
        if point.measurement != "chlorine_dose":
            merged.append(point)
            continue
        event_dose_timestamps.add(point.timestamp_ns)
        snapshot = snapshot_doses.get(point.timestamp_ns)
        if snapshot is None:
            merged.append(point)
            continue
        fields = dict(point.fields)
        for key, value in snapshot.fields.items():
            if value is not None and key not in fields:
                fields[key] = value
        merged.append(replace(point, fields=fields))
    merged.extend(
        point
        for point in snapshot_points
        if point.measurement != "chlorine_dose"
        or point.timestamp_ns not in event_dose_timestamps
    )
    return merged


def _counts(points: list[Point]) -> tuple[int, int, int]:
    dose = sum(1 for p in points if p.measurement == "chlorine_dose")
    tank = sum(1 for p in points if p.measurement == "chlorine_tank")
    target = sum(1 for p in points if p.measurement == "chlorine_target")
    return dose, tank, target


def _fetch_dose_pages(
    url: str,
    token: str,
    device_key: str,
    *,
    bounding: dict[str, Any],
    timeout: float,
    session: requests.Session | None,
) -> tuple[dict[str, EventPage], dict[str, str], list[str]]:
    """Read each dosing attribute's history with an explicit ``attribute`` filter.

    Returns ``(pages, failures, saturated)``. One attribute failing never aborts the others:
    marker+volume are required for a coherent dose, runtime is best-effort.
    """
    pages: dict[str, EventPage] = {}
    failures: dict[str, str] = {}
    saturated: list[str] = []
    for attribute in (*_REQUIRED_DOSE_ATTRIBUTES, *_OPTIONAL_DOSE_ATTRIBUTES):
        try:
            payload = fetch_device_events(
                url,
                token,
                device_key,
                attribute=attribute,
                limit=EVENT_PAGE_LIMIT,
                timeout=timeout,
                session=session,
                **bounding,
            )
            page = parse_event_page(payload)
        except HubError as exc:
            failures[attribute] = f"{type(exc).__name__}: {exc}"
            continue
        pages[attribute] = page
        if _is_saturated(page):
            saturated.append(attribute)
    return pages, failures, saturated


def collect(
    *,
    url: str,
    token: str,
    device_id: str,
    writer: Any | None = None,
    dry_run: bool = False,
    timeout: float = 30.0,
    session: requests.Session | None = None,
    state_dir: str | None = None,
    now_ns: int | None = None,
) -> DosingResult:
    """Read the dosing tile (events + snapshot) and write the chlorine measurements.

    ``writer`` needs only a ``write(points) -> int`` method (``InfluxWriter``). Event
    history and the attribute snapshot are fetched independently: a hub failure in one
    still publishes the other, and the cursor only advances after a successful write and a
    complete required history read.
    """
    now_ns = now_ns if now_ns is not None else time.time_ns()
    device_key = str(device_id)
    cursor = EventCursor.load(state_dir, device_key) if state_dir else None

    # --- append-only event history: the primary dose source ---
    # Scope each read by attribute: the hub filters client-side and defaults to 100 rows, so
    # unrelated tile traffic would otherwise consume the budget and truncate older doses.
    event_points: list[Point] = []
    events_ok = True
    events_error: str | None = None
    since = cursor.query_since(now_ns) if cursor is not None else None
    bounding: dict[str, Any] = (
        {"since": since} if since is not None else {"hours_back": EVENT_RECOVERY_DAYS * 24}
    )
    pages, failures, saturated = _fetch_dose_pages(
        url, token, device_key, bounding=bounding, timeout=timeout, session=session
    )
    required_complete = not any(
        attribute in failures or attribute in saturated
        for attribute in _REQUIRED_DOSE_ATTRIBUTES
    )
    runtime_usable = _DOSE_RUNTIME in pages and _DOSE_RUNTIME not in saturated
    # Only a complete required read is trusted as history; otherwise the snapshot dose is the
    # honest answer and the cursor stays put so the next run re-reads the same window.
    merged_events: list[dict[str, Any]] = [
        event
        for attribute in _REQUIRED_DOSE_ATTRIBUTES
        for event in pages.get(attribute, EventPage()).events
    ]
    if required_complete:
        if runtime_usable:
            merged_events += pages[_DOSE_RUNTIME].events
        event_points = events_to_points(merged_events, device_key)
        if cursor is not None:
            cursor.advance(merged_events)

    problems = [
        f"{attribute} history failed ({failures[attribute]})"
        if attribute in failures
        else f"{attribute} history saturated at limit {EVENT_PAGE_LIMIT}"
        for attribute in (*_REQUIRED_DOSE_ATTRIBUTES, *_OPTIONAL_DOSE_ATTRIBUTES)
        if attribute in failures or attribute in saturated
    ]
    if problems:
        events_ok = False
        events_error = "; ".join(problems)
        _LOGGER.warning(
            "Hubitat dose history incomplete (%s)%s",
            events_error,
            "; using the snapshot dose and not advancing the cursor"
            if not required_complete
            else "; runtime omitted from otherwise coherent doses",
        )

    # --- current snapshot: tank inventory, FC target, and a dose fallback ---
    snapshot_points: list[Point] = []
    snapshot_ok = True
    snapshot_error: str | None = None
    try:
        attributes = fetch_device_attributes(
            url, token, device_key, timeout=timeout, session=session
        )
        snapshot_points = build_points(attributes, device_key, now_ns=now_ns)
    except HubError as exc:
        snapshot_ok = False
        snapshot_error = f"{type(exc).__name__}: {exc}"
        _LOGGER.warning("Dosing tile snapshot read failed: %s", exc)

    # One physical dose yields one point: the snapshot dose is merged into a matching
    # event-history dose (event fields win) instead of being written twice.
    points = _dedupe_snapshot_dose(event_points, snapshot_points)
    dose_points, tank_points, target_points = _counts(points)

    if not points:
        _LOGGER.warning(
            "Dosing tile %s exposed no dose, tank or target fields; nothing to write", device_key
        )
        return DosingResult(
            device_id=device_key,
            dose_points=0,
            tank_points=0,
            written=0,
            dry_run=dry_run,
            points=[],
            events_ok=events_ok,
            snapshot_ok=snapshot_ok,
            events_error=events_error,
            snapshot_error=snapshot_error,
        )

    written = 0
    if dry_run or writer is None:
        for point in points:
            _LOGGER.info("dry run: %s", point.to_line())
    else:
        written = writer.write(points)
        if cursor is not None:
            # Only persisted after a successful write, so a failed run re-reads the same
            # window (safe: dose points carry their original timestamps).
            cursor.save()

    return DosingResult(
        device_id=device_key,
        dose_points=dose_points,
        tank_points=tank_points,
        written=written,
        dry_run=dry_run,
        target_points=target_points,
        dose_event_points=sum(1 for p in event_points if p.measurement == "chlorine_dose"),
        points=points,
        events_ok=events_ok,
        snapshot_ok=snapshot_ok,
        events_error=events_error,
        snapshot_error=snapshot_error,
    )
