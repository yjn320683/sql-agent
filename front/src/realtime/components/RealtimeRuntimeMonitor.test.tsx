import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { TaskInstance } from '../types';
import RealtimeRuntimeMonitor from './RealtimeRuntimeMonitor';

const instance = (overrides: Partial<TaskInstance> = {}): TaskInstance => ({
  id: 278,
  taskId: 1053,
  versionId: 1196,
  jobId: 'job-1',
  yarnApplicationId: 'application-1',
  trackingUrl: 'http://flink.example/',
  status: 'running',
  executionMode: 'PRODUCTION',
  managed: false,
  createTime: '2026-08-27 17:00:00',
  updateTime: '2026-08-27 17:01:00',
  ...overrides,
});

describe('RealtimeRuntimeMonitor', () => {
  it('使用专用监控布局展示导入实例，而不是展开原始实例字段', () => {
    const html = renderToStaticMarkup(
      <RealtimeRuntimeMonitor taskId={1053} instance={instance()} active={false} />,
    );

    expect(html).toContain('历史导入实例为只读观测');
    expect(html).toContain('Job 状态');
    expect(html).toContain('Source 输出');
    expect(html).toContain('Paimon 提交');
    expect(html).toContain('Checkpoint 健康');
    expect(html).toContain('数据流');
    expect(html).toContain('算子健康');
    expect(html).not.toContain('versionId');
    expect(html).not.toContain('>imported<');
  });

  it('终态实例显示历史提示且不伪装成实时指标', () => {
    const html = renderToStaticMarkup(
      <RealtimeRuntimeMonitor taskId={1053} instance={instance({ status: 'canceled', managed: true })} active={false} />,
    );

    expect(html).toContain('当前实例不是运行态');
    expect(html).toContain('终态实例不展示实时算子指标');
  });
});
