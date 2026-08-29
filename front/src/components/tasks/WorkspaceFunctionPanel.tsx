import { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Empty,
  Input,
  Skeleton,
  Tooltip,
  Typography,
  message,
} from 'antd';
import {
  ArrowLeftOutlined,
  FunctionOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { getHiveFunction, searchHiveFunctions } from '../../api/workspace';
import type { HiveFunctionDetailVO } from '../../types';

interface Props {
  database?: string;
  onInsertSql: (text: string) => void;
}

const FUNCTION_LIMIT = 100;

export default function WorkspaceFunctionPanel({ database, onInsertSql }: Props) {
  const [keyword, setKeyword] = useState('');
  const [functions, setFunctions] = useState<string[]>([]);
  const [total, setTotal] = useState(0);
  const [selected, setSelected] = useState('');
  const [detail, setDetail] = useState<HiveFunctionDetailVO>();
  const [listLoading, setListLoading] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState('');

  const loadFunctions = useCallback(async () => {
    setListLoading(true);
    setError('');
    try {
      const result = await searchHiveFunctions(keyword.trim(), 1, FUNCTION_LIMIT, database);
      setFunctions(result.items);
      setTotal(result.total);
    } catch (loadError) {
      setFunctions([]);
      setTotal(0);
      setError((loadError as Error).message);
    } finally {
      setListLoading(false);
    }
  }, [database, keyword]);

  useEffect(() => {
    const timer = window.setTimeout(() => void loadFunctions(), 250);
    return () => window.clearTimeout(timer);
  }, [loadFunctions]);

  const openFunction = async (name: string) => {
    setSelected(name);
    setDetail(undefined);
    setDetailLoading(true);
    setError('');
    try {
      setDetail(await getHiveFunction(name, database));
    } catch (loadError) {
      setError((loadError as Error).message);
    } finally {
      setDetailLoading(false);
    }
  };

  const insertFunction = (name: string) => {
    onInsertSql(`${name}()`);
    message.success(`已插入 ${name}()`);
  };

  if (selected) {
    return (
      <div className="workspace-function-panel">
        <div className="metadata-browser-header">
          <Button type="text" size="small" icon={<ArrowLeftOutlined />} onClick={() => setSelected('')} />
          <span className="workspace-function-detail-title"><FunctionOutlined /> {selected}</span>
          <Tooltip title="插入函数">
            <Button type="text" size="small" icon={<PlusOutlined />} onClick={() => insertFunction(selected)} />
          </Tooltip>
        </div>
        {error ? <Alert type="error" showIcon message="读取函数说明失败" description={error} /> : null}
        {detailLoading ? <Skeleton active paragraph={{ rows: 8 }} /> : detail ? (
          <div className="workspace-function-detail">
            <div className="workspace-function-fact"><span>默认库</span><code>{detail.defaultDb}</code></div>
            {detail.properties.type ? <div className="workspace-function-fact"><span>类型</span><strong>{detail.properties.type}</strong></div> : null}
            {detail.properties.class ? <div className="workspace-function-class"><span>实现类</span><code>{detail.properties.class}</code></div> : null}
            {detail.properties.usage ? (
              <section>
                <Typography.Text type="secondary">调用方式</Typography.Text>
                <pre>{detail.properties.usage}</pre>
              </section>
            ) : null}
            <section>
              <Typography.Text type="secondary">HiveServer2 说明</Typography.Text>
              <pre>{detail.lines.join('\n')}</pre>
            </section>
          </div>
        ) : null}
      </div>
    );
  }

  return (
    <div className="workspace-function-panel">
      <div className="metadata-browser-header">
        <span><FunctionOutlined /> 函数帮助</span>
        <Tooltip title="刷新函数">
          <Button type="text" size="small" icon={<ReloadOutlined />} onClick={() => void loadFunctions()} />
        </Tooltip>
      </div>
      <div className="workspace-function-search">
        <Input
          allowClear
          value={keyword}
          prefix={<SearchOutlined />}
          placeholder="搜索 Hive 函数"
          onChange={(event) => setKeyword(event.target.value)}
        />
        <span>{database || 'default'} · {total} 个可查询函数</span>
      </div>
      {error ? <Alert type="error" showIcon message="读取 Hive 函数失败" description={error} /> : null}
      <div className="workspace-function-list" aria-busy={listLoading}>
        {listLoading ? <Skeleton active paragraph={{ rows: 8 }} title={false} /> : functions.map((name) => (
          <button type="button" key={name} className="workspace-function-row" onClick={() => void openFunction(name)}>
            <FunctionOutlined />
            <code>{name}</code>
            <Tooltip title="插入函数">
              <Button
                type="text"
                size="small"
                icon={<PlusOutlined />}
                onClick={(event) => {
                  event.stopPropagation();
                  insertFunction(name);
                }}
              />
            </Tooltip>
          </button>
        ))}
        {!listLoading && !functions.length && !error ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="未找到函数" /> : null}
      </div>
      {total > FUNCTION_LIMIT ? <div className="metadata-hint">当前显示前 {FUNCTION_LIMIT} 个，请输入关键词缩小范围</div> : null}
    </div>
  );
}
