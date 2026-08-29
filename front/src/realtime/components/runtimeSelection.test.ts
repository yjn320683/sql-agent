import { describe, expect, it } from 'vitest';
import type { TaskInstance } from '../types';
import { selectRuntimeInstance } from './runtimeSelection';

const instance = (id: number, status: string, managed: boolean): TaskInstance => ({
  id,
  taskId: 1,
  status,
  executionMode: 'PRODUCTION',
  managed,
  createTime: `2026-08-28 00:00:0${id}`,
  updateTime: `2026-08-28 00:00:0${id}`,
});

describe('selectRuntimeInstance', () => {
  it('优先选择当前运行实例，不因导入只读而退回已取消实例', () => {
    expect(selectRuntimeInstance([
      instance(250, 'canceled', true),
      instance(278, 'running', false),
    ])?.id).toBe(278);
  });

  it('没有运行实例时展示接口返回的最新实例', () => {
    expect(selectRuntimeInstance([
      instance(300, 'canceled', false),
      instance(299, 'failed', true),
    ])?.id).toBe(300);
  });
});
