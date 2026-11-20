import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ApiError } from '../../api/client';
import { sendStockCommand } from './api';

type Attempt = { path: string; payload: object; key: string; retryAt?: number; startedAt?: number };
const RETRY_WINDOW_MS = 24 * 60 * 60 * 1000;

export function useStockCommand(userId: string, onSuccess: () => Promise<void>, scope = 'inventory', onConflict?: (error: ApiError) => void) {
  const storageKey = scope === 'inventory' ? `garage.stock-command.${userId}` : `garage.stock-command.${scope}.${userId}`;
  const legacyKey = `garage.stock-command.repair.${userId}`;
  const [attempt, setAttempt] = useState<Attempt | null>(() => {
    const saved = loadAttempt(storageKey);
    if (saved || !scope.startsWith('repair:')) return saved;
    const legacy = loadAttempt(legacyKey);
    return legacy?.path.startsWith(`/staff/repair-orders/${scope.slice(7)}/part-`) ? legacy : null;
  });
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const retryAt = attempt?.retryAt ?? 0;
  const [now, setNow] = useState(Date.now());
  const request = useRef<AbortController | null>(null);

  useEffect(() => {
    if (!attempt || !scope.startsWith('repair:') || loadAttempt(legacyKey)?.key !== attempt.key) return;
    try {
      sessionStorage.setItem(storageKey, JSON.stringify(attempt));
      sessionStorage.removeItem(legacyKey);
    } catch { /* The original attempt is still available. */ }
  }, [attempt, legacyKey, scope, storageKey]);
  useEffect(() => () => request.current?.abort(), []);

  useEffect(() => {
    if (!attempt) return;
    const timer = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(timer);
  }, [attempt]);

  const send = useCallback(async (current: Attempt) => {
    if (request.current || Date.now() < (current.retryAt ?? 0) ||
        (current.startedAt !== undefined && Date.now() >= current.startedAt + RETRY_WINDOW_MS)) return;
    try { sessionStorage.setItem(storageKey, JSON.stringify(current)); }
    catch { setMessage('Không lưu được mã yêu cầu trong tab này. Kiểm tra bộ nhớ trình duyệt rồi thử lại.'); return; }
    const controller = new AbortController();
    request.current = controller;
    setAttempt(current);
    setBusy(true); setMessage('');
    try {
      const result = await sendStockCommand(current.path, current.payload, current.key,
        AbortSignal.any([controller.signal, AbortSignal.timeout(15_000)]));
      if (controller.signal.aborted) return;
      if (typeof result?.stockQuantity !== 'string') throw new Error('Chưa có kết quả lệnh kho.');
      sessionStorage.removeItem(storageKey);
      setAttempt(null);
      setMessage('Lệnh đã được ghi. Đang tải tồn và dữ liệu hiện tại…');
      try { await onSuccess(); if (!controller.signal.aborted) setMessage('Lệnh đã được ghi.'); }
      catch { if (!controller.signal.aborted) setMessage('Lệnh đã được ghi. Hãy làm mới để kiểm tra số dư và dữ liệu hiện tại.'); }
    } catch (cause) {
      if (controller.signal.aborted) return;
      if (cause instanceof ApiError && cause.status === 409) onConflict?.(cause);
      if (cause instanceof ApiError && [400, 404, 409, 422].includes(cause.status)
          && cause.code !== 'IDEMPOTENCY_CONFLICT' && cause.code !== 'CSRF_UNAVAILABLE') {
        sessionStorage.removeItem(storageKey);
        setAttempt(null);
        setMessage(cause.message);
      } else {
        const waitMs = cause instanceof ApiError ? cause.retryAfterMs ?? 0 : 0;
        const retry = { ...current, retryAt: Date.now() + waitMs };
        setAttempt(retry);
        try { sessionStorage.setItem(storageKey, JSON.stringify(retry)); } catch { /* Key and payload were already saved. */ }
        setMessage(cause instanceof ApiError && cause.status === 403
          ? cause.code === 'CSRF_INVALID' || cause.code === 'CSRF_UNAVAILABLE'
            ? 'Mã bảo mật đã hết hạn. Thử lại cùng yêu cầu sau khi kiểm tra dữ liệu.'
            : 'Tài khoản không có quyền thực hiện thao tác này.'
          : 'Chưa xác nhận kết quả lệnh kho. Gửi lại yêu cầu đã lưu để đối soát.');
      }
    } finally { request.current = null; if (!controller.signal.aborted) setBusy(false); }
  }, [onConflict, onSuccess, storageKey]);

  const execute = useCallback(async (path: Attempt['path'], payload: object) => {
    if (attempt) {
      if (attempt.path !== path || JSON.stringify(attempt.payload) !== JSON.stringify(payload)) {
        setMessage('Còn một lệnh chưa xác định kết quả. Hãy làm mới lịch sử và gửi lại lệnh cũ bằng cùng mã yêu cầu trước khi tạo lệnh mới.');
        return;
      }
      await send(attempt);
      return;
    }
    const next = { path, payload, key: crypto.randomUUID(), startedAt: Date.now() };
    await send(next);
  }, [attempt, send]);

  const retry = useCallback(() => { if (attempt && now >= retryAt) void send(attempt); }, [attempt, now, retryAt, send]);
  const waitSeconds = Math.ceil(Math.max(0, retryAt - now) / 1000);
  const expired = attempt?.startedAt !== undefined && now >= attempt.startedAt + RETRY_WINDOW_MS;
  const clearExpired = useCallback(() => {
    if (!expired || request.current) return;
    sessionStorage.removeItem(storageKey);
    setAttempt(null);
    setMessage('');
  }, [expired, storageKey]);
  return useMemo(() => ({ attempt, busy, message, execute, retry, retryBlocked: expired || waitSeconds > 0, waitSeconds, expired, clearExpired, setMessage }),
    [attempt, busy, message, execute, retry, waitSeconds, expired, clearExpired]);
}

function loadAttempt(key: string): Attempt | null {
  try {
    const raw = sessionStorage.getItem(key);
    if (!raw) return null;
    const value = JSON.parse(raw) as Attempt;
    if (isStockCommandPath(value.path) && value.key && value.payload) return value;
  } catch { /* A damaged local retry record should not block the inventory page. */ }
  return null;
}

function isStockCommandPath(path: unknown): path is string {
  return typeof path === 'string' && (
    path === '/admin/stock-receipts' || path === '/admin/stock-adjustments' ||
    /^\/staff\/repair-orders\/[1-9]\d*\/part-(?:issues|returns)$/.test(path)
  );
}
