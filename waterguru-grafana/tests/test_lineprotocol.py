from __future__ import annotations

import pytest

from waterguru_poller.lineprotocol import Point, serialize


def test_basic_line() -> None:
    p = Point("waterguru_pod", tags={"pod_id": "1"}, fields={"ph": 7.8}, timestamp_ns=1000)
    assert p.to_line() == "waterguru_pod,pod_id=1 ph=7.8 1000"


def test_field_types() -> None:
    p = Point(
        "m",
        fields={"f": 1.5, "i": 3, "b": True, "s": "hi"},
        timestamp_ns=None,
    )
    line = p.to_line()
    # int gets the trailing 'i', bool is lowercase literal, str is quoted, float bare.
    assert "f=1.5" in line
    assert "i=3i" in line
    assert "b=true" in line
    assert 's="hi"' in line
    assert not line.endswith(" ")  # no dangling timestamp separator


def test_tags_sorted_and_empty_skipped() -> None:
    p = Point("m", tags={"z": "1", "a": "2", "empty": ""}, fields={"v": 1.0})
    line = p.to_line()
    assert line.startswith("m,a=2,z=1 ")
    assert "empty" not in line


def test_escaping() -> None:
    p = Point(
        "space name",
        tags={"k ey": "va,lue"},
        fields={"txt": 'quote"here'},
    )
    line = p.to_line()
    assert "space\\ name" in line
    assert "k\\ ey=va\\,lue" in line
    assert 'txt="quote\\"here"' in line


def test_no_fields_raises() -> None:
    with pytest.raises(ValueError):
        Point("m", fields={}).to_line()


def test_all_null_fields_raises() -> None:
    with pytest.raises(ValueError):
        Point("m", fields={"a": None}).to_line()


def test_serialize_multiple() -> None:
    pts = [
        Point("m", fields={"v": 1.0}, timestamp_ns=1),
        Point("m", fields={"v": 2.0}, timestamp_ns=2),
    ]
    assert serialize(pts) == "m v=1.0 1\nm v=2.0 2"
