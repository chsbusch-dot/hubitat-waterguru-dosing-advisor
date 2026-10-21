"""``Config.from_env()``: nothing about one installation is a default.

The hub URL and the dosing tile's device id used to default to one particular hub. Anyone
else who set only ``HUBITAT_MCP_TOKEN`` would then have sent their token to that address.
Now they have no default, and a token without them is a configuration error.
"""

from __future__ import annotations

import pytest

from waterguru_poller.config import Config, ConfigError

HUB_VARS = ("HUBITAT_MCP_TOKEN", "HUBITAT_MCP_URL", "WG_DOSING_DEVICE_ID")


@pytest.fixture
def base_env(monkeypatch: pytest.MonkeyPatch) -> pytest.MonkeyPatch:
    for name in HUB_VARS + ("WG_DRY_RUN",):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("WATERGURU_EMAIL", "example@example.test")
    monkeypatch.setenv("WATERGURU_PASSWORD", "not-a-real-password")
    monkeypatch.setenv("INFLUX_TOKEN", "not-a-real-token")
    return monkeypatch


def test_without_a_hub_token_the_hub_settings_stay_empty(base_env: pytest.MonkeyPatch) -> None:
    config = Config.from_env()
    assert config.hub_token == ""
    assert config.hub_url == ""
    assert config.hub_device_id == ""


def test_a_hub_token_without_url_and_device_fails_loudly(base_env: pytest.MonkeyPatch) -> None:
    base_env.setenv("HUBITAT_MCP_TOKEN", "not-a-real-token")
    with pytest.raises(ConfigError) as excinfo:
        Config.from_env()
    assert "HUBITAT_MCP_URL" in str(excinfo.value)
    assert "WG_DOSING_DEVICE_ID" in str(excinfo.value)


@pytest.mark.parametrize("missing", ["HUBITAT_MCP_URL", "WG_DOSING_DEVICE_ID"])
def test_each_hub_setting_is_required_with_a_token(
    base_env: pytest.MonkeyPatch, missing: str
) -> None:
    base_env.setenv("HUBITAT_MCP_TOKEN", "not-a-real-token")
    base_env.setenv("HUBITAT_MCP_URL", "http://hub.example.test/apps/api/1/mcp")
    base_env.setenv("WG_DOSING_DEVICE_ID", "101")
    base_env.delenv(missing)
    with pytest.raises(ConfigError, match=missing):
        Config.from_env()


def test_the_dry_run_still_requires_the_hub_settings(base_env: pytest.MonkeyPatch) -> None:
    # The dry run before a deploy is where a half-configured hub must show up.
    base_env.setenv("WG_DRY_RUN", "true")
    base_env.setenv("HUBITAT_MCP_TOKEN", "not-a-real-token")
    with pytest.raises(ConfigError, match="HUBITAT_MCP_URL"):
        Config.from_env()


def test_a_fully_configured_hub_is_read_as_given(base_env: pytest.MonkeyPatch) -> None:
    base_env.setenv("HUBITAT_MCP_TOKEN", "not-a-real-token")
    base_env.setenv("HUBITAT_MCP_URL", "http://hub.example.test/apps/api/1/mcp")
    base_env.setenv("WG_DOSING_DEVICE_ID", "101")
    config = Config.from_env()
    assert config.hub_url == "http://hub.example.test/apps/api/1/mcp"
    assert config.hub_device_id == "101"
    assert config.hub_token == "not-a-real-token"
