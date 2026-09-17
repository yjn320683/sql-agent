import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Descriptions,
  Empty,
  Input,
  Pagination,
  Select,
  Skeleton,
  Space,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import {
  CopyOutlined,
  DatabaseOutlined,
  ReloadOutlined,
  SearchOutlined,
  TableOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  getHivePartitions,
  getHiveStorageLayout,
  getHiveTable,
  getHiveTableDdl,
  getHiveTableFreshness,
  getHiveTableStatistics,
  listHiveDatabases,
  searchHiveTables,
} from '../../api/workspace';
import type {
  HiveColumnVO,
  HiveDdlVO,
  HivePartitionVO,
  HivePartitionsVO,
  HiveStatisticsVO,
  HiveStorageLayoutVO,
  HiveTableFreshnessVO,
  HiveTableDetailVO,
  HiveTableVO,
} from '../../types';
import {
  assignAssetDomain,
  getAssetDomainAssignment,
  listBusinessDomainAssets,
  listBusinessDomainOptions,
  unassignAssetDomain,
} from '../../realtime/api';
import type { BusinessDomain } from '../../realtime/types';

type DetailTab = 'columns' | 'partitions' | 'statistics' | 'storage' | 'freshness' | 'ddl';

const TABLE_PAGE_SIZE = 30;
const PARTITION_PAGE_SIZE = 20;

