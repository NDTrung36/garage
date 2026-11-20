import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError } from '../../api/client';
import { listServices, type GarageService, type VehicleType } from './bookingApi';
import {
  createWalkIn, emptyIntakeDraft, intakeFieldMessages, readIntakeDraft, repairOrderPath,
  type IntakeDraft, type WalkInPayload,
} from './intakeApi';

type Props = { userId: string; onNavigate: (path: string) => void };
type Attempt = { key: string; payload: WalkInPayload; startedAt: number; retryAt: number };
const retryWindowMs = 24 * 60 * 60 * 1000;
const currency = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND' });

function loadAttempt(key: string): Attempt | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(key) ?? 'null') as Attempt | null;
    if (value?.key && value.payload?.contact && value.payload?.vehicle && Array.isArray(value.payload.serviceIds)
      && Number.isFinite(value.startedAt) && Number.isFinite(value.retryAt)) return value;
  } catch { /* An unreadable record cannot be replayed. */ }
  return null;
}

export function WalkInForm({ userId, onNavigate }: Props) {
  const storageKey = `garage.walk-in.${userId}`;
  const [attempt, setAttempt] = useState(() => loadAttempt(storageKey));
  const saved = attempt?.payload;
  const [contact, setContact] = useState(saved?.contact ?? { fullName: '', phone: '' });
  const [vehicle, setVehicle] = useState(() => ({
    type: saved?.vehicle.type ?? 'CAR', plateNumber: saved?.vehicle.plateNumber ?? '',
    brand: saved?.vehicle.brand ?? '', model: saved?.vehicle.model ?? '',
  }));
  const [note, setNote] = useState(saved?.note ?? '');
  const [details, setDetails] = useState<IntakeDraft>(() => saved ? {
    odometerKm: saved.odometerKm?.toString() ?? '',
    conditionNotes: saved.conditionNotes,
    internalNotes: saved.internalNotes,
  } : emptyIntakeDraft);
  const [selected, setSelected] = useState(saved?.serviceIds ?? []);
  const [services, setServices] = useState<GarageService[]>([]);
  const [serviceLoading, setServiceLoading] = useState(true);
  const [serviceError, setServiceError] = useState('');
  const [reload, setReload] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [fields, setFields] = useState<Record<string, string>>({});
  const [now, setNow] = useState(Date.now);
  const activeRequest = useRef<AbortController | null>(null);
  const pending = attempt !== null;

  useEffect(() => {
    const controller = new AbortController();
    setServiceLoading(true);
    setServiceError('');
    void listServices(vehicle.type, controller.signal).then((items) => {
      if (controller.signal.aborted) return;
      setServices(items);
      if (!pending) setSelected((previous) => previous.filter((id) => items.some((item) => item.id === id)));
    }).catch(() => {
      if (!controller.signal.aborted) setServiceError('Không tải được danh sách dịch vụ.');
    }).finally(() => {
      if (!controller.signal.aborted) setServiceLoading(false);
    });
    return () => controller.abort();
  }, [vehicle.type, reload, pending]);

  useEffect(() => () => activeRequest.current?.abort(), []);
  useEffect(() => {
    if (!attempt) return;
    setNow(Date.now());
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [attempt]);

  function clearAttempt() {
    sessionStorage.removeItem(storageKey);
    setAttempt(null);
  }

  async function send(current: Attempt) {
    if (activeRequest.current || Date.now() < current.retryAt || Date.now() >= current.startedAt + retryWindowMs) return;
    try {
      sessionStorage.setItem(storageKey, JSON.stringify(current));
    } catch {
      setError('Không lưu được mã yêu cầu trong phiên này. Kiểm tra bộ nhớ của trình duyệt rồi thử lại.');
      return;
    }
    const controller = new AbortController();
    activeRequest.current = controller;
    setAttempt(current);
    setBusy(true);
    setError('');
    setFields({});
    try {
      const result = await createWalkIn(current.payload, current.key, controller.signal);
      if (!controller.signal.aborted) {
        clearAttempt();
        onNavigate(repairOrderPath(result.repairOrderId));
      }
    } catch (cause) {
      if (controller.signal.aborted) return;
      if (cause instanceof ApiError && (cause.status === 400 || cause.status === 422)
          && cause.code !== 'CSRF_UNAVAILABLE') {
        clearAttempt();
        setError(cause.message);
        setFields(intakeFieldMessages(cause));
        if (cause.code === 'SERVICE_NOT_AVAILABLE') setReload((value) => value + 1);
      } else {
        const retryAt = Date.now() + (cause instanceof ApiError ? cause.retryAfterMs ?? 0 : 0);
        const retry = { ...current, retryAt };
        setAttempt(retry);
        try { sessionStorage.setItem(storageKey, JSON.stringify(retry)); } catch { /* Original key and payload are already saved. */ }
        setError(cause instanceof ApiError ? cause.message : 'Kết nối bị gián đoạn khi gửi yêu cầu.');
      }
    } finally {
      activeRequest.current = null;
      if (!controller.signal.aborted) setBusy(false);
    }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (activeRequest.current || attempt || serviceLoading || serviceError) return;
    const { details: intake, errors } = readIntakeDraft(details);
    if (contact.fullName.trim().length < 2 || contact.fullName.length > 100) errors['contact.fullName'] = 'Nhập họ tên từ 2 đến 100 ký tự.';
    if (!/^(?:0|\+84)[\d\s().-]+$/.test(contact.phone.trim())) errors['contact.phone'] = 'Nhập số di động bắt đầu bằng 0 hoặc +84.';
    if (!/^[a-z\d\s.-]+$/i.test(vehicle.plateNumber.trim()) || !/[a-z\d]/i.test(vehicle.plateNumber)
        || vehicle.plateNumber.trim().length > 20) errors['vehicle.plateNumber'] = 'Nhập biển số hợp lệ, tối đa 20 ký tự.';
    if (selected.length === 0 || selected.length > 50) errors.serviceIds = 'Chọn từ 1 đến 50 dịch vụ.';
    setFields(errors);
    setError('');
    if (Object.keys(errors).length) return;
    const payload = { ...intake, contact, vehicle, note, serviceIds: selected };
    void send({ key: crypto.randomUUID(), payload, startedAt: Date.now(), retryAt: 0 });
  }

  const expired = attempt !== null && now >= attempt.startedAt + retryWindowMs;
  const wait = Math.ceil(Math.max(0, (attempt?.retryAt ?? 0) - now) / 1000);
  return <div className="intake-page">
    <section className="intake-heading">
      <div><h1>Tiếp nhận khách tại gara</h1><p className="muted">Nhập thông tin khách, xe và dịch vụ cần thực hiện.</p></div>
      <button className="outline" onClick={() => onNavigate('/staff/bookings')}>Xem lịch tiếp nhận</button>
    </section>
    <form className="card intake-card booking-form" onSubmit={submit} noValidate aria-busy={busy}>
      <fieldset className="intake-edit" disabled={pending || busy}>
        <legend>Khách hàng và xe</legend>
        <div className="form-grid">
          <label>Họ và tên<input value={contact.fullName} maxLength={100} required autoComplete="name" aria-invalid={Boolean(fields['contact.fullName'])}
            onChange={(event) => setContact({ ...contact, fullName: event.target.value })} /><IntakeFieldError message={fields['contact.fullName']} /></label>
          <label>Số điện thoại<input type="tel" value={contact.phone} maxLength={40} required autoComplete="tel" aria-invalid={Boolean(fields['contact.phone'])}
            onChange={(event) => setContact({ ...contact, phone: event.target.value })} /><IntakeFieldError message={fields['contact.phone']} /></label>
          <label>Loại xe<select value={vehicle.type} aria-invalid={Boolean(fields['vehicle.type'])} onChange={(event) => {
            setServiceLoading(true);
            setServices([]);
            setVehicle({ ...vehicle, type: event.target.value as VehicleType });
          }}><option value="CAR">Ô tô</option><option value="MOTORBIKE">Xe máy</option></select><IntakeFieldError message={fields['vehicle.type']} /></label>
          <label>Biển số<input value={vehicle.plateNumber} maxLength={20} required aria-invalid={Boolean(fields['vehicle.plateNumber'])}
            onChange={(event) => setVehicle({ ...vehicle, plateNumber: event.target.value })} /><IntakeFieldError message={fields['vehicle.plateNumber']} /></label>
          <label>Hãng xe<input value={vehicle.brand} maxLength={100} aria-invalid={Boolean(fields['vehicle.brand'])}
            onChange={(event) => setVehicle({ ...vehicle, brand: event.target.value })} /><IntakeFieldError message={fields['vehicle.brand']} /></label>
          <label>Mẫu xe<input value={vehicle.model} maxLength={100} aria-invalid={Boolean(fields['vehicle.model'])}
            onChange={(event) => setVehicle({ ...vehicle, model: event.target.value })} /><IntakeFieldError message={fields['vehicle.model']} /></label>
        </div>
        <h2>Dịch vụ yêu cầu</h2>
        {serviceLoading ? <p role="status" className="muted">Đang tải dịch vụ…</p>
          : serviceError ? <div className="intake-feedback" role="alert">{serviceError} <button type="button" className="outline" onClick={() => setReload((value) => value + 1)}>Tải lại dịch vụ</button></div>
          : !services.length ? <p className="muted">Chưa có dịch vụ cho loại xe này.</p>
          : <div className="service-options">{services.map((service) => (
            <label key={service.id} className="choice-card">
              <input type="checkbox" checked={selected.includes(service.id)} onChange={(event) => {
                const checked = event.target.checked;
                setSelected((previous) => checked ? [...previous, service.id] : previous.filter((id) => id !== service.id));
              }} />
              <span>{service.name}<small>{currency.format(service.price)}</small></span>
            </label>
          ))}</div>}
        <IntakeFieldError message={fields.serviceIds} />
        <label>Yêu cầu của khách<textarea value={note} maxLength={1000} rows={2} aria-invalid={Boolean(fields.note)}
          onChange={(event) => setNote(event.target.value)} /><IntakeFieldError message={fields.note} /></label>
        <h2>Thông tin tiếp nhận</h2>
        <IntakeFields draft={details} onChange={setDetails} errors={fields} />
      </fieldset>
      {error && <p className="error" role="alert">{error}</p>}
      {attempt && !busy && <div className="intake-feedback" role="status">
        <b>Chưa xác nhận kết quả tiếp nhận</b>
        <p>{expired ? 'Đã hết thời hạn thử lại. Hãy kiểm tra lịch đã tiếp nhận và phiếu của xe trước khi tạo lượt mới.'
          : 'Gửi lại yêu cầu đã lưu để lấy đúng phiếu. Thông tin và mã yêu cầu được giữ trong tab này.'}</p>
        {expired ? <button type="button" className="outline" onClick={() => {
          if (window.confirm('Bạn đã kiểm tra và xác nhận cần tạo lượt tiếp nhận mới?')) clearAttempt();
        }}>Bắt đầu lượt mới</button> : <button type="button" onClick={() => void send(attempt)} disabled={wait > 0}>
          {wait > 0 ? `Thử lại sau ${wait} giây` : 'Gửi lại yêu cầu đã lưu'}
        </button>}
      </div>}
      {(!attempt || busy) && <div className="intake-actions"><button type="submit" disabled={busy || serviceLoading || Boolean(serviceError) || !services.length}>
        {busy ? 'Đang tiếp nhận…' : 'Tiếp nhận và mở phiếu'}
      </button></div>}
    </form>
  </div>;
}

export function IntakeFields({ draft, onChange, errors }: {
  draft: IntakeDraft; onChange: (value: IntakeDraft) => void; errors: Record<string, string>;
}) {
  return <>
    <label>Số km hiện tại<input inputMode="numeric" value={draft.odometerKm} aria-invalid={Boolean(errors.odometerKm)}
      onChange={(event) => onChange({ ...draft, odometerKm: event.target.value })} /><IntakeFieldError message={errors.odometerKm} /></label>
    <div className="form-grid">
      <label>Tình trạng xe<textarea value={draft.conditionNotes} maxLength={2000} rows={3} aria-invalid={Boolean(errors.conditionNotes)}
        onChange={(event) => onChange({ ...draft, conditionNotes: event.target.value })} /><IntakeFieldError message={errors.conditionNotes} /></label>
      <label>Ghi chú nội bộ<textarea value={draft.internalNotes} maxLength={2000} rows={3} aria-invalid={Boolean(errors.internalNotes)}
        onChange={(event) => onChange({ ...draft, internalNotes: event.target.value })} /><IntakeFieldError message={errors.internalNotes} /></label>
    </div>
  </>;
}

function IntakeFieldError({ message }: { message?: string }) {
  return message ? <span className="error intake-field-error" role="alert">{message}</span> : null;
}
