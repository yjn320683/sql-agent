"""SQL 开发工作台的真实 Hive 元数据与编译接口。"""

from __future__ import annotations

import asyncio
import json
import re
from datetime import datetime, timezone
from functools import lru_cache
from typing import Any

from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel, Field

from app.domain.sql.backend_lineage import get_task_dependencies as get_java_task_dependencies
from app.domain.sql.backend_lineage import get_task_lineage as get_java_task_lineage
from app.domain.sql.static_check import static_check_sql
from app.domain.sql.completion import SqlCompletionService
from app.execution.sql_script import parse_and_render_script

from sql_agent_mcp_server.common.errors import McpDomainError, McpErrorCode
from sql_agent_mcp_server.common.schemas import dump_response
from sql_agent_mcp_server.domains.hive_execution.schemas import (
    HiveExplainRequest,
    HiveFunctionDetailRequest,
    HiveFunctionSearchRequest,
    HivePreviewRequest,
    HiveSqlRequest,
)
from sql_agent_mcp_server.domains.hive_execution.service import HiveExecutionService
from sql_agent_mcp_server.domains.hive_metadata.schemas import (
    GetColumnsRequest,
    GetPartitionsRequest,
    GetTableDdlRequest,
    GetTableRequest,
    GetTableStatisticsRequest,
    ListDatabasesRequest,
    SearchTablesRequest,
)
from sql_agent_mcp_server.domains.hive_metadata.service import HiveMetadataService
from sql_agent_mcp_server.domains.hadoop_runtime.schemas import (
    JobDiagnosticsRequest,
    StorageLayoutRequest,
    TableFreshnessRequest,
)
from sql_agent_mcp_server.domains.hadoop_runtime.service import HadoopRuntimeService
from sql_agent_mcp_server.domains.sql_task.service import SqlTaskService
from sql_agent_mcp_server.domains.data_map.schemas import GetTablePrimaryKeysRequest
from sql_agent_mcp_server.domains.data_map.service import DataMapService
from sql_agent_mcp_server.settings import get_settings as get_mcp_settings

router = APIRouter(prefix="/workspace", tags=["workspace"])


@lru_cache
def _metadata_service() -> HiveMetadataService:
    return HiveMetadataService.from_settings(get_mcp_settings())


@lru_cache
def _execution_service() -> HiveExecutionService:
    return HiveExecutionService.from_settings(get_mcp_settings())


@lru_cache
def _task_service() -> SqlTaskService:
    return SqlTaskService.from_settings(get_mcp_settings())


@lru_cache
def _hadoop_service() -> HadoopRuntimeService:
    return HadoopRuntimeService.from_settings(
        get_mcp_settings(),
        metadata=_metadata_service(),
        hive_execution=_execution_service(),
    )


@lru_cache
def _completion_service() -> SqlCompletionService:
    return SqlCompletionService(_metadata_service(), _execution_service())


@lru_cache
def _data_map_service() -> DataMapService:
    return DataMapService.from_settings(get_mcp_settings())


class SqlCompletionRequest(BaseModel):
    sql: str = Field(max_length=1_000_000)
    cursor: int = Field(ge=0)
    default_db: str | None = Field(default=None, alias="defaultDb", max_length=256)
    limit: int = Field(default=100, ge=1, le=200)


class SqlStructureRequest(BaseModel):
    sql: str = Field(min_length=1, max_length=1_000_000)
    parameter_schema: list[dict[str, Any]] = Field(default_factory=list, alias="parameterSchema")
    parameters: dict[str, Any] = Field(default_factory=dict)
    business_date: str | None = Field(default=None, alias="businessDate")
    validate_parameter_values: bool = Field(default=True, alias="validateParameterValues")


class SqlQueryPreviewRequest(BaseModel):
    sql: str = Field(min_length=1, max_length=1_000_000)
    default_db: str | None = Field(default=None, alias="defaultDb", max_length=256)
    limit: int = Field(default=100, ge=1, le=200)


@router.post("/sql/completions")
async def complete_sql(request: SqlCompletionRequest) -> dict[str, Any]:
    return await _run(lambda: _completion_service().complete(
        request.sql, request.cursor, request.default_db, request.limit,
    ))


