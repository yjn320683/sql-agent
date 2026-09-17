from __future__ import annotations

import io
from types import SimpleNamespace

from app.domain.sql import backend_lineage


class _Response:
    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def read(self) -> bytes:
        return b'{"code":0,"data":{"source":"java-parse-sql"}}'


def test_task_lineage_delegates_to_backend_java_parser(monkeypatch) -> None:
    captured = {}
    monkeypatch.setattr(backend_lineage, "get_settings", lambda: SimpleNamespace(
        backend_base_url="http://backend:8080",
    ))

    def open_request(request, timeout):
        captured["url"] = request.full_url
        captured["obId"] = request.headers["X-ob-id"]
        captured["timeout"] = timeout
        return _Response()

    monkeypatch.setattr(backend_lineage, "urlopen", open_request)

    result = backend_lineage.get_task_lineage(9, 3, "dw")

    assert result["source"] == "java-parse-sql"
    assert captured["url"] == "http://backend:8080/api/workspace/tasks/9/lineage?versionNo=3&defaultDb=dw"
    assert captured["obId"] == "agent"
    assert captured["timeout"] == 15
