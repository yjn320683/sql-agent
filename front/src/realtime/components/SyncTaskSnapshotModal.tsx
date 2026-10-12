import { Alert, Modal } from 'antd';
import SyncTaskEditorDrawer from './SyncTaskEditorDrawer';
import type { SyncTask, SyncTaskConfig } from '../types';

const record = (value: unknown): Record<string, unknown> => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};

/** 实例快照是唯一数据源；不得用当前任务或参数默认值补齐历史配置。 */
export function parseSyncSnapshot(value: unknown, expectedTaskId?: number, expectedInstanceId?: number): { task: SyncTask; start: { startType?: string; statePath?: string; sourceStartupTimestampMillis?: number } } {
  const outer = record(value);
  const source = record(outer.config ?? outer.request ?? outer.task ?? outer);
  if (expectedInstanceId != null && !source.taskType) throw new Error('实例配置快照缺少任务类型');
  if (source.taskType && source.taskType !== 'sync') throw new Error('配置快照不是同步任务');
  if (expectedInstanceId != null && source.id == null && source.taskId == null) throw new Error('实例配置快照缺少任务 ID');
  if (expectedTaskId != null && source.id != null && Number(source.id) !== expectedTaskId) throw new Error('配置快照任务 ID 不匹配');
  if (expectedTaskId != null && source.taskId != null && Number(source.taskId) !== expectedTaskId) throw new Error('配置快照任务 ID 不匹配');
  if (expectedInstanceId != null && Number(outer.instanceId) !== expectedInstanceId) throw new Error('配置快照实例 ID 缺失或不匹配');
  const rawConfig = record(source.taskConfig);
  const cdc = record(rawConfig.cdcConfig);
  if (!Object.keys(rawConfig).length || !Object.keys(cdc).length || !Array.isArray(cdc.selectedTables)) {
    throw new Error('实例有效配置快照缺失或不完整');
  }
  const alarm = record(source.alarmConfig);
  const flink = record(source.flinkConf);
  const config = {
    ...rawConfig,
    cdcConfig: cdc,
    alarmType: rawConfig.alarmType ?? alarm.alarmType,
    alarmGroup: rawConfig.alarmGroup ?? alarm.alarmGroup,
    parallelism: rawConfig.parallelism ?? flink.parallelism,
    checkpointInterval: rawConfig.checkpointInterval ?? flink.checkpointIntervalSeconds,
    taskManagerMemory: rawConfig.taskManagerMemory ?? (flink.taskManagerMemoryGb == null ? undefined : `${flink.taskManagerMemoryGb}GB`),
    jobManagerMemory: rawConfig.jobManagerMemory ?? (flink.jobManagerMemoryGb == null ? undefined : `${flink.jobManagerMemoryGb}GB`),
    flinkConfOverrides: rawConfig.flinkConfOverrides ?? flink.flinkConfOverrides,
  } as unknown as SyncTaskConfig;
  const task = {
    id: Number(source.id ?? source.taskId ?? expectedTaskId ?? 0), name: String(source.name ?? ''),
    owner: String(source.owner ?? ''), description: String(source.description ?? ''),
    flinkVersion: String(source.flinkVersion ?? ''), sourceType: 'mysql-cdc',
    sourceServerId: Number(source.sourceServerId ?? rawConfig.sourceServerId ?? 0),
    sourceServerName: source.sourceServerName == null ? undefined : String(source.sourceServerName),
    targetDatabase: String(source.targetDatabase ?? cdc.targetDatabase ?? ''),
    taskConfig: config, status: 'not_running', instanceStatus: 'not_running', taskStatus: 0,
    createTime: '', updateTime: '',
  } as SyncTask;
  return { task, start: {
    startType: source.startType == null ? undefined : String(source.startType),
    statePath: source.statePath == null ? undefined : String(source.statePath),
    sourceStartupTimestampMillis: source.sourceStartupTimestampMillis == null ? undefined : Number(source.sourceStartupTimestampMillis),
  } };
}

export default function SyncTaskSnapshotModal({ open, title, value, loading, loadError, snapshotKey, expectedTaskId, expectedInstanceId, onClose }: {
  open: boolean; title?: string; value?: unknown; loading?: boolean; loadError?: string; snapshotKey: string;
  expectedTaskId?: number; expectedInstanceId?: number; onClose: () => void;
}) {
  let parsed: ReturnType<typeof parseSyncSnapshot> | undefined;
  let error = loadError ?? '';
  if (value !== undefined) {
    try { parsed = parseSyncSnapshot(value, expectedTaskId, expectedInstanceId); } catch (cause) { error = (cause as Error).message; }
  }
  if (!loading && value === undefined && !error) error = '配置快照不存在或读取失败，请关闭后重试。';
  return <Modal className="sync-task-snapshot-modal" title={title} open={open} onCancel={onClose} footer={null}
    width={1520} destroyOnHidden loading={loading}>
    {error ? <Alert type="error" showIcon message={error} /> : parsed && <>
      <Alert type="info" showIcon message={expectedInstanceId == null
        ? '以下为历史版本配置快照，仅供查看；不会回填当前任务或默认参数。'
        : '以下为该实例启动时保存的有效配置快照，仅供查看；不会回填当前任务或默认参数。'} />
      <SyncTaskEditorDrawer key={snapshotKey} open task={parsed.task} readOnly snapshotMode snapshotKey={snapshotKey}
        snapshotStart={expectedInstanceId == null ? undefined : parsed.start} onClose={onClose} onSaved={() => undefined} />
    </>}
  </Modal>;
}
