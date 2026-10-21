from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path

from waterguru_poller.watermark import Watermark


def test_missing_file_is_empty(tmp_path: Path) -> None:
    wm = Watermark(str(tmp_path / "nope.json"))
    assert wm.last_seen("1") is None


def test_update_and_roundtrip(tmp_path: Path) -> None:
    path = str(tmp_path / "state" / "watermark.json")
    wm = Watermark(path)
    t1 = datetime(2026, 8, 18, 14, 0, tzinfo=UTC)
    wm.update("999001", t1)
    wm.save()

    reloaded = Watermark(path)
    assert reloaded.last_seen("999001") == t1


def test_update_is_monotonic(tmp_path: Path) -> None:
    wm = Watermark(str(tmp_path / "w.json"))
    newer = datetime(2026, 8, 18, 14, 0, tzinfo=UTC)
    older = datetime(2026, 8, 17, 14, 0, tzinfo=UTC)
    wm.update("1", newer)
    wm.update("1", older)  # should not move backwards
    assert wm.last_seen("1") == newer


def test_corrupt_file_tolerated(tmp_path: Path) -> None:
    path = tmp_path / "bad.json"
    path.write_text("{not json", encoding="utf-8")
    wm = Watermark(str(path))
    assert wm.last_seen("1") is None
