export interface BaseResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface SessionVO {
  sessionId: string;
  title: string;
  createdAt: string;
  lastActiveAt: string;
  archived: boolean;
  contextType?: AiContextType;
  contextId?: string;
  contextTitle?: string;
}

export type SessionStatus = 'active' | 'archived' | 'all';
export type SessionSortBy = 'createdAt' | 'lastActiveAt';
export type SortOrder = 'asc' | 'desc';

export interface SessionManageQuery {
  status: SessionStatus;
  keyword: string;
  page: number;
  pageSize: number;
  sortBy: SessionSortBy;
  sortOrder: SortOrder;
}

export interface SessionPageVO {
  items: SessionVO[];
  page: number;
  pageSize: number;
  total: number;
}

export interface LoginUser {
  obId: string;
}

export interface MessageVO {
  role: 'user' | 'assistant';
  content: string;
  thinking?: string;
  steps?: Step[];
}

export type SqlCommand =
  | 'sql_generate'
  | 'sql_optimize'
  | 'sql_fix'
  | 'sql_explain'
  | 'sql_static_check'
  | 'platform_assist';

export type AiContextType =
  | 'OFFLINE_TASK'
  | 'OFFLINE_VERSION'
  | 'OFFLINE_EXECUTION'
  | 'OFFLINE_SCHEDULE'
  | 'DATA_COMPARE'
  | 'CATALOG_TABLE'
  | 'REALTIME_SYNC_TASK'
  | 'REALTIME_INSTANCE'
  | 'REALTIME_COMPUTE_TASK'
  | 'REALTIME_EXPORT_TASK'
  | 'REALTIME_TABLE'
  | 'REALTIME_SERVER'
  | 'REALTIME_ALERT'
  | 'PLATFORM_STATUS';

export type AiIntent =
  | 'GENERATE'
  | 'OPTIMIZE'
  | 'FIX'
  | 'EXPLAIN'
  | 'REVIEW'
  | 'DIAGNOSE'
  | 'RECOMMEND'
  | 'COMPARE'
  | 'SEARCH'
  | 'SUMMARIZE';

export interface AiContext {
  contextType: AiContextType;
  entityId?: string;
  parentId?: string;
  title?: string;
  versionNo?: number;
  revision?: number | string;
  draft?: Record<string, unknown>;
}

export interface AiProposal {
  /** platform_proposal_present 的工具调用 ID；用于历史回放与状态去重。 */
  proposalId?: string;
  target: string;
  kind: 'SQL' | 'DDL' | 'CONFIG' | 'FORM' | string;
  before?: string;
  after?: string;
  patch?: Record<string, unknown>;
  baseRevision?: number | string;
  summary?: string;
  risks?: string[];
}

export type ProposalApplyStatus = 'APPLIED' | 'READ_ONLY' | 'STALE' | 'INVALID' | 'UNSUPPORTED';

export interface ProposalActionResult {
  status: ProposalApplyStatus;
  message: string;
  details?: string[];
}

export interface ChatRequest {
  sessionId: string;
  taskId?: number;
  executionId?: number;
  versionNo?: number;
  command?: SqlCommand;
  intent?: AiIntent;
  context?: AiContext;
  message: string;
}

export type SqlTaskParameterType = 'STRING' | 'INTEGER' | 'DECIMAL' | 'DATE' | 'DATETIME' | 'BOOLEAN';
export type SqlTaskType = 'RUN_HIVE';

export interface SqlTaskParameter {
  name: string;
  type: SqlTaskParameterType;
  description?: string;
  required: boolean;
  defaultValue?: string | number | boolean;
}

export interface SqlTaskVO {
  id: number;
  name: string;
  description?: string;
  taskType: SqlTaskType;
  executionFrequency: string;
  owner: string;
  enabled: boolean;
  sql: string;
  ddl?: string;
  parameters: SqlTaskParameter[];
  sqlChecksum: string;
  revision: number;
  effectiveVersionNo?: number;
  archived: boolean;
  archivedBy?: string;
  archivedAt?: string;
  createdBy: string;
  updatedBy: string;
  createdAt: string;
  updatedAt: string;
  lastExecutionStatus?: TaskExecutionStatus;
  lastExecutionAt?: string;
  stepCount?: number;
}

