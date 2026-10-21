"""Poller orchestration: authenticate, backfill pod history, snapshot the dashboard, write.

The two data sources are collected **independently**:

* the WaterGuru cloud (auth -> dashboard snapshot -> per-pod history), and
* the Hubitat dosing tile (event history + attribute snapshot, via :mod:`dosing`).

A WaterGuru cloud outage must not prevent the Hubitat telemetry (chlorine dose/tank/target)
from being written, and a Hubitat/hub outage must not discard already-written WaterGuru
chemistry. Each side therefore fails soft, recording the failure on :class:`RunResult`.
"""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any

from .client import WaterGuruClient
from .config import Config
from .dosing import HubError, collect
from .influx import InfluxWriter
from .lineprotocol import Point, serialize
from .mapping import dashboard_to_points, parse_iso_ns, pod_records_to_points
from .watermark import Watermark

_LOGGER = logging.getLogger(__name__)


@dataclass
class RunResult:
    pods: int = 0
    windows: int = 0
    pod_points: int = 0
    dashboard_points: int = 0
    dose_points: int = 0
    tank_points: int = 0
    target_points: int = 0
    dose_event_points: int = 0
    written: int = 0
    dry_run: bool = False
    pod_ids: list[int] = field(default_factory=list)
    waterguru_ok: bool = True
    hubitat_ok: bool = True
    waterguru_error: str | None = None
    hubitat_error: str | None = None

    @property
    def total_points(self) -> int:
        """Every point this run produced, across both sources."""
        return (
            self.pod_points
            + self.dashboard_points
            + self.dose_points
            + self.tank_points
            + self.target_points
        )


def _windows(start: datetime, end: datetime, window_days: int) -> list[tuple[datetime, datetime]]:
    """Split [start, end] into <= window_days chunks (keeps each query under maxResults)."""
    if start >= end:
        return [(end - timedelta(minutes=1), end)]
    step = timedelta(days=max(window_days, 1))
    out: list[tuple[datetime, datetime]] = []
    cursor = start
    while cursor < end:
        chunk_end = min(cursor + step, end)
        out.append((cursor, chunk_end))
        cursor = chunk_end
    return out


def _discover_pods(dashboard: dict[str, Any]) -> list[tuple[int, str]]:
    pods: list[tuple[int, str]] = []
    seen: set[int] = set()
    for wb in dashboard.get("waterBodies", []) or []:
        wb_id = str(wb.get("waterBodyId", ""))
        for pod in wb.get("pods", []) or []:
            pod_id = pod.get("pod", {}).get("podId", pod.get("podId"))
            if isinstance(pod_id, int) and pod_id not in seen:
                seen.add(pod_id)
                pods.append((pod_id, wb_id))
    return pods


def _max_record_time(history_response: dict[str, Any]) -> datetime | None:
    records = history_response.get("queryDataHistoryRsp", {}).get("data", []) or []
    times = [r["time"] for r in records if r.get("time")]
    if not times:
        return None
    newest = max(times)
    return datetime.fromtimestamp(parse_iso_ns(newest) / 1e9, tz=UTC)


def _collect_waterguru(
    config: Config,
    *,
    now: datetime,
    now_ns: int,
    sink: Any,
    result: RunResult,
) -> None:
    """Backfill WaterGuru chemistry. Raises on failure; the caller fails soft."""
    client = WaterGuruClient(config.wg_email, config.wg_password)
    client.authenticate()

    dashboard = client.get_dashboard()
    dashboard_points = dashboard_to_points(dashboard, now_ns)

    pods = _discover_pods(dashboard)
    watermark = Watermark(os.path.join(config.state_dir, "watermark.json"))

    result.pods = len(pods)
    result.dashboard_points = len(dashboard_points)
    result.pod_ids = [pod_id for pod_id, _ in pods]

    # Write incrementally — the dashboard snapshot first, then each pod-history window as it is
    # fetched — instead of accumulating the whole backfill into one giant write. A fresh-volume
    # 365-day backfill would otherwise be a single multi-thousand-point POST that can make
    # InfluxDB time out server-side. Writes are idempotent, so per-window flushing is safe; the
    # watermark is still saved only after the whole side succeeds, so a mid-run failure re-runs
    # from the last saved point (re-writing already-written windows is a harmless dedupe).
    sink(list(dashboard_points))

    for pod_id, _wb_id in pods:
        last = watermark.last_seen(str(pod_id))
        if last is None:
            start = now - timedelta(days=config.backfill_days)
            _LOGGER.info("Pod %s: no watermark, backfilling %d days", pod_id, config.backfill_days)
        else:
            start = last - timedelta(hours=config.overlap_hours)
            _LOGGER.info("Pod %s: incremental from %s", pod_id, start)

        newest_seen: datetime | None = None
        for win_start, win_end in _windows(start, now, config.window_days):
            resp = client.get_pod_history(
                pod_id, win_start, win_end, max_results=config.max_results
            )
            result.windows += 1
            points = pod_records_to_points(resp)
            result.pod_points += len(points)
            sink(points)

            win_max = _max_record_time(resp)
            if win_max and (newest_seen is None or win_max > newest_seen):
                newest_seen = win_max

            n_records = len(resp.get("queryDataHistoryRsp", {}).get("data", []) or [])
            if n_records >= config.max_results:
                _LOGGER.warning(
                    "Pod %s window %s..%s hit maxResults=%d; shrink WG_WINDOW_DAYS to avoid gaps",
                    pod_id,
                    win_start.date(),
                    win_end.date(),
                    config.max_results,
                )

        if newest_seen:
            watermark.update(str(pod_id), newest_seen)

    if not config.dry_run:
        watermark.save()


