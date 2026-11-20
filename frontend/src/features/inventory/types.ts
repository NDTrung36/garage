import type { Part } from '../part/types';

export type MovementType = 'RECEIPT' | 'ADJUSTMENT' | 'ISSUE' | 'RETURN';
export type StockMovement = {
  id: string;
  partId: string;
  partCode: string;
  movementType: MovementType;
  quantityDelta: string;
  quantityBefore: string;
  quantityAfter: string;
  reason: string;
  note: string | null;
  actorId: string;
  createdAt: string;
  repairItemId: string | null;
  originalIssueId: string | null;
  actorDisplayName: string | null;
  repairOrderId: string | null;
};

export type MovementPage = { items: StockMovement[]; page: number; size: number; totalElements: number; totalPages: number };
export type StockFilter = { page: number; partId: string; movementType: string };
export type StockCommand = { partId: string; quantity?: string; quantityDelta?: string; reason: string; note?: string };
export type PartOption = Pick<Part, 'id' | 'code' | 'name' | 'unit' | 'stockQuantity' | 'active'>;

export const movementLabels: Record<MovementType, string> = {
  RECEIPT: 'Nhập kho',
  ADJUSTMENT: 'Điều chỉnh',
  ISSUE: 'Xuất cho phiếu',
  RETURN: 'Hoàn phụ tùng',
};
