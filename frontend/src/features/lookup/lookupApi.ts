import { publicApi } from '../../api/publicClient';

export type LookupService = { name: string; referencePrice: number };
export type BookingLookup = {
  bookingCode: string; createdAt: string; startsAt: string | null; endsAt: string | null;
  vehicleType: 'CAR' | 'MOTORBIKE'; maskedPlate: string; status: string;
  services: LookupService[]; referenceTotal: number;
};
export type LookupPage = { items: BookingLookup[]; page: number; size: number; totalElements: number; totalPages: number };
export const lookupBookings = (criteria: { phone?: string; bookingCode?: string }, page = 0, signal?: AbortSignal) =>
  publicApi<LookupPage>('/public/lookups', {
    method: 'POST',
    body: JSON.stringify({ ...criteria, page, size: 20 }),
    signal,
  });
