"""调用 backend 的 Java parse-sql 血缘事实接口。"""

from __future__ import annotations

import json
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from app.settings import get_settings


def get_task_lineage(task_id: int, version_no: int | None, default_db: str | None) -> dict[str, Any]:
    return _request(f"/api/workspace/tasks/{task_id}/lineage", version_no, default_db)


def get_task_dependencies(task_id: int, version_no: int | None, default_db: str | None) -> dict[str, Any]:
    return _request(f"/api/workspace/tasks/{task_id}/dependencies", version_no, default_db)


def _request(path: str, version_no: int | None, default_db: str | None) -> dict[str, Any]:
    base_url = get_settings().backend_base_url
    if not base_url:
        raise ValueError("未配置 SQL_AGENT_BACKEND_URL，无法获取 Java parse-sql 血缘结果。")
    query = {}
    if version_no is not None:
        query["versionNo"] = str(version_no)
    if default_db:
        query["defaultDb"] = default_db
    url = f"{base_url}{path}"
    if query:
        url = f"{url}?{urlencode(query)}"
    request = Request(url, headers={"X-Ob-Id": "agent"})
    try:
        with urlopen(request, timeout=15) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:300]
        raise ValueError(f"Java 血缘接口返回 HTTP {exc.code}：{detail}") from exc
    except (URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise ValueError(f"Java 血缘接口不可用：{exc}") from exc
    if not isinstance(payload, dict) or int(payload.get("code", -1)) != 0:
        message = payload.get("message") if isinstance(payload, dict) else "响应格式错误"
        raise ValueError(f"Java 血缘接口失败：{message}")
    data = payload.get("data")
    if not isinstance(data, dict):
        raise ValueError("Java 血缘接口未返回对象结果。")
    return data
