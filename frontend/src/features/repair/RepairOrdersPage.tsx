import { useEffect, useState } from 'react';
import { listRepairOrders, type RepairOrderListItem, type RepairOrderPageData } from './repairApi';

type ListProps = { onNavigate: (path: string) => void };

export function RepairOrdersPage({ onNavigate }: ListProps) {
  const [data, setData] = useState<RepairOrderPageData | null>(null);
  const [status, setStatus] = useState('IN_PROGRESS');
  const [page, setPage] = useState(0);
  function filter(value: string) { setStatus(value); setPage(0); }
  const [reloadIndex, setReloadIndex] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    const controller = new AbortController(); setLoading(true); setData(null); setError('');
    void listRepairOrders(status, page, controller.signal).then((result) => {
      if (controller.signal.aborted) return;
      setData(result);
    }).catch((cause) => { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : 'Không tải được danh sách phiếu.'); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [status, page, reloadIndex]);

  const orders = data?.items ?? [];
  return <div className="repair-orders-page inventory-page"><section className="inventory-page-heading"><div><p className="eyebrow">TIẾP NHẬN & SỬA CHỮA</p><h1>Phiếu sửa chữa</h1><p className="muted">Theo dõi xe đang sửa và xem chi tiết vật tư trên từng phiếu.</p></div><button className="outline" onClick={() => filter('')}>Tất cả phiếu</button></section>
    <div className="repair-order-toolbar card"><div><b>Danh sách phiếu</b><span>{data?.totalElements ?? 0} phiếu</span></div><label>Trạng thái<select value={status} onChange={(event) => filter(event.target.value)}><option value="IN_PROGRESS">Đang sửa</option><option value="COMPLETED">Đã chốt</option><option value="">Tất cả</option></select></label></div>
    {error && <div className="inventory-error-panel" role="alert"><span>{error}</span><button className="outline" onClick={() => setReloadIndex((value) => value + 1)}>Thử lại</button></div>}
    <div className="inventory-table-card card">{loading ? <div className="inventory-loading">Đang tải phiếu…</div> : error ? null : !orders.length ? <div className="inventory-empty"><b>Chưa có phiếu phù hợp</b><span>Phiếu được tạo khi khách đến garage hoặc nhân viên tiếp nhận lịch hẹn.</span></div> : <div className="inventory-overflow"><table className="inventory-table repair-order-table"><thead><tr><th>Phiếu / lịch hẹn</th><th>Khách hàng</th><th>Xe</th><th>Tiếp nhận</th><th>Trạng thái</th><th /></tr></thead><tbody>{orders.map((order) => <RepairOrderRow key={order.repairOrderId} order={order} onOpen={() => onNavigate(`/staff/repair-orders/${order.repairOrderId}`)} />)}</tbody></table></div>}
    {data && (data.totalPages > 1 || page > 0) && <div className="pager">
      <span>{data.totalElements} phiếu</span>
      <button className="outline" disabled={page === 0} onClick={() => setPage(page - 1)}>Trước</button>
      <button className="outline" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Tiếp</button>
    </div>}</div>
  </div>;
}

function RepairOrderRow({ order, onOpen }: { order: RepairOrderListItem; onOpen: () => void }) {
  return <tr className="repair-order-row" onClick={onOpen} tabIndex={0} onKeyDown={(event) => { if (event.target === event.currentTarget && (event.key === 'Enter' || event.key === ' ')) { event.preventDefault(); onOpen(); } }}><td><b>Phiếu #{order.repairOrderId}</b><small>{order.bookingCode} · {order.bookingSource}</small></td><td><b>{order.contactName}</b><small>{order.contactPhone}</small></td><td><b>{order.plate}</b><small>{vehicleName(order.vehicleType)}</small></td><td>{formatTime(order.receivedAt)}</td><td><span className={`repair-order-state ${order.status === 'COMPLETED' ? 'complete' : ''}`}>{order.status === 'COMPLETED' ? 'Đã chốt' : 'Đang sửa'}</span></td><td><button className="text-button" onClick={(event) => { event.stopPropagation(); onOpen(); }}>Mở phiếu →</button></td></tr>;
}

function vehicleName(type: string) { return type === 'CAR' ? 'Ô tô' : 'Xe máy'; }
function formatTime(value: string) { return new Intl.DateTimeFormat('vi-VN', { timeZone: 'Asia/Ho_Chi_Minh', dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)); }

