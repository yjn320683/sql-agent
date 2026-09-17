import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { CopyOutlined, ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Drawer, Empty, Input, message, Space, Spin, Tag, Typography } from 'antd';
import { getMysqlTableDdl } from '../api';
import type { MysqlTableDdl, SyncSourceTableOption } from '../types';

interface Props {
  open: boolean;
  serverId?: number;
  tables: SyncSourceTableOption[];
  selectedTables: string[];
  initialTable?: string;
  onClose: () => void;
}

export default function MysqlTableDdlDrawer({ open, serverId, tables, selectedTables, initialTable, onClose }: Props) {
  const [keyword, setKeyword] = useState('');
  const [activeTable, setActiveTable] = useState<string>();
  const [ddl, setDdl] = useState<MysqlTableDdl>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const cache = useRef(new Map<string, MysqlTableDdl>());
  const revision = useRef(0);

  const sortedTables = useMemo(() => {
    const selectedOrder = new Map(selectedTables.map((table, index) => [table, index]));
    return [...tables].sort((left, right) => {
      const leftOrder = selectedOrder.get(left.tableName);
      const rightOrder = selectedOrder.get(right.tableName);
      if (leftOrder !== undefined && rightOrder !== undefined) return leftOrder - rightOrder;
      if (leftOrder !== undefined) return -1;
      if (rightOrder !== undefined) return 1;
      return left.tableName.localeCompare(right.tableName);
    });
  }, [selectedTables, tables]);
  const visibleTables = useMemo(() => {
    const value = keyword.trim().toLocaleLowerCase();
    return value ? sortedTables.filter((item) => item.tableName.toLocaleLowerCase().includes(value)) : sortedTables;
  }, [keyword, sortedTables]);

  const load = useCallback(async (table: string, force = false) => {
    if (!serverId) return;
    const key = `${serverId}/${table}`;
    if (!force && cache.current.has(key)) {
      setDdl(cache.current.get(key)); setError(''); return;
    }
    const current = ++revision.current;
    setLoading(true); setError(''); setDdl(undefined);
    try {
      const result = await getMysqlTableDdl(serverId, table);
      if (revision.current !== current) return;
      cache.current.set(key, result); setDdl(result);
    } catch (reason) {
      if (revision.current === current) setError(reason instanceof Error ? reason.message : 'MySQL 表 DDL 查询失败');
    } finally {
      if (revision.current === current) setLoading(false);
    }
  }, [serverId]);

  useEffect(() => {
    revision.current += 1; cache.current.clear(); setActiveTable(undefined); setDdl(undefined); setError(''); setKeyword('');
  }, [serverId]);
  useEffect(() => {
    if (!open) { revision.current += 1; return; }
    const first = initialTable && tables.some((item) => item.tableName === initialTable)
      ? initialTable : selectedTables.find((table) => tables.some((item) => item.tableName === table)) ?? sortedTables[0]?.tableName;
    setActiveTable(first); setKeyword('');
  }, [initialTable, open, selectedTables, sortedTables, tables]);
  useEffect(() => { if (open && activeTable) void load(activeTable); }, [activeTable, load, open]);

  const copy = async () => {
    if (!ddl?.ddl) return;
    try { await navigator.clipboard.writeText(ddl.ddl); message.success('DDL 已复制'); }
    catch { message.error('DDL 复制失败，请手动复制'); }
  };

  return <Drawer title="查看源表 DDL" width={960} open={open} onClose={onClose} destroyOnHidden={false}>
    <div className="mysql-ddl-drawer-layout">
      <aside className="mysql-ddl-table-pane">
        <Input.Search allowClear aria-label="搜索源表" placeholder="搜索源表" value={keyword} onChange={(event) => setKeyword(event.target.value)} />
        <div className="mysql-ddl-table-list">{visibleTables.length ? visibleTables.map((item) => <button
          type="button" key={item.tableName} className={`mysql-ddl-table-item${activeTable === item.tableName ? ' active' : ''}`}
          onClick={() => setActiveTable(item.tableName)}
        >
          <Typography.Text ellipsis={{ tooltip: item.tableName }}>{item.tableName}</Typography.Text>
          <Space size={[4, 4]} wrap>{selectedTables.includes(item.tableName) && <Tag color="blue">已选</Tag>}{item.occupied && <Tag color="gold">{item.occupiedTaskId ? `已占用 #${item.occupiedTaskId}` : '已占用'}</Tag>}</Space>
        </button>) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有匹配的源表" />}</div>
      </aside>
      <section className="mysql-ddl-preview-pane">
        <div className="mysql-ddl-preview-toolbar"><Typography.Text strong>{activeTable ?? '请选择源表'}</Typography.Text><Space>
          <Button icon={<CopyOutlined />} disabled={!ddl?.ddl} onClick={() => void copy()}>复制</Button>
          <Button icon={<ReloadOutlined />} disabled={!activeTable} loading={loading} onClick={() => activeTable && void load(activeTable, true)}>刷新</Button>
        </Space></div>
        <div className="mysql-ddl-preview-content">
          {loading && <Spin tip="正在读取实时 DDL"><div className="mysql-ddl-loading" /></Spin>}
          {!loading && error && <Alert showIcon type="error" message="MySQL 表 DDL 查询失败" description={error} action={<Button size="small" onClick={() => activeTable && void load(activeTable, true)}>重试</Button>} />}
          {!loading && !error && ddl?.ddl && <pre className="ddl-preview">{ddl.ddl}</pre>}
          {!loading && !error && !ddl && <Empty description="请选择源表查看 DDL" />}
        </div>
      </section>
    </div>
  </Drawer>;
}
