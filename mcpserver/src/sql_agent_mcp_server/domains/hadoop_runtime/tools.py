"""Hive on MapReduce 运行诊断 MCP 工具。"""

from __future__ import annotations

from typing import Any

from mcp.server.fastmcp import FastMCP

from sql_agent_mcp_server.common.logging import run_tool_safely
from sql_agent_mcp_server.common.schemas import dump_response
from sql_agent_mcp_server.domains.hadoop_runtime.schemas import (
    AggregatedLogsRequest,
    JobCompareRequest,
    JobDiagnosticsRequest,
    JobSearchRequest,
    StorageLayoutRequest,
    TableFreshnessRequest,
    YarnDiagnosticsRequest,
)
from sql_agent_mcp_server.domains.hadoop_runtime.service import HadoopRuntimeService


def register_hadoop_runtime_tools(mcp: FastMCP, service: HadoopRuntimeService) -> None:
    @mcp.tool()
    def platform_dependency_health_get() -> dict[str, Any]:
        """Check configured SQL platform dependencies without returning endpoints or credentials."""

        return run_tool_safely(
            "platform_dependency_health_get",
            lambda: dump_response(service.dependency_health()),
        )

    @mcp.tool()
    def hive_storage_layout_get(
        catalog: str = "hive",
        db: str = "",
        table: str = "",
        partitions: list[str] | None = None,
        maxFiles: int = 1000,
    ) -> dict[str, Any]:
        """Inspect bounded HDFS file layout for a Hive table or selected partitions."""

        return run_tool_safely(
            "hive_storage_layout_get",
            lambda: dump_response(service.storage_layout(StorageLayoutRequest(
                catalog=catalog, db=db, table=table, partitions=partitions or [], maxFiles=maxFiles,
            ))),
            {"catalog": catalog, "db": db, "table": table,
             "partitionCount": len(partitions or []), "maxFiles": maxFiles},
        )

    @mcp.tool()
    def hive_table_freshness_get(
        catalog: str = "hive",
        db: str = "",
        table: str = "",
        partitionScanLimit: int = 2000,
        pathSampleLimit: int = 20,
    ) -> dict[str, Any]:
        """Read bounded Metastore partition facts and WebHDFS path timestamps without inferring SLA status."""

        return run_tool_safely(
            "hive_table_freshness_get",
            lambda: dump_response(service.table_freshness(TableFreshnessRequest(
                catalog=catalog,
                db=db,
                table=table,
                partitionScanLimit=partitionScanLimit,
                pathSampleLimit=pathSampleLimit,
            ))),
            {"catalog": catalog, "db": db, "table": table,
             "partitionScanLimit": partitionScanLimit, "pathSampleLimit": pathSampleLimit},
        )

    @mcp.tool()
    def yarn_application_diagnostics_get(
        applicationIds: list[str] | None = None,
        includeAttempts: bool = True,
        includeContainers: bool = True,
    ) -> dict[str, Any]:
        """Get read-only YARN Application, attempt, container, resource, and failure facts."""

        return run_tool_safely(
            "yarn_application_diagnostics_get",
            lambda: dump_response(service.yarn_diagnostics(YarnDiagnosticsRequest(
                applicationIds=applicationIds or [], includeAttempts=includeAttempts,
                includeContainers=includeContainers,
            ))),
            {"applicationCount": len(applicationIds or []),
             "includeAttempts": includeAttempts, "includeContainers": includeContainers},
        )

    @mcp.tool()
    def mapreduce_aggregated_logs_get(
        jobIds: list[str] | None = None,
        logTypes: list[str] | None = None,
        tailLines: int = 200,
        maxCharsPerLog: int = 30_000,
    ) -> dict[str, Any]:
        """Read bounded and redacted AM container logs from the configured JobHistory server."""

        resolved_log_types = logTypes or ["stderr", "syslog"]
        return run_tool_safely(
            "mapreduce_aggregated_logs_get",
            lambda: dump_response(service.aggregated_logs(AggregatedLogsRequest(
                jobIds=jobIds or [], logTypes=resolved_log_types,
                tailLines=tailLines, maxCharsPerLog=maxCharsPerLog,
            ))),
            {"jobCount": len(jobIds or []), "logTypes": resolved_log_types,
             "tailLines": tailLines, "maxCharsPerLog": maxCharsPerLog},
        )

    @mcp.tool()
    def mapreduce_job_search(
        jobId: str | None = None,
        applicationId: str | None = None,
        queryId: str | None = None,
        user: str | None = None,
        queue: str | None = None,
        name: str | None = None,
        state: str | None = None,
        startedAfter: int | None = None,
        startedBefore: int | None = None,
        finishedAfter: int | None = None,
        finishedBefore: int | None = None,
        limit: int = 20,
    ) -> dict[str, Any]:
        """Locate completed MapReduce jobs by exact ID or bounded JobHistory filters."""

        params = locals().copy()
        return run_tool_safely(
            "mapreduce_job_search",
            lambda: dump_response(service.search_jobs(JobSearchRequest(**params))),
            {key: value for key, value in params.items() if key not in {"queryId"}},
        )

    @mcp.tool()
    def mapreduce_job_diagnostics_get(
        jobIds: list[str] | None = None,
        applicationIds: list[str] | None = None,
        includeTaskOutliers: bool = True,
        outlierLimit: int = 10,
    ) -> dict[str, Any]:
        """Aggregate completed MapReduce jobs, counters, task distributions, and abnormal attempts."""

        return run_tool_safely(
            "mapreduce_job_diagnostics_get",
            lambda: dump_response(service.job_diagnostics(JobDiagnosticsRequest(
                jobIds=jobIds or [], applicationIds=applicationIds or [],
                includeTaskOutliers=includeTaskOutliers, outlierLimit=outlierLimit,
            ))),
            {"jobCount": len(jobIds or []), "applicationCount": len(applicationIds or []),
             "includeTaskOutliers": includeTaskOutliers, "outlierLimit": outlierLimit},
        )

    @mcp.tool()
    def mapreduce_job_compare(
        baselineJobIds: list[str] | None = None,
        candidateJobIds: list[str] | None = None,
    ) -> dict[str, Any]:
        """Compare two bounded MapReduce job sets after checking whether their execution contexts are comparable."""

        return run_tool_safely(
            "mapreduce_job_compare",
            lambda: dump_response(service.compare_jobs(JobCompareRequest(
                baselineJobIds=baselineJobIds or [], candidateJobIds=candidateJobIds or [],
            ))),
            {"baselineJobCount": len(baselineJobIds or []), "candidateJobCount": len(candidateJobIds or [])},
        )
