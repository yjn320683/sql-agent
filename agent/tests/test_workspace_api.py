from __future__ import annotations

import asyncio
from types import SimpleNamespace

from app.api import workspace
from sql_agent_mcp_server.domains.hive_execution.schemas import (
    HiveExplainResponse,
    HiveFunctionDetailResponse,
    HiveFunctionSearchResponse,
    HiveValidationResponse,
)
from sql_agent_mcp_server.domains.hive_metadata.schemas import (
    ColumnMetadata,
    ColumnsResponse,
    DatabaseListResponse,
    DdlResponse,
    PartitionsResponse,
    SearchTablesResponse,
    TableMetadata,
    TableResponse,
    TableStatisticsResponse,
)
from sql_agent_mcp_server.domains.hadoop_runtime.schemas import (
    DependencyHealthResponse,
    JobDiagnosticsResponse,
    StorageLayoutResponse,
)


def test_workspace_metadata_uses_real_service_contract(monkeypatch) -> None:
    captured = {}

    class MetadataService:
        def list_databases(self, request):
            return DatabaseListResponse(source="hive-metastore", databases=["default", "dw"])

        def search_tables(self, request):
            captured["search"] = request
            return SearchTablesResponse(
                source="hive-metastore",
                items=[TableMetadata(db="dw", table="orders")],
                total=1, limit=request.limit, offset=request.offset,
            )

        def get_columns(self, request):
            captured["columns"] = request
            return ColumnsResponse(
                source="hive-metastore",
                columns=[ColumnMetadata(name="order_id", dataType="bigint")],
            )

        def get_table(self, request):
            captured["table"] = request
            return TableResponse(
                source="hive-metastore",
                table=TableMetadata(
                    db="dw", table="orders",
                    columns=[ColumnMetadata(name="order_id", dataType="bigint")],
                ),
            )

        def get_partitions(self, request):
            captured["partitions"] = request
            return PartitionsResponse(
                source="hive-metastore", partitions=[], total=0,
                limit=request.limit, offset=request.offset,
            )

        def get_table_ddl(self, request):
            return DdlResponse(source="hive-metastore", ddl="CREATE TABLE dw.orders (order_id BIGINT)")

        def get_table_statistics(self, request):
            captured["statistics"] = request
            return TableStatisticsResponse(
                source="hive-metastore", tableStatistics={"numRows": 10},
            )

    monkeypatch.setattr(workspace, "_metadata_service", lambda: MetadataService())

    databases = asyncio.run(workspace.list_hive_databases())
    tables = asyncio.run(workspace.search_hive_tables(pattern="ord", db="dw", limit=20, offset=0))
    columns = asyncio.run(workspace.get_hive_columns("dw", "orders"))
    table = asyncio.run(workspace.get_hive_table("dw", "orders"))
    partitions = asyncio.run(workspace.get_hive_partitions("dw", "orders", limit=20, offset=0))
    ddl = asyncio.run(workspace.get_hive_table_ddl("dw", "orders"))
    statistics = asyncio.run(workspace.get_hive_table_statistics("dw", "orders", columns=["order_id"]))

    assert databases["databases"] == ["default", "dw"]
    assert tables["items"][0]["table"] == "orders"
    assert columns["columns"][0]["name"] == "order_id"
    assert table["table"]["columns"][0]["name"] == "order_id"
    assert partitions["total"] == 0
    assert ddl["ddl"].startswith("CREATE TABLE")
    assert statistics["tableStatistics"]["numRows"] == 10
    assert captured["search"].db == "dw"
    assert captured["columns"].table == "orders"
    assert captured["table"].include_columns is True
    assert captured["statistics"].columns == ["order_id"]


def test_workspace_storage_layout_uses_real_hadoop_service_contract(monkeypatch) -> None:
    captured = {}

    class HadoopService:
        def storage_layout(self, request):
            captured["request"] = request
            return StorageLayoutResponse(
                source="webhdfs",
                table={"db": "dw", "table": "orders"},
                paths=[{"path": "/warehouse/dw.db/orders", "fileCount": 2}],
                summary={"lengthBytes": 2048, "fileCount": 2},
                fileSizeDistribution={"count": 2, "p50": 1024},
            )

    monkeypatch.setattr(workspace, "_hadoop_service", lambda: HadoopService())

    result = asyncio.run(workspace.get_hive_storage_layout("dw", "orders", max_files=500))

    assert result["summary"]["lengthBytes"] == 2048
    assert captured["request"].max_files == 500