export interface SqlTaskPageVO {
  items: SqlTaskVO[];
  page: number;
  pageSize: number;
  total: number;
}

export interface SqlTaskSaveRequest {
  name: string;
  description?: string;
  taskType?: SqlTaskType;
  executionFrequency?: string;
  owner?: string;
  sql: string;
  ddl?: string;
  revision?: number;
  parameters: SqlTaskParameter[];
}

export type SqlTaskVersionStatus = 'DRAFT' | 'EFFECTIVE' | 'HISTORICAL' | 'STALE';

export interface SqlTaskVersionVO {
  id: number;
  taskId: number;
  versionNo: number;
  status: SqlTaskVersionStatus;
  revision: number;
  baseEffectiveVersionNo?: number;
  baseEffectiveChecksum?: string;
  effectiveAt?: string;
  effectiveBy?: string;
  canEdit: boolean;
  inUnion?: boolean;
  canActivate?: boolean;
  name: string;
  description?: string;
  sql?: string;
  ddl?: string;
  parameters: SqlTaskParameter[];
  sqlChecksum: string;
  versionNote?: string;
  createdBy: string;
  updatedBy: string;
  createdAt: string;
  updatedAt: string;
  steps: SqlTaskStepVO[];
}

export interface SqlTaskVersionSaveRequest {
  name: string;
  description?: string;
  sql: string;
  ddl?: string;
  parameters: SqlTaskParameter[];
  note?: string;
  revision: number;
}

export interface SqlTaskVersionPageVO {
  items: SqlTaskVersionVO[];
  page: number;
  pageSize: number;
  total: number;
}

export interface TaskVersionCheckItemVO {
  type: 'VALIDATE' | 'QUALITY' | 'EXPLAIN' | 'COMPARE';
  status: string;
  passed?: boolean;
  complete?: boolean;
  errorCount?: number;
  warningCount?: number;
  durationMs?: number;
  summary?: string;
  checkedBy?: string;
  checkedAt?: string;
}

export interface TaskVersionCheckSummaryVO {
  taskId: number;
  versionNo: number;
  allPassed: boolean;
  readyToActivate: boolean;
  blockingReason?: string;
  checks: Partial<Record<TaskVersionCheckItemVO['type'], TaskVersionCheckItemVO>>;
}

export interface SqlTaskScheduleVO {
  id?: number;
  taskId: number;
  scheduleType: 'MANUAL' | 'CRON';
  cronExpression?: string;
  timezone: string;
  enabled: boolean;
  concurrencyPolicy: 'FORBID' | 'ALLOW';
  maxRetries: number;
  retryIntervalSeconds: number;
  executionTimeoutSeconds?: number;
  slaDurationMinutes?: number;
  timeoutPolicy?: 'ALERT_ONLY' | 'CANCEL';
  parameters: Record<string, unknown>;
  nextTriggerTime?: string;
  lastTriggerTime?: string;
  lastRunStatus?: string;
  revision: number;
}

export interface SqlTaskDependencyVO {
  id: number;
  taskId: number;
  upstreamTaskId: number;
  dependencyType: 'SUCCESS' | 'COMPLETED';
}

export interface SqlTaskScheduleRunVO {
  id: number;
  scheduleId?: number;
  taskId: number;
  triggerType: 'CRON' | 'MANUAL' | 'BACKFILL' | 'RETRY';
  scheduledTime: string;
  businessDate?: string;
  status: string;
  attemptNo: number;
  executionId?: number;
  backfillBatchId?: number;
  message?: string;
  createdBy: string;
}

export interface SqlTaskScheduleRunPageVO {
  items: SqlTaskScheduleRunVO[];
  page: number;
  pageSize: number;
  total: number;
}

