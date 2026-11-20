import { api } from '../../api/client';
import type { MovementPage, PartOption } from './types';

export function listMovements(filters: URLSearchParams, signal?: AbortSignal) {
  return api<MovementPage>(`/staff/stock-movements?${filters.toString()}`, { signal });
}

export function listPartOptions(signal?: AbortSignal) {
  return api<{ items: PartOption[] }>('/staff/parts?page=0&size=100', { signal });
}

export function sendStockCommand<T = { stockQuantity: string }>(path: string, payload: object, key: string, signal?: AbortSignal) {
  return api<T>(path, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(payload), signal });
}