def test_workspace_platform_health_uses_real_dependency_checks(monkeypatch) -> None:
    class HadoopService:
        def dependency_health(self):
            return DependencyHealthResponse(
                source="sql-agent-platform",
                dependencies=[{
                    "name": "hiveServer2", "configured": True,
                    "reachable": True, "latencyMs": 18, "details": {},
                }],
            )

    monkeypatch.setattr(workspace, "_hadoop_service", lambda: HadoopService())

    result = asyncio.run(workspace.get_platform_health())

    assert result["complete"] is True
    assert result["dependencies"][0]["reachable"] is True


def test_workspace_sql_structure_uses_execution_parser() -> None:
    request = workspace.SqlStructureRequest(
        sql="====step:0:read====\nselect ${limit} as n, '${yyyy-MM-dd,-1,day}' as dt",
        parameterSchema=[{"name": "limit", "type": "INTEGER", "required": True}],
        parameters={"limit": 5},
        businessDate="2026-08-25",
        validateParameterValues=True,
    )

    result = asyncio.run(workspace.preview_sql_structure(request))

    assert result["valid"] is True
    assert result["stepCount"] == 1
    assert result["steps"][0]["stepName"] == "read"
    assert "5 as n" in result["renderedSql"]
    assert "2026-08-24" in result["renderedSql"]


def test_workspace_execution_diagnostics_reports_missing_runtime_ids(monkeypatch) -> None:
    task_service = SimpleNamespace(get_execution=lambda execution_id: {
        "execution": {
            "id": execution_id,
            "task_id": 3,
            "status": "FAILED",
            "query_id": None,
            "application_ids": [],
            "job_ids": [],
        }
    })
    monkeypatch.setattr(workspace, "_task_service", lambda: task_service)

    result = asyncio.run(workspace.get_task_execution_diagnostics(12))

    assert result["complete"] is False
    assert result["execution"]["id"] == 12
    assert result["missingReasons"] == ["runtime_identifiers_missing"]
    assert result["jobs"] == []


def test_workspace_execution_diagnostics_uses_saved_job_ids(monkeypatch) -> None:
    task_service = SimpleNamespace(get_execution=lambda execution_id: {
        "execution": {
            "id": execution_id,
            "task_id": 3,
            "status": "SUCCEEDED",
            "query_id": "query_1",
            "application_ids": ["application_100_0001"],
            "job_ids": ["job_100_0001"],
        }
    })
    captured = {}

    class HadoopService:
        def job_diagnostics(self, request):
            captured["request"] = request
            return JobDiagnosticsResponse(
                source="mapreduce-jobhistory",
                jobs=[{"job": {"id": "job_100_0001", "state": "SUCCEEDED"}}],
                aggregate={"jobCount": 1, "metrics": {"durationMs": 1200}},
            )

    monkeypatch.setattr(workspace, "_task_service", lambda: task_service)
    monkeypatch.setattr(workspace, "_hadoop_service", lambda: HadoopService())

    result = asyncio.run(workspace.get_task_execution_diagnostics(13))

    assert result["aggregate"]["jobCount"] == 1
    assert result["execution"]["queryId"] == "query_1"
    assert captured["request"].job_ids == ["job_100_0001"]
    assert captured["request"].application_ids == ["application_100_0001"]


def test_workspace_function_catalog_uses_hiveserver2_contract(monkeypatch) -> None:
    captured = []

    class ExecutionService:
        def search_functions(self, request):
            captured.append((request.keyword, request.limit, request.offset, request.default_db))
            return HiveFunctionSearchResponse(
                source="hive-server2", items=["date_add", "date_sub"], total=2,
                limit=request.limit, offset=request.offset, keyword=request.keyword,
                defaultDb=request.default_db or "default", elapsedMs=11,
            )

        def get_function(self, request):
            captured.append((request.name, request.default_db))
            return HiveFunctionDetailResponse(
                source="hive-server2", name=request.name,
                lines=["Function: date_add", "Usage: date_add(startdate, days)"],
                properties={"function": "date_add", "usage": "date_add(startdate, days)"},
                defaultDb=request.default_db or "default", elapsedMs=7,
            )

    monkeypatch.setattr(workspace, "_execution_service", lambda: ExecutionService())

    search = asyncio.run(workspace.search_hive_functions(
        keyword="date", limit=20, offset=0, default_db="dw",
    ))
    detail = asyncio.run(workspace.get_hive_function("date_add", default_db="dw"))

    assert search["items"] == ["date_add", "date_sub"]
    assert detail["properties"]["usage"] == "date_add(startdate, days)"
    assert captured == [("date", 20, 0, "dw"), ("date_add", "dw")]


