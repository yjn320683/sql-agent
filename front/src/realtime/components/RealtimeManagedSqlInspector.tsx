import { useMemo, useState } from 'react';
import {
  CopyOutlined,
  DatabaseOutlined,
  DownOutlined,
  FunctionOutlined,
  KeyOutlined,
  PlusOutlined,
  ReloadOutlined,
  RightOutlined,
  SearchOutlined,
  TableOutlined,
} from '@ant-design/icons';
import { Button, Empty, Input, Select, Skeleton, Tabs, Tooltip, Typography, message } from 'antd';
import { getRealtimeTable } from '../api';
import type { RealtimeTable } from '../types';
import { createRealtimeTableDdl } from '../utils/realtimeTableDdl';

interface Props {
  tables: RealtimeTable[];
  database?: string;
  onDatabaseChange: (database: string) => void;
  onInsertSql: (text: string) => void;
  onRefresh: () => Promise<void>;
}

type MetadataModule = 'tables' | 'columns' | 'ddl';

const flinkFunctions = [
  ['COALESCE', 'COALESCE(value1, value2)'],
  ['CAST', 'CAST(value AS type)'],
  ['CONCAT', 'CONCAT(value1, value2)'],
  ['DATE_FORMAT', 'DATE_FORMAT(timestamp, format)'],
  ['CURRENT_TIMESTAMP', 'CURRENT_TIMESTAMP'],
  ['IF', 'IF(condition, true_value, false_value)'],
  ['JSON_VALUE', 'JSON_VALUE(json, path)'],
  ['ROW_NUMBER', 'ROW_NUMBER() OVER (...)'],
  ['SUM', 'SUM(value)'],
  ['COUNT', 'COUNT(*)'],
] as const;

