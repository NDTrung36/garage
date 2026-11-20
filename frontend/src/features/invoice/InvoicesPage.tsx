import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../../api/client';
import { formatGarageTime } from '../booking/bookingApi';
import { listInvoices } from './api';
import type { InvoicePageData } from './types';
import './invoice.css';

const money = (amount: number) => `${amount.toLocaleString('vi-VN')} ₫`;

export function InvoicesPage({ onNavigate }: { onNavigate: (path: string) => void }) {
  const [status, setStatus] = useState('');
  const [pageIndex, setPageIndex] = useState(0);
  const [data, setData] = useState<InvoicePageData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);

  const load = useCallback(async (signal: AbortSignal) => {
    setLoading(true);
    setError('');
    try {
      const result = await listInvoices(status, pageIndex, signal);
      if (signal.aborted) return;
      const lastPage = Math.max(0, result.totalPages - 1);
      if (pageIndex > lastPage) {
        setData(null);
        setPageIndex(lastPage);
        return;
      }
      setData(result);
    } catch (cause) {
      if (!signal.aborted) setError(cause instanceof ApiError ? cause.message : 'Không tải được danh sách hóa đơn.');
    } finally { if (!signal.aborted) setLoading(false); }
  }, [pageIndex, reload, status]);

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  return <section className="invoice-page">
    <div className="invoice-heading">
      <div><p className="eyebrow">THU TIỀN & ĐỐI SOÁT</p><h1>Hóa đơn</h1><p className="muted">Theo dõi hóa đơn từ các phiếu sửa chữa đã hoàn tất.</p></div>
      <button className="outline" disabled={loading} onClick={() => setReload((value) => value + 1)}>{loading ? 'Đang tải…' : 'Tải lại'}</button>
    </div>
    <div className="card invoice-filters">
      <label>Trạng thái thanh toán<select value={status} onChange={(event) => { setStatus(event.target.value); setPageIndex(0); }}>
        <option value="">Tất cả</option><option value="UNPAID">Chưa thanh toán</option><option value="PAID">Đã thanh toán</option>
      </select></label>
      <span className="muted">{data ? `${data.totalElements} hóa đơn` : 'Danh sách hóa đơn'}</span>
    </div>
    {error && <div className="invoice-error" role="alert">{error}<button className="outline" onClick={() => setReload((value) => value + 1)}>Thử lại</button></div>}
    {loading && <p className="invoice-state" role="status">Đang tải hóa đơn…</p>}
    {!loading && !error && data && data.items.length === 0 && <div className="card invoice-state"><h2>Chưa có hóa đơn</h2><p className="muted">Hóa đơn sẽ xuất hiện khi phiếu sửa chữa được hoàn tất.</p></div>}
    {!loading && !error && data && data.items.length > 0 && <div className="card invoice-table-wrap">
      <table className="invoice-table"><thead><tr><th>Mã hóa đơn</th><th>Phiếu sửa</th><th>Ngày phát hành</th><th>Tổng tiền</th><th>Trạng thái</th><th></th></tr></thead>
        <tbody>{data.items.map((invoice) => <tr key={invoice.id}>
          <td><button className="invoice-link" onClick={() => onNavigate(`/staff/invoices/${invoice.id}`)}>{invoice.code}</button></td>
          <td>#{invoice.repairOrderId}</td><td>{formatGarageTime(invoice.issuedAt)}</td><td className="invoice-money">{money(invoice.totalAmount)}</td>
          <td><span className={`invoice-badge ${invoice.paymentStatus.toLowerCase()}`}>{invoice.paymentStatus === 'PAID' ? 'Đã thanh toán' : 'Chưa thanh toán'}</span></td>
          <td><button className="outline invoice-view" onClick={() => onNavigate(`/staff/invoices/${invoice.id}`)}>Xem</button></td>
        </tr>)}</tbody>
      </table>
      <div className="invoice-pager"><span>Trang {data.page + 1} / {Math.max(1, data.totalPages)}</span><button className="outline" disabled={loading || data.page <= 0} onClick={() => setPageIndex((value) => Math.max(0, value - 1))}>Trước</button><button className="outline" disabled={loading || data.page + 1 >= data.totalPages} onClick={() => setPageIndex((value) => value + 1)}>Tiếp</button></div>
    </div>}
  </section>;
}
