import { useCallback, useEffect, useState } from 'react';
import { Alert, Button, Descriptions, Drawer, Empty, Input, Select, Space, Table, Tag, Typography, message } from 'antd';
import { CopyOutlined, FunctionOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { getHiveFunction, listHiveDatabases, searchHiveFunctions } from '../../api/workspace';
import type { HiveFunctionDetailVO } from '../../types';
import { useSearchParams } from 'react-router-dom';

const PAGE_SIZE = 30;

export default function HiveFunctionCatalogPage() {
  const [searchParams] = useSearchParams();
  const [database, setDatabase] = useState<string | undefined>(() => searchParams.get('defaultDb') || undefined);
  const [databases, setDatabases] = useState<string[]>([]);
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [items, setItems] = useState<string[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [detail, setDetail] = useState<HiveFunctionDetailVO>();
  const [detailLoading, setDetailLoading] = useState(false);

  useEffect(() => { void listHiveDatabases().then((result) => setDatabases(result.databases || [])).catch(() => setDatabases([])); }, []);
  const load = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const result = await searchHiveFunctions(keyword.trim(), page, PAGE_SIZE, database);
      setItems(result.items); setTotal(result.total);
    } catch (loadError) { setItems([]); setTotal(0); setError((loadError as Error).message); }
    finally { setLoading(false); }
  }, [database, keyword, page]);
  useEffect(() => { const timer = window.setTimeout(() => void load(), 250); return () => window.clearTimeout(timer); }, [load]);

  const openDetail = async (name: string) => {
    setDetailLoading(true); setDetail(undefined);
    try { setDetail(await getHiveFunction(name, database)); }
    catch (loadError) { message.error(`读取函数详情失败：${(loadError as Error).message}`); }
    finally { setDetailLoading(false); }
  };
  useEffect(() => {
    const initial = searchParams.get('name');
    if (initial) void openDetail(initial);
    // URL 参数仅在页面首次打开时用于从编辑器定位函数。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const copy = async (text: string) => { await navigator.clipboard.writeText(text); message.success('调用模板已复制'); };

  return <div className="function-catalog-page">
    <header className="function-catalog-header">
      <div><h2>Hive 函数目录</h2><p>签名与说明均来自当前 HiveServer2；缺失信息明确显示“未提供”。</p></div>
      <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
    </header>
    <section className="function-catalog-filter">
      <Input allowClear prefix={<SearchOutlined />} value={keyword} placeholder="搜索函数名称" onChange={(event) => { setKeyword(event.target.value); setPage(1); }} />
      <Select allowClear value={database} placeholder="默认数据库" options={databases.map((value) => ({ value, label: value }))} onChange={(value) => { setDatabase(value); setPage(1); }} />
    </section>
    {error ? <Alert type="error" showIcon message="Hive 函数服务暂不可用" description={error} action={<Button size="small" onClick={() => void load()}>重试</Button>} /> : null}
    <Table rowKey={(name) => name} loading={loading} dataSource={items} columns={[
      { title: '函数名', render: (name: string) => <Button type="link" icon={<FunctionOutlined />} onClick={() => void openDetail(name)}>{name}</Button> },
      { title: '函数类型', width: 180, render: () => <Typography.Text type="secondary">详情中读取</Typography.Text> },
      { title: '操作', width: 100, render: (name: string) => <Button size="small" onClick={() => void openDetail(name)}>查看</Button> },
    ]} pagination={{ current: page, pageSize: PAGE_SIZE, total, showSizeChanger: false, showTotal: (value) => `共 ${value} 个`, onChange: setPage }} locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="未找到函数" /> }} />
    <Drawer open={Boolean(detail) || detailLoading} width={620} title={detail ? `${detail.name} · 函数详情` : '加载函数详情'} onClose={() => setDetail(undefined)} loading={detailLoading}>
      {detail ? <div className="function-detail-page">
        {!detail.complete ? <Alert type="warning" showIcon message="Hive 返回的信息不完整" description={detail.missingReasons.join('；') || '部分说明未提供'} /> : null}
        <Descriptions bordered size="small" column={1} items={[
          { key: 'type', label: '类型', children: detail.functionType || '未提供' },
          { key: 'return', label: '返回类型', children: detail.returnType || '未提供' },
          { key: 'database', label: '默认库', children: detail.defaultDb },
          { key: 'class', label: '实现类', children: detail.properties.class || '未提供' },
        ]} />
        <section><Space><strong>调用模板</strong><Button size="small" icon={<CopyOutlined />} onClick={() => void copy(detail.invocationTemplate)}>复制</Button></Space><pre>{detail.invocationTemplate || '未提供'}</pre></section>
        <section><strong>签名</strong>{detail.signatures.length ? detail.signatures.map((item) => <pre key={item}>{item}</pre>) : <p>未提供</p>}</section>
        <section><strong>参数</strong>{detail.arguments.length ? detail.arguments.map((item) => <Tag key={item.name}>{item.name} · {item.type}</Tag>) : <p>未提供</p>}</section>
        <section><strong>HiveServer2 原始说明</strong><pre>{detail.lines.join('\n') || '未提供'}</pre></section>
      </div> : null}
    </Drawer>
  </div>;
}