export interface SqlTaskBackfillBatchVO {
  id: number;
  taskId: number;
  startDate: string;
  endDate: string;
  status: 'PENDING' | 'RUNNING' | 'PAUSED' | 'SUCCEEDED' | 'PARTIAL_FAILED' | 'FAILED' | 'CANCELLED';
  totalCount: number;
  submittedCount: number;
  succeededCount: number;
  failedCount: number;
  maxConcurrency: number;
  requestedBy: string;
  createTime: string;
  updateTime: string;
}

export interface SqlTaskBackfillItemVO {
  id: number; batchId: number; taskId: number; businessDate: string; status: string;
  executionId?: number; attemptNo: number; message?: string; createTime: string; updateTime: string;
}

export interface ScheduleDagVO {
  nodes: Array<{ id: number; name: string; enabled: boolean; schedule_type?: string; last_run_status?: string; last_execution_id?: number; last_execution_status?: string; last_backfill_id?: number; last_backfill_status?: string; medianDurationMs?: number; durationSampleCount: number; upstreamCount: number; downstreamCount: number; criticalPath: boolean }>;
  edges: Array<{ id: number; taskId: number; upstreamTaskId: number; dependencyType: string }>;
  criticalPathTaskIds: number[];
}

export interface SqlTaskBackfillBatchPageVO {
  items: SqlTaskBackfillBatchVO[];
  page: number;
  pageSize: number;
  total: number;
}

export type TaskExecutionStatus =
  | 'PENDING' | 'QUEUED' | 'RUNNING' | 'SUCCEEDED'
  | 'FAILED' | 'CANCELLING' | 'CANCELLED';

export type ExecutionCenterStatus = TaskExecutionStatus | 'all' | 'active' | 'cancelled';

export type TaskExecutionStepStatus = TaskExecutionStatus | 'SKIPPED';

export interface SqlTaskStepVO {
  id?: number;
  stepNo: number;
  stepOrder: number;
  stepName: string;
  sql: string;
  statementType?: string;
  inputTables: string[];
  outputTables: string[];
  status?: TaskExecutionStepStatus;
  queryId?: string;
  applicationIds: string[];
  jobIds: string[];
  errorMessage?: string;
  logFile?: string;
  startedAt?: string;
  finishedAt?: string;
  durationMs?: number;
}

export interface SqlStructurePreviewStepVO {
  stepNo: number;
  stepOrder: number;
  stepName: string;
  statementType: string;
  sql: string;
  renderedSql?: string;
}

export interface SqlStructurePreviewVO {
  valid: boolean;
  stepCount: number;
  renderedSql?: string;
  parameters: Record<string, unknown>;
  steps: SqlStructurePreviewStepVO[];
}

export interface SqlQueryPreviewVO {
  ok: boolean;
  source: string;
  fetchedAt: string;
  warnings: string[];
  complete: boolean;
  missingReasons: string[];
  stepNo: number;
  stepName: string;
  renderedSql: string;
  previewSql: string;
  parameters: Record<string, unknown>;
  defaultDb: string;
  columns: Array<{ name: string; type?: string }>;
  rows: unknown[][];
  rowCount: number;
  truncated: boolean;
  elapsedMs: number;
}

export interface TaskExecutionVO {
  id: number;
  taskId: number;
  taskName: string;
  sourceType: 'EFFECTIVE' | 'VERSION' | 'REPLAY';
  taskVersionNo?: number;
  taskRevision?: number;
  sourceExecutionId?: number;
  replayStrategy?: string;
  status: TaskExecutionStatus;
  currentStepNo?: number;
  totalSteps: number;
  succeededSteps: number;
  failedStepNo?: number;
  businessDate?: string;
  parameters: Record<string, unknown>;
  requestedBy: string;
  queryId?: string;
  applicationIds: string[];
  jobIds: string[];
  errorMessage?: string;
  submittedAt: string;
  startedAt?: string;
  finishedAt?: string;
  durationMs?: number;
  steps: SqlTaskStepVO[];
}

export interface TaskExecutionCreateRequest {
  versionNo?: number;
  revision?: number;
  businessDate?: string;
  parameters: Record<string, string | number | boolean | null | undefined>;
}

export interface TaskExecutionPageVO {
  items: TaskExecutionVO[];
  page: number;
  pageSize: number;
  total: number;
}

