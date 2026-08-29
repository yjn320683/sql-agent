from __future__ import annotations

import httpx
import pytest

from sql_agent_mcp_server.common.errors import McpDomainError
from sql_agent_mcp_server.common.http import ReadonlyJsonHttpClient


def _client_with_transport(monkeypatch, handler, *, max_response_bytes=1024):
    real_client = httpx.Client
    transport = httpx.MockTransport(handler)

    def factory(*args, **kwargs):
        kwargs["transport"] = transport
        return real_client(*args, **kwargs)

    monkeypatch.setattr(httpx, "Client", factory)
    return ReadonlyJsonHttpClient(
        ["http://primary", "http://secondary"],
        dependency_name="TEST_URLS",
        timeout_seconds=1,
        max_response_bytes=max_response_bytes,
    )


def test_http_client_retries_idempotent_get_and_fails_over(monkeypatch) -> None:
    calls = []

    def handler(request):
        calls.append(str(request.url))
        if request.url.host == "primary":
            raise httpx.ConnectError("offline", request=request)
        return httpx.Response(200, json={"ok": True})

    client = _client_with_transport(monkeypatch, handler)

    assert client.get_json("/health") == {"ok": True}
    assert len([url for url in calls if "primary" in url]) == 2
    assert any("secondary" in url for url in calls)


def test_http_client_rejects_oversized_response_without_leaking_url(monkeypatch) -> None:
    client = _client_with_transport(
        monkeypatch,
        lambda request: httpx.Response(200, content=b'{"data":"' + b"x" * 100 + b'"}'),
        max_response_bytes=32,
    )

    with pytest.raises(McpDomainError) as exc_info:
        client.get_json("/large")

    assert exc_info.value.code == "dependency_unavailable"
    assert "primary" not in str(exc_info.value.details)


def test_http_client_maps_404_to_not_found(monkeypatch) -> None:
    client = _client_with_transport(monkeypatch, lambda request: httpx.Response(404, json={}))

    with pytest.raises(McpDomainError) as exc_info:
        client.get_json("/missing", not_found_message="job missing")

    assert exc_info.value.code == "not_found"
    assert exc_info.value.message == "job missing"


def test_http_client_reports_missing_configuration() -> None:
    client = ReadonlyJsonHttpClient([], dependency_name="TEST_URLS", timeout_seconds=1)

    with pytest.raises(McpDomainError) as exc_info:
        client.get_json("/health")

    assert exc_info.value.details["required"] == "TEST_URLS"


def test_http_client_returns_bounded_text_with_truncation(monkeypatch) -> None:
    client = _client_with_transport(
        monkeypatch,
        lambda request: httpx.Response(200, content=b"x" * 100),
    )

    content, truncated = client.get_text("/logs", max_response_bytes=32)

    assert content == "x" * 32
    assert truncated is True
