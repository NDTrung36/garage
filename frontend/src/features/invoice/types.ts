export type InvoicePaymentStatus = 'UNPAID' | 'PAID';
export type InvoicePaymentMethod = 'CASH' | 'BANK_TRANSFER' | 'NO_CHARGE';
export type InvoiceLineType = 'LABOR' | 'PART';

export type InvoiceSummary = {
  id: string;
  code: string;
  totalAmount: number;
  paymentStatus: InvoicePaymentStatus;
  issuedAt: string;
};

export type InvoiceListItem = InvoiceSummary & { repairOrderId: string };
export type InvoicePageData = {
  items: InvoiceListItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type InvoiceLine = {
  id: string;
  type: InvoiceLineType;
  description: string;
  unit: string;
  quantity: string;
  unitPrice: number;
  lineTotal: number;
};

export type InvoiceDetail = InvoiceListItem & {
  version: number;
  paidAmount: number | null;
  paymentMethod: InvoicePaymentMethod | null;
  paidBy: string | null;
  paidByDisplayName: string | null;
  paidAt: string | null;
  items: InvoiceLine[];
};

export type InvoicePaymentResponse = {
  id: string;
  paymentStatus: InvoicePaymentStatus;
  paidAmount: number | null;
  paymentMethod: InvoicePaymentMethod | null;
  paidBy: string | null;
  paidAt: string | null;
  version: number;
};
