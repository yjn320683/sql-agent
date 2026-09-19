import { describe, expect, it } from 'vitest';
import { taskProgressPollingDelay } from './TaskInstanceProgressCell';
import type { TaskInstanceProgress } from '../types';

const progress = (phase: TaskInstanceProgress['phase'], executionMode = 'DEBUG'): TaskInstanceProgress => ({
  taskId: 1, instanceId: 2, executionMode, taskType: 'compute', instanceStatus: phase,
  phase, stage: phase, stageCount: 6, message: phase,
});

describe('TaskInstanceProgressCell', () => {
  it('only polls transitional and debug qualifying states', () => {
    expect(taskProgressPollingDelay(progress('submitting'))).toBe(2000);
    expect(taskProgressPollingDelay(progress('qualifying'))).toBe(5000);
    expect(taskProgressPollingDelay(progress('running', 'PRODUCTION'))).toBeUndefined();
  });
});
