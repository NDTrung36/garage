import { api } from '../../api/client';
import type { PartPageData, PartPayload } from './types';

export function listParts(path: '/staff/parts' | '/admin/parts', filters: URLSearchParams, signal?: AbortSignal) {
  return api<PartPageData>(`${path}?${filters.toString()}`, { signal });
}

export function createPart(payload: PartPayload) {
  return api('/admin/parts', { method: 'POST', body: JSON.stringify(payload) });
}

export function updatePart(id: string, payload: PartPayload) {
  return api(`/admin/parts/${encodeURIComponent(id)}`, { method: 'PATCH', body: JSON.stringify(payload) });
}
