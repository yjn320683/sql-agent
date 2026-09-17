import { previewSqlStructure, validateHiveDdl } from '../../api/workspace';
import { analyzeManagedSql, validateManagedTask } from '../../realtime/api';
import { parseRealtimeTableDdl } from '../../realtime/utils/realtimeTableDdl';
import type { ManagedTaskSave } from '../../realtime/types';
import type { AiContext, AiProposal, ProposalActionResult } from '../../types';

type ProposalKind = 'SQL' | 'DDL' | 'FORM' | 'CONFIG';

interface TargetRule {
  contexts: AiContext['contextType'][];
  kinds: ProposalKind[];
  patchPaths?: string[];
  content?: 'sql' | 'ddl';
  types?: Record<string, 'string' | 'number' | 'boolean' | 'array' | 'object'>;
}

const RULES: Record<string, TargetRule> = {
  'offline-sql': { contexts: ['OFFLINE_TASK', 'OFFLINE_VERSION'], kinds: ['SQL'], content: 'sql' },
  'offline-ddl': { contexts: ['OFFLINE_TASK', 'OFFLINE_VERSION'], kinds: ['DDL'], content: 'ddl' },
  'offline-task-form': { contexts: ['OFFLINE_TASK', 'OFFLINE_VERSION'], kinds: ['FORM', 'CONFIG'], patchPaths: ['name', 'description', 'taskType', 'executionFrequency', 'owner', 'parameters'], types: { name: 'string', description: 'string', taskType: 'string', executionFrequency: 'string', owner: 'string', parameters: 'array' } },
  'offline-schedule': { contexts: ['OFFLINE_TASK', 'OFFLINE_SCHEDULE'], kinds: ['FORM', 'CONFIG'], patchPaths: ['scheduleType', 'cronExpression', 'timezone', 'enabled', 'concurrencyPolicy', 'maxRetries', 'retryIntervalSeconds', 'parameters', 'executionTimeoutSeconds', 'slaDurationMinutes', 'timeoutPolicy'], types: { scheduleType: 'string', cronExpression: 'string', timezone: 'string', enabled: 'boolean', concurrencyPolicy: 'string', maxRetries: 'number', retryIntervalSeconds: 'number', parameters: 'object', executionTimeoutSeconds: 'number', slaDurationMinutes: 'number', timeoutPolicy: 'string' } },
  'offline-dependencies': { contexts: ['OFFLINE_TASK', 'OFFLINE_SCHEDULE'], kinds: ['FORM', 'CONFIG'], patchPaths: ['dependencies', 'dependencyIds'] },
  'version-note': { contexts: ['OFFLINE_TASK', 'OFFLINE_VERSION'], kinds: ['FORM', 'CONFIG'], patchPaths: ['note', 'versionNote', 'summary'], types: { note: 'string', versionNote: 'string', summary: 'string' } },
  'realtime-table-ddl': { contexts: ['REALTIME_TABLE'], kinds: ['DDL'], content: 'ddl' },
  'realtime-table-form': { contexts: ['REALTIME_TABLE'], kinds: ['FORM', 'CONFIG'], patchPaths: ['catalogName', 'databaseName', 'tableName', 'tableComment', 'comment', 'tableType', 'columns', 'options', 'bucket', 'sinkParallelism', 'changelogProducer', 'consumerExpiration', 'extraOptions'], types: { catalogName: 'string', databaseName: 'string', tableName: 'string', tableComment: 'string', comment: 'string', tableType: 'string', columns: 'array', options: 'object', bucket: 'number', sinkParallelism: 'number', changelogProducer: 'string', consumerExpiration: 'string', extraOptions: 'object' } },
  'realtime-table-safe-update': { contexts: ['REALTIME_TABLE'], kinds: ['FORM', 'CONFIG'], patchPaths: ['comment', 'options', 'addColumns', 'columnComments'], types: { comment: 'string', options: 'object', addColumns: 'array', columnComments: 'array' } },
  'server-form': { contexts: ['REALTIME_SERVER'], kinds: ['FORM', 'CONFIG'], patchPaths: ['name', 'databaseName', 'databasePrefix', 'description'], types: { name: 'string', databaseName: 'string', databasePrefix: 'string', description: 'string' } },
  'sync-task-form': { contexts: ['REALTIME_SYNC_TASK'], kinds: ['FORM', 'CONFIG'], patchPaths: ['name', 'owner', 'description', 'alarmConfig', 'flinkConf'], types: { name: 'string', owner: 'string', description: 'string', alarmConfig: 'object', flinkConf: 'object' } },
  'sync-mapping': { contexts: ['REALTIME_SYNC_TASK'], kinds: ['FORM', 'CONFIG'], patchPaths: ['taskConfig.cdcConfig.selectedTables', 'taskConfig.cdcConfig.tableConfigs', 'tableConfigs', 'selectedTables'], types: { 'taskConfig.cdcConfig.selectedTables': 'array', 'taskConfig.cdcConfig.tableConfigs': 'object', tableConfigs: 'object', selectedTables: 'array' } },
  'sync-config': { contexts: ['REALTIME_SYNC_TASK'], kinds: ['FORM', 'CONFIG'], patchPaths: ['taskConfig.cdcConfig', 'targetDatabase', 'sourceServerId', 'alarmConfig', 'flinkConf'], types: { 'taskConfig.cdcConfig': 'object', targetDatabase: 'string', sourceServerId: 'number', alarmConfig: 'object', flinkConf: 'object' } },
  'flink-sql': { contexts: ['REALTIME_COMPUTE_TASK'], kinds: ['SQL'], content: 'sql' },
  'compute-task-form': { contexts: ['REALTIME_COMPUTE_TASK'], kinds: ['FORM', 'CONFIG'], patchPaths: ['name', 'owner', 'description', 'alarmConfig', 'flinkConf', 'taskConfig.computeConfig.defaultDatabase', 'taskConfig.computeConfig.sql'], types: { name: 'string', owner: 'string', description: 'string', alarmConfig: 'object', flinkConf: 'object', 'taskConfig.computeConfig.defaultDatabase': 'string', 'taskConfig.computeConfig.sql': 'string' } },
  'export-form': { contexts: ['REALTIME_EXPORT_TASK'], kinds: ['FORM', 'CONFIG'], patchPaths: ['name', 'owner', 'description', 'alarmConfig', 'flinkConf', 'taskConfig.exportConfig'], types: { name: 'string', owner: 'string', description: 'string', alarmConfig: 'object', flinkConf: 'object', 'taskConfig.exportConfig': 'object' } },
};

