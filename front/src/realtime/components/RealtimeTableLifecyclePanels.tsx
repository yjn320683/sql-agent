import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Descriptions, Empty, Modal, Select, Space, Table, Tag, Typography, message } from 'antd';
import { analyzeAssetImpact, type AssetImpact } from '../../api/assets';
import {
  compareRealtimeTableSchemaVersions,
  getRealtimeTableSchemaVersion,
  listRealtimeTableSchemaVersions,
} from '../api';
import type { RealtimeSchemaDiff, RealtimeSchemaVersion, RealtimeTable } from '../types';

const sourceLabel: Record<string, string> = {
  CREATE: '创建', MANUAL_REFRESH: '手工刷新', SCHEDULED_REFRESH: '定时刷新',
  SAFE_UPDATE: '安全变更', SYNC_EVOLUTION: '同步演进', BACKFILLED_BASELINE: '历史基线',
};

function changedColumns(diff?: RealtimeSchemaDiff) {
  return [
    ...(diff?.addedColumns ?? []),
    ...(diff?.removedColumns ?? []),
    ...(diff?.modifiedColumns ?? []).map((item) => ({ name: item.name })),
  ].map((item) => item.name);
}

function DiffView({ diff }: { diff?: RealtimeSchemaDiff }) {
  if (!diff) return <Empty description="没有差异" />;
  return <div className="schema-diff-view">
    <Descriptions bordered size="small" column={2} items={[
      { key: 'add', label: '新增字段', children: diff.addedColumns.map((item) => item.name).join('、') || '-' },
      { key: 'remove', label: '删除字段', children: diff.removedColumns.map((item) => item.name).join('、') || '-' },
      { key: 'modify', label: '修改字段', children: diff.modifiedColumns.map((item) => item.name).join('、') || '-' },
      { key: 'options', label: '参数变化', children: diff.optionChanges.map((item) => item.key).join('、') || '-' },
      { key: 'comment', label: '描述变化', children: diff.commentChanged ? '是' : '否' },
    ]} />
  </div>;
}

export function RealtimeTableSchemaHistory({ table }: { table: RealtimeTable }) {
  const [versions, setVersions] = useState<RealtimeSchemaVersion[]>([]);
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<RealtimeSchemaVersion>();
  const [fromVersion, setFromVersion] = useState<number>();
  const [toVersion, setToVersion] = useState<number>();
  const [comparison, setComparison] = useState<{ diff: RealtimeSchemaDiff; compatibility: string; fromVersion: RealtimeSchemaVersion; toVersion: RealtimeSchemaVersion }>();
  const [impact, setImpact] = useState<AssetImpact>();

  const load = async () => {
    setLoading(true);
    try {
      const records = (await listRealtimeTableSchemaVersions(table.id)).records;
      setVersions(records);
      if (records.length >= 2) {
        setFromVersion((value) => value ?? records[1].versionNo);
        setToVersion((value) => value ?? records[0].versionNo);
      }
    } catch (error) { message.error((error as Error).message); }
    finally { setLoading(false); }
  };
  useEffect(() => { void load(); }, [table.id]);

  const inspect = async (version: number) => {
    try { setDetail(await getRealtimeTableSchemaVersion(table.id, version)); }
    catch (error) { message.error((error as Error).message); }
  };
  const compare = async () => {
    if (!fromVersion || !toVersion || fromVersion === toVersion) { message.warning('请选择两个不同版本'); return; }
    try { setComparison(await compareRealtimeTableSchemaVersions(table.id, fromVersion, toVersion)); }
    catch (error) { message.error((error as Error).message); }
  };
  const analyzeVersion = async (version: RealtimeSchemaVersion) => {
    try { setImpact(await analyzeSchemaVersionImpact(table, version)); }
    catch (error) { message.error((error as Error).message); }
  };
  const options = useMemo(() => versions.map((item) => ({ value: item.versionNo, label: `v${item.versionNo} · ${sourceLabel[item.changeSource] ?? item.changeSource}` })), [versions]);

  return <>
    <div className="realtime-detail-toolbar"><Typography.Text type="secondary">只记录真实观测到的物理结构；相同指纹不会重复生成版本。</Typography.Text><Button onClick={() => void load()}>刷新历史</Button></div>
    <div className="schema-version-compare-toolbar"><Space wrap><Typography.Text>版本对比</Typography.Text><Select value={fromVersion} onChange={setFromVersion} options={options} placeholder="起始版本" style={{ width: 190 }} /><span>→</span><Select value={toVersion} onChange={setToVersion} options={options} placeholder="目标版本" style={{ width: 190 }} /><Button disabled={versions.length < 2} onClick={() => void compare()}>比较</Button></Space></div>
    <Table rowKey="id" size="small" loading={loading} pagination={false} dataSource={versions} columns={[
      { title: '版本', dataIndex: 'versionNo', width: 80 },
      { title: '来源', dataIndex: 'changeSource', render: (value) => sourceLabel[value] || value },
      { title: '兼容性', dataIndex: 'compatibility', render: (value) => <Tag color={value === 'INCOMPATIBLE' ? 'error' : value === 'COMPATIBLE' ? 'success' : 'default'}>{value === 'INCOMPATIBLE' ? '不兼容' : value === 'COMPATIBLE' ? '兼容' : '基线'}</Tag> },
      { title: '操作人', dataIndex: 'operator' }, { title: '首次发现', dataIndex: 'firstSeenAt' }, { title: '最后确认', dataIndex: 'lastSeenAt' },
      { title: '操作', width: 180, render: (_, row) => <Space><Button type="link" onClick={() => void inspect(row.versionNo)}>查看差异</Button><Button type="link" onClick={() => void analyzeVersion(row)}>查看影响</Button></Space> },
    ]} />
    <Modal open={Boolean(detail)} title={detail ? `Schema v${detail.versionNo} · ${sourceLabel[detail.changeSource] || detail.changeSource}` : 'Schema 版本'} footer={null} width={760} onCancel={() => setDetail(undefined)}><DiffView diff={detail?.diff} />{detail?.schema?.columns ? <><Typography.Title level={5}>版本字段</Typography.Title><Table size="small" pagination={false} rowKey="name" dataSource={detail.schema.columns} columns={[{ title: '字段', dataIndex: 'name' }, { title: '类型', dataIndex: 'dataType' }, { title: '可空', render: (_, row) => row.nullable ? '是' : '否' }, { title: '主键', render: (_, row) => row.primaryKey ? '是' : '-' }, { title: '分区', render: (_, row) => row.partitionKey ? '是' : '-' }]} /></> : null}</Modal>
    <Modal open={Boolean(comparison)} title={comparison ? `Schema v${comparison.fromVersion.versionNo} → v${comparison.toVersion.versionNo}` : 'Schema 对比'} footer={null} width={760} onCancel={() => setComparison(undefined)}>{comparison ? <><Tag color={comparison.compatibility === 'INCOMPATIBLE' ? 'error' : 'success'}>{comparison.compatibility === 'INCOMPATIBLE' ? '不兼容' : '兼容'}</Tag><DiffView diff={comparison.diff} /></> : null}</Modal>
    <Modal open={Boolean(impact)} title="Schema 版本变更影响" footer={null} width={900} onCancel={() => setImpact(undefined)}><ImpactSummary value={impact} /></Modal>
  </>;
}

