import { useEffect, useRef, useState, type FormEvent } from 'react';
import { PublicApiError } from '../../api/publicClient';
import { formatGarageTime } from '../booking/bookingApi';
import { lookupBookings, type LookupPage as LookupResult } from './lookupApi';

export function LookupPage({ onNavigate }: { onNavigate: (path: string) => void }) {
  const [mode, setMode] = useState<'phone' | 'code'>('code');
  const [page, setPage] = useState<LookupResult | null>(null);
  const [pageIndex, setPageIndex] = useState(0);
  const [criteria, setCriteria] = useState<{ phone?: string; bookingCode?: string }>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const activeRequest = useRef<AbortController | null>(null);

  useEffect(() => () => activeRequest.current?.abort(), []);

  function changeMode(next: typeof mode) {
    if (next === mode) return;
    activeRequest.current?.abort();
    setMode(next);
    setPage(null);
    setPageIndex(0);
    setCriteria({});
    setLoading(false);
    setError('');
  }

  async function search(event?: FormEvent<HTMLFormElement>, next = 0) {
    event?.preventDefault();
    const form = event?.currentTarget;
    let query = criteria;
    if (form) {
      setPage(null);
      const data = new FormData(form);
      query = mode === 'phone' ? { phone: String(data.get('phone') ?? '').trim() } : { bookingCode: String(data.get('bookingCode') ?? '').trim() };
      setCriteria(query);
    }
    activeRequest.current?.abort();
    const controller = new AbortController();
    activeRequest.current = controller;
    setError('');
    setLoading(true);
    try {
      const response = await lookupBookings(query, next, controller.signal);
      if (controller.signal.aborted) return;
      setPage(response);
      setPageIndex(next);
    } catch (cause) {
      if (controller.signal.aborted) return;
      setError(cause instanceof PublicApiError && cause.status === 429 ? 'Bạn tra cứu quá nhiều lần. Vui lòng đợi một chút rồi thử lại.' : cause instanceof Error ? cause.message : 'Không tra cứu được lịch hẹn.');
    } finally {
      if (!controller.signal.aborted) {
        activeRequest.current = null;
        setLoading(false);
      }
    }
  }

  return <main className="public-shell"><nav className="public-nav"><button className="text-button" onClick={() => onNavigate('/')}>GARAGE WORKSHOP</button><button onClick={() => onNavigate('/booking')}>Đặt lịch</button></nav>
    <section className="public-intro"><p className="eyebrow">TRA CỨU LỊCH SỬ</p><h1>Tìm lịch hẹn của bạn</h1><p className="muted">Dùng số điện thoại đã đặt lịch hoặc mã đặt lịch.</p></section>
    <form className="public-card lookup-form" onSubmit={(event) => void search(event, 0)}>
      <div className="segmented"><button type="button" className={mode === 'code' ? 'active' : ''} onClick={() => changeMode('code')}>Mã đặt lịch</button><button type="button" className={mode === 'phone' ? 'active' : ''} onClick={() => changeMode('phone')}>Số điện thoại</button></div>
      {mode === 'code' ? <label key="code">Mã đặt lịch<input name="bookingCode" required maxLength={24} placeholder="Ví dụ: BK-2345..." autoComplete="off" /></label> : <label key="phone">Số điện thoại<input name="phone" type="tel" required maxLength={40} placeholder="0912 345 678" autoComplete="tel" /></label>}
      {error && <p className="error" role="alert">{error}</p>}<button disabled={loading}>{loading ? 'Đang tra cứu…' : 'Tra cứu'}</button>
    </form>
    {page && <section className="lookup-results"><h2>Kết quả ({page.totalElements})</h2>{page.items.length === 0 ? <div className="public-card empty-state">Không tìm thấy lịch hẹn phù hợp.</div> : page.items.map((booking) => <article className="public-card booking-result" key={booking.bookingCode}><div className="result-heading"><div><small>MÃ ĐẶT LỊCH</small><h3>{booking.bookingCode}</h3></div><span className={`status status-${booking.status.toLowerCase()}`}>{statusName(booking.status)}</span></div><dl className="summary-list"><div><dt>Thời gian hẹn</dt><dd>{booking.startsAt ? formatGarageTime(booking.startsAt) : 'Chưa xếp khung giờ'}</dd></div><div><dt>Phương tiện</dt><dd>{booking.vehicleType === 'CAR' ? 'Ô tô' : 'Xe máy'} · {booking.maskedPlate}</dd></div><div><dt>Dịch vụ</dt><dd>{booking.services.map((service) => service.name).join(', ') || '—'}</dd></div><div><dt>Giá tham khảo</dt><dd>{booking.referenceTotal.toLocaleString('vi-VN')} ₫</dd></div></dl></article>)}{page.totalPages > 1 && <div className="pager"><button className="outline" disabled={pageIndex === 0 || loading} onClick={() => void search(undefined, pageIndex - 1)}>Trước</button><span>Trang {pageIndex + 1}/{page.totalPages}</span><button className="outline" disabled={pageIndex + 1 >= page.totalPages || loading} onClick={() => void search(undefined, pageIndex + 1)}>Tiếp</button></div>}</section>}
  </main>;
}

function statusName(status: string) {
  return ({ CONFIRMED: 'Đã xác nhận', CHECKED_IN: 'Đã tiếp nhận', COMPLETED: 'Đã hoàn tất', CANCELLED: 'Đã hủy', NO_SHOW: 'Không đến' } as Record<string, string>)[status] ?? status;
}
