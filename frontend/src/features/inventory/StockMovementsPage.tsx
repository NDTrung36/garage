import { useCallback, useEffect, useState } from 'react';
import type { GarageUser } from '../../api/client';
import { listMovements, listPartOptions } from './api';
import { StockMovementForm } from './StockMovementForm';
import { movementLabels, type MovementPage, type PartOption, type StockCommand, type StockFilter, type StockMovement } from './types';
import { useStockCommand } from './useStockCommand';

type Props = { user: GarageUser };
const emptyPage: MovementPage = { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };

export function StockMovementsPage({ user }: Props) {
  const isAdmin = user.role === 'ADMIN';
  const [filters, setFilters] = useState<StockFilter>({ page: 0, partId: '', movementType: '' });
  const [page, setPage] = useState<MovementPage | null>(null);
  const [parts, setParts] = useState<PartOption[]>([]);
  const [loading, setLoading] = useState(true);
  const [partsLoading, setPartsLoading] = useState(true);
  const [error, setError] = useState('');
  const [partsError, setPartsError] = useState('');

  const fetchMovements = useCallback(async (next: StockFilter, signal?: AbortSignal) => {
    const params = new URLSearchParams({ page: String(next.page), size: '20' });
    if (next.partId) params.set('partId', next.partId);
    if (next.movementType) params.set('movementType', next.movementType);
    setLoading(true); setError('');
    try { const result = await listMovements(params, signal); if (!signal?.aborted) setPage(result); }
    catch (cause) { if (!signal?.aborted) setError(cause instanceof Error ? cause.message : 'Không tải được lịch sử kho.'); }
    finally { if (!signal?.aborted) setLoading(false); }
  }, []);

  const fetchParts = useCallback(async (signal?: AbortSignal) => {
    setPartsLoading(true); setPartsError('');
    try { const result = await listPartOptions(signal); if (!signal?.aborted) setParts(result.items); }
    catch (cause) { if (!signal?.aborted) setPartsError(cause instanceof Error ? cause.message : 'Không tải được danh mục phụ tùng.'); }
    finally { if (!signal?.aborted) setPartsLoading(false); }
  }, []);

  useEffect(() => { const controller = new AbortController(); void fetchParts(controller.signal); return () => controller.abort(); }, [fetchParts]);
  useEffect(() => { const controller = new AbortController(); void fetchMovements(filters, controller.signal); return () => controller.abort(); }, [fetchMovements, filters]);

  const refreshCurrent = useCallback(async () => {
    const current = { ...filters, page: 0 };
    setFilters(current);
    await Promise.all([fetchParts(), fetchMovements(current)]);
  }, [fetchMovements, fetchParts, filters]);
  const command = useStockCommand(user.id, refreshCurrent);

  const submitCommand = (payload: StockCommand) => {
    const route = payload.quantity ? '/admin/stock-receipts' : '/admin/stock-adjustments';
    void command.execute(route, payload);
  };

  const currentPage = page ?? emptyPage;
  const unresolved = Boolean(command.attempt);
  return <div className="inventory-page">
    <section className="inventory-page-heading"><div><p className="eyebrow">KHO PHỤ TÙNG</p><h1>Lịch sử tồn kho</h1><p className="muted">Theo dõi nhập, điều chỉnh, xuất và hoàn phụ tùng theo từng biến động.</p></div><button className="outline" onClick={() => void refreshCurrent()} disabled={loading || partsLoading}>↻ Làm mới dữ liệu</button></section>
    {isAdmin && <div className="inventory-command-layout"><StockMovementForm parts={parts} busy={command.busy} locked={unresolved} onSubmit={submitCommand} />
      <div className="stock-command-aside"><span className="stock-aside-icon">✓</span><h3>Ghi nhận có kiểm soát</h3><p>Mỗi thao tác nhập hoặc điều chỉnh được lưu cùng lý do và một mã yêu cầu riêng để tránh ghi trùng khi thử lại.</p><div className="stock-aside-stat"><span>Phụ tùng trong danh sách</span><b>{parts.length}{parts.length === 100 ? '+' : ''}</b></div></div>
    </div>}
    {unresolved && <div className="inventory-retry-panel" role="alert"><div><b>Đang chờ xác nhận kết quả</b><p>{command.message || `Mã yêu cầu ${command.attempt?.key} chưa hoàn tất.`}</p><small>Đã giữ nguyên dữ liệu và mã yêu cầu. Không sửa payload trước khi xử lý xong lệnh này.</small></div><div><button type="button" className="outline" disabled={loading} onClick={() => void refreshCurrent()}>Đối soát tồn và lịch sử</button>{command.expired ? <button type="button" onClick={() => { if (window.confirm('Bạn đã đối soát tồn và lịch sử kho?')) command.clearExpired(); }}>Đã đối soát lệnh hết hạn</button> : <button type="button" disabled={command.busy || command.retryBlocked} onClick={command.retry}>{command.busy ? 'Đang thử lại…' : command.retryBlocked ? `Thử lại sau ${command.waitSeconds}s` : 'Thử lại đúng lệnh cũ'}</button>}</div></div>}
    {command.message && !unresolved && <p className="inventory-notice" role="status">{command.message}</p>}
    {partsError && <div className="inventory-error-panel" role="alert"><span>{partsError}</span><button className="outline" onClick={() => void fetchParts()}>Tải lại phụ tùng</button></div>}
    {error && <div className="inventory-error-panel" role="alert"><span>{error}</span><button className="outline" onClick={() => void fetchMovements(filters)}>Tải lại lịch sử</button></div>}
    {partsLoading && <p className="inventory-hint">Đang tải danh mục phụ tùng…</p>}
    <div className="inventory-table-card card">
      <div className="inventory-table-title"><div><p className="eyebrow">SỔ KHO</p><h2>Các lần biến động</h2><span>{currentPage.totalElements} giao dịch</span></div><button className="outline" onClick={() => void fetchMovements(filters)} disabled={loading}>Làm mới lịch sử</button></div>
      <div className="movement-filters"><label>Phụ tùng<select value={filters.partId} onChange={(event) => setFilters((value) => ({ ...value, page: 0, partId: event.target.value }))}><option value="">Tất cả phụ tùng</option>{parts.map((part) => <option key={part.id} value={part.id}>{part.code} · {part.name}</option>)}</select></label><label>Loại biến động<select value={filters.movementType} onChange={(event) => setFilters((value) => ({ ...value, page: 0, movementType: event.target.value }))}><option value="">Tất cả loại</option><option value="RECEIPT">Nhập kho</option><option value="ADJUSTMENT">Điều chỉnh</option><option value="ISSUE">Xuất cho phiếu</option><option value="RETURN">Hoàn phụ tùng</option></select></label><span className="filter-result">{loading ? 'Đang tải…' : `${currentPage.totalElements} kết quả`}</span></div>
      {loading && <div className="inventory-loading" role="status">Đang tải lịch sử kho…</div>}
      {!loading && !error && currentPage.items.length === 0 && <div className="inventory-empty"><b>Chưa có biến động kho</b><span>Các giao dịch nhập, điều chỉnh, xuất và hoàn sẽ hiển thị tại đây.</span></div>}
      {currentPage.items.length > 0 && <div className="movement-list">{currentPage.items.map((item) => <MovementRow key={item.id} movement={item} />)}</div>}
      <div className="inventory-pager"><span>Trang {currentPage.totalPages === 0 ? 0 : currentPage.page + 1} / {currentPage.totalPages}</span><button className="outline" disabled={loading || currentPage.page <= 0} onClick={() => setFilters((value) => ({ ...value, page: value.page - 1 }))}>Trước</button><button className="outline" disabled={loading || currentPage.page + 1 >= currentPage.totalPages} onClick={() => setFilters((value) => ({ ...value, page: value.page + 1 }))}>Tiếp</button></div>
    </div>
  </div>;
}