def run(config: Config, *, now: datetime | None = None) -> RunResult:
    now = now or datetime.now(UTC)
    now_ns = int(now.timestamp() * 1e9)

    result = RunResult(dry_run=config.dry_run)

    writer: InfluxWriter | None = None
    if not config.dry_run:
        writer = InfluxWriter(
            config.influx_url, config.influx_token, config.influx_org, config.influx_bucket
        )
        if config.ensure_bucket:
            writer.ensure_bucket()

    dry_points: list[Point] = []

    def _sink(points: list[Point]) -> None:
        if not points:
            return
        if writer is None:
            dry_points.extend(points)
        else:
            result.written += writer.write(points)

    # --- WaterGuru cloud: independent of the hub, and vice versa ---
    try:
        _collect_waterguru(config, now=now, now_ns=now_ns, sink=_sink, result=result)
    except Exception as exc:  # a cloud outage must not cost hub telemetry
        result.waterguru_ok = False
        result.waterguru_error = f"{type(exc).__name__}: {exc}"
        _LOGGER.exception(
            "WaterGuru cloud collection failed; continuing with Hubitat telemetry only"
        )

    # --- Chlorine dose volume and tank inventory come from the hub, not WaterGuru ---
    if config.hub_token:
        try:
            dosing = collect(
                url=config.hub_url,
                token=config.hub_token,
                device_id=config.hub_device_id,
                writer=writer,
                dry_run=config.dry_run,
                timeout=config.hub_timeout,
                state_dir=config.state_dir,
                now_ns=now_ns,
            )
            result.dose_points = dosing.dose_points
            result.tank_points = dosing.tank_points
            result.target_points = dosing.target_points
            result.dose_event_points = dosing.dose_event_points
            result.written += dosing.written
            result.hubitat_ok = dosing.events_ok and dosing.snapshot_ok
            if not result.hubitat_ok:
                result.hubitat_error = dosing.events_error or dosing.snapshot_error
                _LOGGER.warning(
                    "Hubitat collection degraded (dose/tank panels may be stale): %s",
                    result.hubitat_error,
                )
            if config.dry_run:
                dry_points.extend(dosing.points)
        except HubError as exc:
            result.hubitat_ok = False
            result.hubitat_error = f"{type(exc).__name__}: {exc}"
            _LOGGER.warning("Dosing tile read failed (chlorine panels will go stale): %s", exc)
        except Exception as exc:  # a hub failure must not discard cloud history
            result.hubitat_ok = False
            result.hubitat_error = f"{type(exc).__name__}: {exc}"
            _LOGGER.exception("Unexpected Hubitat collection failure; cloud data is unaffected")
    else:
        _LOGGER.info("HUBITAT_MCP_TOKEN not set; skipping chlorine dose/tank/target collection")

    if config.dry_run:
        _LOGGER.info(
            "DRY RUN: %d pod + %d dashboard + %d dose + %d tank + %d target point(s) (not written)",
            result.pod_points,
            result.dashboard_points,
            result.dose_points,
            result.tank_points,
            result.target_points,
        )
        print(serialize(dry_points))
        return result

    return result