export function ImpactSummary({ value }: { value?: AssetImpact }) {
  if (!value) return <Empty description="暂无影响分析" />;
  return <div className="impact-summary">
    {!value.complete ? <Alert type="warning" showIcon message="影响范围可能不完整" description={value.missingReasons.join('；')} /> : null}
    {!value.fieldImpactKnown ? <Alert type="warning" showIcon message="仅能确认表级影响，字段范围未知" /> : null}
    <Descriptions bordered size="small" column={2} items={[{ key: 'tasks', label: '受影响任务', children: value.affectedTasks.length }, { key: 'assets', label: '下游资产', children: value.affectedAssets.length }]} />
    <Typography.Title level={5}>受影响任务</Typography.Title><Table size="small" pagination={false} rowKey="id" dataSource={value.affectedTasks} columns={[{ title: '任务', dataIndex: 'taskName' }, { title: '范围', dataIndex: 'taskScope' }, { title: '类型', dataIndex: 'taskType' }]} />
    <Typography.Title level={5}>下游资产</Typography.Title><Table size="small" pagination={false} rowKey="id" dataSource={value.affectedAssets} columns={[{ title: '资产', dataIndex: 'qualifiedName' }, { title: 'Catalog', dataIndex: 'catalog' }]} />
  </div>;
}

export function RealtimeTableImpactPanel({ table }: { table: RealtimeTable }) {
  const [value, setValue] = useState<AssetImpact>(); const [loading, setLoading] = useState(false);
  const load = async () => { setLoading(true); try { setValue(await analyzeAssetImpact({ catalog: table.catalogName || 'paimon', database: table.databaseName, table: table.tableName, columns: [], changeType: 'TABLE_CHANGE' })); } catch (error) { message.error((error as Error).message); } finally { setLoading(false); } };
  useEffect(() => { void load(); }, [table.id]);
  return <><div className="realtime-detail-toolbar"><Typography.Text type="secondary">展示生效离线版本、最新实时版本和受管表引用的下游影响。</Typography.Text><Button loading={loading} onClick={() => void load()}>重新分析</Button></div><ImpactSummary value={value} /></>;
}

export async function analyzeSchemaVersionImpact(table: RealtimeTable, version: RealtimeSchemaVersion) {
  return analyzeAssetImpact({ catalog: table.catalogName || 'paimon', database: table.databaseName, table: table.tableName, columns: changedColumns(version.diff), changeType: version.compatibility === 'INCOMPATIBLE' ? 'INCOMPATIBLE_SCHEMA_CHANGE' : 'COMPATIBLE_SCHEMA_CHANGE' });
}
