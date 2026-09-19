import { SearchOutlined } from '@ant-design/icons';
import { Button, Input, Select, Space, Table } from 'antd';
import { useMemo, useState } from 'react';
import type { TaskChangeLog } from '../types';
import { availableChangeActions, changeDetailButtonText, normalizeChangeAction } from './changeActions';

interface Props {
  changes: TaskChangeLog[];
  loading?: boolean;
  detailLoadingId?: number;
  onOpenDetail: (row: TaskChangeLog) => void;
}

/** 三类实时任务共用变更记录筛选、文案及详情入口。 */
export default function RealtimeChangeLogTable({ changes, loading, detailLoadingId, onOpenDetail }: Props) {
  const [action, setAction] = useState('all');
  const [keyword, setKeyword] = useState('');
  const actionOptions = useMemo(() => availableChangeActions(changes.map((item) => item.action))
    .map((value) => ({ label: value, value })), [changes]);
  const rows = useMemo(() => { const search = keyword.trim().toLowerCase(); return changes.filter((item) =>
    (action === 'all' || normalizeChangeAction(item.action) === action)
    && (!search || [item.operator, normalizeChangeAction(item.action), item.detail, item.summary].join(' ').toLowerCase().includes(search)));
  }, [action, changes, keyword]);
  return <>
    <div className="realtime-detail-toolbar"><Space>
      <Select showSearch optionFilterProp="label" value={action} onChange={setAction} options={[{ label: '全部操作', value: 'all' }, ...actionOptions]} />
      <Input allowClear prefix={<SearchOutlined />} value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="搜索操作人 / 操作类型 / 变更说明" />
    </Space></div>
    <Table rowKey="id" size="small" loading={loading} dataSource={rows} pagination={false}
      locale={{ emptyText: keyword.trim() || action !== 'all' ? '没有匹配的变更记录' : '暂无变更记录' }} columns={[
        { title: '操作时间', dataIndex: 'createTime', width: 180 },
        { title: '操作人', dataIndex: 'operator', width: 120 },
        { title: '操作类型', dataIndex: 'action', width: 150, render: (value: string) => normalizeChangeAction(value) },
        { title: '变更明细', dataIndex: 'detail', render: (value: string, row: TaskChangeLog) => row.detailKind !== 'text'
          ? <Button className="task-change-detail-button" type="link" size="small" loading={detailLoadingId === row.id} onClick={() => onOpenDetail(row)}>{changeDetailButtonText(row.action)}</Button>
          : (row.summary || value || '-') },
      ]} />
  </>;
}
