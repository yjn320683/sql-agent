"""HiveServer2 编译服务。"""

from __future__ import annotations

from sql_agent_mcp_server.domains.hive_execution.adapter import HiveCompilationError, HiveServer2Adapter
from sql_agent_mcp_server.domains.hive_execution.schemas import (
    HiveExplainRequest,
    HiveExplainResponse,
    HiveFunctionDetailRequest,
    HiveFunctionDetailResponse,
    HiveFunctionSearchRequest,
    HiveFunctionSearchResponse,
    HivePreviewRequest,
    HivePreviewResponse,
    HiveSqlRequest,
    HiveValidationResponse,
)
from sql_agent_mcp_server.settings import Settings


class HiveExecutionService:
    def __init__(self, adapter: HiveServer2Adapter) -> None:
        self.adapter = adapter

    @classmethod
    def from_settings(cls, settings: Settings) -> "HiveExecutionService":
        return cls(HiveServer2Adapter(settings.hive_server2_uri, timeout_seconds=settings.mcp_tool_timeout_seconds))

    def validate(self, request: HiveSqlRequest) -> HiveValidationResponse:
        try:
            result = self.adapter.explain(request.sql, default_db=request.default_db, extended=False)
        except HiveCompilationError as exc:
            return HiveValidationResponse(
                source=self.adapter.source,
                valid=False,
                defaultDb=exc.default_db,
                compilationMs=exc.compilation_ms,
                errors=[{"type": "compilation_error", "message": str(exc)}],
            )
        return HiveValidationResponse(
            source=self.adapter.source,
            valid=True,
            defaultDb=result.default_db,
            compilationMs=result.compilation_ms,
        )

    def explain(self, request: HiveExplainRequest) -> HiveExplainResponse:
        try:
            result = self.adapter.explain(
                request.sql,
                default_db=request.default_db,
                extended=request.extended,
            )
        except HiveCompilationError as exc:
            from sql_agent_mcp_server.common.errors import McpDomainError, McpErrorCode

            raise McpDomainError(
                McpErrorCode.INVALID_REQUEST,
                "Hive SQL compilation failed.",
                details={"compilationError": str(exc)},
            ) from exc
        warnings = ["Explain 原始输出超过返回上限，已保留关键执行信号并压缩。"] if result.truncated else []
        return HiveExplainResponse(
            source=self.adapter.source,
            planText=result.plan_text,
            defaultDb=result.default_db,
            compilationMs=result.compilation_ms,
            truncated=result.truncated,
            complete=not result.truncated,
            missingReasons=["plan_text_compacted"] if result.truncated else [],
            warnings=warnings,
        )

    def preview(self, request: HivePreviewRequest) -> HivePreviewResponse:
        result = self.adapter.preview_query(
            request.sql, default_db=request.default_db, limit=request.limit,
            timeout_seconds=request.timeout_seconds,
        )
        return HivePreviewResponse(
            source=self.adapter.source,
            defaultDb=result.default_db,
            columns=result.columns,
            rows=result.rows,
            rowCount=len(result.rows),
            truncated=result.truncated,
            elapsedMs=result.elapsed_ms,
        )

    def search_functions(self, request: HiveFunctionSearchRequest) -> HiveFunctionSearchResponse:
        result = self.adapter.search_functions(
            keyword=request.keyword,
            limit=request.limit,
            offset=request.offset,
            default_db=request.default_db,
        )
        return HiveFunctionSearchResponse(
            source=self.adapter.source,
            items=result.items,
            total=result.total,
            limit=result.limit,
            offset=result.offset,
            keyword=request.keyword.strip(),
            defaultDb=result.default_db,
            elapsedMs=result.elapsed_ms,
        )

    def get_function(self, request: HiveFunctionDetailRequest) -> HiveFunctionDetailResponse:
        result = self.adapter.get_function(request.name, default_db=request.default_db)
        warnings = ["函数说明超过返回上限，已截断。"] if result.truncated else []
        return HiveFunctionDetailResponse(
            source=self.adapter.source,
            name=result.name,
            lines=result.lines,
            properties=result.properties,
            defaultDb=result.default_db,
            elapsedMs=result.elapsed_ms,
            truncated=result.truncated,
            complete=not result.truncated,
            missingReasons=["function_description_truncated"] if result.truncated else [],
            warnings=warnings,
        )
