"""执行日志脱敏和有限写入。"""

from __future__ import annotations

import re
from pathlib import Path

MAX_LOG_BYTES = 50 * 1024 * 1024
MAX_LINE_CHARS = 20_000
SECRET_PATTERN = re.compile(r"(?i)(password|passwd|token|secret|access[_-]?key)\s*[=:]\s*[^\s;,]+")
URI_USERINFO_PATTERN = re.compile(r"(?i)([a-z][a-z0-9+.-]*://)[^/@\s]+@")


class ExecutionLogWriter:
    def __init__(self, root: str, execution_id: int, step_no: int | None = None) -> None:
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        if step_no is None:
            self.path = self.root / f"{execution_id}.log"
        else:
            step_root = self.root / str(execution_id)
            step_root.mkdir(parents=True, exist_ok=True)
            self.path = step_root / f"step-{step_no}.log"
        self._truncated = False

    def append(self, lines: list[str] | str) -> None:
        if self._truncated:
            return
        values = [lines] if isinstance(lines, str) else lines
        payload = "".join(_sanitize_line(line) + "\n" for line in values).encode("utf-8")
        current = self.path.stat().st_size if self.path.exists() else 0
        remaining = MAX_LOG_BYTES - current
        if remaining <= 0:
            self._truncated = True
            return
        if len(payload) > remaining:
            marker = "\n[WARN] 日志达到50MB上限，后续内容已截断。\n".encode("utf-8")
            payload = payload[:max(0, remaining - len(marker))] + marker
            self._truncated = True
        with self.path.open("ab") as stream:
            stream.write(payload)


def read_log_tail(root: str, execution_id: int, max_bytes: int = 64 * 1024) -> str:
    path = Path(root).resolve() / f"{execution_id}.log"
    if not path.is_file():
        return ""
    with path.open("rb") as stream:
        size = path.stat().st_size
        stream.seek(max(0, size - max_bytes))
        return stream.read(max_bytes).decode("utf-8", errors="replace")


def _sanitize_line(line: str) -> str:
    value = str(line).replace("\x00", "")[:MAX_LINE_CHARS]
    value = SECRET_PATTERN.sub(lambda match: f"{match.group(1)}=<redacted>", value)
    return URI_USERINFO_PATTERN.sub(r"\1<redacted>@", value)
