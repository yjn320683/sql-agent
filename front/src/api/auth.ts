import type { LoginUser } from '../types';
import { requestJson } from './client';

export async function getCurrentUser(): Promise<LoginUser | null> {
  try {
    return await requestJson<LoginUser>('/api/auth/me');
  } catch {
    return null;
  }
}

export function login(obId: string): Promise<LoginUser> {
  return requestJson<LoginUser>('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ obId }),
  });
}

export async function logout(): Promise<void> {
  await requestJson<void>('/api/auth/logout', { method: 'POST' });
}
