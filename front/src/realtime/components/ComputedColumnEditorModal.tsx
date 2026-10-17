import { useEffect, useMemo, useRef, useState } from "react";
import { DeleteOutlined, PlusOutlined } from "@ant-design/icons";
import { Alert, AutoComplete, Button, Input, InputNumber, Modal, Segmented, Select, Space } from "antd";
import type { MysqlColumn } from "../types";
import {
  CAST_TYPE_OPTIONS,
  COMPUTED_FUNCTION_OPTIONS,
  type ComputedColumnDraftRow,
  type OfficialComputedFunction,
  buildStructuredComputedExpression,
  computedExpressionToDraft,
  emptyComputedColumnDraft,
  isFunctionCompatibleWithColumn,
  isTemporalPrecisionCompatibleWithColumn,
  validateComputedColumnRows,
} from "./computedColumns";

interface ComputedColumnEditorModalProps {
  open: boolean;
  tableName?: string;
  expressions: string[];
  columns: MysqlColumn[];
  typeMappings?: string[];
  protectedKeys: string[];
  onSave: (expressions: string[]) => void;
  onCancel: () => void;
}

const updateRow = (
  rows: ComputedColumnDraftRow[],
  index: number,
  update: Partial<ComputedColumnDraftRow>,
) => rows.map((row, rowIndex) => rowIndex === index ? { ...row, ...update } : row);

const DATE_FORMAT_OPTIONS = [
  { value: "yyyy-MM-dd", label: "yyyy-MM-dd（日期）" },
  { value: "yyyy-MM", label: "yyyy-MM（月）" },
  { value: "yyyyMM", label: "yyyyMM（月）" },
  { value: "yyyyMMddHH0000", label: "yyyyMMddHH0000（小时）" },
];
const TEMPORAL_PRECISION_OPTIONS = [
  { value: "0", label: "0（秒）" },
  { value: "3", label: "3（毫秒）" },
  { value: "6", label: "6（微秒）" },
  { value: "9", label: "9（纳秒）" },
];
const TEMPORAL_FUNCTIONS = new Set(["year", "month", "day", "hour", "minute", "second", "date_format"]);