export interface TaskExecutionDiagnosticsVO {
  ok: boolean;
  source: string;
  fetchedAt: string;
  warnings: string[];
  complete: boolean;
  missingReasons: string[];
  execution: {
    id: number;
    taskId: number;
    status: TaskExecutionStatus;
    queryId?: string;
    applicationIds: string[];
    jobIds: string[];
  };
  aggregate: {
    jobCount: number;
    retainedJobCount?: number;
    expiredJobCount?: number;
    metrics: Record<string, number | null>;
  };
  jobs: Array<{
    job: Record<string, unknown> & {
      id?: string;
      applicationId?: string;
      name?: string;
      queue?: string;
      user?: string;
      state?: string;
      durationMs?: number;
      queueWaitMs?: number;
    };
    query: Record<string, unknown>;
    configuration: Record<string, unknown>;
    counters: Record<string, number>;
    tasks: {
      map?: { count?: number; durationMs?: Record<string, number | null> };
      reduce?: { count?: number; durationMs?: Record<string, number | null> };
      states?: Record<string, number>;
    };
    taskOutliers: Array<Record<string, unknown>>;
    yarnApplication?: Record<string, unknown>;
    missingReasons: string[];
  }>;
}

export interface DiagnosticReport {
  targetKind: 'OFFLINE_EXECUTION' | 'REALTIME_INSTANCE';
  targetId: number;
  revision: number;
  status: 'COMPLETE' | 'PARTIAL' | 'FAILED';
  complete: boolean;
  failureStage: string;
  summary: string;
  generatedAt: string;
  findings: Array<{
    code: string;
    severity: 'INFO' | 'WARNING' | 'ERROR';
    title: string;
    cause: string;
    impact: string;
    evidenceRefs: string[];
    actions: Array<{ type: string; label: string; description: string; link?: string }>;
  }>;
  evidence: Array<{ id: string; type: string; source: string; label: string; value: string; link?: string }>;
  missingEvidence: string[];
}

export interface ExecutionSummaryVO {
  total: number;
  active: number;
  succeeded24h: number;
  failed24h: number;
  cancelled24h: number;
}

export interface ExecutionLogChunkVO {
  content: string;
  nextOffset: number;
  eof: boolean;
  truncated: boolean;
}

export interface HiveColumnVO {
  name: string;
  dataType: string;
  comment?: string;
  nullable: boolean;
  partitionKey: boolean;
}

export interface HiveTableVO {
  catalog: string;
  db: string;
  table: string;
  owner?: string;
  comment?: string;
  tableType: string;
}

export interface HiveDatabaseListVO {
  ok: boolean;
  source: string;
  fetchedAt: string;
  warnings: string[];
  databases: string[];
}

export interface DataMapPrimaryKeysVO {
  ok: boolean;
  source: string;
  warnings: string[];
  result: {
    db: string;
    table: string;
    primaryKeys: string[];
  };
}

export interface SqlCompletionItemVO {
  label: string;
  apply?: string;
  type: 'database' | 'table' | 'column' | 'function';
  detail?: string;
  db?: string;
  table?: string;
  dataType?: string;
  comment?: string;
}

export interface SqlCompletionVO {
  ok: boolean;
  source: string;
  from: number;
  to: number;
  items: SqlCompletionItemVO[];
  warnings: string[];
  complete: boolean;
  missingReasons: string[];
}

export type DataCompareStatus =
  | 'PENDING' | 'RUNNING' | 'PASSED' | 'NOT_PASSED'
  | 'FORCE_PASSED' | 'NEEDS_RERUN'
  | 'FAILED' | 'CANCELLING' | 'CANCELLED';

export interface DataCompareRule {
  onlyCompareSamePrimaryKey: boolean;
  ignoreNullPrimaryKey: boolean;
  primaryKeyList: string[];
  compareColumnList: string[];
  probeColumnList: string[];
}

export interface DataCompareTableRequest {
  originalTable?: string;
  baselineSourceTable?: string;
  candidateSourceTable?: string;
  requiredByDdl?: boolean;
  baselineTable: string;
  candidateTable: string;
  rule: DataCompareRule;
}

