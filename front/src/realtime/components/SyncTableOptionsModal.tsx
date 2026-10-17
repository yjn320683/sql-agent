import { useEffect, useState } from 'react';
import { Alert, Button, Input, InputNumber, Modal, Select, Space, Switch, Typography } from 'antd';
import type { MysqlTableSchema, TablePrivateConfig } from '../types';
import { partitionField, partitionValueError, recommendedPartitionValue } from './partitionDefaults';
import { timeFilterError } from './timeFilter';

type TimeFilter = NonNullable<TablePrivateConfig['timeFilter']>;
const timeType = (raw: string) => raw.trim().toLowerCase().replace(/\(0\)$/, '');
const supportedTimeType = (raw: string) => /^(date|datetime|timestamp)(\([0-6]\))?$/.test(timeType(raw));

export default function SyncTableOptionsModal({ table, config, schema, timeZone, disabled, syncMode, typeMappings = [], onCancel, onSave }: {
  table?: string;
  config?: TablePrivateConfig;
  schema?: MysqlTableSchema;
  timeZone: string;
  disabled?: boolean;
  syncMode?: string;
  typeMappings?: string[];
  onCancel: () => void;
  onSave: (changes: Pick<TablePrivateConfig, 'partitionDefaults' | 'timeFilter' | 'bucket'>) => void;
}) {
  const [enabled, setEnabled] = useState(false);
  const [defaults, setDefaults] = useState<Record<string, string>>({});
  const [field, setField] = useState<string>();
  const [start, setStart] = useState('');
  const [zone, setZone] = useState('');
  const [error, setError] = useState('');
  const [bucket, setBucket] = useState<number>();
  useEffect(() => {
    if (!table) return;
    setEnabled(Boolean(config?.partitionDefaults));
    setDefaults(config?.partitionDefaults?.values ?? {});
    setField(config?.timeFilter?.field);
    setStart(config?.timeFilter?.start ?? '');
    setZone(timeZone);
    setError('');
    setBucket(config?.bucket);
  }, [table, config, timeZone]);
  const partitionKeys = config?.partitionKeys ?? [];
  const fieldInfo = (key: string) => partitionField(schema, config?.computedColumns ?? [], key, typeMappings);
  const recommend = () => setDefaults(Object.fromEntries(partitionKeys.map(key => [key, recommendedPartitionValue(fieldInfo(key))])));
  const save = () => {
    if (bucket !== undefined && (!Number.isInteger(bucket) || bucket < 1 || bucket > 100)) {
      setError('Bucket 必须为 1～100 的整数'); return;
    }
    if (enabled) {
      for (const key of partitionKeys) {
        const error = partitionValueError(defaults[key], fieldInfo(key));
        if (error) { setError(key + '：' + error); return; }
      }
    }
    let timeFilter: TimeFilter | undefined;
    if (field) {
      if (syncMode === 'incremental') { setError('历史快照时间过滤仅支持全量任务'); return; }
      if (typeMappings.includes('to-string')) { setError('时间过滤不支持 to-string 类型映射'); return; }
      const sourceType = timeType(schema?.columns.find((column) => column.name === field)?.type ?? '');
      if (!sourceType || !start.trim() || !zone.trim()) { setError('时间过滤需要源字段、起始时间和源端时区'); return; }
      timeFilter = { version: 1, field, sourceType, start: start.trim(), timeZone: zone.trim() };
      const error = timeFilterError(timeFilter, schema?.columns ?? []);
      if (error) { setError(error); return; }
    }
    onSave({ bucket, partitionDefaults: enabled ? { version: 1, values: Object.fromEntries(partitionKeys.map((key) => [key, defaults[key]])) } : undefined, timeFilter });
  };
  return <Modal title={`同步表配置：${table ?? ''}`} open={Boolean(table)} onCancel={onCancel}
    footer={<Space><Button onClick={onCancel}>取消</Button><Button type="primary" disabled={disabled} onClick={save}>保存</Button></Space>}>
    <Typography.Title level={5}>逐表 Bucket</Typography.Title>
    <InputNumber aria-label="逐表 Bucket" min={1} max={100} precision={0} disabled={disabled} value={bucket}
      placeholder="继承公共配置" style={{ width: '100%' }} onChange={value => setBucket(value ?? undefined)} />
    <Typography.Title level={5}>分区空值处理</Typography.Title>
    <Space align="center"><Switch checked={enabled} disabled={disabled || !partitionKeys.length} onChange={value => { setEnabled(value); if (value && !Object.keys(defaults).length) recommend(); }} />默认分区
      {enabled && <Button size="small" disabled={disabled} onClick={recommend}>使用推荐值</Button>}
    </Space>
    <div><Typography.Text type="secondary">NULL、空字符串和纯空白值使用默认分区值。</Typography.Text></div>
    {enabled && <div style={{ marginTop: 12 }}>{partitionKeys.map((key) => <div key={key} style={{ marginBottom: 12 }}>
      <Typography.Text>{key} · {fieldInfo(key).type} · 推荐：{recommendedPartitionValue(fieldInfo(key))}</Typography.Text>
      <Input aria-label={`${key} 默认分区值`} disabled={disabled} value={defaults[key] ?? ''} placeholder="请输入默认分区值"
        onChange={(event) => setDefaults((previous) => ({ ...previous, [key]: event.target.value }))} />
    </div>)}</div>}
    <Typography.Title level={5}>全量快照时间过滤</Typography.Title>
    <Select aria-label="过滤时间字段" allowClear disabled={disabled || syncMode === 'incremental'} value={field} style={{ width: '100%' }} placeholder={syncMode === 'incremental' ? '仅全量任务支持' : '不启用'}
      options={(schema?.columns ?? []).filter((column) => supportedTimeType(column.type)).map((column) => ({ value: column.name, label: `${column.name} · ${column.type}` }))}
      onChange={(value) => { setField(value); setStart(''); }} />
    {field && <Space direction="vertical" style={{ width: '100%', marginTop: 12 }}>
      <Input aria-label="过滤开始时间" disabled={disabled} value={start} placeholder="yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss"
        onChange={(event) => setStart(event.target.value)} />
      <Input aria-label="MySQL 源端时区" disabled value={zone} placeholder="请先配置 MySQL server-time-zone" />
      <Typography.Text type="secondary">仅过滤全量快照；Binlog 增量不受影响。</Typography.Text>
    </Space>}
    {error && <Alert type="error" showIcon message={error} style={{ marginTop: 12 }} />}
  </Modal>;
}
