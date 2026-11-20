import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError } from '../../api/client';
import { formatGarageTime, garageDate } from './bookingApi';
import {
  bookingStatuses, checkInBooking, emptyIntakeDraft, intakeFieldMessages, listStaffBookings,
  readIntakeDraft, repairOrderPath, type BookingStatus, type StaffBooking, type StaffBookingPage,
} from './intakeApi';
import { IntakeFields } from './WalkInForm';

type StatusFilter = BookingStatus | '';
type Filters = { date: string; status: StatusFilter; page: number };
type PageProps = { onNavigate: (path: string) => void };

function initialFilters(): Filters {
  const params = new URLSearchParams(location.search);
  const date = params.get('date') ?? '';
  const status = params.get('status') ?? 'CONFIRMED';
  const page = Number(params.get('page') ?? 0);
  return {
    date: /^\d{4}-\d{2}-\d{2}$/.test(date) ? date : garageDate(),
    status: status === '' || Object.hasOwn(bookingStatuses, status) ? status as StatusFilter : 'CONFIRMED',
    page: Number.isInteger(page) && page >= 0 && page <= 2_147_483_647 ? page : 0,
  };
}

function bookingTime(booking: StaffBooking) {
  const time = booking.source === 'ONLINE' ? booking.startsAt : booking.receivedAt;
  return time ? formatGarageTime(time) : '—';
}

export function IntakePage({ onNavigate }: PageProps) {
  const [filters, setFilters] = useState(initialFilters);
  const [list, setList] = useState<StaffBookingPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);
  const [selected, setSelected] = useState<StaffBooking | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError('');
    setList(null);
    void listStaffBookings(filters.date, filters.status, filters.page, controller.signal).then((page) => {
      if (!controller.signal.aborted) setList(page);
    }).catch(() => {
      if (!controller.signal.aborted) setError('Không tải được lịch tiếp nhận.');
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false);
    });
    return () => controller.abort();
  }, [filters, reload]);

  function updateFilters(next: Filters) {
    if (busy) return;
    const query = new URLSearchParams({ date: next.date, status: next.status, page: String(next.page) });
    history.replaceState(history.state, '', `/staff/bookings?${query}`);
    setSelected(null);
    setList(null);
    setLoading(true);
    setFilters(next);
  }

  return <div className="intake-page">
    <section className="intake-heading">
      <div><h1>Lịch tiếp nhận xe</h1><p className="muted">Chọn lịch hẹn để tiếp nhận xe hoặc mở phiếu đã tạo.</p></div>
      <button onClick={() => onNavigate('/staff/walk-ins')}>Tiếp nhận khách tại gara</button>
    </section>
    <div className="card intake-filters">
      <label>Ngày tại gara<input type="date" value={filters.date} disabled={busy}
        onChange={(event) => event.target.value && updateFilters({ ...filters, date: event.target.value, page: 0 })} /></label>
      <label>Trạng thái<select value={filters.status} disabled={busy} onChange={(event) =>
        updateFilters({ ...filters, status: event.target.value as StatusFilter, page: 0 })}>
        <option value="">Tất cả trạng thái</option>
        {Object.entries(bookingStatuses).map(([status, label]) => <option key={status} value={status}>{label}</option>)}
      </select></label>
      <button className="outline" disabled={loading || busy} onClick={() => setReload((value) => value + 1)}>Làm mới lịch</button>
    </div>
    {selected && <CheckInForm key={selected.id} initialBooking={selected} date={filters.date} onNavigate={onNavigate}
      onCancel={() => setSelected(null)} onBusy={setBusy} onRefreshList={() => setReload((value) => value + 1)} />}
    <div className="card table-wrap intake-list" aria-busy={loading}>
      {loading ? <p role="status" className="muted">Đang tải lịch tiếp nhận…</p>
        : error ? <div className="intake-feedback" role="alert">{error} <button className="outline" onClick={() => setReload((value) => value + 1)}>Thử tải lại</button></div>
        : !list?.items.length ? <p className="muted">Không có lịch phù hợp với ngày và trạng thái đã chọn.</p>
        : <table>
          <thead><tr><th>Lịch hẹn</th><th>Khách hàng</th><th>Xe</th><th>Dịch vụ yêu cầu</th><th>Trạng thái</th><th>Thao tác</th></tr></thead>
          <tbody>{list.items.map((booking) => (
            <tr key={booking.id}>
              <td><b>{booking.code}</b><small>{bookingTime(booking)} · {booking.source === 'ONLINE' ? 'Đặt lịch' : 'Tại gara'}</small></td>
              <td>{booking.contactName}<small>{booking.contactPhone}</small></td>
              <td><b>{booking.plate}</b><small>{booking.vehicleType === 'CAR' ? 'Ô tô' : 'Xe máy'} {booking.brand} {booking.model}</small></td>
              <td>{booking.requestedServices.map((service) => service.name).join(', ') || '—'}</td>
              <td><span className={`status status-${booking.status.toLowerCase()}`}>{bookingStatuses[booking.status]}</span></td>
              <td>{booking.status === 'CONFIRMED' ? <button type="button" className="outline" disabled={busy}
                onClick={() => setSelected(booking)}>Tiếp nhận</button>
                : (booking.status === 'CHECKED_IN' || booking.status === 'COMPLETED') && booking.repairOrderId
                  ? <button type="button" className="outline" onClick={() => onNavigate(repairOrderPath(booking.repairOrderId!))}>Mở phiếu</button> : '—'}</td>
            </tr>
          ))}</tbody>
        </table>}
      {list && (list.totalPages > 1 || filters.page > 0) && <div className="pager">
        <span>{list.totalElements} lịch</span>
        <button className="outline" disabled={busy || filters.page === 0}
          onClick={() => updateFilters({ ...filters, page: filters.page - 1 })}>Trước</button>
        <button className="outline" disabled={busy || filters.page + 1 >= list.totalPages}
          onClick={() => updateFilters({ ...filters, page: filters.page + 1 })}>Tiếp</button>
      </div>}
    </div>
  </div>;
}

