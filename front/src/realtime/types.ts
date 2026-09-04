export type RealtimeTaskStatus = 'not_running' | 'submitting' | 'running' | 'stopping' | 'restarting' | 'failed';

export interface RealtimeServer {
  id: number;
  name: string;
  type: 'mysql';
  address: string;
  databaseName?: string;
  databasePrefix?: string;
  account?: string;
  passwordConfigured?: boolean;
  description?: string;
  operator: string;
  createTime?: string;
  updateTime?: string;
}

export interface RealtimeServerSave extends Omit<RealtimeServer, 'id' | 'type' | 'operator' | 'passwordConfigured' | 'createTime' | 'updateTime'> {
  password?: string;
}

export interface TablePrivateConfig {
  primaryKeys?: string[];
  partitionKeys?: string[];
  computedColumns?: string[];
}

export interface SyncTaskConfig {
  sourceServerId: number;
  parallelism?: number;
  taskManagerMemory?: string;
  jobManagerMemory?: string;
  checkpointInterval?: number;
  alarmType?: string;
  alarmGroup?: string;
  flinkConfOverrides?: Record<string, string>;
  cdcConfig: {
    databaseName?: string;
    selectedTables: string[];
    targetDatabase: string;
    domainPrefix: string;
    tablePrefix?: string;
    tableSuffix?: string;
    targetTable?: string;
    targetTableList?: string[];
    metadataColumns?: string[];
    typeMappings?: string[];
    mode?: string;
    ignoreIncompatible?: boolean;
    excludingTables?: string;
    mysqlConfOverrides?: Record<string, string>;
    tableConfOverrides?: Record<string, string>;
    tableConfigs?: Record<string, TablePrivateConfig>;
  };
}

export interface SyncTaskSave {
  name: string;
  owner?: string;
  description?: string;
  flinkVersion: string;
  sourceServerId: number;
  sourceType: 'mysql-cdc';
  targetDatabase: string;
  taskConfig: SyncTaskConfig;
  expectedUpdateTime?: string;
}

export interface SyncTask extends SyncTaskSave {
  id: number;
  status: RealtimeTaskStatus;
  projectId?: number;
  projectName?: string;
  sourceServerName?: string;
  createTime: string;
  updateTime: string;
  editPolicy?: {
    editable: boolean;
    updateBlocked?: boolean;
    structureLocked: boolean;
    productionLocked?: boolean;
    lockedTables?: string[];
    lockedTableConfigs?: Record<string, TablePrivateConfig>;
    topologyChanged?: boolean;
    syncTableSetChanged?: boolean;
    requiredStartType?: 'savepoint';
    requiredStatePath?: string;
    reason?: string;
  };
}

export interface SyncTaskListItem {
  id: number;
  name: string;
  status: RealtimeTaskStatus;
  owner: string;
  description?: string;
  flinkVersion?: string;
  sourceServerId: number;
  sourceServerName?: string;
  sourceServerDatabase?: string;
  mappingCount?: number;
  mapping?: TaskMapping[];
  parallelism?: number;
  taskManagerMemory?: string;
  jobManagerMemory?: string;
  taskManagerCount?: number;
  configuredMemoryMb?: number;
  configuredVCores?: number;
  flinkUrl?: string;
  runtimeSeconds?: number;
  targetDatabase: string;
  projectName?: string;
  lastOperator?: string;
  lastAction?: string;
  lastOperationTime?: string;
  runtimeStatus?: string;
  managed?: boolean;
  runtimeFailureMessage?: string;
  createTime: string;
  updateTime: string;
}

export interface SyncTaskPage {
  items: SyncTaskListItem[];
  page: number;
  pageSize: number;
  total: number;
}

export interface TaskInstance {
  id: number;
  taskId: number;
  versionId?: number;
  jobId?: string;
  yarnApplicationId?: string;
  status: string;
  executionMode: 'PRODUCTION' | 'DEBUG';
  managed: boolean;
  trackingUrl?: string;
  savepointPath?: string;
  failureMessage?: string;
  startedAt?: string;
  endedAt?: string;
  createTime: string;
  updateTime: string;
}

export interface TaskRuntimeSnapshot {
  available?: boolean;
  instanceId?: number;
  jobId?: string;
  status?: string;
  uptimeMs?: number;
  restartCount?: number;
  restartCountUnavailableReason?: string;
  sync?: TaskSyncRuntimeMetrics;
  vertices?: TaskRuntimeVertexMetric[];
  latestException?: TaskRuntimeException;
  latestExceptionUnavailableReason?: string;
  updatedAt?: string;
}

