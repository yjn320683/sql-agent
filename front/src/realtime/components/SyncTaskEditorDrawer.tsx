import { useEffect, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Collapse,
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
  listTaskParams,
  previewSyncTask,
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
import MysqlTableDdlDrawer from './MysqlTableDdlDrawer';
import SyncSectionNav from './SyncSectionNav';
import { computedColumnName } from './computedColumns';
import type { AiProposal } from '../../types';
import TaskDevelopmentSteps, { taskStepsCollapsedKey } from '../../components/tasks/TaskDevelopmentSteps';

interface Props {
  open: boolean;
  task?: SyncTask;
  onClose: () => void;
  onSaved: () => void;
}

const emptyTableConfig = (): TablePrivateConfig => ({ primaryKeys: [], partitionKeys: [], computedColumns: [] });
const sameOrderedValues = (left: string[] = [], right: string[] = []) => left.length === right.length && left.every((value, index) => value === right[index]);
const metadataColumnOptions = ['database_name', 'table_name', 'op_ts'].map((value) => ({ label: value, value }));
const booleanValue = (value: unknown) => value === true || String(value).toLowerCase() === 'true';
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
  'table_conf.consumer.expiration-time': '1 d',
};

const platformParamLabels: Record<string, string> = {
  'table_conf.bucket': '目标 Paimon 表 Bucket',
  'table_conf.sink.parallelism': '目标 Paimon 表 Sink 并行度',
  'table_conf.changelog-producer': '目标 Paimon 表 Changelog Producer',
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
  const platformDefault = platformParamDefaults[`${param.paramType}.${param.paramKey}`];
  if (platformDefault !== undefined) return platformDefault;
  const option = paramOptions(param).find((item) => item.default);
  if (option) return option.value;
  try {
    const value = JSON.parse(param.paramValue ?? '');
    return ['string', 'number', 'boolean'].includes(typeof value) ? String(value) : undefined;
  } catch {
    return param.paramValue?.trim() || undefined;
  }
};

const paramPath = (param: TaskParam): string[] => param.paramType === 'mysql_conf'
  ? ['taskConfig', 'cdcConfig', 'mysqlConfOverrides', param.paramKey]
  : param.paramType === 'table_conf'
    ? ['taskConfig', 'cdcConfig', 'tableConfOverrides', param.paramKey]
    : ['taskConfig', 'flinkConfOverrides', param.paramKey];

