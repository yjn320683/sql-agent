import { Form, Input, InputNumber } from 'antd';
import { useEffect } from 'react';

type NamePath = Array<string | number>;

export const mergeSyncTopologyOverrides = (
  dynamicOverrides: Record<string, unknown>,
  sourceOverrides?: Record<string, unknown>,
  taskParallelism?: unknown,
) => {
  const sink = Number(sourceOverrides?.['sink.parallelism'] ?? taskParallelism ?? 3);
  const parallelism = Number.isInteger(sink) && sink >= 1 && sink <= 4 ? sink : 3;
  return {
    ...dynamicOverrides,
    bucket: String(parallelism),
    'sink.parallelism': String(parallelism),
  };
};

export default function SyncTopologyConfigRows({
  tableConfPath,
  parallelismPath,
  flinkConfPath,
  readOnly = false,
  frozenParallelism,
}: {
  tableConfPath: NamePath;
  parallelismPath: NamePath;
  flinkConfPath: NamePath;
  readOnly?: boolean;
  frozenParallelism?: number;
}) {
  const form = Form.useFormInstance();
  const sinkPath = [...tableConfPath, 'sink.parallelism'];
  const bucketPath = [...tableConfPath, 'bucket'];
  const slotsPath = [...flinkConfPath, 'taskmanager.numberOfTaskSlots'];
  const sinkParallelism = Form.useWatch(sinkPath, form);
  const taskParallelism = Form.useWatch(parallelismPath, form);

  useEffect(() => {
    const raw = readOnly && frozenParallelism !== undefined
      ? frozenParallelism
      : readOnly && taskParallelism !== undefined
        ? taskParallelism : sinkParallelism ?? taskParallelism ?? '3';
    const parallelism = Number(raw);
    if (!Number.isInteger(parallelism) || parallelism < 1 || parallelism > 4) return;
    if (sinkParallelism === undefined || (readOnly && Number(sinkParallelism) !== parallelism)) {
      form.setFieldValue(sinkPath, String(parallelism));
    }
    form.setFieldValue(bucketPath, String(parallelism));
    form.setFieldValue(parallelismPath, parallelism);
    form.setFieldValue(slotsPath, String(parallelism));
  }, [form, frozenParallelism, readOnly, sinkParallelism, taskParallelism]);

  return <>
    <Form.Item label="目标 Paimon 表 Bucket" name={bucketPath} className="realtime-dynamic-param">
      <Input disabled />
    </Form.Item>
    <Form.Item label="目标 Paimon 表 Sink 并行度" name={sinkPath} className="realtime-dynamic-param"
      rules={[{ required: true, message: '请输入 Sink 并行度' }]}>
      <InputNumber min={1} max={4} precision={0} disabled={readOnly} style={{ width: '100%' }} />
    </Form.Item>
  </>;
}
