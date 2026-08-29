import type { TaskInstance } from '../types';

const RUNTIME_ACTIVE_STATUSES = new Set(['running', 'restarting']);

/** 运行监控与参考项目一致：优先当前生产运行实例，否则展示最新生产实例。 */
export const selectRuntimeInstance = (instances: TaskInstance[]) => instances.find((instance) =>
  RUNTIME_ACTIVE_STATUSES.has((instance.status ?? '').toLowerCase())) ?? instances[0];
