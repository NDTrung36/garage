import { api, ApiError } from '../../api/client';
import type { InvoiceSummary } from '../invoice/types';
import type { RepairPartItem, ReturnableIssue } from './partApi';

export type { InvoiceSummary } from '../invoice/types';
export type LaborInput = { description: string; quantity: string; unitPrice: number };
export type LaborItem = LaborInput & { id: string; serviceId: string | null; unit: string; lineTotal: number };

export const positiveQuantity = (value: string) => /^(?:0|[1-9]\d{0,14})(?:\.\d{1,3})?$/.test(value) && /[1-9]/.test(value);
export const vnd = (amount: number) => `${amount.toLocaleString('vi-VN')} ₫`;

export function repairError(cause: unknown) {
  if (cause instanceof ApiError && cause.status === 403) {
    return cause.code === 'CSRF_INVALID' || cause.code === 'CSRF_UNAVAILABLE'
      ? 'Mã bảo mật đã hết hạn. Kiểm tra lại thông tin và gửi lại.'
      : 'Tài khoản không có quyền thực hiện thao tác này.';
  }
  return cause instanceof ApiError ? cause.message : 'Mất kết nối. Hãy tải lại phiếu để kiểm tra kết quả.';
}

export function patchIntake(id: string, payload: { odometerKm: number | null; conditionNotes: string | null; internalNotes: string | null }, expectedVersion: number) {
  return api(`/staff/repair-orders/${id}`, { method: 'PATCH', body: JSON.stringify({ ...payload, expectedVersion }) });
}

export function saveLabor(id: string, payload: LaborInput, expectedVersion: number, itemId?: string, serviceId?: string) {
  const body = { ...payload, expectedVersion, ...(itemId || !serviceId ? {} : { serviceId }) };
  return api(`/staff/repair-orders/${id}/labor-items${itemId ? `/${itemId}` : ''}`,
    { method: itemId ? 'PATCH' : 'POST', body: JSON.stringify(body) });
}

export function removeLabor(id: string, itemId: string, expectedVersion: number) {
  return api(`/staff/repair-orders/${id}/labor-items/${itemId}?expectedVersion=${expectedVersion}`, { method: 'DELETE' });
}

export function completeRepair(id: string, expectedVersion: number) {
  return api<{ invoice: InvoiceSummary; version: number; completedAt: string }>(`/staff/repair-orders/${id}/complete`,
    { method: 'POST', body: JSON.stringify({ expectedVersion }) });
}

export type RepairOrderDetail = {
  repairOrderId: string;
  bookingId: string;
  bookingCode: string;
  bookingSource: string;
  bookingStatus: string;
  status: 'IN_PROGRESS' | 'COMPLETED';
  orderVersion: number;
  vehicleType: 'CAR' | 'MOTORBIKE';
  contactName: string;
  contactPhone: string;
  plate: string;
  brand: string | null;
  model: string | null;
  receivedAt: string;
  completedAt: string | null;
  odometerKm: number | null;
  conditionNotes: string | null;
  internalNotes: string | null;
  receivedBy: string;
  completedBy: string | null;
  requestedServices: Array<{ id: string; name: string; referencePrice: number }>;
  laborItems: LaborItem[];
  invoice: InvoiceSummary | null;
  partItems: RepairPartItem[];
  returnableIssues: ReturnableIssue[];
  totals: { laborAmount: number; partAmount: number; totalAmount: number };
};

export type RepairOrderListItem = Pick<RepairOrderDetail, 'repairOrderId' | 'bookingId' | 'bookingCode' | 'bookingSource' | 'status' | 'vehicleType' | 'contactName' | 'contactPhone' | 'plate' | 'receivedAt' | 'completedAt'> & { version: number };
export type RepairOrderPageData = { items: RepairOrderListItem[]; page: number; size: number; totalElements: number; totalPages: number };

export function getRepairOrder(id: string, signal?: AbortSignal) {
  return api<RepairOrderDetail>(`/staff/repair-orders/${encodeURIComponent(id)}`, { signal });
}

export function listRepairOrders(status: string, page: number, signal?: AbortSignal) {
  const params = new URLSearchParams({ page: String(page), size: '20' });
  if (status) params.set('status', status);
  return api<RepairOrderPageData>(`/staff/repair-orders?${params.toString()}`, { signal });
}