export interface DataCompareCreateRequest {
  compareType: 'VERSION' | 'TABLE';
  taskId?: number;
  baselineVersionNo?: number;
  candidateVersionNo?: number;
  planToken?: string;
  baselineTable?: string;
  candidateTable?: string;
  baselineSteps: string[];
  candidateSteps: string[];
  onlyCompareSameColumn: boolean;
  tables: DataCompareTableRequest[];
}

export interface DataCompareStepVO {
  name: string;
  group: number;
  order: number;
  sql: string;
  inputTables: string[];
  outputTables: string[];
  requiredSteps: string[];
}

export interface DataComparePrepareVO {
  taskId: number;
  baselineVersion: { taskId: number; versionNo: number; name: string; note?: string; sql: string; ddl?: string; checksum: string };
  candidateVersion: { taskId: number; versionNo: number; name: string; note?: string; sql: string; ddl?: string; checksum: string };
  baselineSteps: DataCompareStepVO[];
  candidateSteps: DataCompareStepVO[];
  unionId?: number;
  unionDdl?: string;
}

export interface DataComparePlanVO extends DataComparePrepareVO {
  planToken: string;
  selectedBaselineSteps: string[];
  selectedCandidateSteps: string[];
  originalBaselineSql: string;
  originalCandidateSql: string;
  generatedBaselineSql: string;
  generatedCandidateSql: string;
  suggestedTables: DataCompareTableRequest[];
}

export interface DataCompareJobVO {
  id: number;
  compare_type: 'VERSION' | 'TABLE';
  task_id?: number;
  baseline_version_no?: number;
  candidate_version_no?: number;
  baseline_table?: string;
  candidate_table?: string;
  status: DataCompareStatus;
  operator_ob_id: string;
  error_message?: string;
  started_at?: string;
  finished_at?: string;
  create_time: string;
  update_time: string;
}

export interface DataCompareTableVO {
  id: number;
  job_id: number;
  original_tbl_name?: string;
  baseline_source_tbl_name?: string;
  candidate_source_tbl_name?: string;
  baseline_tbl_name: string;
  candidate_tbl_name: string;
  status: DataCompareStatus;
  display_status?: DataCompareStatus;
  compare_rule?: string;
  rule_revision?: number;
  verified_rule_revision?: number;
  result_stale?: boolean | number;
  rerun_count?: number;
  force_pass?: boolean | number;
  force_reason?: string;
  force_operator_ob_id?: string;
  force_time?: string;
  partition_scope?: string;
  meta_data_is_same?: boolean | number;
  meta_data_diff?: string;
  row_num_is_same?: boolean | number;
  row_nums?: string;
  crc32_value_is_same?: boolean | number;
  crc32_values?: string;
  col_probe_detail?: string;
  diff_detail?: string;
  diff_tbl_name?: string;
  error_message?: string;
  log_file?: string;
}

export interface DataCompareDetailVO extends DataCompareJobVO {
  tables: DataCompareTableVO[];
  original_baseline_sql?: string;
  original_candidate_sql?: string;
  generated_baseline_sql?: string;
  generated_candidate_sql?: string;
}

export interface DataCompareReportVO extends DataCompareJobVO {
  original_baseline_sql?: string;
  original_candidate_sql?: string;
  generated_baseline_sql?: string;
  generated_candidate_sql?: string;
  tablePage: { items: DataCompareTableVO[]; page: number; pageSize: number; total: number };
}

export interface DataComparePageVO {
  items: DataCompareJobVO[];
  page: number;
  pageSize: number;
  total: number;
}

