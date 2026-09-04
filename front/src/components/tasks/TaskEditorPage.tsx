import { useCallback, useEffect, useRef, useState, type CSSProperties, type KeyboardEvent as ReactKeyboardEvent, type PointerEvent } from 'react';
import { Alert, Button, Dropdown, Form, Input, Modal, Select, Skeleton, Space, Switch, Tabs, Tooltip, message } from 'antd';
import {
  BranchesOutlined, CheckCircleOutlined, CodeOutlined, DatabaseOutlined,
  DeleteOutlined, DownOutlined, FunctionOutlined, MoreOutlined, OrderedListOutlined,
  PlusOutlined, ReloadOutlined, RobotOutlined, SaveOutlined, SafetyCertificateOutlined,
  SettingOutlined, UpOutlined, LeftOutlined, RightOutlined,
} from '@ant-design/icons';
import CodeMirror, { type ReactCodeMirrorRef } from '@uiw/react-codemirror';
import { sql } from '@codemirror/lang-sql';
import { autocompletion, type CompletionContext } from '@codemirror/autocomplete';
import { useNavigate } from 'react-router-dom';
import { activateTaskVersion, createTask, getTask, getTaskVersion, listTaskVersions, updateTaskVersion } from '../../api/tasks';
import { ApiError } from '../../api/client';
import { checkTaskQuality, completeSql, explainTaskSql, previewSqlStructure, validateTaskSql } from '../../api/workspace';
import type {
  HiveExplainVO, HiveValidationVO, SqlStructurePreviewVO, SqlTaskSaveRequest, SqlTaskVO,
  SqlTaskVersionSaveRequest, SqlTaskVersionVO, TaskQualityVO,
} from '../../types';
import WorkspaceBottomPanel, { type WorkbenchTab } from './WorkspaceBottomPanel';
import WorkspaceMetadataPanel from './WorkspaceMetadataPanel';
import TaskVersionDrawer from './TaskVersionDrawer';
import WorkspaceFunctionPanel from './WorkspaceFunctionPanel';
import TaskDevelopmentSteps, { taskStepsCollapsedKey } from './TaskDevelopmentSteps';

type InspectorTab = 'metadata' | 'functions';

const inspectorWidthKey = 'sql-agent-inspector-width';

interface Props {
  taskId?: number;
  versionNo?: number;
  mode?: 'create' | 'edit';
  onDirtyChange?: (dirty: boolean) => void;
  onTaskSaved?: (task: SqlTaskVO) => void;
  onOpenExecutions?: (taskId: number, executionId?: number) => void;
}

