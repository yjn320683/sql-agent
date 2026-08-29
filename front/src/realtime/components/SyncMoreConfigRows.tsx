import { DeleteOutlined, PlusOutlined, QuestionCircleOutlined, SearchOutlined } from '@ant-design/icons';
import { Button, Form, Input, InputNumber, Popover, Select, Space, Tooltip, Typography } from 'antd';
import { useMemo, useState } from 'react';
import type { TaskParam } from '../types';

type NamePath = Array<string | number>;

interface Props {
  paramType: TaskParam['paramType'];
  formNamePath: NamePath;
  taskParams: TaskParam[];
  readOnly?: boolean;
}

const normalizeMap = (value: unknown): Record<string, unknown> => value && typeof value === 'object' && !Array.isArray(value)
  ? { ...(value as Record<string, unknown>) }
  : {};

const options = (param?: TaskParam) => {
  if (!param?.paramValue) return [];
  try {
    const parsed = JSON.parse(param.paramValue) as unknown;
    if (!Array.isArray(parsed)) return [];
    return parsed.flatMap((item) => {
      if (item && typeof item === 'object' && 'value' in item) {
        const option = item as { value?: unknown; label?: unknown };
        return option.value === undefined ? [] : [{ value: String(option.value), label: String(option.label ?? option.value) }];
      }
      return ['string', 'number', 'boolean'].includes(typeof item)
        ? [{ value: String(item), label: String(item) }]
        : [];
    });
  } catch { return []; }
};

const defaultValue = (param: TaskParam) => {
  if (!param.paramValue) return '';
  try {
    const parsed = JSON.parse(param.paramValue) as unknown;
    if (Array.isArray(parsed)) {
      const selected = parsed.find((item) => item && typeof item === 'object' && (item as { default?: unknown }).default === true)
        ?? parsed[0];
      if (selected && typeof selected === 'object' && 'value' in selected) return String((selected as { value: unknown }).value);
      return selected === undefined ? '' : String(selected);
    }
    return ['string', 'number', 'boolean'].includes(typeof parsed) ? String(parsed) : '';
  } catch { return param.paramValue; }
};

const hasText = (value: unknown) => value !== undefined && value !== null && String(value).trim() !== '';
const decimals = (value: unknown) => String(value).split('.')[1]?.replace(/0+$/, '').length ?? 0;

const rulesFor = (param?: TaskParam, disabled = false) => disabled ? [] : param?.inputType === 'input_number' ? [{
  validator: (_: unknown, value: unknown) => {
    if (!hasText(value)) return Promise.reject(new Error('请输入配置值'));
    const number = Number(value);
    const label = param.keyDesc || param.paramKey;
    if (!Number.isFinite(number)) return Promise.reject(new Error(`${label}必须为数字`));
    if (param.paramKey === 'bucket' && !(Number.isInteger(number) && (number === -2 || number === -1 || number > 0))) {
      return Promise.reject(new Error('Bucket 只允许 -2、-1 或正整数'));
    }
    if (param.paramKey === 'sink.parallelism' && !(Number.isSafeInteger(number) && number > 0)) {
      return Promise.reject(new Error('Sink 并行度必须为正整数'));
    }
    if (param.minValue !== undefined && number < param.minValue) return Promise.reject(new Error(`${label}必须大于等于 ${param.minValue}`));
    if (param.maxValue !== undefined && number > param.maxValue) return Promise.reject(new Error(`${label}必须小于等于 ${param.maxValue}`));
    if (param.precisionValue !== undefined && decimals(value) > param.precisionValue) return Promise.reject(new Error(`${label}最多保留 ${param.precisionValue} 位小数`));
    if (param.stepValue && Math.abs(((number - (param.minValue ?? 0)) / param.stepValue) - Math.round((number - (param.minValue ?? 0)) / param.stepValue)) > 1e-9) {
      return Promise.reject(new Error(`${label}必须按步长 ${param.stepValue} 递增`));
    }
    return Promise.resolve();
  },
}] : [{ required: true, message: '请输入配置值' }];