def test_workspace_task_lineage_reads_saved_sql_and_validates_metastore(monkeypatch) -> None:
    task_service = SimpleNamespace(get_task=lambda task_id: {
        "task": {
            "id": task_id,
            "name": "订单汇总",
            "sql": "insert overwrite table dw.summary select * from ods.orders",
        }
    })

    class MetadataService:
        def get_table(self, request):
            if request.table == "orders":
                return TableResponse(
                    source="hive-metastore",
                    table=TableMetadata(db="ods", table="orders", tableType="EXTERNAL_TABLE"),
                )
            return TableResponse(
                source="hive-metastore",
                table=TableMetadata(db="dw", table="summary", tableType="MANAGED_TABLE"),
            )

    monkeypatch.setattr(workspace, "_task_service", lambda: task_service)
    monkeypatch.setattr(workspace, "_metadata_service", lambda: MetadataService())

    result = asyncio.run(workspace.get_task_lineage(9, default_db="default"))

    assert result["taskId"] == 9
    assert result["inputs"][0]["qualifiedName"] == "ods.orders"
    assert result["inputs"][0]["validationStatus"] == "EXISTS"
    assert result["outputs"][0]["qualifiedName"] == "dw.summary"
    assert result["source"] == "sqlglot+hive-metastore"
    assert result["complete"] is True


def test_workspace_task_quality_combines_real_service_contracts(monkeypatch) -> None:
    task_service = SimpleNamespace(get_task=lambda task_id: {
        "task": {
            "id": task_id,
            "name": "订单质量检查",
            "sql": "insert overwrite table dw.summary select * from ods.orders",
        }
    })

    class MetadataService:
        def get_table(self, request):
            table_type = "EXTERNAL_TABLE" if request.table == "orders" else "MANAGED_TABLE"
            return TableResponse(
                source="hive-metastore",
                table=TableMetadata(
                    db=request.db,
                    table=request.table,
                    tableType=table_type,
                    columns=[ColumnMetadata(
                        name="dt", dataType="string", partitionKey=request.table == "orders",
                    )],
                ),
            )

    class ExecutionService:
        def validate(self, request):
            assert request.sql.startswith("insert overwrite table")
            return HiveValidationResponse(
                source="hiveserver2", valid=True, defaultDb="default", compilationMs=23,
            )

    monkeypatch.setattr(workspace, "_task_service", lambda: task_service)
    monkeypatch.setattr(workspace, "_metadata_service", lambda: MetadataService())
    monkeypatch.setattr(workspace, "_execution_service", lambda: ExecutionService())

    result = asyncio.run(workspace.check_task_quality(11, default_db="default"))

    assert result["taskId"] == 11
    assert result["source"] == "sqlglot+hive-metastore+hiveserver2"
    assert result["status"] == "PASSED_WITH_WARNINGS"
    assert result["checks"]["metadata"] == {
        "source": "hive-metastore", "checked": 2, "exists": 2, "missing": 0, "unknown": 0,
    }
    assert result["checks"]["compilation"]["valid"] is True
    assert result["lineage"]["inputs"][0]["partitionKeys"] == ["dt"]
    assert any(item["code"] == "SELECT_STAR" for item in result["issues"])


