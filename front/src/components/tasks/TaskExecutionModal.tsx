import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Form, Input, InputNumber, Modal, Select, Space, Typography, message } from 'antd';
import { CalendarOutlined, PlayCircleOutlined } from '@ant-design/icons';
import { executeTask } from '../../api/tasks';
import { previewSqlStructure } from '../../api/workspace';
import type { SqlStructurePreviewVO, SqlTaskParameter, TaskExecutionVO } from '../../types';

interface Props {
  open: boolean;
  taskId: number;
  taskName: string;
  sql: string;
  parameters?: SqlTaskParameter[];
  revision?: number;
  effectiveVersionNo?: number;
  versionNo?: number;
  onClose: () => void;
  onSubmitted: (execution: TaskExecutionVO) => void;
}

interface FormValues {
  businessDate?: string;
  parameters?: Record<string, string | number | boolean | null | undefined>;
}

const legacyDateExpression = /\$\{yyyy-MM-dd\s*,\s*-?\d+\s*,\s*(?:day|month|year)\s*}/i;

function defaultValues(parameters: SqlTaskParameter[]): Record<string, string | number | boolean> {
  return parameters.reduce<Record<string, string | number | boolean>>((values, parameter) => {
    if (parameter.defaultValue !== undefined) values[parameter.name] = parameter.defaultValue;
    return values;
  }, {});
}

function ParameterInput({ parameter }: { parameter: SqlTaskParameter }) {
  if (parameter.type === 'BOOLEAN') {
    return <Select options={[{ label: 'true', value: true }, { label: 'false', value: false }]} placeholder="请选择布尔值" />;
  }
  if (parameter.type === 'INTEGER') return <InputNumber precision={0} style={{ width: '100%' }} placeholder="请输入整数" />;
  if (parameter.type === 'DECIMAL') return <InputNumber stringMode style={{ width: '100%' }} placeholder="请输入数值" />;
  if (parameter.type === 'DATE') return <Input type="date" />;
  if (parameter.type === 'DATETIME') return <Input type="datetime-local" />;
  return <Input allowClear placeholder={parameter.description || '请输入参数值'} />;
}

export default function TaskExecutionModal({
  open, taskId, taskName, sql, parameters = [], revision, effectiveVersionNo, versionNo, onClose, onSubmitted,
}: Props) {
  const [form] = Form.useForm<FormValues>();
  const [submitting, setSubmitting] = useState(false);
  const [previewing, setPreviewing] = useState(false);
  const [preview, setPreview] = useState<SqlStructurePreviewVO>();
  const [previewError, setPreviewError] = useState('');
  const needsBusinessDate = useMemo(() => legacyDateExpression.test(sql), [sql]);

  useEffect(() => {
    if (!open) return;
    form.setFieldsValue({ businessDate: undefined, parameters: defaultValues(parameters) });
  }, [form, open, parameters, taskId, versionNo]);

  const validateAndPreview = async (): Promise<FormValues | undefined> => {
    try {
      const values = await form.validateFields();
      setPreviewing(true);
      const result = await previewSqlStructure({
        sql,
        parameterSchema: parameters,
        parameters: values.parameters || {},
        businessDate: values.businessDate,
        validateParameterValues: true,
      });
      setPreview(result);
      setPreviewError('');
      return values;
    } catch (error) {
      if ((error as { errorFields?: unknown }).errorFields) return undefined;
      setPreview(undefined);
      setPreviewError((error as Error).message);
      message.error(`SQL 预检失败：${(error as Error).message}`);
      return undefined;
    } finally {
      setPreviewing(false);
    }
  };

  const submit = async () => {
    const values = await validateAndPreview();
    if (!values) return;
    try {
      setSubmitting(true);
      const execution = await executeTask(taskId, {
        revision: versionNo == null ? revision : undefined,
        versionNo,
        businessDate: values.businessDate,
        parameters: values.parameters || {},
      });
      message.success(`实例 ${execution.id} 已提交`);
      onSubmitted(execution);
    } catch (error) {
      message.error(`提交执行失败：${(error as Error).message}`);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      className="task-execution-modal"
      open={open}
      width={760}
      title={<Space><PlayCircleOutlined /><span>执行 {versionNo == null ? '当前生效代码' : `版本 v${versionNo}`}</span></Space>}
      destroyOnHidden
      onCancel={onClose}
      footer={[
        <Button key="cancel" onClick={onClose}>取消</Button>,
        <Button key="preview" loading={previewing} onClick={() => void validateAndPreview()}>预检参数</Button>,
        <Button key="submit" type="primary" icon={<PlayCircleOutlined />} loading={submitting} onClick={() => void submit()}>提交执行</Button>,
      ]}
    >
      <div className="execution-source-summary">
        <div><span>任务</span><strong>{taskId} {taskName}</strong></div>
        <div><span>来源</span><strong>{versionNo == null ? `${effectiveVersionNo ? `生效版本 v${effectiveVersionNo}` : '当前生效代码'} · revision ${revision ?? '-'}` : `版本 v${versionNo}`}</strong></div>
        <div><span>脚本</span><strong>{sql.split('\n').length} 行 · {sql.length.toLocaleString()} 字符</strong></div>
      </div>
      {previewError ? <Alert type="error" showIcon message="SQL 预检未通过" description={previewError} /> : null}
      {preview ? (
        <div className="execution-preview-summary">
          <strong>预检通过 · {preview.stepCount} 个 Step</strong>
          <span>{preview.steps.map((step) => `${step.stepNo}:${step.statementType}`).join('  ·  ')}</span>
        </div>
      ) : null}
      <Form
        form={form}
        layout="vertical"
        requiredMark="optional"
        className="execution-parameter-form"
        onValuesChange={() => { setPreview(undefined); setPreviewError(''); }}
      >
        {needsBusinessDate ? (
          <Form.Item name="businessDate" label={<Space><CalendarOutlined />业务日期</Space>} rules={[{ required: true, message: '日期表达式需要业务日期' }]}>
            <Input type="date" />
          </Form.Item>
        ) : null}
        {parameters.length ? parameters.map((parameter) => (
          <Form.Item
            key={parameter.name}
            name={['parameters', parameter.name]}
            label={<Space><Typography.Text code>{parameter.name}</Typography.Text><Typography.Text type="secondary">{parameter.type}</Typography.Text></Space>}
            extra={parameter.description}
            rules={[{ required: parameter.required, message: `请填写参数 ${parameter.name}` }]}
          >
            <ParameterInput parameter={parameter} />
          </Form.Item>
        )) : <div className="execution-no-parameters">当前脚本没有声明运行参数，可直接提交。</div>}
      </Form>
    </Modal>
  );
}
