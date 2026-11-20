import { api } from '../../api/client';
import type { Part } from '../part/types';

export type RepairPartItem = {
  id: string;
  partId: string;
  description: string;
  unit: string;
  quantity: string;
  unitPrice: number;
  lineTotal: number;
};

export type ReturnableIssue = {
  id: string;
  repairItemId: string;
  partId: string;
  issuedQuantity: string;
  remainingQuantity: string;
  createdAt: string;
};

export type RepairCommandResponse = { movement: unknown; stockQuantity: string; partItem: RepairPartItem; orderVersion: number; repairTotal: number };
export type RepairPriceResponse = { partItem: RepairPartItem; orderVersion: number; repairTotal: number };

export function listIssueParts(vehicleType: 'CAR' | 'MOTORBIKE', signal?: AbortSignal) {
  return api<{ items: Part[] }>(`/staff/parts?page=0&size=100&active=true&vehicleType=${vehicleType}`, { signal });
}

export function issueRepairPart(orderId: string, payload: { partId: string; quantity: string }, key: string) {
  return api<RepairCommandResponse>(`/staff/repair-orders/${encodeURIComponent(orderId)}/part-issues`, {
    method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(payload),
  });
}

export function returnRepairPart(orderId: string, payload: { originalIssueId: string; quantity: string }, key: string) {
  return api<RepairCommandResponse>(`/staff/repair-orders/${encodeURIComponent(orderId)}/part-returns`, {
    method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(payload),
  });
}

export function updateRepairPartPrice(orderId: string, itemId: string, payload: { unitPrice: number; reason: string; expectedVersion: number }) {
  return api<RepairPriceResponse>(`/staff/repair-orders/${encodeURIComponent(orderId)}/part-items/${encodeURIComponent(itemId)}/price`, {
    method: 'PATCH', body: JSON.stringify(payload),
  });
}
