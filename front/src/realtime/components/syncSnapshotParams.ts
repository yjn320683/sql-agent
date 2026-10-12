import type { TaskParam } from '../types';

// 只读快照仅借用字段展示元数据；选项只包含快照中保存的值，不读取当前参数表或注入默认值。
const descriptors: Record<string, Pick<TaskParam, 'keyDesc' | 'inputType' | 'valueType' | 'sortOrder'> & { required?: boolean }> = {
  'mysql_conf.scan.snapshot.fetch.size': { keyDesc: '快照单次拉取行数', inputType: 'select', valueType: 'list', sortOrder: 10, required: true },
  'mysql_conf.scan.incremental.snapshot.chunk.size': { keyDesc: '快照分片行数', inputType: 'select', valueType: 'list', sortOrder: 20, required: true },
  'table_conf.bucket': { keyDesc: '目标 Paimon 表 Bucket', inputType: 'input_number', valueType: 'number', sortOrder: 10, required: true },
  'table_conf.sink.parallelism': { keyDesc: '目标 Paimon 表 Sink 并行度', inputType: 'input_number', valueType: 'number', sortOrder: 20, required: true },
  'table_conf.changelog-producer': { keyDesc: '目标 Paimon 表 Changelog Producer', inputType: 'select', valueType: 'list', sortOrder: 30, required: true },
  'table_conf.dynamic-bucket.target-row-num': { keyDesc: '动态 Bucket 目标行数', inputType: 'input_number', valueType: 'number', sortOrder: 42 },
  'table_conf.sequence.field': { keyDesc: '主键表 Sequence 字段', inputType: 'input', valueType: 'string', sortOrder: 51, required: true },
  'table_conf.precommit-compact': { keyDesc: 'Changelog 提交前压缩', inputType: 'select', valueType: 'list', sortOrder: 65 },
  'table_conf.consumer.expiration-time': { keyDesc: 'Consumer 过期时间', inputType: 'input', valueType: 'string', sortOrder: 104, required: true },
  'flink_conf.taskmanager.memory.managed.fraction': { keyDesc: 'TaskManager Managed Memory 比例', inputType: 'input_number', valueType: 'number', sortOrder: 10, required: true },
  'flink_conf.taskmanager.memory.network.fraction': { keyDesc: 'TaskManager Network Memory 比例', inputType: 'input_number', valueType: 'number', sortOrder: 20, required: true },
  'flink_conf.taskmanager.memory.network.min': { keyDesc: 'TaskManager Network Memory 最小值', inputType: 'input', valueType: 'string', sortOrder: 30 },
  'flink_conf.taskmanager.memory.network.max': { keyDesc: 'TaskManager Network Memory 最大值', inputType: 'input', valueType: 'string', sortOrder: 40 },
  'flink_conf.taskmanager.memory.jvm-overhead.fraction': { keyDesc: 'TaskManager JVM Overhead 比例', inputType: 'input_number', valueType: 'number', sortOrder: 50 },
  'flink_conf.taskmanager.memory.jvm-overhead.min': { keyDesc: 'TaskManager JVM Overhead 最小值', inputType: 'input', valueType: 'string', sortOrder: 60 },
  'flink_conf.taskmanager.memory.jvm-overhead.max': { keyDesc: 'TaskManager JVM Overhead 最大值', inputType: 'input', valueType: 'string', sortOrder: 70 },
  'flink_conf.taskmanager.memory.task.off-heap.size': { keyDesc: 'TaskManager Task Off-Heap Memory', inputType: 'input', valueType: 'string', sortOrder: 80 },
  'flink_conf.taskmanager.memory.managed.size': { keyDesc: 'TaskManager Managed Memory 固定大小', inputType: 'input', valueType: 'string', sortOrder: 90 },
  'flink_conf.taskmanager.numberOfTaskSlots': { keyDesc: 'TaskManager Slot 数', inputType: 'input_number', valueType: 'number', sortOrder: 100 },
  'flink_conf.table.exec.state.ttl': { keyDesc: 'Table 状态 TTL', inputType: 'input', valueType: 'string', sortOrder: 180 },
  'flink_conf.table.exec.mini-batch.enabled': { keyDesc: 'MiniBatch 开关', inputType: 'select', valueType: 'list', sortOrder: 181 },
  'flink_conf.table.exec.mini-batch.allow-latency': { keyDesc: 'MiniBatch 允许延迟', inputType: 'input', valueType: 'string', sortOrder: 182 },
  'flink_conf.table.exec.mini-batch.size': { keyDesc: 'MiniBatch 批量大小', inputType: 'input_number', valueType: 'number', sortOrder: 183 },
  'flink_conf.table.exec.source.idle-timeout': { keyDesc: 'Source 空闲超时', inputType: 'input', valueType: 'string', sortOrder: 184 },
  'flink_conf.table.local-time-zone': { keyDesc: 'Table 时区', inputType: 'input', valueType: 'string', sortOrder: 185 },
  'flink_conf.yarn.application-attempts': { keyDesc: 'YARN AM 最大尝试次数（含首次）', inputType: 'input_number', valueType: 'number', sortOrder: 190 },
  'flink_conf.high-availability.type': { keyDesc: '高可用模式', inputType: 'select', valueType: 'list', sortOrder: 200 },
  'flink_conf.high-availability.storageDir': { keyDesc: 'HA 元数据共享存储 URI（需与 ZooKeeper HA 一起配置）', inputType: 'input', valueType: 'string', sortOrder: 201 },
  'flink_conf.high-availability.zookeeper.quorum': { keyDesc: 'ZooKeeper Quorum（需与 ZooKeeper HA 一起配置）', inputType: 'input', valueType: 'string', sortOrder: 202 },
  'flink_conf.high-availability.zookeeper.path.root': { keyDesc: 'ZooKeeper 根路径（默认 /flink）', inputType: 'input', valueType: 'string', sortOrder: 203 },
};

export function snapshotParams(overrides: Partial<Record<TaskParam['paramType'], Record<string, unknown> | undefined>>): TaskParam[] {
  return (['mysql_conf', 'table_conf', 'flink_conf'] as const).flatMap((paramType) =>
    Object.entries(overrides[paramType] ?? {}).map(([paramKey, value], index) => {
      const descriptor = descriptors[`${paramType}.${paramKey}`];
      return {
        paramType, paramKey,
        keyDesc: descriptor?.keyDesc ?? paramKey,
        inputType: descriptor?.inputType ?? 'input',
        valueType: descriptor?.valueType ?? 'string',
        sortOrder: descriptor?.sortOrder ?? 1000 + index,
        required: descriptor?.required ?? false,
        editable: false,
        paramValue: descriptor?.inputType === 'select' && value != null
          ? JSON.stringify([{ label: String(value), value: String(value) }]) : undefined,
      };
    }).sort((left, right) => left.sortOrder - right.sortOrder));
}