@router.post("/sql/structure")
async def preview_sql_structure(request: SqlStructureRequest) -> dict[str, Any]:
    def preview() -> dict[str, Any]:
        definitions = request.parameter_schema
        values = request.parameters
        business_date = request.business_date
        if not request.validate_parameter_values:
            values = _preview_parameter_values(definitions)
            business_date = business_date or "2000-01-01"
        rendered, steps, resolved = parse_and_render_script(
            request.sql,
            json.dumps(definitions, ensure_ascii=False),
            json.dumps(values, ensure_ascii=False),
            business_date,
        )
        return {
            "valid": True,
            "stepCount": len(steps),
            "renderedSql": rendered if request.validate_parameter_values else None,
            "parameters": resolved if request.validate_parameter_values else {},
            "steps": [
                {
                    "stepNo": step.number,
                    "stepOrder": step.order,
                    "stepName": step.name,
                    "statementType": _statement_type(step.rendered_sql),
                    "sql": step.source_sql,
                    "renderedSql": step.rendered_sql if request.validate_parameter_values else None,
                }
                for step in steps
            ],
        }

    return await _run(preview)


@router.post("/sql/query-preview")
async def preview_sql_query(request: SqlQueryPreviewRequest) -> dict[str, Any]:
    """执行后端已通过语法树转换的服务端限量只读查询。"""
    return await _run(lambda: dump_response(_execution_service().preview(HivePreviewRequest(
        sql=request.sql,
        defaultDb=request.default_db,
        limit=request.limit,
        timeoutSeconds=30,
    ))))


def _preview_parameter_values(definitions: list[dict[str, Any]]) -> dict[str, Any]:
    samples = {
        "STRING": "preview",
        "INTEGER": 0,
        "DECIMAL": "0",
        "DATE": "2000-01-01",
        "DATETIME": "2000-01-01T00:00:00",
        "BOOLEAN": False,
    }
    return {
        str(item.get("name") or ""): item.get("defaultValue")
        if item.get("defaultValue") not in (None, "")
        else samples.get(str(item.get("type") or "").upper(), "preview")
        for item in definitions
        if item.get("name")
    }


def _statement_type(sql: str) -> str:
    match = re.search(r"(?is)^\s*(?:--[^\n]*\n|/\*.*?\*/\s*)*([a-z]+)", sql)
    return match.group(1).upper() if match else "UNKNOWN"


def _get_task_payload(service: SqlTaskService, task_id: int, version_no: int | None) -> dict[str, Any]:
    resolved_version_no = version_no if isinstance(version_no, int) else None
    result = service.get_task(task_id) if resolved_version_no is None else service.get_task(task_id, resolved_version_no)
    return result.get("task") or {}


def _task_rendered_steps(task: dict[str, Any]):
    """把任务脚本统一转换为可提交给 Hive 的 Step SQL。"""
    definitions = list(task.get("parameters") or [])
    preview_values = _preview_parameter_values(definitions)
    _, steps, _ = parse_and_render_script(
        str(task.get("sql") or ""),
        json.dumps(definitions, ensure_ascii=False),
        json.dumps(preview_values, ensure_ascii=False),
        datetime.now(timezone.utc).date(),
    )
    return steps


def _validate_rendered_steps(steps, default_db: str | None) -> dict[str, Any]:
    results = [
        dump_response(_execution_service().validate(HiveSqlRequest(
            sql=step.rendered_sql, defaultDb=default_db,
        )))
        for step in steps
    ]
    errors: list[dict[str, str]] = []
    warnings: list[str] = []
    missing_reasons: list[str] = []
    for step, result in zip(steps, results):
        errors.extend({
            **error,
            "stepNo": step.number,
            "stepName": step.name,
            "message": f"Step {step.number}（{step.name}）：{error.get('message') or 'Hive 编译失败。'}",
        } for error in result.get("errors", []))
        warnings.extend(
            f"Step {step.number}（{step.name}）：{warning}"
            for warning in result.get("warnings", [])
        )
        missing_reasons.extend(result.get("missingReasons", []))
    response = {
        "ok": True,
        "source": "hiveserver2",
        "fetchedAt": datetime.now(timezone.utc).isoformat(),
        "valid": all(bool(result.get("valid")) for result in results),
        "defaultDb": results[0].get("defaultDb") or default_db or "default",
        "compilationMs": sum(int(result.get("compilationMs") or 0) for result in results),
        "errors": errors,
        "steps": [
            {
                "stepNo": step.number,
                "stepName": step.name,
                "valid": bool(result.get("valid")),
                "errors": [{**error, "stepNo": step.number, "stepName": step.name}
                           for error in result.get("errors", [])],
                "warnings": list(result.get("warnings", [])),
            }
            for step, result in zip(steps, results)
        ],
        "warnings": warnings,
        "complete": all(bool(result.get("complete", True)) for result in results),
        "missingReasons": list(dict.fromkeys(missing_reasons)),
    }
    if len(results) == 1:
        response.update({key: value for key, value in results[0].items()
                         if key not in {"errors", "warnings", "missingReasons"}})
        response["errors"] = errors
        response["warnings"] = warnings
        response["missingReasons"] = list(dict.fromkeys(missing_reasons))
    return response


