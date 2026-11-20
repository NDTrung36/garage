import { api, ApiError } from '../../api/client';
import type { BookingPayload, VehicleType } from './bookingApi';

export const bookingStatuses = {
  CONFIRMED: 'Đã xác nhận',
  CHECKED_IN: 'Đã tiếp nhận',
  COMPLETED: 'Đã hoàn tất',
  CANCELLED: 'Đã hủy',
  NO_SHOW: 'Không đến',
} as const;

export type BookingStatus = keyof typeof bookingStatuses;
export type StaffBooking = {
  id: string;
  code: string;
  source: 'ONLINE' | 'WALK_IN';
  status: BookingStatus;
  version: number;
  startsAt: string | null;
  endsAt: string | null;
  receivedAt: string | null;
  vehicleType: VehicleType;
  contactName: string;
  contactPhone: string;
  plate: string;
  brand: string | null;
  model: string | null;
  requestedServices: Array<{ id: string; name: string; referencePrice: number }>;
  repairOrderId: string | null;
};
export type StaffBookingPage = { items: StaffBooking[]; page: number; totalElements: number; totalPages: number };
export type IntakeDetails = { odometerKm: number | null; conditionNotes: string; internalNotes: string };
export type IntakeDraft = Omit<IntakeDetails, 'odometerKm'> & { odometerKm: string };
export type WalkInPayload = Omit<BookingPayload, 'slotId'> & IntakeDetails;
export type IntakeResult = {
  bookingId: string;
  bookingStatus: 'CHECKED_IN' | 'COMPLETED';
  repairOrderId: string;
  repairStatus: 'IN_PROGRESS' | 'COMPLETED';
};

export const emptyIntakeDraft: IntakeDraft = { odometerKm: '', conditionNotes: '', internalNotes: '' };
export const repairOrderPath = (id: string) => `/staff/repair-orders/${id}`;

export function listStaffBookings(date: string, status: BookingStatus | '', page: number, signal: AbortSignal) {
  const query = new URLSearchParams({ date, page: String(page) });
  if (status) query.set('status', status);
  return api<StaffBookingPage>(`/staff/bookings?${query}`, { signal });
}

export function checkInBooking(id: string, expectedVersion: number, details: IntakeDetails, signal: AbortSignal) {
  return submitIntake(`/staff/bookings/${id}/check-in`, { ...details, expectedVersion }, signal);
}

export function createWalkIn(payload: WalkInPayload, key: string, signal: AbortSignal) {
  return submitIntake('/staff/walk-ins', payload, signal, key);
}

async function submitIntake(path: string, payload: object, signal: AbortSignal, key?: string) {
  const result = await api<Partial<IntakeResult> | null>(path, {
    method: 'POST',
    headers: key ? { 'Idempotency-Key': key } : undefined,
    body: JSON.stringify(payload),
    signal: AbortSignal.any([signal, AbortSignal.timeout(15_000)]),
  });
  if (!result || typeof result.repairOrderId !== 'string' || !/^[1-9]\d*$/.test(result.repairOrderId)
      || (result.bookingStatus !== 'CHECKED_IN' && result.bookingStatus !== 'COMPLETED')) {
    throw new ApiError('Chưa nhận được mã phiếu từ máy chủ.', 200, 'INVALID_INTAKE_RESPONSE');
  }
  return result as IntakeResult;
}

type FieldMessages = Record<string, string>;
export function readIntakeDraft(draft: IntakeDraft): { details: IntakeDetails; errors: FieldMessages } {
  const odometerKm = draft.odometerKm === '' ? null : Number(draft.odometerKm);
  const errors: FieldMessages = {};
  if (odometerKm !== null && (!/^\d+$/.test(draft.odometerKm) || !Number.isInteger(odometerKm) || odometerKm > 2_147_483_647)) {
    errors.odometerKm = 'Nhập số nguyên từ 0 đến 2.147.483.647.';
  }
  if (draft.conditionNotes.length > 2000) errors.conditionNotes = 'Tối đa 2.000 ký tự.';
  if (draft.internalNotes.length > 2000) errors.internalNotes = 'Tối đa 2.000 ký tự.';
  return { details: { ...draft, odometerKm }, errors };
}

const intakeCodeFields: Record<string, [string, string]> = {
  INVALID_CONTACT_NAME: ['contact.fullName', 'Nhập họ tên từ 2 đến 100 ký tự.'],
  INVALID_PHONE: ['contact.phone', 'Nhập số điện thoại di động Việt Nam hợp lệ.'],
  INVALID_PLATE: ['vehicle.plateNumber', 'Nhập biển số hợp lệ, tối đa 20 ký tự.'],
  INVALID_VEHICLE_TYPE: ['vehicle.type', 'Chọn loại xe hợp lệ.'],
  INVALID_SERVICE_IDS: ['serviceIds', 'Chọn từ 1 đến 50 dịch vụ.'],
  SERVICE_NOT_AVAILABLE: ['serviceIds', 'Một dịch vụ đã thay đổi. Hãy kiểm tra danh sách và chọn lại.'],
  VEHICLE_TYPE_CONFLICT: ['vehicle.type', 'Loại xe không khớp với hồ sơ đã lưu.'],
};

export function intakeFieldMessages(cause: unknown): FieldMessages {
  if (!(cause instanceof ApiError)) return {};
  const fields: FieldMessages = {};
  for (const error of cause.fieldErrors) {
    const field = error.field.replace(/\[\d+\]$/, '');
    fields[field] = error.message ?? intakeCodeFields[error.code]?.[1] ?? 'Kiểm tra lại giá trị này.';
  }
  const fallback = cause.code ? intakeCodeFields[cause.code] : undefined;
  if (fallback && !fields[fallback[0]]) fields[fallback[0]] = fallback[1];
  return fields;
}
