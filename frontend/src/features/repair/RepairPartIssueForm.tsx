import { useState } from 'react';
import type { FormEvent } from 'react';
import type { Part } from '../part/types';

type Props = { parts: Part[]; disabled: boolean; onSubmit: (payload: { partId: string; quantity: string }) => boolean };
const quantityPattern = /^(?:0|[1-9]\d{0,14})(?:\.\d{1,3})?$/;

export function RepairPartIssueForm({ parts, disabled, onSubmit }: Props) {
  const [partId, setPartId] = useState('');
  const [quantity, setQuantity] = useState('');
  const [error, setError] = useState('');
  const selected = parts.find((part) => part.id === partId);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError('');
    if (!selected) { setError('Chọn phụ tùng muốn xuất.'); return; }
    const value = quantity.trim();
    if (!quantityPattern.test(value) || !/[1-9]/.test(value)) { setError('Số lượng phải lớn hơn 0 và có tối đa 3 chữ số thập phân.'); return; }
    if (onSubmit({ partId, quantity: value })) setQuantity('');
  }

  return <form className="repair-stock-form" onSubmit={submit}>
    <label>Phụ tùng<select value={partId} onChange={(event) => setPartId(event.target.value)} required disabled={disabled || parts.length === 0}><option value="">Chọn phụ tùng phù hợp</option>{parts.map((part) => <option key={part.id} value={part.id}>{part.code} · {part.name} · tồn {part.stockQuantity} {part.unit}</option>)}</select></label>
    {selected && <p className="repair-part-stock">Đơn vị: <b>{selected.unit}</b><span>Tồn kho <b>{selected.stockQuantity}</b></span></p>}
    <label>Số lượng xuất<input type="text" inputMode="decimal" value={quantity} onChange={(event) => setQuantity(event.target.value)} required disabled={disabled} placeholder="Ví dụ: 1.250" /></label>
    {error && <p className="inventory-error" role="alert">{error}</p>}
    <button type="submit" disabled={disabled || parts.length === 0}>Xuất phụ tùng</button>
    {!parts.length && <p className="repair-form-hint">Không có phụ tùng đang kinh doanh phù hợp với loại xe này.</p>}
  </form>;
}
