export const LAST_OFFLINE_PATH_KEY = 'sql-agent:last-offline-path';
export const LAST_REALTIME_PATH_KEY = 'sql-agent:last-realtime-path';
export const LAST_DATA_MAP_PATH_KEY = 'sql-agent:last-data-map-path';
export const OFFLINE_FALLBACK = '/chat';
export const REALTIME_FALLBACK = '/realtime/sync-tasks';
export const DATA_MAP_FALLBACK = '/data-map';

export type DevelopmentMode = 'offline' | 'realtime';
export type RememberedMode = DevelopmentMode | 'data-map';
export type ApplicationMode = 'workspace' | RememberedMode;

export function modeOf(pathname: string): ApplicationMode {
  if (pathname === '/overview' || pathname.startsWith('/overview?')) return 'workspace';
  if (pathname === '/data-map' || pathname.startsWith('/data-map/')) return 'data-map';
  return pathname.startsWith('/realtime') ? 'realtime' : 'offline';
}

export function storageKeyOf(mode: RememberedMode): string {
  if (mode === 'data-map') return LAST_DATA_MAP_PATH_KEY;
  return mode === 'realtime' ? LAST_REALTIME_PATH_KEY : LAST_OFFLINE_PATH_KEY;
}

export function fallbackOf(mode: RememberedMode): string {
  if (mode === 'data-map') return DATA_MAP_FALLBACK;
  return mode === 'realtime' ? REALTIME_FALLBACK : OFFLINE_FALLBACK;
}

export function validRememberedPath(mode: RememberedMode, remembered?: string | null): string {
  const fallback = fallbackOf(mode);
  if (!remembered) return fallback;
  return modeOf(remembered) === mode ? remembered : fallback;
}
