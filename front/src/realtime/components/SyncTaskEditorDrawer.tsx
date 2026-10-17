import { useEffect, useRef, useState, type ReactNode } from 'react';
import {
  Alert,
  Button,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Tabs,
  Tooltip,
  Typography,
  message,
} from 'antd';
import { EditOutlined, EyeOutlined, ReloadOutlined } from '@ant-design/icons';
import {
  createSyncTask,
  getCdcOptions,
  getMysqlSchema,
  listSyncSourceTables,
  listServers,
  getSyncServerAvailability,
  listTaskParams,
  listSyncDdlExceptions,
  previewSyncTask,
  previewSavedSyncTask,
  updateSyncTask,
} from '../api';
import { MYSQL_METADATA_COLUMN_PREFIX, type
  PaimonTablePrefixOption,
  MysqlTableSchema,
  RealtimeServer,
  SyncTask,
  SyncTaskSave,
  TablePrivateConfig,
  TaskParam,
  SyncSourceTableOption,
} from '../types';
import SyncMoreConfigRows from './SyncMoreConfigRows';
import SyncTopologyConfigRows, { mergeSyncTopologyOverrides } from './SyncTopologyConfigRows';
import ComputedColumnEditorModal from './ComputedColumnEditorModal';
import SyncTableOptionsModal from './SyncTableOptionsModal';
import { partitionDefaultsSummary } from './partitionDefaults';
import { timeFilterSummary } from './timeFilter';
import MysqlTableDdlDrawer from './MysqlTableDdlDrawer';
import SyncSectionNav from './SyncSectionNav';
import { snapshotParams } from './syncSnapshotParams';
import { syncPrivateKeyErrors } from './syncPrivateKeys';
import { computedColumnName } from './computedColumns';
import type { AiProposal } from '../../types';
import TaskDevelopmentSteps from '../../components/tasks/TaskDevelopmentSteps';

interface Props {
  open: boolean;
  task?: SyncTask;
  readOnly?: boolean;
  snapshotMode?: boolean;
  snapshotKey?: string;
  snapshotStart?: { startType?: string; statePath?: string; sourceStartupTimestampMillis?: number };
  onClose: () => void;
  onSaved: () => void;
}

const emptyTableConfig = (): TablePrivateConfig => ({ primaryKeys: [], partitionKeys: [], computedColumns: [] });
const sameOrderedValues = (left: string[] = [], right: string[] = []) => left.length === right.length && left.every((value, index) => value === right[index]);
const metadataColumnOptions = ['database_name', 'table_name', 'op_ts'].map((value) => ({ label: value, value }));
const booleanValue = (value: unknown) => value === true || String(value).toLowerCase() === 'true';
export const memoryToGb = (value: unknown) => {
  if (value === undefined || value === null || value === '') return undefined;
  const matched = String(value).trim().match(/^(\d+(?:\.\d+)?)\s*(B|K(?:I?B)?|M(?:I?B)?|G(?:I?B)?|T(?:I?B)?)?$/i);
  if (!matched) return undefined;
  const unit = (matched[2] ?? 'G').toUpperCase();
  const inGb: Record<string, number> = {
    B: 1 / 1024 ** 3,
    K: 1 / 1024 ** 2, KB: 1 / 1024 ** 2, KIB: 1 / 1024 ** 2,
    M: 1 / 1024, MB: 1 / 1024, MIB: 1 / 1024,
    G: 1, GB: 1, GIB: 1,
    T: 1024, TB: 1024, TIB: 1024,
  };
  return Number(matched[1]) * inGb[unit];
};
const MYSQL_SCHEMA_REQUEST_CONCURRENCY = 6;
const SYNC_TOPOLOGY_KEYS = new Set(['bucket', 'sink.parallelism']);

export const createAsyncLimiter = (limit: number) => {
  let active = 0;
  const pending: Array<() => void> = [];
  const runNext = () => {
    if (active >= limit) return;
    const next = pending.shift();
    if (!next) return;
    active += 1;
    next();
  };
  return function limitTask<T>(task: () => Promise<T>): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      pending.push(() => {
        task().then(resolve, reject).finally(() => {
          active -= 1;
          runNext();
        });
      });
      runNext();
    });
  };
};

const platformParamDefaults: Record<string, string> = {
  'table_conf.bucket': '3',
  'table_conf.sink.parallelism': '3',
  'table_conf.changelog-producer': 'input',
  'table_conf.sequence.field': '__meta_op_ts',
  'table_conf.consumer.expiration-time': '1 d',
};

const platformParamLabels: Record<string, string> = {
  'table_conf.bucket': '目标 Paimon 表 Bucket',
  'table_conf.sink.parallelism': '目标 Paimon 表 Sink 并行度',
  'table_conf.changelog-producer': '目标 Paimon 表 Changelog Producer',
  'table_conf.sequence.field': '主键表 Sequence 字段',
  'table_conf.consumer.expiration-time': 'Consumer 过期时间',
};

const paramLabel = (param: TaskParam) => platformParamLabels[`${param.paramType}.${param.paramKey}`]
  ?? param.keyDesc
  ?? param.paramKey;

const paramOptions = (param: TaskParam) => {
  try {
    const value = JSON.parse(param.paramValue ?? '[]') as unknown;
    if (!Array.isArray(value)) return [];
    return value.flatMap((item) => {
      if (item && typeof item === 'object' && 'value' in item) {
        const option = item as { label?: unknown; value?: unknown; default?: unknown };
        if (!['string', 'number', 'boolean'].includes(typeof option.value)) return [];
        return [{ label: String(option.label ?? option.value), value: String(option.value), default: option.default === true }];
      }
      return ['string', 'number', 'boolean'].includes(typeof item)
        ? [{ label: String(item), value: String(item), default: false }] : [];
    });
  } catch { return []; }
};

const paramDefault = (param: TaskParam) => {
  const option = paramOptions(param).find((item) => item.default);
  if (option) return option.value;
  try {
    const value = JSON.parse(param.paramValue ?? '');
    if (['string', 'number', 'boolean'].includes(typeof value)) return String(value);
  } catch {
    if (param.paramValue?.trim()) return param.paramValue.trim();
  }
  return platformParamDefaults[`${param.paramType}.${param.paramKey}`];
};

const paramPath = (param: TaskParam): string[] => param.paramType === 'mysql_conf'
  ? ['taskConfig', 'cdcConfig', 'mysqlConfOverrides', param.paramKey]
  : param.paramType === 'table_conf'
    ? ['taskConfig', 'cdcConfig', 'tableConfOverrides', param.paramKey]
    : ['taskConfig', 'flinkConfOverrides', param.paramKey];

