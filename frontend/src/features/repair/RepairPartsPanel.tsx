import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../../api/client';
import type { Part } from '../part/types';
import { useStockCommand } from '../inventory/useStockCommand';
import { RepairPartIssueForm } from './RepairPartIssueForm';
import { RepairPartPriceForm } from './RepairPartPriceForm';
import { RepairPartReturnForm } from './RepairPartReturnForm';
import { updateRepairPartPrice, type RepairPartItem, type ReturnableIssue } from './partApi';
import { repairError, type RepairOrderDetail } from './repairApi';

type Props = { detail: RepairOrderDetail; issueParts: Part[]; partsError: string; partsLoading: boolean; userId: string; onRefresh: () => Promise<RepairOrderDetail>; onChanged: () => Promise<void>; onRefreshParts: () => Promise<void>; disabled: boolean; onBusyChange: (busy: boolean) => void };
type PriceAttempt = { itemId: string; unitPrice: number; reason: string; expectedVersion: number; confirmedVersion?: number };

export function RepairPartsPanel({ detail, issueParts, partsError, partsLoading, userId, onRefresh, onChanged, onRefreshParts, disabled, onBusyChange }: Props) {
  const readOnly = detail.status !== 'IN_PROGRESS';
  const [priceItemId, setPriceItemId] = useState<string | null>(null);
  const [priceNotice, setPriceNotice] = useState('');
  const [priceAttempt, setPriceAttempt] = useState<PriceAttempt | null>(null);
  const [priceBusy, setPriceBusy] = useState(false);

  const refreshAfterConflict = useCallback(() => { void onRefresh().catch(() => {}); }, [onRefresh]);
  const command = useStockCommand(userId, onChanged, 'repair:' + detail.repairOrderId, refreshAfterConflict);
  const unresolved = Boolean(command.attempt);
  const busy = command.busy || priceBusy;
  const locked = readOnly || disabled || busy || unresolved || partsLoading;
  useEffect(() => {
    onBusyChange(!readOnly && (busy || unresolved || Boolean(priceAttempt) || priceItemId !== null));
  }, [busy, unresolved, priceAttempt, priceItemId, readOnly, onBusyChange]);

  const issuePath = `/staff/repair-orders/${detail.repairOrderId}/part-issues`;
  const returnPath = `/staff/repair-orders/${detail.repairOrderId}/part-returns`;
  function issue(payload: { partId: string; quantity: string }) { if (locked || !window.confirm("Xác nhận xuất " + payload.quantity + " đơn vị phụ tùng cho phiếu này?")) return false; void command.execute(issuePath, payload); return true; }
  function returnPart(payload: { originalIssueId: string; quantity: string }) { if (locked || !window.confirm("Xác nhận hoàn " + payload.quantity + " theo lần xuất #" + payload.originalIssueId + "?")) return false; void command.execute(returnPath, payload); return true; }

  async function savePrice(unitPrice: number, reason: string, expectedVersion: number) {
    if (!priceItemId) return;
    const attempt: PriceAttempt = { itemId: priceItemId, unitPrice, reason, expectedVersion };
    setPriceBusy(true); setPriceNotice('');
    try {
      await updateRepairPartPrice(detail.repairOrderId, priceItemId, { unitPrice, reason, expectedVersion });
      setPriceItemId(null); setPriceAttempt(null); setPriceNotice('Giá snapshot trên phiếu đã được cập nhật.');
      await onChanged().catch(() => {});
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 409) {
        try {
          const fresh = await onRefresh();
          const current = fresh.partItems.find((item) => item.id === priceItemId);
          if (fresh.status === 'COMPLETED') {
            setPriceItemId(null); setPriceAttempt(null); setPriceNotice('Phiếu đã chốt. Đã tải lại chi tiết ở chế độ chỉ xem.');
          } else if (current?.unitPrice === unitPrice && fresh.orderVersion > expectedVersion) {
            setPriceItemId(null); setPriceAttempt(null); setPriceNotice('Giá đã được cập nhật trước khi phản hồi lỗi đến trình duyệt. Đã đối soát phiên bản mới nhất.');
            await onChanged().catch(() => {});
          } else {
            setPriceItemId(null); setPriceAttempt(null);
            setPriceNotice(cause.code === 'STALE_VERSION'
              ? 'Phiếu đã thay đổi. Đã tải lại giá và phiên bản mới nhất; mở lại sửa giá để xác nhận theo dữ liệu mới.'
              : cause.message);
          }
        } catch { setPriceNotice('Máy chủ báo dữ liệu phiếu đã thay đổi nhưng chưa tải lại được. Hãy làm mới chi tiết phiếu.'); }
      } else {
        if (cause instanceof ApiError && cause.status >= 400 && cause.status < 500) {
          setPriceNotice(repairError(cause));
          throw new Error(repairError(cause));
        }
        setPriceItemId(null); setPriceAttempt(attempt);
        setPriceNotice('Chưa rõ máy chủ đã cập nhật giá hay chưa. Hãy đối soát giá và phiên bản phiếu trước khi gửi lại.');
        try {
          const fresh = await onRefresh();
          const current = fresh.partItems.find((item) => item.id === priceItemId);
          if (current?.unitPrice === unitPrice && fresh.orderVersion > expectedVersion) {
            setPriceAttempt(null); setPriceNotice('Giá đã được cập nhật. Chi tiết và phiên bản mới nhất đã được tải lại.');
            await onChanged().catch(() => {});
          } else setPriceAttempt({ ...attempt, confirmedVersion: fresh.orderVersion });
        } catch { /* Keep the attempt and require another explicit reconciliation before retry. */ }
      }
      throw cause;
    } finally { setPriceBusy(false); }
  }

  async function reconcilePrice() {
    if (!priceAttempt) return;
    setPriceBusy(true);
    try {
      const fresh = await onRefresh();
      const current = fresh.partItems.find((item) => item.id === priceAttempt.itemId);
      if (fresh.status === 'COMPLETED') {
        setPriceAttempt(null); setPriceNotice('Phiếu đã chốt. Đã tải lại ở chế độ chỉ xem.');
      } else if (current?.unitPrice === priceAttempt.unitPrice && fresh.orderVersion > priceAttempt.expectedVersion) {
        setPriceAttempt(null); setPriceNotice('Giá đã được cập nhật; phiên bản phiếu đã được đối soát.'); await onChanged().catch(() => {});
      } else {
        setPriceAttempt({ ...priceAttempt, confirmedVersion: fresh.orderVersion });
        setPriceNotice('Giá hiện chưa khớp với lệnh đang chờ. Xem lại dữ liệu mới nhất trước khi xác nhận gửi lại.');
      }
    } catch (cause) { setPriceNotice(cause instanceof Error ? cause.message : 'Không tải được chi tiết để đối soát.'); }
    finally { setPriceBusy(false); }
  }

  function confirmPriceRetry() {
    if (!priceAttempt || priceAttempt.confirmedVersion == null || readOnly) return;
    setPriceItemId(priceAttempt.itemId);
  }

  const issueItemById = new Map(detail.partItems.map((item) => [item.id, item]));
  return <section className="repair-parts-panel card">
    <div className="repair-section-heading"><div><p className="eyebrow">PHỤ TÙNG TRÊN PHIẾU</p><h2>Vật tư đã sử dụng</h2><p className="muted">Số lượng là lượng ròng sau khi trừ các lần hoàn.</p></div><span className={`repair-order-state ${readOnly ? 'complete' : ''}`}>{readOnly ? 'Đã chốt · chỉ xem' : 'Đang sửa'}</span></div>
    {readOnly && <div className="repair-readonly-banner" role="status">Phiếu đã hoàn tất. Các dòng phụ tùng và lịch sử hoàn vẫn được xem; thao tác xuất, hoàn hoặc sửa giá đã khóa.</div>}
    {!readOnly && (command.message || unresolved) && <div className={unresolved ? 'inventory-retry-panel' : 'inventory-notice'} role={unresolved ? 'alert' : 'status'}>
      {unresolved ? <><div><b>Kết quả lệnh kho chưa rõ</b><p>{command.message || "Hãy gửi lại lệnh đã lưu để xác nhận kết quả."}</p></div><div><button className="outline" type="button" disabled={busy} onClick={() => void onRefresh().catch(() => {})}>Đối soát phiếu</button>{command.expired ? <button type="button" disabled={disabled || busy} onClick={() => {
          if (window.confirm('Đã hết thời hạn thử lại. Bạn đã đối soát phiếu và lịch sử kho?')) command.clearExpired();
        }}>Đã đối soát lệnh hết hạn</button> : <button type="button" disabled={disabled || busy || command.retryBlocked} onClick={command.retry}>{command.busy ? 'Đang thử lại…' : command.retryBlocked ? `Thử lại sau ${command.waitSeconds}s` : 'Thử lại cùng mã yêu cầu'}</button>}</div></> : <span>{command.message}</span>}
    </div>}
    {!readOnly && priceNotice && <div className={priceAttempt ? 'inventory-retry-panel' : 'inventory-notice'} role={priceAttempt ? 'alert' : 'status'}><div><p>{priceNotice}</p>{priceAttempt && <small>Giá yêu cầu {formatMoney(priceAttempt.unitPrice)} · phiên bản ban đầu {priceAttempt.expectedVersion}</small>}</div>{priceAttempt && <div><button type="button" className="outline" disabled={priceBusy} onClick={() => void reconcilePrice()}>Đối soát giá/phiên bản</button>{priceAttempt.confirmedVersion != null && !readOnly && <><button type="button" disabled={priceBusy} onClick={confirmPriceRetry}>Xác nhận gửi lại</button><button type="button" className="outline" onClick={() => { setPriceAttempt(null); setPriceNotice(""); }}>Kết thúc đối soát</button></>}</div>}</div>}
    {partsError && !readOnly && <div className="inventory-error-panel" role="alert"><span>{partsError}</span><button className="outline" onClick={() => void onRefreshParts().catch(() => {})}>Tải lại danh mục</button></div>}
    <div className="repair-part-items">
      {detail.partItems.length === 0 ? <div className="inventory-empty"><b>Chưa có phụ tùng trên phiếu</b><span>Khi xuất phụ tùng, hệ thống sẽ lưu snapshot giá vào phiếu này.</span></div> : detail.partItems.map((item) => <article className="repair-part-item" key={item.id}>
        <div className="repair-part-item-icon">▧</div><div className="repair-part-item-main"><div className="repair-part-item-title"><b>{item.description}</b><span className={isZero(item.quantity) ? 'repair-quantity-zero' : 'repair-quantity'}>{isZero(item.quantity) ? 'Đã hoàn hết · ' : ''}{item.quantity} {item.unit}</span></div><div className="repair-part-price-line"><span>Đơn giá snapshot <b>{formatMoney(item.unitPrice)}</b></span><span>Thành tiền <b>{formatMoney(item.lineTotal)}</b></span></div></div>{!readOnly && <button className="text-button" disabled={locked} onClick={() => { setPriceNotice(''); setPriceItemId(item.id); }}>Sửa giá</button>}
      </article>)}
    </div>
    <div className="repair-part-sum"><span>Tổng phụ tùng</span><b>{formatMoney(detail.totals.partAmount)}</b></div>
    {!readOnly && <div className="repair-stock-actions"><div className="repair-stock-action-card"><div><span className="repair-action-icon issue">↓</span><div><h3>Xuất phụ tùng</h3><p>Chọn phụ tùng đang kinh doanh, tương thích với {detail.vehicleType === 'CAR' ? 'ô tô' : 'xe máy'}.</p></div></div><RepairPartIssueForm parts={issueParts} disabled={locked} onSubmit={issue} /></div><div className="repair-stock-action-card"><div><span className="repair-action-icon return">↗</span><div><h3>Hoàn phụ tùng</h3><p>Chọn đúng lần xuất gốc. Phụ tùng đã ngừng kinh doanh vẫn hoàn được.</p></div></div><RepairPartReturnForm issues={detail.returnableIssues} partItems={detail.partItems} disabled={locked} onSubmit={returnPart} /></div></div>}
    {!readOnly && priceItemId && detail.partItems.find((item) => item.id === priceItemId) && <RepairPartPriceForm key={`${priceItemId}-${detail.orderVersion}-${priceAttempt?.confirmedVersion ?? ''}`} item={detail.partItems.find((item) => item.id === priceItemId) as RepairPartItem} orderVersion={detail.orderVersion} disabled={readOnly || priceBusy || unresolved} initialPrice={priceAttempt?.unitPrice == null ? undefined : String(priceAttempt.unitPrice)} initialReason={priceAttempt?.reason} confirmRetry={priceAttempt?.confirmedVersion != null} onSave={savePrice} onCancel={() => setPriceItemId(null)} />}
    <details className="repair-issue-history"><summary>Lịch sử xuất và hoàn · {detail.returnableIssues.length} lần xuất</summary>{detail.returnableIssues.length ? <div className="repair-issue-list">{detail.returnableIssues.map((issue: ReturnableIssue) => {
      const part = issueItemById.get(issue.repairItemId);
      return <div key={issue.id}><span><b>#{issue.id}</b> · {part?.description ?? `Phụ tùng #${issue.partId}`} · {formatTime(issue.createdAt)}</span><span>Đã xuất {issue.issuedQuantity} · Còn hoàn {issue.remainingQuantity}</span></div>;
    })}</div> : <p className="repair-form-hint">Chưa có lần xuất phụ tùng trên phiếu.</p>}</details>
  </section>;
}

function isZero(value: string) { return !/[1-9]/.test(value); }
function formatMoney(value: number) { return `${value.toLocaleString('vi-VN')} ₫`; }
function formatTime(value: string) { return new Intl.DateTimeFormat('vi-VN', { timeZone: 'Asia/Ho_Chi_Minh', dateStyle: 'short', timeStyle: 'short' }).format(new Date(value)); }
