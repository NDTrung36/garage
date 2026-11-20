import { useState, type FormEvent } from 'react';
import { positiveQuantity, removeLabor, saveLabor, vnd, type LaborItem, type RepairOrderDetail } from './repairApi';

export type RunRepairCommand = (command: () => Promise<unknown>) => Promise<boolean>;
type Draft = { description: string; quantity: string; price: string; serviceId: string };
const emptyDraft: Draft = { description: '', quantity: '1', price: '', serviceId: '' };

export function LaborItemsForm({ detail, disabled, run }: {
  detail: RepairOrderDetail; disabled: boolean; run: RunRepairCommand;
}) {
  const readOnly = detail.status === 'COMPLETED';
  const [draft, setDraft] = useState(emptyDraft);
  const [editing, setEditing] = useState<{ id: string; version: number } | null>(null);
  const [errors, setErrors] = useState<Partial<Record<keyof Draft, string>>>({});
  const current = detail.laborItems.find((item) => item.id === editing?.id);
  const stale = editing !== null && editing.version !== detail.orderVersion;
  function edit(item: LaborItem) {
    setEditing({ id: item.id, version: detail.orderVersion });
    setDraft({ description: item.description, quantity: item.quantity, price: String(item.unitPrice), serviceId: '' });
    setErrors({});
  }
  function reset() { setEditing(null); setDraft(emptyDraft); setErrors({}); }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (disabled || readOnly || stale || (editing && !current)) return;
    const description = draft.description.trim();
    const quantity = draft.quantity.trim();
    const price = draft.price.trim();
    const unitPrice = Number(price);
    const next: typeof errors = {};
    if (!description || description.length > 200) next.description = 'Nhập mô tả từ 1 đến 200 ký tự.';
    if (!positiveQuantity(quantity)) next.quantity = 'Nhập số dương, tối đa 15 chữ số nguyên và 3 chữ số thập phân.';
    if (!/^\d+$/.test(price) || !Number.isSafeInteger(unitPrice) || unitPrice < 0) {
      next.price = 'Nhập số nguyên từ 0 đến 9.007.199.254.740.991 đồng.';
    }
    setErrors(next);
    if (Object.keys(next).length) return;
    if (await run(() => saveLabor(detail.repairOrderId, { description, quantity, unitPrice },
      editing?.version ?? detail.orderVersion, editing?.id, draft.serviceId))) reset();
  }

  return <section className="card repair-info">
    <h2>Công việc thực tế</h2>
    {detail.laborItems.length === 0 ? <p className="muted">Chưa ghi công việc.</p> : <div className="inventory-overflow"><table>
      <thead><tr><th>Công việc</th><th>Số lượng</th><th>Đơn giá</th><th>Thành tiền</th>{!readOnly && <th>Thao tác</th>}</tr></thead>
      <tbody>{detail.laborItems.map((item) => <tr key={item.id}>
        <td>{item.description}</td><td>{item.quantity} {item.unit}</td><td>{vnd(item.unitPrice)}</td><td>{vnd(item.lineTotal)}</td>
        {!readOnly && <td><div className="repair-inline-actions">
          <button className="outline" disabled={disabled || !!editing} onClick={() => edit(item)}>Sửa</button>
          <button className="outline" disabled={disabled || !!editing} onClick={() => {
            if (window.confirm(`Bỏ công việc “${item.description}” khỏi phiếu?`)) {
              void run(() => removeLabor(detail.repairOrderId, item.id, detail.orderVersion));
            }
          }}>Bỏ</button>
        </div></td>}
      </tr>)}</tbody>
    </table></div>}
    {!readOnly && <form className="booking-form repair-edit-form" noValidate onSubmit={(event) => void submit(event)}>
      <fieldset disabled={disabled}>
        <legend>{editing ? 'Sửa công việc' : 'Thêm công việc'}</legend>
        {editing && !current ? <p role="alert">Công việc này không còn trên phiếu.</p> : stale && current &&
          <div className="intake-feedback" role="status">
            <p>Phiếu đã thay đổi. Công việc hiện tại là “{current.description}”, {current.quantity} {current.unit}, đơn giá {vnd(current.unitPrice)}.</p>
            <button type="button" className="outline" onClick={() => setEditing({ id: current.id, version: detail.orderVersion })}>Đã đối chiếu công việc</button>
          </div>}
        {!editing && detail.requestedServices.length > 0 && <label>Dịch vụ liên quan<select value={draft.serviceId} onChange={(event) => {
          const service = detail.requestedServices.find((item) => item.id === event.target.value);
          setDraft((previous) => ({ ...previous, serviceId: event.target.value,
            description: service?.name ?? previous.description,
            price: service ? String(service.referencePrice) : previous.price }));
        }}><option value="">Công việc phát sinh</option>
          {detail.requestedServices.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}
        </select></label>}
        <label>Mô tả công việc<input value={draft.description} maxLength={200} required aria-invalid={!!errors.description}
          onChange={(event) => setDraft({ ...draft, description: event.target.value })} />
          {errors.description && <small className="error" role="alert">{errors.description}</small>}</label>
        <div className="form-grid">
          <label>Số lượng công<input inputMode="decimal" value={draft.quantity} maxLength={19} required aria-invalid={!!errors.quantity}
            onChange={(event) => setDraft({ ...draft, quantity: event.target.value })} />
            {errors.quantity && <small className="error" role="alert">{errors.quantity}</small>}</label>
          <label>Đơn giá công (đồng)<input inputMode="decimal" value={draft.price} maxLength={16} required aria-invalid={!!errors.price}
            onChange={(event) => setDraft({ ...draft, price: event.target.value })} />
            {errors.price && <small className="error" role="alert">{errors.price}</small>}</label>
        </div>
        <div className="repair-inline-actions">
          {editing && <button type="button" className="outline" onClick={reset}>Hủy sửa</button>}
          <button type="submit" disabled={stale || (!!editing && !current)}>{editing ? 'Lưu công việc' : 'Ghi công việc'}</button>
        </div>
      </fieldset>
    </form>}
  </section>;
}
