"""Hadoop 运行诊断请求与响应。"""

from __future__ import annotations

from pydantic import BaseModel, Field, model_validator

from sql_agent_mcp_server.common.schemas import DiagnosticFactResponse


class StorageLayoutRequest(BaseModel):
    catalog: str = "hive"
    db: str = Field(min_length=1)
    table: str = Field(min_length=1)
    partitions: list[str] = Field(default_factory=list, max_length=50)
    max_files: int = Field(default=1000, ge=1, le=5000, alias="maxFiles")


class StorageLayoutResponse(DiagnosticFactResponse):
    table: dict
    paths: list[dict]
    summary: dict
    file_size_distribution: dict = Field(alias="fileSizeDistribution")


class TableFreshnessRequest(BaseModel):
    catalog: str = "hive"
    db: str = Field(min_length=1)
    table: str = Field(min_length=1)
    partition_scan_limit: int = Field(default=2000, ge=1, le=5000, alias="partitionScanLimit")
    path_sample_limit: int = Field(default=20, ge=1, le=50, alias="pathSampleLimit")


class TableFreshnessResponse(DiagnosticFactResponse):
    table: dict
    partition_summary: dict = Field(alias="partitionSummary")
    storage_paths: list[dict] = Field(alias="storagePaths")
    latest_storage_modification_time: int | None = Field(alias="latestStorageModificationTime")
    storage_age_seconds: int | None = Field(alias="storageAgeSeconds")


class YarnDiagnosticsRequest(BaseModel):
    application_ids: list[str] = Field(min_length=1, max_length=20, alias="applicationIds")
    include_attempts: bool = Field(default=True, alias="includeAttempts")
    include_containers: bool = Field(default=True, alias="includeContainers")


class YarnDiagnosticsResponse(DiagnosticFactResponse):
    applications: list[dict]


class AggregatedLogsRequest(BaseModel):
    job_ids: list[str] = Field(min_length=1, max_length=5, alias="jobIds")
    log_types: list[str] = Field(default_factory=lambda: ["stderr", "syslog"], min_length=1, max_length=3, alias="logTypes")
    tail_lines: int = Field(default=200, ge=20, le=500, alias="tailLines")
    max_chars_per_log: int = Field(default=30_000, ge=2_000, le=100_000, alias="maxCharsPerLog")


class AggregatedLogsResponse(DiagnosticFactResponse):
    jobs: list[dict]
    highlights: list[str]


class JobSearchRequest(BaseModel):
    job_id: str | None = Field(default=None, alias="jobId")
    application_id: str | None = Field(default=None, alias="applicationId")
    query_id: str | None = Field(default=None, alias="queryId", max_length=512)
    user: str | None = Field(default=None, max_length=256)
    queue: str | None = Field(default=None, max_length=256)
    name: str | None = Field(default=None, max_length=512)
    state: str | None = Field(default=None, max_length=32)
    started_after: int | None = Field(default=None, alias="startedAfter", ge=0)
    started_before: int | None = Field(default=None, alias="startedBefore", ge=0)
    finished_after: int | None = Field(default=None, alias="finishedAfter", ge=0)
    finished_before: int | None = Field(default=None, alias="finishedBefore", ge=0)
    limit: int = Field(default=20, ge=1, le=50)

    @model_validator(mode="after")
    def validate_search_scope(self) -> "JobSearchRequest":
        if self.job_id or self.application_id:
            return self
        if not any((self.query_id, self.user, self.queue, self.name, self.state)):
            raise ValueError("搜索必须提供精确 ID 或至少一个过滤条件。")
        if self.query_id and not (self.started_after is not None and self.started_before is not None):
            raise ValueError("按 queryId 搜索必须提供 startedAfter 和 startedBefore。")
        return self


class JobSearchResponse(DiagnosticFactResponse):
    items: list[dict]
    total: int


class JobDiagnosticsRequest(BaseModel):
    job_ids: list[str] = Field(default_factory=list, max_length=20, alias="jobIds")
    application_ids: list[str] = Field(default_factory=list, max_length=20, alias="applicationIds")
    include_task_outliers: bool = Field(default=True, alias="includeTaskOutliers")
    outlier_limit: int = Field(default=10, ge=1, le=20, alias="outlierLimit")

    @model_validator(mode="after")
    def require_identifiers(self) -> "JobDiagnosticsRequest":
        if not self.job_ids and not self.application_ids:
            raise ValueError("jobIds 或 applicationIds 至少提供一项。")
        return self


class JobDiagnosticsResponse(DiagnosticFactResponse):
    jobs: list[dict]
    aggregate: dict


class JobCompareRequest(BaseModel):
    baseline_job_ids: list[str] = Field(min_length=1, max_length=20, alias="baselineJobIds")
    candidate_job_ids: list[str] = Field(min_length=1, max_length=20, alias="candidateJobIds")


class JobCompareResponse(DiagnosticFactResponse):
    comparable: bool
    incompatibilities: list[str]
    baseline: dict
    candidate: dict
    differences: dict


class DependencyHealthResponse(DiagnosticFactResponse):
    dependencies: list[dict]