def _explain_risks(plan_text: str) -> list[dict[str, str]]:
    """只根据 Explain 原文中的明确证据生成风险，不从 SQL 文本推测。"""
    rules = (
        ("CARTESIAN_JOIN", "检测到笛卡尔连接", ("cartesian product", "cross product")),
        ("GLOBAL_SORT", "检测到全局排序", ("global sort", "order by operator")),
        ("PARTITION_NOT_PRUNED", "执行计划明确显示分区未裁剪", (
            "partition pruning: false", "partition predicate: null", "pruned partition list: []",
        )),
    )
    lines = [line.strip() for line in str(plan_text or "").splitlines() if line.strip()]
    risks: list[dict[str, str]] = []
    for code, message, markers in rules:
        evidence = next((line for line in lines if any(marker in line.lower() for marker in markers)), None)
        if evidence:
            risks.append({"code": code, "level": "warning", "message": message, "evidence": evidence[:500]})
    return risks


async def _run(operation) -> dict[str, Any]:
    try:
        return await asyncio.to_thread(operation)
    except McpDomainError as exc:
        status = 400 if exc.code in {McpErrorCode.INVALID_REQUEST, McpErrorCode.NOT_FOUND} else 503
        raise HTTPException(status_code=status, detail=exc.message) from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.get("/platform/health")
async def get_platform_health() -> dict[str, Any]:
    return await _run(lambda: dump_response(_hadoop_service().dependency_health()))


@router.get("/data-map/tables/{db}/{table}/primary-keys")
async def get_data_map_primary_keys(db: str, table: str) -> dict[str, Any]:
    return await _run(lambda: dump_response(_data_map_service().get_table_primary_keys(
        GetTablePrimaryKeysRequest(db=db, table=table),
    )))


@router.get("/task-executions/{execution_id}/diagnostics")
async def get_task_execution_diagnostics(execution_id: int) -> dict[str, Any]:
    def diagnose() -> dict[str, Any]:
        execution = (_task_service().get_execution(execution_id).get("execution") or {})
        job_ids = list(execution.get("job_ids") or [])
        application_ids = list(execution.get("application_ids") or [])
        execution_summary = {
            "id": execution.get("id"),
            "taskId": execution.get("task_id"),
            "status": execution.get("status"),
            "queryId": execution.get("query_id"),
            "jobIds": job_ids,
            "applicationIds": application_ids,
        }
        if not job_ids and not application_ids:
            return {
                "ok": True,
                "source": "sql-agent-db",
                "fetchedAt": datetime.now(timezone.utc).isoformat(),
                "warnings": ["该实例尚未记录 MapReduce Job 或 YARN Application 标识。"],
                "complete": False,
                "missingReasons": ["runtime_identifiers_missing"],
                "execution": execution_summary,
                "jobs": [],
                "aggregate": {"jobCount": 0, "metrics": {}},
            }
        payload = dump_response(_hadoop_service().job_diagnostics(JobDiagnosticsRequest(
            jobIds=job_ids,
            applicationIds=application_ids,
            includeTaskOutliers=True,
            outlierLimit=10,
        )))
        payload["execution"] = execution_summary
        return payload

    return await _run(diagnose)


