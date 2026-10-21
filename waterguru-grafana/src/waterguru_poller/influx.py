"""Minimal InfluxDB 2 writer (line protocol over the HTTP v2 API).

Deliberately dependency-free (plain ``requests``) so the write path is easy to reason
about and unit-test. Writes go to ``http://influxdb:8086`` on the compose-internal network
in production, so InfluxDB is never exposed on the LAN.
"""

from __future__ import annotations

import logging
import time
from typing import Any

import requests

from .lineprotocol import Point, serialize

_LOGGER = logging.getLogger(__name__)

_WRITE_CHUNK = 5000
_WRITE_RETRIES = 3


class InfluxError(RuntimeError):
    """Raised when InfluxDB rejects a write or bucket operation."""


class InfluxWriter:
    def __init__(
        self,
        url: str,
        token: str,
        org: str,
        bucket: str,
        *,
        timeout: float = 60.0,
        session: requests.Session | None = None,
    ) -> None:
        self._url = url.rstrip("/")
        self._org = org
        self._bucket = bucket
        self._timeout = timeout
        self._session = session or requests.Session()
        self._headers = {"Authorization": f"Token {token}"}

    def _get(self, path: str, params: dict[str, str]) -> dict[str, Any]:
        resp = self._session.get(
            f"{self._url}{path}", headers=self._headers, params=params, timeout=self._timeout
        )
        if resp.status_code != 200:
            raise InfluxError(f"GET {path}: HTTP {resp.status_code}: {resp.text[:300]}")
        data: dict[str, Any] = resp.json()
        return data

    def ensure_bucket(self, *, retention_seconds: int = 0) -> None:
        """Create the target bucket if it does not already exist (retention 0 = infinite)."""
        existing = self._get("/api/v2/buckets", {"name": self._bucket})
        if existing.get("buckets"):
            _LOGGER.info("InfluxDB bucket %r already exists", self._bucket)
            return

        orgs = self._get("/api/v2/orgs", {"org": self._org})
        org_list = orgs.get("orgs") or []
        if not org_list:
            raise InfluxError(f"InfluxDB org {self._org!r} not found")
        org_id = org_list[0]["id"]

        resp = self._session.post(
            f"{self._url}/api/v2/buckets",
            headers=self._headers,
            json={
                "orgID": org_id,
                "name": self._bucket,
                "retentionRules": [{"type": "expire", "everySeconds": retention_seconds}],
            },
            timeout=self._timeout,
        )
        if resp.status_code not in (200, 201):
            raise InfluxError(f"create bucket: HTTP {resp.status_code}: {resp.text[:300]}")
        _LOGGER.info("Created InfluxDB bucket %r in org %r", self._bucket, self._org)

    def _write_chunk(self, payload: bytes) -> None:
        # Writes are idempotent (InfluxDB dedupes on measurement+tags+timestamp), so retrying
        # on a transient failure is safe. Transient here means a client timeout/connection error
        # or an HTTP 5xx — e.g. "writing points to database: timeout" while InfluxDB is busy or
        # provisioning a shard group on the first write to a fresh bucket. A 4xx is a client
        # error (bad payload / auth) and fails fast without retrying.
        last_exc: Exception | None = None
        for attempt in range(1, _WRITE_RETRIES + 1):
            try:
                resp = self._session.post(
                    f"{self._url}/api/v2/write",
                    headers={**self._headers, "Content-Type": "text/plain; charset=utf-8"},
                    params={"org": self._org, "bucket": self._bucket, "precision": "ns"},
                    data=payload,
                    timeout=self._timeout,
                )
            except (requests.Timeout, requests.ConnectionError) as exc:
                last_exc = exc
                _LOGGER.warning("write attempt %d/%d failed: %s", attempt, _WRITE_RETRIES, exc)
                if attempt < _WRITE_RETRIES:
                    time.sleep(2 * attempt)
                continue
            if resp.status_code == 204:
                return
            if resp.status_code >= 500:
                last_exc = InfluxError(f"write: HTTP {resp.status_code}: {resp.text[:300]}")
                _LOGGER.warning(
                    "write attempt %d/%d got HTTP %d (retryable): %s",
                    attempt, _WRITE_RETRIES, resp.status_code, resp.text[:200],
                )
                if attempt < _WRITE_RETRIES:
                    time.sleep(2 * attempt)
                continue
            raise InfluxError(f"write: HTTP {resp.status_code}: {resp.text[:300]}")
        raise InfluxError(f"write failed after {_WRITE_RETRIES} attempts: {last_exc}")

    def write(self, points: list[Point]) -> int:
        """Write points in chunks. Returns the number of points written."""
        if not points:
            return 0
        written = 0
        for start in range(0, len(points), _WRITE_CHUNK):
            chunk = points[start : start + _WRITE_CHUNK]
            self._write_chunk(serialize(chunk).encode("utf-8"))
            written += len(chunk)
        _LOGGER.info("Wrote %d points to InfluxDB bucket %r", written, self._bucket)
        return written
