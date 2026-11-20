import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError, type GarageUser } from '../../api/client';
import { formatGarageTime } from '../booking/bookingApi';
import { IntakeFields } from '../booking/WalkInForm';
import { readIntakeDraft, type IntakeDraft } from '../booking/intakeApi';
import type { Part } from '../part/types';
import { LaborItemsForm, type RunRepairCommand } from './LaborItemsForm';
import { RepairPartsPanel } from './RepairPartsPanel';
import { listIssueParts } from './partApi';
import { completeRepair, getRepairOrder, patchIntake, repairError, vnd, type RepairOrderDetail } from './repairApi';

export function RepairOrderDetailPage({ id, user, onNavigate }: { id: string; user: GarageUser; onNavigate: (path: string) => void }) {
  const [detail, setDetail] = useState<RepairOrderDetail | null>(null);
  const [parts, setParts] = useState<Part[]>([]);
  const [partsError, setPartsError] = useState('');
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [stockBusy, setStockBusy] = useState(false);
  const [confirmVersion, setConfirmVersion] = useState<number | null>(null);
  const dialog = useRef<HTMLDialogElement>(null);
  const read = useRef<AbortController | null>(null);
  const writing = useRef(false);
  const mounted = useRef(false);

  const refresh = useCallback(async () => {
    if (!mounted.current) throw new DOMException('Phiếu đã đóng', 'AbortError');
    read.current?.abort();
    const controller = new AbortController();
    read.current = controller;
    setLoading(true);
    setLoadError('');
    setPartsError('');
    try {
      const fresh = await getRepairOrder(id, controller.signal);
      controller.signal.throwIfAborted();
      setDetail(fresh);
      if (fresh.status === 'IN_PROGRESS') {
        try {
          const catalog = await listIssueParts(fresh.vehicleType, controller.signal);
          if (!controller.signal.aborted) setParts(catalog.items);
        } catch (cause) {
          if (!controller.signal.aborted) {
            setParts([]);
            setPartsError(repairError(cause));
          }
        }
      }
      controller.signal.throwIfAborted();
      return fresh;
    } catch (cause) {
      if (!controller.signal.aborted) setLoadError(repairError(cause));
      throw cause;
    } finally {
      if (!controller.signal.aborted) setLoading(false);
    }
  }, [id]);
  const reload = useCallback(async () => { await refresh(); }, [refresh]);

  useEffect(() => {
    mounted.current = true;
    void reload().catch(() => {});
    return () => {
      mounted.current = false;
      read.current?.abort();
    };
  }, [reload]);

  const run: RunRepairCommand = async (command) => {
    if (writing.current || stockBusy || loading || loadError || detail?.status !== 'IN_PROGRESS') return false;
    writing.current = true;
    setBusy(true);
    setMessage('');
    try {
      try { await command(); }
      catch (cause) {
        if (!mounted.current) return false;
        const stale = cause instanceof ApiError && (cause.code === 'STALE_VERSION' || cause.code === 'REPAIR_NOT_IN_PROGRESS');
        setMessage(stale ? 'Phiếu đã thay đổi. Hãy đối chiếu thông tin mới trước khi gửi lại.' : repairError(cause));
        if (stale || !(cause instanceof ApiError) || cause.status >= 500) await reload().catch(() => {});
        return false;
      }
      if (mounted.current) await reload().catch(() => {});
      return true;
    } finally {
      writing.current = false;
      if (mounted.current) setBusy(false);
    }
  };

  const back = <button className="text-button" onClick={() => onNavigate('/staff/repair-orders')}>← Danh sách phiếu</button>;
  if (!detail) return <div>{back}{loading ? <p role="status">Đang tải phiếu…</p> : <div className="inventory-error-panel" role="alert">
    <span>{loadError}</span><button className="outline" onClick={() => void reload().catch(() => {})}>Tải lại</button>
  </div>}</div>;

  const completed = detail.status === 'COMPLETED';
  const disabled = loading || busy || stockBusy || Boolean(loadError);
  const totals = detail.totals;
  return <div className="repair-order-page inventory-page">
    {back}
    <section className="repair-order-hero card">
      <div><p className="eyebrow">{detail.bookingCode} · {detail.bookingSource === 'WALK_IN' ? 'Tại gara' : 'Đặt lịch'}</p>
        <h1>Phiếu #{detail.repairOrderId}</h1>
        <p className="muted">{detail.contactName} · {detail.contactPhone} · {detail.plate} · {detail.vehicleType === 'CAR' ? 'Ô tô' : 'Xe máy'} {detail.brand} {detail.model}</p></div>
      <div className="repair-order-hero-meta">
        <span className={`repair-order-state ${completed ? 'complete' : ''}`}>{completed ? 'Đã chốt · chỉ xem' : 'Đang sửa'}</span>
        <span>Phiên bản phiếu <b>{detail.orderVersion}</b></span>
        <button className="outline" disabled={loading || busy} onClick={() => void reload().catch(() => {})}>{loading ? 'Đang tải…' : 'Làm mới phiếu'}</button>
      </div>
    </section>
    {loadError && <p className="inventory-error-panel" role="alert">{loadError} Hãy làm mới phiếu trước khi tiếp tục.</p>}
    {message && <p className="intake-feedback" role="alert">{message}</p>}
    <div className="repair-order-overview">
      <div className="card"><span>Công việc</span><b>{vnd(totals.laborAmount)}</b></div>
      <div className="card"><span>Phụ tùng</span><b>{vnd(totals.partAmount)}</b></div>
      <div className="card total"><span>Tổng phiếu</span><b>{vnd(totals.totalAmount)}</b></div>
    </div>
    {detail.invoice && <section className="card repair-info" aria-label="Hóa đơn">
      <h2>Hóa đơn {detail.invoice.code}</h2>
      <p><b>{vnd(detail.invoice.totalAmount)}</b> · {detail.invoice.paymentStatus === 'PAID' ? 'Đã thanh toán' : 'Chưa thanh toán'}</p>
      <p className="muted">Lập lúc {formatGarageTime(detail.invoice.issuedAt)}</p>
      <button className="outline" onClick={() => onNavigate(`/staff/invoices/${detail.invoice!.id}`)}>Xem hóa đơn</button>
    </section>}
    <IntakeInfo detail={detail} disabled={disabled} run={run} />
    <section className="card repair-info">
      <h2>Dịch vụ khách yêu cầu</h2>
      {detail.requestedServices.length ? <ul>{detail.requestedServices.map((service) => (
        <li key={service.id}>{service.name} · giá tham khảo {vnd(service.referencePrice)}</li>
      ))}</ul> : <p className="muted">Không có dịch vụ đặt trước.</p>}
    </section>
    <LaborItemsForm detail={detail} disabled={disabled} run={run} />
    <RepairPartsPanel detail={detail} issueParts={parts} partsError={partsError} partsLoading={loading} userId={user.id}
      onRefresh={refresh} onChanged={reload} onRefreshParts={reload} disabled={busy || Boolean(loadError)}
      onBusyChange={setStockBusy} />
    {!completed && <section className="card repair-info">
      <h2>Hoàn tất sửa chữa</h2>
      <button disabled={disabled} onClick={() => {
        setConfirmVersion(detail.orderVersion);
        dialog.current?.showModal();
      }}>Chốt phiếu và lập hóa đơn</button>
      <dialog ref={dialog} className="card repair-complete-dialog" aria-labelledby="complete-title">
        <h2 id="complete-title">Chốt phiếu #{id}?</h2>
        <p>Hóa đơn sẽ được lập với tổng tiền <b>{vnd(totals.totalAmount)}</b>. Sau khi chốt, phiếu chỉ có thể xem.</p>
        <div className="repair-inline-actions">
          <form method="dialog"><button className="outline" autoFocus>Quay lại</button></form>
          <button disabled={disabled || confirmVersion !== detail.orderVersion} onClick={() => {
            if (confirmVersion !== detail.orderVersion) return;
            dialog.current?.close();
            void run(async () => {
              const result = await completeRepair(id, detail.orderVersion);
              if (!result?.invoice?.code) throw new Error('Thiếu hóa đơn');
              if (!mounted.current) return;
              read.current?.abort();
              setDetail({ ...detail, status: 'COMPLETED', bookingStatus: 'COMPLETED', invoice: result.invoice,
                orderVersion: result.version, completedAt: result.completedAt,
                totals: { ...detail.totals, totalAmount: result.invoice.totalAmount } });
            });
          }}>Xác nhận chốt phiếu</button>
        </div>
      </dialog>
    </section>}
  </div>;
}