function CheckInForm({ initialBooking, date, onNavigate, onCancel, onBusy, onRefreshList }: PageProps & {
  initialBooking: StaffBooking; date: string; onCancel: () => void; onBusy: (busy: boolean) => void; onRefreshList: () => void;
}) {
  const [booking, setBooking] = useState<StaffBooking | null>(initialBooking);
  const [draft, setDraft] = useState(emptyIntakeDraft);
  const [fields, setFields] = useState<Record<string, string>>({});
  const [message, setMessage] = useState('');
  const [needsRefresh, setNeedsRefresh] = useState(false);
  const [review, setReview] = useState(false);
  const [busy, setBusy] = useState(false);
  const request = useRef<AbortController | null>(null);
  const form = useRef<HTMLFormElement>(null);

  useEffect(() => {
    form.current?.focus();
    return () => request.current?.abort();
  }, []);

  async function refreshBooking(signal: AbortSignal) {
    setNeedsRefresh(true);
    try {
      let found: StaffBooking | undefined;
      for (let page = 0; !found; page++) {
        const result = await listStaffBookings(date, '', page, signal);
        found = result.items.find((item) => item.id === initialBooking.id);
        if (page + 1 >= result.totalPages) break;
      }
      if (signal.aborted) return;
      setBooking(found ?? null);
      setNeedsRefresh(false);
      setReview(true);
      onRefreshList();
      setMessage(found ? 'Lịch đã thay đổi. Đối chiếu trạng thái và thông tin mới trước khi tiếp tục.'
        : 'Không còn thấy lịch trong ngày này. Hãy kiểm tra lại ngày hẹn trước khi tiếp nhận.');
    } catch {
      if (!signal.aborted) setMessage('Chưa tải được lịch mới. Dữ liệu nhận xe vẫn được giữ.');
    }
  }

  async function run(action: (signal: AbortSignal) => Promise<void>) {
    if (request.current) return;
    const controller = new AbortController();
    request.current = controller;
    setBusy(true);
    onBusy(true);
    try { await action(controller.signal); }
    finally {
      request.current = null;
      if (!controller.signal.aborted) {
        setBusy(false);
        onBusy(false);
      }
    }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!booking || booking.status !== 'CONFIRMED' || needsRefresh || request.current) return;
    const { details, errors } = readIntakeDraft(draft);
    setFields(errors);
    setMessage('');
    if (Object.keys(errors).length) return;
    void run(async (signal) => {
      try {
        const result = await checkInBooking(booking.id, booking.version, details, signal);
        if (!signal.aborted) onNavigate(repairOrderPath(result.repairOrderId));
      } catch (cause) {
        if (signal.aborted) return;
        if (cause instanceof ApiError && cause.status === 409 &&
            (cause.code === 'STALE_VERSION' || cause.code === 'BOOKING_STATE_CONFLICT')) {
          await refreshBooking(signal);
        } else {
          setFields(intakeFieldMessages(cause));
          setMessage(cause instanceof ApiError ? cause.message : 'Chưa nhận được kết quả. Hãy thử lại trên cùng lịch hẹn.');
        }
      }
    });
  }

  const existingOrder = booking && (booking.status === 'CHECKED_IN' || booking.status === 'COMPLETED') ? booking.repairOrderId : null;
  return <form ref={form} tabIndex={-1} className="card intake-card booking-form intake-check-in" onSubmit={submit} aria-busy={busy}>
    <h2>Tiếp nhận lịch {initialBooking.code}</h2>
    {booking && <p className="muted">{booking.contactName} · {booking.plate} · {bookingTime(booking)} · {bookingStatuses[booking.status]}
      {booking.requestedServices.length > 0 && <> · {booking.requestedServices.map((service) => service.name).join(', ')}</>}</p>}
    <fieldset className="intake-edit" disabled={busy || booking?.status !== 'CONFIRMED'}>
      <legend>Thông tin nhận xe</legend>
      <IntakeFields draft={draft} onChange={setDraft} errors={fields} />
    </fieldset>
    {message && <p className="intake-feedback" role="alert">{message}</p>}
    <div className="intake-actions">
      <button type="button" className="outline" disabled={busy} onClick={onCancel}>Đóng</button>
      {needsRefresh ? <button type="button" disabled={busy} onClick={() => void run(refreshBooking)}>Tải lại lịch để đối chiếu</button>
        : existingOrder ? <button type="button" onClick={() => onNavigate(repairOrderPath(existingOrder))}>Mở phiếu đã có</button>
        : booking?.status === 'CONFIRMED' && <button type="submit" disabled={busy}>
          {busy ? 'Đang xử lý…' : review ? 'Xác nhận tiếp nhận sau đối chiếu' : 'Xác nhận tiếp nhận'}
        </button>}
    </div>
  </form>;
}