export type TaskVersionUnionStatus = 'DRAFT' | 'PUBLISHING' | 'PUBLISHED' | 'PUBLISH_FAILED' | 'STALE';
export interface TaskVersionUnionMemberVO {
  id: number;
  union_id: number;
  task_id: number;
  task_name: string;
  version_no: number;
  version_revision: number;
  current_version_revision: number;
  version_checksum: string;
  current_version_checksum: string;
  baseline_checksum: string;
  current_baseline_checksum: string;
  ddl?: string;
  version_note?: string;
  version_status: SqlTaskVersionStatus;
  latest_compare_job_id?: number;
  compare_status?: DataCompareStatus;
}
export interface TaskVersionUnionVO {
  id: number;
  union_ddl?: string;
  status: TaskVersionUnionStatus;
  revision: number;
  error_message?: string;
  created_by: string;
  updated_by: string;
  published_by?: string;
  published_time?: string;
  create_time: string;
  update_time: string;
  members: TaskVersionUnionMemberVO[];
  member_count: number;
  can_publish: boolean;
}
export interface TaskVersionUnionCandidateVO {
  task_id: number;
  task_name: string;
  version_no: number;
  version_revision: number;
  version_note?: string;
  ddl?: string;
  sql_checksum: string;
  baseline_checksum: string;
  task_revision: number;
}

export interface HiveFunctionSearchVO {
  ok: boolean;
  source: string;
  fetchedAt: string;
  warnings: string[];
  complete: boolean;
  missingReasons: string[];
  items: string[];
  total: number;
  limit: number;
  offset: number;
  keyword: string;
  defaultDb: string;
  elapsedMs: number;
  cacheHit?: boolean;
}

export interface HiveFunctionDetailVO {
  ok: boolean;
  source: string;
  fetchedAt: string;
  warnings: string[];
  complete: boolean;
  missingReasons: string[];
  name: string;
  lines: string[];
  properties: Record<string, string>;
  defaultDb: string;
  elapsedMs: number;
  truncated: boolean;
  cacheHit?: boolean;
  functionType: string;
  signatures: string[];
  arguments: Array<{ name: string; type: string; description: string }>;
  returnType: string;
  invocationTemplate: string;
  examples: string[];
}

export interface HiveTableSearchVO {
  ok: boolean;
  source: string;
  fetchedAt: string;
  warnings: string[];
  items: HiveTableVO[];
  total: number;
  limit: number;
  offset: number;
}

export interface HiveColumnsVO {
  ok: boolean;
  source: string;
  fetchedAt: string;
  warnings: string[];
  columns: HiveColumnVO[];
}

export interface HiveTableDetailVO {
  ok: boolean;
  source: string;
  fetchedAt: string;
  warnings: string[];
  table: HiveTableVO & {
    location?: string;
    columns?: HiveColumnVO[];
    serdeClass?: string;
    inputFormat?: string;
    outputFormat?: string;
    tblProperties?: Record<string, string>;
  };
}

export interface HivePartitionVO {
  name: string;
  values: Record<string, string>;
  location?: string;
}

export interface HivePartitionsVO {
  ok: boolean;
  source: string;
  fetchedAt: string;
  warnings: string[];
  partitions: HivePartitionVO[];
  total: number;
  limit: number;
  offset: number;
}

export interface HiveDdlVO {
  ok: boolean;
  source: string;
  fetchedAt: string;
  warnings: string[];
  ddl: string;
}

export interface HiveStatisticsVO {
  ok: boolean;
  source: string;
  fetchedAt: string;
  warnings: string[];
  complete: boolean;
  missingReasons: string[];
  tableStatistics: Record<string, unknown>;
  partitionStatistics: Array<Record<string, unknown>>;
  columnStatistics: Array<Record<string, unknown>>;
}

export interface HiveStorageLayoutVO {
  ok: boolean;
  source: string;
  fetchedAt: string;
  warnings: string[];
  complete: boolean;
  missingReasons: string[];
  table: Record<string, unknown>;
  paths: Array<Record<string, unknown>>;
  summary: Record<string, unknown>;
  fileSizeDistribution: Record<string, unknown>;
}

