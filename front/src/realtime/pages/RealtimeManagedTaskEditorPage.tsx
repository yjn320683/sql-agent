import { useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { AlignLeftOutlined, BranchesOutlined, CheckCircleOutlined, CodeOutlined, DatabaseOutlined, FullscreenExitOutlined, FullscreenOutlined, MoreOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { Alert, Button, Descriptions, Dropdown, Form, Input, InputNumber, Modal, Select, Space, Spin, Table, Tabs, Tooltip, Typography, message } from 'antd';
import CodeMirror from '@uiw/react-codemirror';
import { sql } from '@codemirror/lang-sql';
import { autocompletion, type CompletionContext } from '@codemirror/autocomplete';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { analyzeManagedSql, createManagedTask, debugManagedTask, getManagedTask, listAvailableRealtimeTables, listMysqlTables, listServers, listTaskParams, updateManagedTask } from '../api';
import SyncMoreConfigRows, { defaultParamValue, SyncParamInput } from '../components/SyncMoreConfigRows';
import SyncSectionNav from '../components/SyncSectionNav';
import RealtimeManagedSqlInspector from '../components/RealtimeManagedSqlInspector';
import RealtimeManagedTaskDetailModal from '../components/RealtimeManagedTaskDetailModal';
import type { ManagedTask, ManagedTaskSave, ManagedTaskType, RealtimeServer, RealtimeTable, TaskParam } from '../types';
import type { AiProposal } from '../../types';
import TaskDevelopmentSteps from '../../components/tasks/TaskDevelopmentSteps';
import { formatFlinkSql } from '../utils/formatFlinkSql';

const defaults = (taskType: ManagedTaskType): ManagedTaskSave => ({ taskType, name: '', owner: '1', description: '', flinkVersion: '2.2.1', alarmConfig: {}, flinkConf: { parallelism: 1, checkpointIntervalSeconds: 60, taskManagerMemoryGb: 2, jobManagerMemoryGb: 1, flinkConfOverrides: {} }, taskConfig: taskType === 'compute' ? { computeConfig: { defaultDatabase: '', sql: '' } } : { exportConfig: { sourceDatabase: '', targetServerId: undefined as unknown as number, mappings: [], sink: { batchSize: 500, flushIntervalMs: 2000, maxRetries: 3 } } } });
interface Props { taskType: ManagedTaskType; debugTask?: ManagedTask; onDebugClose?: () => void; onDebugStarted?: () => void }

export default function RealtimeManagedTaskEditorPage({ taskType, debugTask, onDebugClose, onDebugStarted }: Props) {
  const { taskId } = useParams(); const navigate = useNavigate(); const location = useLocation(); const id = debugTask?.id ?? (taskId ? Number(taskId) : undefined); const label = taskType === 'compute' ? '计算' : '出仓';
  const readOnly = !debugTask && location.pathname.endsWith('/view');
  const routeState = location.state as { returnTo?: string; taskName?: string } | null;
  const returnTo = routeState?.returnTo || `/realtime/${taskType}`;
  const [form] = Form.useForm<ManagedTaskSave>(); const [step, setStep] = useState(0); const [mode, setMode] = useState<'wizard' | 'advanced'>('wizard'); const [loading, setLoading] = useState(false); const [analyzing, setAnalyzing] = useState(false); const [supportLoading, setSupportLoading] = useState(true); const [supportError, setSupportError] = useState(''); const [tables, setTables] = useState<RealtimeTable[]>([]); const [servers, setServers] = useState<RealtimeServer[]>([]); const [params, setParams] = useState<TaskParam[]>([]); const [mysqlTables, setMysqlTables] = useState<string[]>([]); const [loadedTask, setLoadedTask] = useState<ManagedTask>(); const [analysisResult, setAnalysisResult] = useState<{ valid: boolean; inputs: string[]; outputs: string[]; insertCount?: number; plan: string }>(); const [analysisError, setAnalysisError] = useState(''); const [bottomOpen, setBottomOpen] = useState(false); const [bottomTab, setBottomTab] = useState<'validation' | 'quality' | 'explain'>('validation'); const [metadataVisible, setMetadataVisible] = useState(true); const [metadataWidth, setMetadataWidth] = useState(310); const [sqlFullscreen, setSqlFullscreen] = useState(false); const [detailOpen, setDetailOpen] = useState(false);
  const debugSubmittingRef = useRef(false);
  const editorBlocked = Boolean(!debugTask && id && loadedTask?.taskStatus === 1);
  const availableDatabases = useMemo(() => Array.from(new Set(tables.map((table) => table.databaseName))), [tables]);
  const sqlExtensions = useMemo(() => [sql(), autocompletion({ override: [(context: CompletionContext) => { const word = context.matchBefore(/[\w.`]+/); if (!word && !context.explicit) return null; return { from: word?.from ?? context.pos, options: tables.map((table) => ({ label: `paimon.${table.databaseName}.${table.tableName}`, type: 'class', detail: '受管实时表' })) }; }] })], [tables]);
  useEffect(() => {
    let active = true; setSupportLoading(true); setSupportError('');
    const taskRequest = debugTask ? Promise.resolve(debugTask) : id ? getManagedTask(id) : Promise.resolve(undefined);
    void Promise.all([listAvailableRealtimeTables(), listServers(), listTaskParams().catch(() => []), taskRequest]).then(([tableRows, serverRows, taskParams, task]) => {
      if (!active) return; setTables(tableRows); setServers(serverRows); setParams(taskParams); setLoadedTask(task);
      const value = task ? { ...task, expectedUpdateTime: task.updateTime } : defaults(taskType);
      const overrides = { ...value.flinkConf.flinkConfOverrides };
      if (!readOnly) taskParams.filter((param) => param.paramType === 'flink_conf' && param.required).forEach((param) => {
        if (overrides[param.paramKey] === undefined) overrides[param.paramKey] = defaultParamValue(param);
      });
      form.setFieldsValue({ ...value, flinkConf: { ...value.flinkConf, flinkConfOverrides: overrides } });
    }).catch((error) => { if (active) setSupportError((error as Error).message); }).finally(() => { if (active) setSupportLoading(false); });
    return () => { active = false; };
  }, [debugTask, form, id, readOnly, taskType]);
  const serverId = Form.useWatch(['taskConfig', 'exportConfig', 'targetServerId'], form); const sourceDatabase = Form.useWatch(['taskConfig', 'exportConfig', 'sourceDatabase'], form); const computeSql = Form.useWatch(['taskConfig', 'computeConfig', 'sql'], form) ?? ''; const computeDatabase = Form.useWatch(['taskConfig', 'computeConfig', 'defaultDatabase'], form);
  useEffect(() => {
    const applyAiProposal = (rawEvent: Event) => {
      if (rawEvent.defaultPrevented || readOnly) return;
      const event = rawEvent as CustomEvent<AiProposal>; const proposal = event.detail; if (!proposal) return;
      const kind = proposal.kind.toUpperCase();
      if (taskType === 'compute' && kind === 'SQL' && proposal.target === 'flink-sql' && proposal.after !== undefined) {
        form.setFieldValue(['taskConfig', 'computeConfig', 'sql'], proposal.after);
      } else if ((kind === 'FORM' || kind === 'CONFIG')
        && proposal.target === (taskType === 'compute' ? 'compute-task-form' : 'export-form') && proposal.patch) {
        form.setFieldsValue(proposal.patch as unknown as ManagedTaskSave);
      } else return;
      setAnalysisResult(undefined); setAnalysisError('');
      event.preventDefault();
    };
    const publishAiContext = () => window.dispatchEvent(new CustomEvent('sql-agent:ai-context-update', {
      detail: {
        contextType: taskType === 'compute' ? 'REALTIME_COMPUTE_TASK' : 'REALTIME_EXPORT_TASK',
        entityId: id ? String(id) : undefined,
        title: form.getFieldValue('name') || `${id ? '编辑' : '新建'}实时${label}任务`,
        revision: loadedTask?.updateTime ?? '0',
        draft: taskType === 'compute'
          ? { sql: computeSql, database: computeDatabase }
          : { config: form.getFieldsValue(true) },
      },
    }));
    window.addEventListener('sql-agent:apply-ai-proposal', applyAiProposal);
    window.addEventListener('sql-agent:ai-context-request', publishAiContext);
    publishAiContext();
    return () => {
      window.removeEventListener('sql-agent:apply-ai-proposal', applyAiProposal);
      window.removeEventListener('sql-agent:ai-context-request', publishAiContext);
    };
  }, [computeDatabase, computeSql, form, id, label, loadedTask?.updateTime, readOnly, taskType]);
  useEffect(() => {
    if (!readOnly && taskType === 'compute' && !computeDatabase && availableDatabases.length) form.setFieldValue(['taskConfig', 'computeConfig', 'defaultDatabase'], availableDatabases[0]);
  }, [availableDatabases, computeDatabase, form, readOnly, taskType]);
  useEffect(() => {
    if (!sqlFullscreen) return undefined;
    document.body.classList.add('managed-sql-is-fullscreen');
    const exitFullscreen = (event: KeyboardEvent) => { if (event.key === 'Escape') setSqlFullscreen(false); };
    window.addEventListener('keydown', exitFullscreen);
    return () => { document.body.classList.remove('managed-sql-is-fullscreen'); window.removeEventListener('keydown', exitFullscreen); };
  }, [sqlFullscreen]);
  useEffect(() => { const server = servers.find((item) => item.id === serverId); if (!server?.databaseName) { setMysqlTables([]); return; } void listMysqlTables(server.id, server.databaseName).then(setMysqlTables).catch((error) => message.error((error as Error).message)); }, [serverId, servers]);
  const saveValue = (): ManagedTaskSave => {
    const value = form.getFieldsValue(true) as ManagedTaskSave;
    return { taskType, name: value.name, owner: value.owner, description: value.description, flinkVersion: value.flinkVersion,
      alarmConfig: value.alarmConfig ?? {}, flinkConf: value.flinkConf, taskConfig: value.taskConfig,
      ...(value.expectedUpdateTime ? { expectedUpdateTime: value.expectedUpdateTime } : {}) };
  };
  const submit = async () => { if (readOnly || debugTask || editorBlocked) return; try { setLoading(true); await form.validateFields(); const value = saveValue(); if (id) await updateManagedTask(id, value); else await createManagedTask(value); message.success(`${label}任务已保存`); navigate(returnTo, { replace: true }); } catch (error) { if (error instanceof Error) message.error(error.message); } finally { setLoading(false); } };
  const submitDebug = async () => {
    if (!debugTask || debugSubmittingRef.current || supportLoading || supportError) return;
    debugSubmittingRef.current = true;
    try {
      await form.validateFields();
      const value = saveValue();
      setLoading(true);
      await debugManagedTask(debugTask.id, value);
      message.success('调试请求已提交；本次修改仅写入调试实例，不会保存到正式任务');
      onDebugStarted?.();
    } catch (error) { if (error instanceof Error) message.error(error.message); }
    finally { debugSubmittingRef.current = false; setLoading(false); }
  };
  const refreshTables = async () => {
    try { setTables(await listAvailableRealtimeTables()); }
    catch (error) { message.error(`刷新实时表失败：${(error as Error).message}`); }
  };
  const insertSql = (text: string) => {
    if (readOnly) return;
    const path: ['taskConfig', 'computeConfig', 'sql'] = ['taskConfig', 'computeConfig', 'sql'];
    const current = String(form.getFieldValue(path) ?? '');
    form.setFieldValue(path, `${current}${current && !/\s$/.test(current) ? ' ' : ''}${text}`);
  };
  const analyze = async (target: 'validation' | 'quality' | 'explain') => {
    setBottomTab(target); setBottomOpen(true); setAnalysisError('');
    try {
      setAnalyzing(true);
      await form.validateFields([['taskConfig', 'computeConfig', 'defaultDatabase'], ['taskConfig', 'computeConfig', 'sql']]);
      const value = form.getFieldsValue(true);
      const result = await analyzeManagedSql({ ...value, taskId: id });
      setAnalysisResult(result);
      message.success(target === 'explain' ? '执行计划生成成功' : target === 'quality' ? 'SQL 质量检查通过' : 'Flink SQL 编译校验通过');
    } catch (error) {
      const text = error instanceof Error ? error.message : '请检查 SQL 配置';
      setAnalysisResult(undefined); setAnalysisError(text); message.error(text);
    } finally { setAnalyzing(false); }
  };
  const beginMetadataResize = (event: ReactPointerEvent<HTMLDivElement>) => {
    event.preventDefault();
    const startX = event.clientX; const startWidth = metadataWidth;
    const move = (moveEvent: PointerEvent) => setMetadataWidth(Math.min(520, Math.max(220, startWidth + startX - moveEvent.clientX)));
    const stop = () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', stop); };
    window.addEventListener('pointermove', move); window.addEventListener('pointerup', stop);
  };
  const common = <Descriptions bordered size="small" column={2} colon={false} className="realtime-config-table"><Descriptions.Item label="任务名称"><Form.Item name="name" rules={[{ required: true, message: '请输入任务名称' }]} noStyle><Input placeholder={`请输入${label}任务名称`} /></Form.Item></Descriptions.Item><Descriptions.Item label="负责人"><Form.Item name="owner" rules={[{ required: true, message: '请输入负责人' }]} noStyle><Input placeholder="请输入负责人" /></Form.Item></Descriptions.Item><Descriptions.Item label="描述" span={2}><Form.Item name="description" noStyle><Input.TextArea rows={3} placeholder="请输入任务用途和数据范围" /></Form.Item></Descriptions.Item><Descriptions.Item label="Flink 版本" span={2}><Form.Item name="flinkVersion" rules={[{ required: true }]} noStyle><Select disabled options={[{ value: '2.2.1', label: '2.2.1' }]} /></Form.Item></Descriptions.Item></Descriptions>;
  const alarm = <Descriptions bordered size="small" column={2} colon={false} className="realtime-config-table"><Descriptions.Item label="告警类型"><Form.Item name={['alarmConfig', 'alarmType']} noStyle><Select allowClear options={[{ value: 'failure', label: '失败告警' }, { value: 'all', label: '全部告警' }]} /></Form.Item></Descriptions.Item><Descriptions.Item label="告警组"><Form.Item name={['alarmConfig', 'alarmGroup']} noStyle><Input placeholder="输入告警接收组" /></Form.Item></Descriptions.Item></Descriptions>;
  const analysisOverview = analysisError
    ? <Alert type="error" showIcon message="Flink SQL 检查失败" description={analysisError} />
    : analysisResult ? <div className="managed-sql-analysis-summary"><div><span>校验结果</span><strong>通过</strong></div><div><span>输入表</span><strong>{analysisResult.inputs.length}</strong></div><div><span>输出表</span><strong>{analysisResult.outputs.length}</strong></div><div><span>INSERT</span><strong>{analysisResult.insertCount ?? analysisResult.outputs.length}</strong></div></div>
      : <div className="managed-sql-empty-result">点击工具栏中的检查按钮获取结果</div>;
  const compute = <div className={sqlFullscreen ? 'managed-sql-workbench offline-aligned fullscreen' : 'managed-sql-workbench offline-aligned'}>
    <div className="managed-sql-workbench-toolbar sql-development-toolbar">
      <div className="managed-sql-context sql-development-context">
        <strong>任务设置</strong><span>/</span><strong>SQL 开发</strong><span>{computeDatabase || '未选择数据库'}</span><span>{String(computeSql).length.toLocaleString()} 字符</span>
      </div>
      <Space size={2}>
        {id && !debugTask ? <Button type="text" icon={<BranchesOutlined />} onClick={() => setDetailOpen(true)}>版本</Button> : null}
        <Button type="text" icon={<AlignLeftOutlined />} disabled={readOnly || editorBlocked || !String(computeSql).trim()} onClick={() => form.setFieldValue(['taskConfig', 'computeConfig', 'sql'], formatFlinkSql(String(form.getFieldValue(['taskConfig', 'computeConfig', 'sql']) ?? '')))}>格式化</Button>
        <Button type="text" icon={<SafetyCertificateOutlined />} disabled={readOnly} loading={analyzing && bottomTab === 'quality'} onClick={() => void analyze('quality')}>质量检查</Button>
        <Button type="text" icon={<CheckCircleOutlined />} disabled={readOnly} loading={analyzing && bottomTab === 'validation'} onClick={() => void analyze('validation')}>编译校验</Button>
        <Button type="text" icon={<CodeOutlined />} disabled={readOnly} loading={analyzing && bottomTab === 'explain'} onClick={() => void analyze('explain')}>Explain</Button>
        <Tooltip title={sqlFullscreen ? '退出全屏（Esc）' : '全屏编辑'}><Button type="text" aria-label={sqlFullscreen ? '退出全屏' : '全屏编辑'} icon={sqlFullscreen ? <FullscreenExitOutlined /> : <FullscreenOutlined />} onClick={() => setSqlFullscreen((value) => !value)} /></Tooltip>
        <Dropdown placement="bottomRight" menu={{ items: [
          ...(!sqlFullscreen ? [{ key: 'metadata', icon: <DatabaseOutlined />, label: metadataVisible ? '隐藏右侧面板' : '显示右侧面板' }] : []),
          { key: 'bottom', icon: <CodeOutlined />, label: bottomOpen ? '隐藏底部面板' : '显示底部面板' },
        ], onClick: ({ key }) => {
          if (key === 'metadata') setMetadataVisible((value) => !value);
          else if (key === 'bottom') setBottomOpen((value) => !value);
        } }}><Button type="text" icon={<MoreOutlined />}>更多</Button></Dropdown>
      </Space>
    </div>
    <Form.Item name={['taskConfig', 'computeConfig', 'defaultDatabase']} rules={[{ required: true, message: '请选择默认 Paimon 数据库' }]} hidden><Input /></Form.Item>
    <div className={`managed-sql-layout${metadataVisible && !sqlFullscreen ? '' : ' metadata-hidden'}`}>
      <div className="managed-sql-editor"><Form.Item name={['taskConfig', 'computeConfig', 'sql']} rules={[{ required: true, message: '请输入 Flink SQL' }]} noStyle><CodeMirror height="100%" editable={!readOnly && !editorBlocked} extensions={sqlExtensions} basicSetup={{ lineNumbers: true, foldGutter: true, highlightActiveLine: true, highlightSelectionMatches: true, bracketMatching: true }} /></Form.Item></div>
      {metadataVisible && <aside className="managed-sql-metadata sql-inspector" style={{ width: metadataWidth }}>
        <div className="managed-sql-resize-handle sql-inspector-resizer" role="separator" aria-label="调整元数据面板宽度" onPointerDown={beginMetadataResize}><span /></div>
        <RealtimeManagedSqlInspector tables={tables} database={computeDatabase} readOnly={readOnly || editorBlocked} onDatabaseChange={(value) => form.setFieldValue(['taskConfig', 'computeConfig', 'defaultDatabase'], value)} onInsertSql={insertSql} onRefresh={refreshTables} />
      </aside>}
    </div>
    {bottomOpen ? <section className="managed-sql-bottom-panel workbench-bottom-panel"><Tabs className="ui-flat-tabs" activeKey={bottomTab} onChange={(value) => setBottomTab(value as typeof bottomTab)} tabBarExtraContent={<Button type="text" size="small" onClick={() => setBottomOpen(false)}>收起</Button>} items={[
      { key: 'validation', label: <span><CheckCircleOutlined /> 编译校验</span>, children: <div className="managed-sql-bottom-content">{analysisOverview}</div> },
      { key: 'quality', label: <span><SafetyCertificateOutlined /> 质量检查</span>, children: <div className="managed-sql-bottom-content">{analysisOverview}{analysisResult ? <Descriptions size="small" column={1} items={[{ key: 'inputs', label: '输入表', children: analysisResult.inputs.join('、') || '-' }, { key: 'outputs', label: '输出表', children: analysisResult.outputs.join('、') || '-' }]} /> : null}</div> },
      { key: 'explain', label: <span><CodeOutlined /> 执行计划</span>, children: <div className="managed-sql-bottom-content">{analysisError ? analysisOverview : <pre>{analysisResult?.plan || '点击工具栏中的 Explain 获取执行计划'}</pre>}</div> },
    ]} /></section> : null}
  </div>;
  const exportFields = <><div className="managed-task-form-grid"><Form.Item name={['taskConfig', 'exportConfig', 'sourceDatabase']} label="Paimon 源数据库" rules={[{ required: true }]}><Select options={availableDatabases.map((value) => ({ value, label: value }))} /></Form.Item><Form.Item name={['taskConfig', 'exportConfig', 'targetServerId']} label="目标 MySQL Server" rules={[{ required: true }]}><Select options={servers.map((item) => ({ value: item.id, label: `${item.name} / ${item.databaseName}` }))} /></Form.Item></div><Form.List name={['taskConfig', 'exportConfig', 'mappings']}>{(fields, { add, remove }) => <><Table rowKey="key" pagination={false} dataSource={fields} columns={[{ title: '受管实时表', render: (_, field) => <Form.Item name={[field.name, 'realtimeTableId']} rules={[{ required: true }]} noStyle><Select style={{ width: 320 }} options={tables.filter((table) => !sourceDatabase || table.databaseName === sourceDatabase).map((table) => ({ value: table.id, label: `${table.databaseName}.${table.tableName}` }))} /></Form.Item> }, { title: 'MySQL 目标表', render: (_, field) => <Form.Item name={[field.name, 'targetTable']} rules={[{ required: true }]} noStyle><Select showSearch style={{ width: 260 }} options={mysqlTables.map((value) => ({ value, label: value }))} /></Form.Item> }, { title: '操作', width: 90, render: (_, field) => <Button type="link" danger disabled={readOnly || editorBlocked} onClick={() => remove(field.name)}>删除</Button> }]} />{!readOnly && <Button block type="dashed" disabled={editorBlocked} onClick={() => add({ columnMappings: [] })}>添加表映射</Button>}</>}</Form.List><div className="realtime-subsection-heading"><span>MySQL 写入设置</span></div><Descriptions bordered size="small" column={2} colon={false} className="realtime-config-table"><Descriptions.Item label="批量行数"><Form.Item name={['taskConfig', 'exportConfig', 'sink', 'batchSize']} noStyle><InputNumber min={1} style={{ width: '100%' }} /></Form.Item></Descriptions.Item><Descriptions.Item label="刷新间隔"><Form.Item name={['taskConfig', 'exportConfig', 'sink', 'flushIntervalMs']} noStyle><InputNumber min={100} addonAfter="ms" style={{ width: '100%' }} /></Form.Item></Descriptions.Item><Descriptions.Item label="最大重试" span={2}><Form.Item name={['taskConfig', 'exportConfig', 'sink', 'maxRetries']} noStyle><InputNumber min={0} max={10} style={{ width: '100%' }} /></Form.Item></Descriptions.Item></Descriptions></>;
  const requiredLabel = (label: string) => <span className="realtime-required-label">{label}</span>;
  const resources = <div className="realtime-editor-section">
    <Descriptions bordered size="small" column={2} colon={false} className="realtime-config-table realtime-sync-runtime-table">
      <Descriptions.Item label={requiredLabel('任务并行度')}><Form.Item name={['flinkConf', 'parallelism']} rules={[{ required: true }]} noStyle><InputNumber min={1} max={128} style={{ width: '100%' }} /></Form.Item></Descriptions.Item>
      <Descriptions.Item label={requiredLabel('TaskManager 内存')}><Form.Item name={['flinkConf', 'taskManagerMemoryGb']} rules={[{ required: true }]} noStyle><InputNumber min={1} addonAfter="GB" style={{ width: '100%' }} /></Form.Item></Descriptions.Item>
      <Descriptions.Item label={requiredLabel('JobManager 内存')}><Form.Item name={['flinkConf', 'jobManagerMemoryGb']} rules={[{ required: true }]} noStyle><InputNumber min={1} addonAfter="GB" style={{ width: '100%' }} /></Form.Item></Descriptions.Item>
      <Descriptions.Item label={requiredLabel('Checkpoint 间隔')}><Form.Item name={['flinkConf', 'checkpointIntervalSeconds']} rules={[{ required: true }]} noStyle><InputNumber min={10} max={600} addonAfter="秒" style={{ width: '100%' }} /></Form.Item></Descriptions.Item>
      <Descriptions.Item label="Flink配置" span={2}>
        <div className="realtime-dynamic-param-grid">{params.filter((param) => param.paramType === 'flink_conf' && param.required).map((param) => <Form.Item key={param.paramKey} label={param.keyDesc || param.paramKey} tooltip={param.paramKey} className="realtime-dynamic-param"><SyncParamInput param={param} name={['flinkConf', 'flinkConfOverrides', param.paramKey]} disabled={readOnly || editorBlocked || param.editable === false} /></Form.Item>)}</div>
        <SyncMoreConfigRows paramType="flink_conf" formNamePath={['flinkConf', 'flinkConfOverrides']} taskParams={params} readOnly={readOnly || editorBlocked} />
      </Descriptions.Item>
    </Descriptions>
  </div>;
  const sections = [
    { key: 'basic', title: '基础信息', description: `定义${label}任务的名称、负责人和基础属性`, content: common },
    { key: 'alarm', title: '告警配置', description: `配置${label}任务异常时的告警方式`, content: alarm },
    { key: 'task', title: taskType === 'compute' ? 'SQL 编辑' : '出仓设置', description: taskType === 'compute' ? '编辑受管 Flink SQL 并校验输入、输出实时表' : '选择受管实时表与已有 MySQL 目标表', content: taskType === 'compute' ? compute : exportFields },
    { key: 'runtime', title: '运行资源', description: '统一配置实时任务运行所需的 Flink 资源与参数', content: resources },
  ];
  const sectionContent = (section: typeof sections[number]) => {
    const workbench = taskType === 'compute' && section.title === 'SQL 编辑';
    return <div className={`realtime-editor-panel${workbench ? ' realtime-editor-panel-workbench' : ''}`}>{!workbench && <div className="realtime-editor-panel-title"><Typography.Title level={5}>{section.title}</Typography.Title><Typography.Text type="secondary">{section.description}</Typography.Text></div>}<div className="realtime-editor-section">{section.content}</div></div>;
  };
  const displayMode = debugTask ? 'advanced' : mode;
  const editor = <><div className="realtime-sync-editor-page realtime-editor-page-compact">
    <div className={`realtime-sync-editor-page-body realtime-editor-page-body-compact mode-${displayMode}`}><Spin spinning={supportLoading}>{supportError && <Alert type="error" showIcon message={`${label}任务加载失败`} description={supportError} className="realtime-editor-warning" />}
      {editorBlocked && !readOnly && <Alert type="warning" showIcon message="上线中的任务不允许编辑，请先下线任务" className="realtime-editor-warning" />}
      {!debugTask && <Tabs className="realtime-editor-mode-tabs ui-flat-tabs" activeKey={mode} items={[{ key: 'wizard', label: '分步向导' }, { key: 'advanced', label: '高级配置' }]} onChange={(value) => setMode(value as 'wizard' | 'advanced')} />}
      <Form className={`realtime-editor-form mode-${displayMode}`} form={form} layout="vertical" preserve disabled={Boolean(supportError) || readOnly || editorBlocked}>{displayMode === 'wizard' ? <div className="realtime-wizard"><TaskDevelopmentSteps className="realtime-wizard-steps" collapsed={false} hideToggle current={step} items={sections.map((item) => ({ title: item.title }))} onChange={setStep} /><div className={taskType === 'compute' && sections[step].title === 'SQL 编辑' ? 'realtime-wizard-content workbench-active' : 'realtime-wizard-content'}>{sectionContent(sections[step])}<div className="realtime-wizard-actions"><Button disabled={step === 0} onClick={() => setStep((value) => value - 1)}>上一步</Button>{step < sections.length - 1 && <Button type="primary" onClick={() => readOnly ? setStep((value) => value + 1) : void form.validateFields().then(() => setStep((value) => value + 1))}>下一步</Button>}{!readOnly && <Button type="primary" disabled={Boolean(supportError) || editorBlocked} loading={loading} onClick={() => void submit()}>{id ? '保存修改' : '保存'}</Button>}</div></div></div>
        : <div className="sync-advanced-layout sync-task-advanced-layout"><SyncSectionNav prefix="managed-advanced-section" items={sections.map((item) => ({ key: item.key, label: item.title }))} /><div className="realtime-advanced-content">{sections.map((item) => <section className="realtime-advanced-section sync-config-anchor-section" id={`managed-advanced-section-${item.key}`} key={item.key}>{sectionContent(item)}</section>)}{!readOnly && !debugTask && <div className="realtime-advanced-actions"><Button type="primary" disabled={Boolean(supportError) || editorBlocked} loading={loading} onClick={() => void submit()}>{id ? '保存修改' : '保存'}</Button></div>}</div></div>}</Form>
    </Spin></div>
  </div>{detailOpen && loadedTask ? <RealtimeManagedTaskDetailModal task={loadedTask} initialTab="versions" onClose={() => setDetailOpen(false)} /> : null}</>;
  if (!debugTask) return editor;
  return <Modal className="realtime-debug-config-modal managed-debug-config-modal" title={`隔离调试配置 · ${debugTask.name}`} open width={1520} okText="开始调试" cancelText="取消" confirmLoading={loading} okButtonProps={{ disabled: supportLoading || Boolean(supportError) }} closable={!loading} maskClosable={!loading} keyboard={!loading} destroyOnHidden onOk={() => void submitDebug()} onCancel={() => !loading && onDebugClose?.()}>
    <Alert showIcon type="info" message="本次配置仅写入 DEBUG 实例快照，不会修改或保存正式任务配置。" />
    {editor}
  </Modal>;
}