@router.get("/tasks/{task_id}/lineage")
async def get_task_lineage(
    task_id: int,
    default_db: str | None = Query(default=None, alias="defaultDb", max_length=256),
    version_no: int | None = Query(default=None, alias="versionNo", ge=1),
) -> dict[str, Any]:
    def analyze() -> dict[str, Any]:
        try:
            return get_java_task_lineage(task_id, version_no, default_db)
        except ValueError as exc:
            raise McpDomainError(McpErrorCode.INVALID_REQUEST, str(exc)) from exc

    return await _run(analyze)


@router.get("/tasks/{task_id}/dependencies")
async def get_task_dependencies(
    task_id: int,
    default_db: str | None = Query(default=None, alias="defaultDb", max_length=256),
    version_no: int | None = Query(default=None, alias="versionNo", ge=1),
    limit: int = Query(default=500, ge=1, le=500),
) -> dict[str, Any]:
    def analyze() -> dict[str, Any]:
        try:
            return get_java_task_dependencies(task_id, version_no, default_db)
        except ValueError as exc:
            raise McpDomainError(McpErrorCode.INVALID_REQUEST, str(exc)) from exc

    return await _run(analyze)


@router.post("/tasks/{task_id}/quality")
async def check_task_quality(
    task_id: int,
    default_db: str | None = Query(default=None, alias="defaultDb", max_length=256),
    version_no: int | None = Query(default=None, alias="versionNo", ge=1),
) -> dict[str, Any]:
    def check() -> dict[str, Any]:
        task = _get_task_payload(_task_service(), task_id, version_no)
        steps = _task_rendered_steps(task)
        sql = ";\n".join(step.rendered_sql for step in steps)
        static_result = static_check_sql(sql, dialect="hive")
        issues = [_quality_issue(item) for item in static_result.issues]
        warnings: list[str] = []
        missing_reasons: list[str] = []

        try:
            lineage = get_java_task_lineage(task_id, version_no, default_db)
            missing_reasons.extend(lineage["missingReasons"])
            warnings.extend(lineage["warnings"])
            for reference in [*lineage["inputs"], *lineage["outputs"]]:
                status = reference.get("validationStatus")
                if status == "MISSING":
                    issues.append({
                        "level": "error",
                        "category": "metadata",
                        "code": "TABLE_NOT_FOUND",
                        "message": f"Hive Metastore 中不存在表 {reference['qualifiedName']}。",
                        "suggestion": "请确认数据库、表名和目标环境，或先完成上游对象发布。",
                        "object": reference["qualifiedName"],
                    })
                elif status in {"UNKNOWN", "UNRESOLVED"}:
                    issues.append({
                        "level": "warning",
                        "category": "metadata",
                        "code": "TABLE_VALIDATION_INCOMPLETE",
                        "message": f"无法确认表 {reference['qualifiedName']} 是否存在。",
                        "suggestion": "请检查默认库和 Hive Metastore 连通性后重新检查。",
                        "object": reference["qualifiedName"],
                    })
        except ValueError:
            lineage = {
                "defaultDb": default_db,
                "statementCount": 0,
                "inputs": [],
                "outputs": [],
                "ctes": [],
            }
            missing_reasons.append("lineage_parse_failed")

        compilation: dict[str, Any]
        try:
            compilation = _validate_rendered_steps(steps, default_db)
            if not compilation["valid"]:
                for error in compilation.get("errors", []):
                    issues.append({
                        "level": "error",
                        "category": "compilation",
                        "code": "HIVE_COMPILATION_ERROR",
                        "message": str(error.get("message") or "Hive 编译失败。"),
                        "suggestion": "请根据 HiveServer2 返回的编译错误修复 SQL。",
                    })
        except McpDomainError as exc:
            compilation = {
                "source": "hiveserver2",
                "valid": None,
                "available": False,
                "errors": [],
            }
            warnings.append(f"HiveServer2 编译校验不可用：{exc.message}")
            missing_reasons.append("hive_compilation_unavailable")

        level_counts = {
            level: sum(1 for item in issues if item["level"] == level)
            for level in ("error", "warning", "info")
        }
        missing_reasons = list(dict.fromkeys(missing_reasons))
        complete = not missing_reasons
        if level_counts["error"]:
            status = "FAILED"
        elif not complete:
            status = "INCOMPLETE"
        elif level_counts["warning"]:
            status = "PASSED_WITH_WARNINGS"
        else:
            status = "PASSED"

        references = [*lineage["inputs"], *lineage["outputs"]]
        metadata_counts = {
            "checked": len(references),
            "exists": sum(1 for item in references if item.get("validationStatus") == "EXISTS"),
            "missing": sum(1 for item in references if item.get("validationStatus") == "MISSING"),
            "unknown": sum(
                1 for item in references
                if item.get("validationStatus") in {"UNKNOWN", "UNRESOLVED"}
            ),
        }
        return {
            "ok": True,
            "source": "java-parse-sql+hive-metastore+hiveserver2",
            "fetchedAt": datetime.now(timezone.utc).isoformat(),
            "taskId": task_id,
            "taskName": task.get("name"),
            "status": status,
            "passed": status in {"PASSED", "PASSED_WITH_WARNINGS"},
            "complete": complete,
            "summary": level_counts,
            "checks": {
                "static": {"source": "sqlglot", "passed": static_result.passed},
                "metadata": {"source": "hive-metastore", **metadata_counts},
                "compilation": compilation,
            },
            "lineage": lineage,
            "issues": issues,
            "warnings": list(dict.fromkeys(warnings)),
            "missingReasons": missing_reasons,
        }

    return await _run(check)