def test_workspace_task_dependencies_scan_saved_tasks(monkeypatch) -> None:
    tasks = [
        {"id": 1, "name": "上游", "sql": "insert overwrite table dwd.orders select * from ods.orders"},
        {"id": 2, "name": "目标", "sql": "insert overwrite table dw.summary select * from dwd.orders"},
        {"id": 3, "name": "下游", "sql": "insert overwrite table ads.report select * from dw.summary"},
    ]
    task_service = SimpleNamespace(
        get_task=lambda task_id: {"task": next(item for item in tasks if item["id"] == task_id)},
        list_tasks=lambda limit, offset: {
            "items": tasks[:limit], "total": len(tasks), "limit": limit, "offset": offset,
        },
    )
    monkeypatch.setattr(workspace, "_task_service", lambda: task_service)

    result = asyncio.run(workspace.get_task_dependencies(2, default_db="default", limit=500))

    assert result["source"] == "sql-agent-db+sqlglot"
    assert result["complete"] is True
    assert result["directUpstream"][0]["taskId"] == 1
    assert result["directDownstream"][0]["taskId"] == 3
    assert result["scannedTaskCount"] == 3


def test_workspace_validate_and_explain_read_exact_saved_task_sql(monkeypatch) -> None:
    captured = []
    task_service = SimpleNamespace(get_task=lambda task_id: {
        "task": {
            "id": task_id,
            "sql": "====step:1:read-orders====\nselect ${limit} as n from dw.orders",
            "parameters": [{
                "name": "limit", "type": "INTEGER", "required": True, "defaultValue": 20,
            }],
        }
    })

    class ExecutionService:
        def validate(self, request):
            captured.append(request.sql)
            return HiveValidationResponse(
                source="hiveserver2", valid=True, defaultDb="dw", compilationMs=12,
            )

        def explain(self, request):
            captured.append(request.sql)
            return HiveExplainResponse(
                source="hiveserver2", planText="Stage-1\n  Cartesian Product", defaultDb="dw", compilationMs=15,
            )

    monkeypatch.setattr(workspace, "_task_service", lambda: task_service)
    monkeypatch.setattr(workspace, "_execution_service", lambda: ExecutionService())

    validation = asyncio.run(workspace.validate_task_sql(7, default_db="dw"))
    explain = asyncio.run(workspace.explain_task_sql(7, default_db="dw", extended=False))

    assert validation["valid"] is True
    assert validation["steps"] == [{
        "stepNo": 1, "stepName": "read-orders", "valid": True, "errors": [], "warnings": [],
    }]
    assert explain["planText"] == "Stage-1\n  Cartesian Product"
    assert explain["risks"][0]["code"] == "CARTESIAN_JOIN"
    assert explain["risks"][0]["stepNo"] == 1
    assert explain["risks"][0]["evidence"] == "Cartesian Product"
    assert captured == [
        "select 20 as n from dw.orders",
        "select 20 as n from dw.orders",
    ]


def test_workspace_quality_compiles_each_rendered_step(monkeypatch) -> None:
    captured: list[str] = []
    task_service = SimpleNamespace(get_task=lambda task_id: {
        "task": {
            "id": task_id,
            "name": "多 Step 检查",
            "sql": "====step:1:first====\nselect 1\n====step:2:second====\nselect 2",
            "parameters": [],
        }
    })

    class ExecutionService:
        def validate(self, request):
            captured.append(request.sql)
            return HiveValidationResponse(
                source="hiveserver2", valid=True, defaultDb="default", compilationMs=3,
            )

    monkeypatch.setattr(workspace, "_task_service", lambda: task_service)
    monkeypatch.setattr(workspace, "_execution_service", lambda: ExecutionService())

    result = asyncio.run(workspace.check_task_quality(12, default_db="default"))

    assert captured == ["select 1", "select 2"]
    assert result["checks"]["compilation"]["valid"] is True
    assert result["checks"]["compilation"]["compilationMs"] == 6


def test_workspace_validate_reads_requested_version_draft_sql(monkeypatch) -> None:
    requested: list[tuple[int, int | None]] = []

    def get_task(task_id: int, version_no: int | None = None) -> dict:
        requested.append((task_id, version_no))
        return {"task": {"id": task_id, "sql": f"select {version_no} as version_no"}}

    class ExecutionService:
        def validate(self, request):
            assert request.sql == "select 4 as version_no"
            return HiveValidationResponse(
                source="hiveserver2", valid=True, defaultDb="dw", compilationMs=9,
            )

    monkeypatch.setattr(workspace, "_task_service", lambda: SimpleNamespace(get_task=get_task))
    monkeypatch.setattr(workspace, "_execution_service", lambda: ExecutionService())

    result = asyncio.run(workspace.validate_task_sql(7, default_db="dw", version_no=4))

    assert result["valid"] is True
    assert requested == [(7, 4)]
