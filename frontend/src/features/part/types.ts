export type Part = {
  id: string;
  code: string;
  name: string;
  unit: string;
  vehicleType: 'CAR' | 'MOTORBIKE' | 'BOTH';
  referencePrice: number;
  stockQuantity: string;
  active: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
};

export type PartPageData = {
  items: Part[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type PartPayload = {
  code?: string;
  name?: string;
  unit?: string;
  vehicleType?: Part['vehicleType'];
  referencePrice?: string;
  active?: boolean;
  expectedVersion?: number;
};

export type PartFilters = { page: number; size: number; q: string; active: string; vehicleType: string };