const READ_ONLY_FIELDS = new Set(['id', 'taskId', 'status', 'createTime', 'updateTime', 'latestInstanceId', 'password', 'revision', 'expectedUpdateTime']);

function result(status: ProposalActionResult['status'], message: string, details?: string[]): ProposalActionResult {
  return { status, message, details };
}

function pathMatches(path: string, allowed: string): boolean {
  return path === allowed || path.startsWith(`${allowed}.`) || path.startsWith(`${allowed}[`);
}

function leafPaths(value: unknown, prefix = ''): string[] {
  if (Array.isArray(value)) return value.flatMap((item) => leafPaths(item, `${prefix}[]`));
  if (value && typeof value === 'object') {
    return Object.entries(value as Record<string, unknown>).flatMap(([key, item]) => leafPaths(item, prefix ? `${prefix}.${key}` : key));
  }
  return prefix ? [prefix] : [];
}

function hasReadOnlySegment(path: string): boolean {
  return path.split(/[.[\]]+/).some((segment) => READ_ONLY_FIELDS.has(segment));
}

function valueAtPath(value: Record<string, unknown>, path: string): unknown {
  return path.split('.').reduce<unknown>((current, key) => current && typeof current === 'object'
    ? (current as Record<string, unknown>)[key] : undefined, value);
}

function matchesType(value: unknown, expected: NonNullable<TargetRule['types']>[string]): boolean {
  if (expected === 'array') return Array.isArray(value);
  if (expected === 'object') return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
  return typeof value === expected;
}

function kindOf(proposal: AiProposal): ProposalKind | undefined {
  const kind = proposal.kind?.toUpperCase();
  return ['SQL', 'DDL', 'FORM', 'CONFIG'].includes(kind) ? kind as ProposalKind : undefined;
}

export function inspectProposal(proposal: AiProposal, context: AiContext): ProposalActionResult {
  const rule = RULES[proposal.target];
  if (!rule) return result('UNSUPPORTED', `未登记的建议目标：${proposal.target}`);
  const kind = kindOf(proposal);
  if (!kind || !rule.kinds.includes(kind)) return result('INVALID', `${proposal.target} 不支持 ${proposal.kind} 类型建议`);
  if (!rule.contexts.includes(context.contextType)) return result('UNSUPPORTED', `当前页面不能应用 ${proposal.target} 建议`);
  if (proposal.baseRevision !== undefined && context.revision !== undefined
    && String(proposal.baseRevision) !== String(context.revision)) {
    return result('STALE', '页面内容已变化，建议基线已过期，请重新生成');
  }
  if (rule.content && (typeof proposal.after !== 'string' || !proposal.after.trim())) {
    return result('INVALID', `${rule.content === 'sql' ? 'SQL' : 'DDL'} 建议内容不能为空`);
  }
  if (rule.patchPaths) {
    if (!proposal.patch || typeof proposal.patch !== 'object' || Array.isArray(proposal.patch)) {
      return result('INVALID', '表单建议必须包含字段补丁');
    }
    const paths = leafPaths(proposal.patch);
    const readOnly = paths.filter(hasReadOnlySegment);
    if (readOnly.length) return result('INVALID', '建议包含只读字段，已拒绝', readOnly);
    const denied = paths.filter((path) => !rule.patchPaths!.some((allowed) => pathMatches(path, allowed)));
    if (denied.length) return result('INVALID', '建议包含当前页面未授权字段，已拒绝', denied);
    const typeErrors = Object.entries(rule.types || {}).filter(([path, expected]) => {
      const value = valueAtPath(proposal.patch!, path);
      return value !== undefined && !matchesType(value, expected);
    }).map(([path, expected]) => `${path} 应为 ${expected}`);
    if (typeErrors.length) return result('INVALID', '建议字段类型不匹配，已拒绝', typeErrors);
  }
  return result('APPLIED', '建议目标、字段权限和数据结构检查通过');
}

