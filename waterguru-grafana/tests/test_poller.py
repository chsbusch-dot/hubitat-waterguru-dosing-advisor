from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest

from waterguru_poller import dosing, poller
from waterguru_poller.client import WaterGuruError
from waterguru_poller.config import Config
from waterguru_poller.dosing import HubError
from waterguru_poller.lineprotocol import Point
from waterguru_poller.poller import _discover_pods, _max_record_time, _windows, run

FIXED_NOW = datetime(2026, 8, 18, 18, 0, tzinfo=UTC)

# A dose recorded two hours before the run, so the event cursor is comfortably in the past.
EVENT_MS = int((FIXED_NOW - timedelta(hours=2)).timestamp() * 1000)

HUB_ATTRS: dict[str, Any] = {
    "lastDoseMl": 237.3273375,
    "lastDoseEpochMs": EVENT_MS,
    "lastDoseRuntimeSeconds": 65,
    "tankRemainingMl": 56543.8494225,
    "tankCapacityMl": 56781.17676,
    "tankPercent": 99.5820316678,
    "tankRunwayDays": 52.4883227879,
    "freeChlorine": 5.7,
    "targetFreeChlorine": 6.0,
    "lastCalcEpochMs": EVENT_MS,
}

HUB_EVENTS: list[dict[str, Any]] = [
    {"name": "lastDoseEpochMs", "value": EVENT_MS, "epoch": EVENT_MS},
    {"name": "lastDoseMl", "value": 237.3273375, "epoch": EVENT_MS},
    {"name": "lastDoseRuntimeSeconds", "value": 65, "epoch": EVENT_MS},
]


def make_config(tmp_path: Path, **overrides: Any) -> Config:
    base: dict[str, Any] = {
        "wg_email": "e",
        "wg_password": "p",
        "influx_url": "http://influxdb:8086",
        "influx_token": "t",
        "influx_org": "home",
        "influx_bucket": "waterguru",
        "state_dir": str(tmp_path),
        "backfill_days": 1,
        "window_days": 14,
        "overlap_hours": 48,
        "max_results": 500,
        "ensure_bucket": True,
        "dry_run": False,
        "log_level": "INFO",
        # Dosing collection is opt-in: an empty hub_token leaves the poller's
        # behaviour unchanged, so the existing tests exercise the cloud path only.
        "hub_url": "http://hub.example.test/apps/api/1/mcp",
        "hub_token": "",
        "hub_device_id": "101",
        "hub_timeout": 30.0,
    }
    base.update(overrides)
    return Config(**base)


class FakeClient:
    def __init__(self, dashboard: dict[str, Any], history: dict[str, Any]) -> None:
        self._dashboard = dashboard
        self._history = history
        self.user_id = "u"
        self.history_calls: list[tuple[int, datetime, datetime]] = []

    def authenticate(self) -> None:
        pass

    def get_dashboard(self) -> dict[str, Any]:
        return self._dashboard

    def get_pod_history(
        self, pod_id: int, start: datetime, end: datetime, *, max_results: int = 500
    ) -> dict[str, Any]:
        self.history_calls.append((pod_id, start, end))
        return self._history


class FakeWriter:
    instances: list[FakeWriter] = []

    def __init__(self, *_args: Any, **_kwargs: Any) -> None:
        self.written: list[Point] = []
        self.ensured = False
        FakeWriter.instances.append(self)

    def ensure_bucket(self, **_: Any) -> None:
        self.ensured = True

    def write(self, points: list[Point]) -> int:
        self.written.extend(points)
        return len(points)


@pytest.fixture
def patched(
    monkeypatch: pytest.MonkeyPatch,
    dashboard_sample: dict[str, Any],
    managepod_sample: dict[str, Any],
) -> FakeClient:
    fake = FakeClient(dashboard_sample, managepod_sample)
    monkeypatch.setattr(poller, "WaterGuruClient", lambda *a, **k: fake)
    FakeWriter.instances.clear()
    monkeypatch.setattr(poller, "InfluxWriter", FakeWriter)
    return fake


