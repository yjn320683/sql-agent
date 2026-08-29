import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert, Button, Checkbox, Empty, Input, Select, Space, Spin, Steps, Switch, Table, Tabs, Tag,
  Typography, message,
} from 'antd';
import { ArrowLeftOutlined, ExperimentOutlined, ReloadOutlined } from '@ant-design/icons';
import { diffLines } from 'diff';
import type { ColumnsType } from 'antd/es/table';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  createDataCompare, generateVersionComparePlan, prepareVersionCompare,
} from '../../api/dataCompare';
import { listTasks, listTaskVersions } from '../../api/tasks';
import { getDataMapPrimaryKeys } from '../../api/workspace';
import type {
  DataComparePlanVO, DataComparePrepareVO, DataCompareTableRequest, SqlTaskVersionVO, SqlTaskVO,
} from '../../types';

interface MappingRow extends DataCompareTableRequest {
  rowKey: string;
  primaryKeysText: string;
  compareColumnsText: string;
  probeColumnsText: string;
  suggestedPrimaryKeys: string[];
}

const split = (value: string) => value.split(',').map((item) => item.trim()).filter(Boolean);
const splitTable = (value?: string): [string, string] | undefined => {
  const parts = (value || '').split('.').map((item) => item.trim()).filter(Boolean);
  return parts.length === 2 ? [parts[0], parts[1]] : undefined;
};

function SqlDiff({ left, right }: { left: string; right: string }) {
  const parts = useMemo(() => diffLines(left || '', right || ''), [left, right]);
  return <pre className="compare-sql-diff">{parts.map((part, index) => (
    <span key={`${index}-${part.value.length}`} className={part.added ? 'diff-added' : part.removed ? 'diff-removed' : ''}>
      {part.value}
    </span>
  ))}</pre>;
}