export interface TaskSyncRuntimeMetrics {
  sourceOutputRate?: number | null;
  sinkInputRate?: number | null;
  committedRate?: number | null;
  committedRecords?: number | null;
  lastCommitDurationMs?: number | null;
  lastCommitAttempts?: number | null;
  busyMaxMsPerSecond?: number | null;
  backpressuredMaxMsPerSecond?: number | null;
  unavailableReasons?: Record<string, string>;
}

export interface TaskRuntimeVertexMetric {
  id: string;
  name?: string;
  status?: string;
  parallelism?: number;
  role?: 'source' | 'sink' | 'source_sink' | 'operator';
  inputRate?: number | null;
  outputRate?: number | null;
  commitRate?: number | null;
  busyMaxMsPerSecond?: number | null;
  backpressuredMaxMsPerSecond?: number | null;
  unavailableReason?: string;
}

export interface TaskRuntimeException {
  exception?: string;
  timestamp?: number;
  taskName?: string;
  location?: string;
}

export interface TaskRuntimeResources {
  available?: boolean;
  instanceId?: number;
  jobId?: string;
  updatedAt?: string;
  configured?: {
    parallelism?: number;
    checkpointIntervalSeconds?: number;
  };
  flink?: {
    jobManagerHeapUsed?: number;
    jobManagerNonHeapUsed?: number;
    jobManagerCpuLoad?: number;
    taskManagerCount?: number;
    totalSlots?: number;
    usedSlots?: number;
    taskManagers?: TaskManagerRuntimeResource[];
  };
  yarn?: {
    applicationId?: string;
    state?: string;
    runningContainers?: number;
    allocatedMemoryMb?: number;
    allocatedVCores?: number;
    unavailableReason?: string;
  };
}

export interface TaskManagerRuntimeResource {
  id: string;
  path?: string;
  heapUsed?: number;
  heapCommitted?: number;
  managedMemoryUsed?: number;
  networkMemoryUsed?: number;
  cpuLoad?: number;
  slots?: number;
  freeSlots?: number;
}

export interface TaskRuntimeCheckpointDetail {
  id?: number | string;
  status?: string;
  trigger_timestamp?: number;
  latest_ack_timestamp?: number;
  end_to_end_duration?: number;
  state_size?: number;
  external_path?: string;
  failure_message?: string;
}

export interface TaskRuntimeCheckpoints {
  available?: boolean;
  instanceId?: number;
  jobId?: string;
  updatedAt?: string;
  counts?: Record<string, number>;
  summary?: Record<string, unknown>;
  latest?: {
    completed?: TaskRuntimeCheckpointDetail;
    failed?: TaskRuntimeCheckpointDetail;
    savepoint?: TaskRuntimeCheckpointDetail;
    restored?: TaskRuntimeCheckpointDetail;
  };
  history?: TaskRuntimeCheckpointDetail[];
}

export interface TaskMapping {
  id: number;
  sourceServerId?: number;
  serverName?: string;
  sourceDatabase: string;
  sourceTable: string;
  targetDatabase: string;
  targetTable: string;
  sortOrder: number;
}

export interface TaskParam {
  id: number;
  paramType: 'mysql_conf' | 'table_conf' | 'flink_conf';
  paramKey: string;
  keyDesc: string;
  paramValue?: string;
  valueType: string;
  inputType: string;
  required?: number | boolean;
  minValue?: number;
  maxValue?: number;
  stepValue?: number;
  precisionValue?: number;
  sortOrder: number;
}

export interface PaimonTablePrefixOption { label: string; value: string }
export interface RealtimeAlert { id: number; taskId: number; taskName: string; severity: string; status: string; title: string; detail?: string; createTime: string; updateTime: string }
export interface TaskChangeLog { id: number; taskId: number; taskName: string; operationId?: number; beforeVersionId?: number; afterVersionId?: number; taskInstanceId?: number; operator: string; action: string; detail?: string; summary?: string; detailKind?: 'create' | 'edit' | 'start' | 'stop' | 'text'; createTime: string }
export interface MysqlColumn { name: string; type: string; nullable: boolean; comment?: string }
export interface MysqlTableSchema { table: string; columns: MysqlColumn[]; primaryKeys: string[] }
export interface SyncSourceTableOption { tableName: string; occupied: boolean; occupiedTaskId?: number; occupiedTaskName?: string }