function MovementRow({ movement }: { movement: StockMovement }) {
  const isIncrease = !movement.quantityDelta.startsWith('-');
  const shownDelta = movement.quantityDelta.startsWith('-') || movement.quantityDelta.startsWith('+') ? movement.quantityDelta : `${isIncrease ? '+' : ''}${movement.quantityDelta}`;
  return <article className="movement-row"><div className={`movement-icon ${isIncrease ? 'increase' : 'decrease'}`}>{isIncrease ? '＋' : '−'}</div><div className="movement-main"><div className="movement-topline"><div><span className={`movement-type movement-${movement.movementType.toLowerCase()}`}>{movementLabels[movement.movementType]}</span><b>{movement.partCode}</b></div><time dateTime={movement.createdAt}>{formatDate(movement.createdAt)}</time></div><p className="movement-reason">{movement.reason}</p>{movement.note && <p className="movement-note">Ghi chú: {movement.note}</p>}<div className="movement-meta"><span>Thực hiện bởi <b>{movement.actorDisplayName || `#${movement.actorId}`}</b></span>{movement.repairOrderId && <span>Phiếu sửa chữa <b>#{movement.repairOrderId}</b></span>}</div></div><div className="movement-balance"><b className={isIncrease ? 'positive' : 'negative'}>{shownDelta}</b><small>{movement.quantityBefore} → {movement.quantityAfter}</small></div></article>;
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('vi-VN', { timeZone: 'Asia/Ho_Chi_Minh', dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}
