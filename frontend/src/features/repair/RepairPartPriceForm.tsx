import { useState } from 'react';
import type { FormEvent } from 'react';
import type { RepairPartItem } from './partApi';

type Props = { item: RepairPartItem; orderVersion: number; disabled: boolean; initialPrice?: string; initialReason?: string; confirmRetry?: boolean; onSave: (unitPrice: number, reason: string, version: number) => Promise<void>; onCancel: () => void };

export function RepairPartPriceForm({ item, orderVersion, disabled, initialPrice, initialReason, confirmRetry, onSave, onCancel }: Props) {
  const [price, setPrice] = useState(initialPrice ?? String(item.unitPrice));
  const [reason, setReason] = useState(initialReason ?? '');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError('');
    const rawPrice = price.trim();
    if (!/^\d+$/.test(rawPrice) || BigInt(rawPrice) > 9007199254740991n) { setError('Giá phải là số nguyên từ 0 đến 9.007.199.254.740.991 đồng.'); return; }
    const cleanReason = reason.trim();
    if (!cleanReason || cleanReason.length > 500) { setError('Lý do bắt buộc, tối đa 500 ký tự.'); return; }
    setSaving(true);
    try { await onSave(Number(BigInt(rawPrice)), cleanReason, orderVersion); }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Chưa cập nhật được giá.'); }
    finally { setSaving(false); }
  }

  return <div className="inventory-modal-backdrop" role="presentation">
    <form className="inventory-modal card repair-price-modal" onSubmit={(event) => void submit(event)} aria-label="Sửa giá phụ tùng trên phiếu">
      <div className="inventory-modal-heading"><div><span className="eyebrow">GIÁ SNAPSHOT TRÊN PHIẾU</span><h2>Sửa giá phụ tùng</h2></div><button type="button" className="icon-button" aria-label="Đóng" onClick={onCancel} disabled={saving}>×</button></div>
      <p className="repair-price-item">{item.description} · số lượng ròng {item.quantity} {item.unit}</p>
      {confirmRetry && <p className="inventory-retry-panel repair-price-retry" role="status">Đã đối soát và tải chi tiết mới nhất. Giá chưa đổi; xác nhận lại để gửi với phiên bản {orderVersion}.</p>}
      <label>Đơn giá mới (đồng)<input inputMode="numeric" pattern="[0-9]*" value={price} onChange={(event) => setPrice(event.target.value)} required disabled={disabled || saving} /></label>
      <label>Lý do thay đổi<input value={reason} onChange={(event) => setReason(event.target.value)} maxLength={500} required disabled={disabled || saving} placeholder="Ghi lý do để lưu vào kiểm toán" /></label>
      {error && <p className="inventory-error" role="alert">{error}</p>}
      <div className="inventory-form-actions"><button type="button" className="outline" onClick={onCancel} disabled={saving}>Hủy</button><button type="submit" disabled={disabled || saving}>{saving ? 'Đang cập nhật…' : confirmRetry ? 'Xác nhận gửi lại' : 'Cập nhật giá'}</button></div>
    </form>
  </div>;
}