export default function TaskEditorPage({ taskId: id, versionNo, onDirtyChange, onTaskSaved }: Props) {
  const navigate = useNavigate();
  const [form] = Form.useForm<SqlTaskSaveRequest>();
  const editorRef = useRef<ReactCodeMirrorRef>(null);
  const completionAbortRef = useRef<AbortController>();
  const callbackRef = useRef({ onDirtyChange, onTaskSaved });
  const [taskName, setTaskName] = useState('');
  const [task, setTask] = useState<SqlTaskVO>();
  const [taskVersion, setTaskVersion] = useState<SqlTaskVersionVO>();
  const [versionItems, setVersionItems] = useState<SqlTaskVersionVO[]>([]);
  const [versionListLoading, setVersionListLoading] = useState(false);
  const [sqlValue, setSqlValue] = useState('');
  const [ddlValue, setDdlValue] = useState('');
  const [versionSection, setVersionSection] = useState<'sql' | 'ddl'>('sql');
  const [loading, setLoading] = useState(Boolean(id));
  const [saving, setSaving] = useState(false);
  const [activating, setActivating] = useState(false);
  const [validating, setValidating] = useState(false);
  const [explaining, setExplaining] = useState(false);
  const [qualityChecking, setQualityChecking] = useState(false);
  const [validation, setValidation] = useState<HiveValidationVO>();
  const [explain, setExplain] = useState<HiveExplainVO>();
  const [quality, setQuality] = useState<TaskQualityVO>();
  const [validationError, setValidationError] = useState('');
  const [explainError, setExplainError] = useState('');
  const [qualityError, setQualityError] = useState('');
  const [database, setDatabase] = useState<string>();
  const [dirty, setDirty] = useState(false);
  const [developmentStep, setDevelopmentStep] = useState(0);
  const [developmentStepsCollapsed, setDevelopmentStepsCollapsed] = useState(
    () => window.localStorage.getItem(taskStepsCollapsedKey) === 'true',
  );
  const [sqlStepOutlineCollapsed, setSqlStepOutlineCollapsed] = useState(false);
  const [sqlStepOutlineHidden, setSqlStepOutlineHidden] = useState(true);
  const [sqlStageMounted, setSqlStageMounted] = useState(false);
  const [inspectorTab, setInspectorTab] = useState<InspectorTab>('metadata');
  const [inspectorOpen, setInspectorOpen] = useState(false);
  const [inspectorWidth, setInspectorWidth] = useState(() => {
    const savedWidth = Number(window.localStorage.getItem(inspectorWidthKey));
    return Number.isFinite(savedWidth) && savedWidth >= 280 ? Math.min(savedWidth, 720) : 310;
  });
  const [bottomTab, setBottomTab] = useState<WorkbenchTab>('instances');
  const [bottomOpen, setBottomOpen] = useState(false);
  const refreshKey = 0;
  const [versionOpen, setVersionOpen] = useState(false);
  const [versionCreateOnOpen, setVersionCreateOnOpen] = useState(false);
  const [lineageRefreshKey, setLineageRefreshKey] = useState(0);
  const [structure, setStructure] = useState<SqlStructurePreviewVO>();
  const [structureError, setStructureError] = useState('');
  const [structureLoading, setStructureLoading] = useState(false);
  const parameters = Form.useWatch('parameters', form) || [];
  const parameterSchemaKey = JSON.stringify(parameters);
  const canEdit = !id || (taskVersion?.status === 'DRAFT' && taskVersion.canEdit);
  const editorContextLabel = !id
    ? '新建任务'
    : taskVersion
      ? `v${taskVersion.versionNo} ${taskVersion.status === 'DRAFT' ? '开发中' : taskVersion.status === 'STALE' ? '基线过期' : taskVersion.status === 'EFFECTIVE' ? '当前生效' : '历史版本'}`
      : task?.effectiveVersionNo
        ? `当前生效代码 v${task.effectiveVersionNo}`
        : '当前生效代码';

  const availableInspectorWidth = useCallback((handle: HTMLDivElement) => {
    const workspace = handle.closest<HTMLElement>('.version-editor-workspace, .sql-development-workspace');
    const outline = workspace?.querySelector<HTMLElement>('.sql-step-outline');
    if (!workspace || !outline) return 720;
    return Math.max(280, Math.min(720, workspace.getBoundingClientRect().width - outline.getBoundingClientRect().width - 360));
  }, []);

  const clampInspectorWidth = useCallback(
    (width: number, maxWidth = 720) => Math.max(280, Math.min(width, maxWidth)),
    [],
  );

  const startInspectorResize = useCallback((event: PointerEvent<HTMLDivElement>) => {
    event.preventDefault();
    const startX = event.clientX;
    const startWidth = inspectorWidth;
    const maxWidth = availableInspectorWidth(event.currentTarget);
    let nextWidth = startWidth;
    document.body.classList.add('is-resizing-inspector');

    const handlePointerMove = (moveEvent: globalThis.PointerEvent) => {
      nextWidth = clampInspectorWidth(startWidth + startX - moveEvent.clientX, maxWidth);
      setInspectorWidth(nextWidth);
    };
    const finishResize = () => {
      document.body.classList.remove('is-resizing-inspector');
      window.localStorage.setItem(inspectorWidthKey, String(nextWidth));
      window.removeEventListener('pointermove', handlePointerMove);
      window.removeEventListener('pointerup', finishResize);
      window.removeEventListener('pointercancel', finishResize);
    };

    window.addEventListener('pointermove', handlePointerMove);
    window.addEventListener('pointerup', finishResize);
    window.addEventListener('pointercancel', finishResize);
  }, [availableInspectorWidth, clampInspectorWidth, inspectorWidth]);

  const resizeInspectorWithKeyboard = useCallback((event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
    event.preventDefault();
    const maxWidth = availableInspectorWidth(event.currentTarget);
    setInspectorWidth((width) => {
      const nextWidth = clampInspectorWidth(width + (event.key === 'ArrowLeft' ? 24 : -24), maxWidth);
      window.localStorage.setItem(inspectorWidthKey, String(nextWidth));
      return nextWidth;
    });
  }, [availableInspectorWidth, clampInspectorWidth]);

  const inspectorWorkspaceStyle = { '--inspector-width': `${inspectorWidth}px` } as CSSProperties;
  const inspectorResizeHandle = (
    <div
      className="sql-inspector-resizer"
      role="separator"
      aria-label="调整右侧检查器宽度"
      aria-orientation="vertical"
      aria-valuemin={280}
      aria-valuemax={720}
      aria-valuenow={inspectorWidth}
      tabIndex={0}
      onPointerDown={startInspectorResize}
      onKeyDown={resizeInspectorWithKeyboard}
    ><span /></div>
  );

  useEffect(() => { callbackRef.current = { onDirtyChange, onTaskSaved }; }, [onDirtyChange, onTaskSaved]);
  useEffect(() => { callbackRef.current.onDirtyChange?.(dirty); }, [dirty]);
  useEffect(() => {
    const beforeUnload = (event: BeforeUnloadEvent) => { if (dirty) event.preventDefault(); };
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, [dirty]);

  const sqlCompletion = useCallback(async (context: CompletionContext) => {
    completionAbortRef.current?.abort();
    const controller = new AbortController();
    completionAbortRef.current = controller;
    try {
      const result = await completeSql(context.state.doc.toString(), context.pos, database, controller.signal);
      return {
        from: result.from,
        to: result.to,
        options: result.items.map((item) => ({
          label: item.label, apply: item.apply || item.label, type: item.type,
          detail: item.detail, info: item.comment || item.dataType,
        })),
        validFor: /^[A-Za-z0-9_$]*$/,
      };
    } catch (error) {
      if ((error as Error).name === 'AbortError') return null;
      return null;
    }
  }, [database]);

  const loadVersionItems = useCallback(async () => {
    if (!id) return;
    setVersionListLoading(true);
    try {
      const result = await listTaskVersions(id, 1, 100);
      setVersionItems(result.items);
    } catch (error) {
      message.error(`加载版本列表失败：${(error as Error).message}`);
    } finally {
      setVersionListLoading(false);
    }
  }, [id]);

  useEffect(() => { void loadVersionItems(); }, [loadVersionItems]);

  useEffect(() => {
    setDevelopmentStep(0);
    setSqlStageMounted(false);
    if (!id) {
      setTask(undefined); setTaskVersion(undefined); setTaskName(''); setSqlValue(''); setDdlValue(''); setDirty(false);
      form.resetFields();
      form.setFieldsValue({ taskType: 'RUN_HIVE', executionFrequency: '手动执行', parameters: [] });
      setLoading(false);
      return;
    }
    let active = true;
    setLoading(true);
    void Promise.all([getTask(id), versionNo != null ? getTaskVersion(id, versionNo) : Promise.resolve(undefined)]).then(([loadedTask, loadedVersion]) => {
      if (!active) return;
      const source = loadedVersion || loadedTask;
      form.setFieldsValue({
        name: source.name,
        description: source.description,
        taskType: loadedTask.taskType,
        executionFrequency: loadedTask.executionFrequency,
        owner: loadedTask.owner,
        parameters: source.parameters || [],
      });
      setTask(loadedTask);
      setTaskVersion(loadedVersion);
      setTaskName(source.name);
      setSqlValue(source.sql || '');
      setDdlValue(source.ddl || '');
      setVersionSection('sql');
      setDevelopmentStep(loadedVersion ? 1 : 0);
      setSqlStageMounted(Boolean(loadedVersion));
      setDirty(false);
    }).catch((error) => message.error(`加载任务或版本失败：${(error as Error).message}`)).finally(() => {
      if (active) setLoading(false);
    });
    return () => { active = false; };
  }, [form, id, versionNo]);

  useEffect(() => {
    if (!sqlValue.trim()) { setStructure(undefined); setStructureError(''); return undefined; }
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      setStructureLoading(true);
      void previewSqlStructure({ sql: sqlValue, parameterSchema: parameters, validateParameterValues: false }, controller.signal)
        .then((result) => { setStructure(result); setStructureError(''); })
        .catch((error) => {
          if ((error as Error).name !== 'AbortError') { setStructure(undefined); setStructureError((error as Error).message); }
        }).finally(() => setStructureLoading(false));
    }, 500);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [parameterSchemaKey, sqlValue]);

  const save = useCallback(async (showSuccess = true): Promise<SqlTaskVO | undefined> => {
    if (id && (!taskVersion || !canEdit)) {
      message.warning('当前代码只读，请先新建或选择可编辑版本');
      return undefined;
    }
    let fields: Pick<SqlTaskSaveRequest, 'name' | 'description' | 'taskType' | 'executionFrequency' | 'owner' | 'parameters'>;
    try { fields = await form.validateFields(); }
    catch { setDevelopmentStep(0); return undefined; }
    const payload: SqlTaskSaveRequest = {
      ...fields, parameters: fields.parameters || [], revision: id ? taskVersion?.revision : undefined, sql: sqlValue.trim(), ddl: ddlValue.trim() || undefined,
    };
    if (!payload.sql) {
      setSqlStageMounted(true);
      setDevelopmentStep(1);
      message.warning('SQL 不能为空');
      return undefined;
    }
    setSaving(true);
    try {
      if (id && task && taskVersion) {
        const versionPayload: SqlTaskVersionSaveRequest = {
          name: fields.name,
          description: fields.description,
          parameters: fields.parameters || [],
          revision: taskVersion.revision,
          sql: sqlValue.trim(),
          ddl: ddlValue.trim() || undefined,
        };
        const savedVersion = await updateTaskVersion(id, taskVersion.versionNo, versionPayload);
        setTaskVersion(savedVersion);
        setTaskName(savedVersion.name);
        form.setFieldsValue({
          name: savedVersion.name,
          description: savedVersion.description,
          taskType: fields.taskType,
          executionFrequency: fields.executionFrequency,
          owner: fields.owner,
          parameters: savedVersion.parameters || [],
        });
        setDirty(false);
        setLineageRefreshKey((current) => current + 1);
        if (showSuccess) message.success(`版本 v${savedVersion.versionNo} 已保存`);
        return task;
      }

      const savedTask = await createTask(payload);
      setTask(savedTask);
      setTaskName(savedTask.name);
      form.setFieldsValue({
        name: savedTask.name,
        description: savedTask.description,
        taskType: savedTask.taskType,
        executionFrequency: savedTask.executionFrequency,
        owner: savedTask.owner,
        parameters: savedTask.parameters || [],
      });
      setDirty(false);
      setLineageRefreshKey((current) => current + 1);
      if (showSuccess) message.success(`任务 ${savedTask.id} 已创建并作为初始生效代码`);
      callbackRef.current.onTaskSaved?.(savedTask);
      if (!callbackRef.current.onTaskSaved) navigate(`/tasks/${savedTask.id}/edit`, { replace: true });
      return savedTask;
    } catch (error) {
      if (error instanceof ApiError && error.status === 409 && id && taskVersion) {
        Modal.confirm({
          title: `版本 v${taskVersion.versionNo} 已被其他人更新`,
          content: '本地内容仍保留。加载服务端最新版本后才能继续保存，避免静默覆盖他人的修改。',
          okText: '加载最新版本', cancelText: '保留本地内容',
          onOk: async () => {
            const latest = await getTaskVersion(id, taskVersion.versionNo);
            setTaskVersion(latest); setTaskName(latest.name); setSqlValue(latest.sql || ''); setDdlValue(latest.ddl || ''); setDirty(false);
            form.setFieldsValue({
              name: latest.name,
              description: latest.description,
              parameters: latest.parameters || [],
            });
          },
        });
      } else message.error(`保存失败：${(error as Error).message}`);
      return undefined;
    } finally { setSaving(false); }
  }, [canEdit, ddlValue, form, id, navigate, sqlValue, task, taskVersion]);

  useEffect(() => {
    const saveShortcut = (event: globalThis.KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') { event.preventDefault(); void save(); }
    };
    window.addEventListener('keydown', saveShortcut);
    return () => window.removeEventListener('keydown', saveShortcut);
  }, [save]);

  const ensureSaved = async () => (!id || dirty || !task) ? save(false) : task;

  const runValidation = async () => {
    const resolved = await ensureSaved(); if (!resolved) return;
    setBottomOpen(true); setBottomTab('validation'); setValidating(true); setValidationError('');
    try { setValidation(await validateTaskSql(resolved.id, database, taskVersion?.versionNo)); }
    catch (error) { setValidation(undefined); setValidationError((error as Error).message); }
    finally { setValidating(false); }
  };

  const runExplain = async () => {
    const resolved = await ensureSaved(); if (!resolved) return;
    setBottomOpen(true); setBottomTab('explain'); setExplaining(true); setExplainError('');
    try { setExplain(await explainTaskSql(resolved.id, database, taskVersion?.versionNo)); }
    catch (error) { setExplain(undefined); setExplainError((error as Error).message); }
    finally { setExplaining(false); }
  };

  const runQualityCheck = async () => {
    const resolved = await ensureSaved(); if (!resolved) return;
    setBottomOpen(true); setBottomTab('quality'); setQualityChecking(true); setQualityError('');
    try { setQuality(await checkTaskQuality(resolved.id, database, taskVersion?.versionNo)); }
    catch (error) { setQuality(undefined); setQualityError((error as Error).message); }
    finally { setQualityChecking(false); }
  };

  const continueToSql = async () => {
    try {
      await form.validateFields(['name', 'description', 'taskType', 'executionFrequency', 'owner', 'parameters']);
      setSqlStageMounted(true);
      setDevelopmentStep(1);
    }
    catch { message.warning('请先完善任务设置'); }
  };

  const insertSql = (text: string) => {
    if (!canEdit) {
      message.info('当前代码只读，请先新建或选择可编辑版本');
      return;
    }
    const view = editorRef.current?.view;
    if (!view) { setSqlValue((current) => `${current}${current && !current.endsWith(' ') ? ' ' : ''}${text}`); setDirty(true); return; }
    const selection = view.state.selection.main;
    const prefix = selection.empty && selection.from > 0 && !/\s/.test(view.state.doc.sliceString(selection.from - 1, selection.from)) ? ' ' : '';
    const insert = `${prefix}${text}`;
    view.dispatch({ changes: { from: selection.from, to: selection.to, insert }, selection: { anchor: selection.from + insert.length } });
    view.focus();
  };

  const jumpToStep = (stepNo: number) => {
    const view = editorRef.current?.view; if (!view) return;
    const marker = new RegExp(`^\\s*====\\s*step\\s*:\\s*${stepNo}(?:\\s*:|\\s*====)`, 'im').exec(view.state.doc.toString());
    view.dispatch({ selection: { anchor: marker?.index || 0 }, scrollIntoView: true }); view.focus();
  };

  const toggleSqlStepOutline = () => setSqlStepOutlineCollapsed((collapsed) => !collapsed);

  const toggleSqlStepOutlineVisibility = () => setSqlStepOutlineHidden((hidden) => !hidden);

  if (loading) return <div className="sql-workbench-page loading"><Skeleton active /></div>;

  const stepOutline = (
    <div className="task-step-browser">
      <div className="metadata-browser-header sql-step-outline-header">
        <span className="sql-step-outline-title"><OrderedListOutlined /><b>SQL Step</b></span>
        <span className="sql-step-outline-actions">
          <small>{structureLoading ? '解析中' : structure ? `${structure.stepCount} 个` : '-'}</small>
          <Tooltip title={sqlStepOutlineCollapsed ? '展开 Step 栏' : '收缩 Step 栏'} placement="right">
            <Button
              className="sql-step-outline-toggle"
              type="text"
              size="small"
              icon={sqlStepOutlineCollapsed ? <RightOutlined /> : <LeftOutlined />}
              aria-label={sqlStepOutlineCollapsed ? '展开 Step 栏' : '收缩 Step 栏'}
              onClick={toggleSqlStepOutline}
            />
          </Tooltip>
        </span>
      </div>
      {structureError ? <div className="task-step-error">{structureError}</div> : null}
      {structure?.steps.map((step) => (
        <button key={step.stepNo} type="button" className="task-step-item" onClick={() => jumpToStep(step.stepNo)}>
          <span className="task-step-number">{step.stepNo}</span><span><strong>{step.stepName}</strong><small>{step.statementType}</small></span>
        </button>
      ))}
      {!structureLoading && !structureError && !structure?.steps.length ? <div className="task-step-empty">输入 SQL 后显示真实解析结果</div> : null}
    </div>
  );

  const inspectorItems = [
    {
      key: 'metadata', label: <span><DatabaseOutlined />元数据</span>,
      children: <WorkspaceMetadataPanel database={database} onDatabaseChange={(value) => {
        setDatabase(value); setValidation(undefined); setExplain(undefined); setQuality(undefined);
        setValidationError(''); setExplainError(''); setQualityError('');
      }} onInsertSql={insertSql} />,
    },
    { key: 'functions', label: <span><FunctionOutlined />函数</span>, children: <WorkspaceFunctionPanel database={database} onInsertSql={insertSql} /> },
  ];

  const workspaceMoreItems = [
    { key: 'step', icon: <OrderedListOutlined />, label: sqlStepOutlineHidden ? '显示 Step' : '隐藏 Step', disabled: Boolean(id && taskVersion && versionSection !== 'sql') },
    { key: 'inspector', icon: <DatabaseOutlined />, label: inspectorOpen ? '隐藏右侧面板' : '显示右侧面板', disabled: Boolean(id && taskVersion && versionSection !== 'sql') },
    { key: 'bottom', icon: bottomOpen ? <DownOutlined /> : <UpOutlined />, label: bottomOpen ? '隐藏底部面板' : '显示底部面板', disabled: Boolean(id && taskVersion && versionSection !== 'sql') },
    { type: 'divider' as const },
    { key: 'agent', icon: <RobotOutlined />, label: '交给 Agent', disabled: !id || task?.archived },
  ];

  const handleWorkspaceMoreClick = ({ key }: { key: string }) => {
    if (key === 'step') toggleSqlStepOutlineVisibility();
    else if (key === 'inspector') setInspectorOpen((open) => !open);
    else if (key === 'bottom') setBottomOpen((open) => !open);
    else if (key === 'agent' && id) navigate(`/chat?taskId=${id}${taskVersion ? `&versionNo=${taskVersion.versionNo}` : ''}`);
  };

  const switchVersion = (targetVersionNo: number) => {
    if (!id || targetVersionNo === taskVersion?.versionNo) return;
    const perform = () => navigate(`/tasks/${id}/edit?versionNo=${targetVersionNo}`);
    if (!dirty) { perform(); return; }
    Modal.confirm({
      title: '切换版本？',
      content: `版本 v${taskVersion?.versionNo} 仍有未保存修改，切换后这些本地修改将丢失。`,
      okText: '放弃修改并切换', okButtonProps: { danger: true }, cancelText: '继续编辑', onOk: perform,
    });
  };

  const activateCurrentVersion = () => {
    if (!id || !task || !taskVersion || !canEdit || dirty) return;
    Modal.confirm({
      title: `生效版本 v${taskVersion.versionNo}？`,
      content: '该版本将成为任务的当前运行代码，其他基于旧生效代码且仍在开发的版本可能会变为基线过期。',
      okText: '确认生效', cancelText: '取消',
      onOk: async () => {
        setActivating(true);
        try {
          await activateTaskVersion(id, taskVersion.versionNo, task.revision, taskVersion.revision);
          const activatedTask = await getTask(id);
          setTask(activatedTask);
          callbackRef.current.onTaskSaved?.(activatedTask);
          message.success(`版本 v${taskVersion.versionNo} 已生效`);
          navigate(`/tasks/${id}/edit`, { replace: true });
        } catch (error) {
          message.error(`版本生效失败：${(error as Error).message}`);
        } finally { setActivating(false); }
      },
    });
  };

  const versionStatusText = (version: SqlTaskVersionVO) => version.status === 'DRAFT'
    ? '开发中'
    : version.status === 'EFFECTIVE' ? '当前生效' : version.status === 'STALE' ? '基线过期' : '历史版本';

  if (id && task && taskVersion) return (
    <div className="sql-workbench-page task-version-editor-page">
      <aside className="task-version-editor-sidebar">
        <button type="button" className={versionSection === 'sql' ? 'version-editor-step active' : 'version-editor-step'} onClick={() => setVersionSection('sql')}><span>1</span><strong>SQL 开发</strong></button>
        <button type="button" className={versionSection === 'ddl' ? 'version-editor-step active' : 'version-editor-step'} onClick={() => setVersionSection('ddl')}><span>2</span><strong>DDL 变更</strong></button>
      </aside>

      <main className="task-version-editor-main">
        <header className="version-editor-primary-toolbar">
          <div className="version-editor-selector">
            <span>版本</span>
            <Select
              value={taskVersion.versionNo}
              loading={versionListLoading}
              popupMatchSelectWidth={220}
              options={versionItems.map((item) => ({ value: item.versionNo, label: `v${item.versionNo} · ${versionStatusText(item)}` }))}
              onChange={switchVersion}
            />
            <Button type="link" icon={<PlusOutlined />} disabled={task.archived} onClick={() => { setVersionCreateOnOpen(true); setVersionOpen(true); }}>新版本</Button>
            <Tooltip title="刷新版本列表"><Button type="text" icon={<ReloadOutlined />} loading={versionListLoading} onClick={() => void loadVersionItems()} /></Tooltip>
          </div>
          <Space size={4}>
            {canEdit ? <Button icon={<SaveOutlined />} loading={saving} onClick={() => void save()}>保存版本</Button> : null}
            {canEdit ? <Tooltip title={dirty ? '请先保存当前修改' : undefined}><Button type="primary" icon={<CheckCircleOutlined />} disabled={dirty} loading={activating} onClick={activateCurrentVersion}>生效</Button></Tooltip> : null}
          </Space>
        </header>

        {!canEdit ? <Alert className="version-editor-readonly-alert" type={taskVersion.status === 'STALE' ? 'warning' : 'info'} showIcon message={`${versionStatusText(taskVersion)}不可编辑`} description="需要修改时，请从当前生效代码新建版本。" action={<Button size="small" type="primary" disabled={task.archived} onClick={() => { setVersionCreateOnOpen(true); setVersionOpen(true); }}>新建版本</Button>} /> : null}

        <div className="version-editor-action-toolbar">
          <div><strong>{versionSection === 'sql' ? '任务 SQL' : '表结构变更 DDL'}</strong><span>{versionSection === 'sql' ? database || '未选择数据库' : '仅保存，不自动执行'}</span></div>
          <Space size={2}>
            {versionSection === 'sql' ? <Button type="text" icon={<SafetyCertificateOutlined />} loading={qualityChecking} onClick={() => void runQualityCheck()}>质量检查</Button> : null}
            {versionSection === 'sql' ? <Button type="text" icon={<CheckCircleOutlined />} loading={validating} onClick={() => void runValidation()}>编译校验</Button> : null}
            {versionSection === 'sql' ? <Button type="text" icon={<CodeOutlined />} loading={explaining} onClick={() => void runExplain()}>Explain</Button> : null}
            <Dropdown placement="bottomRight" menu={{ items: workspaceMoreItems, onClick: handleWorkspaceMoreClick }}><Button type="text" icon={<MoreOutlined />}>更多</Button></Dropdown>
          </Space>
        </div>

        <div style={inspectorWorkspaceStyle} className={`${inspectorOpen && versionSection === 'sql' ? 'version-editor-workspace' : 'version-editor-workspace inspector-collapsed'}${versionSection === 'sql' && sqlStepOutlineCollapsed ? ' steps-collapsed' : ''}${versionSection === 'sql' && sqlStepOutlineHidden ? ' steps-hidden' : ''}`}>
          <aside className={`sql-step-outline${versionSection === 'sql' && sqlStepOutlineCollapsed ? ' collapsed' : ''}`}>{versionSection === 'sql' ? stepOutline : <div className="ddl-change-guide"><strong>DDL 变更说明</strong><span>记录本版本需要人工执行的 Hive DDL。</span><span>建议每条语句附带变更原因和回滚说明。</span><span>版本生效不会触发 DDL 执行。</span></div>}</aside>
          <div className="sql-editor-column">
            <div className="workbench-editor">
              <CodeMirror ref={editorRef} value={versionSection === 'sql' ? sqlValue : ddlValue} height="100%" editable={canEdit} extensions={versionSection === 'sql' ? [sql(), autocompletion({ override: [sqlCompletion], activateOnTyping: true })] : [sql()]} onChange={(value) => {
                if (!canEdit) return;
                if (versionSection === 'sql') {
                  setSqlValue(value); setValidation(undefined); setExplain(undefined); setQuality(undefined);
                  setValidationError(''); setExplainError(''); setQualityError('');
                }
                else setDdlValue(value);
                setDirty(true);
              }} basicSetup={{ lineNumbers: true, foldGutter: true, highlightActiveLine: true, highlightSelectionMatches: true, bracketMatching: true, autocompletion: false }} />
            </div>
          </div>
          {versionSection === 'sql' ? <aside className="sql-inspector">{inspectorResizeHandle}<Tabs className="ui-flat-tabs" activeKey={inspectorTab} onChange={(key) => setInspectorTab(key as InspectorTab)} items={inspectorItems} /></aside> : null}
        </div>
        {bottomOpen && versionSection === 'sql' ? (
          <section className={`workbench-bottom-panel version-editor-bottom-panel${bottomTab === 'lineage' ? ' lineage-active' : ''}`}>
            <WorkspaceBottomPanel
              taskId={id}
              activeTab={bottomTab}
              onActiveTabChange={setBottomTab}
              validation={validation}
              validationLoading={validating}
              validationError={validationError}
              explain={explain}
              explainLoading={explaining}
              explainError={explainError}
              quality={quality}
              qualityLoading={qualityChecking}
              qualityError={qualityError}
              refreshKey={refreshKey}
              versionNo={taskVersion?.versionNo}
              defaultDb={database}
              taskName={taskName}
              lineageRefreshKey={lineageRefreshKey}
              onInsertSql={insertSql}
            />
          </section>
        ) : null}
      </main>

      <TaskVersionDrawer open={versionOpen} task={task} editingVersionNo={taskVersion.versionNo} hasUnsavedChanges={dirty} createOnOpen={versionCreateOnOpen} onClose={() => { setVersionOpen(false); setVersionCreateOnOpen(false); }} onEditVersion={(targetVersionNo) => { setVersionOpen(false); switchVersion(targetVersionNo); }} onActivated={(activatedTask) => { setTask(activatedTask); callbackRef.current.onTaskSaved?.(activatedTask); navigate(`/tasks/${id}/edit`, { replace: true }); }} />
    </div>
  );

  return (
    <div className="sql-workbench-page task-development-page">
      <div className="task-development-body">
        <TaskDevelopmentSteps collapsed={developmentStepsCollapsed} onCollapsedChange={setDevelopmentStepsCollapsed} current={developmentStep} onChange={(next) => next === 0 ? setDevelopmentStep(0) : void continueToSql()} items={[
            { title: '任务设置', description: id ? `任务 ${id}` : '名称与运行参数', icon: <SettingOutlined /> },
            { title: 'SQL 开发', description: structure ? `${structure.stepCount} 个 Step` : 'Hive SQL', icon: <CodeOutlined /> },
          ]} />

        <main className="task-development-main">
          <section className={developmentStep === 0 ? 'task-stage task-settings-stage active' : 'task-stage task-settings-stage'}>
            <div className="task-stage-heading">
              <div><strong>任务设置</strong><span>{id ? `${editorContextLabel}${canEdit ? '' : ' · 只读'}` : '定义基本信息和运行参数'}</span></div>
              <Button type="primary" onClick={() => void continueToSql()}>下一步</Button>
            </div>
            <Form
              form={form}
              disabled={!canEdit}
              className="task-settings-form"
              layout="vertical"
              requiredMark="optional"
              initialValues={{ taskType: 'RUN_HIVE', executionFrequency: '手动执行', parameters: [] }}
              onValuesChange={(changedValues) => {
                if (typeof changedValues.name === 'string') setTaskName(changedValues.name);
                setDirty(true);
              }}
            >
              <div className="task-settings-table">
                <div className="task-settings-table-row">
                  <div className="task-settings-table-label required">任务名称</div>
                  <div className="task-settings-table-control">
                    <Form.Item name="name" rules={[{ required: true, whitespace: true, message: '请输入任务名称' }, { max: 128 }]}><Input placeholder="例如：订单日汇总" /></Form.Item>
                  </div>
                  <div className="task-settings-table-label">任务 ID</div>
                  <div className="task-settings-table-control"><Input disabled value={id || '保存后自动生成'} /></div>
                </div>
                <div className="task-settings-table-row">
                  <div className="task-settings-table-label required">任务类型</div>
                  <div className="task-settings-table-control">
                    <Form.Item name="taskType" rules={[{ required: true, message: '请选择任务类型' }]}>
                      <Select options={[{ value: 'RUN_HIVE', label: 'Run Hive' }]} />
                    </Form.Item>
                  </div>
                  <div className="task-settings-table-label required">执行频率</div>
                  <div className="task-settings-table-control">
                    <Form.Item name="executionFrequency" rules={[{ required: true, whitespace: true, message: '请输入执行频率' }, { max: 128 }]}>
                      <Input placeholder="例如：手动执行、每天 07:00" />
                    </Form.Item>
                  </div>
                </div>
                <div className="task-settings-table-row">
                  <div className="task-settings-table-label">负责人<span className="task-settings-optional">（可选）</span></div>
                  <div className="task-settings-table-control">
                    <Form.Item name="owner" rules={[{ max: 64 }]}><Input placeholder="留空时默认为当前用户" /></Form.Item>
                  </div>
                  <div className="task-settings-table-label">执行引擎</div>
                  <div className="task-settings-table-control"><Input disabled value="Hive on YARN" /></div>
                </div>
                <div className="task-settings-table-row task-settings-table-row-full">
                  <div className="task-settings-table-label">任务描述<span className="task-settings-optional">（可选）</span></div>
                  <div className="task-settings-table-control">
                    <Form.Item name="description" rules={[{ max: 1024 }]}><Input.TextArea rows={4} placeholder="说明业务目标、统计口径和输出范围" /></Form.Item>
                  </div>
                </div>
              </div>
              <div className="task-parameter-heading"><span>运行参数</span><small>定义 SQL 中可引用的安全参数及默认值</small></div>
              <Form.List name="parameters">
                {(fields, { add, remove }) => <div className="task-parameter-list task-parameter-table">
                  <div className="task-parameter-table-head">
                    <span>参数名</span><span>类型</span><span>默认值</span><span>说明</span><span>必填</span><span>操作</span>
                  </div>
                  {fields.map((field) => <div className="task-parameter-item" key={field.key}>
                    <Form.Item name={[field.name, 'name']} label="参数名" rules={[{ required: true, pattern: /^[A-Za-z_][A-Za-z0-9_]{0,63}$/, message: '请输入合法参数名' }]}><Input placeholder="biz_date" /></Form.Item>
                    <Form.Item name={[field.name, 'type']} label="类型" rules={[{ required: true }]}><Select options={['STRING', 'INTEGER', 'DECIMAL', 'DATE', 'DATETIME', 'BOOLEAN'].map((value) => ({ value, label: value }))} /></Form.Item>
                    <Form.Item name={[field.name, 'defaultValue']} label="默认值"><Input placeholder="可选" /></Form.Item>
                    <Form.Item name={[field.name, 'description']} label="说明"><Input placeholder="可选" /></Form.Item>
                    <Form.Item name={[field.name, 'required']} label="必填" valuePropName="checked" initialValue><Switch /></Form.Item>
                    <Tooltip title="删除参数"><Button danger type="text" icon={<DeleteOutlined />} onClick={() => remove(field.name)} /></Tooltip>
                  </div>)}
                  <Button className="task-add-parameter" type="dashed" icon={<PlusOutlined />} onClick={() => add({ type: 'STRING', required: true })}>添加运行参数</Button>
                </div>}
              </Form.List>
            </Form>
          </section>

          {sqlStageMounted ? <section className={developmentStep === 1 ? 'task-stage sql-development-stage active' : 'task-stage sql-development-stage'}>
            <div className="sql-development-toolbar">
              <div className="sql-development-context"><Button type="text" onClick={() => setDevelopmentStep(0)}>任务设置</Button><span>/</span><strong>SQL 开发</strong><span>{database || '未选择数据库'}</span><span>{sqlValue.length.toLocaleString()} 字符</span></div>
              <Space size={2}>
                {id ? <Button type="text" icon={<BranchesOutlined />} onClick={() => { setVersionCreateOnOpen(false); setVersionOpen(true); }}>版本{taskVersion ? ` v${taskVersion.versionNo}` : task?.effectiveVersionNo ? ` · 生效 v${task.effectiveVersionNo}` : ''}</Button> : null}
                <Button type="text" icon={<SafetyCertificateOutlined />} loading={qualityChecking} onClick={() => void runQualityCheck()}>质量检查</Button>
                <Button type="text" icon={<CheckCircleOutlined />} loading={validating} onClick={() => void runValidation()}>编译校验</Button>
                <Button type="text" icon={<CodeOutlined />} loading={explaining} onClick={() => void runExplain()}>Explain</Button>
                <Dropdown placement="bottomRight" menu={{ items: workspaceMoreItems, onClick: handleWorkspaceMoreClick }}><Button type="text" icon={<MoreOutlined />}>更多</Button></Dropdown>
                {!id && canEdit ? <Button icon={<SaveOutlined />} loading={saving} onClick={() => void save()}>创建任务</Button> : null}
              </Space>
            </div>
            <div style={inspectorWorkspaceStyle} className={`${inspectorOpen ? 'sql-development-workspace' : 'sql-development-workspace inspector-collapsed'}${sqlStepOutlineCollapsed ? ' steps-collapsed' : ''}${sqlStepOutlineHidden ? ' steps-hidden' : ''}`}>
              <aside className={`sql-step-outline${sqlStepOutlineCollapsed ? ' collapsed' : ''}`}>{stepOutline}</aside>
              <div className="sql-editor-column">
                <div className="workbench-editor">
                  <CodeMirror ref={editorRef} value={sqlValue} height="100%" editable={canEdit} extensions={[sql(), autocompletion({ override: [sqlCompletion], activateOnTyping: true })]} onChange={(value) => {
                    if (!canEdit) return;
                    setSqlValue(value); setDirty(true); setValidation(undefined); setExplain(undefined); setQuality(undefined);
                    setValidationError(''); setExplainError(''); setQualityError('');
                  }} basicSetup={{ lineNumbers: true, foldGutter: true, highlightActiveLine: true, highlightSelectionMatches: true, bracketMatching: true, autocompletion: false }} />
                </div>
              </div>
              <aside className="sql-inspector">{inspectorResizeHandle}<Tabs className="ui-flat-tabs" activeKey={inspectorTab} onChange={(key) => setInspectorTab(key as InspectorTab)} items={inspectorItems} /></aside>
            </div>
            {bottomOpen ? (
              <section className={`workbench-bottom-panel${bottomTab === 'lineage' ? ' lineage-active' : ''}`}>
                <WorkspaceBottomPanel
                  taskId={id}
                  activeTab={bottomTab}
                  onActiveTabChange={setBottomTab}
                  validation={validation}
                  validationLoading={validating}
                  validationError={validationError}
                  explain={explain}
                  explainLoading={explaining}
                  explainError={explainError}
                  quality={quality}
                  qualityLoading={qualityChecking}
                  qualityError={qualityError}
                  refreshKey={refreshKey}
                  versionNo={taskVersion?.versionNo}
                  defaultDb={database}
                  taskName={taskName}
                  lineageRefreshKey={lineageRefreshKey}
                  onInsertSql={insertSql}
                />
              </section>
            ) : null}
          </section> : null}
        </main>
      </div>

      {id && task ? <TaskVersionDrawer open={versionOpen} task={task} editingVersionNo={taskVersion?.versionNo} hasUnsavedChanges={dirty} createOnOpen={versionCreateOnOpen} onClose={() => { setVersionOpen(false); setVersionCreateOnOpen(false); }} onEditVersion={(targetVersionNo) => {
        const switchVersion = () => { setVersionOpen(false); navigate(`/tasks/${id}/edit?versionNo=${targetVersionNo}`); };
        if (!dirty || targetVersionNo === taskVersion?.versionNo) { switchVersion(); return; }
        Modal.confirm({
          title: '切换版本？',
          content: `版本 v${taskVersion?.versionNo} 仍有未保存修改，切换后这些本地修改将丢失。`,
          okText: '放弃修改并切换',
          okButtonProps: { danger: true },
          cancelText: '继续编辑',
          onOk: switchVersion,
        });
      }} onActivated={(activatedTask) => { setTask(activatedTask); callbackRef.current.onTaskSaved?.(activatedTask); navigate(`/tasks/${id}/edit`, { replace: true }); }} /> : null}
    </div>
  );
}
