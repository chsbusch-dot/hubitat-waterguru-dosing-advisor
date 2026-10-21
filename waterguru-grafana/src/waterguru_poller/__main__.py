"""CLI entry point for the WaterGuru poller."""

from __future__ import annotations

import logging
import sys

from .config import Config, ConfigError
from .poller import run


def main() -> int:
    try:
        config = Config.from_env()
    except ConfigError as exc:
        print(f"Configuration error: {exc}", file=sys.stderr)
        return 2

    logging.basicConfig(
        level=getattr(logging, config.log_level, logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    log = logging.getLogger("waterguru_poller")

    try:
        result = run(config)
    except Exception as exc:  # noqa: BLE001 - top-level: log and exit non-zero
        log.error("Poll failed: %s", exc)
        return 1

    log.info(
        "Done: %d pod(s) %s, %d windows, %d pod + %d dashboard points, "
        "%d dose + %d tank + %d target point(s), %d written%s",
        result.pods,
        result.pod_ids,
        result.windows,
        result.pod_points,
        result.dashboard_points,
        result.dose_points,
        result.tank_points,
        result.target_points,
        result.written,
        " (dry run)" if result.dry_run else "",
    )
    if not result.waterguru_ok:
        log.warning("WaterGuru cloud collection failed this run: %s", result.waterguru_error)
    if not result.hubitat_ok:
        log.warning("Hubitat dosing collection failed this run: %s", result.hubitat_error)
    # The two sources are independent, so both still run and the successful one is kept. The
    # exit code must not hide a partial failure from the scheduler: any failed (or
    # degraded/saturated) configured source is a non-zero exit. A skipped optional Hubitat
    # source (no token) leaves hubitat_ok true. A run that produced nothing at all is also a
    # failure; in dry-run mode "produced" means the points it would have written.
    if not result.waterguru_ok or not result.hubitat_ok:
        log.error("Partial collection: at least one configured source failed this run")
        return 1
    collected = result.total_points > 0 if result.dry_run else result.written > 0
    if not collected:
        log.error("No data was collected from either source")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
