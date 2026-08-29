import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { Button, Input, Select, Space, Tooltip } from 'antd';
import type { ReactNode } from 'react';

export type InstanceSearchField = 'all' | 'id' | 'jobId' | 'yarnApplicationId' | 'failureMessage';
export type InstanceSortOrder = 'startedAtDesc' | 'startedAtAsc' | 'idDesc' | 'idAsc';

export const searchFieldOptions = [
  { label: '全部字段', value: 'all' }, { label: '实例 ID', value: 'id' },
  { label: 'JobID', value: 'jobId' }, { label: 'Application ID', value: 'yarnApplicationId' },
  { label: '失败原因', value: 'failureMessage' },
];

export const sortOptions = [
  { label: '开始时间（新→旧）', value: 'startedAtDesc' }, { label: '开始时间（旧→新）', value: 'startedAtAsc' },
  { label: '实例 ID（大→小）', value: 'idDesc' }, { label: '实例 ID（小→大）', value: 'idAsc' },
];

interface Props {
  keyword: string;
  searchField: InstanceSearchField;
  status: string;
  sortOrder: InstanceSortOrder;
  statusOptions: { label: string; value: string }[];
  loading: boolean;
  refreshLabel: string;
  primaryAction?: ReactNode;
  onKeywordChange: (value: string) => void;
  onSearchFieldChange: (value: InstanceSearchField) => void;
  onStatusChange: (value: string) => void;
  onSortOrderChange: (value: InstanceSortOrder) => void;
  onReset: () => void;
  onRefresh: () => void;
}

export default function InstanceListToolbar(props: Props) {
  const changed = Boolean(props.keyword.trim()) || props.searchField !== 'all' || props.status !== 'all' || props.sortOrder !== 'startedAtDesc';
  return <div className="instance-list-toolbar">
    <Space.Compact className="instance-list-search-group">
      <Select aria-label="搜索字段" className="instance-list-search-field" value={props.searchField} options={searchFieldOptions} onChange={props.onSearchFieldChange} />
      <Input allowClear aria-label="实例搜索关键词" prefix={<SearchOutlined />} placeholder="输入搜索关键词" value={props.keyword} onChange={(event) => props.onKeywordChange(event.target.value)} />
    </Space.Compact>
    <Select aria-label="实例状态" className="instance-list-status-filter" value={props.status} options={props.statusOptions} onChange={props.onStatusChange} />
    <Select aria-label="实例排序" className="instance-list-sort" value={props.sortOrder} options={sortOptions} onChange={props.onSortOrderChange} />
    <div className="instance-list-toolbar-actions">
      {props.primaryAction}
      <Button type="link" disabled={!changed} onClick={props.onReset}>重置</Button>
      <Tooltip title={props.refreshLabel}><Button aria-label={props.refreshLabel} icon={<ReloadOutlined />} loading={props.loading} onClick={props.onRefresh} /></Tooltip>
    </div>
  </div>;
}
