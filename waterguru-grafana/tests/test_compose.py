"""The compose file must pass every documented override through to the service.

A variable that appears in ``.env.example``/README but not in ``docker-compose.yml`` is a
silent no-op: the host value never reaches the container. These tests read the compose file
directly (no docker daemon needed) and cross-check its documented defaults against the
poller's own ``Config.from_env()`` defaults, so the two cannot drift apart.
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any

import pytest
import yaml

from waterguru_poller.config import Config

COMPOSE = Path(__file__).resolve().parents[1] / "docker-compose.yml"
ENV_EXAMPLE = Path(__file__).resolve().parents[1] / ".env.example"

# (env var, documented default) -- every safe override the README/.env.example advertises.
DEFAULTED_OVERRIDES = [
    ("INFLUX_URL", "http://influxdb:8086"),
    ("INFLUX_ORG", "home"),
    ("INFLUX_BUCKET", "waterguru"),
    ("WG_STATE_DIR", "/state"),
    ("WG_BACKFILL_DAYS", "365"),
    ("WG_WINDOW_DAYS", "14"),
    ("WG_OVERLAP_HOURS", "48"),
    ("WG_MAX_RESULTS", "500"),
    ("WG_ENSURE_BUCKET", "true"),
    ("WG_DRY_RUN", "false"),
    ("WG_LOG_LEVEL", "INFO"),
    ("HUBITAT_TIMEOUT", "30"),
]

# Optional/secret pass-throughs (empty default when the host sets nothing). The hub URL and
# the tile's device id belong to one installation, so they have no committed default either.
EMPTY_DEFAULTED = ["HUBITAT_MCP_TOKEN", "HUBITAT_MCP_URL", "WG_DOSING_DEVICE_ID"]

# Required secrets: present, never given a committed default.
REQUIRED_SECRETS = ["WATERGURU_EMAIL", "WATERGURU_PASSWORD", "INFLUX_TOKEN"]

_INTERPOLATION = re.compile(r"^\$\{([A-Z0-9_]+)(?::-(.*))?\}$")


def _compose() -> dict[str, Any]:
    with open(COMPOSE, encoding="utf-8") as fh:
        data: dict[str, Any] = yaml.safe_load(fh)
    return data


def _environment() -> dict[str, str]:
    return _compose()["services"]["waterguru-poll"]["environment"]


def _default(entry: str) -> tuple[str, str | None]:
    match = _INTERPOLATION.match(str(entry))
    assert match, f"environment entry is not an interpolation: {entry!r}"
    return match.group(1), match.group(2)


@pytest.mark.parametrize(("name", "expected_default"), DEFAULTED_OVERRIDES)
def test_documented_override_reaches_the_service(name: str, expected_default: str) -> None:
    entry = _environment().get(name)
    assert entry is not None, f"{name} is not passed through to the container"
    var, default = _default(entry)
    assert var == name
    assert default == expected_default, f"{name} compose default drifted from the documented one"


@pytest.mark.parametrize("name", EMPTY_DEFAULTED)
def test_optional_override_reaches_the_service(name: str) -> None:
    entry = _environment().get(name)
    assert entry is not None, f"{name} is not passed through to the container"
    var, default = _default(entry)
    assert var == name
    assert default == ""


@pytest.mark.parametrize("name", REQUIRED_SECRETS)
def test_secrets_are_passed_through_without_a_committed_default(name: str) -> None:
    assert _environment().get(name) == f"${{{name}}}"


def test_dry_run_is_passed_through_not_hardcoded() -> None:
    """A host ``WG_DRY_RUN=true`` must win over the committed default."""
    assert _environment()["WG_DRY_RUN"] == "${WG_DRY_RUN:-false}"


@pytest.mark.parametrize(
    "name", [name for name, _default in DEFAULTED_OVERRIDES] + EMPTY_DEFAULTED
)
def test_every_passthrough_is_documented_in_env_example(name: str) -> None:
    text = ENV_EXAMPLE.read_text(encoding="utf-8")
    assert name in text, f"{name} is passed through but not advertised in .env.example"


def test_documented_defaults_match_the_poller_config(monkeypatch: pytest.MonkeyPatch) -> None:
    """Cross-check: the compose defaults equal ``Config.from_env()``'s own defaults."""
    for name, _default_value in DEFAULTED_OVERRIDES + [
        (name, "") for name in EMPTY_DEFAULTED + REQUIRED_SECRETS
    ]:
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("WATERGURU_EMAIL", "example@example.test")
    monkeypatch.setenv("WATERGURU_PASSWORD", "not-a-real-password")
    monkeypatch.setenv("INFLUX_TOKEN", "not-a-real-token")
    config = Config.from_env()

    expected = {
        "INFLUX_URL": config.influx_url,
        "INFLUX_ORG": config.influx_org,
        "INFLUX_BUCKET": config.influx_bucket,
        "WG_STATE_DIR": config.state_dir,
        "WG_BACKFILL_DAYS": str(config.backfill_days),
        "WG_WINDOW_DAYS": str(config.window_days),
        "WG_OVERLAP_HOURS": str(config.overlap_hours),
        "WG_MAX_RESULTS": str(config.max_results),
        "WG_ENSURE_BUCKET": "true" if config.ensure_bucket else "false",
        "WG_DRY_RUN": "true" if config.dry_run else "false",
        "WG_LOG_LEVEL": config.log_level,
        "HUBITAT_MCP_URL": config.hub_url,
        "WG_DOSING_DEVICE_ID": config.hub_device_id,
    }
    for name, value in expected.items():
        entry = _environment()[name]
        _var, compose_default = _default(entry)
        assert compose_default == value, f"{name}: compose={compose_default!r} config={value!r}"

    _var, timeout_default = _default(_environment()["HUBITAT_TIMEOUT"])
    assert float(timeout_default or "0") == config.hub_timeout


def test_service_publishes_no_host_port() -> None:
    """The poller must never expose a host port (no network/ports.md change)."""
    assert "ports" not in _compose()["services"]["waterguru-poll"]


def test_image_tag_is_parameterized_for_immutable_versioned_builds() -> None:
    """Builds must be taggable per version so the previous image can be retained."""
    image = _compose()["services"]["waterguru-poll"]["image"]
    assert image == "waterguru-poller:${WATERGURU_IMAGE_TAG:-latest}"


def test_network_default_is_the_existing_hubitat_stack() -> None:
    network = _compose()["networks"]["hubitat"]
    assert network["external"] is True
    assert network["name"] == "${HUBITAT_NETWORK:-hubitat-stack_hubitat-net}"