export const ComputedColumnEditorModal = ({
  open,
  tableName,
  expressions,
  columns,
  typeMappings = [],
  protectedKeys,
  onSave,
  onCancel,
}: ComputedColumnEditorModalProps) => {
  const [rows, setRows] = useState<ComputedColumnDraftRow[]>([]);
  const [errors, setErrors] = useState<string[]>([]);
  const initializedKey = useRef<string>();

  useEffect(() => {
    if (!open || !tableName) {
      initializedKey.current = undefined;
      return;
    }
    if (initializedKey.current === tableName) return;
    initializedKey.current = tableName;
    const drafts = expressions.map(computedExpressionToDraft);
    setRows(drafts);
    setErrors(drafts.map((row) => row.functionName ? ""
      : validateComputedColumnRows([row], columns, typeMappings).errors[0]));
  }, [expressions, open, tableName]);

  const inputColumns = useMemo(
    () => validateComputedColumnRows(rows, columns, typeMappings).inputColumns,
    [rows, columns, typeMappings],
  );

  const changeMode = (index: number, mode: "structured" | "advanced") => {
    const row = rows[index];
    if (row.mode === mode) return;
    if (mode === "advanced") {
      const expression = buildStructuredComputedExpression(row);
      setRows((current) => updateRow(current, index, { mode, expression }));
      setErrors((current) => current.map((error, errorIndex) => errorIndex === index ? "" : error));
      return;
    }
    if (!row.expression.trim()) {
      setRows((current) => updateRow(current, index, {
        ...emptyComputedColumnDraft(),
        columnName: row.columnName,
      }));
      setErrors((current) => current.map((error, errorIndex) => errorIndex === index ? "" : error));
      return;
    }
    const parsed = computedExpressionToDraft(row.expression);
    setRows((current) => current.map((item, rowIndex) => rowIndex === index ? parsed : item));
    setErrors((current) => {
      const next = [...current];
      next[index] = parsed.functionName ? ""
        : validateComputedColumnRows([parsed], columns, typeMappings).errors[0];
      return next;
    });
  };

  const save = () => {
    const validation = validateComputedColumnRows(rows, columns, typeMappings);
    setErrors(validation.errors);
    if (validation.errors.some(Boolean)) return;
    const previousNames = new Set(expressions.map((expression) => {
      const parsed = computedExpressionToDraft(expression);
      return parsed.columnName;
    }).filter(Boolean));
    const removedReferencedColumn = protectedKeys.find(
      (key) => previousNames.has(key) && !validation.names.has(key),
    );
    if (removedReferencedColumn) {
      setErrors([`计算列 ${removedReferencedColumn} 已被主键或分区键引用，请先调整键配置`]);
      return;
    }
    onSave(validation.expressions);
  };

  return (
    <Modal
      title={`计算列配置${tableName ? `：${tableName}` : ""}`}
      open={open}
      width={1280}
      okText="保存"
      cancelText="取消"
      onOk={save}
      onCancel={onCancel}
      destroyOnHidden
    >
      <Space direction="vertical" size={12} style={{ width: "100%" }}>
        {errors.length > 0 && errors[0] && rows.length === 0 && (
          <Alert type="error" showIcon message={errors[0]} />
        )}
        {rows.map((row, index) => {
          const availableColumns = inputColumns[index];
          const sourceColumn = row.referenceColumn ? availableColumns.get(row.referenceColumn) : undefined;
          const preview = row.mode === "structured" ? buildStructuredComputedExpression(row) : "";
          const functionOptions = COMPUTED_FUNCTION_OPTIONS.map((option) => ({
            ...option,
            disabled: (Boolean(row.referenceColumn) && (option.value === "cast" || option.value === "now"))
              || !isFunctionCompatibleWithColumn(option.value, sourceColumn, typeMappings),
          }));
          const referenceOptions = [...availableColumns.values()]
            .filter((column) => !row.functionName
              || isFunctionCompatibleWithColumn(row.functionName, column, typeMappings))
            .map((column) => ({
              label: `${column.name}[${column.type}]`,
              value: column.name,
            }));
          return (
            <div className="computed-column-editor-row" key={index}>
              <div className="computed-column-editor-row-header">
                <Segmented
                  aria-label={`计算列 ${index + 1} 编辑模式`}
                  value={row.mode}
                  options={[
                    { label: "结构化", value: "structured" },
                    { label: "高级表达式", value: "advanced" },
                  ]}
                  onChange={(value) => changeMode(index, value as "structured" | "advanced")}
                />
                <Button
                  aria-label={`删除计算列 ${index + 1}`}
                  icon={<DeleteOutlined />}
                  onClick={() => {
                    setRows((current) => current.filter((_, rowIndex) => rowIndex !== index));
                    setErrors((current) => current.filter((_, rowIndex) => rowIndex !== index));
                  }}
                />
              </div>
              {row.mode === "structured" ? (
                <>
                  <div className="computed-column-structured-line">
                    <Input
                      className="computed-column-name-input"
                      aria-label={`计算列 ${index + 1} 名称`}
                      placeholder="计算列名，例如 create_date"
                      value={row.columnName}
                      onChange={(event) => setRows((current) => updateRow(current, index, { columnName: event.target.value }))}
                    />
                    <Select
                      className="computed-column-function-select"
                      aria-label={`计算列 ${index + 1} 函数`}
                      placeholder="选择函数"
                      allowClear
                      value={row.functionName}
                      options={functionOptions}
                      onChange={(value: OfficialComputedFunction | undefined) => setRows((current) => updateRow(current, index, {
                        functionName: value,
                        referenceColumn: value === "now" || value === "cast"
                          || (value !== undefined && !isFunctionCompatibleWithColumn(value, sourceColumn, typeMappings))
                          ? undefined : current[index].referenceColumn,
                        castType: value === "cast" ? "STRING" : current[index].castType,
                      }))}
                    />
                    {row.functionName !== "now" && row.functionName !== "cast" && (
                      <Select
                        className="computed-column-reference-select"
                        aria-label={`计算列 ${index + 1} 引用源字段`}
                        showSearch
                        allowClear
                        placeholder="引用源字段"
                        optionFilterProp="label"
                        value={row.referenceColumn}
                        options={referenceOptions}
                        onChange={(value) => setRows((current) => updateRow(current, index, { referenceColumn: value }))}
                      />
                    )}
                    {row.functionName === "cast" && (
                      <Input
                        className="computed-column-reference-select"
                        aria-label={`计算列 ${index + 1} 常量值`}
                        placeholder="常量值，例如 11"
                        value={row.literalValue}
                        onChange={(event) => setRows((current) => updateRow(current, index, { literalValue: event.target.value }))}
                      />
                    )}
                    {row.functionName === "date_format" && (
                      <AutoComplete
                        className="computed-column-parameter-input"
                        aria-label={`计算列 ${index + 1} 日期格式`}
                        placeholder="选择或输入格式（无需引号）"
                        value={row.format}
                        options={DATE_FORMAT_OPTIONS}
                        filterOption={false}
                        onChange={(value) => setRows((current) => updateRow(current, index, { format: value }))}
                      />
                    )}
                    {row.functionName && TEMPORAL_FUNCTIONS.has(row.functionName) && (
                      <Select
                        className="computed-column-parameter-input"
                        aria-label={`计算列 ${index + 1} 时间精度`}
                        allowClear
                        disabled={!isTemporalPrecisionCompatibleWithColumn(sourceColumn, typeMappings)}
                        placeholder="精度（可选，默认不选）"
                        title="整数时间戳或数字字符串可选：0 秒、3 毫秒、6 微秒、9 纳秒；留空时整数按秒解析"
                        value={row.temporalPrecision}
                        options={TEMPORAL_PRECISION_OPTIONS}
                        onChange={(value) => setRows((current) => updateRow(current, index, { temporalPrecision: value }))}
                      />
                    )}
                    {row.functionName === "substring" && (
                      <>
                        <InputNumber
                          className="computed-column-parameter-input"
                          aria-label={`计算列 ${index + 1} 开始位置`}
                          min={0}
                          precision={0}
                          placeholder="开始位置"
                          value={row.begin === undefined || row.begin === "" ? null : Number(row.begin)}
                          onChange={(value) => setRows((current) => updateRow(current, index, { begin: value === null ? "" : String(value) }))}
                        />
                        <InputNumber
                          className="computed-column-parameter-input"
                          aria-label={`计算列 ${index + 1} 结束位置`}
                          min={0}
                          precision={0}
                          placeholder="结束位置（可选）"
                          value={row.end === undefined || row.end === "" ? null : Number(row.end)}
                          onChange={(value) => setRows((current) => updateRow(current, index, { end: value === null ? "" : String(value) }))}
                        />
                      </>
                    )}
                    {row.functionName === "truncate" && (
                      <InputNumber
                        className="computed-column-parameter-input"
                        aria-label={`计算列 ${index + 1} 截断宽度`}
                        min={1}
                        precision={0}
                        placeholder="截断宽度"
                        value={row.width === undefined || row.width === "" ? null : Number(row.width)}
                        onChange={(value) => setRows((current) => updateRow(current, index, { width: value === null ? "" : String(value) }))}
                      />
                    )}
                    {row.functionName === "cast" && (
                      <Select
                        className="computed-column-parameter-input"
                        aria-label={`计算列 ${index + 1} 目标数据类型`}
                        placeholder="默认 STRING"
                        value={row.castType}
                        options={CAST_TYPE_OPTIONS}
                        onChange={(value) => setRows((current) => updateRow(current, index, { castType: value }))}
                      />
                    )}
                  </div>
                  <div className="computed-column-expression-preview">
                    <span>表达式预览</span>
                    <Input
                      aria-label={`计算列 ${index + 1} 表达式预览`}
                      readOnly
                      value={preview}
                      placeholder="填写完整后自动生成"
                    />
                  </div>
                </>
              ) : (
                <Input
                  aria-label={`计算列 ${index + 1} 高级表达式`}
                  value={row.expression}
                  placeholder="计算列名=date_format(引用字段,yyyy-MM-dd)"
                  onChange={(event) => setRows((current) => updateRow(current, index, { expression: event.target.value }))}
                />
              )}
              {errors[index] && <Alert type="error" showIcon message={errors[index]} />}
            </div>
          );
        })}
        <Button
          icon={<PlusOutlined />}
          onClick={() => {
            setRows((current) => [...current, emptyComputedColumnDraft()]);
            setErrors((current) => [...current, ""]);
          }}
        >
          添加计算列
        </Button>
      </Space>
    </Modal>
  );
};

export default ComputedColumnEditorModal;
