from __future__ import annotations

import pytest
from pydantic import ValidationError

from sql_agent_mcp_server.common.errors import McpDomainError, McpErrorCode
from sql_agent_mcp_server.domains.hadoop_runtime.adapters import (
    JobHistoryAdapter,
    WebHdfsAdapter,
    application_to_job_id,
    job_to_application_id,
)
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
from sql_agent_mcp_server.domains.hive_metadata.schemas import ColumnMetadata, PartitionMetadata, TableMetadata


class FakeWebHdfs:
    source = "fake-webhdfs"

    def health(self):
        return {"capacity": 100}

    def inspect_paths(self, locations, *, max_files):
        return ([{"path": "/warehouse/orders", "length": 300, "spaceConsumed": 900,
                  "fileCount": 3, "directoryCount": 1}], [10, 20], False, ["file_distribution_sampled"])

    def get_path_status(self, location):
        return {"path": location, "type": "DIRECTORY", "modificationTime": 1_700_000_000_000}


class FakeYarn:
    source = "fake-yarn"

    def health(self):
        return {"state": "STARTED"}

    def get_application(self, application_id, *, include_attempts, include_containers):
        if application_id.endswith("9999"):
            raise McpDomainError(McpErrorCode.NOT_FOUND, "expired")
        return {"id": application_id, "queue": "root.default", "memorySeconds": 100}


class FakeJobHistory:
    source = "fake-job-history"

    def health(self):
        return {"hadoopVersion": "3.3.5"}

    def get_job(self, job_id):
        if job_id.endswith("8888"):
            raise McpDomainError(McpErrorCode.NOT_FOUND, "expired")
        number = int(job_id.rsplit("_", 1)[1])
        return {"id": job_id, "name": f"Hive query {number}", "queue": "root.default", "user": "alice",
                "state": "SUCCEEDED", "submitTime": 1000, "startTime": 1100,
                "finishTime": 1100 + number * 100, "mapsTotal": 2, "reducesTotal": 1}

    def get_job_attempts(self, job_id):
        return [{"id": 1, "containerId": "container_1", "startTime": 1000,
                 "logsLink": "http://jhs:19888/jobhistory/logs/node/container_1/job_1/alice"}]

    def get_aggregated_log(self, logs_link, *, log_type, max_chars):
        return ("INFO start\nERROR password=<redacted> failed\nCaused by: disk full", False)

    def search_jobs(self, params):
        return [self.get_job("job_100_0001"), self.get_job("job_100_0002")]

    def get_counters(self, job_id):
        return {
            "org.apache.hadoop.mapreduce.FileSystemCounter.HDFS_BYTES_READ": 100,
            "org.apache.hadoop.mapreduce.TaskCounter.REDUCE_SHUFFLE_BYTES": 50,
            "secret.group.UNSAFE_COUNTER": 999,
        }

    def get_tasks(self, job_id):
        return [
            {"id": f"task_{job_id}_m_0", "type": "MAP", "state": "SUCCEEDED", "elapsedTime": 10},
            {"id": f"task_{job_id}_m_1", "type": "MAP", "state": "SUCCEEDED", "elapsedTime": 30},
            {"id": f"task_{job_id}_r_0", "type": "REDUCE", "state": "FAILED", "elapsedTime": 80},
        ]

    def get_task_attempts(self, job_id, task_id):
        return [{"id": "attempt_1", "state": "FAILED", "elapsedTime": 80,
                 "diagnostics": "Container exited", "assignedContainerId": "container_1"}]

    def get_safe_configuration(self, job_id):
        return {"hive.query.id": "query_1", "hive.execution.engine": "mr",
                "fingerprints": {"mapred.input.dir": "same-input"}}


class FakeMetadataAdapter:
    source = "fake-metastore"

    def list_databases(self, *, catalog):
        return ["default"]

    def get_storage_locations(self, *, catalog, db, table, partitions):
        return TableMetadata(db=db, table=table, location="hdfs://ns/warehouse/orders",
                             inputFormat="orc", outputFormat="orc"), ["hdfs://ns/warehouse/orders"]

    def get_table(self, *, catalog, db, table):
        return TableMetadata(
            db=db,
            table=table,
            location="hdfs://ns/warehouse/orders",
            columns=[],
            tblProperties={"transient_lastDdlTime": "1699999999"},
        )


