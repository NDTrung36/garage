import { api } from '../../api/client';
import type { InvoicePageData, InvoiceDetail, InvoicePaymentMethod, InvoicePaymentResponse } from './types';

export function listInvoices(paymentStatus: string, page: number, signal?: AbortSignal) {
  const params = new URLSearchParams({ page: String(page), size: '20' });
  if (paymentStatus) params.set('paymentStatus', paymentStatus);
  return api<InvoicePageData>(`/staff/invoices?${params.toString()}`, { signal });
}

export function getInvoice(id: string, signal?: AbortSignal) {
  return api<InvoiceDetail>(`/staff/invoices/${encodeURIComponent(id)}`, { signal });
}

export function markInvoicePaid(id: string, key: string, expectedVersion: number, paymentMethod: InvoicePaymentMethod) {
  return api<InvoicePaymentResponse>(`/staff/invoices/${encodeURIComponent(id)}/mark-paid`, {
    method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ expectedVersion, paymentMethod }),
  });
}

export function resetInvoicePayment(id: string, key: string, expectedVersion: number, reason: string) {
  return api<InvoicePaymentResponse>(`/admin/invoices/${encodeURIComponent(id)}/reset-payment`, {
    method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ expectedVersion, reason }),
  });
}