export interface HiveTableFreshnessVO {
  ok: boolean;
  source: string;
  fetchedAt: string;
  warnings: string[];
  complete: boolean;
  missingReasons: string[];
  table: {
    catalog: string;
    db: string;
    table: string;
    partitioned: boolean;
    partitionKeys: string[];
    metadataLastDdlEpochSeconds?: number;
  };
  partitionSummary: {
    totalPartitions: number;
    scannedPartitionCount: number;
    scanLimit: number;
    candidatePathCount: number;
    pathSampleCount: number;
    unavailablePathCount: number;
    candidateLatestPartition?: HivePartitionVO;
  };
  storagePaths: Array<{
    path: string;
    type?: string;
    length?: number;
    modificationTime?: number;
    accessTime?: number;
    owner?: string;
    group?: string;
  }>;
  latestStorageModificationTime?: number;
  storageAgeSeconds?: number;
}

export interface PlatformDependencyVO {
  name: string;
  configured: boolean;
  reachable: boolean;
  latencyMs?: number;
  errorCode?: string;
  message?: string;
  details?: Record<string, unknown>;
}

export interface PlatformHealthVO {
  ok: boolean;
  source: string;
  fetchedAt: string;
  warnings: string[];
  complete: boolean;
  missingReasons: string[];
  dependencies: PlatformDependencyVO[];
}

export type LineageValidationStatus = 'EXISTS' | 'MISSING' | 'UNRESOLVED' | 'UNKNOWN';

export interface SqlLineageTableVO {
  catalog?: string;
  db?: string;
  table: string;
  qualifiedName: string;
  validationStatus: LineageValidationStatus;
  tableType?: string;
  owner?: string;
  comment?: string;
  partitionKeys?: string[];
}

export interface SqlLineageCteVO {
  name: string;
  dependencies: Array<{
    kind: 'cte' | 'table';
    name?: string;
    catalog?: string;
    db?: string;
    table?: string;
    qualifiedName?: string;
  }>;
}

export interface TaskLineageVO {
  ok: boolean;
  source: string;
  fetchedAt: string;
  taskId: number;
  taskName: string;
  defaultDb?: string;
  statementCount: number;
  inputs: SqlLineageTableVO[];
  outputs: SqlLineageTableVO[];
  ctes: SqlLineageCteVO[];
  warnings: string[];
  complete: boolean;
  missingReasons: string[];
}

export interface TaskDependencyNodeVO {
  taskId: number;
  taskName: string;
  updatedAt?: string;
  tables?: string[];
  depth?: number;
}

export interface TaskDependenciesVO {
  ok: boolean;
  source: string;
  fetchedAt: string;
  taskId: number;
  taskName: string;
  scannedTaskCount: number;
  totalTaskCount: number;
  scanLimit: number;
  target: TaskDependencyNodeVO;
  directUpstream: TaskDependencyNodeVO[];
  directDownstream: TaskDependencyNodeVO[];
  transitiveUpstream: TaskDependencyNodeVO[];
  transitiveDownstream: TaskDependencyNodeVO[];
  externalInputs: string[];
  selfDependencies: string[];
  outputs: Array<{ table: string; consumerTasks: TaskDependencyNodeVO[] }>;
  producerConflicts: Array<{ table: string; producerTasks: TaskDependencyNodeVO[] }>;
  parseFailures: Array<TaskDependencyNodeVO & { reason: string }>;
  parsedTaskCount: number;
  edges: Array<{ upstreamTaskId: number; downstreamTaskId: number; table: string }>;
  warnings: string[];
  complete: boolean;
  missingReasons: string[];
}

export interface HiveValidationVO {
  ok: boolean;
  source: string;
  fetchedAt: string;
  warnings: string[];
  complete: boolean;
  missingReasons: string[];
  valid: boolean;
  defaultDb: string;
  compilationMs: number;
  errors: Array<{ type: string; message: string; stepNo?: number; stepName?: string }>;
  steps?: Array<{
    stepNo: number;
    stepName: string;
    valid: boolean;
    errors: Array<{ type: string; message: string; stepNo?: number; stepName?: string }>;
    warnings: string[];
  }>;
}