class FakeMetadata:
    adapter = FakeMetadataAdapter()


class FakeHiveExecutionAdapter:
    def health(self):
        return 5


class FakeHiveExecution:
    adapter = FakeHiveExecutionAdapter()


def service() -> HadoopRuntimeService:
    return HadoopRuntimeService(FakeWebHdfs(), FakeYarn(), FakeJobHistory(), FakeMetadata(), FakeHiveExecution())


def test_webhdfs_health_uses_get_file_status() -> None:
    class RecordingClient:
        def get_json(self, path, *, params):
            assert path == "/"
            assert params == {"op": "GETFILESTATUS"}
            return {"FileStatus": {"type": "DIRECTORY"}}

    adapter = WebHdfsAdapter(("http://namenode:9870",), timeout_seconds=10, http_user=None)
    adapter.client = RecordingClient()

    assert adapter.health() == {"type": "DIRECTORY"}


def test_mapreduce_ids_convert_without_guessing_other_engines() -> None:
    assert application_to_job_id("application_123_0007") == "job_123_0007"
    assert job_to_application_id("job_123_0007") == "application_123_0007"
    with pytest.raises(McpDomainError):
        application_to_job_id("app-123")


def test_query_id_search_requires_bounded_time_range() -> None:
    with pytest.raises(ValidationError):
        JobSearchRequest(queryId="query_1")


def test_job_diagnostics_aggregates_multiple_jobs_and_whitelists_counters() -> None:
    response = service().job_diagnostics(JobDiagnosticsRequest(
        jobIds=["job_100_0001", "job_100_0002"], outlierLimit=2
    ))

    assert response.aggregate["jobCount"] == 2
    assert response.aggregate["metrics"]["HDFS_BYTES_READ"] == 200
    assert "UNSAFE_COUNTER" not in response.aggregate["metrics"]
    assert response.jobs[0]["tasks"]["map"]["durationMs"] == {
        "count": 2, "min": 10, "p50": 20, "p95": 29, "max": 30
    }
    assert response.jobs[0]["taskOutliers"][0]["task"]["state"] == "FAILED"


def test_job_diagnostics_keeps_jobhistory_when_yarn_history_expired() -> None:
    response = service().job_diagnostics(JobDiagnosticsRequest(jobIds=["job_100_9999"]))

    assert response.complete is False
    assert response.jobs[0]["job"]["id"] == "job_100_9999"
    assert "yarn_application_not_retained_or_unavailable" in response.missing_reasons


def test_job_diagnostics_keeps_identifiers_when_jobhistory_detail_expired() -> None:
    response = service().job_diagnostics(JobDiagnosticsRequest(jobIds=["job_100_8888"]))

    assert response.complete is False
    assert response.jobs[0]["job"] == {
        "id": "job_100_8888", "applicationId": "application_100_8888",
    }
    assert response.jobs[0]["yarnApplication"]["id"] == "application_100_8888"
    assert response.aggregate["expiredJobCount"] == 1
    assert "job_history_not_retained_or_unavailable" in response.missing_reasons


def test_job_compare_checks_queue_user_and_input_context() -> None:
    response = service().compare_jobs(JobCompareRequest(
        baselineJobIds=["job_100_0001"], candidateJobIds=["job_100_0002"]
    ))

    assert response.comparable is True
    assert response.differences["durationMs"]["absolute"] == 100


def test_storage_layout_marks_bounded_file_distribution() -> None:
    response = service().storage_layout(StorageLayoutRequest(db="dwd", table="orders", maxFiles=2))

    assert response.summary["fileCount"] == 3
    assert response.summary["sampledFileCount"] == 2
    assert response.complete is False
    assert response.file_size_distribution["p50"] == 15


def test_table_freshness_reports_storage_facts_without_sla_claim() -> None:
    response = service().table_freshness(TableFreshnessRequest(db="dwd", table="orders"))

    assert response.table["partitioned"] is False
    assert response.latest_storage_modification_time == 1_700_000_000_000
    assert response.storage_paths[0]["path"] == "hdfs://ns/warehouse/orders"
    assert response.complete is True
    assert any("不等同于业务数据时间" in warning for warning in response.warnings)