function mergeObjects<T>(base: T, patch: Record<string, unknown>): T {
  const merge = (left: unknown, right: unknown): unknown => {
    if (!right || typeof right !== 'object' || Array.isArray(right)) return right;
    const source = left && typeof left === 'object' && !Array.isArray(left) ? left as Record<string, unknown> : {};
    return Object.fromEntries(Object.entries(right as Record<string, unknown>).map(([key, value]) => [key, merge(source[key], value)]).concat(
      Object.entries(source).filter(([key]) => !(key in (right as Record<string, unknown>))),
    ));
  };
  return merge(base, patch) as T;
}

function managedCandidate(proposal: AiProposal, context: AiContext, taskType: 'compute' | 'export'): ManagedTaskSave {
  const draft = (context.draft?.config && typeof context.draft.config === 'object'
    ? context.draft.config : {}) as Partial<ManagedTaskSave>;
  const base: ManagedTaskSave = {
    taskId: context.entityId ? Number(context.entityId) : undefined,
    taskType,
    name: draft.name || `Agent ${taskType} validation`, owner: draft.owner || 'agent-validation',
    description: draft.description, flinkVersion: draft.flinkVersion || '2.2.1',
    alarmConfig: draft.alarmConfig || {},
    flinkConf: draft.flinkConf || { parallelism: 1, checkpointIntervalSeconds: 60, taskManagerMemoryGb: 2, jobManagerMemoryGb: 1 },
    taskConfig: draft.taskConfig || (taskType === 'compute'
      ? { computeConfig: { defaultDatabase: String(context.draft?.database || ''), sql: String(context.draft?.sql || '') } }
      : {}),
  };
  if (proposal.target === 'flink-sql') {
    base.taskConfig = { computeConfig: { defaultDatabase: String(context.draft?.database || ''), sql: proposal.after || '' } };
    return base;
  }
  return proposal.patch ? mergeObjects(base, proposal.patch) : base;
}

export async function validateProposal(proposal: AiProposal, context: AiContext): Promise<ProposalActionResult> {
  const inspected = inspectProposal(proposal, context);
  if (inspected.status !== 'APPLIED') return inspected;
  try {
    if (proposal.target === 'offline-sql') {
      await previewSqlStructure({ sql: proposal.after!, parameterSchema: [], validateParameterValues: false });
      return result('APPLIED', 'SQL 结构校验通过；未在 Hive 中执行');
    }
    if (proposal.target === 'offline-ddl') {
      const checked = await validateHiveDdl(proposal.after!);
      return result('APPLIED', `Hive DDL 白名单与目标表检查通过（${checked.affectedTables.length} 张表）；未执行 DDL`);
    }
    if (proposal.target === 'realtime-table-ddl') {
      const parsed = parseRealtimeTableDdl(proposal.after!);
      return result('APPLIED', `Paimon 建表结构检查通过（${parsed.columns?.length || 0} 个字段）；未执行 DDL`);
    }
    if (proposal.target === 'flink-sql' || proposal.target === 'compute-task-form') {
      await analyzeManagedSql(managedCandidate(proposal, context, 'compute'));
      return result('APPLIED', 'Flink SQL Parser/Planner 与受管实时表检查通过；未提交作业');
    }
    if (proposal.target === 'export-form') {
      await validateManagedTask(managedCandidate(proposal, context, 'export'));
      return result('APPLIED', '出仓表单、Paimon/MySQL Schema 与字段映射检查通过；未保存任务、未写数据');
    }
    return result('APPLIED', '字段白名单、只读字段和页面表单契约检查通过；应用时仍需使用原页面保存');
  } catch (error) {
    return result('INVALID', error instanceof Error ? error.message : '建议验证失败');
  }
}

export function recoverProposals(messages: Array<{ steps?: Array<{ kind: string; id?: string; name?: string; input?: unknown; semanticType?: string }> }>): AiProposal[] {
  const byId = new Map<string, AiProposal>();
  messages.forEach((message) => message.steps?.forEach((step) => {
    if (step.kind !== 'tool' || (step.semanticType !== 'proposal' && !step.name?.endsWith('platform_proposal_present'))) return;
    if (!step.input || typeof step.input !== 'object' || Array.isArray(step.input)) return;
    const input = step.input as Record<string, unknown>;
    if (typeof input.target !== 'string' || typeof input.kind !== 'string') return;
    const proposal: AiProposal = { ...(input as unknown as AiProposal), proposalId: step.id };
    byId.set(step.id || `${proposal.target}:${byId.size}`, proposal);
  }));
  return [...byId.values()];
}
