"""Runtime configuration, sourced from environment variables.

Secrets (WaterGuru password, InfluxDB token, hub token) are read from the environment only;
they are never written to disk or logged. In the container they are injected as environment
variables (a ``.env`` file next to ``docker-compose.yml``, or a container manager's
environment editor; see .env.example). Nothing secret is baked into the image, and nothing
about one particular installation is a default: the hub URL and device id have to be set.
"""

from __future__ import annotations

import os
from dataclasses import dataclass


def _bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    return int(raw) if raw not in (None, "") else default


class ConfigError(RuntimeError):
    """Raised when required configuration is missing."""


@dataclass(frozen=True)
class Config:
    # WaterGuru credentials
    wg_email: str
    wg_password: str

    # InfluxDB 2 target
    influx_url: str
    influx_token: str
    influx_org: str
    influx_bucket: str

    # Behaviour
    state_dir: str
    backfill_days: int
    window_days: int
    overlap_hours: int
    max_results: int
    ensure_bucket: bool
    dry_run: bool
    log_level: str

    # Hubitat dosing tile (chlorine dose volume + tank inventory). The chlorine
    # charts need measurements the WaterGuru cloud does not expose; these come
    # from the hub instead. Collection is skipped when hub_token is empty, so an
    # unset HUBITAT_MCP_TOKEN leaves the existing poller behaviour untouched. With
    # a token set, hub_url and hub_device_id are required: they have no default,
    # because any default would be one installation's hub.
    hub_url: str
    hub_token: str
    hub_device_id: str
    hub_timeout: float

    @staticmethod
    def from_env() -> Config:
        email = os.environ.get("WATERGURU_EMAIL", "")
        password = os.environ.get("WATERGURU_PASSWORD", "")
        token = os.environ.get("INFLUX_TOKEN", "")
        hub_url = os.environ.get("HUBITAT_MCP_URL", "")
        hub_token = os.environ.get("HUBITAT_MCP_TOKEN", "")
        hub_device_id = os.environ.get("WG_DOSING_DEVICE_ID", "")

        missing = [
            name
            for name, val in (
                ("WATERGURU_EMAIL", email),
                ("WATERGURU_PASSWORD", password),
                ("INFLUX_TOKEN", token),
            )
            if not val
        ]
        # In dry-run mode the InfluxDB token is not required (we never write).
        dry_run = _bool("WG_DRY_RUN", False)
        if dry_run and "INFLUX_TOKEN" in missing:
            missing.remove("INFLUX_TOKEN")
        # A hub token without the hub's address is a half-configured dosing collector. Fail
        # loudly (a dry run before any deploy catches it) rather than skip it silently.
        if hub_token:
            missing += [
                name
                for name, val in (
                    ("HUBITAT_MCP_URL", hub_url),
                    ("WG_DOSING_DEVICE_ID", hub_device_id),
                )
                if not val
            ]
        if missing:
            raise ConfigError(f"Missing required environment variables: {', '.join(missing)}")

        return Config(
            wg_email=email,
            wg_password=password,
            influx_url=os.environ.get("INFLUX_URL", "http://influxdb:8086").rstrip("/"),
            influx_token=token,
            influx_org=os.environ.get("INFLUX_ORG", "home"),
            influx_bucket=os.environ.get("INFLUX_BUCKET", "waterguru"),
            state_dir=os.environ.get("WG_STATE_DIR", "/state"),
            backfill_days=_int("WG_BACKFILL_DAYS", 365),
            window_days=_int("WG_WINDOW_DAYS", 14),
            overlap_hours=_int("WG_OVERLAP_HOURS", 48),
            max_results=_int("WG_MAX_RESULTS", 500),
            ensure_bucket=_bool("WG_ENSURE_BUCKET", True),
            dry_run=dry_run,
            log_level=os.environ.get("WG_LOG_LEVEL", "INFO").upper(),
            hub_url=hub_url,
            hub_token=hub_token,
            hub_device_id=hub_device_id,
            hub_timeout=float(os.environ.get("HUBITAT_TIMEOUT", "30")),
        )
