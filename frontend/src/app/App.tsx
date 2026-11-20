import { useEffect, useState } from 'react';
import { api, ApiError, getCsrf, type GarageUser } from '../api/client';
import { BookingPage } from '../features/booking/BookingPage';
import { IntakePage } from '../features/booking/IntakePage';
import { WalkInForm } from '../features/booking/WalkInForm';
import { LookupPage } from '../features/lookup/LookupPage';
import { PartsPage } from '../features/part/PartsPage';
import { StockMovementsPage } from '../features/inventory/StockMovementsPage';
import { RepairOrdersPage } from '../features/repair/RepairOrdersPage';
import { RepairOrderDetailPage } from '../features/repair/RepairOrderDetailPage';
import { InvoicesPage } from '../features/invoice/InvoicesPage';
import { InvoicePage } from '../features/invoice/InvoicePage';
import './app.css';
import '../features/inventory/inventory.css';

type UserRow = GarageUser & { active: boolean; authVersion: number };
type UserPage = { items: UserRow[]; page: number; size: number; total: number };

export function App() {
  const [user, setUser] = useState<GarageUser | null>(null);
  const [loading, setLoading] = useState(() => !isPublicPath(location.pathname));
  const [error, setError] = useState('');
  const [loadError, setLoadError] = useState('');
  const [path, setPath] = useState(location.pathname);
  useEffect(() => {
    setLoadError('');
    if (isPublicPath(path)) {
      setUser(null);
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    const { signal } = controller;
    setLoading(true);
    api<GarageUser>('/auth/me', { signal })
      .then((current) => { if (!signal.aborted) setUser(current); })
      .catch((cause: unknown) => {
        if (signal.aborted) return;
        if (cause instanceof ApiError && cause.status === 401) setUser(null);
        else setLoadError('Không kết nối được máy chủ. Vui lòng tải lại trang.');
      })
      .finally(() => { if (!signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [path]);

  useEffect(() => {
    const onPop = () => setPath(location.pathname);
    const onUnauthorized = () => setUser(null);
    addEventListener('popstate', onPop);
    addEventListener('garage:unauthorized', onUnauthorized);
    return () => { removeEventListener('popstate', onPop); removeEventListener('garage:unauthorized', onUnauthorized); };
  }, []);
  async function login(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault(); setError('');
    const data = new FormData(e.currentTarget);
    try { await getCsrf(); const result = await api<GarageUser>('/auth/login', { method: 'POST', body: JSON.stringify({ username: data.get('username'), password: data.get('password') }) }); setUser(result); }
    catch (err) { setError(err instanceof Error ? err.message : 'Không đăng nhập được.'); }
  }
  async function logout() { await getCsrf(); await api('/auth/logout', { method: 'POST' }); setUser(null); }
  function navigate(next: string) { history.pushState({}, '', next); setPath(next); }
  if (isPublicPath(path)) {
    if (path === '/booking') return <BookingPage onNavigate={navigate} />;
    if (path === '/lookup') return <LookupPage onNavigate={navigate} />;
    return <main className="public-shell public-home"><nav className="public-nav"><b>GARAGE WORKSHOP</b><button className="outline" onClick={() => navigate('/lookup')}>Tra cứu lịch</button></nav><section className="home-hero"><p className="eyebrow">GARAGE SỬA Ô TÔ & XE MÁY</p><h1>Chăm xe dễ dàng,<br/>đặt lịch nhanh chóng.</h1><p className="muted">Chọn dịch vụ và khung giờ phù hợp. Không cần tạo tài khoản.</p><div><button onClick={() => navigate('/booking')}>Đặt lịch sửa xe</button><button className="outline" onClick={() => navigate('/lookup')}>Tra cứu lịch đã đặt</button></div></section><footer>Garage Workshop · Phục vụ ô tô và xe máy <button className="text-button" onClick={() => navigate('/staff')}>Nhân viên Garage</button></footer></main>;
  }
  if (loading) return <main className="shell">Đang kiểm tra phiên đăng nhập…</main>;
  if(loadError) return <main className="shell"><p className="error">{loadError}</p><button onClick={()=>location.reload()}>Thử lại</button></main>;
  if (!user) return <main className="login-shell"><form className="card login" onSubmit={login}><p className="eyebrow">GARAGE • QUẢN TRỊ</p><h1>Đăng nhập</h1><p className="muted">Dành cho nhân viên và quản trị viên garage.</p><label>Tên đăng nhập<input name="username" autoComplete="username" required minLength={3} /></label><label>Mật khẩu<input name="password" type="password" autoComplete="current-password" required /></label>{error && <p className="error">{error}</p>}<button>Đăng nhập</button></form></main>;
  const adminPartsPath = path === '/admin/parts';
  const staffPartsPath = path === '/staff/parts';
  const stockPath = path === '/staff/stock-movements';
  const usersPath = path.startsWith('/admin/users');
  const repairsPath = path === '/staff/repair-orders';
  const invoicesPath = path === '/staff/invoices';
  const bookingsPath = path === '/staff/bookings';
  const walkInsPath = path === '/staff/walk-ins';
  const repairId = path.match(/^\/staff\/repair-orders\/([1-9]\d*)$/)?.[1];
  const invoiceId = path.match(/^\/staff\/invoices\/([1-9]\d*)$/)?.[1];
  return <div className="layout"><aside><div className="brand">GARAGE<span>WORKSHOP</span></div><button className="nav" aria-current={path === '/staff' ? 'page' : undefined} onClick={() => navigate('/staff')}>Tổng quan</button><button className="nav" aria-current={bookingsPath ? 'page' : undefined} onClick={() => navigate('/staff/bookings')}>Lịch tiếp nhận xe</button><button className="nav" aria-current={walkInsPath ? 'page' : undefined} onClick={() => navigate('/staff/walk-ins')}>Tiếp nhận tại gara</button><button className="nav" aria-current={staffPartsPath || adminPartsPath ? 'page' : undefined} onClick={() => navigate(user.role === 'ADMIN' ? '/admin/parts' : '/staff/parts')}>Phụ tùng & tồn kho</button><button className="nav" aria-current={stockPath ? 'page' : undefined} onClick={() => navigate('/staff/stock-movements')}>Lịch sử kho</button><button className="nav" aria-current={repairsPath || Boolean(repairId) ? 'page' : undefined} onClick={() => navigate('/staff/repair-orders')}>Phiếu sửa chữa</button><button className="nav" aria-current={invoicesPath || Boolean(invoiceId) ? 'page' : undefined} onClick={() => navigate('/staff/invoices')}>Hóa đơn</button>{user.role === 'ADMIN' && <button className="nav" aria-current={usersPath ? 'page' : undefined} onClick={() => navigate('/admin/users')}>Tài khoản nhân viên</button>}<div className="profile"><b>{user.displayName}</b><small>{user.role === 'ADMIN' ? 'Quản trị viên' : 'Nhân viên'}</small><button className="outline" onClick={() => void logout()}>Đăng xuất</button></div></aside><main className="content"><header><span>Garage sửa xe</span><span>{user.displayName}</span></header>{usersPath && user.role === 'ADMIN' ? <UsersPage /> : adminPartsPath && user.role === 'ADMIN' ? <PartsPage admin /> : (adminPartsPath && user.role !== 'ADMIN') ? <section className="inventory-access-denied"><p className="eyebrow">KHÔNG ĐỦ QUYỀN</p><h1>Trang này dành cho quản trị viên</h1><p className="muted">Bạn có thể xem danh mục và tồn kho tại trang phụ tùng.</p><button onClick={() => navigate('/staff/parts')}>Mở phụ tùng và tồn kho</button></section> : staffPartsPath ? <PartsPage admin={false} /> : stockPath ? <StockMovementsPage user={user} /> : bookingsPath ? <IntakePage onNavigate={navigate} /> : walkInsPath ? <WalkInForm key={user.id} userId={user.id} onNavigate={navigate} /> : repairsPath ? <RepairOrdersPage onNavigate={navigate} /> : repairId ? <RepairOrderDetailPage key={`${user.id}:${repairId}`} id={repairId} user={user} onNavigate={navigate} /> : invoicesPath ? <InvoicesPage onNavigate={navigate} /> : invoiceId ? <InvoicePage key={`${user.id}:${invoiceId}`} id={invoiceId} user={user} onNavigate={navigate} /> : <section><p className="eyebrow">KHÔNG GIAN LÀM VIỆC</p><h1>Xin chào, {user.displayName}</h1><p className="muted">Chọn Lịch tiếp nhận xe để xem lịch hẹn hoặc Tiếp nhận tại gara để tạo phiếu cho khách đến trực tiếp.</p></section>}</main></div>;
}

function isPublicPath(path: string) { return path === '/' || path === '/booking' || path === '/lookup'; }

function UsersPage() {
  const [page, setPage] = useState<UserPage | null>(null); const [pageIndex, setPageIndex] = useState(0); const [message, setMessage] = useState(''); const [resetUser,setResetUser]=useState<UserRow|null>(null);
  const refresh = () => api<UserPage>(`/admin/users?page=${pageIndex}&size=20`).then(setPage).catch(e => setMessage(String(e)));
  useEffect(() => { void refresh(); }, [pageIndex]);
  async function create(e: React.FormEvent<HTMLFormElement>) { e.preventDefault(); const form=e.currentTarget; const d=new FormData(form); try { await api('/admin/users',{method:'POST',body:JSON.stringify({username:d.get('username'),displayName:d.get('displayName'),role:d.get('role'),password:d.get('password')})}); form.reset();setMessage('Đã tạo tài khoản.');void refresh(); } catch(e) {setMessage(e instanceof Error?e.message:'Không tạo được tài khoản.');} }
  async function change(u: UserRow, changes: {displayName?:string; role?:GarageUser['role']; active?:boolean}) { try { await api(`/admin/users/${u.id}`,{method:'PATCH',body:JSON.stringify({...changes,expectedAuthVersion:u.authVersion})});setMessage('Đã cập nhật tài khoản.');void refresh();} catch(e) {if(e instanceof ApiError&&e.status===409&&e.code==='STALE_AUTH_VERSION'){setMessage('Tài khoản đã được thay đổi. Đang tải dữ liệu mới.');void refresh();}else setMessage(e instanceof Error?e.message:'Cập nhật thất bại.');} }
  async function reset(e: React.FormEvent<HTMLFormElement>) { e.preventDefault(); if(!resetUser)return; const password=new FormData(e.currentTarget).get('password') as string; try { await api(`/admin/users/${resetUser.id}/reset-password`,{method:'POST',body:JSON.stringify({password,expectedAuthVersion:resetUser.authVersion})});setMessage('Đã đặt lại mật khẩu.');setResetUser(null);void refresh();} catch(e) {if(e instanceof ApiError&&e.status===409&&e.code==='STALE_AUTH_VERSION'){setMessage('Tài khoản đã được thay đổi. Đang tải dữ liệu mới.');setResetUser(null);void refresh();}else setMessage(e instanceof Error?e.message:'Đặt lại mật khẩu thất bại.');} }
  async function rename(u: UserRow) { const displayName=prompt('Tên hiển thị',u.displayName);if(displayName===null)return;await change(u,{displayName}); }
  return <><section><p className="eyebrow">QUẢN TRỊ</p><h1>Tài khoản nhân viên</h1><p className="muted">Tạo tài khoản, phân quyền và thu hồi phiên đăng nhập.</p></section><form className="card create-form" onSubmit={create}><h2>Tạo tài khoản</h2><label>Tên đăng nhập<input name="username" required minLength={3} maxLength={100}/></label><label>Tên hiển thị<input name="displayName" required maxLength={100}/></label><label>Vai trò<select name="role"><option value="STAFF">Nhân viên</option><option value="ADMIN">Quản trị viên</option></select></label><label>Mật khẩu ban đầu<input name="password" type="password" required minLength={12}/></label><button>Tạo tài khoản</button></form>{message&&<p className="notice">{message}</p>}<div className="card table-wrap"><table><thead><tr><th>Tài khoản</th><th>Vai trò</th><th>Trạng thái</th><th>Thao tác</th></tr></thead><tbody>{page?.items.map(u=><tr key={u.id}><td><b>{u.displayName}</b><small>@{u.username}</small><button className="text-button" onClick={()=>void rename(u)}>Sửa tên</button></td><td><select value={u.role} onChange={e=>void change(u,{role:e.target.value as GarageUser['role']})}><option value="STAFF">Nhân viên</option><option value="ADMIN">Quản trị viên</option></select></td><td><button className="text-button" onClick={()=>void change(u,{active:!u.active})}>{u.active?'Đang hoạt động':'Đã khóa'}</button></td><td><button className="outline" onClick={()=>setResetUser(u)}>Đặt lại mật khẩu</button></td></tr>)}</tbody></table>{page?.items.length===0&&<p>Chưa có tài khoản.</p>}<div className="pager"><span>{page?.total ?? 0} tài khoản</span><button className="outline" disabled={pageIndex===0} onClick={()=>setPageIndex(v=>v-1)}>Trước</button><button className="outline" disabled={!page || (pageIndex+1)*page.size>=page.total} onClick={()=>setPageIndex(v=>v+1)}>Tiếp</button></div></div>{resetUser&&<div className="modal-backdrop"><form className="card reset-form" onSubmit={reset}><h2>Đặt lại mật khẩu</h2><p>{resetUser.displayName} · @{resetUser.username}</p><label>Mật khẩu mới<input name="password" type="password" minLength={12} maxLength={72} required autoComplete="new-password" autoFocus/></label><div><button type="button" className="outline" onClick={()=>setResetUser(null)}>Hủy</button><button type="submit">Lưu mật khẩu</button></div></form></div>}</>;
}
