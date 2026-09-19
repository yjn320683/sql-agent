import { Alert, Descriptions, Empty, Select, Spin, Table, Tag, Typography, message } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { getInstanceDebugReport } from '../api';
import type { RealtimeDebugReport, TaskInstance } from '../types';

interface Props { taskId: number; instances: TaskInstance[] }

export default function RealtimeDebugReportPanel({ taskId, instances }: Props) {
  const debugInstances = useMemo(() => instances.filter((item) => item.executionMode === 'DEBUG'), [instances]);
  const [instanceId, setInstanceId] = useState<number>();
  const [report, setReport] = useState<RealtimeDebugReport>();
  const [loading, setLoading] = useState(false);
  useEffect(() => { setInstanceId((current) => current && debugInstances.some((item) => item.id === current) ? current : debugInstances[0]?.id); }, [debugInstances]);
  useEffect(() => {
    if (!instanceId) { setReport(undefined); return; }
    setLoading(true); getInstanceDebugReport(taskId, instanceId).then(setReport)
      .catch((error) => { setReport(undefined); message.error((error as Error).message); })
      .finally(() => setLoading(false));
  }, [instanceId, taskId]);
  if (!debugInstances.length) return <Empty description="暂无调试实例，发起调试后将在这里生成真实无写入报告" />;
  const rows = [...(report?.checks ?? []), ...(report?.diagnostics ?? [])];
  return <div className="realtime-debug-report">
    <div className="realtime-detail-toolbar"><Select value={instanceId} onChange={setInstanceId} style={{ width: 260 }} options={debugInstances.map((item) => ({ value: item.id, label: `调试实例 ${item.id} · ${item.debugReportStatus ?? item.status}` }))} /></div>
    <Spin spinning={loading}>{report ? <>
      <Alert showIcon type={report.status === 'PASSED' ? 'success' : 'error'} message={report.status === 'PASSED' ? '真实无写入调试通过' : '真实无写入调试未通过'} description={report.summary || '未提供摘要'} />
      <Descriptions bordered size="small" column={2} className="realtime-debug-summary"><Descriptions.Item label="任务类型">{report.taskType}</Descriptions.Item><Descriptions.Item label="生成时间">{report.generatedAt || '-'}</Descriptions.Item><Descriptions.Item label="输入表" span={2}>{report.inputs?.join('、') || '-'}</Descriptions.Item><Descriptions.Item label="输出表" span={2}>{report.outputs?.join('、') || '-'}</Descriptions.Item></Descriptions>
      <Table rowKey={(row) => `${row.code}-${row.subject}`} size="small" pagination={false} dataSource={rows} columns={[{ title: '结果', dataIndex: 'status', width: 100, render: (value: string) => <Tag color={value === 'PASSED' ? 'green' : 'red'}>{value}</Tag> }, { title: '检查项', dataIndex: 'code', width: 200 }, { title: '对象', dataIndex: 'subject', width: 280 }, { title: '事实', dataIndex: 'message' }]} />
      {report.logicalPlan && <><Typography.Title level={5}>Flink 执行计划</Typography.Title><pre className="managed-task-json-detail realtime-debug-plan">{report.logicalPlan}</pre></>}
    </> : <Empty description="调试报告尚未生成" />}</Spin>
  </div>;
}
