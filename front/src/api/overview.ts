import { requestJson } from './client';

export interface OverviewItem {
  itemKey?: string;
  objectType?: string;
  objectId: number;
  title: string;
  subtitle?: string;
  status?: string;
  occurredAt?: string;
  route: string;
  frequency?: number;
}

export interface OverviewData {
  generatedAt: string;
  summary: Record<string, number>;
  sections: Record<string, { available: boolean; error?: string }>;
  attention: OverviewItem[];
  recentTasks: OverviewItem[];
  frequentTasks: OverviewItem[];
  realtimeTableIssues: OverviewItem[];
}

export const getOverview = () => requestJson<OverviewData>('/api/overview');