def _quality_issue(issue: dict[str, str]) -> dict[str, str]:
    correctness_codes = {"EMPTY_SQL", "PARSE_ERROR", "DANGEROUS_DDL", "CARTESIAN_JOIN"}
    return {
        **issue,
        "category": "correctness" if issue["code"] in correctness_codes else "performance",
    }


@router.get("/hive/databases")
async def list_hive_databases() -> dict[str, Any]:
    return await _run(lambda: dump_response(
        _metadata_service().list_databases(ListDatabasesRequest(catalog="hive"))
    ))


@router.get("/hive/functions")
async def search_hive_functions(
    keyword: str = Query(default="", max_length=256),
    limit: int = Query(default=50, ge=1, le=200),
    offset: int = Query(default=0, ge=0),
    default_db: str | None = Query(default=None, alias="defaultDb", max_length=256),
) -> dict[str, Any]:
    return await _run(lambda: dump_response(_execution_service().search_functions(
        HiveFunctionSearchRequest(
            keyword=keyword,
            limit=limit,
            offset=offset,
            defaultDb=default_db,
        )
    )))


@router.get("/hive/functions/{name}")
async def get_hive_function(
    name: str,
    default_db: str | None = Query(default=None, alias="defaultDb", max_length=256),
) -> dict[str, Any]:
    return await _run(lambda: dump_response(_execution_service().get_function(
        HiveFunctionDetailRequest(name=name, defaultDb=default_db)
    )))


