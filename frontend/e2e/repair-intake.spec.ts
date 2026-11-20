import { randomUUID } from 'node:crypto';
import { expect, test, type APIRequestContext, type APIResponse, type Page } from '@playwright/test';

const orders = '/api/staff/repair-orders';
const partBody = { code: 'SC14-PART', name: 'Lọc dầu SC14', unit: 'cái', vehicleType: 'CAR', referencePrice: 101 };
const staffCredentials = { username: 'sc14_staff', password: 'Synthetic-SC14-Staff!' };

async function json<T>(response: APIResponse, status = 200): Promise<T> {
  expect(response.status(), `${response.url()} ${await response.text()}`).toBe(status);
  return response.json();
}

async function csrf(api: APIRequestContext) {
  const token = await json<{ headerName: string; token: string }>(await api.get('/api/auth/csrf'));
  return { [token.headerName]: token.token };
}

async function loginApi(api: APIRequestContext, credentials: { username: string; password: string }) {
  await json(await api.post('/api/auth/login', { headers: await csrf(api), data: credentials }));
}

async function loginUi(page: Page) {
  await page.getByLabel('Tên đăng nhập').fill(staffCredentials.username);
  await page.getByLabel('Mật khẩu').fill(staffCredentials.password);
  await page.getByRole('button', { name: 'Đăng nhập' }).click();
}

async function addLabor(page: Page, description: string) {
  await page.getByLabel('Mô tả công việc').fill(description);
  await page.getByLabel('Số lượng công').fill('0.500');
  await page.getByLabel('Đơn giá công (đồng)').fill('101');
  await page.getByRole('button', { name: 'Ghi công việc' }).click();
}

async function completeRepair(page: Page) {
  await page.getByRole('button', { name: 'Chốt phiếu và lập hóa đơn' }).press('Enter');
  await page.getByRole('dialog').getByRole('button', { name: 'Xác nhận chốt phiếu' }).click();
  await expect(page.getByRole('heading', { name: /Hóa đơn INV-/ })).toBeVisible();
}

function garageDate(days = 0) {
  return new Date(Date.now() + days * 86_400_000).toLocaleDateString('sv-SE', { timeZone: 'Asia/Ho_Chi_Minh' });
}

type Booking = { id: string; code: string; version: number; repairOrderId: string | null };
type Detail = { repairOrderId: string; orderVersion: number; status: string; odometerKm: number;
  partItems: { id: string; quantity: string; unitPrice: number }[];
  returnableIssues: { id: string; remainingQuantity: string }[];
  totals: { totalAmount: number }; invoice: { id: string; code: string; totalAmount: number; paymentStatus: string } | null };

