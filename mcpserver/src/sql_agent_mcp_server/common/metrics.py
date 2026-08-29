"""诊断指标归一化工具。"""

from __future__ import annotations

import math
from collections.abc import Iterable
from typing import Any


def distribution(values: Iterable[int | float | None]) -> dict[str, int | float | None]:
    ordered = sorted(float(value) for value in values if value is not None)
    if not ordered:
        return {"count": 0, "min": None, "p50": None, "p95": None, "max": None}
    return {
        "count": len(ordered),
        "min": _clean_number(ordered[0]),
        "p50": _clean_number(_percentile(ordered, 0.50)),
        "p95": _clean_number(_percentile(ordered, 0.95)),
        "max": _clean_number(ordered[-1]),
    }


def safe_delta(baseline: int | float | None, candidate: int | float | None) -> dict[str, Any]:
    if baseline is None or candidate is None:
        return {"baseline": baseline, "candidate": candidate, "absolute": None, "percent": None}
    absolute = candidate - baseline
    percent = None if baseline == 0 else absolute / baseline * 100
    return {
        "baseline": baseline,
        "candidate": candidate,
        "absolute": _clean_number(float(absolute)),
        "percent": _clean_number(float(percent)) if percent is not None else None,
    }


def _percentile(values: list[float], quantile: float) -> float:
    position = (len(values) - 1) * quantile
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return values[lower]
    return values[lower] + (values[upper] - values[lower]) * (position - lower)


def _clean_number(value: float) -> int | float:
    return int(value) if value.is_integer() else round(value, 3)