export interface HiveExplainVO {
  ok: boolean;
  source: string;
  fetchedAt: string;
  warnings: string[];
  complete: boolean;
  missingReasons: string[];
  planSource: 'predicted';
  planText: string;
  defaultDb: string;
  compilationMs: number;
  truncated: boolean;
  risks?: Array<{
    code: 'CARTESIAN_JOIN' | 'GLOBAL_SORT' | 'PARTITION_NOT_PRUNED' | string;
    level: 'warning';
    message: string;
    evidence: string;
    stepNo: number;
    stepName: string;
  }>;
  steps?: Array<{
    stepNo: number;
    stepName: string;
    planText: string;
    risks: Array<{ code: string; level: 'warning'; message: string; evidence: string }>;
  }>;
}

export type TaskQualityStatus = 'PASSED' | 'PASSED_WITH_WARNINGS' | 'FAILED' | 'INCOMPLETE';

export interface TaskQualityIssueVO {
  level: 'error' | 'warning' | 'info';
  category: 'correctness' | 'performance' | 'metadata' | 'compilation';
  code: string;
  message: string;
  suggestion: string;
  object?: string;
}

export interface TaskQualityVO {
  ok: boolean;
  source: string;
  fetchedAt: string;
  taskId: number;
  taskName: string;
  status: TaskQualityStatus;
  passed: boolean;
  complete: boolean;
  summary: { error: number; warning: number; info: number };
  checks: {
    static: { source: string; passed: boolean };
    metadata: {
      source: string;
      checked: number;
      exists: number;
      missing: number;
      unknown: number;
    };
    compilation: {
      source: string;
      valid: boolean | null;
      available?: boolean;
      defaultDb?: string;
      compilationMs?: number;
      errors: Array<{ type: string; message: string }>;
    };
  };
  lineage: {
    source?: string;
    defaultDb?: string;
    statementCount: number;
    inputs: SqlLineageTableVO[];
    outputs: SqlLineageTableVO[];
    ctes: SqlLineageCteVO[];
    warnings?: string[];
    complete?: boolean;
    missingReasons?: string[];
  };
  issues: TaskQualityIssueVO[];
  warnings: string[];
  missingReasons: string[];
}

export interface DonePayload {
  usage?: Record<string, unknown>;
  sessionId?: string;
}

export type PermissionStatus = 'pending' | 'allowed' | 'denied';
export type UserQuestionStatus = 'pending' | 'answered' | 'cancelled';

export interface UserQuestionOption {
  label: string;
  description?: string;
}

export interface UserQuestionItem {
  question: string;
  header?: string;
  options?: UserQuestionOption[];
  multiSelect?: boolean;
}

export interface UserQuestionAnswerItem {
  question: string;
  header?: string;
  selectedLabels: string[];
  selectedOptions: UserQuestionOption[];
  customAnswer?: string;
}

export interface UserQuestionAnswerPayload {
  answers?: UserQuestionAnswerItem[];
  cancelled?: boolean;
  reason?: string;
}

export type Step =
  | { kind: 'thinking'; text: string }
  | { kind: 'text'; text: string }
  | { kind: 'error'; text: string }
  | {
      kind: 'tool';
      id: string;
      name?: string;
      input?: unknown;
      result?: string;
      isError?: boolean;
      semanticType?: 'user_question_answer' | 'user_question_prompt' | 'proposal';
    }
  | {
      kind: 'permission';
      requestId: string;
      toolName: string;
      toolInput: unknown;
      status: PermissionStatus;
    }
  | {
      kind: 'user_question';
      requestId: string;
      questions: UserQuestionItem[];
      rawInput?: unknown;
      status: UserQuestionStatus;
      answers?: UserQuestionAnswerItem[];
    };

export interface ToolUsePayload {
  id: string;
  name: string;
  input: unknown;
}

export interface ToolResultPayload {
  toolUseId: string;
  content: string;
  isError: boolean;
  semanticType?: 'user_question_answer' | 'user_question_prompt' | 'proposal';
}

export interface PermissionRequestPayload {
  requestId: string;
  toolName: string;
  toolInput: unknown;
}

export interface UserQuestionRequestPayload {
  requestId: string;
  questions: UserQuestionItem[];
  rawInput?: unknown;
}

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  thinking?: string;
  steps?: Step[];
  streaming?: boolean;
  error?: boolean;
}
