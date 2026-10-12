import { describe, expect, it } from 'vitest';
import { parseSyncSnapshot } from './SyncTaskSnapshotModal';

describe('parseSyncSnapshot', () => {
  it('只使用实例保存值，不补任务默认值', () => {
    const parsed = parseSyncSnapshot({ taskType: 'sync', id: 1101, name: '历史任务',
      sourceServerId: 3, taskConfig: { cdcConfig: { selectedTables: ['orders'], targetDatabase: 'ods' } } });
    expect(parsed.task.name).toBe('历史任务');
    expect(parsed.task.taskConfig.parallelism).toBeUndefined();
    expect(parsed.task.taskConfig.cdcConfig.metadataColumns).toBeUndefined();
    expect(parsed.task.sourceServerName).toBeUndefined();
  });

  it('还原统一快照中实际运行与告警字段', () => {
    const parsed = parseSyncSnapshot({ request: { taskType: 'sync', taskConfig: {
      cdcConfig: { selectedTables: ['orders'] } },
      alarmConfig: { alarmType: 'task-failed', alarmGroup: 'ops' },
      flinkConf: { parallelism: 3, taskManagerMemoryGb: 2 } } });
    expect(parsed.task.taskConfig.alarmGroup).toBe('ops');
    expect(parsed.task.taskConfig.parallelism).toBe(3);
    expect(parsed.task.taskConfig.taskManagerMemory).toBe('2GB');
  });

  it('识别版本接口的 config 包装，不把版本 ID 当作任务 ID', () => {
    const parsed = parseSyncSnapshot({ id: 27, versionNo: 3, config: {
      name: '第三版', taskConfig: { cdcConfig: { selectedTables: ['orders'] } },
    } }, 1101);
    expect(parsed.task.id).toBe(1101);
    expect(parsed.task.name).toBe('第三版');
  });

  it('拒绝错误类型和缺失源表字段的快照', () => {
    expect(() => parseSyncSnapshot({ taskType: 'compute', taskConfig: { cdcConfig: { selectedTables: [] } } })).toThrow();
    expect(() => parseSyncSnapshot({ taskType: 'sync', taskConfig: { cdcConfig: {} } })).toThrow();
    expect(() => parseSyncSnapshot({ taskType: 'sync', id: 1101, instanceId: 251,
      taskConfig: { cdcConfig: { selectedTables: ['orders'] } } }, 1101, 250)).toThrow('实例 ID 缺失或不匹配');
    expect(() => parseSyncSnapshot({ instanceId: 250, id: 1101,
      taskConfig: { cdcConfig: { selectedTables: ['orders'] } } }, 1101, 250)).toThrow('缺少任务类型');
    expect(() => parseSyncSnapshot({ taskType: 'sync', instanceId: 250,
      taskConfig: { cdcConfig: { selectedTables: ['orders'] } } }, 1101, 250)).toThrow('缺少任务 ID');
    expect(() => parseSyncSnapshot({ taskType: 'sync', id: 1101,
      taskConfig: { cdcConfig: { selectedTables: ['orders'] } } }, 1101, 250)).toThrow('实例 ID 缺失');
  });
});