@pytest.fixture
def hub(monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    """Patch only the hub HTTP layer, so ``run()`` exercises the production collect path."""
    state: dict[str, Any] = {
        "attributes": dict(HUB_ATTRS),
        "events": [dict(event) for event in HUB_EVENTS],
        "attributes_error": None,
        "events_error": None,
        "event_kwargs": [],
    }

    def fake_attributes(url, token, device_id, *, timeout=30.0, session=None):
        if state["attributes_error"] is not None:
            raise state["attributes_error"]
        return dict(state["attributes"])

    def fake_events(
        url,
        token,
        device_id,
        *,
        attribute=None,
        since=None,
        hours_back=None,
        limit=None,
        timeout=30.0,
        session=None,
    ):
        state["event_kwargs"].append(
            {"attribute": attribute, "since": since, "hours_back": hours_back, "limit": limit}
        )
        if state["events_error"] is not None:
            raise state["events_error"]
        matches = [
            dict(event)
            for event in state["events"]
            if attribute is None or event.get("name") == attribute
        ]
        return {
            "events": matches,
            "count": len(matches),
            "sinceMode": "since" if since else "hoursBack",
            "sinceTimestamp": "2026-08-18T00:00:00.000Z",
        }

    monkeypatch.setattr(dosing, "fetch_device_attributes", fake_attributes)
    monkeypatch.setattr(dosing, "fetch_device_events", fake_events)
    return state


def test_discover_pods(dashboard_sample: dict[str, Any]) -> None:
    assert _discover_pods(dashboard_sample) == [(999001, "wb-test-0001")]


def test_windows_single_and_multi() -> None:
    start = FIXED_NOW - timedelta(days=1)
    assert _windows(start, FIXED_NOW, 14) == [(start, FIXED_NOW)]

    start30 = FIXED_NOW - timedelta(days=30)
    wins = _windows(start30, FIXED_NOW, 14)
    assert len(wins) == 3  # 14 + 14 + 2 days
    assert wins[0][0] == start30
    assert wins[-1][1] == FIXED_NOW


def test_max_record_time(managepod_sample: dict[str, Any]) -> None:
    assert _max_record_time(managepod_sample) == datetime(
        2026, 8, 18, 14, 34, 18, tzinfo=UTC
    )


def test_run_writes_and_persists_watermark(patched: FakeClient, tmp_path: Path) -> None:
    config = make_config(tmp_path)
    result = run(config, now=FIXED_NOW)

    assert result.pods == 1
    assert result.pod_ids == [999001]
    assert result.windows == 1
    assert result.hubitat_ok is True  # no HUBITAT_MCP_TOKEN: an optional skip, not a failure
    assert result.pod_points == 2          # 2 numeric records (3rd has no numeric fields)
    assert result.dashboard_points == 6    # 5 measurements + 1 status point
    assert result.written == 8

    writer = FakeWriter.instances[0]
    assert writer.ensured is True
    assert len(writer.written) == 8

    # watermark persisted at the newest pod record time
    wm_file = tmp_path / "watermark.json"
    assert wm_file.exists()
    saved = json.loads(wm_file.read_text())
    assert saved["pods"]["999001"].startswith("2026-08-18T14:34:18")


def test_incremental_run_uses_watermark(patched: FakeClient, tmp_path: Path) -> None:
    config = make_config(tmp_path)
    run(config, now=FIXED_NOW)
    patched.history_calls.clear()

    # second run: start should derive from the watermark minus the overlap, not backfill_days
    run(config, now=FIXED_NOW)
    assert len(patched.history_calls) == 1
    _pod, start, _end = patched.history_calls[0]
    expected_start = datetime(2026, 8, 18, 14, 34, 18, tzinfo=UTC) - timedelta(hours=48)
    assert start == expected_start


def test_dry_run_does_not_write(patched: FakeClient, tmp_path: Path, capsys: Any) -> None:
    config = make_config(tmp_path, dry_run=True)
    result = run(config, now=FIXED_NOW)

    assert result.written == 0
    assert FakeWriter.instances == []           # writer never constructed
    assert not (tmp_path / "watermark.json").exists()
    out = capsys.readouterr().out
    assert "waterguru_pod" in out               # line protocol printed


# ------------------------------------------------------------------ independent sources


def test_run_collects_both_sources_and_totals_every_point(
    patched: FakeClient, hub: dict[str, Any], tmp_path: Path
) -> None:
    config = make_config(tmp_path, hub_token="hub-token")
    result = run(config, now=FIXED_NOW)

    assert result.waterguru_ok is True
    assert result.hubitat_ok is True
    assert (result.pod_points, result.dashboard_points) == (2, 6)
    # One physical dose: the snapshot dose is merged into the event-history dose.
    assert (result.dose_event_points, result.dose_points) == (1, 1)
    assert (result.tank_points, result.target_points) == (1, 1)

    # Pod + dashboard + one dose + tank + target.
    assert result.total_points == 11
    assert result.written == 11
    writer = FakeWriter.instances[0]
    assert len(writer.written) == 11
    assert result.written == len(writer.written)
    assert {p.measurement for p in writer.written} == {
        "waterguru_pod",
        "waterguru_measure",
        "waterguru_status",
        "chlorine_dose",
        "chlorine_tank",
        "chlorine_target",
    }
    doses = [p for p in writer.written if p.measurement == "chlorine_dose"]
    assert len(doses) == 1
    assert doses[0].fields["runtime_seconds"] == 65.0


def test_waterguru_auth_failure_still_writes_hubitat(
    monkeypatch: pytest.MonkeyPatch,
    hub: dict[str, Any],
    tmp_path: Path,
    dashboard_sample: dict[str, Any],
    managepod_sample: dict[str, Any],
) -> None:
    fake = FakeClient(dashboard_sample, managepod_sample)

    def boom() -> None:
        raise WaterGuruError("Cognito SRP authentication failed: nope")

    fake.authenticate = boom  # type: ignore[method-assign]
    monkeypatch.setattr(poller, "WaterGuruClient", lambda *a, **k: fake)
    FakeWriter.instances.clear()
    monkeypatch.setattr(poller, "InfluxWriter", FakeWriter)

    result = run(make_config(tmp_path, hub_token="hub-token"), now=FIXED_NOW)

    assert result.waterguru_ok is False
    assert result.waterguru_error is not None and "Cognito" in result.waterguru_error
    assert result.pod_points == 0 and result.dashboard_points == 0
    assert result.hubitat_ok is True
    assert result.dose_points == 1
    assert result.written == 3
    assert result.total_points == 3
    assert len(FakeWriter.instances[0].written) == 3


def test_waterguru_history_failure_does_not_block_hubitat_or_write_a_watermark(
    patched: FakeClient, hub: dict[str, Any], tmp_path: Path
) -> None:
    def boom(*_args: Any, **_kwargs: Any) -> None:
        raise WaterGuruError("prod-managePod: HTTP 500")

    patched.get_pod_history = boom  # type: ignore[method-assign]
    result = run(make_config(tmp_path, hub_token="hub-token"), now=FIXED_NOW)

    assert result.waterguru_ok is False
    assert result.dashboard_points == 6  # the dashboard snapshot was already written
    assert result.pod_points == 0
    assert result.hubitat_ok is True
    assert result.written == 9
    # A partial WaterGuru side must not advance the watermark...
    assert not (tmp_path / "watermark.json").exists()
    # ...and the hub side still ran.
    assert result.hubitat_ok is True


def test_hubitat_failure_still_writes_waterguru(
    patched: FakeClient, hub: dict[str, Any], tmp_path: Path
) -> None:
    hub["attributes_error"] = HubError("hub returned HTTP 500")
    hub["events_error"] = HubError("hub request failed")

    result = run(make_config(tmp_path, hub_token="hub-token"), now=FIXED_NOW)

    assert result.hubitat_ok is False
    assert result.hubitat_error is not None
    assert result.waterguru_ok is True
    assert result.written == 8
    assert (result.dose_points, result.tank_points, result.target_points) == (0, 0, 0)
    assert result.total_points == 8
    # The cloud side succeeded, so its watermark is still persisted.
    assert (tmp_path / "watermark.json").exists()


def test_only_events_failing_still_writes_the_snapshot_and_flags_degraded(
    patched: FakeClient, hub: dict[str, Any], tmp_path: Path
) -> None:
    hub["events_error"] = HubError("hub request failed")

    result = run(make_config(tmp_path, hub_token="hub-token"), now=FIXED_NOW)

    assert result.waterguru_ok is True
    assert result.hubitat_ok is False
    assert result.dose_event_points == 0
    assert result.dose_points == 1  # snapshot fallback
    assert (result.tank_points, result.target_points) == (1, 1)
    assert result.written == 11


def test_both_sources_failing_is_reported_not_raised(
    monkeypatch: pytest.MonkeyPatch,
    hub: dict[str, Any],
    tmp_path: Path,
    dashboard_sample: dict[str, Any],
    managepod_sample: dict[str, Any],
) -> None:
    fake = FakeClient(dashboard_sample, managepod_sample)

    def boom() -> None:
        raise WaterGuruError("cloud down")

    fake.authenticate = boom  # type: ignore[method-assign]
    monkeypatch.setattr(poller, "WaterGuruClient", lambda *a, **k: fake)
    FakeWriter.instances.clear()
    monkeypatch.setattr(poller, "InfluxWriter", FakeWriter)
    hub["attributes_error"] = HubError("hub down")
    hub["events_error"] = HubError("hub down")

    result = run(make_config(tmp_path, hub_token="hub-token"), now=FIXED_NOW)

    assert result.waterguru_ok is False
    assert result.hubitat_ok is False
    assert result.written == 0
    assert result.total_points == 0
    assert not (tmp_path / "watermark.json").exists()


def test_dry_run_prints_and_counts_hubitat_points_without_writing(
    patched: FakeClient, hub: dict[str, Any], tmp_path: Path, capsys: Any
) -> None:
    result = run(make_config(tmp_path, dry_run=True, hub_token="hub-token"), now=FIXED_NOW)

    assert result.written == 0
    assert FakeWriter.instances == []
    assert result.total_points == 11
    assert (result.dose_points, result.tank_points, result.target_points) == (1, 1, 1)
    out = capsys.readouterr().out
    assert "waterguru_pod" in out
    assert "chlorine_dose" in out
    assert "chlorine_tank" in out
    assert "chlorine_target" in out
    assert not (tmp_path / "watermark.json").exists()


def test_malformed_hub_values_do_not_suppress_waterguru_history(
    patched: FakeClient, hub: dict[str, Any], tmp_path: Path
) -> None:
    hub["attributes"] = {
        "lastDoseMl": float("inf"),
        "lastDoseEpochMs": 1,
        "tankRemainingMl": float("nan"),
        "tankPercent": 1e9,
        "freeChlorine": float("nan"),
    }
    hub["events"] = [
        {"name": "lastDoseEpochMs", "value": 1, "epoch": 1},
        {"name": "lastDoseMl", "value": float("nan"), "epoch": 1},
    ]

    result = run(make_config(tmp_path, hub_token="hub-token"), now=FIXED_NOW)

    assert result.waterguru_ok is True
    assert result.pod_points == 2 and result.dashboard_points == 6
    assert result.total_points == 8
    assert result.written == 8
    assert (result.dose_points, result.tank_points, result.target_points) == (0, 0, 0)


def test_run_persists_and_reuses_the_hubitat_event_cursor(
    patched: FakeClient, hub: dict[str, Any], tmp_path: Path
) -> None:
    config = make_config(tmp_path, hub_token="hub-token")
    run(config, now=FIXED_NOW)

    cursor_file = tmp_path / dosing.EventCursor.FILE_NAME
    assert cursor_file.exists()
    assert hub["event_kwargs"][0]["hours_back"] == dosing.EVENT_RECOVERY_DAYS * 24
    assert hub["event_kwargs"][0]["attribute"] == "lastDoseEpochMs"
    assert hub["event_kwargs"][0]["limit"] == dosing.EVENT_PAGE_LIMIT
    assert [kwargs["attribute"] for kwargs in hub["event_kwargs"][:3]] == [
        "lastDoseEpochMs",
        "lastDoseMl",
        "lastDoseRuntimeSeconds",
    ]

    run(config, now=FIXED_NOW + timedelta(minutes=5))
    second = hub["event_kwargs"][-1]
    assert second["since"] is not None
    assert second["hours_back"] is None
