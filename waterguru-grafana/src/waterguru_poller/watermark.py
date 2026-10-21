"""Per-pod 'last seen' watermark, persisted as JSON on a mounted volume.

The watermark bounds how far back each run queries. It is an *optimization*, not a
correctness mechanism: InfluxDB deduplicates on measurement + tag-set + timestamp, so an
overlapping re-query never produces duplicate points. Losing the watermark just means a
larger (still idempotent) backfill on the next run.
"""

from __future__ import annotations

import json
import os
import tempfile
from datetime import UTC, datetime


class Watermark:
    def __init__(self, path: str) -> None:
        self._path = path
        self._data: dict[str, str] = {}
        self._load()

    def _load(self) -> None:
        try:
            with open(self._path, encoding="utf-8") as fh:
                loaded = json.load(fh)
            if isinstance(loaded, dict):
                self._data = {str(k): str(v) for k, v in loaded.get("pods", {}).items()}
        except (FileNotFoundError, json.JSONDecodeError, OSError):
            self._data = {}

    def last_seen(self, pod_id: str) -> datetime | None:
        raw = self._data.get(str(pod_id))
        if not raw:
            return None
        try:
            dt = datetime.fromisoformat(raw.replace("Z", "+00:00"))
        except ValueError:
            return None
        return dt if dt.tzinfo else dt.replace(tzinfo=UTC)

    def update(self, pod_id: str, seen: datetime) -> None:
        current = self.last_seen(pod_id)
        if current is None or seen > current:
            self._data[str(pod_id)] = seen.astimezone(UTC).isoformat()

    def save(self) -> None:
        os.makedirs(os.path.dirname(self._path) or ".", exist_ok=True)
        payload = {
            "updatedAt": datetime.now(UTC).isoformat(),
            "pods": self._data,
        }
        # Atomic write so a crash mid-write can't corrupt the watermark file.
        fd, tmp = tempfile.mkstemp(dir=os.path.dirname(self._path) or ".", suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                json.dump(payload, fh, indent=2)
            os.replace(tmp, self._path)
        finally:
            if os.path.exists(tmp):
                os.remove(tmp)