export default function VersionComparePage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const initialTaskId = Number(params.get('taskId')) || undefined;
  const initialCandidate = Number(params.get('candidateVersionNo')) || undefined;
  const [tasks, setTasks] = useState<SqlTaskVO[]>([]);
  const [versions, setVersions] = useState<SqlTaskVersionVO[]>([]);
  const [taskId, setTaskId] = useState<number | undefined>(initialTaskId);
  const [candidateVersionNo, setCandidateVersionNo] = useState<number | undefined>(initialCandidate);
  const [prepared, setPrepared] = useState<DataComparePrepareVO>();
  const [plan, setPlan] = useState<DataComparePlanVO>();
  const [baselineSteps, setBaselineSteps] = useState<string[]>([]);
  const [candidateSteps, setCandidateSteps] = useState<string[]>([]);
  const [mappings, setMappings] = useState<MappingRow[]>([]);
  const [onlySameColumns, setOnlySameColumns] = useState(true);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    void listTasks('', 1, 100).then((result) => setTasks(result.items)).catch((error) => {
      message.error(`读取任务失败：${(error as Error).message}`);
    });
  }, []);

  const candidates = useMemo(
    () => versions.filter((version) => version.status === 'DRAFT' && version.canEdit),
    [versions],
  );

  const loadVersions = useCallback(async (selectedTaskId: number) => {
    setLoading(true);
    try {
      const result = await listTaskVersions(selectedTaskId, 1, 100);
      setVersions(result.items);
      const editable = result.items.filter((item) => item.status === 'DRAFT' && item.canEdit);
      const selected = initialCandidate && editable.some((item) => item.versionNo === initialCandidate)
        ? initialCandidate : editable[0]?.versionNo;
      setCandidateVersionNo(selected);
      setPrepared(undefined);
      setPlan(undefined);
      setMappings([]);
    } catch (error) {
      setVersions([]);
      message.error(`读取版本失败：${(error as Error).message}`);
    } finally {
      setLoading(false);
    }
  }, [initialCandidate]);

  useEffect(() => { if (taskId) void loadVersions(taskId); }, [loadVersions, taskId]);

  const prepare = async () => {
    if (!taskId || !candidateVersionNo) {
      message.warning('请选择任务和开发中候选版本');
      return;
    }
    setLoading(true);
    try {
      const result = await prepareVersionCompare(taskId, candidateVersionNo);
      setPrepared(result);
      setPlan(undefined);
      setMappings([]);
      setBaselineSteps(result.baselineSteps.map((step) => step.name));
      setCandidateSteps(result.candidateSteps.map((step) => step.name));
    } catch (error) {
      message.error(`准备版本验数失败：${(error as Error).message}`);
    } finally {
      setLoading(false);
    }
  };

  const loadPrimaryKeySuggestions = (rows: MappingRow[]) => {
    void Promise.all(rows.map(async (row) => {
      const baseline = splitTable(row.baselineSourceTable);
      const candidate = splitTable(row.candidateSourceTable);
      if (!baseline || !candidate) return { rowKey: row.rowKey, keys: [] as string[] };
      const [left, right] = await Promise.allSettled([
        getDataMapPrimaryKeys(baseline[0], baseline[1]),
        getDataMapPrimaryKeys(candidate[0], candidate[1]),
      ]);
      if (left.status !== 'fulfilled' || right.status !== 'fulfilled') {
        return { rowKey: row.rowKey, keys: [] as string[] };
      }
      const rightKeys = new Set(right.value.result.primaryKeys.map((key) => key.toLowerCase()));
      return {
        rowKey: row.rowKey,
        keys: left.value.result.primaryKeys.filter((key) => rightKeys.has(key.toLowerCase())),
      };
    })).then((suggestions) => {
      setMappings((current) => current.map((row) => {
        const suggestion = suggestions.find((item) => item.rowKey === row.rowKey);
        return suggestion ? { ...row, suggestedPrimaryKeys: suggestion.keys } : row;
      }));
    });
  };

  const generatePlan = async () => {
    if (!taskId || !candidateVersionNo || !prepared) return;
    if (!baselineSteps.length || !candidateSteps.length) {
      message.warning('基线和候选版本都至少选择一个 Step');
      return;
    }
    setLoading(true);
    try {
      const result = await generateVersionComparePlan(
        taskId, candidateVersionNo, baselineSteps, candidateSteps,
      );
      setPlan(result);
      setBaselineSteps(result.selectedBaselineSteps);
      setCandidateSteps(result.selectedCandidateSteps);
      const rows = result.suggestedTables.map((table, index) => ({
        ...table,
        rowKey: `${table.baselineSourceTable}-${table.candidateSourceTable}-${index}`,
        primaryKeysText: table.rule?.primaryKeyList?.join(', ') || '',
        compareColumnsText: table.rule?.compareColumnList?.join(', ') || '',
        probeColumnsText: table.rule?.probeColumnList?.join(', ') || '',
        suggestedPrimaryKeys: [],
      }));
      setMappings(rows);
      loadPrimaryKeySuggestions(rows);
    } catch (error) {
      message.error(`生成调测 SQL 失败：${(error as Error).message}`);
    } finally {
      setLoading(false);
    }
  };

  const updateMapping = (rowKey: string, key: keyof MappingRow, value: string) => {
    setMappings((current) => current.map((item) => (
      item.rowKey === rowKey ? { ...item, [key]: value } : item
    )));
  };

  const submit = async () => {
    if (!taskId || !candidateVersionNo || !plan) return;
    if (!mappings.length) {
      message.warning('至少需要一组输出表映射');
      return;
    }
    setSubmitting(true);
    try {
      const detail = await createDataCompare({
        compareType: 'VERSION',
        planToken: plan.planToken,
        taskId,
        candidateVersionNo,
        baselineSteps: plan.selectedBaselineSteps,
        candidateSteps: plan.selectedCandidateSteps,
        onlyCompareSameColumn: onlySameColumns,
        tables: mappings.map((item) => ({
          originalTable: item.originalTable,
          baselineSourceTable: item.baselineSourceTable,
          candidateSourceTable: item.candidateSourceTable,
          requiredByDdl: item.requiredByDdl,
          baselineTable: item.baselineTable,
          candidateTable: item.candidateTable,
          rule: {
            onlyCompareSamePrimaryKey: false,
            ignoreNullPrimaryKey: false,
            primaryKeyList: split(item.primaryKeysText),
            compareColumnList: split(item.compareColumnsText),
            probeColumnList: split(item.probeColumnsText),
          },
        })),
      });
      navigate(`/data-compares/${detail.id}`);
    } catch (error) {
      message.error(`发起版本验数失败：${(error as Error).message}`);
    } finally {
      setSubmitting(false);
    }
  };

  const mappingColumns = useMemo<ColumnsType<MappingRow>>(() => [
    {
      title: '原始输出表', dataIndex: 'candidateSourceTable', width: 250, fixed: 'left', ellipsis: true,
      render: (value, row) => <Space size={4}><code>{value}</code>{row.requiredByDdl ? <Tag color="blue">DDL 必验</Tag> : null}</Space>,
    },
    { title: '基线调测表', dataIndex: 'baselineTable', width: 280, ellipsis: true, render: (value) => <code>{value}</code> },
    { title: '候选调测表', dataIndex: 'candidateTable', width: 280, ellipsis: true, render: (value) => <code>{value}</code> },
    {
      title: '业务主键', dataIndex: 'primaryKeysText', width: 250,
      render: (value, row) => <div className="compare-rule-input">
        <Input value={value} placeholder="id, business_date" onChange={(event) => updateMapping(row.rowKey, 'primaryKeysText', event.target.value)} />
        {row.suggestedPrimaryKeys.length ? <button type="button" onClick={() => updateMapping(row.rowKey, 'primaryKeysText', row.suggestedPrimaryKeys.join(', '))}>采用 Data Map：{row.suggestedPrimaryKeys.join(', ')}</button> : null}
      </div>,
    },
    { title: '对比列', dataIndex: 'compareColumnsText', width: 220, render: (value, row) => <Input value={value} placeholder="留空比较全部同名列" onChange={(event) => updateMapping(row.rowKey, 'compareColumnsText', event.target.value)} /> },
    { title: '抽样探测列', dataIndex: 'probeColumnsText', width: 220, render: (value, row) => <Input value={value} placeholder="留空自动选择" onChange={(event) => updateMapping(row.rowKey, 'probeColumnsText', event.target.value)} /> },
  ], []);

  const stepItems = prepared ? [
    {
      title: '选择版本与 Step',
      description: `基线：${prepared.baselineVersion.versionNo ? `v${prepared.baselineVersion.versionNo}` : '初始代码'}`,
    },
    { title: '配置表和规则', description: plan ? `${mappings.length} 张输出表` : '请先生成调测 SQL' },
    { title: '执行并查看报告' },
  ] : [{ title: '选择版本与 Step' }, { title: '配置表和规则' }, { title: '执行并查看报告' }];

  return (
    <div className="data-page version-compare-page">
      <header className="data-page-header">
        <div><Typography.Title level={2}>版本验数</Typography.Title><Typography.Text type="secondary">生成不可变调测计划后再执行，运行时不再读取变化中的代码</Typography.Text></div>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/data-compares')}>返回验数中心</Button>
      </header>
      <section className="data-panel compare-workflow-steps"><Steps size="small" current={plan ? 1 : 0} items={stepItems} /></section>
      <section className="data-panel version-compare-config">
        <div className="compare-section-title"><strong>候选版本</strong><span>基线固定为任务当前生效代码；没有生效版本时使用初始代码</span></div>
        <Space wrap className="version-selector-row">
          <Select showSearch optionFilterProp="label" placeholder="选择 SQL 任务" value={taskId} style={{ width: 300 }} options={tasks.map((task) => ({ value: task.id, label: `${task.id} ${task.name}` }))} onChange={setTaskId} />
          <Select placeholder="选择开发中版本" value={candidateVersionNo} style={{ width: 280 }} options={candidates.map((version) => ({ value: version.versionNo, label: `v${version.versionNo} · ${version.versionNote || version.name}` }))} onChange={(value) => { setCandidateVersionNo(value); setPrepared(undefined); setPlan(undefined); }} />
          <Button type="primary" icon={<ReloadOutlined />} loading={loading} onClick={() => void prepare()}>准备 Step</Button>
        </Space>
        {!loading && taskId && !candidates.length ? <Alert type="warning" showIcon message="当前任务没有基线有效的开发中版本" description="请基于当前生效代码创建新版本后再发起验数。" /> : null}
      </section>
      {loading && !prepared ? <div className="compare-page-loading"><Spin /></div> : prepared ? (
        <>
          <section className="data-panel compare-step-panel">
            <div className="compare-section-title"><strong>选择 Step</strong><span>生成计划时自动补齐必要的前置依赖 Step</span></div>
            {prepared.unionId ? <Alert type="info" showIcon message={`当前候选属于联合版本 ${prepared.unionId}，联合 DDL 会一起生成调测 SQL`} /> : null}
            <div className="compare-step-grid">
              <div><Typography.Text strong>基线：{prepared.baselineVersion.versionNo ? `v${prepared.baselineVersion.versionNo}` : '初始代码'}</Typography.Text><Checkbox.Group value={baselineSteps} onChange={(value) => { setBaselineSteps(value as string[]); setPlan(undefined); }} options={prepared.baselineSteps.map((step) => ({ label: `${step.name}${step.outputTables.length ? ` · ${step.outputTables.join(', ')}` : ''}`, value: step.name }))} /></div>
              <div><Typography.Text strong>候选：v{prepared.candidateVersion.versionNo}</Typography.Text><Checkbox.Group value={candidateSteps} onChange={(value) => { setCandidateSteps(value as string[]); setPlan(undefined); }} options={prepared.candidateSteps.map((step) => ({ label: `${step.name}${step.outputTables.length ? ` · ${step.outputTables.join(', ')}` : ''}`, value: step.name }))} /></div>
            </div>
            <div className="compare-submit-bar"><Button type="primary" loading={loading} onClick={() => void generatePlan()}>生成调测 SQL</Button></div>
          </section>
          {plan ? (
            <>
              <section className="data-panel compare-sql-preview-panel">
                <div className="compare-section-title"><strong>调测 SQL 快照</strong><span>计划令牌：<code>{plan.planToken}</code></span></div>
                <Tabs className="ui-flat-tabs" size="small" items={[
                  { key: 'original-baseline', label: '基线原始代码', children: <pre className="compare-sql-code">{plan.originalBaselineSql}</pre> },
                  { key: 'original-candidate', label: '候选原始代码', children: <pre className="compare-sql-code">{plan.originalCandidateSql}</pre> },
                  { key: 'generated-baseline', label: '基线调测代码', children: <pre className="compare-sql-code">{plan.generatedBaselineSql}</pre> },
                  { key: 'generated-candidate', label: '候选调测代码', children: <pre className="compare-sql-code">{plan.generatedCandidateSql}</pre> },
                  { key: 'diff', label: '调测代码差异', children: <SqlDiff left={plan.generatedBaselineSql} right={plan.generatedCandidateSql} /> },
                ]} />
              </section>
              <section className="data-panel compare-mapping-panel">
                <div className="compare-section-title"><strong>输出表与比对规则</strong><Space><span>元数据差异时仅比较同名字段</span><Switch size="small" checked={onlySameColumns} onChange={setOnlySameColumns} /></Space></div>
                <Table rowKey="rowKey" size="small" pagination={false} columns={mappingColumns} dataSource={mappings} scroll={{ x: 1500 }} locale={{ emptyText: <Empty description="所选 Step 没有可配对的同名输出表" /> }} />
                <div className="compare-submit-bar"><Button type="primary" icon={<ExperimentOutlined />} loading={submitting} onClick={() => void submit()}>创建验数任务</Button></div>
              </section>
            </>
          ) : null}
        </>
      ) : null}
    </div>
  );
}
