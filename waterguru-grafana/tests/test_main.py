"""Direct CLI tests for ``python -m waterguru_poller``.

The two collectors are independent by design, so a partial failure still publishes whatever
the healthy source produced. The exit code must nevertheless surface any failed (or
degraded/saturated) configured source to the scheduler, while a skipped optional source
(no ``HUBITAT_MCP_TOKEN``) stays healthy and the configuration error keeps exit 2.
"""

from __future__ import annotations

from typing import Any

import pytest

from waterguru_poller import __main__ as main_mod
from waterguru_poller.config import ConfigError
from waterguru_poller.poller import RunResult


class _RunSpy:
    """Stands in for ``poller.run`` and records that it was allowed to complete."""

    def __init__(self, result: RunResult) -> None:
        self.result = result
        self.calls: list[Any] = []

    def __call__(self, config: Any) -> RunResult:
        self.calls.append(config)
        return self.result


def _install(
    monkeypatch: pytest.MonkeyPatch, result: RunResult, *, dry_run: bool = False
) -> _RunSpy:
    class _Config:
        log_level = "WARNING"

        def __init__(self) -> None:
            self.dry_run = dry_run

        @staticmethod
        def from_env() -> _Config:
            return _Config()

    spy = _RunSpy(result)
    monkeypatch.setattr(main_mod, "Config", _Config)
    monkeypatch.setattr(main_mod, "run", spy)
    monkeypatch.setattr(main_mod.logging, "basicConfig", lambda **kwargs: None)
    return spy


def test_main_full_success_returns_zero(monkeypatch: pytest.MonkeyPatch) -> None:
    spy = _install(monkeypatch, RunResult(pod_points=5, written=5))
    assert main_mod.main() == 0
    assert len(spy.calls) == 1


def test_main_partial_waterguru_failure_returns_one_after_hubitat_completed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # The hub source completed and produced its three points; the exit code must still fail.
    result = RunResult(
        waterguru_ok=False,
        waterguru_error="WaterGuruError: cloud down",
        hubitat_ok=True,
        dose_points=1,
        tank_points=1,
        target_points=1,
        written=3,
    )
    spy = _install(monkeypatch, result)
    assert main_mod.main() == 1
    assert len(spy.calls) == 1
    assert spy.result.written == 3  # the healthy source was allowed to finish


def test_main_partial_hubitat_failure_returns_one_after_waterguru_completed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    result = RunResult(
        waterguru_ok=True,
        hubitat_ok=False,
        hubitat_error="HubError: history saturated at limit 500",
        pod_points=2,
        dashboard_points=6,
        written=8,
    )
    spy = _install(monkeypatch, result)
    assert main_mod.main() == 1
    assert len(spy.calls) == 1
    assert spy.result.written == 8  # the healthy WaterGuru source was allowed to finish


def test_main_both_sources_failing_and_no_data_returns_one(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    result = RunResult(waterguru_ok=False, hubitat_ok=False, written=0)
    spy = _install(monkeypatch, result)
    assert main_mod.main() == 1
    assert len(spy.calls) == 1
    assert spy.result.total_points == 0


def test_main_healthy_sources_but_nothing_written_returns_one(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    result = RunResult(waterguru_ok=True, hubitat_ok=True, written=0)
    _install(monkeypatch, result)
    assert main_mod.main() == 1


def test_main_dry_run_success_returns_zero(monkeypatch: pytest.MonkeyPatch) -> None:
    result = RunResult(
        dry_run=True,
        dose_points=1,
        tank_points=1,
        target_points=1,
        pod_points=2,
        dashboard_points=6,
        written=0,
    )
    spy = _install(monkeypatch, result, dry_run=True)
    assert main_mod.main() == 0
    assert len(spy.calls) == 1


def test_main_configuration_error_keeps_exit_two_and_never_runs(
    monkeypatch: pytest.MonkeyPatch, capsys: Any
) -> None:
    class _Config:
        log_level = "WARNING"
        dry_run = False

        @staticmethod
        def from_env() -> _Config:
            raise ConfigError("Missing required environment variables: WATERGURU_EMAIL")

    spy = _RunSpy(RunResult())
    monkeypatch.setattr(main_mod, "Config", _Config)
    monkeypatch.setattr(main_mod, "run", spy)

    assert main_mod.main() == 2
    assert spy.calls == []
    assert "Configuration error" in capsys.readouterr().err


def test_main_catches_an_unexpected_run_failure(monkeypatch: pytest.MonkeyPatch) -> None:
    def boom(config: Any) -> RunResult:
        raise RuntimeError("influx exploded")

    class _Config:
        log_level = "WARNING"
        dry_run = False

        @staticmethod
        def from_env() -> _Config:
            return _Config()

    monkeypatch.setattr(main_mod, "Config", _Config)
    monkeypatch.setattr(main_mod, "run", boom)
    monkeypatch.setattr(main_mod.logging, "basicConfig", lambda **kwargs: None)

    assert main_mod.main() == 1
