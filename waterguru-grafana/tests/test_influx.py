"""Unit tests for the InfluxDB write path — retry semantics on the /api/v2/write POST."""

from __future__ import annotations

from typing import Any

import pytest

from waterguru_poller import influx
from waterguru_poller.influx import InfluxError, InfluxWriter
from waterguru_poller.lineprotocol import Point


class _Resp:
    def __init__(self, status_code: int, text: str = "") -> None:
        self.status_code = status_code
        self.text = text


class _Session:
    """Fake requests.Session yielding a scripted sequence of responses/exceptions."""

    def __init__(self, responses: list[Any]) -> None:
        self._responses = list(responses)
        self.calls = 0

    def post(self, *_args: Any, **_kwargs: Any) -> _Resp:
        self.calls += 1
        item = self._responses.pop(0)
        if isinstance(item, Exception):
            raise item
        return item


@pytest.fixture(autouse=True)
def _no_sleep(monkeypatch: pytest.MonkeyPatch) -> None:
    # The retry backoff sleeps 2*attempt seconds; skip the wait in tests.
    monkeypatch.setattr(influx.time, "sleep", lambda *_a, **_k: None)


def _writer(session: _Session) -> InfluxWriter:
    return InfluxWriter(
        "http://influx:8086", "tok", "home", "waterguru", session=session  # type: ignore[arg-type]
    )


_PTS = [Point("m", {}, {"v": 1}, 1)]


def test_write_retries_on_5xx_then_succeeds() -> None:
    session = _Session([_Resp(500, "writing points to database: timeout"), _Resp(204)])
    assert _writer(session).write(_PTS) == 1
    assert session.calls == 2  # one failed 5xx, one success


def test_write_raises_after_persistent_5xx() -> None:
    session = _Session([_Resp(503), _Resp(503), _Resp(503)])
    with pytest.raises(InfluxError):
        _writer(session).write(_PTS)
    assert session.calls == 3  # exhausts _WRITE_RETRIES


def test_write_fails_fast_on_4xx() -> None:
    session = _Session([_Resp(400, "bad line protocol")])
    with pytest.raises(InfluxError):
        _writer(session).write(_PTS)
    assert session.calls == 1  # client error is not retried


def test_write_succeeds_first_try() -> None:
    session = _Session([_Resp(204)])
    assert _writer(session).write(_PTS) == 1
    assert session.calls == 1
