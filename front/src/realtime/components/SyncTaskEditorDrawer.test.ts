import { describe, expect, it } from 'vitest';
import { createAsyncLimiter } from './SyncTaskEditorDrawer';

describe('实时同步多表 Schema 请求限制', () => {
  it('最多同时读取六张表，并继续执行排队任务', async () => {
    const limit = createAsyncLimiter(6);
    let active = 0;
    let maxActive = 0;
    const releases: Array<() => void> = [];
    const tasks = Array.from({ length: 8 }, (_, index) => limit(() => new Promise<number>((resolve) => {
      active += 1;
      maxActive = Math.max(maxActive, active);
      releases.push(() => {
        active -= 1;
        resolve(index);
      });
    })));

    await Promise.resolve();
    expect(active).toBe(6);
    releases.splice(0, 6).forEach((release) => release());
    await Promise.resolve();
    await Promise.resolve();
    expect(maxActive).toBe(6);
    expect(active).toBe(2);
    releases.splice(0).forEach((release) => release());
    expect(await Promise.all(tasks)).toEqual([0, 1, 2, 3, 4, 5, 6, 7]);
  });
});
