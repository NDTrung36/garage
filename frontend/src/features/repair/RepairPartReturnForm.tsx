import { useState } from 'react';
import type { FormEvent } from 'react';
import type { RepairPartItem, ReturnableIssue } from './partApi';

type Props = { issues: ReturnableIssue[]; partItems: RepairPartItem[]; disabled: boolean; onSubmit: (payload: { originalIssueId: string; quantity: string }) => boolean };
const quantityPattern = /^(?:0|[1-9]\d{0,14})(?:\.\d{1,3})?$/;

export function RepairPartReturnForm({ issues, partItems, disabled, onSubmit }: Props) {
  const returnable = issues.filter((issue) => isPositive(issue.remainingQuantity));
  const [issueId, setIssueId] = useState('');
  const [quantity, setQuantity] = useState('');
  const [error, setError] = useState('');
  const selected = returnable.find((issue) => issue.id === issueId);
  const partItem = selected && partItems.find((item) => item.id === selected.repairItemId);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError('');
    if (!selected) { setError('Chọn lần xuất còn lượng có thể hoàn.'); return; }
    const value = quantity.trim();
    if (!quantityPattern.test(value) || !/[1-9]/.test(value)) { setError('Số lượng hoàn phải lớn hơn 0, tối đa 3 chữ số thập phân.'); return; }
    if (decimalGreaterThan(value, selected.remainingQuantity)) { setError(`Lượng hoàn không được vượt ${selected.remainingQuantity}.`); return; }
    if (onSubmit({ originalIssueId: selected.id, quantity: value })) setQuantity('');
  }

  return <form className="repair-stock-form" onSubmit={submit}>
    <label>Lần xuất gốc<select value={issueId} onChange={(event) => setIssueId(event.target.value)} required disabled={disabled || !returnable.length}><option value="">Chọn lần xuất cần hoàn</option>{returnable.map((issue) => {
      const part = partItems.find((item) => item.id === issue.repairItemId);
      return <option key={issue.id} value={issue.id}>#{issue.id} · {part?.description ?? `Phụ tùng #${issue.partId}`} · còn {issue.remainingQuantity} {part?.unit ?? ''} · {formatTime(issue.createdAt)}</option>;
    })}</select></label>
    {selected && <p className="repair-part-stock">Đã xuất {selected.issuedQuantity}<span>Còn được hoàn <b>{selected.remainingQuantity} {partItem?.unit ?? ''}</b></span></p>}
    <label>Số lượng hoàn<input type="text" inputMode="decimal" value={quantity} onChange={(event) => setQuantity(event.target.value)} required disabled={disabled || !returnable.length} placeholder="Ví dụ: 0.250" /></label>
    {error && <p className="inventory-error" role="alert">{error}</p>}
    <button type="submit" className="outline" disabled={disabled || !returnable.length}>Hoàn phụ tùng</button>
    {!returnable.length && <p className="repair-form-hint">Không còn lần xuất nào có lượng được hoàn.</p>}
  </form>;
}

function isPositive(value: string) { return /[1-9]/.test(value); }
function decimalGreaterThan(left: string, right: string) { return BigInt(left.replace('.', '').padEnd(left.split('.')[0].length + 3, '0')) > BigInt(right.replace('.', '').padEnd(right.split('.')[0].length + 3, '0')); }
function formatTime(value: string) { return new Intl.DateTimeFormat('vi-VN', { timeZone: 'Asia/Ho_Chi_Minh', dateStyle: 'short', timeStyle: 'short' }).format(new Date(value)); }
