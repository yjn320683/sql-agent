import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { Alert, Button, Input, InputNumber, Modal, Segmented, Select, Space } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { MysqlColumn } from '../types';
import { COMPUTED_FUNCTION_OPTIONS, buildStructuredComputedExpression, computedExpressionToDraft,
  emptyComputedColumnDraft, isFunctionCompatibleWithColumn, validateComputedColumnRows,
  type ComputedColumnDraftRow, type OfficialComputedFunction } from './computedColumns';

interface Props {
  open: boolean; tableName?: string; expressions: string[]; columns: MysqlColumn[];
  protectedKeys: string[]; onSave: (expressions: string[]) => void; onCancel: () => void;
}

const updateRow = (rows: ComputedColumnDraftRow[], index: number, update: Partial<ComputedColumnDraftRow>) =>
  rows.map((row, rowIndex) => rowIndex === index ? { ...row, ...update } : row);

export default function ComputedColumnEditorModal({ open, tableName, expressions, columns, protectedKeys, onSave, onCancel }: Props) {
  const [rows, setRows] = useState<ComputedColumnDraftRow[]>([]);
  const [errors, setErrors] = useState<string[]>([]);
  const initialized = useRef<string>();
  useEffect(() => {
    if (!open || !tableName) { initialized.current = undefined; return; }
    if (initialized.current === tableName) return;
    initialized.current = tableName; setRows(expressions.map(computedExpressionToDraft)); setErrors([]);
  }, [expressions, open, tableName]);
  const columnsByName = useMemo(() => new Map(columns.map((column) => [column.name, column])), [columns]);

  const changeMode = (index: number, mode: 'structured' | 'advanced') => {
    const row = rows[index]; if (row.mode === mode) return;
    if (mode === 'advanced') {
      setRows((current) => updateRow(current, index, { mode, expression: buildStructuredComputedExpression(row) }));
      setErrors((current) => current.map((error, errorIndex) => errorIndex === index ? '' : error));
      return;
    }
    if (!row.expression.trim()) {
      setRows((current) => updateRow(current, index, { ...emptyComputedColumnDraft(), columnName: row.columnName }));
      setErrors((current) => current.map((error, errorIndex) => errorIndex === index ? '' : error));
      return;
    }
    const parsed = computedExpressionToDraft(row.expression);
    if (parsed.mode !== 'structured') { setErrors((current) => { const next = [...current]; next[index] = '当前表达式不是可识别的官方函数，已保留高级表达式模式'; return next; }); return; }
    setRows((current) => current.map((item, rowIndex) => rowIndex === index ? parsed : item));
    setErrors((current) => current.map((error, errorIndex) => errorIndex === index ? '' : error));
  };

  const save = () => {
    const validation = validateComputedColumnRows(rows, columns); setErrors(validation.errors);
    if (validation.errors.some(Boolean)) return;
    const previous = new Set(expressions.map((item) => computedExpressionToDraft(item).columnName).filter(Boolean));
    const removed = protectedKeys.find((key) => previous.has(key) && !validation.names.has(key));
    if (removed) { setErrors([`计算列 ${removed} 已被主键或分区键引用，请先调整键配置`]); return; }
    onSave(validation.expressions);
  };

  return <Modal title={`计算列配置${tableName ? `：${tableName}` : ''}`} open={open} width={1280} okText="保存" cancelText="取消" onOk={save} onCancel={onCancel} destroyOnHidden>
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      {rows.map((row, index) => {
        const sourceColumn = row.referenceColumn ? columnsByName.get(row.referenceColumn) : undefined;
        const functionOptions = COMPUTED_FUNCTION_OPTIONS.map((option) => ({ ...option, disabled: !isFunctionCompatibleWithColumn(option.value, sourceColumn) }));
        return <div className="computed-column-editor-row" key={index}>
          <div className="computed-column-editor-row-header"><Segmented aria-label={`计算列 ${index + 1} 编辑模式`} className="ui-flat-segmented" value={row.mode} options={[{ label: '结构化', value: 'structured' }, { label: '高级表达式', value: 'advanced' }]} onChange={(value) => changeMode(index, value as 'structured' | 'advanced')} /><Button aria-label={`删除计算列 ${index + 1}`} icon={<DeleteOutlined />} onClick={() => { setRows((current) => current.filter((_, rowIndex) => rowIndex !== index)); setErrors((current) => current.filter((_, rowIndex) => rowIndex !== index)); }} /></div>
          {row.mode === 'structured' ? <>
            <div className="computed-column-structured-line">
              <Input aria-label={`计算列 ${index + 1} 名称`} className="computed-column-name-input" placeholder="计算列名，例如 create_date" value={row.columnName} onChange={(event) => setRows((current) => updateRow(current, index, { columnName: event.target.value }))} />
              <Select aria-label={`计算列 ${index + 1} 函数`} className="computed-column-function-select" placeholder="选择函数" value={row.functionName} options={functionOptions} onChange={(value: OfficialComputedFunction) => setRows((current) => updateRow(current, index, { functionName: value, referenceColumn: value === 'now' ? undefined : current[index].referenceColumn }))} />
              {row.functionName !== 'now' && <Select aria-label={`计算列 ${index + 1} 引用源字段`} className="computed-column-reference-select" showSearch optionFilterProp="label" placeholder="引用源字段" value={row.referenceColumn} options={columns.map((column) => ({ label: `${column.name}[${column.type}]`, value: column.name }))} onChange={(value) => setRows((current) => updateRow(current, index, { referenceColumn: value }))} />}
              {row.functionName === 'date_format' && <Input aria-label={`计算列 ${index + 1} 日期格式`} className="computed-column-parameter-input" placeholder="格式，例如 yyyyMMdd（无需引号）" value={row.format} onChange={(event) => setRows((current) => updateRow(current, index, { format: event.target.value }))} />}
              {row.functionName === 'substring' && <><InputNumber aria-label={`计算列 ${index + 1} 开始位置`} className="computed-column-parameter-input" min={0} precision={0} placeholder="开始位置" value={row.begin === undefined || row.begin === '' ? null : Number(row.begin)} onChange={(value) => setRows((current) => updateRow(current, index, { begin: value === null ? '' : String(value) }))} /><InputNumber aria-label={`计算列 ${index + 1} 结束位置`} className="computed-column-parameter-input" min={0} precision={0} placeholder="结束位置（可选）" value={row.end === undefined || row.end === '' ? null : Number(row.end)} onChange={(value) => setRows((current) => updateRow(current, index, { end: value === null ? '' : String(value) }))} /></>}
              {row.functionName === 'truncate' && <InputNumber aria-label={`计算列 ${index + 1} 截断宽度`} className="computed-column-parameter-input" min={1} precision={0} placeholder="截断宽度" value={row.width === undefined || row.width === '' ? null : Number(row.width)} onChange={(value) => setRows((current) => updateRow(current, index, { width: value === null ? '' : String(value) }))} />}
              {row.functionName === 'cast' && <Input aria-label={`计算列 ${index + 1} 目标数据类型`} className="computed-column-parameter-input" placeholder="目标数据类型，例如 STRING 或 DECIMAL(18,2)" value={row.castType} onChange={(event) => setRows((current) => updateRow(current, index, { castType: event.target.value }))} />}
            </div>
            <div className="computed-column-expression-preview"><span>表达式预览</span><Input aria-label={`计算列 ${index + 1} 表达式预览`} readOnly value={buildStructuredComputedExpression(row)} placeholder="填写完整后自动生成" /></div>
          </> : <Input aria-label={`计算列 ${index + 1} 高级表达式`} value={row.expression} placeholder="计算列名=自定义函数(引用字段,参数...)" onChange={(event) => setRows((current) => updateRow(current, index, { expression: event.target.value }))} />}
          {errors[index] && <Alert type="error" showIcon message={errors[index]} />}
        </div>;
      })}
      {errors.length > 0 && rows.length === 0 && errors[0] && <Alert type="error" showIcon message={errors[0]} />}
      <Button icon={<PlusOutlined />} onClick={() => { setRows((current) => [...current, emptyComputedColumnDraft()]); setErrors((current) => [...current, '']); }}>添加计算列</Button>
    </Space>
  </Modal>;
}
