import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Empty,
  Space,
  Spin,
  Switch,
  Table,
  Tag,
  Tooltip,
  message,
} from 'antd';
import {
  CheckCircleFilled,
  CloseCircleFilled,
  ReloadOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { getPlatformHealth } from '../../api/workspace';
import type { PlatformDependencyVO, PlatformHealthVO } from '../../types';

const dependencyNames: Record<string, { label: string; description: string }> = {
  agent: { label: 'SQL Agent', description: '任务执行、诊断与 Claude Code Agent 入口' },
  dataCompare: { label: 'Datacompare', description: '任务版本与 Hive 表数据验数服务' },
  hiveServer2: { label: 'HiveServer2', description: 'SQL 编译、Explain 与任务执行入口' },
  hiveMetastore: { label: 'Hive Metastore', description: '库表、字段、分区与统计元数据' },
  webHdfs: { label: 'WebHDFS', description: '表路径、文件数量与存储大小' },
  yarnResourceManager: { label: 'YARN ResourceManager', description: 'Application、Attempt 与运行资源信息' },
  mapReduceJobHistory: { label: 'MapReduce JobHistory', description: '历史 Job、Counter、Task 与聚合日志' },
};

function formatTime(value?: string): string {
  if (!value) return '-';
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(new Date(value));
}

const detailFields: Record<string, string[]> = {
  hiveServer2: ['compilationMs'],
  hiveMetastore: ['databaseCount'],
  webHdfs: ['type', 'owner', 'childrenNum', 'snapshotEnabled'],
  yarnResourceManager: ['state', 'haState', 'resourceManagerVersion', 'hadoopVersion'],
  mapReduceJobHistory: ['hadoopVersion', 'startedOn'],
};

function formatDetails(name: string, details?: Record<string, unknown>): string {
  if (!details || !Object.keys(details).length) return '-';
  const allowed = detailFields[name] || [];
  return Object.entries(details)
    .filter(([key]) => allowed.includes(key))
    .map(([key, value]) => `${key}: ${typeof value === 'object' ? JSON.stringify(value) : String(value)}`)
    .join(' · ') || '-';
}

export default function PlatformStatusPage() {
  const [health, setHealth] = useState<PlatformHealthVO>();
  const [loading, setLoading] = useState(false);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [error, setError] = useState('');

  const load = useCallback(async (silent = false) => {
    if (!silent) setLoading(true);
    try {
      const result = await getPlatformHealth();
      setHealth(result);
      setError('');
    } catch (loadError) {
      const text = (loadError as Error).message;
      setError(text);
      if (!silent) {
        message.error({
          key: 'platform-health-load-error',
          content: `平台健康检查失败：${text}`,
        });
      }
    } finally {
      if (!silent) setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    if (!autoRefresh) return undefined;
    const timer = window.setInterval(() => void load(true), 30_000);
    return () => window.clearInterval(timer);
  }, [autoRefresh, load]);

  const reachable = health?.dependencies.filter((item) => item.reachable).length || 0;
  const total = health?.dependencies.length || 0;
  const averageLatency = useMemo(() => {
    const samples = health?.dependencies
      .map((item) => item.latencyMs)
      .filter((value): value is number => value != null) || [];
    return samples.length ? Math.round(samples.reduce((sum, value) => sum + value, 0) / samples.length) : undefined;
  }, [health]);

  const columns: ColumnsType<PlatformDependencyVO> = [
    {
      title: '依赖服务', dataIndex: 'name', width: 250, fixed: 'left',
      render: (name: string) => {
        const metadata = dependencyNames[name] || { label: name, description: '' };
        return <div className="platform-service-name"><strong>{metadata.label}</strong><span>{metadata.description}</span></div>;
      },
    },
    {
      title: '状态', dataIndex: 'reachable', width: 112, fixed: 'left',
      render: (reachable: boolean, row) => reachable
        ? <Tag icon={<CheckCircleFilled />} color="success">可达</Tag>
        : <Tag icon={<CloseCircleFilled />} color="error">{row.configured ? '不可达' : '未配置'}</Tag>,
    },
    {
      title: '延迟', dataIndex: 'latencyMs', width: 110,
      render: (value) => value == null ? '-' : <span className={value > 2000 ? 'platform-latency slow' : 'platform-latency'}>{value} ms</span>,
    },
    {
      title: '检查结果', key: 'details',
      render: (_, row) => row.reachable
        ? <span className="platform-detail-text">{formatDetails(row.name, row.details)}</span>
        : <div className="platform-error-text"><strong>{row.errorCode || 'dependency_unavailable'}</strong><span>{row.message || '依赖检查失败'}</span></div>,
    },
  ];

  return (
    <div className="data-page platform-status-page">
      <div className="data-toolbar platform-status-toolbar">
        <span className="result-count">共 {total} 项依赖</span>
        <Space size={10}>
          <span className="muted-text">30 秒刷新</span>
          <Switch size="small" checked={autoRefresh} onChange={setAutoRefresh} />
          <Tooltip title="立即检查"><Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()} /></Tooltip>
        </Space>
      </div>

      <section className="platform-health-summary" aria-label="平台健康概览">
        <div><span>整体状态</span><strong className={health?.complete ? 'healthy' : 'unhealthy'}>{health?.complete ? '健康' : health ? '部分不可用' : '-'}</strong></div>
        <div><span>可达依赖</span><strong>{reachable} / {total}</strong></div>
        <div><span>平均检查延迟</span><strong>{averageLatency == null ? '-' : `${averageLatency} ms`}</strong></div>
        <div><span>最近检查</span><strong className="platform-checked-at">{formatTime(health?.fetchedAt)}</strong></div>
      </section>

      {error ? <Alert type="error" showIcon message="无法完成平台健康检查" description={error} /> : null}
      {health && (!health.complete || health.warnings.length) ? (
        <Alert
          type="warning"
          showIcon
          message="部分诊断能力不可用"
          description={[...health.warnings, ...health.missingReasons].join('；')}
        />
      ) : null}

      <section className="data-panel platform-health-panel">
        {loading && !health ? <div className="platform-health-loading"><Spin /></div> : (
          <Table
            rowKey="name"
            size="middle"
            pagination={false}
            columns={columns}
            dataSource={health?.dependencies || []}
            scroll={{ x: 900 }}
            locale={{ emptyText: <Empty description="尚未取得真实依赖检查结果" /> }}
          />
        )}
      </section>
    </div>
  );
}
