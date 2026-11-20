import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../../api/client';
import { createPart, listParts, updatePart } from './api';
import { PartForm } from './PartForm';
import type { Part, PartFilters, PartPageData, PartPayload } from './types';

type Props = { admin: boolean };
const emptyPage: PartPageData = { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };

export function PartsPage({ admin }: Props) {
  const [filters, setFilters] = useState<PartFilters>({ page: 0, size: 20, q: '', active: '', vehicleType: '' });
  const [search, setSearch] = useState('');
  const [data, setData] = useState<PartPageData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [editing, setEditing] = useState<Part | null | undefined>(undefined);
  const [saving, setSaving] = useState(false);
  const endpoint = admin ? '/admin/parts' : '/staff/parts';

  useEffect(() => {
    const timer = window.setTimeout(() => setFilters((current) => ({ ...current, page: 0, q: search.trim() })), 250);
    return () => window.clearTimeout(timer);
  }, [search]);

  const refresh = useCallback(async (signal?: AbortSignal) => {
    const params = new URLSearchParams({ page: String(filters.page), size: String(filters.size) });
    if (filters.q) params.set('q', filters.q);
    if (filters.active) params.set('active', filters.active);
    if (filters.vehicleType) params.set('vehicleType', filters.vehicleType);
    setLoading(true); setError('');
    try { const next = await listParts(endpoint, params, signal); if (!signal?.aborted) setData(next); }
    catch (cause) { if (!signal?.aborted) setError(cause instanceof Error ? cause.message : 'Không tải được danh mục.'); }
    finally { if (!signal?.aborted) setLoading(false); }
  }, [endpoint, filters]);

  useEffect(() => { const controller = new AbortController(); void refresh(controller.signal); return () => controller.abort(); }, [refresh]);

  async function save(payload: PartPayload) {
    if (editing === undefined) return;
    setSaving(true); setNotice('');
    try {
      if (editing) await updatePart(editing.id, payload); else await createPart(payload);
      setEditing(undefined); setNotice(editing ? 'Thông tin phụ tùng đã được cập nhật.' : 'Đã thêm phụ tùng.');
      await refresh();
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 409 && (cause.code === 'STALE_VERSION' || cause.code === 'STALE_PART_VERSION' || cause.code === 'PART_UNIT_LOCKED')) {
        await refresh();
        setEditing(undefined);
        setNotice(cause.code === 'PART_UNIT_LOCKED'
          ? 'Không thể đổi đơn vị vì phụ tùng đã có biến động kho. Danh mục đã được tải lại.'
          : 'Phụ tùng đã được thay đổi ở nơi khác. Danh mục mới nhất đã được tải lại.');
      }
      throw cause;
    } finally { setSaving(false); }
  }

  const page = data ?? emptyPage;
  return <div className="inventory-page">
    <section className="inventory-page-heading"><div><p className="eyebrow">KHO PHỤ TÙNG</p><h1>{admin ? 'Danh mục phụ tùng' : 'Phụ tùng và tồn kho'}</h1><p className="muted">Tra cứu số dư hiện tại và thông tin phụ tùng của garage.</p></div>{admin && <button onClick={() => setEditing(null)}>＋ Thêm phụ tùng</button>}</section>
    {notice && <p className="inventory-notice" role="status">{notice}</p>}
    <div className="inventory-toolbar card"><label className="inventory-search">Tìm theo mã hoặc tên<input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="VD: lọc nhớt" /></label><label>Loại xe<select value={filters.vehicleType} onChange={(event) => setFilters((value) => ({ ...value, page: 0, vehicleType: event.target.value }))}><option value="">Tất cả</option><option value="CAR">Ô tô</option><option value="MOTORBIKE">Xe máy</option></select></label><label>Trạng thái<select value={filters.active} onChange={(event) => setFilters((value) => ({ ...value, page: 0, active: event.target.value }))}><option value="">Tất cả</option><option value="true">Đang kinh doanh</option><option value="false">Ngừng kinh doanh</option></select></label></div>
    {error && <div className="inventory-error-panel" role="alert"><span>{error}</span><button className="outline" onClick={() => void refresh()}>Thử tải lại</button></div>}
    <div className="inventory-table-card card">
      <div className="inventory-table-title"><div><h2>Phụ tùng</h2><span>{page.totalElements} mặt hàng</span></div><button className="outline" onClick={() => void refresh()} disabled={loading}>Làm mới</button></div>
      {loading && <div className="inventory-loading" role="status">Đang tải danh mục…</div>}
      {!loading && !error && page.items.length === 0 && <div className="inventory-empty"><b>Chưa có phụ tùng phù hợp</b><span>Thử đổi bộ lọc hoặc tìm bằng từ khóa khác.</span></div>}
      {page.items.length > 0 && <div className="inventory-overflow"><table className="inventory-table"><thead><tr><th>Phụ tùng</th><th>Loại xe</th><th>Tồn hiện tại</th><th>Giá tham khảo</th><th>Trạng thái</th>{admin && <th />}</tr></thead><tbody>{page.items.map((part) => <tr key={part.id}><td><b>{part.name}</b><small>{part.code} · {part.unit}</small></td><td>{vehicleLabel(part.vehicleType)}</td><td className="stock-cell">{part.stockQuantity} <small>{part.unit}</small></td><td>{part.referencePrice.toLocaleString('vi-VN')} ₫</td><td><span className={`part-status ${part.active ? 'active' : 'inactive'}`}>{part.active ? 'Đang kinh doanh' : 'Ngừng kinh doanh'}</span></td>{admin && <td><button className="text-button" onClick={() => setEditing(part)}>Sửa</button></td>}</tr>)}</tbody></table></div>}
      <div className="inventory-pager"><span>Trang {page.totalPages === 0 ? 0 : page.page + 1} / {page.totalPages}</span><button className="outline" disabled={loading || page.page <= 0} onClick={() => setFilters((value) => ({ ...value, page: value.page - 1 }))}>Trước</button><button className="outline" disabled={loading || page.page + 1 >= page.totalPages} onClick={() => setFilters((value) => ({ ...value, page: value.page + 1 }))}>Tiếp</button></div>
    </div>
    {editing !== undefined && <PartForm part={editing} busy={saving} onSave={save} onCancel={() => setEditing(undefined)} />}
  </div>;
}

function vehicleLabel(type: Part['vehicleType']) { return type === 'BOTH' ? 'Ô tô · xe máy' : type === 'CAR' ? 'Ô tô' : 'Xe máy'; }
