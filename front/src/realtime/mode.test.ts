import { describe, expect, it } from 'vitest';
import {
  LAST_OFFLINE_PATH_KEY,
  LAST_REALTIME_PATH_KEY,
  modeOf,
  storageKeyOf,
  validRememberedPath,
} from './mode';

describe('实时/离线路由切换', () => {
  it('识别实时深链接并使用独立记忆键', () => {
    expect(modeOf('/realtime/sync-tasks/12')).toBe('realtime');
    expect(modeOf('/overview')).toBe('workspace');
    expect(storageKeyOf('realtime')).toBe(LAST_REALTIME_PATH_KEY);
    expect(storageKeyOf('offline')).toBe(LAST_OFFLINE_PATH_KEY);
  });

  it('恢复对应模式最后页面', () => {
    expect(validRememberedPath('realtime', '/realtime/alerts?status=open')).toBe('/realtime/alerts?status=open');
    expect(validRememberedPath('offline', '/tasks?status=running')).toBe('/tasks?status=running');
  });

  it('拒绝跨模式污染并回退到默认页面', () => {
    expect(validRememberedPath('realtime', '/chat')).toBe('/realtime/sync-tasks');
    expect(validRememberedPath('offline', '/realtime/servers')).toBe('/chat');
  });
});
