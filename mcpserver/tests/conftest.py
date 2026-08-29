from __future__ import annotations

import pytest


@pytest.fixture(autouse=True)
def mcp_principal(monkeypatch):
    monkeypatch.setenv("SQL_AGENT_OB_ID", "10001")