function displayValue(value: unknown): string {
  if (value == null || value === '') return '-';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

function formatBytes(value: unknown): string {
  const bytes = Number(value);
  if (!Number.isFinite(bytes)) return displayValue(value);
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
  let current = bytes;
  let index = 0;
  while (current >= 1024 && index < units.length - 1) {
    current /= 1024;
    index += 1;
  }
  return `${current >= 10 || index === 0 ? current.toFixed(0) : current.toFixed(1)} ${units[index]}`;
}

function formatTimestamp(value: unknown): string {
  const timestamp = Number(value);
  if (!Number.isFinite(timestamp) || timestamp <= 0) return '-';
  return new Date(timestamp).toLocaleString('zh-CN', { hour12: false });
}

function formatDuration(value: unknown): string {
  if (value == null || value === '') return '-';
  const seconds = Number(value);
  if (!Number.isFinite(seconds) || seconds < 0) return '-';
  if (seconds < 60) return `${Math.floor(seconds)} 秒`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟`;
  if (seconds < 86400) return `${(seconds / 3600).toFixed(1)} 小时`;
  return `${(seconds / 86400).toFixed(1)} 天`;
}

function FactTable({ value, emptyText }: { value: Record<string, unknown>; emptyText: string }) {
  const rows = Object.entries(value).map(([key, item]) => ({ key, value: displayValue(item) }));
  return (
    <Table
      rowKey="key"
      size="small"
      pagination={false}
      dataSource={rows}
      columns={[
        { title: '指标', dataIndex: 'key', width: 220, render: (text) => <code>{text}</code> },
        { title: '值', dataIndex: 'value', render: (text) => <span className="catalog-fact-value">{text}</span> },
      ]}
      locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={emptyText} /> }}
    />
  );
}

export default function DataCatalogPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedTableRef = useRef(searchParams.get('table') || '');
  const [databases, setDatabases] = useState<string[]>([]);
  const [database, setDatabase] = useState(searchParams.get('db') || '');
  const [keyword, setKeyword] = useState(searchParams.get('keyword') || '');
  const [committedKeyword, setCommittedKeyword] = useState(searchParams.get('keyword') || '');
  const [tablePage, setTablePage] = useState(1);
  const [tables, setTables] = useState<HiveTableVO[]>([]);
  const [tableTotal, setTableTotal] = useState(0);
  const [selected, setSelected] = useState<HiveTableVO>();
  const [tableLoading, setTableLoading] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detail, setDetail] = useState<HiveTableDetailVO>();
  const [detailError, setDetailError] = useState('');
  const [activeTab, setActiveTab] = useState<DetailTab>('columns');
  const [partitionPage, setPartitionPage] = useState(1);
  const [partitions, setPartitions] = useState<HivePartitionsVO>();
  const [statistics, setStatistics] = useState<HiveStatisticsVO>();
  const [storage, setStorage] = useState<HiveStorageLayoutVO>();
  const [freshness, setFreshness] = useState<HiveTableFreshnessVO>();
  const [ddl, setDdl] = useState<HiveDdlVO>();
  const [tabLoading, setTabLoading] = useState(false);
  const [tabError, setTabError] = useState('');
  const [domainOptions, setDomainOptions] = useState<BusinessDomain[]>([]);
  const [domainId, setDomainId] = useState<number>();
  const [domainFilter, setDomainFilter] = useState<number>();
  const [domainSaving, setDomainSaving] = useState(false);

  const selectedKey = selected ? `${selected.db}.${selected.table}` : '';

  const syncQuery = useCallback((db: string, pattern: string, table?: HiveTableVO) => {
    const params = new URLSearchParams();
    if (db) params.set('db', db);
    if (pattern) params.set('keyword', pattern);
    if (table) params.set('table', table.table);
    setSearchParams(params, { replace: true });
  }, [setSearchParams]);

  useEffect(() => {
    let active = true;
    void listHiveDatabases().then((result) => {
      if (!active) return;
      setDatabases(result.databases);
      setDatabase((current) => current || (result.databases.includes('default') ? 'default' : result.databases[0] || ''));
    }).catch((error) => message.error(`加载 Hive 数据库失败：${(error as Error).message}`));
    return () => { active = false; };
  }, []);

  useEffect(() => {
    void listBusinessDomainOptions().then(setDomainOptions).catch(() => setDomainOptions([]));
  }, []);

  const loadTables = useCallback(async () => {
    if (!database) return;
    setTableLoading(true);
    try {
      const result = domainFilter
        ? await listBusinessDomainAssets(domainFilter, new URLSearchParams({
          assetType: 'HIVE', databaseName: database, keyword: committedKeyword,
          page: String(tablePage), pageSize: String(TABLE_PAGE_SIZE),
        })).then((pageResult) => ({
          items: pageResult.records.map((item): HiveTableVO => ({
            catalog: item.catalogName || 'hive', db: item.databaseName || database,
            table: item.tableName || '', tableType: 'HIVE', comment: '业务域资产',
          })),
          total: pageResult.total,
        }))
        : await searchHiveTables(
          database,
          committedKeyword,
          TABLE_PAGE_SIZE,
          (tablePage - 1) * TABLE_PAGE_SIZE,
        );
      setTables(result.items.filter((item) => item.table));
      setTableTotal(result.total);
      setSelected((current) => {
        const requested = requestedTableRef.current;
        requestedTableRef.current = '';
        const next = result.items.find((item) => item.table === requested)
          || result.items.find((item) => current && `${item.db}.${item.table}` === `${current.db}.${current.table}`)
          || result.items[0];
        syncQuery(database, committedKeyword, next);
        return next;
      });
    } catch (error) {
      setTables([]);
      setTableTotal(0);
      setSelected(undefined);
      message.error(`加载 Hive 表失败：${(error as Error).message}`);
    } finally {
      setTableLoading(false);
    }
  }, [committedKeyword, database, domainFilter, syncQuery, tablePage]);

  useEffect(() => { void loadTables(); }, [loadTables]);

  useEffect(() => {
    if (!selected) {
      setDetail(undefined);
      return;
    }
    let active = true;
    setDetailLoading(true);
    setDetailError('');
    setPartitions(undefined);
    setStatistics(undefined);
    setStorage(undefined);
    setFreshness(undefined);
    setDdl(undefined);
    setPartitionPage(1);
    void getHiveTable(selected.db, selected.table).then((result) => {
      if (active) setDetail(result);
    }).catch((error) => {
      if (active) setDetailError((error as Error).message);
    }).finally(() => {
      if (active) setDetailLoading(false);
    });
    return () => { active = false; };
  }, [selectedKey]);

  useEffect(() => {
    if (!selected) {
      setDomainId(undefined);
      return;
    }
    let active = true;
    void getAssetDomainAssignment('HIVE', 'hive', selected.db, selected.table)
      .then((value) => { if (active) setDomainId(value.domainId); })
      .catch(() => { if (active) setDomainId(undefined); });
    return () => { active = false; };
  }, [selected, selectedKey]);

  const changeDomain = async (nextDomainId?: number) => {
    if (!selected) return;
    setDomainSaving(true);
    try {
      if (nextDomainId) {
        await assignAssetDomain({
          assetType: 'HIVE', catalogName: 'hive', databaseName: selected.db,
          tableName: selected.table, domainId: nextDomainId,
        });
        setDomainId(nextDomainId);
        message.success('业务域已关联');
      } else {
        await unassignAssetDomain('HIVE', 'hive', selected.db, selected.table);
        setDomainId(undefined);
        message.success('业务域关联已解除');
      }
    } catch (error) {
      message.error(`更新业务域失败：${(error as Error).message}`);
    } finally {
      setDomainSaving(false);
    }
  };

  useEffect(() => {
    if (!selected || activeTab === 'columns') return;
    if (activeTab === 'partitions' && partitions && partitions.offset === (partitionPage - 1) * PARTITION_PAGE_SIZE) return;
    if (activeTab === 'statistics' && statistics) return;
    if (activeTab === 'storage' && storage) return;
    if (activeTab === 'freshness' && freshness) return;
    if (activeTab === 'ddl' && ddl) return;
    let active = true;
    setTabLoading(true);
    setTabError('');
    const request = activeTab === 'partitions'
      ? getHivePartitions(selected.db, selected.table, partitionPage, PARTITION_PAGE_SIZE).then(setPartitions)
      : activeTab === 'statistics'
        ? getHiveTableStatistics(
          selected.db,
          selected.table,
          (detail?.table.columns || []).map((column) => column.name).slice(0, 50),
        ).then(setStatistics)
        : activeTab === 'storage'
          ? getHiveStorageLayout(selected.db, selected.table).then(setStorage)
          : activeTab === 'freshness'
            ? getHiveTableFreshness(selected.db, selected.table).then(setFreshness)
          : getHiveTableDdl(selected.db, selected.table).then(setDdl);
    void request.catch((error) => {
      if (active) setTabError((error as Error).message);
    }).finally(() => {
      if (active) setTabLoading(false);
    });
    return () => { active = false; };
  }, [activeTab, ddl, detail, freshness, partitionPage, partitions, selected, selectedKey, statistics, storage]);

  useEffect(() => {
    const publishAiContext = () => window.dispatchEvent(new CustomEvent('sql-agent:ai-context-update', {
      detail: selected ? {
        contextType: 'CATALOG_TABLE',
        entityId: `${selected.db}.${selected.table}`,
        title: `数据目录 · ${selected.db}.${selected.table}`,
      } : { contextType: 'CATALOG_TABLE', title: '数据目录' },
    }));
    publishAiContext();
    window.addEventListener('sql-agent:ai-context-request', publishAiContext);
    return () => window.removeEventListener('sql-agent:ai-context-request', publishAiContext);
  }, [selected]);

  const chooseTable = (table: HiveTableVO) => {
    setSelected(table);
    setActiveTab('columns');
    syncQuery(database, committedKeyword, table);
  };

  const submitSearch = () => {
    setTablePage(1);
    setCommittedKeyword(keyword.trim());
  };

  const columnRows = detail?.table.columns || [];
  const columnDefinitions: ColumnsType<HiveColumnVO> = [
    { title: '字段名', dataIndex: 'name', width: 220, fixed: 'left', render: (value) => <code className="catalog-column-name">{value}</code> },
    { title: '类型', dataIndex: 'dataType', width: 190, fixed: 'left', render: (value) => <Tag>{value}</Tag> },
    { title: '分区键', dataIndex: 'partitionKey', width: 92, render: (value) => value ? <Tag color="blue">是</Tag> : '-' },
    { title: '可空', dataIndex: 'nullable', width: 80, render: (value) => value ? '是' : '否' },
    { title: '注释', dataIndex: 'comment', ellipsis: true, render: (value) => value || <span className="muted-text">-</span> },
  ];

  const partitionColumns: ColumnsType<HivePartitionVO> = [
    { title: '分区', dataIndex: 'name', width: 300, fixed: 'left', render: (value) => <code>{value}</code> },
    { title: '分区值', dataIndex: 'values', width: 300, render: (value) => displayValue(value) },
    { title: 'HDFS 路径', dataIndex: 'location', ellipsis: true, render: (value) => value ? <code>{value}</code> : '-' },
  ];

  const statisticsContent = useMemo(() => {
    if (!statistics) return null;
    return (
      <div className="catalog-facts-stack">
        {!statistics.complete || statistics.warnings.length ? (
          <Alert
            type="warning"
            showIcon
            message="统计信息不完整"
            description={[...statistics.warnings, ...statistics.missingReasons].join('；')}
          />
        ) : null}
        <Typography.Title level={5}>表级统计</Typography.Title>
        <FactTable value={statistics.tableStatistics} emptyText="Metastore 中没有表级统计" />
        <Typography.Title level={5}>字段统计</Typography.Title>
        <Table
          rowKey={(row, index) => String(row.column || row.columnName || row.colName || index)}
          size="small"
          pagination={false}
          dataSource={statistics.columnStatistics}
          columns={[
            { title: '字段', render: (_, row) => <code>{displayValue(row.column || row.columnName || row.colName)}</code> },
            { title: '统计详情', render: (_, row) => <span className="catalog-fact-value">{displayValue(row)}</span> },
          ]}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="所选字段没有列统计" /> }}
        />
      </div>
    );
  }, [statistics]);

  const storageContent = storage ? (
    <div className="catalog-facts-stack">
      {!storage.complete || storage.warnings.length ? (
        <Alert
          type="warning"
          showIcon
          message="存储扫描不完整"
          description={[...storage.warnings, ...storage.missingReasons].join('；')}
        />
      ) : null}
      <div className="catalog-storage-summary">
        {[
          ['逻辑大小', formatBytes(storage.summary.lengthBytes)],
          ['实际空间', formatBytes(storage.summary.spaceConsumedBytes)],
          ['文件数', displayValue(storage.summary.fileCount)],
          ['目录数', displayValue(storage.summary.directoryCount)],
          ['采样文件', displayValue(storage.summary.sampledFileCount)],
        ].map(([label, value]) => <div key={label}><span>{label}</span><strong>{value}</strong></div>)}
      </div>
      <Typography.Title level={5}>存储路径</Typography.Title>
      <Table
        rowKey={(row) => String(row.path)}
        size="small"
        pagination={false}
        dataSource={storage.paths}
        columns={[
          { title: 'HDFS 路径', dataIndex: 'path', ellipsis: true, render: (value) => <code>{displayValue(value)}</code> },
          { title: '大小', dataIndex: 'length', width: 120, render: formatBytes },
          { title: '文件', dataIndex: 'fileCount', width: 90, render: displayValue },
          { title: '目录', dataIndex: 'directoryCount', width: 90, render: displayValue },
        ]}
      />
      <Typography.Title level={5}>文件大小分布</Typography.Title>
      <FactTable value={storage.fileSizeDistribution} emptyText="没有可用的文件样本" />
    </div>
  ) : null;

  const freshnessContent = freshness ? (
    <div className="catalog-facts-stack">
      <Alert
        type={freshness.complete ? 'info' : 'warning'}
        showIcon
        message="时间口径说明"
        description={(freshness.warnings.length ? freshness.warnings : freshness.missingReasons).join('；')}
      />
      <div className="catalog-storage-summary">
        {[
          ['分区总数', displayValue(freshness.partitionSummary.totalPartitions)],
          ['扫描分区', displayValue(freshness.partitionSummary.scannedPartitionCount)],
          ['有效路径', `${freshness.partitionSummary.pathSampleCount}/${freshness.partitionSummary.candidatePathCount}`],
          ['最近存储变更', formatTimestamp(freshness.latestStorageModificationTime)],
          ['距当前时间', formatDuration(freshness.storageAgeSeconds)],
        ].map(([label, value]) => <div key={label}><span>{label}</span><strong>{value}</strong></div>)}
      </div>
      {freshness.partitionSummary.candidateLatestPartition ? (
        <Descriptions size="small" column={1} title="候选最新分区" bordered>
          <Descriptions.Item label="分区名">
            <code>{freshness.partitionSummary.candidateLatestPartition.name}</code>
          </Descriptions.Item>
          <Descriptions.Item label="分区值">
            {displayValue(freshness.partitionSummary.candidateLatestPartition.values)}
          </Descriptions.Item>
        </Descriptions>
      ) : null}
      <Typography.Title level={5}>已检查存储路径</Typography.Title>
      <Table
        rowKey="path"
        size="small"
        pagination={false}
        dataSource={freshness.storagePaths}
        scroll={{ x: 960 }}
        columns={[
          { title: 'HDFS 路径', dataIndex: 'path', width: 360, fixed: 'left', ellipsis: true, render: (value) => <code>{value}</code> },
          { title: '类型', dataIndex: 'type', width: 100, render: displayValue },
          { title: '修改时间', dataIndex: 'modificationTime', width: 190, render: formatTimestamp },
          { title: '访问时间', dataIndex: 'accessTime', width: 190, render: formatTimestamp },
          { title: '大小', dataIndex: 'length', width: 110, render: formatBytes },
          { title: '属主', dataIndex: 'owner', width: 130, render: displayValue },
        ]}
        locale={{ emptyText: <Empty description="没有可检查的 HDFS 存储路径" /> }}
      />
    </div>
  ) : null;

  const tabItems = [
    {
      key: 'columns', label: `字段 ${columnRows.length}`,
      children: (
        <Table
          rowKey="name"
          size="small"
          pagination={false}
          columns={columnDefinitions}
          dataSource={columnRows}
          scroll={{ x: 900 }}
          locale={{ emptyText: <Empty description="该表没有字段元数据" /> }}
        />
      ),
    },
    {
      key: 'partitions', label: '分区',
      children: partitions ? (
        <>
          <Table
            rowKey="name"
            size="small"
            pagination={false}
            columns={partitionColumns}
            dataSource={partitions.partitions}
            scroll={{ x: 900 }}
            locale={{ emptyText: <Empty description="该表没有分区" /> }}
          />
          {partitions.total > PARTITION_PAGE_SIZE ? (
            <div className="catalog-tab-pagination">
              <Pagination
                current={partitionPage}
                pageSize={PARTITION_PAGE_SIZE}
                total={partitions.total}
                showSizeChanger={false}
                onChange={setPartitionPage}
              />
            </div>
          ) : null}
        </>
      ) : null,
    },
    { key: 'statistics', label: '统计信息', children: statisticsContent },
    { key: 'storage', label: '存储布局', children: storageContent },
    { key: 'freshness', label: '新鲜度', children: freshnessContent },
    {
      key: 'ddl', label: '建表语句',
      children: ddl ? (
        <div className="catalog-ddl">
          <Tooltip title="复制 DDL">
            <Button
              className="catalog-ddl-copy"
              icon={<CopyOutlined />}
              onClick={() => void navigator.clipboard.writeText(ddl.ddl).then(() => message.success('DDL 已复制'))}
            />
          </Tooltip>
          <pre>{ddl.ddl}</pre>
        </div>
      ) : null,
    },
  ];

  return (
    <div className="data-page catalog-page">
      <section className="data-panel catalog-workbench">
        <aside className="catalog-browser">
          <div className="catalog-browser-toolbar">
            <div className="catalog-source-row">
              <Space size={6} className="catalog-source"><span className="catalog-source-dot" />Hive Metastore</Space>
              <span className="result-count">{tableTotal} 张表</span>
            </div>
            <Select
              showSearch
              value={database || undefined}
              placeholder="选择数据库"
              options={databases.map((item) => ({ value: item, label: item }))}
              onChange={(value) => {
                setDatabase(value);
                setTablePage(1);
                setSelected(undefined);
                syncQuery(value, committedKeyword);
              }}
            />
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              value={domainFilter}
              placeholder="全部业务域"
              options={domainOptions.map((item) => ({ value: item.id, label: `${item.name} (${item.code})` }))}
              onChange={(value) => {
                setDomainFilter(value);
                setTablePage(1);
                setSelected(undefined);
              }}
            />
            <div className="catalog-search-row">
              <Input
                allowClear
                value={keyword}
                prefix={<SearchOutlined />}
                placeholder="搜索表名"
                onChange={(event) => setKeyword(event.target.value)}
                onPressEnter={submitSearch}
              />
              <Tooltip title="刷新"><Button icon={<ReloadOutlined />} onClick={() => void loadTables()} /></Tooltip>
            </div>
          </div>
          <div className="catalog-list-meta">
            <span><DatabaseOutlined /> {database || '-'}</span>
            <span>{tableTotal} 张表</span>
          </div>
          <div className="catalog-table-list" aria-busy={tableLoading}>
            {tableLoading ? <Skeleton active paragraph={{ rows: 7 }} /> : tables.length ? tables.map((item) => {
              const active = selectedKey === `${item.db}.${item.table}`;
              return (
                <button
                  type="button"
                  key={`${item.db}.${item.table}`}
                  className={active ? 'catalog-table-item active' : 'catalog-table-item'}
                  onClick={() => chooseTable(item)}
                >
                  <TableOutlined />
                  <span><strong>{item.table}</strong><small>{item.comment || item.tableType}</small></span>
                </button>
              );
            }) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有匹配的真实 Hive 表" />}
          </div>
          {tableTotal > TABLE_PAGE_SIZE ? (
            <div className="catalog-list-pagination">
              <Pagination
                simple
                current={tablePage}
                pageSize={TABLE_PAGE_SIZE}
                total={tableTotal}
                onChange={setTablePage}
              />
            </div>
          ) : null}
        </aside>

        <main className="catalog-detail">
          {!selected ? (
            <Empty description="请选择一张 Hive 表查看元数据" />
          ) : detailLoading ? (
            <div className="catalog-detail-loading"><Skeleton active paragraph={{ rows: 9 }} /></div>
          ) : detailError ? (
            <Alert type="error" showIcon message="加载表详情失败" description={detailError} />
          ) : detail ? (
            <>
              <div className="catalog-detail-header">
                <div className="catalog-table-identity">
                  <span className="catalog-table-icon"><TableOutlined /></span>
                  <div>
                    <Space size={8}><Typography.Title level={3}>{detail.table.table}</Typography.Title><Tag>{detail.table.tableType}</Tag></Space>
                    <Typography.Text type="secondary">{detail.table.db}.{detail.table.table}</Typography.Text>
                  </div>
                </div>
                <Descriptions
                  size="small"
                  column={{ xs: 1, sm: 2, lg: 3 }}
                  items={[
                    { key: 'owner', label: '负责人', children: detail.table.owner || '-' },
                    { key: 'fields', label: '字段数', children: columnRows.length },
                    { key: 'source', label: '数据来源', children: detail.source },
                    { key: 'lineage', label: '血缘', children: <Button type="link" onClick={() => navigate(`/data-map/lineage?catalog=hive&database=${detail.table.db}&table=${detail.table.table}`)}>查看全局血缘</Button> },
                    {
                      key: 'domain', label: '业务域', children: (
                        <Select
                          allowClear
                          showSearch
                          optionFilterProp="label"
                          loading={domainSaving}
                          value={domainId}
                          placeholder="未归属业务域"
                          style={{ minWidth: 180 }}
                          options={domainOptions.map((item) => ({ value: item.id, label: `${item.name} (${item.code})` }))}
                          onChange={(value) => void changeDomain(value)}
                        />
                      ),
                    },
                    { key: 'comment', label: '表注释', children: detail.table.comment || '-' },
                    { key: 'location', label: '存储位置', span: 2, children: <code className="catalog-location">{detail.table.location || '-'}</code> },
                  ]}
                />
              </div>
              {detail.warnings.length ? <Alert type="warning" showIcon message={detail.warnings.join('；')} /> : null}
              <Tabs
                className="ui-flat-tabs"
                activeKey={activeTab}
                onChange={(key) => setActiveTab(key as DetailTab)}
                items={tabItems}
              />
              {tabLoading ? <div className="catalog-tab-loading"><Skeleton active paragraph={{ rows: 5 }} /></div> : null}
              {tabError ? <Alert className="catalog-tab-error" type="error" showIcon message="读取失败" description={tabError} /> : null}
            </>
          ) : null}
        </main>
      </section>
    </div>
  );
}