export default function SyncTaskEditorDrawer({ open, task, readOnly = false, snapshotMode = false, snapshotKey, snapshotStart, onSaved }: Props) {
  const [form] = Form.useForm();
  const [servers, setServers] = useState<RealtimeServer[]>([]);
  const [serverAvailability, setServerAvailability] = useState<Awaited<ReturnType<typeof getSyncServerAvailability>>>([]);
  const syncMode = Form.useWatch(['taskConfig', 'syncMode'], form) ?? 'full_incremental';
  const [domains, setDomains] = useState<PaimonTablePrefixOption[]>([]);
  const [params, setParams] = useState<TaskParam[]>([]);
  const [tables, setTables] = useState<SyncSourceTableOption[]>([]);
  const [ddlExceptions, setDdlExceptions] = useState<Array<{ tableName: string; status: string; ddlSql: string }>>([]);
  const [schemas, setSchemas] = useState<Record<string, MysqlTableSchema>>({});
  const [schemaLoadingTables, setSchemaLoadingTables] = useState<Set<string>>(new Set());
  const [schemaErrorTables, setSchemaErrorTables] = useState<Set<string>>(new Set());
  const [schemaReloadRevision, setSchemaReloadRevision] = useState(0);
  const [tableConfigs, setTableConfigs] = useState<Record<string, TablePrivateConfig>>({});
  const [step, setStep] = useState(0);
  const [mode, setMode] = useState<'wizard' | 'advanced'>('wizard');
  const [loading, setLoading] = useState(false);
  const [supportLoading, setSupportLoading] = useState(false);
  const [supportError, setSupportError] = useState('');
  const [metadataLoading, setMetadataLoading] = useState(false);
  const [preview, setPreview] = useState('');
  const [previewLoading, setPreviewLoading] = useState(false);
  const [computedColumnTable, setComputedColumnTable] = useState<string>();
  const [tableOptionsTable, setTableOptionsTable] = useState<string>();
  const [mysqlDdlOpen, setMysqlDdlOpen] = useState(false);
  const [mysqlDdlInitialTable, setMysqlDdlInitialTable] = useState<string>();
  const [defaultProjectId, setDefaultProjectId] = useState<number>();
  const [mysqlServerTimeZone, setMysqlServerTimeZone] = useState('');
  const initializedKeyRef = useRef('');
  const schemasRef = useRef<Record<string, MysqlTableSchema>>({});
  const schemaErrorsRef = useRef<Set<string>>(new Set());
  const schemaSourceRef = useRef('');
  const schemaGenerationRef = useRef(0);
  const selectedTablesRef = useRef<string[]>([]);
  const schemaRequestsRef = useRef(new Map<string, Promise<MysqlTableSchema | undefined>>());
  const [limitSchemaRequest] = useState(() => createAsyncLimiter(MYSQL_SCHEMA_REQUEST_CONCURRENCY));
  const serverId = (Form.useWatch('sourceServerId', form) as number | undefined) ?? task?.sourceServerId;
  const selectedTables = Form.useWatch(['taskConfig', 'cdcConfig', 'selectedTables'], form) as string[] | undefined;
  selectedTablesRef.current = selectedTables ?? [];
  const targetDatabase = Form.useWatch('targetDatabase', form) as string | undefined;
  const domainPrefix = Form.useWatch(['taskConfig', 'cdcConfig', 'domainPrefix'], form) as string | undefined;
  const updateBlocked = Boolean(task?.editPolicy?.updateBlocked || (task?.editPolicy && !task.editPolicy.editable));
  const structureLocked = Boolean(task?.editPolicy?.productionLocked || task?.editPolicy?.structureLocked);
  const lockedTables = task?.editPolicy?.lockedTables ?? [];

  useEffect(() => {
    if (!open || !task?.id || snapshotMode) { setDdlExceptions([]); return; }
    let active = true;
    void listSyncDdlExceptions(task.id).then((value) => { if (active) setDdlExceptions(value); })
      .catch((error) => { if (active) setSupportError(`DDL 异常状态加载失败：${(error as Error).message}`); });
    return () => { active = false; };
  }, [open, task?.id, snapshotMode]);

  useEffect(() => {
    if (!open) return undefined;
    const applyAiProposal = (rawEvent: Event) => {
      if (readOnly || rawEvent.defaultPrevented) return;
      const event = rawEvent as CustomEvent<AiProposal>; const proposal = event.detail; if (!proposal) return;
      const kind = proposal.kind.toUpperCase();
      if ((kind === 'FORM' || kind === 'CONFIG')
        && ['sync-task-form', 'sync-mapping', 'sync-config'].includes(proposal.target) && proposal.patch) {
        form.setFieldsValue(proposal.patch);
        setPreview('');
        event.preventDefault();
      }
    };
    const publishAiContext = () => window.dispatchEvent(new CustomEvent('sql-agent:ai-context-update', {
      detail: {
        contextType: 'REALTIME_SYNC_TASK',
        entityId: task ? String(task.id) : undefined,
        title: form.getFieldValue('name') || (task ? `实时同步任务 #${task.id}` : '新建实时同步任务'),
        revision: task?.updateTime ?? '0',
        draft: { config: form.getFieldsValue(true), tableConfigs },
      },
    }));
    window.addEventListener('sql-agent:apply-ai-proposal', applyAiProposal);
    window.addEventListener('sql-agent:ai-context-request', publishAiContext);
    publishAiContext();
    return () => {
      window.removeEventListener('sql-agent:apply-ai-proposal', applyAiProposal);
      window.removeEventListener('sql-agent:ai-context-request', publishAiContext);
    };
  }, [form, open, readOnly, tableConfigs, task]);

  useEffect(() => {
    if (!open) { initializedKeyRef.current = ''; return; }
    const initializationKey = snapshotMode ? `snapshot-${snapshotKey}` : task ? `task-${task.id}` : 'new';
    if (initializedKeyRef.current === initializationKey) return;
    initializedKeyRef.current = initializationKey;
    setStep(0);
    setMode(snapshotMode ? 'advanced' : 'wizard');
    setPreview('');
    setSupportError('');
    const config = task?.taskConfig;
    const normalizedConfig = snapshotMode ? config : config ? {
      ...config,
      syncMode: config.syncMode ?? 'full_incremental',
      cdcConfig: {
        ...config.cdcConfig,
        ignoreIncompatible: booleanValue(config.cdcConfig.ignoreIncompatible),
        metadataColumns: ['database_name', 'table_name', 'op_ts'],
        tableConfOverrides: mergeSyncTopologyOverrides(
          config.cdcConfig.tableConfOverrides ?? {},
          config.cdcConfig.tableConfOverrides,
          config.parallelism,
        ),
      },
    } : undefined;
    form.setFieldsValue(task ? {
      name: task.name,
      owner: task.owner,
      description: task.description,
      flinkVersion: snapshotMode ? task.flinkVersion : task.flinkVersion || '2.2.1',
      sourceServerId: task.sourceServerId,
      targetDatabase: task.targetDatabase,
      taskConfig: normalizedConfig,
    } : {
      flinkVersion: '2.2.1',
      taskConfig: {
        syncMode: 'full_incremental',
        parallelism: 3,
        taskManagerMemory: '3GB',
        jobManagerMemory: '1GB',
        checkpointInterval: 60,
        alarmType: 'task-failed',
        cdcConfig: {
          selectedTables: [], metadataColumns: ['database_name', 'table_name', 'op_ts'],
          typeMappings: ['tinyint1-not-bool', 'to-nullable'],
          mode: 'combined', ignoreIncompatible: false, mysqlConfOverrides: {},
          tableConfOverrides: mergeSyncTopologyOverrides({}),
        },
        flinkConfOverrides: {},
      },
    });
    setTableConfigs(config?.cdcConfig?.tableConfigs ?? {});
    setComputedColumnTable(undefined);
    if (snapshotMode) {
      setServers(task ? [{ id: task.sourceServerId, name: task.sourceServerName || String(task.sourceServerId),
        type: 'mysql', address: '', databaseName: task.taskConfig.cdcConfig.databaseName, operator: '' }] : []);
      setTables((config?.cdcConfig.selectedTables ?? []).map((table) => ({ tableName: table, occupied: false })));
      setParams(snapshotParams({ mysql_conf: config?.cdcConfig.mysqlConfOverrides,
        table_conf: config?.cdcConfig.tableConfOverrides, flink_conf: config?.flinkConfOverrides }));
      setSupportLoading(false);
      return;
    }
    setSupportLoading(true);
    void Promise.allSettled([listServers(), getCdcOptions(), listTaskParams(), getSyncServerAvailability(task?.id)]).then(([serverResult, optionResult, paramResult, availabilityResult]) => {
      if (availabilityResult.status === 'fulfilled') setServerAvailability(availabilityResult.value);
      else setSupportError('Server 任务限额加载失败，请刷新后重试');
      if (serverResult.status === 'fulfilled') setServers(serverResult.value);
      if (paramResult.status === 'fulfilled') {
        const taskParams = paramResult.value;
        setParams(taskParams);
        taskParams.filter((param) => Boolean(param.required) || param.editable === false).forEach((param) => {
          const path = paramPath(param);
          if (form.getFieldValue(path) === undefined) {
            const value = paramDefault(param);
            if (value !== undefined) form.setFieldValue(path, value);
          }
        });
      } else {
        setSupportError('同步参数元数据加载失败，为避免丢失或覆盖配置，当前不能预览或保存。');
      }
      if (optionResult.status === 'fulfilled') {
        setDomains(optionResult.value.tablePrefixes);
        setDefaultProjectId(optionResult.value.defaultProjectId);
        setMysqlServerTimeZone(optionResult.value.mysqlServerTimeZone ?? '');
        if (!task) {
          form.setFieldValue('targetDatabase', optionResult.value.targetDatabase);
          form.setFieldValue(['taskConfig', 'cdcConfig', 'targetDatabase'], optionResult.value.targetDatabase);
          form.setFieldValue(['taskConfig', 'cdcConfig', 'domainPrefix'], optionResult.value.tablePrefixes[0]?.value);
        }
      }
      const failed = [serverResult, optionResult, paramResult].find((item) => item.status === 'rejected');
      if (failed?.status === 'rejected') message.warning((failed.reason as Error).message);
    }).finally(() => setSupportLoading(false));
  }, [open, task?.id, snapshotKey, snapshotMode, form]);

  useEffect(() => {
    if (!open || snapshotMode) return;
    const selectedServer = servers.find((item) => item.id === serverId);
    const persistedPrefix = task?.taskConfig.cdcConfig.tablePrefix;
    const prefix = (readOnly || structureLocked) && persistedPrefix
      ? persistedPrefix
      : targetDatabase && domainPrefix
        ? [targetDatabase, selectedServer?.databasePrefix, selectedServer?.databaseName, domainPrefix].filter(Boolean).join('_') + '_'
        : '';
    form.setFieldValue(['taskConfig', 'cdcConfig', 'tablePrefix'], prefix);
  }, [domainPrefix, form, open, readOnly, serverId, servers, structureLocked, targetDatabase, task?.taskConfig.cdcConfig.tablePrefix, snapshotMode]);

  useEffect(() => {
    if (snapshotMode) return;
    if (!open || !serverId) {
      setTables([]);
      return;
    }
    const source = servers.find((item) => item.id === serverId);
    if (!source?.databaseName) return;
    form.setFieldValue(['taskConfig', 'cdcConfig', 'databaseName'], source.databaseName);
    setMetadataLoading(true);
    void listSyncSourceTables(serverId, source.databaseName, task?.id).then(setTables).catch((error) => message.error((error as Error).message)).finally(() => setMetadataLoading(false));
  }, [open, serverId, servers, form, task?.id, snapshotMode]);

  const selectedTablesKey = (selectedTables ?? []).join('\u0000');
  useEffect(() => {
    if (!open || snapshotMode || !serverId || !selectedTables?.length) {
      schemaSourceRef.current = '';
      schemaGenerationRef.current += 1;
      schemasRef.current = {};
      schemaErrorsRef.current = new Set();
      setSchemas({});
      setSchemaLoadingTables(new Set());
      setSchemaErrorTables(new Set());
      return;
    }
    const sourceDatabase = servers.find((item) => item.id === serverId)?.databaseName ?? '';
    if (!sourceDatabase) return;
    const sourceKey = `${serverId}\u0000${sourceDatabase}`;
    const sourceChanged = schemaSourceRef.current !== sourceKey;
    if (sourceChanged) {
      schemaSourceRef.current = sourceKey;
      schemaGenerationRef.current += 1;
      schemasRef.current = {};
      schemaErrorsRef.current = new Set();
    }
    const generation = schemaGenerationRef.current;
    const selected = [...selectedTables];
    const selectedSet = new Set(selected);
    const retainedSchemas = sourceChanged ? {} : Object.fromEntries(
      Object.entries(schemasRef.current).filter(([table]) => selectedSet.has(table)),
    );
    const retainedErrors = new Set(sourceChanged ? []
      : [...schemaErrorsRef.current].filter((table) => selectedSet.has(table)));
    schemasRef.current = retainedSchemas;
    schemaErrorsRef.current = retainedErrors;
    setSchemas(retainedSchemas);
    setSchemaErrorTables(retainedErrors);

    const tablesToLoad = selected.filter((table) => !retainedSchemas[table] && !retainedErrors.has(table));
    setSchemaLoadingTables(new Set(tablesToLoad));
    if (!tablesToLoad.length) return;

    void Promise.all(tablesToLoad.map(async (table) => {
      const requestKey = `${generation}\u0000${sourceKey}\u0000${table}`;
      let request = schemaRequestsRef.current.get(requestKey);
      if (!request) {
        request = limitSchemaRequest(async () => {
          if (schemaGenerationRef.current !== generation
            || schemaSourceRef.current !== sourceKey
            || !selectedTablesRef.current.includes(table)) return undefined;
          return getMysqlSchema(serverId, sourceDatabase, table).catch(() => undefined);
        }).finally(() => schemaRequestsRef.current.delete(requestKey));
        schemaRequestsRef.current.set(requestKey, request);
      }
      return [table, await request] as const;
    })).then((rows) => {
      if (schemaGenerationRef.current !== generation || schemaSourceRef.current !== sourceKey) return;
      const currentSelected = new Set(selectedTablesRef.current);
      const nextSchemas = { ...schemasRef.current };
      const nextErrors = new Set(schemaErrorsRef.current);
      rows.forEach(([table, schema]) => {
        if (!currentSelected.has(table)) return;
        if (!schema) {
          nextErrors.add(table);
          return;
        }
        nextSchemas[table] = schema;
        nextErrors.delete(table);
        setTableConfigs((current) => current[table]
          ? current : { ...current, [table]: emptyTableConfig() });
      });
      schemasRef.current = nextSchemas;
      schemaErrorsRef.current = nextErrors;
      setSchemas(nextSchemas);
      setSchemaErrorTables(nextErrors);
    }).finally(() => {
      if (schemaGenerationRef.current !== generation || schemaSourceRef.current !== sourceKey) return;
      setSchemaLoadingTables((current) => {
        const next = new Set(current);
        tablesToLoad.forEach((table) => next.delete(table));
        return next;
      });
    });
  }, [limitSchemaRequest, open, schemaReloadRevision, selectedTablesKey, serverId, servers, snapshotMode]);

  const retrySchema = (table: string) => {
    const next = new Set(schemaErrorsRef.current);
    next.delete(table);
    schemaErrorsRef.current = next;
    setSchemaErrorTables(next);
    setSchemaReloadRevision((current) => current + 1);
  };

  const setPrivate = (table: string, key: keyof TablePrivateConfig, value: string[]) => {
    const next = { ...(tableConfigs[table] ?? emptyTableConfig()), [key]: value };
    if (key === 'partitionKeys' && !sameOrderedValues(value, tableConfigs[table]?.partitionKeys)) {
      delete next.partitionDefaults;
    }
    setTableConfigs((current) => ({ ...current, [table]: next }));
  };

  const buildRequest = async (): Promise<SyncTaskSave> => {
    if (supportError) throw new Error(supportError);
    const values = await form.validateFields();
    const taskConfig = values.taskConfig ?? {};
    for (const table of taskConfig.cdcConfig?.selectedTables ?? []) {
      const configured = tableConfigs[table] ?? emptyTableConfig();
      const errors = syncPrivateKeyErrors(table, configured, schemas[table], taskConfig.cdcConfig?.tableConfOverrides ?? {}, taskConfig.cdcConfig?.typeMappings ?? []);
      if (taskConfig.syncMode === 'incremental' && configured.timeFilter) throw new Error(table + '：历史快照时间过滤仅支持全量任务，请清除时间过滤');
      if (errors.length) throw new Error(errors[0]);
    }
    taskConfig.sourceServerId = values.sourceServerId;
    const normalizedTableConfigs = Object.fromEntries((taskConfig.cdcConfig?.selectedTables ?? []).flatMap((name: string) => {
      if (lockedTables.includes(name) && task?.editPolicy?.lockedTableConfigs?.[name]) {
        return [[name, task.editPolicy.lockedTableConfigs[name]]];
      }
      const current = tableConfigs[name] ?? emptyTableConfig();
      const normalized: TablePrivateConfig = {};
      if (current.bucket !== undefined) normalized.bucket = current.bucket;
      if (current.primaryKeys?.length && !sameOrderedValues(current.primaryKeys, schemas[name]?.primaryKeys)) normalized.primaryKeys = current.primaryKeys;
      if (current.partitionKeys?.length) normalized.partitionKeys = current.partitionKeys;
      if (current.computedColumns?.length) normalized.computedColumns = current.computedColumns;
      if (current.partitionDefaults) normalized.partitionDefaults = current.partitionDefaults;
      if (current.timeFilter) normalized.timeFilter = current.timeFilter;
      return Object.keys(normalized).length ? [[name, normalized]] : [];
    }));
    taskConfig.cdcConfig = {
      ...(taskConfig.cdcConfig ?? {}),
      targetDatabase: values.targetDatabase,
      tableConfigs: normalizedTableConfigs,
      tableConfOverrides: mergeSyncTopologyOverrides(
        taskConfig.cdcConfig?.tableConfOverrides ?? {},
        taskConfig.cdcConfig?.tableConfOverrides,
        taskConfig.parallelism,
      ),
    };
    return {
      projectId: task?.projectId ?? defaultProjectId,
      name: values.name,
      owner: values.owner,
      description: values.description,
      flinkVersion: values.flinkVersion,
      sourceServerId: values.sourceServerId,
      sourceType: 'mysql-cdc',
      targetDatabase: values.targetDatabase,
      taskConfig,
      expectedUpdateTime: task?.updateTime,
    };
  };

  const save = async () => {
    if (readOnly) return;
    setLoading(true);
    try {
      const request = await buildRequest();
      if (task) await updateSyncTask(task.id, request); else await createSyncTask(request);
      message.success(task ? '同步任务已更新' : '同步任务已创建');
      onSaved();
    } catch (error) {
      message.error((error as Error).message);
    } finally {
      setLoading(false);
    }
  };

  const commandPreview = async () => {
    setPreviewLoading(true);
    try {
      const result = readOnly && task
        ? await previewSavedSyncTask(task.id)
        : await previewSyncTask(await buildRequest(), task?.id);
      setPreview(result.command);
    } catch (error) {
      message.error((error as Error).message);
    } finally {
      setPreviewLoading(false);
    }
  };

  const fieldLabel = (label: string, required = false) => (
    <span className={required ? 'realtime-required-label' : undefined}>{label}</span>
  );

  const commonFields = (
    <div className="realtime-editor-section">
      <Descriptions bordered size="small" column={2} colon={false} className="realtime-config-table">
        <Descriptions.Item label={fieldLabel('任务名称', true)}><Form.Item name="name" rules={[{ required: true, message: '请输入任务名称' }]} noStyle><Input placeholder="请输入同步任务名称" /></Form.Item></Descriptions.Item>
        <Descriptions.Item label={fieldLabel('负责人', true)}><Form.Item name="owner" rules={[{ required: true, whitespace: true, message: '请输入负责人' }]} noStyle><Input placeholder="请输入负责人" /></Form.Item></Descriptions.Item>
        <Descriptions.Item label="描述" span={2}><Form.Item name="description" noStyle><Input.TextArea rows={3} placeholder="请输入任务用途、数据范围或其他说明" /></Form.Item></Descriptions.Item>
        <Descriptions.Item label="Flink 版本" span={2}><Form.Item name="flinkVersion" noStyle><Select disabled options={[{ label: '2.2.1', value: '2.2.1' }]} /></Form.Item></Descriptions.Item>
      </Descriptions>
    </div>
  );

  const alertFields = (
    <div className="realtime-editor-section">
      <Descriptions bordered size="small" column={2} colon={false} className="realtime-config-table">
        <Descriptions.Item label="报警设置类型"><Form.Item name={['taskConfig', 'alarmType']} noStyle><Select options={[{ label: '任务失败', value: 'task-failed' }]} /></Form.Item></Descriptions.Item>
        <Descriptions.Item label={fieldLabel('告警组', true)}><Form.Item name={['taskConfig', 'alarmGroup']} noStyle><Input placeholder="输入告警接收组" /></Form.Item></Descriptions.Item>
      </Descriptions>
    </div>
  );

  const mappingColumns = [
    {
      title: '源表', dataIndex: 'table', width: 180,
      render: (table: string) => snapshotMode ? <Typography.Text>{table}</Typography.Text>
        : <Typography.Link onClick={() => {
          setMysqlDdlInitialTable(table); setMysqlDdlOpen(true);
        }}>{table}</Typography.Link>,
    },
    {
      title: '表结构',
      width: 96,
      render: (_: unknown, row: { table: string }) => {
        if (snapshotMode) return <Tag>历史快照</Tag>;
        if (schemaLoadingTables.has(row.table)) return <Tag>读取中</Tag>;
        if (schemaErrorTables.has(row.table)) {
          return <Space direction="vertical" size={2}>
            <Tag color="error">读取失败</Tag>
            <Button size="small" onClick={() => retrySchema(row.table)}>重试</Button>
          </Space>;
        }
        return schemas[row.table] ? <Tag color="success">已读取</Tag> : <Tag>待读取</Tag>;
      },
    },
    {
      title: '计算列',
      width: 400,
      render: (_: unknown, row: { table: string }) => {
        const expressions = tableConfigs[row.table]?.computedColumns ?? [];
        return <div className="mysql-private-computed-columns">
          <div className="mysql-private-value-list">
            {expressions.length
              ? expressions.map((expression) => <Typography.Text key={expression} ellipsis={{ tooltip: expression }}>{expression}</Typography.Text>)
              : <Typography.Text type="secondary">未配置</Typography.Text>}
          </div>
          <Button aria-label={`配置 ${row.table} 计算列`} icon={<EditOutlined />} size="small" disabled={readOnly || lockedTables.includes(row.table)} onClick={() => setComputedColumnTable(row.table)} />
        </div>;
      },
    },
    {
      title: '主键',
      render: (_: unknown, row: { table: string }) => {
        const configured = tableConfigs[row.table]?.primaryKeys ?? [];
        const inherited = schemas[row.table]?.primaryKeys ?? [];
        const computedOptions = (tableConfigs[row.table]?.computedColumns ?? []).map(computedColumnName).filter((name): name is string => Boolean(name));
        return <Select disabled={readOnly || lockedTables.includes(row.table)} mode="multiple" value={configured.length ? configured : inherited} onChange={(value) => setPrivate(row.table, 'primaryKeys', value)} options={[
          ...(schemas[row.table]?.columns ?? []).map((column) => ({ label: `${column.name} [${column.type}]`, value: column.name })),
          ...computedOptions.map((name) => ({ label: `${name} [计算列]`, value: name })),
        ]} style={{ width: '100%' }} />;
      },
    },
    {
      title: '分区键',
      render: (_: unknown, row: { table: string }) => {
        const primaryKeys = tableConfigs[row.table]?.primaryKeys?.length ? tableConfigs[row.table].primaryKeys! : schemas[row.table]?.primaryKeys ?? [];
        return <Select disabled={readOnly || lockedTables.includes(row.table)} mode="multiple" value={tableConfigs[row.table]?.partitionKeys} onChange={(value) => setPrivate(row.table, 'partitionKeys', value)} options={primaryKeys.map((name) => ({ label: name, value: name }))} style={{ width: '100%' }} />;
      },
    },
    {
      title: '逐表配置', width: 240,
      render: (_: unknown, row: { table: string }) => <Space direction="vertical" size={2}>
        <Typography.Text type="secondary">Bucket：{tableConfigs[row.table]?.bucket ?? '继承'} · 默认分区：{partitionDefaultsSummary(tableConfigs[row.table])}</Typography.Text>
        <Typography.Text type="secondary">快照过滤：{timeFilterSummary(tableConfigs[row.table]?.timeFilter)}</Typography.Text>
        <Button size="small" aria-label={`配置 ${row.table} 同步选项`} disabled={readOnly || lockedTables.includes(row.table) || !schemas[row.table]}
          onClick={() => setTableOptionsTable(row.table)}>配置</Button>
      </Space>,
    },
  ];

  function dynamicParam(param: TaskParam, disabled = false) {
    const path = paramPath(param);
    const options = paramOptions(param);
    const numericRule = {
      validator: (_: unknown, input: unknown) => {
        if (input === undefined || input === null || input === '') return Promise.resolve();
        const value = Number(input);
        const label = paramLabel(param);
        if (!Number.isFinite(value)) return Promise.reject(new Error(`${label}必须是数字`));
        if (param.minValue !== undefined && value < param.minValue) return Promise.reject(new Error(`${label}必须大于等于 ${param.minValue}`));
        if (param.maxValue !== undefined && value > param.maxValue) return Promise.reject(new Error(`${label}必须小于等于 ${param.maxValue}`));
        if (param.precisionValue !== undefined && (String(input).split('.')[1]?.length ?? 0) > param.precisionValue) return Promise.reject(new Error(`${label}最多保留 ${param.precisionValue} 位小数`));
        if (param.stepValue && Math.abs(((value - (param.minValue ?? 0)) / param.stepValue) - Math.round((value - (param.minValue ?? 0)) / param.stepValue)) > 1e-9) return Promise.reject(new Error(`${label}必须按步长 ${param.stepValue} 递增`));
        return Promise.resolve();
      },
    };
    const label = paramLabel(param);
    const rules = [...(param.required ? [{ required: true, message: `请输入${label}` }] : []), ...(param.inputType === 'input_number' ? [numericRule] : [])];
    return (
      <Form.Item key={`${param.paramType}-${param.paramKey}`} name={path} label={label} tooltip={param.paramKey} rules={rules.length ? rules : undefined} className="realtime-dynamic-param">
        {param.inputType === 'select' && options.length
          ? <Select disabled={readOnly || disabled || param.editable === false} allowClear options={options} />
          : param.inputType === 'switch'
            ? <Select disabled={readOnly || disabled || param.editable === false} options={[{ label: '否', value: 'false' }, { label: '是', value: 'true' }]} />
            : param.inputType === 'input_number'
              ? <InputNumber disabled={readOnly || disabled || param.editable === false} min={param.minValue} max={param.maxValue} step={param.stepValue} precision={param.precisionValue} style={{ width: '100%' }} />
              : <Input disabled={readOnly || disabled || param.editable === false} placeholder={param.paramKey} />}
      </Form.Item>
    );
  }

  const sourceConfigFields = (
    <div className="realtime-editor-section">
      {mode === 'wizard' && <div className="realtime-subsection-heading">
        <span>源端配置</span>
      </div>}
      <Descriptions bordered size="small" column={2} colon={false} className="realtime-config-table realtime-sync-endpoint-table">
        <Descriptions.Item label="源端类型"><Select disabled value="mysql-cdc" options={[{ label: 'MySQL CDC', value: 'mysql-cdc' }]} /></Descriptions.Item>
        <Descriptions.Item label={fieldLabel('Server', true)}>
          {readOnly || structureLocked
            ? <><Form.Item name="sourceServerId" hidden rules={[{ required: true, message: '请选择 Server' }]}><Input /></Form.Item>
                <Input disabled value={task?.sourceServerName ?? servers.find((item) => item.id === serverId)?.name ?? ''} /></>
            : <Form.Item name="sourceServerId" rules={[{ required: true, message: '请选择 Server' }]} noStyle>
                <Select showSearch optionFilterProp="label" placeholder="请选择 Server" options={servers.map((item) => {
                  const capacity = serverAvailability.find(row => row.serverId === item.id && row.syncMode === syncMode);
                  return { label: capacity ? `${item.name}（${syncMode === 'incremental' ? '增量' : '全量'} ${capacity.currentTaskCount}/${capacity.maxTaskCount}）` : item.name,
                    value: item.id, disabled: !capacity?.available };
                })} />
              </Form.Item>}
        </Descriptions.Item>
      </Descriptions>
    </div>
  );

  const activeDdlTables = [...new Set(ddlExceptions.filter((item) => item.status === 'ACTIVE')
    .map((item) => item.tableName))];
  const targetConfigFields = (
    <div className="realtime-editor-section">
      {mode === 'wizard' && <div className="realtime-subsection-heading">
        <span>公共配置</span>
      </div>}
      <Descriptions bordered size="small" column={1} colon={false} className="realtime-config-table realtime-sync-common-table">
        <Descriptions.Item label="同步类型"><Form.Item name={['taskConfig', 'syncMode']} noStyle><Select disabled={readOnly || structureLocked}
          onChange={mode => {
            if (mode === 'incremental') setTableConfigs(current => Object.fromEntries(Object.entries(current).map(([name, value]) => [name, { ...value, timeFilter: undefined }])));
            if (!serverAvailability.find(row => row.serverId === serverId && row.syncMode === mode)?.available) form.setFieldValue('sourceServerId', undefined);
          }}
          options={[{ label: '全量', value: 'full_incremental' }, { label: '增量', value: 'incremental' }]} /></Form.Item></Descriptions.Item>
        {!snapshotMode && activeDdlTables.length > 0 && <Descriptions.Item label="DDL 异常">
          <Alert type="error" showIcon message="以下源表发生破坏性 DDL，请剔除后保存配置"
            description={<Space wrap>{activeDdlTables.map((tableName) =>
              <Button key={tableName} danger size="small" disabled={!selectedTables?.includes(tableName)}
                onClick={() => form.setFieldValue(['taskConfig', 'cdcConfig', 'selectedTables'], (selectedTables ?? []).filter((table) => table !== tableName))}>
                剔除 {tableName}</Button>)}</Space>} />
        </Descriptions.Item>}
        <Descriptions.Item label={fieldLabel('源库', true)}><Form.Item name={['taskConfig', 'cdcConfig', 'databaseName']} noStyle><Select disabled placeholder="选择 Server 后自动获取" options={servers.filter((item) => item.id === serverId).map((item) => ({ label: item.databaseName, value: item.databaseName }))} /></Form.Item></Descriptions.Item>
        <Descriptions.Item label={fieldLabel('源表列表', true)}>
          <div className="realtime-sync-source-tables">
            <Space.Compact block>
            <Form.Item name={['taskConfig', 'cdcConfig', 'selectedTables']} rules={[{ required: true, message: '至少选择一张源表' }]} noStyle>
              <Select mode="multiple" showSearch loading={metadataLoading} placeholder="请选择需要同步的源表" options={tables.map((item) => {
                const reasons = [item.occupied ? `已被任务 #${item.occupiedTaskId} ${item.occupiedTaskName ?? ''} 使用`.trim() : '',
                  item.hasPrimaryKey === false ? '无物理主键' : '',
                  ddlExceptions.some((entry) => entry.status === 'ACTIVE' && entry.tableName === item.tableName) ? 'DDL 异常' : ''].filter(Boolean);
                return { label: reasons.length ? `${item.tableName}（${reasons.join('；')}）` : item.tableName,
                  value: item.tableName, disabled: reasons.length > 0 && !(selectedTables ?? []).includes(item.tableName), title: reasons.join('；') };
              })} maxTagCount="responsive" />
            </Form.Item>
            {!readOnly && <Button aria-label="刷新源表列表" title="刷新源表列表" icon={<ReloadOutlined />} loading={metadataLoading} onClick={async () => {
              if (!serverId) return;
              const source = servers.find((item) => item.id === serverId);
              try { setMetadataLoading(true); setTables(await listSyncSourceTables(serverId, source?.databaseName ?? '', task?.id)); }
              catch (error) { message.error((error as Error).message); }
              finally { setMetadataLoading(false); }
            }} />}
            </Space.Compact>
            <Button type="link" icon={<EyeOutlined />} disabled={!serverId || snapshotMode} onClick={() => {
              setMysqlDdlInitialTable(selectedTables?.[0] ?? tables[0]?.tableName); setMysqlDdlOpen(true);
            }}>查看源表 DDL</Button>
          </div>
        </Descriptions.Item>
        <Descriptions.Item label={fieldLabel('目标Paimon库', true)}><Form.Item name="targetDatabase" rules={[{ required: true }]} noStyle><Input disabled placeholder="ods_rt" /></Form.Item></Descriptions.Item>
        <Descriptions.Item label={fieldLabel('目标Paimon表所属域', true)}><Form.Item name={['taskConfig', 'cdcConfig', 'domainPrefix']} rules={[{ required: true }]} noStyle><Select disabled={readOnly || structureLocked} placeholder="请选择业务域" options={domains} /></Form.Item></Descriptions.Item>
        <Descriptions.Item label={fieldLabel('目标Paimon表前缀', true)}><Form.Item name={['taskConfig', 'cdcConfig', 'tablePrefix']} rules={[{ required: true, message: '请选择业务域以生成目标Paimon表前缀' }]} noStyle><Input disabled placeholder="目标Paimon库_[库前缀_]库名_业务域_" /></Form.Item></Descriptions.Item>
        <Descriptions.Item label="目标Paimon表列表"><Input.TextArea disabled value={snapshotMode
          ? (task?.taskConfig.cdcConfig.targetTableList ?? []).join('\n')
          : (selectedTables ?? []).map((table) => `${form.getFieldValue(['taskConfig', 'cdcConfig', 'tablePrefix']) || ''}${table}`).join('\n')} autoSize={{ minRows: 2, maxRows: 6 }} /></Descriptions.Item>
        <Descriptions.Item label="目标Paimon表同步元数据列"><Form.Item name={['taskConfig', 'cdcConfig', 'metadataColumns']} noStyle><Select disabled mode="multiple" options={metadataColumnOptions} placeholder="固定同步元数据列" /></Form.Item></Descriptions.Item>
        <Descriptions.Item label="目标Paimon表同步元数据列前缀"><Input value={MYSQL_METADATA_COLUMN_PREFIX} disabled /></Descriptions.Item>
        <Descriptions.Item label="目标Paimon表类型映射"><Form.Item name={['taskConfig', 'cdcConfig', 'typeMappings']} noStyle><Select disabled={readOnly || structureLocked} mode="multiple" options={['to-nullable', 'to-string', 'char-to-string', 'tinyint1-not-bool', 'longtext-to-bytes', 'bigint-unsigned-to-bigint'].map((value) => ({ label: value, value }))} /></Form.Item></Descriptions.Item>
        <Descriptions.Item label="目标Paimon表配置">
          <div className="realtime-dynamic-param-grid">
            <SyncTopologyConfigRows
              tableConfPath={['taskConfig', 'cdcConfig', 'tableConfOverrides']}
              parallelismPath={['taskConfig', 'parallelism']}
              flinkConfPath={['taskConfig', 'flinkConfOverrides']}
              readOnly={readOnly || structureLocked}
              frozenParallelism={structureLocked ? task?.taskConfig.parallelism : undefined}
            />
            {params.filter((item) => item.paramType === 'table_conf' && Boolean(item.required)
              && !SYNC_TOPOLOGY_KEYS.has(item.paramKey)).map((item) => dynamicParam(item, structureLocked))}
          </div>
          <SyncMoreConfigRows paramType="table_conf" formNamePath={['taskConfig', 'cdcConfig', 'tableConfOverrides']} taskParams={params} readOnly={readOnly || structureLocked} />
        </Descriptions.Item>
        <Descriptions.Item label="MySQL 配置">
          <div className="realtime-dynamic-param-grid">{params.filter((item) => item.paramType === 'mysql_conf' && Boolean(item.required)).map((item) => dynamicParam(item, structureLocked))}</div>
          <SyncMoreConfigRows paramType="mysql_conf" formNamePath={['taskConfig', 'cdcConfig', 'mysqlConfOverrides']} taskParams={params} readOnly={readOnly || structureLocked} />
        </Descriptions.Item>
        <Descriptions.Item label="整库模式"><Form.Item name={['taskConfig', 'cdcConfig', 'mode']} noStyle><Select disabled options={[{ label: 'combined（固定）', value: 'combined' }]} /></Form.Item></Descriptions.Item>
      </Descriptions>
    </div>
  );

  const mappingFields = (
    <div className="realtime-editor-section">
      {mode === 'wizard' && <div className="realtime-subsection-heading">
        <span>私有配置</span>
      </div>}
      <div className="realtime-mapping-table">
        <Table size="small" pagination={false} rowKey="table" dataSource={(selectedTables ?? []).map((table) => ({ table }))} columns={mappingColumns} scroll={{ x: 1100 }} />
      </div>
    </div>
  );

  const previewFields = (
    <div className="realtime-editor-section">
      <div className="task-command-preview realtime-command-preview-section">
        <Button type="link" icon={<EyeOutlined />} disabled={snapshotMode} loading={previewLoading} onClick={() => void commandPreview()}>生成预览</Button>
        <Input.TextArea
          className="task-command-preview-textarea"
          value={preview}
          placeholder={snapshotMode ? '历史快照不重新生成命令' : '点击生成预览，查看 Paimon Action 等价命令'}
          readOnly
          rows={8}
          wrap="off"
        />
      </div>
    </div>
  );

  const sourceFields = <>{sourceConfigFields}{targetConfigFields}{mappingFields}{previewFields}</>;

  const runtimeFields = (
    <div className="realtime-editor-section">
      <Descriptions bordered size="small" column={2} colon={false} className="realtime-config-table realtime-sync-runtime-table">
        <Descriptions.Item label={<Tooltip title="由目标 Paimon 表 Sink 并行度统一决定">{fieldLabel('任务并行度', true)}</Tooltip>}><Form.Item name={['taskConfig', 'parallelism']} rules={[{ required: true, message: '请输入任务并行度' }, { type: 'number', min: 1, max: 4, message: '同步任务并行度必须在 1-4 之间' }]} noStyle><InputNumber min={1} max={4} disabled style={{ width: '100%' }} /></Form.Item></Descriptions.Item>
        <Descriptions.Item label={fieldLabel('TaskManager 内存', true)}><Form.Item name={['taskConfig', 'taskManagerMemory']} getValueProps={(value) => ({ value: memoryToGb(value) })} normalize={(value) => value === undefined || value === null || value === '' ? undefined : `${Number(value)}GB`} rules={[{ required: true }]} noStyle><InputNumber min={1} max={20} addonAfter="GB" style={{ width: '100%' }} /></Form.Item></Descriptions.Item>
        <Descriptions.Item label={fieldLabel('JobManager 内存', true)}><Form.Item name={['taskConfig', 'jobManagerMemory']} getValueProps={(value) => ({ value: memoryToGb(value) })} normalize={(value) => value === undefined || value === null || value === '' ? undefined : `${Number(value)}GB`} rules={[{ required: true }]} noStyle><InputNumber min={1} max={20} addonAfter="GB" style={{ width: '100%' }} /></Form.Item></Descriptions.Item>
        <Descriptions.Item label={fieldLabel('Checkpoint 间隔', true)}><Form.Item name={['taskConfig', 'checkpointInterval']} rules={[{ required: true, message: '请输入 Checkpoint 间隔' }, { type: 'number', min: 10, max: 3600, message: 'Checkpoint 间隔必须在 10-3600 秒之间' }]} noStyle><InputNumber min={10} max={3600} addonAfter="秒" style={{ width: '100%' }} /></Form.Item></Descriptions.Item>
        <Descriptions.Item label="Flink配置" span={2}>
          <div className="realtime-dynamic-param-grid">{params.filter((item) => item.paramType === 'flink_conf' && Boolean(item.required)).map((item) => dynamicParam(item))}</div>
          <SyncMoreConfigRows paramType="flink_conf" formNamePath={['taskConfig', 'flinkConfOverrides']} taskParams={params} readOnly={readOnly} />
        </Descriptions.Item>
      </Descriptions>
    </div>
  );

  const startupFields = <div className="realtime-editor-section"><Descriptions bordered size="small" column={2}
    colon={false} className="realtime-config-table">
    <Descriptions.Item label="启动方式"><Input disabled value={snapshotStart?.startType ?? ''} /></Descriptions.Item>
    <Descriptions.Item label="历史状态"><Input disabled value={snapshotStart?.statePath ?? ''} /></Descriptions.Item>
    {snapshotStart?.sourceStartupTimestampMillis != null && <Descriptions.Item label="启动时间戳">
      <Input disabled value={snapshotStart.sourceStartupTimestampMillis} /></Descriptions.Item>}
  </Descriptions></div>;

  const wizardSections = [
    { title: '基础信息', description: '定义同步任务的名称、负责人和基础属性', content: commonFields },
    { title: '告警配置', description: '配置同步任务异常时的告警方式', content: alertFields },
    { title: '入仓配置', description: '选择源表并配置 Paimon 目标与表映射', content: sourceFields },
    { title: '运行资源', description: '统一配置实时任务运行所需的 Flink 资源与参数', content: runtimeFields },
  ];

  const advancedSections = [
    { key: 'basic', title: '基础信息', content: commonFields },
    { key: 'alert', title: '告警配置', content: alertFields },
    { key: 'mysql', title: '源端配置', description: '展示 MySQL CDC 源端类型和 Server，源库由 Server 配置自动带出。', content: sourceConfigFields },
    { key: 'target', title: '公共配置', description: '配置同步源表、MySQL CDC 参数、目标 Paimon 表结构及同步参数。', content: targetConfigFields },
    { key: 'mapping', title: '私有配置', description: '按表覆盖计算列、主键和分区键，未配置项继承源表。', content: mappingFields },
    ...(snapshotMode && snapshotStart ? [{ key: 'startup', title: '消费点配置', content: startupFields }] : []),
    { key: 'runtime', title: '运行资源', description: '统一配置实时任务运行所需的 Flink 资源与参数', content: runtimeFields },
    { key: 'command', title: '命令预览', content: previewFields },
  ];

  const sectionContent = (section: { title: string; description?: string; content: ReactNode }) => (
    <div className="realtime-editor-panel">
      <div className="realtime-editor-panel-title">
        <Typography.Title level={5}>{section.title}</Typography.Title>
        {section.description && <Typography.Text type="secondary">{section.description}</Typography.Text>}
      </div>
      {section.content}
    </div>
  );

  return (<>
    <div className="realtime-sync-editor-page realtime-editor-page-compact">
      <div className={`realtime-sync-editor-page-body realtime-editor-page-body-compact mode-${mode}`}>
        <Spin spinning={supportLoading}>
        {supportError && <Alert type="error" showIcon message={supportError} className="realtime-editor-warning" />}
        {!readOnly && task?.editPolicy && !task.editPolicy.editable && <Alert type="warning" showIcon message={task.editPolicy.reason} className="realtime-editor-warning" />}
        {!readOnly && structureLocked && !updateBlocked && <Alert type="info" showIcon message="该任务已有生产实例，保留表的私有配置以及源端、目标 Paimon 公共结构配置不可修改；仍可新增或移除源表，并调整告警及运行资源。" className="realtime-editor-warning" />}
        {!snapshotMode && <Tabs
          className="realtime-editor-mode-tabs ui-flat-tabs"
          activeKey={mode}
          items={[{ key: 'wizard', label: '分步向导' }, { key: 'advanced', label: '高级配置' }]}
          onChange={(value) => setMode(value as 'wizard' | 'advanced')}
        />}
        <Form className={`realtime-editor-form mode-${mode}`} form={form} layout="vertical" preserve disabled={readOnly || updateBlocked || Boolean(supportError)} onValuesChange={() => { if (preview) setPreview(''); }}>
          {mode === 'wizard' ? (
            <div className="realtime-wizard">
              <TaskDevelopmentSteps className="realtime-wizard-steps" collapsed={false} hideToggle current={step} items={wizardSections.map((item) => ({ title: item.title }))} onChange={setStep} />
              <div className="realtime-wizard-content">
                {sectionContent(wizardSections[step])}
                <div className="realtime-wizard-actions">
                  <Button disabled={step === 0} onClick={() => setStep((value) => value - 1)}>上一步</Button>
                  {step < wizardSections.length - 1 && <Button type="primary" disabled={false} onClick={() => readOnly ? setStep((value) => value + 1) : void form.validateFields().then(() => setStep((value) => value + 1))}>下一步</Button>}
                  {!readOnly && <Button type="primary" disabled={updateBlocked} loading={loading} onClick={() => void save()}>{task ? '保存修改' : '保存'}</Button>}
                </div>
              </div>
            </div>
          ) : (
            <div className="sync-advanced-layout sync-task-advanced-layout">
              <SyncSectionNav prefix="sync-advanced-section" items={[
                { key: 'basic', label: '基础信息' },
                { key: 'alert', label: '告警配置' },
                { key: 'ingest', label: '入仓配置', children: [
                  { key: 'mysql', label: '源端配置' },
                  { key: 'target', label: '公共配置' },
                  { key: 'mapping', label: '私有配置' },
                ] },
                ...(snapshotMode && snapshotStart ? [{ key: 'startup', label: '消费点配置' }] : []),
                { key: 'runtime', label: '运行资源' },
                { key: 'command', label: '命令预览' },
              ]} />
              <div className="realtime-advanced-content">
                {advancedSections.map((item) => (
                  <section id={`sync-advanced-section-${item.key}`} className="realtime-advanced-section sync-config-anchor-section" key={item.key}>
                    {sectionContent(item)}
                  </section>
                ))}
                {!readOnly && <div className="realtime-advanced-actions">
                  <Button type="primary" disabled={updateBlocked} loading={loading} onClick={() => void save()}>{task ? '保存修改' : '保存'}</Button>
                </div>}
              </div>
            </div>
          )}
        </Form>
        </Spin>
      </div>
    </div>
    {!readOnly && <ComputedColumnEditorModal
      open={Boolean(computedColumnTable)}
      tableName={computedColumnTable}
      expressions={computedColumnTable ? tableConfigs[computedColumnTable]?.computedColumns ?? [] : []}
      columns={computedColumnTable ? schemas[computedColumnTable]?.columns ?? [] : []}
      typeMappings={form.getFieldValue(['taskConfig', 'cdcConfig', 'typeMappings']) ?? []}
      protectedKeys={computedColumnTable ? [
        ...(tableConfigs[computedColumnTable]?.primaryKeys?.length
          ? tableConfigs[computedColumnTable].primaryKeys!
          : schemas[computedColumnTable]?.primaryKeys ?? []),
        ...(tableConfigs[computedColumnTable]?.partitionKeys ?? []),
      ] : []}
      onSave={(expressions) => {
        if (computedColumnTable) setPrivate(computedColumnTable, 'computedColumns', expressions);
        setComputedColumnTable(undefined);
        setPreview('');
      }}
      onCancel={() => setComputedColumnTable(undefined)}
    />}
    <SyncTableOptionsModal table={tableOptionsTable} config={tableOptionsTable ? tableConfigs[tableOptionsTable] : undefined}
      syncMode={form.getFieldValue(['taskConfig', 'syncMode'])}
      typeMappings={form.getFieldValue(['taskConfig', 'cdcConfig', 'typeMappings']) ?? []}
      schema={tableOptionsTable ? schemas[tableOptionsTable] : undefined} disabled={readOnly}
      timeZone={form.getFieldValue(['taskConfig', 'cdcConfig', 'mysqlConfOverrides', 'server-time-zone']) || mysqlServerTimeZone}
      onCancel={() => setTableOptionsTable(undefined)} onSave={(changes) => {
        if (tableOptionsTable) setTableConfigs((current) => ({ ...current,
          [tableOptionsTable]: { ...(current[tableOptionsTable] ?? emptyTableConfig()), ...changes } }));
        setTableOptionsTable(undefined);
        setPreview('');
      }} />
    <MysqlTableDdlDrawer
      open={mysqlDdlOpen}
      serverId={serverId}
      tables={tables}
      selectedTables={selectedTables ?? []}
      initialTable={mysqlDdlInitialTable}
      onClose={() => setMysqlDdlOpen(false)}
    />
  </>);
}
