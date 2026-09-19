import { Alert, Select } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import type { DiagnosticReport } from '../../types';
import { getInstanceDiagnosticReport } from '../api';
import type { TaskInstance } from '../types';
import DiagnosticReportView from '../../components/diagnostics/DiagnosticReportView';

interface Props {
  taskId: number;
  instances: TaskInstance[];
  initialInstanceId?: number;
  onRecover?: (instance: TaskInstance) => void;
}

export default function RealtimeDiagnosticPanel({ taskId, instances, initialInstanceId, onRecover }: Props) {
  const candidates = useMemo(() => [...instances].sort((a, b) => b.id - a.id), [instances]);
  const [instanceId, setInstanceId] = useState<number | undefined>(initialInstanceId ?? candidates[0]?.id);
  const [report, setReport] = useState<DiagnosticReport>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (instanceId && candidates.some((item) => item.id === instanceId)) return;
    setInstanceId(candidates[0]?.id);
  }, [candidates, instanceId]);

  const load = async (refresh = false) => {
    if (!instanceId) return;
    setLoading(true); setError('');
    try { setReport(await getInstanceDiagnosticReport(taskId, instanceId, refresh)); }
    catch (requestError) { setError((requestError as Error).message); }
    finally { setLoading(false); }
  };

  useEffect(() => { setReport(undefined); void load(); }, [instanceId, taskId]); // eslint-disable-line react-hooks/exhaustive-deps

  const selected = candidates.find((item) => item.id === instanceId);
  if (!candidates.length) return <Alert showIcon type="info" message="暂无可诊断的运行实例" />;
  return <div className="realtime-diagnostic-panel">
    <div className="realtime-diagnostic-selector">
      <span>诊断实例</span>
      <Select value={instanceId} onChange={setInstanceId} style={{ minWidth: 320 }} options={candidates.map((item) => ({ value: item.id, label: `实例 ${item.id} · ${item.executionMode === 'DEBUG' ? '调试' : '正式'} · ${item.status}` }))} />
    </div>
    {error && <Alert showIcon type="error" message="加载诊断报告失败" description={error} />}
    <DiagnosticReportView report={report} loading={loading} onRefresh={() => void load(true)} onAction={(type) => { if (type === 'RECOVER' && selected) onRecover?.(selected); }} />
  </div>;
}