function IntakeInfo({ detail, disabled, run }: { detail: RepairOrderDetail; disabled: boolean; run: RunRepairCommand }) {
  const [draft, setDraft] = useState<IntakeDraft | null>(null);
  const [version, setVersion] = useState(detail.orderVersion);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const readOnly = detail.status === 'COMPLETED';
  const stale = version !== detail.orderVersion;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!draft || stale || readOnly || disabled) return;
    const { details, errors: validation } = readIntakeDraft(draft);
    setErrors(validation);
    if (Object.keys(validation).length) return;
    if (await run(() => patchIntake(detail.repairOrderId, { odometerKm: details.odometerKm,
      conditionNotes: details.conditionNotes.trim() || null, internalNotes: details.internalNotes.trim() || null }, version))) setDraft(null);
  }
  return <section className="card repair-info">
    <h2>Hồ sơ tiếp nhận</h2>
    <dl className="repair-facts">
      <div><dt>Thời điểm nhận</dt><dd>{formatGarageTime(detail.receivedAt)}</dd></div>
      <div><dt>Nhân viên tiếp nhận</dt><dd>#{detail.receivedBy}</dd></div>
      <div><dt>Số km hiện tại</dt><dd>{detail.odometerKm == null ? '—' : `${detail.odometerKm} km`}</dd></div>
      <div><dt>Tình trạng xe</dt><dd>{detail.conditionNotes || '—'}</dd></div>
      <div><dt>Ghi chú nội bộ</dt><dd>{detail.internalNotes || '—'}</dd></div>
      {detail.completedAt && <div><dt>Hoàn tất</dt><dd>{formatGarageTime(detail.completedAt)}{detail.completedBy && ` · nhân viên #${detail.completedBy}`}</dd></div>}
    </dl>
    {!readOnly && (draft ? <form className="booking-form repair-edit-form" onSubmit={(event) => void submit(event)}>
      <fieldset disabled={disabled}>
        <legend>Sửa thông tin nhận xe</legend>
        {stale && <p className="intake-feedback">Hãy đối chiếu với hồ sơ mới ở trên. <button type="button" className="outline"
          onClick={() => setVersion(detail.orderVersion)}>Đã đối chiếu hồ sơ</button></p>}
        <IntakeFields draft={draft} onChange={setDraft} errors={errors} />
        <div className="repair-inline-actions">
          <button type="button" className="outline" onClick={() => setDraft(null)}>Hủy sửa</button>
          <button disabled={stale}>Lưu thông tin nhận xe</button>
        </div>
      </fieldset>
    </form> : <button className="outline" disabled={disabled} onClick={() => {
      setVersion(detail.orderVersion); setErrors({});
      setDraft({ odometerKm: detail.odometerKm?.toString() ?? '', conditionNotes: detail.conditionNotes ?? '', internalNotes: detail.internalNotes ?? '' });
    }}>Sửa thông tin nhận xe</button>)}
  </section>;
}