export default function SyncTaskEditorDrawer({ open, task, onSaved }: Props) {
  const [form] = Form.useForm();
  const [servers, setServers] = useState<RealtimeServer[]>([]);
  const [domains, setDomains] = useState<PaimonTablePrefixOption[]>([]);
  const [params, setParams] = useState<TaskParam[]>([]);
  const [tables, setTables] = useState<SyncSourceTableOption[]>([]);
  const [schemas, setSchemas] = useState<Record<string, MysqlTableSchema>>({});
  const [schemaLoadingTables, setSchemaLoadingTables] = useState<Set<string>>(new Set());
  const [schemaErrorTables, setSchemaErrorTables] = useState<Set<string>>(new Set());
  const [schemaReloadRevision, setSchemaReloadRevision] = useState(0);
  const [tableConfigs, setTableConfigs] = useState<Record<string, TablePrivateConfig>>({});
  const [step, setStep] = useState(0);
  const [stepsCollapsed, setStepsCollapsed] = useState(() => window.localStorage.getItem(taskStepsCollapsedKey) === 'true');
  const [mode, setMode] = useState<'wizard' | 'advanced'>('wizard');
  const [loading, setLoading] = useState(false);
  const [supportLoading, setSupportLoading] = useState(false);
  const [supportError, setSupportError] = useState('');
  const [metadataLoading, setMetadataLoading] = useState(false);
  const [preview, setPreview] = useState('');
  const [previewLoading, setPreviewLoading] = useState(false);
  const [computedColumnTable, setComputedColumnTable] = useState<string>();
  const [mysqlDdlOpen, setMysqlDdlOpen] = useState(false);
  const [mysqlDdlInitialTable, setMysqlDdlInitialTable] = useState<string>();
  const [defaultProjectId, setDefaultProjectId] = useState<number>();
  const initializedKeyRef = useRef('');
  const schemasRef = useRef<Record<string, MysqlTableSchema>>({});
  const schemaErrorsRef = useRef<Set<string>>(new Set());
  const schemaSourceRef = useRef('');
  const schemaGenerationRef = useRef(0);
  const selectedTablesRef = useRef<string[]>([]);
  const schemaRequestsRef = useRef(new Map<string, Promise<MysqlTableSchema | undefined>>());
  const [limitSchemaRequest] = useState(() => createAsyncLimiter(MYSQL_SCHEMA_REQUEST_CONCURRENCY));
  const serverId = Form.useWatch('sourceServerId', form) as number | undefined;
  const selectedTables = Form.useWatch(['taskConfig', 'cdcConfig', 'selectedTables'], form) as string[] | undefined;
  selectedTablesRef.current = selectedTables ?? [];
  const targetDatabase = Form.useWatch('targetDatabase', form) as string | undefined;
  const domainPrefix = Form.useWatch(['taskConfig', 'cdcConfig', 'domainPrefix'], form) as string | undefined;
  const updateBlocked = Boolean(task?.editPolicy?.updateBlocked || (task?.editPolicy && !task.editPolicy.editable));
  const structureLocked = Boolean(task?.editPolicy?.productionLocked || task?.editPolicy?.structureLocked);
  const lockedTables = task?.editPolicy?.lockedTables ?? [];

  useEffect(() => {
    if (!open) return undefined;
    const applyAiProposal = (rawEvent: Event) => {
      if (rawEvent.defaultPrevented) return;
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
  }, [form, open, tableConfigs, task]);

  useEffect(() => {
    if (!open) { initializedKeyRef.current = ''; return; }
    const initializationKey = task ? `task-${task.id}` : 'new';
    if (initializedKeyRef.current === initializationKey) return;
    initializedKeyRef.current = initializationKey;
    setStep(0);
    setMode('wizard');
    setPreview('');
    setSupportError('');
    const config = task?.taskConfig;
    const normalizedConfig = config ? {
      ...config,
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
      flinkVersion: task.flinkVersion || '2.2.1',
      sourceServerId: task.sourceServerId,
      targetDatabase: task.targetDatabase,
      taskConfig: normalizedConfig,
    } : {
      flinkVersion: '2.2.1',
      taskConfig: {
        parallelism: 3,
        taskManagerMemory: '3GB',
        jobManagerMemory: '1GB',
        checkpointInterval: 60,
        alarmType: 'task-failed',
        cdcConfig: {
          selectedTables: [], metadataColumns: ['database_name', 'table_name', 'op_ts'], typeMappings: [],
          mode: 'combined', ignoreIncompatible: false, mysqlConfOverrides: {},
          tableConfOverrides: mergeSyncTopologyOverrides({}),
        },
        flinkConfOverrides: {},
      },
    });
    setTableConfigs(config?.cdcConfig?.tableConfigs ?? {});
    setComputedColumnTable(undefined);
    setSupportLoading(true);
    void Promise.allSettled([listServers(), getCdcOptions(), listTaskParams()]).then(([serverResult, optionResult, paramResult]) => {
      if (serverResult.status === 'fulfilled') setServers(serverResult.value);
      if (paramResult.status === 'fulfilled') {
        setParams(paramResult.value);
        if (!task) {
          paramResult.value.filter((param) => Boolean(param.required)).forEach((param) => {
            const path = paramPath(param);
            if (form.getFieldValue(path) === undefined) {
              const value = paramDefault(param);
              if (value !== undefined) form.setFieldValue(path, value);
            }
          });
        }
      } else {
        setSupportError('同步参数元数据加载失败，为避免丢失或覆盖配置，当前不能预览或保存。');
      }
      if (optionResult.status === 'fulfilled') {
        setDomains(optionResult.value.tablePrefixes);
        setDefaultProjectId(optionResult.value.defaultProjectId);
        if (!task) {
          form.setFieldValue('targetDatabase', optionResult.value.targetDatabase);
          form.setFieldValue(['taskConfig', 'cdcConfig', 'targetDatabase'], optionResult.value.targetDatabase);
          form.setFieldValue(['taskConfig', 'cdcConfig', 'domainPrefix'], optionResult.value.tablePrefixes[0]?.value);
        }
      }
      const failed = [serverResult, optionResult, paramResult].find((item) => item.status === 'rejected');
      if (failed?.status === 'rejected') message.warning((failed.reason as Error).message);
    }).finally(() => setSupportLoading(false));
  }, [open, task?.id, form]);

  useEffect(() => {
    if (!open) return;
    const selectedServer = servers.find((item) => item.id === serverId);
    const persistedPrefix = task?.taskConfig.cdcConfig.tablePrefix;
    const prefix = structureLocked && persistedPrefix
      ? persistedPrefix
      : targetDatabase && domainPrefix
        ? [targetDatabase, selectedServer?.databasePrefix, selectedServer?.databaseName, domainPrefix].filter(Boolean).join('_') + '_'
        : '';
    form.setFieldValue(['taskConfig', 'cdcConfig', 'tablePrefix'], prefix);
  }, [domainPrefix, form, open, serverId, servers, structureLocked, targetDatabase, task?.taskConfig.cdcConfig.tablePrefix]);

  useEffect(() => {
    if (!open || !serverId) {
      setTables([]);
      return;
    }
    const source = servers.find((item) => item.id === serverId);
    form.setFieldValue(['taskConfig', 'cdcConfig', 'databaseName'], source?.databaseName);
    setMetadataLoading(true);
    void listSyncSourceTables(serverId, source?.databaseName ?? '', task?.id).then(setTables).catch((error) => message.error((error as Error).message)).finally(() => setMetadataLoading(false));
  }, [open, serverId, servers, form, task?.id]);

  const selectedTablesKey = (selectedTables ?? []).join('\u0000');
  useEffect(() => {
    if (!open || !serverId || !selectedTables?.length) {
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
  }, [limitSchemaRequest, open, schemaReloadRevision, selectedTablesKey, serverId, servers]);

  const retrySchema = (table: string) => {
    const next = new Set(schemaErrorsRef.current);
    next.delete(table);
    schemaErrorsRef.current = next;
    setSchemaErrorTables(next);
    setSchemaReloadRevision((current) => current + 1);
  };

  const setPrivate = (table: string, key: keyof TablePrivateConfig, value: string[]) => {
    setTableConfigs((current) => ({ ...current, [table]: { ...(current[table] ?? emptyTableConfig()), [key]: value } }));
  };

  const buildRequest = async (): Promise<SyncTaskSave> => {
    if (supportError) throw new Error(supportError);
    const values = await form.validateFields();
    const taskConfig = values.taskConfig ?? {};
    for (const table of taskConfig.cdcConfig?.selectedTables ?? []) {
      const configured = tableConfigs[table] ?? emptyTableConfig();
      const effectivePrimaryKeys = configured.primaryKeys?.length ? configured.primaryKeys : schemas[table]?.primaryKeys ?? [];
      const partitions = configured.partitionKeys ?? [];
      if (partitions.length && !partitions.every((name) => effectivePrimaryKeys.includes(name))) {
        throw new Error(`表 ${table} 的分区键必须是最终主键子集`);
      }
      if (effectivePrimaryKeys.length && partitions.length && effectivePrimaryKeys.every((name) => partitions.includes(name))) {
        throw new Error(`表 ${table} 的分区键不能包含全部最终主键`);
      }
    }
    taskConfig.sourceServerId = values.sourceServerId;
    const normalizedTableConfigs = Object.fromEntries((taskConfig.cdcConfig?.selectedTables ?? []).flatMap((name: string) => {
      if (lockedTables.includes(name) && task?.editPolicy?.lockedTableConfigs?.[name]) {
        return [[name, task.editPolicy.lockedTableConfigs[name]]];
      }
      const current = tableConfigs[name] ?? emptyTableConfig();
      const normalized: TablePrivateConfig = {};
      if (current.primaryKeys?.length && !sameOrderedValues(current.primaryKeys, schemas[name]?.primaryKeys)) normalized.primaryKeys = current.primaryKeys;
      if (current.partitionKeys?.length) normalized.partitionKeys = current.partitionKeys;
      if (current.computedColumns?.length) normalized.computedColumns = current.computedColumns;
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
      const result = await previewSyncTask(await buildRequest(), task?.id);
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
      render: (table: string) => <Typography.Link onClick={() => {
        setMysqlDdlInitialTable(table); setMysqlDdlOpen(true);
      }}>{table}</Typography.Link>,
    },
    {
      title: '表结构',
      width: 96,
      render: (_: unknown, row: { table: string }) => {
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
          <Button aria-label={`配置 ${row.table} 计算列`} icon={<EditOutlined />} size="small" disabled={lockedTables.includes(row.table)} onClick={() => setComputedColumnTable(row.table)} />
        </div>;
      },
    },
    {
      title: '主键',
      render: (_: unknown, row: { table: string }) => {
        const configured = tableConfigs[row.table]?.primaryKeys ?? [];
        const inherited = schemas[row.table]?.primaryKeys ?? [];
        const computedOptions = (tableConfigs[row.table]?.computedColumns ?? []).map(computedColumnName).filter((name): name is string => Boolean(name));
        return <Select disabled={lockedTables.includes(row.table)} mode="multiple" value={configured.length ? configured : inherited} onChange={(value) => setPrivate(row.table, 'primaryKeys', value)} options={[
          ...(schemas[row.table]?.columns ?? []).map((column) => ({ label: `${column.name} [${column.type}]`, value: column.name })),
          ...computedOptions.map((name) => ({ label: `${name} [计算列]`, value: name })),
        ]} style={{ width: '100%' }} />;
      },
    },
    {
      title: '分区键',
      render: (_: unknown, row: { table: string }) => {
        const primaryKeys = tableConfigs[row.table]?.primaryKeys?.length ? tableConfigs[row.table].primaryKeys! : schemas[row.table]?.primaryKeys ?? [];
        return <Select disabled={lockedTables.includes(row.table)} mode="multiple" value={tableConfigs[row.table]?.partitionKeys} onChange={(value) => setPrivate(row.table, 'partitionKeys', value)} options={primaryKeys.map((name) => ({ label: name, value: name }))} style={{ width: '100%' }} />;
      },
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
          ? <Select disabled={disabled} allowClear options={options} />
          : param.inputType === 'switch'
            ? <Select disabled={disabled} options={[{ label: '否', value: 'false' }, { label: '是', value: 'true' }]} />
            : param.inputType === 'input_number'
              ? <InputNumber disabled={disabled} min={param.minValue} max={param.maxValue} step={param.stepValue} precision={param.precisionValue} style={{ width: '100%' }} />
              : <Input disabled={disabled} placeholder={param.paramKey} />}
      </Form.Item>
    );
  }

  const sourceFields = (
    <div className="realtime-editor-section">
      <div className="realtime-subsection-heading">
        <span>公共配置</span>
      </div>
      <Descriptions bordered size="small" column={2} colon={false} className="realtime-config-table">
        <Descriptions.Item label="源端类型"><Input value="MySQL CDC" disabled /></Descriptions.Item>
        <Descriptions.Item label={fieldLabel('Server', true)}>
          <Form.Item name="sourceServerId" rules={[{ required: true, message: '请选择 Server' }]} noStyle>
            <Select disabled={structureLocked} showSearch optionFilterProp="label" placeholder="请选择 Server" options={servers.map((item) => ({ label: item.name, value: item.id }))} />
          </Form.Item>
        </Descriptions.Item>
        <Descriptions.Item label={fieldLabel('源库', true)} span={2}><Form.Item name={['taskConfig', 'cdcConfig', 'databaseName']} noStyle><Input disabled placeholder="选择 Server 后自动获取" /></Form.Item></Descriptions.Item>
        <Descriptions.Item label={fieldLabel('源表列表', true)} span={2}>
          <Space.Compact block>
            <Form.Item name={['taskConfig', 'cdcConfig', 'selectedTables']} rules={[{ required: true, message: '至少选择一张源表' }]} noStyle>
              <Select mode="multiple" showSearch loading={metadataLoading} placeholder="请选择需要同步的源表" options={tables.map((item) => {
                const occupiedBy = item.occupied ? `已被任务 #${item.occupiedTaskId} ${item.occupiedTaskName ?? ''} 使用`.trim() : '';
                return { label: occupiedBy ? `${item.tableName}（${occupiedBy}）` : item.tableName, value: item.tableName, disabled: item.occupied, title: occupiedBy };
              })} maxTagCount="responsive" />
            </Form.Item>
            <Button aria-label="刷新源表列表" title="刷新源表列表" icon={<ReloadOutlined />} loading={metadataLoading} onClick={async () => {
              if (!serverId) return;
              const source = servers.find((item) => item.id === serverId);
              try { setMetadataLoading(true); setTables(await listSyncSourceTables(serverId, source?.databaseName ?? '', task?.id)); }
              catch (error) { message.error((error as Error).message); }
              finally { setMetadataLoading(false); }
            }} />
            <Button type="link" icon={<EyeOutlined />} disabled={!serverId} onClick={() => {
              setMysqlDdlInitialTable(selectedTables?.[0] ?? tables[0]?.tableName); setMysqlDdlOpen(true);
            }}>查看源表 DDL</Button>
          </Space.Compact>
        </Descriptions.Item>
        <Descriptions.Item label="MySQL 配置" span={2}>
          <div className="realtime-dynamic-param-grid">{params.filter((item) => item.paramType === 'mysql_conf' && Boolean(item.required)).map((item) => dynamicParam(item, structureLocked))}</div>
          <SyncMoreConfigRows paramType="mysql_conf" formNamePath={['taskConfig', 'cdcConfig', 'mysqlConfOverrides']} taskParams={params} readOnly={structureLocked} />
        </Descriptions.Item>
        <Descriptions.Item label={fieldLabel('目标Paimon库', true)}><Form.Item name="targetDatabase" rules={[{ required: true }]} noStyle><Input disabled placeholder="ods_rt" /></Form.Item></Descriptions.Item>
        <Descriptions.Item label={fieldLabel('目标Paimon表所属域', true)}><Form.Item name={['taskConfig', 'cdcConfig', 'domainPrefix']} rules={[{ required: true }]} noStyle><Select disabled={structureLocked} placeholder="请选择业务域" options={domains} /></Form.Item></Descriptions.Item>
        <Descriptions.Item label={fieldLabel('目标Paimon表前缀', true)}><Form.Item name={['taskConfig', 'cdcConfig', 'tablePrefix']} rules={[{ required: true, message: '请选择业务域以生成目标Paimon表前缀' }]} noStyle><Input disabled placeholder="目标Paimon库_[库前缀_]库名_业务域_" /></Form.Item></Descriptions.Item>
        <Descriptions.Item label="目标Paimon表列表"><Input.TextArea disabled value={(selectedTables ?? []).map((table) => `${form.getFieldValue(['taskConfig', 'cdcConfig', 'tablePrefix']) || ''}${table}`).join('\n')} autoSize={{ minRows: 2, maxRows: 6 }} /></Descriptions.Item>
        <Descriptions.Item label="目标Paimon表同步元数据列" span={2}><Form.Item name={['taskConfig', 'cdcConfig', 'metadataColumns']} noStyle><Select disabled mode="multiple" options={metadataColumnOptions} placeholder="固定同步元数据列" /></Form.Item></Descriptions.Item>
        <Descriptions.Item label="目标Paimon表同步元数据列前缀" span={2}><Input value={MYSQL_METADATA_COLUMN_PREFIX} disabled /></Descriptions.Item>
        <Descriptions.Item label="目标Paimon表类型映射" span={2}><Form.Item name={['taskConfig', 'cdcConfig', 'typeMappings']} noStyle><Select disabled={structureLocked} mode="multiple" options={['to-nullable', 'to-string', 'char-to-string', 'tinyint1-not-bool', 'longtext-to-bytes', 'bigint-unsigned-to-bigint'].map((value) => ({ label: value, value }))} /></Form.Item></Descriptions.Item>
        <Descriptions.Item label="目标Paimon表配置" span={2}>
          <div className="realtime-dynamic-param-grid">
            <SyncTopologyConfigRows
              tableConfPath={['taskConfig', 'cdcConfig', 'tableConfOverrides']}
              parallelismPath={['taskConfig', 'parallelism']}
              flinkConfPath={['taskConfig', 'flinkConfOverrides']}
              readOnly={structureLocked}
              frozenParallelism={structureLocked ? task?.taskConfig.parallelism : undefined}
            />
            {params.filter((item) => item.paramType === 'table_conf' && Boolean(item.required)
              && !SYNC_TOPOLOGY_KEYS.has(item.paramKey)).map((item) => dynamicParam(item, structureLocked))}
          </div>
          <SyncMoreConfigRows paramType="table_conf" formNamePath={['taskConfig', 'cdcConfig', 'tableConfOverrides']} taskParams={params} readOnly={structureLocked} />
        </Descriptions.Item>
        <Descriptions.Item label="整库模式" span={2}><Form.Item name={['taskConfig', 'cdcConfig', 'mode']} noStyle><Select disabled options={[{ label: 'combined（固定）', value: 'combined' }]} /></Form.Item></Descriptions.Item>
      </Descriptions>

      <div className="realtime-subsection-heading">
        <span>私有配置</span>
      </div>
      <div className="realtime-mapping-table">
        <Table size="small" pagination={false} rowKey="table" dataSource={(selectedTables ?? []).map((table) => ({ table }))} columns={mappingColumns} scroll={{ x: 900 }} />
      </div>

      <div className="task-command-preview realtime-command-preview-section">
        <Button type="link" icon={<EyeOutlined />} loading={previewLoading} onClick={() => void commandPreview()}>生成预览</Button>
        <Input.TextArea
          className="task-command-preview-textarea"
          value={preview}
          placeholder="点击生成预览，查看 Paimon Action 等价命令"
          readOnly
          rows={8}
          wrap="off"
        />
      </div>
    </div>
  );

  const runtimeFields = (
    <div className="realtime-editor-section">
      <div className="realtime-subsection-heading realtime-subsection-heading-first">
        <span>基础资源配置</span>
        <Typography.Text type="secondary">配置作业并行度、内存与 Checkpoint 周期</Typography.Text>
      </div>
      <Descriptions bordered size="small" column={2} colon={false} className="realtime-config-table">
        <Descriptions.Item label={fieldLabel('并行度', true)}><Form.Item name={['taskConfig', 'parallelism']} rules={[{ required: true, message: '请输入并行度' }, { type: 'number', min: 1, max: 4, message: '同步任务并行度必须在 1-4 之间' }]} noStyle><Tooltip title="由目标 Paimon 表 Sink 并行度统一决定"><InputNumber min={1} max={4} disabled style={{ width: '100%' }} /></Tooltip></Form.Item></Descriptions.Item>
        <Descriptions.Item label={fieldLabel('Checkpoint 间隔', true)}><Form.Item name={['taskConfig', 'checkpointInterval']} rules={[{ required: true, message: '请输入 Checkpoint 间隔' }, { type: 'number', min: 10, max: 600, message: 'Checkpoint 间隔必须在 10-600 秒之间' }]} noStyle><InputNumber min={10} max={600} addonAfter="秒" style={{ width: '100%' }} /></Form.Item></Descriptions.Item>
        <Descriptions.Item label={fieldLabel('TaskManager 内存', true)}><Form.Item name={['taskConfig', 'taskManagerMemory']} rules={[{ required: true }]} noStyle><Input placeholder="3GB" /></Form.Item></Descriptions.Item>
        <Descriptions.Item label={fieldLabel('JobManager 内存', true)}><Form.Item name={['taskConfig', 'jobManagerMemory']} rules={[{ required: true }]} noStyle><Input placeholder="1GB" /></Form.Item></Descriptions.Item>
      </Descriptions>
      <Collapse className="realtime-param-collapse" items={[
        { key: 'flink', label: 'Flink 与高可用参数', children: <><div className="realtime-dynamic-param-grid">{params.filter((item) => item.paramType === 'flink_conf' && Boolean(item.required)).map((item) => dynamicParam(item))}</div><SyncMoreConfigRows paramType="flink_conf" formNamePath={['taskConfig', 'flinkConfOverrides']} taskParams={params} /></> },
      ]} />
    </div>
  );

  const sections = [
    { title: '基础信息', description: '定义同步任务的名称、负责人和基础属性', content: commonFields },
    { title: '告警配置', description: '配置同步任务异常时的告警方式', content: alertFields },
    { title: '源端&目标Paimon配置', description: '选择源表并配置 Paimon 目标与表映射', content: sourceFields },
    { title: '资源与运行', description: '设置 Flink 资源、Checkpoint 与高级参数', content: runtimeFields },
  ];

  const sectionContent = (section: typeof sections[number]) => (
    <div className="realtime-editor-panel">
      <div className="realtime-editor-panel-title">
        <Typography.Title level={5}>{section.title}</Typography.Title>
        <Typography.Text type="secondary">{section.description}</Typography.Text>
      </div>
      {section.content}
    </div>
  );

  return (<>
    <div className="realtime-sync-editor-page realtime-editor-page-compact">
      <div className={`realtime-sync-editor-page-body realtime-editor-page-body-compact mode-${mode}`}>
        <Spin spinning={supportLoading}>
        {supportError && <Alert type="error" showIcon message={supportError} className="realtime-editor-warning" />}
        {task?.editPolicy && !task.editPolicy.editable && <Alert type="warning" showIcon message={task.editPolicy.reason} className="realtime-editor-warning" />}
        {structureLocked && !updateBlocked && <Alert type="info" showIcon message="该任务已有生产实例，保留表的私有配置以及源端、目标 Paimon 公共结构配置不可修改；仍可新增或移除源表，并调整告警及运行资源。" className="realtime-editor-warning" />}
        <Tabs
          className="realtime-editor-mode-tabs ui-flat-tabs"
          activeKey={mode}
          items={[{ key: 'wizard', label: '分步向导' }, { key: 'advanced', label: '高级配置' }]}
          onChange={(value) => setMode(value as 'wizard' | 'advanced')}
        />
        <Form className={`realtime-editor-form mode-${mode}`} form={form} layout="vertical" preserve disabled={updateBlocked || Boolean(supportError)} onValuesChange={() => { if (preview) setPreview(''); }}>
          {mode === 'wizard' ? (
            <div className={stepsCollapsed ? 'realtime-wizard steps-collapsed' : 'realtime-wizard'}>
              <TaskDevelopmentSteps className="realtime-wizard-steps" collapsed={stepsCollapsed} onCollapsedChange={setStepsCollapsed} current={step} items={sections.map((item) => ({ title: item.title }))} onChange={setStep} />
              <div className="realtime-wizard-content">
                {sectionContent(sections[step])}
                <div className="realtime-wizard-actions">
                  <Button disabled={step === 0} onClick={() => setStep((value) => value - 1)}>上一步</Button>
                  {step < sections.length - 1 && <Button type="primary" onClick={() => void form.validateFields().then(() => setStep((value) => value + 1))}>下一步</Button>}
                  <Button type="primary" disabled={updateBlocked} loading={loading} onClick={() => void save()}>{task ? '保存修改' : '保存'}</Button>
                </div>
              </div>
            </div>
          ) : (
            <div className="sync-advanced-layout">
              <div className="realtime-advanced-content">
                {sections.map((item, index) => (
                  <section id={`sync-advanced-section-${index}`} className="realtime-advanced-section sync-config-anchor-section" key={item.title}>
                    {sectionContent(item)}
                  </section>
                ))}
                <div className="realtime-advanced-actions">
                  <Button type="primary" disabled={updateBlocked} loading={loading} onClick={() => void save()}>{task ? '保存修改' : '保存'}</Button>
                </div>
              </div>
              <SyncSectionNav prefix="sync-advanced-section" items={sections.map((item, index) => ({ key: String(index), label: item.title }))} />
            </div>
          )}
        </Form>
        </Spin>
      </div>
    </div>
    <ComputedColumnEditorModal
      open={Boolean(computedColumnTable)}
      tableName={computedColumnTable}
      expressions={computedColumnTable ? tableConfigs[computedColumnTable]?.computedColumns ?? [] : []}
      columns={computedColumnTable ? schemas[computedColumnTable]?.columns ?? [] : []}
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
    />
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
