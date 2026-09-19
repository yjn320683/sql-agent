import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Empty,
  Input,
  Modal,
  Spin,
  Tag,
  Tooltip,
} from 'antd';
import {
  AlertOutlined,
  ClockCircleOutlined,
  CodeOutlined,
  DatabaseOutlined,
  HistoryOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import type { InputRef } from 'antd';
import {
  globalSearch,
  type GlobalSearchGroup,
  type GlobalSearchItem,
  type GlobalSearchResult,
  type GlobalSearchType,
} from '../../api/search';

const searchTypes: Array<{ type?: GlobalSearchType; label: string }> = [
  { label: '全部' },
  { type: 'OFFLINE_TASK', label: '离线任务' },
  { type: 'REALTIME_TASK', label: '实时任务' },
  { type: 'HIVE_TABLE', label: 'Hive 表' },
  { type: 'REALTIME_TABLE', label: '实时表' },
  { type: 'OFFLINE_EXECUTION', label: '离线实例' },
  { type: 'REALTIME_INSTANCE', label: '实时实例' },
  { type: 'CHAT_SESSION', label: '我的会话' },
  { type: 'REALTIME_ALERT', label: '实时告警' },
];

interface Props {
  open: boolean;
  onClose: () => void;
}

function iconOf(type: GlobalSearchType) {
  if (type === 'HIVE_TABLE' || type === 'REALTIME_TABLE') return <DatabaseOutlined />;
  if (type === 'OFFLINE_EXECUTION' || type === 'REALTIME_INSTANCE') return <ClockCircleOutlined />;
  if (type === 'CHAT_SESSION') return <HistoryOutlined />;
  if (type === 'REALTIME_ALERT') return <AlertOutlined />;
  return <CodeOutlined />;
}

export default function GlobalSearchPalette({ open, onClose }: Props) {
  const navigate = useNavigate();
  const inputRef = useRef<InputRef>(null);
  const [keyword, setKeyword] = useState('');
  const [selectedType, setSelectedType] = useState<GlobalSearchType | undefined>();
  const [page, setPage] = useState(1);
  const [result, setResult] = useState<GlobalSearchResult>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [activeIndex, setActiveIndex] = useState(0);

  useEffect(() => {
    if (!open) return;
    window.setTimeout(() => inputRef.current?.focus(), 50);
  }, [open]);

  useEffect(() => {
    if (!open || keyword.trim().length < 2) {
      setResult(undefined);
      setError('');
      return;
    }
    let alive = true;
    const timer = window.setTimeout(async () => {
      setLoading(true);
      setError('');
      try {
        const response = await globalSearch(keyword.trim(), selectedType, page, selectedType ? 10 : 5);
        if (alive) {
          setResult(response);
          setActiveIndex(0);
        }
      } catch (cause) {
        if (alive) setError(cause instanceof Error ? cause.message : '搜索失败');
      } finally {
        if (alive) setLoading(false);
      }
    }, 250);
    return () => {
      alive = false;
      window.clearTimeout(timer);
    };
  }, [keyword, open, page, selectedType]);

  const flatItems = useMemo(
    () => result?.groups.flatMap((group) => group.items) ?? [],
    [result],
  );

  const openItem = (item: GlobalSearchItem) => {
    navigate(item.route);
    onClose();
  };

  const changeType = (type?: GlobalSearchType) => {
    setSelectedType(type);
    setPage(1);
  };

  return (
    <Modal
      className="global-search-modal"
      open={open}
      onCancel={onClose}
      footer={null}
      width={760}
      title={null}
      destroyOnClose={false}
      afterClose={() => setActiveIndex(0)}
    >
      <Input
        ref={inputRef}
        className="global-search-input"
        size="large"
        allowClear
        prefix={<SearchOutlined />}
        placeholder="搜索任务、表、实例、会话或告警"
        value={keyword}
        onChange={(event) => { setKeyword(event.target.value); setPage(1); }}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown' && flatItems.length) {
            event.preventDefault();
            setActiveIndex((value) => Math.min(value + 1, flatItems.length - 1));
          }
          if (event.key === 'ArrowUp' && flatItems.length) {
            event.preventDefault();
            setActiveIndex((value) => Math.max(value - 1, 0));
          }
          if (event.key === 'Enter' && flatItems[activeIndex]) openItem(flatItems[activeIndex]);
        }}
      />

      <div className="global-search-types" role="tablist" aria-label="搜索类型">
        {searchTypes.map((item) => (
          <button
            key={item.type ?? 'ALL'}
            type="button"
            role="tab"
            aria-selected={selectedType === item.type}
            className={selectedType === item.type ? 'active' : ''}
            onClick={() => changeType(item.type)}
          >
            {item.label}
          </button>
        ))}
      </div>

      <div className="global-search-results">
        {loading && <div className="global-search-state"><Spin /><span>正在检索…</span></div>}
        {!loading && error && <Alert type="error" showIcon message={error} />}
        {!loading && !error && keyword.trim().length < 2 && (
          <div className="global-search-hint">
            输入至少 2 个字符开始搜索；按 ↑↓ 选择，Enter 打开结果。
          </div>
        )}
        {!loading && !error && keyword.trim().length >= 2 && result && flatItems.length === 0 && (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有找到匹配内容" />
        )}
        {!loading && !error && result?.groups.map((group) => (
          <SearchGroup
            key={group.type}
            group={group}
            activeItem={flatItems[activeIndex]}
            onOpen={openItem}
            onMore={() => changeType(group.type)}
          />
        ))}
      </div>

      {selectedType && result?.groups[0] && result.groups[0].total > result.groups[0].pageSize && (
        <div className="global-search-pagination">
          <Button disabled={page <= 1} onClick={() => setPage((value) => Math.max(1, value - 1))}>上一页</Button>
          <span>第 {page} 页，共 {result.groups[0].total} 条</span>
          <Button
            disabled={page * result.groups[0].pageSize >= result.groups[0].total}
            onClick={() => setPage((value) => value + 1)}
          >下一页</Button>
        </div>
      )}
      <div className="global-search-footer"><span>Esc 关闭</span><span>Ctrl / ⌘ + K 唤起</span></div>
    </Modal>
  );
}

function SearchGroup({ group, activeItem, onOpen, onMore }: {
  group: GlobalSearchGroup;
  activeItem?: GlobalSearchItem;
  onOpen: (item: GlobalSearchItem) => void;
  onMore: () => void;
}) {
  if (group.error) {
    return <Alert className="global-search-group-error" type="warning" showIcon message={`${group.label}暂不可用`} description={group.error} />;
  }
  if (group.items.length === 0) return null;
  return (
    <section className="global-search-group">
      <header>
        <span>{group.label}</span>
        <span>{group.total} 条</span>
      </header>
      {group.items.map((item) => (
        <button
          type="button"
          key={`${item.type}-${item.id}`}
          className={activeItem === item ? 'global-search-result active' : 'global-search-result'}
          onClick={() => onOpen(item)}
        >
          <span className="global-search-result-icon">{iconOf(item.type)}</span>
          <span className="global-search-result-copy">
            <span className="global-search-result-title">{item.title}</span>
            <Tooltip title={item.subtitle}><span className="global-search-result-subtitle">{item.subtitle || `ID: ${item.id}`}</span></Tooltip>
          </span>
          {item.status && <Tag>{item.status}</Tag>}
        </button>
      ))}
      {group.total > group.items.length && (
        <button type="button" className="global-search-more" onClick={onMore}>查看全部 {group.total} 条</button>
      )}
    </section>
  );
}