def test_partitioned_freshness_is_bounded_and_deduplicates_missing_paths() -> None:
    class PartitionedMetadataAdapter(FakeMetadataAdapter):
        def get_table(self, *, catalog, db, table):
            return TableMetadata(
                db=db,
                table=table,
                columns=[ColumnMetadata(name="dt", dataType="string", partitionKey=True)],
            )

        def get_partitions(self, *, catalog, db, table, limit, offset):
            assert limit == 2
            assert offset == 0
            return [
                PartitionMetadata(name="dt=2026-08-23", values={"dt": "2026-08-23"}, location="hdfs://ns/a"),
                PartitionMetadata(name="dt=2026-08-24", values={"dt": "2026-08-24"}, location="hdfs://ns/b"),
            ], 10

    class MissingWebHdfs(FakeWebHdfs):
        def get_path_status(self, location):
            raise McpDomainError(McpErrorCode.NOT_FOUND, "missing")

    metadata = FakeMetadata()
    metadata.adapter = PartitionedMetadataAdapter()
    runtime = HadoopRuntimeService(MissingWebHdfs(), FakeYarn(), FakeJobHistory(), metadata, FakeHiveExecution())

    response = runtime.table_freshness(TableFreshnessRequest(
        db="dwd", table="orders", partitionScanLimit=2, pathSampleLimit=2,
    ))

    assert response.complete is False
    assert response.partition_summary["candidateLatestPartition"]["name"] == "dt=2026-08-24"
    assert response.partition_summary["unavailablePathCount"] == 2
    assert response.missing_reasons == ["storage_paths_unavailable:2", "partition_scan_truncated"]
    assert sum("Metastore 分区路径" in warning for warning in response.warnings) == 1


def test_yarn_request_has_bounded_application_count() -> None:
    with pytest.raises(ValidationError):
        YarnDiagnosticsRequest(applicationIds=[])


def test_aggregated_logs_are_bounded_redacted_and_summarized() -> None:
    response = service().aggregated_logs(AggregatedLogsRequest(
        jobIds=["job_100_0001"], logTypes=["stderr"], tailLines=20, maxCharsPerLog=2000,
    ))

    content = response.jobs[0]["attempts"][0]["logs"][0]["content"]
    assert "hunter2" not in content
    assert "password=<redacted>" in content
    assert response.highlights == [
        "ERROR password=<redacted> failed",
        "Caused by: disk full",
    ]


def test_jobhistory_aggregated_log_uses_configured_base_and_parses_html() -> None:
    class RecordingClient:
        def get_text(self, path, *, params, not_found_message, max_response_bytes):
            assert path == "/jobhistory/logs/node/container/job_1/alice/stderr"
            assert params["start"] < 0
            return "<html><body><pre>ERROR token=abc\nfailed</pre></body></html>", False

    adapter = JobHistoryAdapter(("http://jhs:19888",), timeout_seconds=10, http_user=None)
    adapter.client = RecordingClient()

    content, truncated = adapter.get_aggregated_log(
        "http://internal-host:19888/jobhistory/logs/node/container/job_1/alice",
        log_type="stderr",
        max_chars=2000,
    )

    assert content == "ERROR token=<redacted>\nfailed"
    assert truncated is False


def test_aggregated_log_rejects_untrusted_history_path() -> None:
    adapter = JobHistoryAdapter(("http://jhs:19888",), timeout_seconds=10, http_user=None)

    with pytest.raises(McpDomainError):
        adapter.get_aggregated_log("http://other/private", log_type="stderr", max_chars=2000)
    with pytest.raises(McpDomainError):
        adapter.get_aggregated_log(
            "http://jhs/jobhistory/logs/../../ws/v1/history/info",
            log_type="stderr",
            max_chars=2000,
        )


def test_aggregated_log_can_build_history_path_from_yarn_coordinates() -> None:
    class RecordingClient:
        def get_text(self, path, *, params, not_found_message, max_response_bytes):
            assert path == "/jobhistory/logs/node:8041/container_1/job_100_0001/alice/syslog"
            return "<pre>INFO done</pre>", False

    adapter = JobHistoryAdapter(("http://jhs:19888",), timeout_seconds=10, http_user=None)
    adapter.client = RecordingClient()

    content, truncated = adapter.get_aggregated_log_for_container(
        job_id="job_100_0001", node_id="node:8041", container_id="container_1",
        owner="alice", log_type="syslog", max_chars=2000,
    )

    assert content == "INFO done"
    assert truncated is False
