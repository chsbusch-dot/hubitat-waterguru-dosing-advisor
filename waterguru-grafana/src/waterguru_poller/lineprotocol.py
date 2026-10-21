"""InfluxDB line-protocol serialization.

Kept dependency-free and pure so it is trivially unit-testable. The one rule that
matters for idempotency: identical measurement + tag-set + timestamp overwrites the
existing point in InfluxDB, so re-running the poller over an overlapping window never
creates duplicates as long as we tag consistently (pod id / water body id / type).
"""

from __future__ import annotations

import math
from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import UTC, datetime

# Conservative plausibility window for a stored reading. Outside it a timestamp cannot be a
# real pool measurement -- it is a malformed payload, and InfluxDB would happily persist a
# year-3000 point that then dominates every chart's range. Ingestors reject instead of writing.
MIN_PLAUSIBLE_NS = int(datetime(2000, 1, 1, tzinfo=UTC).timestamp()) * 1_000_000_000
MAX_PLAUSIBLE_NS = int(datetime(2100, 1, 1, tzinfo=UTC).timestamp()) * 1_000_000_000


def finite_float(value: object) -> float | None:
    """Coerce a value to a finite float, or ``None`` when it is not one.

    ``bool`` is rejected (it is an ``int`` subclass), as are ``NaN``, positive/negative
    infinity, non-numeric strings, and integers too large to represent. These values are
    never a real measurement, so callers drop them rather than writing them.
    """
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    try:
        number = float(value)
    except (OverflowError, ValueError):
        return None
    return number if math.isfinite(number) else None


def is_plausible_timestamp_ns(timestamp_ns: int) -> bool:
    """True when a nanosecond epoch falls inside the documented 2000-2100 window."""
    return MIN_PLAUSIBLE_NS <= timestamp_ns <= MAX_PLAUSIBLE_NS


def _escape_tag(value: str) -> str:
    # Tag keys, tag values, and field keys escape commas, equals signs, and spaces.
    return value.replace("\\", "\\\\").replace(",", "\\,").replace("=", "\\=").replace(" ", "\\ ")


def _escape_measurement(value: str) -> str:
    # Measurement names escape commas and spaces (but not equals).
    return value.replace("\\", "\\\\").replace(",", "\\,").replace(" ", "\\ ")


def _escape_str_field(value: str) -> str:
    # String field values are wrapped in double quotes; escape backslash and quote.
    return value.replace("\\", "\\\\").replace('"', '\\"')


def _format_field(value: object) -> str:
    # bool must be checked before int (bool is a subclass of int).
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return f"{value}i"
    if isinstance(value, float):
        # Backstop: upstream ingestors already drop non-finite values, so reaching here
        # means an internal bug. Refuse rather than emit `NaN`/`+Inf` line protocol.
        if not math.isfinite(value):
            raise ValueError(f"Non-finite field value for line protocol: {value!r}")
        return repr(value)
    if isinstance(value, str):
        return f'"{_escape_str_field(value)}"'
    raise TypeError(f"Unsupported field type for line protocol: {type(value)!r}")


@dataclass(frozen=True)
class Point:
    """A single InfluxDB point ready for line-protocol serialization."""

    measurement: str
    tags: Mapping[str, str] = field(default_factory=dict)
    fields: Mapping[str, object] = field(default_factory=dict)
    timestamp_ns: int | None = None

    def to_line(self) -> str:
        if not self.fields:
            raise ValueError(f"Point {self.measurement!r} has no fields; InfluxDB rejects it")

        out = _escape_measurement(self.measurement)

        # Tags are sorted for deterministic output (and marginally better ingest perf).
        for key in sorted(self.tags):
            val = self.tags[key]
            if val is None or val == "":
                continue
            out += f",{_escape_tag(str(key))}={_escape_tag(str(val))}"

        field_parts = [
            f"{_escape_tag(str(k))}={_format_field(v)}"
            for k, v in self.fields.items()
            if v is not None
        ]
        if not field_parts:
            raise ValueError(f"Point {self.measurement!r} has only null fields")
        out += " " + ",".join(field_parts)

        if self.timestamp_ns is not None:
            out += f" {self.timestamp_ns}"
        return out


def serialize(points: list[Point]) -> str:
    """Serialize points into a newline-delimited line-protocol payload."""
    return "\n".join(p.to_line() for p in points)
