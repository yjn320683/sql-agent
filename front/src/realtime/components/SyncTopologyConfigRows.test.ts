import { describe, expect, it } from 'vitest';
import { mergeSyncTopologyOverrides } from './SyncTopologyConfigRows';

describe('mergeSyncTopologyOverrides', () => {
  it('新建同步任务默认使用并行度 3', () => {
    expect(mergeSyncTopologyOverrides({})).toMatchObject({
      bucket: '3',
      'sink.parallelism': '3',
    });
  });

  it('保留已有任务的冻结拓扑', () => {
    expect(mergeSyncTopologyOverrides({}, { 'sink.parallelism': '2' }, 2)).toMatchObject({
      bucket: '2',
      'sink.parallelism': '2',
    });
  });

  it('旧任务缺少 Sink 字段时使用已保存的任务并行度', () => {
    expect(mergeSyncTopologyOverrides({}, {}, 2)).toMatchObject({
      bucket: '2',
      'sink.parallelism': '2',
    });
  });
});
