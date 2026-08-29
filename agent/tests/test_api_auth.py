from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from app.api import auth


def test_service_token_accepts_matching_token(monkeypatch):
    monkeypatch.setattr(auth, "get_settings", lambda: SimpleNamespace(service_token="shared-token"))

    auth.require_service_token("shared-token")


def test_service_token_rejects_missing_or_wrong_token(monkeypatch):
    monkeypatch.setattr(auth, "get_settings", lambda: SimpleNamespace(service_token="shared-token"))

    with pytest.raises(HTTPException) as missing:
        auth.require_service_token(None)
    with pytest.raises(HTTPException) as wrong:
        auth.require_service_token("wrong-token")

    assert missing.value.status_code == 401
    assert wrong.value.status_code == 401


def test_service_token_fails_closed_when_not_configured(monkeypatch):
    monkeypatch.setattr(auth, "get_settings", lambda: SimpleNamespace(service_token=""))

    with pytest.raises(HTTPException) as error:
        auth.require_service_token("anything")

    assert error.value.status_code == 503
