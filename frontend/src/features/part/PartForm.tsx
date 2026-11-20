import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { ApiError } from '../../api/client';
import type { Part, PartPayload } from './types';

type Props = { part: Part | null; busy: boolean; onSave: (payload: PartPayload) => Promise<void>; onCancel: () => void };

export function PartForm({ part, busy, onSave, onCancel }: Props) {
  const [error, setError] = useState('');
  const [version, setVersion] = useState(part?.version ?? 0);

  useEffect(() => { setVersion(part?.version ?? 0); setError(''); }, [part]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const name = String(form.get('name') ?? '').trim();
    const unit = String(form.get('unit') ?? '').trim();
    const vehicleType = String(form.get('vehicleType') ?? 'BOTH') as Part['vehicleType'];
    const price = String(form.get('referencePrice') ?? '').trim();
    if (!/^\d+$/.test(price) || BigInt(price) > 9007199254740991n) { setError('Giá phải là số nguyên từ 0 đến 9.007.199.254.740.991 đồng.'); return; }
    const payload: PartPayload = part
      ? { name, unit, vehicleType, referencePrice: price, active: form.get('active') === 'on', expectedVersion: version }
      : { code: String(form.get('code') ?? '').trim(), name, unit, vehicleType, referencePrice: price };
    setError('');
    try { await onSave(payload); }
    catch (cause) {
      if (cause instanceof ApiError && cause.status === 409 && cause.code === 'PART_UNIT_LOCKED') {
        setError('Phụ tùng đã phát sinh biến động kho nên không thể đổi đơn vị. Danh mục đang được tải lại.');
      } else if (cause instanceof ApiError && cause.status === 409 && (cause.code === 'STALE_VERSION' || cause.code === 'STALE_PART_VERSION')) {
        setError('Phụ tùng đã thay đổi ở nơi khác. Danh mục đang được tải lại.');
      } else setError(cause instanceof Error ? cause.message : 'Không lưu được phụ tùng.');
      throw cause;
    }
  }

  return <div className="inventory-modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget && !busy) onCancel(); }}>
    <form className="inventory-modal card" onSubmit={(event) => void submit(event)} aria-label={part ? 'Sửa phụ tùng' : 'Tạo phụ tùng'}>
      <div className="inventory-modal-heading"><div><span className="eyebrow">DANH MỤC KHO</span><h2>{part ? 'Sửa phụ tùng' : 'Thêm phụ tùng'}</h2></div><button type="button" className="icon-button" aria-label="Đóng" onClick={onCancel} disabled={busy}>×</button></div>
      {!part && <label>Mã phụ tùng<input name="code" required maxLength={40} autoFocus placeholder="VD: LOC-001" /></label>}
      <div className="part-form-grid">
        <label>Tên phụ tùng<input name="name" required maxLength={150} defaultValue={part?.name} placeholder="Tên hiển thị" /></label>
        <label>Đơn vị<input name="unit" required maxLength={30} defaultValue={part?.unit} placeholder="Cái, chai, bộ…" /></label>
        <label>Loại xe<select name="vehicleType" defaultValue={part?.vehicleType ?? 'BOTH'}><option value="BOTH">Ô tô và xe máy</option><option value="CAR">Ô tô</option><option value="MOTORBIKE">Xe máy</option></select></label>
        <label>Giá tham khảo (đồng)<input name="referencePrice" inputMode="numeric" pattern="[0-9]*" required defaultValue={String(part?.referencePrice ?? 0)} /></label>
      </div>
      {part && <><label className="inventory-checkbox"><input type="checkbox" name="active" defaultChecked={part.active} /> Đang kinh doanh</label><p className="inventory-hint">Mã {part.code} · tồn hiện tại {part.stockQuantity} {part.unit} · phiên bản {version}</p></>}
      {error && <p className="inventory-error" role="alert">{error}</p>}
      <div className="inventory-form-actions"><button type="button" className="outline" onClick={onCancel} disabled={busy}>Hủy</button><button type="submit" disabled={busy}>{busy ? 'Đang lưu…' : part ? 'Lưu thay đổi' : 'Tạo phụ tùng'}</button></div>
    </form>
  </div>;
}
