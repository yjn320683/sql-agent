import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import type { ScheduleDagVO } from '../../types';
import { layout } from './ScheduleDagPage';

const data: ScheduleDagVO = {
  nodes: [
    { id: 1, name: '上游失败', enabled: true, last_execution_id: 91, last_execution_status: 'FAILED', last_backfill_id: 81, durationSampleCount: 2, medianDurationMs: 2000, upstreamCount: 0, downstreamCount: 1, criticalPath: true },
    { id: 2, name: '下游任务', enabled: true, last_execution_status: 'SUCCEEDED', durationSampleCount: 1, medianDurationMs: 1000, upstreamCount: 1, downstreamCount: 0, criticalPath: true },
    { id: 3, name: '无关任务', enabled: true, durationSampleCount: 0, upstreamCount: 0, downstreamCount: 0, criticalPath: false },
  ],
  edges: [{ id: 10, upstreamTaskId: 1, taskId: 2, dependencyType: 'SUCCESS' }],
  criticalPathTaskIds: [1, 2],
};

describe('ScheduleDagPage layout', () => {
  it('高亮失败传播链并提供任务、实例和补数深链入口', () => {
    const navigate = vi.fn();
    const graph = layout(data, 'ALL', navigate);
    const failedNode = graph.nodes.find((node) => node.id === '1');
    const unrelatedNode = graph.nodes.find((node) => node.id === '3');
    const html = renderToStaticMarkup(failedNode?.data.label as ReactNode);

    expect(failedNode?.className).toContain('failed-chain');
    expect(unrelatedNode?.className).not.toContain('failed-chain');
    expect(graph.edges[0].style).toMatchObject({ stroke: '#ff4d4f', strokeWidth: 2 });
    expect(html).toContain('>任务<');
    expect(html).toContain('>实例<');
    expect(html).toContain('>补数<');
  });

  it('状态筛选不会保留被过滤节点的边', () => {
    const graph = layout(data, 'FAILED', vi.fn());
    expect(graph.nodes.map((node) => node.id)).toEqual(['1']);
    expect(graph.edges).toHaveLength(0);
  });
});
