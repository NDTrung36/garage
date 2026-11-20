import { useCallback, useEffect, useRef, useState } from 'react';
import type { GarageUser } from '../../api/client';
import { formatGarageTime } from '../booking/bookingApi';
import { getInvoice } from './api';
import type { InvoiceDetail, InvoicePaymentMethod } from './types';
import { useInvoicePayment } from './useInvoicePayment';
import './invoice.css';

const money = (amount: number) => `${amount.toLocaleString('vi-VN')} ₫`;
const paymentMethodLabel = (method: InvoicePaymentMethod | null) => method === 'CASH' ? 'Tiền mặt' : method === 'BANK_TRANSFER' ? 'Chuyển khoản' : method === 'NO_CHARGE' ? 'Miễn phí' : '—';

export function InvoicePage({ id, user, onNavigate }: { id: string; user: GarageUser; onNavigate: (path: string) => void }) {
  const [invoice, setInvoice] = useState<InvoiceDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [method, setMethod] = useState<InvoicePaymentMethod | ''>('');
  const [confirmed, setConfirmed] = useState(false);
  const [reason, setReason] = useState('');
  const [resetVersion, setResetVersion] = useState<number | null>(null);
  const activeRead = useRef<AbortController | null>(null);
  const mounted = useRef(false);

  const refresh = useCallback(async () => {
    if (!mounted.current) return;
    activeRead.current?.abort();
    const controller = new AbortController();
    activeRead.current = controller;
    setLoading(true);
    setError('');
    try {
      const fresh = await getInvoice(id, controller.signal);
      if (!controller.signal.aborted) setInvoice(fresh);
    } catch (cause) {
      if (controller.signal.aborted) return;
      setError(cause instanceof Error ? cause.message : 'Không tải được hóa đơn.');
      throw cause;
    }
    finally { if (!controller.signal.aborted) setLoading(false); }
  }, [id]);

  useEffect(() => {
    mounted.current = true;
    void refresh().catch(() => {});
    return () => {
      mounted.current = false;
      activeRead.current?.abort();
    };
  }, [refresh]);

  const canReset = user.role === 'ADMIN';
  const payment = useInvoicePayment(user.id, id, canReset, refresh);
  const canWrite = !loading && !error && invoice !== null && !payment.pending;
  const reloadInvoice = () => { void refresh().catch(() => {}); };
  const submitPaid = () => {
    if (!invoice || !canWrite || payment.busy || !confirmed) return;
    const selected = invoice.totalAmount === 0 ? 'NO_CHARGE' : method;
    if (!selected) return;
    payment.start({ kind: 'mark-paid', expectedVersion: invoice.version, paymentMethod: selected });
    setConfirmed(false);
  };

  return <section className="invoice-page invoice-detail-page">
    <button className="text-button invoice-back" onClick={() => onNavigate('/staff/invoices')}>← Danh sách hóa đơn</button>
    {loading && <p className="invoice-state" role="status">Đang tải hóa đơn…</p>}
    {error && <div className="invoice-error" role="alert">{error}<button className="outline" onClick={reloadInvoice}>Tải lại</button></div>}
    {payment.message && <div className="invoice-feedback" role="status">
      <span>{payment.message}</span>
      {payment.pending && !error && !loading && invoice && <button disabled={payment.busy || payment.retryAt > Date.now()} onClick={payment.retry}>
        {payment.busy ? 'Đang gửi…' : payment.retryAt > Date.now() ? `Thử lại sau ${Math.ceil((payment.retryAt - Date.now()) / 1000)} giây` : 'Gửi lại yêu cầu đã lưu'}
      </button>}
    </div>}
    {!loading && !error && invoice && <>
      <div className="invoice-detail-heading">
        <div><p className="eyebrow">HÓA ĐƠN · PHIẾU #{invoice.repairOrderId}</p><h1>{invoice.code}</h1><span className={`invoice-badge ${invoice.paymentStatus.toLowerCase()}`}>{invoice.paymentStatus === 'PAID' ? 'Đã thanh toán' : 'Chưa thanh toán'}</span></div>
        <button className="outline" disabled={payment.busy} onClick={reloadInvoice}>Tải lại hóa đơn</button>
      </div>
      <div className="invoice-detail-grid">
        <div className="invoice-detail-main">
          <section className="card invoice-facts">
            <div><span>Phiếu sửa chữa</span><button className="invoice-link" onClick={() => onNavigate(`/staff/repair-orders/${invoice.repairOrderId}`)}>Phiếu #{invoice.repairOrderId} ↗</button></div>
            <div><span>Ngày phát hành</span><b>{formatGarageTime(invoice.issuedAt)}</b></div>
            <div><span>Mã hóa đơn</span><b>{invoice.code}</b></div>
          </section>
          <section className="card invoice-lines-card">
            <div className="invoice-card-heading"><div><h2>Chi tiết đã chốt</h2><p className="muted">Giá và số lượng được lưu tại thời điểm hoàn tất phiếu.</p></div><span>{invoice.items.length} dòng</span></div>
            {invoice.items.length === 0 ? <p className="invoice-state">Hóa đơn không phát sinh hạng mục.</p> : <div className="invoice-table-wrap invoice-lines-wrap">
              <table className="invoice-table invoice-lines-table"><thead><tr><th>Hạng mục</th><th>Đơn vị</th><th>Số lượng</th><th>Đơn giá</th><th>Thành tiền</th></tr></thead>
                <tbody>{invoice.items.map((line) => <tr key={`${line.type}-${line.id}`}><td><span className="invoice-line-type">{line.type === 'LABOR' ? 'Công việc' : 'Phụ tùng'}</span><b>{line.description}</b></td><td>{line.unit}</td><td>{line.quantity}</td><td>{money(line.unitPrice)}</td><td className="invoice-money">{money(line.lineTotal)}</td></tr>)}</tbody>
              </table>
            </div>}
            <div className="invoice-total"><span>Tổng hóa đơn</span><strong>{money(invoice.totalAmount)}</strong></div>
          </section>
        </div>
        <aside className="card invoice-payment-card">
          <p className="eyebrow">THANH TOÁN</p><h2>{invoice.paymentStatus === 'PAID' ? 'Đã ghi nhận thanh toán' : 'Xác nhận thu đủ'}</h2>
          <div className="invoice-due"><span>{invoice.paymentStatus === 'PAID' ? 'Đã thanh toán' : 'Số tiền cần thu'}</span><strong>{money(invoice.totalAmount)}</strong></div>
          {invoice.paymentStatus === 'UNPAID' ? <form className="invoice-payment-form" onSubmit={(event) => { event.preventDefault(); submitPaid(); }}>
            {invoice.totalAmount > 0 ? <label>Phương thức thanh toán<select value={method} onChange={(event) => setMethod(event.target.value as InvoicePaymentMethod | '')} disabled={!canWrite || payment.busy}>
              <option value="">Chọn phương thức</option><option value="CASH">Tiền mặt</option><option value="BANK_TRANSFER">Chuyển khoản</option>
            </select></label> : <p className="invoice-free-note">Tổng tiền bằng 0 ₫. Hóa đơn sẽ được xác nhận theo diện miễn phí.</p>}
            <label className="invoice-confirm"><input type="checkbox" checked={confirmed} onChange={(event) => setConfirmed(event.target.checked)} disabled={!canWrite || payment.busy} />Tôi xác nhận đã thu đủ số tiền trên.</label>
            <button disabled={!canWrite || payment.busy || !confirmed || (invoice.totalAmount > 0 && !method)}>{payment.busy ? 'Đang ghi nhận…' : invoice.totalAmount === 0 ? 'Xác nhận miễn phí' : `Xác nhận thu ${money(invoice.totalAmount)}`}</button>
          </form> : <dl className="invoice-paid-facts">
            <div><dt>Số tiền ghi nhận</dt><dd>{invoice.paidAmount == null ? '—' : money(invoice.paidAmount)}</dd></div>
            <div><dt>Phương thức</dt><dd>{paymentMethodLabel(invoice.paymentMethod)}</dd></div>
            <div><dt>Người thu</dt><dd>{invoice.paidByDisplayName || (invoice.paidBy ? `Nhân viên #${invoice.paidBy}` : '—')}</dd></div>
            <div><dt>Thời điểm</dt><dd>{invoice.paidAt ? formatGarageTime(invoice.paidAt) : '—'}</dd></div>
          </dl>}
          {canReset && invoice.paymentStatus === 'PAID' && <div className="invoice-reset">
            <h3>Sửa ghi nhận thanh toán</h3><p className="muted">Thao tác chỉ cập nhật trạng thái thanh toán trong hồ sơ hóa đơn.</p>
            {resetVersion !== invoice.version ? <>
              {resetVersion !== null && <p role="status">Ghi nhận đã thay đổi. Hãy kiểm tra khoản thu hiện tại trước khi mở lại biểu mẫu.</p>}
              <button className="outline" disabled={!canWrite || payment.busy} onClick={() => {
                setReason('');
                setResetVersion(invoice.version);
              }}>Mở biểu mẫu</button>
            </> : <form onSubmit={(event) => {
              event.preventDefault();
              const trimmed = reason.trim();
              if (!canWrite || !trimmed || trimmed.length > 500 || payment.busy || resetVersion !== invoice.version) return;
              payment.start({ kind: 'reset-payment', expectedVersion: resetVersion, reason: trimmed });
              setResetVersion(null);
            }}>
              <label>Lý do cập nhật<textarea value={reason} onChange={(event) => setReason(event.target.value)} maxLength={500} required disabled={!canWrite || payment.busy} /></label>
              <small>{reason.trim().length}/500 ký tự</small>
              <div className="invoice-actions"><button type="button" className="outline" disabled={payment.busy} onClick={() => setResetVersion(null)}>Hủy</button><button disabled={!canWrite || payment.busy || !reason.trim()}>Cập nhật ghi nhận</button></div>
            </form>}
          </div>}
        </aside>
      </div>
    </>}
  </section>;
}
