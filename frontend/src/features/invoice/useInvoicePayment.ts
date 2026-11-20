import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '../../api/client';
import { markInvoicePaid, resetInvoicePayment } from './api';
import type { InvoicePaymentMethod, InvoicePaymentResponse } from './types';

type PaymentAction =
  | { kind: 'mark-paid'; key: string; expectedVersion: number; paymentMethod: InvoicePaymentMethod; retryAt?: number }
  | { kind: 'reset-payment'; key: string; expectedVersion: number; reason: string; retryAt?: number };

const isPaymentAction = (value: unknown): value is PaymentAction => {
  if (!value || typeof value !== 'object') return false;
  const action = value as Partial<PaymentAction>;
  if (typeof action.key !== 'string' || !/^[0-9a-f-]{36}$/i.test(action.key) || typeof action.expectedVersion !== 'number') return false;
  if (action.kind === 'mark-paid') return ['CASH', 'BANK_TRANSFER', 'NO_CHARGE'].includes(action.paymentMethod ?? '');
  return action.kind === 'reset-payment' && typeof action.reason === 'string';
};

export function useInvoicePayment(actorId: string, invoiceId: string, canReset: boolean, refresh: () => Promise<unknown>) {
  const storageKey = `garage:billing:invoice-command:${actorId}:${invoiceId}`;
  const [pending, setPending] = useState<PaymentAction | null>(() => {
    try {
      const raw = sessionStorage.getItem(storageKey);
      if (!raw) return null;
      const action: unknown = JSON.parse(raw);
      return isPaymentAction(action) && (action.kind === 'mark-paid' || canReset) ? action : null;
    } catch { return null; }
  });
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [retryAt, setRetryAt] = useState(0);
  const busyRef = useRef(false);

  useEffect(() => {
    try {
      const raw = sessionStorage.getItem(storageKey);
      if (!raw) { setPending(null); return; }
      const action: unknown = JSON.parse(raw);
      if (isPaymentAction(action) && (action.kind === 'mark-paid' || canReset)) {
        setPending(action);
        setRetryAt(action.retryAt ?? 0);
        setMessage('Có yêu cầu trước chưa nhận được phản hồi cuối cùng. Hãy gửi lại yêu cầu đã lưu để kiểm tra kết quả.');
      } else {
        sessionStorage.removeItem(storageKey);
        setPending(null);
        setRetryAt(0);
        setMessage('');
      }
    } catch {
      setPending(null);
      setMessage('Không đọc được yêu cầu thanh toán đã lưu trong phiên này. Tải lại trang trước khi thao tác.');
    }
  }, [canReset, storageKey]);

  useEffect(() => {
    if (retryAt <= Date.now()) return;
    const timer = window.setInterval(() => {
      if (Date.now() >= retryAt) { setRetryAt(0); window.clearInterval(timer); }
    }, 250);
    return () => window.clearInterval(timer);
  }, [retryAt]);

  const send = useCallback(async (action: PaymentAction) => {
    if (busyRef.current || (action.kind === 'reset-payment' && !canReset)) return;
    if ((action.retryAt ?? 0) > Date.now()) {
      setMessage(`Vui lòng thử lại sau ${Math.max(1, Math.ceil(((action.retryAt ?? 0) - Date.now()) / 1000))} giây.`);
      return;
    }
    busyRef.current = true;
    setBusy(true);
    setMessage('');
    try {
      const response: InvoicePaymentResponse = action.kind === 'mark-paid'
        ? await markInvoicePaid(invoiceId, action.key, action.expectedVersion, action.paymentMethod)
        : await resetInvoicePayment(invoiceId, action.key, action.expectedVersion, action.reason);
      if (response.id !== invoiceId || !response.paymentStatus || typeof response.version !== 'number') {
        setMessage('Chưa xác nhận được phản hồi. Gửi lại yêu cầu đã lưu để kiểm tra, không tạo yêu cầu mới.');
        return;
      }
      sessionStorage.removeItem(storageKey);
      setPending(null);
      setRetryAt(0);
      setMessage('Đã nhận phản hồi. Đang tải lại trạng thái hóa đơn…');
      try {
        await refresh();
        setMessage('Thông tin hóa đơn đã được cập nhật.');
      } catch {
        setMessage('Đã ghi nhận thao tác. Hãy tải lại để xem trạng thái hóa đơn.');
      }
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 409) {
        sessionStorage.removeItem(storageKey);
        setPending(null);
        setRetryAt(0);
        setMessage('Hóa đơn đã thay đổi. Đang tải trạng thái mới để đối chiếu.');
        await refresh().catch(() => {});
      } else if (cause instanceof ApiError && cause.status >= 400 && cause.status < 500 && cause.status !== 401 && cause.code !== 'CSRF_INVALID') {
        sessionStorage.removeItem(storageKey);
        setPending(null);
        setMessage(cause.message || 'Yêu cầu không hợp lệ.');
      } else {
        const next = cause instanceof ApiError && cause.retryAfterMs !== undefined
          ? { ...action, retryAt: Date.now() + cause.retryAfterMs } as PaymentAction : action;
        try {
          sessionStorage.setItem(storageKey, JSON.stringify(next));
          setPending(next);
          setRetryAt(next.retryAt ?? 0);
          setMessage(cause instanceof ApiError && cause.retryAfterMs !== undefined
            ? 'Máy chủ yêu cầu chờ trước khi thử lại. Yêu cầu đã được giữ để gửi lại cùng mã.'
            : 'Chưa nhận được phản hồi cuối cùng. Yêu cầu đã được giữ để gửi lại an toàn.');
        } catch {
          setMessage('Mất phản hồi và không thể lưu yêu cầu trong phiên. Không gửi thao tác mới; hãy tải lại hóa đơn để kiểm tra.');
        }
      }
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }, [canReset, invoiceId, refresh, storageKey]);

  const start = useCallback((action: Omit<Extract<PaymentAction, { kind: 'mark-paid' }>, 'key' | 'retryAt'> | Omit<Extract<PaymentAction, { kind: 'reset-payment' }>, 'key' | 'retryAt'>) => {
    if (busyRef.current || (action.kind === 'reset-payment' && !canReset)) return;
    if (pending) {
      setMessage('Hãy gửi lại yêu cầu đang lưu trước khi tạo thao tác mới.');
      return;
    }
    const next = { ...action, key: crypto.randomUUID() } as PaymentAction;
    try {
      sessionStorage.setItem(storageKey, JSON.stringify(next));
      setPending(next);
      void send(next);
    } catch {
      setMessage('Không thể lưu mã yêu cầu trong phiên. Thao tác chưa được gửi.');
    }
  }, [canReset, pending, send, storageKey]);

  const retry = useCallback(() => { if (pending) void send(pending); }, [pending, send]);
  return { pending: pending?.kind === 'reset-payment' && !canReset ? null : pending, busy, message, retryAt, start, retry };
}
