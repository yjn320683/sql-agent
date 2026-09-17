import { requestJson } from './client';

export interface AssetLineageNode {
  id: string; nodeType: 'TABLE' | 'COLUMN' | 'TASK'; catalog?: string; database?: string;
  table?: string; column?: string; qualifiedName?: string; taskScope?: string; taskId?: number;
  taskName?: string; taskType?: string;
}
export interface AssetLineageEdge { id: string; source: string; target: string; relationKind: string; usageType?: string; sourceColumn?: string }
export interface AssetLineageGraph { start: AssetLineageNode; nodes: AssetLineageNode[]; edges: AssetLineageEdge[]; depth: number; direction: string; view?: 'TABLE' | 'COLUMN' | 'TASK'; complete: boolean; missingReasons: string[] }
export interface AssetSearchPage { records: AssetLineageNode[]; total: number; page: number; pageSize: number; complete: boolean }
export interface AssetImpact { asset: Record<string,string>; changeType?: string; columns: string[]; affectedTasks: AssetLineageNode[]; affectedAssets: AssetLineageNode[]; edges: AssetLineageEdge[]; fieldImpactKnown: boolean; complete: boolean; missingReasons: string[] }

export const searchLineageAssets = (query: URLSearchParams) => requestJson<AssetSearchPage>(`/api/data-map/catalog/search?${query}`);
export const getAssetLineageGraph = (query: URLSearchParams) => requestJson<AssetLineageGraph>(`/api/data-map/lineage/graph?${query}`);
export const analyzeAssetImpact = (value: Record<string, unknown>) => requestJson<AssetImpact>('/api/data-map/impact-analysis', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value) });
