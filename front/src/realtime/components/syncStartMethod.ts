/** 历史记录只根据已持久化参数解释，不把信息缺失猜成首次全量。 */
export const syncStartMethodLabel = (
  startType: unknown,
  timestamp?: unknown,
  sourceMode?: unknown,
) => {
  if (startType === 'savepoint') return '从 Savepoint 恢复';
  if (startType === 'checkpoint') return '从 Checkpoint 恢复';
  if (startType === 'direct' && (timestamp != null || sourceMode === 'timestamp')) return '从指定时间戳开始消费';
  if (startType === 'direct' && sourceMode === 'initial') return '首次全量同步';
  if (startType === 'direct' && sourceMode === 'earliest-offset') return '从最早消费点同步';
  if (startType === 'direct' && sourceMode === 'latest-offset') return '从最新消费点同步';
  return '未记录';
};

export type SyncStartMethod = 'direct' | 'savepoint' | 'checkpoint';

type StartPolicy = {
  productionLocked?: boolean;
  syncTableSetChanged?: boolean;
  requiredStartType?: 'savepoint';
  requiredStatePath?: string;
  canResetConsumptionPoint?: boolean;
};

export const showStateRecoveryFallback = (policy?: StartPolicy) => Boolean(
  policy?.productionLocked && policy.requiredStartType === 'savepoint' && policy.requiredStatePath,
);

/** 启动方式下拉与参考项目共用同一套禁用规则。 */
export const syncStartMethodOptions = (policy?: StartPolicy, syncMode: 'full_incremental' | 'incremental' = 'full_incremental') => {
  return [
    { label: syncMode === 'incremental' ? '按消费点启动' : '首次全量同步', value: 'direct' as const,
      disabled: Boolean(policy?.syncTableSetChanged) || (syncMode !== 'incremental' && (Boolean(policy?.productionLocked) || Boolean(policy?.requiredStartType))) },
    { label: '从 Savepoint 恢复', value: 'savepoint' as const, disabled: false },
    { label: '从 Checkpoint 恢复', value: 'checkpoint' as const, disabled: false },
  ];
};