export function SyncParamInput({ param, name, disabled = false }: { param?: TaskParam; name: NamePath; disabled?: boolean }) {
  const valueOptions = options(param);
  const common = { name, rules: rulesFor(param, disabled), className: 'sync-more-config-form-item' };
  if (param?.inputType === 'select' && valueOptions.length) {
    return <Form.Item {...common}><Select disabled={disabled} options={valueOptions} /></Form.Item>;
  }
  if (param?.inputType === 'switch') {
    return <Form.Item {...common}><Select disabled={disabled} options={[{ label: '否', value: 'false' }, { label: '是', value: 'true' }]} /></Form.Item>;
  }
  if (param?.inputType === 'input_number') {
    return <Form.Item {...common}><InputNumber<string | number> disabled={disabled} changeOnBlur={false} min={param.minValue} max={param.maxValue} step={param.stepValue} style={{ width: '100%' }} /></Form.Item>;
  }
  return <Form.Item {...common}><Input disabled={disabled} /></Form.Item>;
}

export default function SyncMoreConfigRows({ paramType, formNamePath, taskParams, readOnly = false }: Props) {
  const form = Form.useFormInstance();
  const watched = Form.useWatch(formNamePath, form);
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [revision, setRevision] = useState(0);
  const current = useMemo(() => normalizeMap(form.getFieldValue(formNamePath) ?? watched), [form, formNamePath, revision, watched]);
  const params = useMemo(() => taskParams.filter((item) => item.paramType === paramType && !Boolean(item.required)), [paramType, taskParams]);
  const byKey = useMemo(() => new Map(params.map((item) => [item.paramKey, item])), [params]);
  const rows = Object.entries(current).filter(([key]) => !taskParams.some((item) => item.paramType === paramType && Boolean(item.required) && item.paramKey === key));
  const selected = new Set(rows.map(([key]) => key));
  const available = params.filter((item) => !selected.has(item.paramKey) && `${item.paramKey} ${item.keyDesc ?? ''}`.toLowerCase().includes(search.trim().toLowerCase()));

  const update = (next: Record<string, unknown>) => {
    form.setFieldValue(formNamePath, next);
    setRevision((value) => value + 1);
  };

  if (readOnly) return rows.length ? <>{rows.map(([key, value]) => <div className="sync-more-config-row" key={key}>
    <div className="sync-more-config-label"><span>{byKey.get(key)?.keyDesc || key}</span><Tooltip title={key}><QuestionCircleOutlined /></Tooltip></div>
    <Typography.Text ellipsis={{ tooltip: String(value) }}>{String(value)}</Typography.Text>
  </div>)}</> : null;

  return <div className="sync-more-config-rows">
    {rows.map(([key]) => {
      const param = byKey.get(key);
      return <div className="sync-more-config-row" key={key}>
        <div className="sync-more-config-label"><span>{param?.keyDesc || '已停用/未知参数'}</span><Tooltip title={`${param?.keyDesc || '已停用/未知参数'}（${key}）`}><QuestionCircleOutlined /></Tooltip></div>
        <Space.Compact block>
          <SyncParamInput param={param} name={[...formNamePath, key]} disabled={!param} />
          <Button aria-label={`删除配置 ${key}`} icon={<DeleteOutlined />} onClick={() => { const next = { ...current }; delete next[key]; update(next); }} />
        </Space.Compact>
      </div>;
    })}
    <Popover
      open={open}
      trigger="click"
      placement="bottomRight"
      onOpenChange={(value) => { setOpen(value); if (!value) setSearch(''); }}
      content={<div className="sync-more-config-picker">
        <Input allowClear autoFocus prefix={<SearchOutlined />} placeholder="搜索配置 key / 描述" value={search} onChange={(event) => setSearch(event.target.value)} />
        <div className="sync-more-config-options">{available.length ? available.map((param) => <button type="button" key={param.paramKey} onClick={() => { update({ ...current, [param.paramKey]: defaultValue(param) }); setOpen(false); setSearch(''); }}>
          <span>{param.paramKey}</span><small>{param.keyDesc}</small>
        </button>) : <div className="sync-more-config-empty">暂无匹配配置</div>}</div>
      </div>}
    >
      <Button className="sync-more-config-trigger" icon={<PlusOutlined />} disabled={!params.some((item) => !selected.has(item.paramKey))}>更多配置</Button>
    </Popover>
  </div>;
}
