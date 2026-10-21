from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

FIXTURES = Path(__file__).parent / "fixtures"


def _load(name: str) -> dict[str, Any]:
    with open(FIXTURES / name, encoding="utf-8") as fh:
        data: dict[str, Any] = json.load(fh)
    return data


@pytest.fixture
def dashboard_sample() -> dict[str, Any]:
    return _load("dashboard_sample.json")


@pytest.fixture
def managepod_sample() -> dict[str, Any]:
    return _load("managepod_sample.json")
