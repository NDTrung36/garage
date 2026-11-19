import { publicApi, PublicApiError } from '../../api/publicClient';

export type VehicleType = 'CAR' | 'MOTORBIKE';
export type GarageService = { id: string; code: string; name: string; description: string | null; vehicleType: VehicleType | 'BOTH'; price: number };
export type OpenSlot = { id: string; startsAt: string; endsAt: string; remainingCapacity: number; closed: boolean };
export type BookingPayload = {
  contact: { fullName: string; phone: string };
  vehicle: { type: VehicleType; plateNumber: string; brand?: string; model?: string };
  serviceIds: string[];
  slotId: string;
  note?: string;
};
export type BookingResult = {
  bookingCode: string; status: string; startsAt: string; endsAt: string; vehicleType: VehicleType;
  services: Array<{ name: string; referencePrice: number }>; referenceTotal: number;
};

export const listServices = (vehicleType: VehicleType, signal?: AbortSignal) =>
  publicApi<GarageService[]>(`/public/services?vehicleType=${vehicleType}`, { signal });
export const listSlots = (date: string, vehicleType: VehicleType, signal?: AbortSignal) =>
  publicApi<OpenSlot[]>(`/public/slots?date=${encodeURIComponent(date)}&vehicleType=${vehicleType}`, { signal });
export async function createBooking(payload: BookingPayload, key: string): Promise<BookingResult> {
  const result = await publicApi<Partial<BookingResult> | null>('/public/bookings', {
    method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(payload),
  });
  if (!result || typeof result.bookingCode !== 'string' || !result.bookingCode.trim()
    || result.status !== 'CONFIRMED' || result.vehicleType !== payload.vehicle.type
    || typeof result.startsAt !== 'string' || !Number.isFinite(Date.parse(result.startsAt))
    || typeof result.endsAt !== 'string' || !Number.isFinite(Date.parse(result.endsAt))
    || !Number.isSafeInteger(result.referenceTotal)
    || !Array.isArray(result.services) || result.services.length !== payload.serviceIds.length
    || !result.services.every((service) => service && typeof service.name === 'string'
      && Number.isSafeInteger(service.referencePrice))) {
    throw new PublicApiError('Chưa nhận được xác nhận đặt lịch. Vui lòng gửi lại cùng thông tin.', 201, 'INVALID_PUBLIC_RESPONSE');
  }
  return result as BookingResult;
}

export function formatGarageTime(value: string) {
  return new Intl.DateTimeFormat('vi-VN', { timeZone: 'Asia/Ho_Chi_Minh', dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}

export function garageDate(date = new Date()) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Ho_Chi_Minh', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(date);
  const value = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return `${value.year}-${value.month}-${value.day}`;
}
