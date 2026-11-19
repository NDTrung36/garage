export class PublicApiError extends Error {
  constructor(message: string, readonly status: number, readonly code?: string) { super(message); }
}

export async function publicApi<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body) headers.set('Content-Type', 'application/json');
  const response = await fetch(`/api${path}`, {
    ...init,
    headers,
    credentials: 'omit',
    cache: 'no-store',
  });
  if (!response.ok) {
    const error = await response.json().catch(() => null) as { message?: string; code?: string } | null;
    throw new PublicApiError(error?.message ?? 'Không thể hoàn tất yêu cầu.', response.status, error?.code);
  }
  try {
    return await response.json() as T;
  } catch (cause) {
    if (init.signal?.aborted) throw cause;
    throw new PublicApiError('Không đọc được phản hồi. Vui lòng thử lại.', response.status, 'INVALID_PUBLIC_RESPONSE');
  }
}
