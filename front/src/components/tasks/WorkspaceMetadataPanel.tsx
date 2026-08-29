import { useEffect, useMemo, useState } from 'react';
import {
  Button,
  Empty,
  Input,
  Select,
  Skeleton,
  Tooltip,
  Typography,
  message,
} from 'antd';
import {
  CopyOutlined,
  DatabaseOutlined,
  DownOutlined,
  FullscreenExitOutlined,
  FullscreenOutlined,
  KeyOutlined,
  PlusOutlined,
  ReloadOutlined,
  RightOutlined,
  SearchOutlined,
  TableOutlined,
} from '@ant-design/icons';
import { getHiveColumns, getHiveTableDdl, listHiveDatabases, searchHiveTables } from '../../api/workspace';
import type { HiveColumnVO, HiveTableVO } from '../../types';

type MetadataModule = 'tables' | 'columns' | 'ddl';

interface Props {
  database?: string;
  onDatabaseChange: (database: string) => void;
  onInsertSql: (text: string) => void;
}

export default function WorkspaceMetadataPanel({ database, onDatabaseChange, onInsertSql }: Props) {
  const [databases, setDatabases] = useState<string[]>([]);
  const [tables, setTables] = useState<HiveTableVO[]>([]);
  const [columns, setColumns] = useState<HiveColumnVO[]>([]);
  const [ddl, setDdl] = useState('');
  const [selectedTable, setSelectedTable] = useState<HiveTableVO>();
  const [keyword, setKeyword] = useState('');
  const [metadataLoading, setMetadataLoading] = useState(true);
  const [tableLoading, setTableLoading] = useState(false);
  const [columnLoading, setColumnLoading] = useState(false);
  const [ddlLoading, setDdlLoading] = useState(false);
  const [expandedModules, setExpandedModules] = useState<Record<MetadataModule, boolean>>({
    tables: true,
    columns: true,
    ddl: true,
  });
  const [maximizedModule, setMaximizedModule] = useState<MetadataModule>();

  const loadDatabases = async () => {
    setMetadataLoading(true);
    try {
      const response = await listHiveDatabases();
      setDatabases(response.databases);
      if (!database && response.databases.length) {
        onDatabaseChange(response.databases.includes('default') ? 'default' : response.databases[0]);
      }
    } catch (error) {
      message.error({
        key: 'workspace-metadata-databases-error',
        content: `加载 Hive 数据库失败：${(error as Error).message}`,
      });
    } finally {
      setMetadataLoading(false);
    }
  };

  useEffect(() => { void loadDatabases(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!database) {
      setTables([]);
      return undefined;
    }
    const timer = window.setTimeout(async () => {
      setTableLoading(true);
      try {
        const response = await searchHiveTables(database, keyword.trim());
        setTables(response.items);
        if (selectedTable && !response.items.some((item) => item.table === selectedTable.table)) {
          setSelectedTable(undefined);
          setColumns([]);
          setDdl('');
        }
      } catch (error) {
        setTables([]);
        message.error(`加载 Hive 表失败：${(error as Error).message}`);
      } finally {
        setTableLoading(false);
      }
    }, 250);
    return () => window.clearTimeout(timer);
  }, [database, keyword]); // eslint-disable-line react-hooks/exhaustive-deps

  const selectTable = async (table: HiveTableVO) => {
    setSelectedTable(table);
    setColumnLoading(true);
    setDdlLoading(true);
    setExpandedModules((current) => ({ ...current, columns: true, ddl: true }));
    const [columnResult, ddlResult] = await Promise.allSettled([
      getHiveColumns(table.db, table.table),
      getHiveTableDdl(table.db, table.table),
    ]);
    if (columnResult.status === 'fulfilled') {
      setColumns(columnResult.value.columns);
    } else {
      setColumns([]);
      message.error(`加载字段失败：${(columnResult.reason as Error).message}`);
    }
    if (ddlResult.status === 'fulfilled') {
      setDdl(ddlResult.value.ddl);
    } else {
      setDdl('');
      message.error(`加载建表语句失败：${(ddlResult.reason as Error).message}`);
    }
    setColumnLoading(false);
    setDdlLoading(false);
  };

  const toggleModule = (module: MetadataModule) => {
    if (maximizedModule === module) setMaximizedModule(undefined);
    setExpandedModules((current) => ({ ...current, [module]: !current[module] }));
  };

  const toggleMaximize = (module: MetadataModule) => {
    setExpandedModules((current) => ({ ...current, [module]: true }));
    setMaximizedModule((current) => current === module ? undefined : module);
  };

  const moduleClassName = (module: MetadataModule, className = '') => [
    'metadata-module',
    className,
    expandedModules[module] ? '' : 'collapsed',
    maximizedModule === module ? 'maximized' : '',
    maximizedModule && maximizedModule !== module ? 'hidden' : '',
  ].filter(Boolean).join(' ');

  const copyDdl = async () => {
    if (!ddl) return;
    try {
      await navigator.clipboard.writeText(ddl);
      message.success('建表语句已复制');
    } catch {
      message.error('复制失败，请手动选择建表语句');
    }
  };

  const databaseOptions = useMemo(
    () => databases.map((item) => ({ label: item, value: item })),
    [databases],
  );

  if (metadataLoading) return <div className="metadata-loading"><Skeleton active paragraph={{ rows: 8 }} /></div>;

  return (
    <div className={`metadata-browser${maximizedModule ? ' module-maximized' : ''}`}>
      <div className="metadata-browser-header">
        <span><DatabaseOutlined /> 数据目录</span>
        <Tooltip title="刷新元数据">
          <Button type="text" size="small" icon={<ReloadOutlined />} onClick={() => void loadDatabases()} />
        </Tooltip>
      </div>
      <div className="metadata-filters">
        <Select
          showSearch
          value={database}
          options={databaseOptions}
          placeholder="选择数据库"
          onChange={(value) => {
            setSelectedTable(undefined);
            setColumns([]);
            setDdl('');
            onDatabaseChange(value);
          }}
        />
        <Input
          allowClear
          prefix={<SearchOutlined />}
          value={keyword}
          placeholder="搜索当前库的表"
          onChange={(event) => setKeyword(event.target.value)}
        />
      </div>
      <section className={moduleClassName('tables', 'metadata-table-module')}>
        <div className="metadata-section-title">
          <button type="button" onClick={() => toggleModule('tables')}>
            {expandedModules.tables ? <DownOutlined /> : <RightOutlined />}
            <span>数据表</span><span>{tables.length}</span>
          </button>
          <Tooltip placement="topRight" title={maximizedModule === 'tables' ? '恢复全部模块' : '放大数据表'}>
            <Button type="text" size="small" icon={maximizedModule === 'tables' ? <FullscreenExitOutlined /> : <FullscreenOutlined />} onClick={() => toggleMaximize('tables')} />
          </Tooltip>
        </div>
        {expandedModules.tables ? <div className="metadata-table-list">
          {tableLoading ? <Skeleton active paragraph={{ rows: 5 }} title={false} /> : tables.map((table) => (
            <div
              key={`${table.db}.${table.table}`}
              className={selectedTable?.table === table.table ? 'metadata-table-row active' : 'metadata-table-row'}
              onClick={() => void selectTable(table)}
              role="button"
              tabIndex={0}
              onKeyDown={(event) => { if (event.key === 'Enter') void selectTable(table); }}
            >
              <TableOutlined />
              <Tooltip title={table.comment || table.table}><span>{table.table}</span></Tooltip>
              <Tooltip title="插入表名">
                <Button
                  type="text"
                  size="small"
                  icon={<PlusOutlined />}
                  onClick={(event) => {
                    event.stopPropagation();
                    onInsertSql(`${table.db}.${table.table}`);
                  }}
                />
              </Tooltip>
            </div>
          ))}
          {!tableLoading && !tables.length ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="未找到数据表" /> : null}
        </div> : null}
      </section>
      <section className={moduleClassName('columns', 'metadata-column-module')}>
        <div className="metadata-section-title">
          <button type="button" onClick={() => toggleModule('columns')}>
            {expandedModules.columns ? <DownOutlined /> : <RightOutlined />}
            <span>{selectedTable ? `${selectedTable.table} · 字段` : '字段'}</span><span>{columns.length}</span>
          </button>
          <Tooltip placement="topRight" title={maximizedModule === 'columns' ? '恢复全部模块' : '放大字段'}>
            <Button type="text" size="small" icon={maximizedModule === 'columns' ? <FullscreenExitOutlined /> : <FullscreenOutlined />} onClick={() => toggleMaximize('columns')} />
          </Tooltip>
        </div>
        {expandedModules.columns ? <div className="metadata-column-list">
          {columnLoading ? <Skeleton active paragraph={{ rows: 5 }} title={false} /> : columns.map((column) => (
            <div key={column.name} className="metadata-column-row">
              <span className="metadata-column-name">
                {column.partitionKey ? <KeyOutlined /> : <span className="column-dot" />}
                <Tooltip title={column.comment || column.name}><span>{column.name}</span></Tooltip>
              </span>
              <Typography.Text type="secondary">{column.dataType}</Typography.Text>
              <Tooltip title="插入字段名">
                <Button type="text" size="small" icon={<PlusOutlined />} onClick={() => onInsertSql(column.name)} />
              </Tooltip>
            </div>
          ))}
          {!columnLoading && selectedTable && !columns.length ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无字段" /> : null}
          {!selectedTable ? <div className="metadata-hint">选择数据表查看字段</div> : null}
        </div> : null}
      </section>
      <section className={moduleClassName('ddl', 'metadata-ddl-module')}>
        <div className="metadata-section-title">
          <button type="button" onClick={() => toggleModule('ddl')}>
            {expandedModules.ddl ? <DownOutlined /> : <RightOutlined />}
            <span>建表语句</span>
          </button>
          <span className="metadata-section-actions">
            <Tooltip placement="topRight" title="复制建表语句"><Button type="text" size="small" disabled={!ddl} icon={<CopyOutlined />} onClick={() => void copyDdl()} /></Tooltip>
            <Tooltip placement="topRight" title={maximizedModule === 'ddl' ? '恢复全部模块' : '放大建表语句'}>
              <Button type="text" size="small" icon={maximizedModule === 'ddl' ? <FullscreenExitOutlined /> : <FullscreenOutlined />} onClick={() => toggleMaximize('ddl')} />
            </Tooltip>
          </span>
        </div>
        {expandedModules.ddl ? <div className="metadata-ddl-content">
          {ddlLoading ? <Skeleton active paragraph={{ rows: 5 }} title={false} /> : ddl ? <pre>{ddl}</pre> : <div className="metadata-hint">选择数据表查看建表语句</div>}
        </div> : null}
      </section>
    </div>
  );
}
