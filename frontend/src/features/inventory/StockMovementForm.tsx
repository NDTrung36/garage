import { useState } from 'react';
import type { FormEvent } from 'react';
import type { PartOption, StockCommand } from './types';

type Props = { parts: PartOption[]; busy: boolean; locked: boolean; onSubmit: (payload: StockCommand) => void };
const quantityPattern = /^[+-]?(?:0|[1-9]\d{0,14})(?:\.\d{1,3})?$/;

export function StockMovementForm({ parts, busy, locked, onSubmit }: Props) {
  const [mode, setMode] = useState<'receipt' | 'adjustment'>('receipt');
  const [partId, setPartId] = useState('');
  const [quantity, setQuantity] = useState('');
  const [reason, setReason] = useState('');
  const [note, setNote] = useState('');
  const [error, setError] = useState('');
  const selected = parts.find((part) => part.id === partId);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError('');
    if (!selected) { setError('Hãy chọn phụ tùng.'); return; }
    const normalized = quantity.trim();
    if (!quantityPattern.test(normalized) || /^[-+]?0(?:\.0{1,3})?$/.test(normalized)) {
      setError('Nhập số lượng tối đa 3 chữ số thập phân; điều chỉnh không được bằng 0.'); return;
    }
    if (mode === 'receipt' && (normalized.startsWith('-') || normalized.startsWith('+'))) {
      setError('Số lượng nhập phải là số dương, không có dấu.'); return;
    }
    if (mode === 'adjustment' && !normalized.startsWith('-') && !normalized.startsWith('+')) {
      setError('Điều chỉnh cần có dấu + hoặc − để thể hiện tăng hay giảm tồn.'); return;
    }
    const cleanReason = reason.trim();
    if (!cleanReason || cleanReason.length > 500) { setError('Lý do bắt buộc, tối đa 500 ký tự.'); return; }
    const payload: StockCommand = mode === 'receipt'
      ? { partId, quantity: normalized, reason: cleanReason, ...(note.trim() ? { note: note.trim() } : {}) }
      : { partId, quantityDelta: normalized, reason: cleanReason };
    onSubmit(payload);
    setQuantity(''); setReason(''); setNote('');
  }

  return <section className="stock-command card">
    <div className="inventory-table-title"><div><p className="eyebrow">THAO TÁC QUẢN TRỊ</p><h2>Ghi nhận biến động</h2></div><span className="stock-command-icon">↕</span></div>
    <div className="stock-mode" role="group" aria-label="Loại biến động"><button type="button" className={mode === 'receipt' ? 'selected' : ''} disabled={locked || busy} onClick={() => { setMode('receipt'); setQuantity(''); }}>Nhập kho</button><button type="button" className={mode === 'adjustment' ? 'selected' : ''} disabled={locked || busy} onClick={() => { setMode('adjustment'); setQuantity(''); }}>Điều chỉnh</button></div>
    <form onSubmit={submit}>
      <label>Phụ tùng<select value={partId} onChange={(event) => setPartId(event.target.value)} required disabled={locked || busy}><option value="">Chọn phụ tùng</option>{parts.map((part) => <option key={part.id} value={part.id}>{part.code} · {part.name} · tồn {part.stockQuantity} {part.unit}{part.active ? '' : ' · ngừng kinh doanh'}</option>)}</select></label>
      {selected && <p className="selected-stock">Tồn hiện tại <b>{selected.stockQuantity} {selected.unit}</b></p>}
      <label>{mode === 'receipt' ? 'Số lượng nhập' : 'Lượng điều chỉnh'}<span className="quantity-entry"><input type="text" inputMode="decimal" value={quantity} onChange={(event) => setQuantity(event.target.value)} required disabled={locked || busy} placeholder={mode === 'receipt' ? 'Ví dụ: 2.500' : 'Ví dụ: -0.750'} /><small>{mode === 'adjustment' ? 'Dùng + để tăng, − để giảm' : 'Số lượng nhập luôn dương'}</small></span></label>
      <label>Lý do<input value={reason} onChange={(event) => setReason(event.target.value)} maxLength={500} required disabled={locked || busy} placeholder={mode === 'receipt' ? 'Nhập từ nhà cung cấp…' : 'Kết quả kiểm kê…'} /></label>
      {mode === 'receipt' && <label>Ghi chú (không bắt buộc)<textarea value={note} onChange={(event) => setNote(event.target.value)} maxLength={500} disabled={locked || busy} rows={2} placeholder="Thông tin thêm cho lần nhập" /></label>}
      {error && <p className="inventory-error" role="alert">{error}</p>}
      <button className="stock-command-submit" disabled={locked || busy || parts.length === 0}>{busy ? 'Đang ghi…' : mode === 'receipt' ? 'Xác nhận nhập kho' : 'Xác nhận điều chỉnh'}</button>
    </form>
  </section>;
}
