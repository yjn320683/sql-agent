#!/usr/bin/env python3
"""对已启动的 SQL Agent 执行无 DDL、无 SQL 执行的全链路冒烟测试。"""

from __future__ import annotations

import argparse
import json
import uuid
from http.cookiejar import CookieJar
from urllib.error import HTTPError
from urllib.request import HTTPCookieProcessor, Request, build_opener


def request(opener, url: str, *, method: str = "GET", payload: dict | None = None, timeout: int = 30):
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
    headers = {"Content-Type": "application/json"} if data is not None else {}
    response = opener.open(Request(url, data=data, headers=headers, method=method), timeout=timeout)
    return response.status, response.headers, response.read().decode("utf-8")


def api_data(opener, base_url: str, path: str) -> dict:
    _, _, body = request(opener, f"{base_url}{path}")
    parsed = json.loads(body)
    assert parsed.get("code") == 0, f"接口调用失败：{path}"
    return parsed.get("data") or {}


def resolve_task_id(opener, base_url: str, task_id: int | None) -> int:
    if task_id is not None:
        return task_id
    page = api_data(opener, base_url, "/api/tasks?page=1&pageSize=1&status=all")
    items = page.get("items") or []
    assert items, "当前没有可用于冒烟测试的任务，请通过 --task-id 指定任务或先创建任务"
    return int(items[0]["id"])


def run(base_url: str, ob_id: str, live_agent: bool, timeout: int, task_id: int | None) -> None:
    opener = build_opener(HTTPCookieProcessor(CookieJar()))
    session_id = str(uuid.uuid4())

    root_status, _, root_body = request(opener, f"{base_url}/")
    assert root_status == 200 and '<div id="root">' in root_body, "front 静态资源未由 backend 提供"

    login_status, _, _ = request(
        opener,
        f"{base_url}/api/auth/login",
        method="POST",
        payload={"obId": ob_id},
    )
    assert login_status == 200, "本地调试登录失败"

    resolved_task_id = resolve_task_id(opener, base_url, task_id)

    sessions_status, _, _ = request(opener, f"{base_url}/api/chat/sessions")
    assert sessions_status == 200, "chat_session 查询失败，请确认已显式执行 DDL"

    stream_status, stream_headers, stream_body = request(
        opener,
        f"{base_url}/api/chat/stream",
        method="POST",
        payload={
            "sessionId": session_id,
            "taskId": resolved_task_id,
            "command": "sql_static_check",
            "message": "select * from dw.orders",
        },
    )
    assert stream_status == 200, "backend SSE 代理失败"
    assert "text/event-stream" in stream_headers.get("Content-Type", ""), "响应不是 SSE"
    assert "event:text" in stream_body or "event: text" in stream_body, "SSE 缺少 text 事件"
    assert "event:done" in stream_body or "event: done" in stream_body, "SSE 缺少 done 事件"

    _, _, sessions_body = request(opener, f"{base_url}/api/chat/sessions")
    assert session_id in sessions_body, "测试会话未写入 chat_session"

    summary = {
        "front": "ok",
        "auth": "ok",
        "chatSession": "ok",
        "backendAgentSse": "ok",
        "taskId": resolved_task_id,
        "sessionId": session_id,
    }
    if live_agent:
        live_session_id = str(uuid.uuid4())
        _, _, live_body = request(
            opener,
            f"{base_url}/api/chat/stream",
            method="POST",
            timeout=timeout,
            payload={
                "sessionId": live_session_id,
                "taskId": resolved_task_id,
                "command": "sql_explain",
                "message": "解释以下 Hive SQL 的含义：SELECT 1。不要请求额外信息。",
            },
        )
        done = "event:done" in live_body or "event: done" in live_body
        error = "event:error" in live_body or "event: error" in live_body
        assert done or error, "Claude SSE 未返回 done 或显式 error"
        summary["liveAgentTerminal"] = "done" if done else "error"
        summary["liveSessionId"] = live_session_id

    print(json.dumps(summary, ensure_ascii=False))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8382")
    parser.add_argument("--ob-id", required=True)
    parser.add_argument("--live-agent", action="store_true")
    parser.add_argument("--task-id", type=int)
    parser.add_argument("--timeout", type=int, default=600)
    args = parser.parse_args()
    try:
        run(args.base_url.rstrip("/"), args.ob_id, args.live_agent, args.timeout, args.task_id)
    except HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise SystemExit(f"HTTP {exc.code}: {body[:500]}") from exc


if __name__ == "__main__":
    main()
