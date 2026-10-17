import { RobotOutlined } from '@ant-design/icons';
import { Alert, Button, Select } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { DiagnosticReport } from '../../types';
import { handoffDiagnosticToAi } from '../../components/ai/diagnosticHandoff';
import { getInstanceDiagnosticReport } from '../api';
import type { TaskInstance } from '../types';
import DiagnosticReportView from '../../components/diagnostics/DiagnosticReportView';

interface Props {
  taskId: number;
  instances: TaskInstance[];
  initialInstanceId?: number;
  onRecover?: (instance: TaskInstance) => void;
  onOpenDdl?: () => void;
}

export default function RealtimeDiagnosticPanel({ taskId, instances, initialInstanceId, onRecover, onOpenDdl }: Props) {
  const candidates = useMemo(() => [...instances].sort((a, b) => b.id - a.id), [instances]);
  const [instanceId, setInstanceId] = useState<number | undefined>(initialInstanceId ?? candidates[0]?.id);
  const [report, setReport] = useState<DiagnosticReport>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const requestSequenceRef = useRef(0);

  useEffect(() => {
    if (instanceId && candidates.some((item) => item.id === instanceId)) return;
    setInstanceId(candidates[0]?.id);
  }, [candidates, instanceId]);

  const load = async (refresh = false) => {
    if (!instanceId) return;
    const sequence = ++requestSequenceRef.current;
    setLoading(true); setError('');
    try {
      const next = await getInstanceDiagnosticReport(taskId, instanceId, refresh);
      if (sequence === requestSequenceRef.current) setReport(next);
    } catch (requestError) {
      if (sequence === requestSequenceRef.current) setError((requestError as Error).message);
    } finally {
      if (sequence === requestSequenceRef.current) setLoading(false);
    }
  };

  useEffect(() => {
    setReport(undefined);
    void load();
    return () => { requestSequenceRef.current += 1; };
  }, [instanceId, taskId]); // eslint-disable-line react-hooks/exhaustive-deps

  const selected = candidates.find((item) => item.id === instanceId);
  const currentReport = report?.targetKind === 'REALTIME_INSTANCE' && report.targetId === instanceId ? report : undefined;
  if (!candidates.length) return <Alert showIcon type="info" message="暂无可诊断的运行实例" />;
  return <div className="realtime-diagnostic-panel">
    <header className="realtime-diagnostic-toolbar">
      <div className="realtime-diagnostic-toolbar-title"><h2>实例诊断</h2></div>
      <div className="realtime-diagnostic-toolbar-actions">
        <label htmlFor="realtime-diagnostic-instance">诊断实例</label>
        <Select id="realtime-diagnostic-instance" value={instanceId} onChange={setInstanceId} style={{ minWidth: 300 }} options={candidates.map((item) => ({ value: item.id, label: `实例 ${item.id} · ${item.executionMode === 'DEBUG' ? '调试' : '正式'} · ${item.status}` }))} />
        <Button type="primary" icon={<RobotOutlined />} disabled={!currentReport || loading || !instanceId} onClick={() => {
          if (currentReport && instanceId) handoffDiagnosticToAi({ taskId, instanceId, report: currentReport });
        }}>用 AI 解读</Button>
      </div>
    </header>
    {error && <Alert showIcon type="error" message="加载诊断报告失败" description={error} />}
    <DiagnosticReportView report={currentReport} loading={loading} onRefresh={() => void load(true)} onAction={(type) => { if (type === 'RECOVER' && selected) onRecover?.(selected); if (type === 'VIEW_DDL') onOpenDdl?.(); }} />
  </div>;
}