export default function RealtimeManagedSqlInspector({ tables, database, onDatabaseChange, onInsertSql, onRefresh }: Props) {
  const [keyword, setKeyword] = useState('');
  const [functionKeyword, setFunctionKeyword] = useState('');
  const [selectedTable, setSelectedTable] = useState<RealtimeTable>();
  const [tableDetailLoading, setTableDetailLoading] = useState(false);
  const [expanded, setExpanded] = useState<Record<MetadataModule, boolean>>({ tables: true, columns: true, ddl: true });
  const databases = useMemo(() => Array.from(new Set(tables.map((table) => table.databaseName))), [tables]);
  const visibleTables = useMemo(() => tables.filter((table) => (!database || table.databaseName === database)
    && (!keyword.trim() || `${table.databaseName}.${table.tableName}`.toLowerCase().includes(keyword.trim().toLowerCase()))), [database, keyword, tables]);
  const ddl = selectedTable?.ddl || createRealtimeTableDdl(selectedTable);
  const toggle = (name: MetadataModule) => setExpanded((value) => ({ ...value, [name]: !value[name] }));
  const moduleClass = (name: MetadataModule, extra: string) => `metadata-module ${extra}${expanded[name] ? '' : ' collapsed'}`;
  const copyDdl = async () => {
    if (!ddl) return;
    try { await navigator.clipboard.writeText(ddl); message.success('建表语句已复制'); }
    catch { message.error('复制失败，请手动选择建表语句'); }
  };
  const selectTable = async (table: RealtimeTable) => {
    setSelectedTable(table); setTableDetailLoading(true);
    try { setSelectedTable(await getRealtimeTable(table.id)); }
    catch (error) { message.error(`读取实时表结构失败：${(error as Error).message}`); }
    finally { setTableDetailLoading(false); }
  };

  const metadata = <div className="metadata-browser managed-realtime-metadata-browser">
    <div className="metadata-browser-header"><span><DatabaseOutlined /> 数据目录</span><Tooltip title="刷新元数据"><Button type="text" size="small" icon={<ReloadOutlined />} onClick={() => void onRefresh()} /></Tooltip></div>
    <div className="metadata-filters">
      <Select showSearch value={database} options={databases.map((value) => ({ value, label: value }))} placeholder="选择数据库" onChange={(value) => { setSelectedTable(undefined); onDatabaseChange(value); }} />
      <Input allowClear prefix={<SearchOutlined />} value={keyword} placeholder="搜索当前库的表" onChange={(event) => setKeyword(event.target.value)} />
    </div>
    <section className={moduleClass('tables', 'metadata-table-module')}>
      <div className="metadata-section-title"><button type="button" onClick={() => toggle('tables')}>{expanded.tables ? <DownOutlined /> : <RightOutlined />}<span>数据表</span><span>{visibleTables.length}</span></button></div>
      {expanded.tables ? <div className="metadata-table-list">{visibleTables.map((table) => <div key={table.id} role="button" tabIndex={0} className={selectedTable?.id === table.id ? 'metadata-table-row active' : 'metadata-table-row'} onClick={() => void selectTable(table)} onKeyDown={(event) => { if (event.key === 'Enter') void selectTable(table); }}><TableOutlined /><Tooltip title={table.tableComment || table.tableName}><span>{table.tableName}</span></Tooltip><Tooltip title="插入表名"><Button type="text" size="small" icon={<PlusOutlined />} onClick={(event) => { event.stopPropagation(); onInsertSql(`paimon.${table.databaseName}.${table.tableName}`); }} /></Tooltip></div>)}{!visibleTables.length ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="未找到数据表" /> : null}</div> : null}
    </section>
    <section className={moduleClass('columns', 'metadata-column-module')}>
      <div className="metadata-section-title"><button type="button" onClick={() => toggle('columns')}>{expanded.columns ? <DownOutlined /> : <RightOutlined />}<span>{selectedTable ? `${selectedTable.tableName} · 字段` : '字段'}</span><span>{selectedTable?.columns?.length ?? 0}</span></button></div>
      {expanded.columns ? <div className="metadata-column-list">{tableDetailLoading ? <Skeleton active title={false} paragraph={{ rows: 4 }} /> : selectedTable?.columns?.map((column) => <div key={column.name} className="metadata-column-row"><span className="metadata-column-name">{column.primaryKey || column.partitionKey ? <KeyOutlined /> : <span className="column-dot" />}<Tooltip title={column.comment || column.name}><span>{column.name}</span></Tooltip></span><Typography.Text type="secondary">{column.dataType}</Typography.Text><Tooltip title="插入字段名"><Button type="text" size="small" icon={<PlusOutlined />} onClick={() => onInsertSql(`\`${column.name}\``)} /></Tooltip></div>)}{!selectedTable ? <div className="metadata-hint">选择数据表查看字段</div> : null}</div> : null}
    </section>
    <section className={moduleClass('ddl', 'metadata-ddl-module')}>
      <div className="metadata-section-title"><button type="button" onClick={() => toggle('ddl')}>{expanded.ddl ? <DownOutlined /> : <RightOutlined />}<span>建表语句</span></button><span className="metadata-section-actions"><Tooltip title="复制建表语句"><Button type="text" size="small" disabled={!ddl} icon={<CopyOutlined />} onClick={() => void copyDdl()} /></Tooltip></span></div>
      {expanded.ddl ? <div className="metadata-ddl-content">{tableDetailLoading ? <Skeleton active title={false} paragraph={{ rows: 4 }} /> : ddl ? <pre>{ddl}</pre> : <div className="metadata-hint">选择数据表查看建表语句</div>}</div> : null}
    </section>
  </div>;

  const functions = <div className="workspace-function-panel managed-flink-function-panel">
    <div className="metadata-browser-header"><span><FunctionOutlined /> Flink SQL 函数</span></div>
    <div className="workspace-function-search"><Input allowClear prefix={<SearchOutlined />} value={functionKeyword} placeholder="搜索 Flink SQL 函数" onChange={(event) => setFunctionKeyword(event.target.value)} /><span>常用内置函数</span></div>
    <div className="workspace-function-list">{flinkFunctions.filter(([name, signature]) => `${name} ${signature}`.toLowerCase().includes(functionKeyword.trim().toLowerCase())).map(([name, signature]) => <button type="button" key={name} className="workspace-function-row" onClick={() => onInsertSql(signature)}><FunctionOutlined /><span><code>{name}</code><small>{signature}</small></span><PlusOutlined /></button>)}</div>
  </div>;

  return <Tabs className="ui-flat-tabs managed-sql-inspector-tabs" items={[
    { key: 'metadata', label: <span><DatabaseOutlined />元数据</span>, children: metadata },
    { key: 'functions', label: <span><FunctionOutlined />函数</span>, children: functions },
  ]} />;
}
