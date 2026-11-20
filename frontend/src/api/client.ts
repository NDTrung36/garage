export type GarageUser = { id: string; username: string; displayName: string; role: 'ADMIN' | 'STAFF' };
type Csrf = { token: string; headerName: string };
let csrf: Csrf | null = null;
export type ApiFieldError = { field: string; code: string; message?: string };
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
    readonly retryAfterMs?: number,
    readonly fieldErrors: ApiFieldError[] = [],
  ) { super(message); }
}
export async function getCsrf(signal?: AbortSignal): Promise<Csrf> {
  const response = await fetch('/api/auth/csrf', { credentials: 'include', cache: 'no-store', signal });
  const value = await response.json().catch(() => ({})) as Partial<Csrf> & {message?:string};
  if (!response.ok || typeof value.token !== 'string' || typeof value.headerName !== 'string') {
    throw new ApiError(value.message ?? 'Không lấy được mã bảo mật. Vui lòng thử lại.', response.status, 'CSRF_UNAVAILABLE');
  }
  csrf = {token:value.token,headerName:value.headerName}; return csrf;
}
export async function api<T = void>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase();
  const headers = new Headers(init.headers);
  if (init.body) headers.set('Content-Type', 'application/json');
  if (!['GET','HEAD','OPTIONS'].includes(method)) {
    if (!csrf) await getCsrf(init.signal ?? undefined);
    if (csrf) headers.set(csrf.headerName, csrf.token);
  }
  const response = await fetch(`/api${path}`, { ...init, method, headers, credentials: 'include', cache: 'no-store' });
  init.signal?.throwIfAborted();
  if (response.status === 204) { if (path === '/auth/logout') csrf = null; return undefined as T; }
  const data: { message?: string; code?: string; fieldErrors?: unknown } = await response.json().catch(() => ({})) ?? {};
  init.signal?.throwIfAborted();
  if (!response.ok) {
    if (response.status === 403 && data.code === 'CSRF_INVALID') csrf = null;
    if (response.status === 401 && path !== '/auth/login') {
      csrf = null;
      window.dispatchEvent(new Event('garage:unauthorized'));
    }
    const fieldErrors = Array.isArray(data.fieldErrors) ? data.fieldErrors.filter(isFieldError) : [];
    throw new ApiError(data.message ?? data.code ?? `HTTP ${response.status}`, response.status, data.code,
      retryAfterMs(response.headers.get('Retry-After')), fieldErrors);
  }
  if (path === '/auth/login') await getCsrf();
  return data as T;
}

function isFieldError(value: unknown): value is ApiFieldError {
  return typeof value === 'object' && value !== null && 'field' in value && typeof value.field === 'string'
    && 'code' in value && typeof value.code === 'string'
    && (!('message' in value) || value.message === undefined || typeof value.message === 'string');
}

function retryAfterMs(value: string | null): number | undefined {
  if (!value) return undefined;
  const seconds = Number(value);
  if (Number.isFinite(seconds) && seconds >= 0) return seconds * 1000;
  const timestamp = Date.parse(value);
  return Number.isFinite(timestamp) ? Math.max(0, timestamp - Date.now()) : undefined;
}
