import { useEffect, useRef, useState, type FormEvent } from 'react';
import { PublicApiError } from '../../api/publicClient';
import { createBooking, formatGarageTime, garageDate, listServices, listSlots, type BookingPayload, type BookingResult, type GarageService, type OpenSlot, type VehicleType } from './bookingApi';

type SlotQuery = { vehicleType: VehicleType; date: string };
type Availability = { query: SlotQuery; services: GarageService[]; slots: OpenSlot[]; error?: string };

export function BookingPage({ onNavigate }: { onNavigate: (path: string) => void }) {
  const [query, setQuery] = useState<SlotQuery>(() => ({ vehicleType: 'MOTORBIKE', date: garageDate() }));
  const [availability, setAvailability] = useState<Availability | null>(null);
  const [selectedServices, setSelectedServices] = useState<string[]>([]);
  const [slotId, setSlotId] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState<BookingResult | null>(null);
  const [copied, setCopied] = useState(false);
  const attempt = useRef<{ key: string; body: string } | null>(null);
  const { vehicleType, date } = query;
  const current = availability?.query === query ? availability : null;
  const services = current?.services ?? [];
  const slots = current?.slots ?? [];
  const loading = Boolean(date && !current);
  const availableIds = new Set(services.map((service) => service.id));
  const selectionChanged = current && !current.error && selectedServices.some((id) => !availableIds.has(id));
  const canSubmit = !submitting && !selectionChanged && slots.some((slot) => slot.id === slotId)
    && selectedServices.length > 0;

  useEffect(() => {
    if (!query.date) return;
    const controller = new AbortController();
    const { signal } = controller;
    Promise.all([listServices(query.vehicleType, signal), listSlots(query.date, query.vehicleType, signal)])
      .then(([services, slots]) => {
        if (signal.aborted) return;
        setAvailability({ query, services, slots });
      })
      .catch((cause: unknown) => {
        if (signal.aborted) return;
        setAvailability({ query, services: [], slots: [],
          error: cause instanceof Error ? cause.message : 'Không tải được dịch vụ và khung giờ.' });
      });
    return () => controller.abort();
  }, [query]);

  function changeQuery(next: SlotQuery) {
    setSlotId('');
    setError('');
    if (next.vehicleType !== vehicleType) setSelectedServices([]);
    setQuery(next);
  }

  function reloadAvailability() {
    setSlotId('');
    setQuery((current) => ({ ...current }));
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canSubmit) return;
    setError('');
    const form = new FormData(event.currentTarget);
    const payload: BookingPayload = {
      contact: { fullName: String(form.get('fullName') ?? ''), phone: String(form.get('phone') ?? '') },
      vehicle: { type: vehicleType, plateNumber: String(form.get('plateNumber') ?? ''),
        brand: String(form.get('brand') ?? '') || undefined, model: String(form.get('model') ?? '') || undefined },
      serviceIds: [...selectedServices].sort(), slotId,
      note: String(form.get('note') ?? '') || undefined,
    };
    const body = JSON.stringify(payload);
    if (!attempt.current || attempt.current.body !== body) attempt.current = { key: crypto.randomUUID(), body };
    setSubmitting(true);
    try {
      const booked = await createBooking(payload, attempt.current.key);
      setResult(booked); attempt.current = null;
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Không đặt được lịch. Vui lòng thử lại.');
      if (cause instanceof PublicApiError
        && ['SLOT_FULL', 'SLOT_UNAVAILABLE', 'SERVICE_NOT_AVAILABLE'].includes(cause.code ?? '')) {
        reloadAvailability();
      }
    } finally { setSubmitting(false); }
  }

  if (result) return <main className="public-shell"><nav className="public-nav"><b>GARAGE WORKSHOP</b><button className="outline" onClick={() => onNavigate('/lookup')}>Tra cứu lịch</button></nav><section className="public-card success-card"><div className="success-icon">✓</div><p className="eyebrow">ĐẶT LỊCH THÀNH CÔNG</p><h1>Mã đặt lịch của bạn</h1><p className="booking-code">{result.bookingCode}</p><p>Hãy lưu mã này để tra cứu lịch hẹn.</p><button className="outline" onClick={() => { void navigator.clipboard.writeText(result.bookingCode).then(() => setCopied(true)).catch(() => setCopied(false)); }}>{copied ? 'Đã sao chép mã' : 'Sao chép mã'}</button><dl className="summary-list"><div><dt>Thời gian</dt><dd>{formatGarageTime(result.startsAt)}</dd></div><div><dt>Loại xe</dt><dd>{result.vehicleType === 'CAR' ? 'Ô tô' : 'Xe máy'}</dd></div><div><dt>Dịch vụ</dt><dd>{result.services.map((service) => service.name).join(', ')}</dd></div><div><dt>Giá tham khảo</dt><dd>{result.referenceTotal.toLocaleString('vi-VN')} ₫</dd></div></dl><button onClick={() => onNavigate('/lookup')}>Tra cứu lịch hẹn</button></section></main>;

  return <main className="public-shell">
    <nav className="public-nav"><button className="text-button" onClick={() => onNavigate('/')}>GARAGE WORKSHOP</button><button className="outline" onClick={() => onNavigate('/lookup')}>Tra cứu lịch</button></nav>
    <section className="public-intro"><p className="eyebrow">ĐẶT LỊCH SỬA XE</p><h1>Chọn lịch phù hợp với bạn</h1><p className="muted">Không cần tạo tài khoản. Chúng tôi dùng số điện thoại để lưu và tra cứu lịch hẹn.</p></section>
    <form className="public-card booking-form" onSubmit={submit}>
      <h2>1. Chọn xe, dịch vụ và thời gian</h2>
      <div className="form-grid"><label>Loại xe<select value={vehicleType} disabled={submitting} onChange={(e) => changeQuery({ ...query, vehicleType: e.target.value as VehicleType })}><option value="MOTORBIKE">Xe máy</option><option value="CAR">Ô tô</option></select></label><label>Ngày hẹn<input type="date" value={date} min={garageDate()} disabled={submitting} onChange={(e) => changeQuery({ ...query, date: e.target.value })} required /></label></div>
      {current?.error ? <div>
        <p className="error" role="alert">{current.error}</p>
        <button className="outline" type="button" onClick={reloadAvailability}>Tải lại dịch vụ và khung giờ</button>
      </div> : date && <>
      <fieldset><legend>Dịch vụ cần làm</legend>{loading ? <p>Đang tải dịch vụ…</p> : services.length === 0 ? <p>Chưa có dịch vụ phù hợp.</p> : <div className="service-options">{services.map((service) => <label className="choice-card" key={service.id}><input type="checkbox" checked={selectedServices.includes(service.id)} onChange={(e) => setSelectedServices((current) => e.target.checked ? [...current, service.id] : current.filter((id) => id !== service.id))} /><span><b>{service.name}</b><small>{service.price.toLocaleString('vi-VN')} ₫ · giá tham khảo</small></span></label>)}</div>}</fieldset>
        {selectionChanged && <div>
          <p className="error" role="alert">Một số dịch vụ bạn đã chọn hiện không còn nhận đặt lịch.</p>
          <button className="outline" type="button"
            onClick={() => setSelectedServices((ids) => ids.filter((id) => availableIds.has(id)))}>
            Xác nhận lựa chọn hiện có
          </button>
        </div>}
      <fieldset><legend>Khung giờ</legend>{loading ? <p>Đang tải khung giờ…</p> : slots.length === 0 ? <p>Ngày này chưa có khung giờ trống. Hãy chọn ngày khác.</p> : <div className="slot-options">{slots.map((slot) => <label className={`slot-choice ${slotId === slot.id ? 'selected' : ''}`} key={slot.id}><input type="radio" name="slot" checked={slotId === slot.id} onChange={() => setSlotId(slot.id)} /><span>{formatGarageTime(slot.startsAt)}</span><small>Còn {slot.remainingCapacity} chỗ</small></label>)}</div>}</fieldset>
      </>}
      <h2>2. Thông tin liên hệ và phương tiện</h2>
      <div className="form-grid"><label>Họ tên<input name="fullName" required minLength={2} maxLength={100} autoComplete="name" /></label><label>Số điện thoại<input name="phone" type="tel" required maxLength={40} autoComplete="tel" placeholder="0912 345 678" /></label><label>Biển số xe<input name="plateNumber" required maxLength={40} placeholder="Ví dụ: 59A1-123.45" /></label><label>Hãng xe<input name="brand" maxLength={100} /></label><label>Dòng xe<input name="model" maxLength={100} /></label></div>
      <label>Ghi chú<textarea name="note" maxLength={1000} rows={3} placeholder="Mô tả ngắn tình trạng xe (không bắt buộc)" /></label>
      {error && <p className="error" role="alert">{error}</p>}
      <div className="booking-actions"><span className="muted">Giá thực tế được xác nhận sau khi garage kiểm tra xe.</span><button disabled={!canSubmit}>{submitting ? 'Đang gửi…' : 'Xác nhận đặt lịch'}</button></div>
    </form>
  </main>;
}