test('tiếp nhận online và tại gara, ghi công/vật tư, chốt phiếu và kiểm quyền', async ({ page, playwright, baseURL }) => {
  const username = process.env.E2E_ADMIN_USERNAME;
  const password = process.env.E2E_ADMIN_PASSWORD;
  if (!username?.startsWith('sc14_') || !password) throw new Error('Use a disposable SC-14 admin (E2E_ADMIN_USERNAME/PASSWORD).');
  const admin = await playwright.request.newContext({ baseURL });
  const guest = await playwright.request.newContext({ baseURL });
  try {
    await loginApi(admin, { username, password });
    const adminHeaders = await csrf(admin);
    const users = await json<{ items: { username: string; role: string }[] }>(await admin.get('/api/admin/users'));
    expect(users.items.map(user => [user.username, user.role])).toEqual([[username, 'ADMIN']]);
    for (const endpoint of ['/api/admin/services', '/api/admin/parts', orders, '/api/staff/invoices']) {
      const { items } = await json<{ items: unknown[] }>(await admin.get(endpoint));
      expect(items, `${endpoint} must be empty on the dedicated DB`).toEqual([]);
    }

    const staff = await json<{ id: string; authVersion: number }>(await admin.post('/api/admin/users', {
      headers: adminHeaders, data: { ...staffCredentials, displayName: 'Nhân viên SC14', role: 'STAFF' },
    }), 201);
    const service = await json<{ id: string }>(await admin.post('/api/admin/services', {
      headers: adminHeaders, data: { code: 'SC14-OIL', name: 'Thay dầu SC14', vehicleType: 'CAR', referencePrice: 101 },
    }), 201);
    const part = await json<{ id: string; stockQuantity: string }>(await admin.post('/api/admin/parts', {
      headers: adminHeaders, data: partBody,
    }), 201);
    expect(part.stockQuantity).toBe('0.000');
    const receipt = { partId: part.id, quantity: '2.000', reason: 'Fixture nghiệm thu SC14' };
    await json(await admin.post('/api/admin/stock-receipts', {
      headers: { ...adminHeaders, 'Idempotency-Key': randomUUID() }, data: receipt,
    }), 201);

    page.on('dialog', dialog => void dialog.accept());
    await test.step('Guest đặt lịch qua API thật, Staff tiếp nhận lịch đã đóng và chốt phiếu', async () => {
      await json(await admin.post('/api/admin/schedule/regenerate', { headers: adminHeaders }));
      const date = garageDate(new Date(`${garageDate(1)}T00:00:00Z`).getUTCDay() === 0 ? 2 : 1);
      const query = `date=${date}&vehicleType=CAR`;
      const slots = await json<{ id: string; remainingCapacity: number }[]>(await guest.get(`/api/public/slots?${query}`));
      const slot = slots.find(value => value.remainingCapacity > 0);
      expect(slot).toBeDefined();
      const created = await json<{ bookingCode: string; status: string }>(await guest.post('/api/public/bookings', {
        headers: { 'Idempotency-Key': randomUUID() },
        data: { slotId: slot!.id, serviceIds: [service.id], contact: { fullName: 'Khách kiểm thử Online', phone: '0910000014' },
          vehicle: { type: 'CAR', plateNumber: 'SC14ONLINE' } },
      }), 201);
      expect(created.status).toBe('CONFIRMED');
      const list = await json<{ items: Booking[] }>(await admin.get(`/api/staff/bookings?date=${date}`));
      const booking = list.items.find(value => value.code === created.bookingCode)!;
      expect(booking.repairOrderId).toBeNull();
      const readSlot = async () => (await json<{ id: string; used: number; version: number; closed: boolean }[]>(
        await admin.get(`/api/admin/slots?${query}`))).find(value => value.id === slot!.id)!;
      const before = await readSlot();
      expect(before.used).toBe(1);
      await json(await admin.patch(`/api/admin/slots/${slot!.id}`, {
        headers: adminHeaders, data: { manualClosed: true, expectedVersion: before.version },
      }));
      expect((await readSlot()).closed).toBe(true);
      await json(await guest.get('/api/staff/bookings'), 401);
      await json(await guest.post(`/api/staff/bookings/${booking.id}/check-in`, {
        headers: await csrf(guest), data: { expectedVersion: booking.version },
      }), 401);

      await page.goto('/staff/bookings');
      await loginUi(page);
      await expect(page.getByRole('heading', { name: 'Lịch tiếp nhận xe' })).toBeVisible();
      await page.getByLabel('Ngày tại gara').fill(date);
      await page.getByLabel('Trạng thái').selectOption('CONFIRMED');
      const row = page.getByRole('row').filter({ hasText: created.bookingCode });
      await row.getByRole('button', { name: 'Tiếp nhận', exact: true }).click();
      await page.getByLabel('Số km hiện tại').fill('12000');
      await page.getByRole('button', { name: 'Xác nhận tiếp nhận' }).click();
      await expect(page).toHaveURL(/\/staff\/repair-orders\/\d+$/);
      const path = `/api${new URL(page.url()).pathname}`;
      const detail = await json<Detail>(await admin.get(path));
      expect(detail).toMatchObject({ status: 'IN_PROGRESS', odometerKm: 12000 });
      const replay = await json<{ repairOrderId: string }>(await admin.post(`/api/staff/bookings/${booking.id}/check-in`, {
        headers: adminHeaders, data: { expectedVersion: booking.version },
      }));
      expect(replay.repairOrderId).toBe(detail.repairOrderId);
      expect((await readSlot()).used).toBe(1);
      await page.goBack();
      await expect(page.getByRole('heading', { name: 'Lịch tiếp nhận xe' })).toBeVisible();
      await page.getByLabel('Trạng thái').selectOption('CHECKED_IN');
      await expect(row.getByRole('button', { name: 'Mở phiếu' })).toBeVisible();
      await row.getByRole('button', { name: 'Mở phiếu' }).click();
      await addLabor(page, 'Công tiếp nhận online');
      const labor = page.getByRole('row').filter({ hasText: 'Công tiếp nhận online' });
      await labor.getByRole('button', { name: 'Sửa', exact: true }).click();
      await page.getByLabel('Số lượng công').fill('1.000');
      await page.getByRole('button', { name: 'Lưu công việc' }).click();
      await expect(labor).toContainText('1.000');
      await addLabor(page, 'Công cần bỏ');
      const removed = page.getByRole('row').filter({ hasText: 'Công cần bỏ' });
      await removed.getByRole('button', { name: 'Bỏ' }).click();
      await expect(removed).toHaveCount(0);
      await json(await admin.patch(`/api/admin/services/${service.id}`, {
        headers: adminHeaders, data: { name: 'Thay dầu SC14', vehicleType: 'CAR', referencePrice: 151, expectedVersion: 0 },
      }));
      await page.getByRole('button', { name: 'Làm mới phiếu' }).click();
      await expect(page.getByRole('listitem').filter({ hasText: 'Thay dầu SC14' })).toContainText('101');
      await completeRepair(page);
      expect((await json<Detail>(await admin.get(path))).invoice).toMatchObject({ totalAmount: 101, paymentStatus: 'UNPAID' });
      expect((await readSlot()).used).toBe(1);
    });

    const attempts: { key: string; body: string | null }[] = [];
    let walkPath = '';
    await test.step('Walk-in mất phản hồi sau commit và gửi lại cùng key', async () => {
      await page.getByRole('button', { name: 'Tiếp nhận tại gara', exact: true }).click();
      await page.getByLabel('Loại xe').selectOption('MOTORBIKE');
      await expect(page.getByText('Chưa có dịch vụ cho loại xe này.')).toBeVisible();
      await page.getByLabel('Loại xe').selectOption('CAR');
      await page.getByLabel('Họ và tên').fill('Khách kiểm thử Walk-in');
      await page.getByLabel('Số điện thoại').fill('0910000015');
      await page.getByLabel('Biển số').fill('SC14WALKIN');
      await page.getByRole('checkbox', { name: /Thay dầu SC14/ }).check();
      await page.route('**/api/staff/walk-ins', async route => {
        const request = route.request();
        attempts.push({ key: request.headers()['idempotency-key'], body: request.postData() });
        if (attempts.length === 1) {
          const committed = await route.fetch();
          expect(committed.status()).toBe(201);
          await route.abort('connectionreset');
        } else {
          await route.continue();
        }
      });
      await page.getByRole('button', { name: 'Tiếp nhận và mở phiếu' }).dblclick();
      await expect(page.getByText('Chưa xác nhận kết quả tiếp nhận')).toBeVisible();
      expect(attempts).toHaveLength(1);
      await page.reload();
      await page.getByRole('button', { name: 'Gửi lại yêu cầu đã lưu' }).click();
      await expect(page).toHaveURL(/\/staff\/repair-orders\/\d+$/);
      expect(attempts).toHaveLength(2);
      expect(attempts[1]).toEqual(attempts[0]);
      walkPath = new URL(page.url()).pathname;
      const result = await json<{ items: { plate: string; repairOrderId: string }[] }>(await admin.get(orders));
      expect(result.items.filter(item => item.plate === 'SC14WALKIN').map(item => item.repairOrderId))
        .toEqual([walkPath.split('/').at(-1)]);
    });

    const detailUrl = `/api${walkPath}`;
    const detail = async () => json<Detail>(await admin.get(detailUrl));
    await test.step('Stale hai tab, tiền công, thiếu tồn, xuất/hoàn và đổi giá', async () => {
      const other = await page.context().newPage();
      try {
        await other.goto(walkPath);
        await expect(other.getByRole('button', { name: 'Ghi công việc' })).toBeEnabled();
        await addLabor(page, 'Công nghiệm thu');
        await expect(page.getByText('Công nghiệm thu', { exact: true })).toBeVisible();
        await addLabor(other, 'Công từ tab cũ');
        await expect(other.getByRole('alert').filter({ hasText: 'Phiếu đã thay đổi' })).toBeVisible();
        await expect(other.getByLabel('Mô tả công việc')).toHaveValue('Công từ tab cũ');
      } finally {
        await other.close();
      }
      expect((await detail()).totals.totalAmount).toBe(51);

      const issue = async (quantity: string) => {
        await page.getByRole('combobox', { name: 'Phụ tùng', exact: true }).selectOption(part.id);
        await page.getByLabel('Số lượng xuất').fill(quantity);
        await page.getByRole('button', { name: 'Xuất phụ tùng' }).click();
      };
      await issue('2.001');
      await expect(page.getByText(/tồn kho không đủ/i)).toBeVisible();
      const stockAttempts: typeof attempts = [];
      await page.route(`**${detailUrl}/part-issues`, async route => {
        const request = route.request();
        stockAttempts.push({ key: request.headers()['idempotency-key'], body: request.postData() });
        if (stockAttempts.length === 1) {
          const committed = await route.fetch();
          expect(committed.status()).toBe(201);
          await route.abort('connectionreset');
        } else {
          await route.continue();
        }
      });
      await issue('1.500');
      await expect(page.getByText('Kết quả lệnh kho chưa rõ')).toBeVisible();
      await page.getByRole('button', { name: 'Thử lại cùng mã yêu cầu' }).click();
      await expect.poll(() => stockAttempts.length).toBe(2);
      expect(stockAttempts[1]).toEqual(stockAttempts[0]);
      await expect.poll(async () => (await detail()).partItems[0]?.quantity).toBe('1.500');
      const issued = (await detail()).returnableIssues[0];
      await page.getByRole('combobox', { name: 'Lần xuất gốc' }).selectOption(issued.id);
      await page.getByLabel('Số lượng hoàn').fill('0.500');
      await page.getByRole('button', { name: 'Hoàn phụ tùng' }).click();
      await expect.poll(async () => (await detail()).returnableIssues[0].remainingQuantity).toBe('1.000');
      await page.getByRole('button', { name: 'Sửa giá', exact: true }).click();
      await page.getByLabel('Đơn giá mới (đồng)').fill('202');
      await page.getByLabel('Lý do thay đổi').fill('Đơn giá nghiệm thu SC14');
      await page.getByRole('button', { name: 'Cập nhật giá' }).click();
      await expect.poll(async () => (await detail()).totals.totalAmount).toBe(253);
    });

    await test.step('Chốt hóa đơn, kiểm API khi nút ghi ẩn, CSRF và thu hồi phiên', async () => {
      await completeRepair(page);
      const completed = await detail();
      expect(completed.status).toBe('COMPLETED');
      expect(completed.invoice).toMatchObject({ totalAmount: 253, paymentStatus: 'UNPAID' });
      await page.reload();
      await expect(page.getByRole('heading', { name: `Hóa đơn ${completed.invoice!.code}` })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Ghi công việc' })).toHaveCount(0);
      const headers = await csrf(page.request);
      const edit = { description: 'Không được ghi sau chốt', quantity: '1.000', unitPrice: 0, expectedVersion: completed.orderVersion };
      const csrfError = await json<{ code: string }>(await page.request.post(`${detailUrl}/labor-items`, { data: edit }), 403);
      expect(csrfError.code).toBe('CSRF_INVALID');
      const blocked = await json<{ code: string }>(await page.request.post(`${detailUrl}/labor-items`, { headers, data: edit }), 409);
      expect(blocked.code).toBe('REPAIR_NOT_IN_PROGRESS');
      const replay = await json<{ invoice: Detail['invoice'] }>(await page.request.post(`${detailUrl}/complete`, {
        headers, data: { expectedVersion: completed.orderVersion },
      }));
      expect(replay.invoice?.id).toBe(completed.invoice!.id);
      await page.goto('/admin/parts');
      await expect(page.getByRole('heading', { name: 'Trang này dành cho quản trị viên' })).toBeVisible();
      for (const [path, data] of [['/api/admin/parts', partBody], ['/api/admin/stock-receipts', receipt]] as const) {
        const forbidden = await json<{ code: string }>(await page.request.post(path, {
          headers: { ...headers, 'Idempotency-Key': randomUUID() }, data,
        }), 403);
        expect(forbidden.code).toBe('FORBIDDEN');
      }
      await json(await admin.patch(`/api/admin/users/${staff.id}`, {
        headers: adminHeaders, data: { active: false, expectedAuthVersion: staff.authVersion },
      }));
      const revoked = await json<{ code: string }>(await page.request.get(detailUrl), 401);
      expect(revoked.code).toBe('SESSION_REVOKED');
      await page.goto(walkPath);
      await expect(page.getByRole('heading', { name: 'Đăng nhập' })).toBeVisible();
      await json(await admin.patch(`/api/admin/users/${staff.id}`, {
        headers: adminHeaders, data: { active: true, expectedAuthVersion: staff.authVersion + 1 },
      }));
      await loginUi(page);
      await expect(page.getByRole('heading', { name: `Hóa đơn ${completed.invoice!.code}` })).toBeVisible();
    });
  } finally {
    await admin.dispose();
    await guest.dispose();
  }
});