@router.get("/hive/tables")
async def search_hive_tables(
    pattern: str = Query(default="", max_length=256),
    db: str | None = Query(default=None, max_length=256),
    limit: int = Query(default=100, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
) -> dict[str, Any]:
    return await _run(lambda: dump_response(_metadata_service().search_tables(
        SearchTablesRequest(
            catalog="hive", pattern=pattern, db=db, limit=limit, offset=offset,
            includeColumns=False, includePartitions=False,
        )
    )))


@router.get("/hive/tables/{db}/{table}/columns")
async def get_hive_columns(db: str, table: str) -> dict[str, Any]:
    return await _run(lambda: dump_response(_metadata_service().get_columns(
        GetColumnsRequest(catalog="hive", db=db, table=table)
    )))


@router.get("/hive/tables/{db}/{table}")
async def get_hive_table(db: str, table: str) -> dict[str, Any]:
    return await _run(lambda: dump_response(_metadata_service().get_table(
        GetTableRequest(catalog="hive", db=db, table=table, includeColumns=True)
    )))


@router.get("/hive/tables/{db}/{table}/partitions")
async def get_hive_partitions(
    db: str,
    table: str,
    limit: int = Query(default=20, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
) -> dict[str, Any]:
    return await _run(lambda: dump_response(_metadata_service().get_partitions(
        GetPartitionsRequest(catalog="hive", db=db, table=table, limit=limit, offset=offset)
    )))


@router.get("/hive/tables/{db}/{table}/ddl")
async def get_hive_table_ddl(db: str, table: str) -> dict[str, Any]:
    return await _run(lambda: dump_response(_metadata_service().get_table_ddl(
        GetTableDdlRequest(catalog="hive", db=db, table=table)
    )))


@router.get("/hive/tables/{db}/{table}/statistics")
async def get_hive_table_statistics(
    db: str,
    table: str,
    columns: list[str] = Query(default=[]),
) -> dict[str, Any]:
    return await _run(lambda: dump_response(_metadata_service().get_table_statistics(
        GetTableStatisticsRequest(catalog="hive", db=db, table=table, columns=columns)
    )))


@router.get("/hive/tables/{db}/{table}/storage-layout")
async def get_hive_storage_layout(
    db: str,
    table: str,
    max_files: int = Query(default=1000, alias="maxFiles", ge=1, le=5000),
) -> dict[str, Any]:
    return await _run(lambda: dump_response(_hadoop_service().storage_layout(
        StorageLayoutRequest(catalog="hive", db=db, table=table, maxFiles=max_files)
    )))


@router.get("/hive/tables/{db}/{table}/freshness")
async def get_hive_table_freshness(
    db: str,
    table: str,
    partition_scan_limit: int = Query(default=2000, alias="partitionScanLimit", ge=1, le=5000),
    path_sample_limit: int = Query(default=20, alias="pathSampleLimit", ge=1, le=50),
) -> dict[str, Any]:
    return await _run(lambda: dump_response(_hadoop_service().table_freshness(
        TableFreshnessRequest(
            catalog="hive",
            db=db,
            table=table,
            partitionScanLimit=partition_scan_limit,
            pathSampleLimit=path_sample_limit,
        )
    )))


@router.post("/tasks/{task_id}/validate")
async def validate_task_sql(
    task_id: int,
    default_db: str | None = Query(default=None, alias="defaultDb", max_length=256),
    version_no: int | None = Query(default=None, alias="versionNo", ge=1),
) -> dict[str, Any]:
    def validate() -> dict[str, Any]:
        task = _get_task_payload(_task_service(), task_id, version_no)
        return _validate_rendered_steps(_task_rendered_steps(task), default_db)

    return await _run(validate)


@router.post("/tasks/{task_id}/explain")
async def explain_task_sql(
    task_id: int,
    default_db: str | None = Query(default=None, alias="defaultDb", max_length=256),
    version_no: int | None = Query(default=None, alias="versionNo", ge=1),
    extended: bool = Query(default=False),
) -> dict[str, Any]:
    def explain() -> dict[str, Any]:
        task = _get_task_payload(_task_service(), task_id, version_no)
        steps = _task_rendered_steps(task)
        results = [
            dump_response(_execution_service().explain(HiveExplainRequest(
                sql=step.rendered_sql, defaultDb=default_db, extended=extended,
            )))
            for step in steps
        ]
        step_plans = [
            {
                "stepNo": step.number,
                "stepName": step.name,
                "planText": result.get("planText") or "",
                "risks": _explain_risks(str(result.get("planText") or "")),
            }
            for step, result in zip(steps, results)
        ]
        all_risks = [
            {**risk, "stepNo": step_plan["stepNo"], "stepName": step_plan["stepName"]}
            for step_plan in step_plans for risk in step_plan["risks"]
        ]
        if len(results) == 1:
            return {**results[0], "risks": all_risks, "steps": step_plans}
        warnings = [warning for result in results for warning in result.get("warnings", [])]
        missing_reasons = [
            reason for result in results for reason in result.get("missingReasons", [])
        ]
        return {
            "ok": True,
            "source": "hiveserver2",
            "fetchedAt": datetime.now(timezone.utc).isoformat(),
            "planSource": "predicted",
            "planText": "\n\n".join(
                f"-- Step {step.number}: {step.name} --\n{result['planText']}"
                for step, result in zip(steps, results)
            ),
            "defaultDb": results[0].get("defaultDb") or default_db or "default",
            "compilationMs": sum(int(result.get("compilationMs") or 0) for result in results),
            "truncated": any(bool(result.get("truncated")) for result in results),
            "risks": all_risks,
            "steps": step_plans,
            "warnings": warnings,
            "complete": all(bool(result.get("complete", True)) for result in results),
            "missingReasons": list(dict.fromkeys(missing_reasons)),
        }

    return await _run(explain)
