export type GarageUser = { id: string; username: string; displayName: string; role: 'ADMIN' | 'STAFF' };
type Csrf = { token: string; headerName: string };
let csrf: Csrf | null = null;
export class ApiError extends Error { constructor(message: string, readonly status: number, readonly code?: string) { super(message); } }
export async function getCsrf(): Promise<Csrf> {
  const response = await fetch('/api/auth/csrf', { credentials: 'include', cache: 'no-store' });
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
    if (!csrf) await getCsrf();
    if (csrf) headers.set(csrf.headerName, csrf.token);
  }
  const response = await fetch(`/api${path}`, { ...init, method, headers, credentials: 'include', cache: 'no-store' });
  if (response.status === 204) { if (path === '/auth/logout') csrf = null; return undefined as T; }
  const data = await response.json().catch(() => ({})) as { message?: string; code?: string };
  if (!response.ok) {
    if (response.status === 403 && data.code === 'CSRF_INVALID') csrf = null;
    if (response.status === 401 && path !== '/auth/login') window.dispatchEvent(new Event('garage:unauthorized'));
    throw new ApiError(data.message ?? data.code ?? `HTTP ${response.status}`,response.status,data.code);
  }
  if (path === '/auth/login') await getCsrf();
  return data as T;
}
