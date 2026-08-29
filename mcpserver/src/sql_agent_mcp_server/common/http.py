"""Hadoop 只读 HTTP 客户端。"""

from __future__ import annotations

import json
from collections.abc import Iterable
from typing import Any

import httpx

from sql_agent_mcp_server.common.errors import McpDomainError, McpErrorCode


class ReadonlyJsonHttpClient:
    """对多个只读端点执行有限重试，并限制响应体大小。"""

    def __init__(
        self,
        base_urls: Iterable[str],
        *,
        dependency_name: str,
        timeout_seconds: int,
        http_user: str | None = None,
        max_response_bytes: int = 16 * 1024 * 1024,
    ) -> None:
        self.base_urls = tuple(url.rstrip("/") for url in base_urls if url.strip())
        self.dependency_name = dependency_name
        self.timeout_seconds = timeout_seconds
        self.http_user = http_user.strip() if http_user else None
        self.max_response_bytes = max_response_bytes

    def get_json(
        self,
        path: str,
        *,
        params: dict[str, Any] | None = None,
        not_found_message: str | None = None,
    ) -> dict[str, Any]:
        if not self.base_urls:
            raise McpDomainError(
                McpErrorCode.DEPENDENCY_UNAVAILABLE,
                f"{self.dependency_name} is not configured.",
                details={"dependency": self.dependency_name, "required": self.dependency_name},
            )

        request_params = {key: value for key, value in (params or {}).items() if value is not None}
        if self.http_user and "user.name" not in request_params:
            request_params["user.name"] = self.http_user

        errors: list[str] = []
        for base_url in self.base_urls:
            for _ in range(2):
                try:
                    return self._get_once(base_url + "/" + path.lstrip("/"), request_params)
                except _NotFoundError as exc:
                    raise McpDomainError(
                        McpErrorCode.NOT_FOUND,
                        not_found_message or f"{self.dependency_name} resource was not found.",
                        details={"dependency": self.dependency_name},
                    ) from exc
                except (httpx.HTTPError, ValueError, _ResponseTooLargeError) as exc:
                    errors.append(type(exc).__name__)

        raise McpDomainError(
            McpErrorCode.DEPENDENCY_UNAVAILABLE,
            f"{self.dependency_name} request failed.",
            details={"dependency": self.dependency_name, "errorTypes": sorted(set(errors))},
        )

    def get_text(
        self,
        path: str,
        *,
        params: dict[str, Any] | None = None,
        not_found_message: str | None = None,
        max_response_bytes: int = 256 * 1024,
    ) -> tuple[str, bool]:
        """读取有界文本响应；超过上限时返回已截断标记。"""

        if not self.base_urls:
            raise McpDomainError(
                McpErrorCode.DEPENDENCY_UNAVAILABLE,
                f"{self.dependency_name} is not configured.",
                details={"dependency": self.dependency_name, "required": self.dependency_name},
            )

        request_params = {key: value for key, value in (params or {}).items() if value is not None}
        if self.http_user and "user.name" not in request_params:
            request_params["user.name"] = self.http_user

        errors: list[str] = []
        for base_url in self.base_urls:
            for _ in range(2):
                try:
                    return self._get_text_once(
                        base_url + "/" + path.lstrip("/"),
                        request_params,
                        max_response_bytes=max_response_bytes,
                    )
                except _NotFoundError as exc:
                    raise McpDomainError(
                        McpErrorCode.NOT_FOUND,
                        not_found_message or f"{self.dependency_name} resource was not found.",
                        details={"dependency": self.dependency_name},
                    ) from exc
                except (httpx.HTTPError, ValueError) as exc:
                    errors.append(type(exc).__name__)

        raise McpDomainError(
            McpErrorCode.DEPENDENCY_UNAVAILABLE,
            f"{self.dependency_name} request failed.",
            details={"dependency": self.dependency_name, "errorTypes": sorted(set(errors))},
        )

    def _get_once(self, url: str, params: dict[str, Any]) -> dict[str, Any]:
        timeout = httpx.Timeout(self.timeout_seconds)
        with httpx.Client(timeout=timeout, follow_redirects=True) as client:
            with client.stream("GET", url, params=params, headers={"Accept": "application/json"}) as response:
                if response.status_code == 404:
                    raise _NotFoundError
                response.raise_for_status()
                content_length = response.headers.get("content-length")
                if content_length and int(content_length) > self.max_response_bytes:
                    raise _ResponseTooLargeError
                payload = bytearray()
                for chunk in response.iter_bytes():
                    payload.extend(chunk)
                    if len(payload) > self.max_response_bytes:
                        raise _ResponseTooLargeError
        decoded = json.loads(payload.decode("utf-8"))
        if not isinstance(decoded, dict):
            raise ValueError("JSON response root must be an object")
        return decoded

    def _get_text_once(
        self,
        url: str,
        params: dict[str, Any],
        *,
        max_response_bytes: int,
    ) -> tuple[str, bool]:
        timeout = httpx.Timeout(self.timeout_seconds)
        payload = bytearray()
        truncated = False
        with httpx.Client(timeout=timeout, follow_redirects=True) as client:
            with client.stream("GET", url, params=params, headers={"Accept": "text/html,text/plain"}) as response:
                if response.status_code == 404:
                    raise _NotFoundError
                response.raise_for_status()
                for chunk in response.iter_bytes():
                    remaining = max_response_bytes - len(payload)
                    if remaining <= 0:
                        truncated = True
                        break
                    payload.extend(chunk[:remaining])
                    if len(chunk) > remaining:
                        truncated = True
                        break
        return payload.decode("utf-8", errors="replace"), truncated


class _NotFoundError(Exception):
    pass


class _ResponseTooLargeError(Exception):
    pass
