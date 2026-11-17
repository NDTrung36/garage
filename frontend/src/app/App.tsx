import { useEffect, useState } from 'react';
import { api, ApiError, getCsrf, type GarageUser } from '../api/client';
import './app.css';

type UserRow = GarageUser & { active: boolean; authVersion: number };
type UserPage = { items: UserRow[]; page: number; size: number; total: number };

export function App() {
  const [user, setUser] = useState<GarageUser | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [loadError, setLoadError] = useState('');
  const [path, setPath] = useState(location.pathname);
  useEffect(() => {
    api<GarageUser>('/auth/me').then(setUser).catch((e:unknown) => { if(e instanceof ApiError && e.status===401) setUser(null); else setLoadError('Không kết nối được máy chủ. Vui lòng tải lại trang.'); }).finally(() => setLoading(false));
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
  if (loading) return <main className="shell">Đang kiểm tra phiên đăng nhập…</main>;
  if(loadError) return <main className="shell"><p className="error">{loadError}</p><button onClick={()=>location.reload()}>Thử lại</button></main>;
  if (!user) return <main className="login-shell"><form className="card login" onSubmit={login}><p className="eyebrow">GARAGE • QUẢN TRỊ</p><h1>Đăng nhập</h1><p className="muted">Dành cho nhân viên và quản trị viên garage.</p><label>Tên đăng nhập<input name="username" autoComplete="username" required minLength={3} /></label><label>Mật khẩu<input name="password" type="password" autoComplete="current-password" required /></label>{error && <p className="error">{error}</p>}<button>Đăng nhập</button></form></main>;
  return <div className="layout"><aside><div className="brand">GARAGE<span>WORKSHOP</span></div><button className="nav" onClick={() => navigate('/staff')}>Tổng quan</button>{user.role === 'ADMIN' && <button className="nav" onClick={() => navigate('/admin/users')}>Tài khoản nhân viên</button>}<div className="profile"><b>{user.displayName}</b><small>{user.role === 'ADMIN' ? 'Quản trị viên' : 'Nhân viên'}</small><button className="outline" onClick={() => void logout()}>Đăng xuất</button></div></aside><main className="content"><header><span>Garage sửa xe</span><span>{user.displayName}</span></header>{path.startsWith('/admin/users') && user.role === 'ADMIN' ? <UsersPage /> : <section><p className="eyebrow">KHÔNG GIAN LÀM VIỆC</p><h1>Xin chào, {user.displayName}</h1><p className="muted">Đăng nhập và phân quyền nhân viên đã sẵn sàng. Các nghiệp vụ garage sẽ được bổ sung ở các feature tiếp theo.</p></section>}</main></div>;
}

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
