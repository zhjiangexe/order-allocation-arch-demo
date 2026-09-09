import { transferableAbortController } from 'node:util';
import { OwnerSessionProvider } from './OwnerSession';
import { AppHeader } from '../components/AppHeader';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider, useBlocker } from 'react-router';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { App } from '../App';
import type { OrderView } from '../api/types';
import * as client from '../api/client';
import samples from '../test/fixtures/fulfillment.json';
import { selectOption } from '../test/selectOption';

const order: OrderView = { ...samples.events.order, status: 'FULFILLED', lines: samples.events.order.lines.map(line => ({ ...line, status: 'FULFILLED' })) };
const ownerA = { ownerId: order.ownerId, code: 'A', name: '貨主甲' };
const ownerB = { ownerId: '00000000-0000-0000-0000-000000000099', code: 'B', name: '貨主乙' };
function mount(path = '/orders') {
  const router = createMemoryRouter([{ path: '*', element: <App /> }], { initialEntries: [path] });
  return { ...render(<RouterProvider router={router} />), router };
}
beforeEach(() => {
  vi.stubGlobal('AbortController', class { constructor() { return transferableAbortController(); } });
  sessionStorage.clear();
  vi.spyOn(client, 'listOwners').mockResolvedValue([ownerA, ownerB]);
  vi.spyOn(client, 'listFacilities').mockResolvedValue([{ facilityId: order.facilityId, name: '北倉', code: 'N' }]);
  vi.spyOn(client, 'listStockLocations').mockResolvedValue([{ facilityId: order.facilityId, locationId: samples.events.stockOperation.operation.fromLocationId, name: '庫位', code: 'L' }]);
  vi.spyOn(client, 'listProducts').mockResolvedValue([]);
  vi.spyOn(client, 'listRecentOrders').mockResolvedValue([order, { ...order, orderId: 'other', ownerId: ownerB.ownerId, externalOrderNo: 'OWNER-B-ORDER' }]);
  vi.spyOn(client, 'listConfirmedStockOperations').mockResolvedValue([]);
  vi.spyOn(client, 'getOrderFulfillment').mockResolvedValue(samples.events);
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', ''); } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open'); } });
});
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); sessionStorage.clear(); });
it('switching header owner clears detail and filters, scopes requests and remembers the selection', async () => {
  const user = userEvent.setup();
  const { router, unmount } = mount(`/orders?sku=OLD&orderId=${order.orderId}`);
  await screen.findByRole('dialog', { name: '訂單履約' });
  fireEvent.click(screen.getByRole('button', { name: '關閉詳情' }));
  expect(screen.queryByText(/OWNER-B-ORDER/)).not.toBeInTheDocument();
  await selectOption(user, screen.getByRole('combobox', { name: '目前貨主' }), ownerB.ownerId);
  await screen.findByText(/OWNER-B-ORDER/);
  expect(router.state.location.search).toBe('');
  expect(screen.queryByText(new RegExp(order.externalOrderNo))).not.toBeInTheDocument();
  expect(client.listRecentOrders).toHaveBeenLastCalledWith(20, undefined, ownerB.ownerId);
  expect(client.listOwners).toHaveBeenCalledTimes(1);
  expect(sessionStorage.getItem('allocation.ownerId')).toBe(ownerB.ownerId);
  unmount();
  mount();
  await waitFor(() => expect(screen.getByRole('combobox', { name: '目前貨主' })).toHaveValue(ownerB.ownerId));
});
it('new-order form and stock page use header owner without another owner selector', async () => {
  const user = userEvent.setup();
  mount();
  await screen.findByRole('button', { name: '新建訂單' });
  await user.click(screen.getByRole('button', { name: '新建訂單' }));
  const dialog = screen.getByRole('dialog', { name: '新建訂單' });
  expect(within(dialog).queryByLabelText('貨主')).not.toBeInTheDocument();
  expect(within(dialog).getByRole('combobox', { name: '履約設施' })).toBeEnabled();
  await user.click(within(dialog).getByRole('button', { name: '取消' }));
  await user.click(screen.getByRole('link', { name: '庫存' }));
  await screen.findByRole('button', { name: '查詢庫存' });
  expect(within(screen.getByRole('main')).queryByLabelText('貨主')).not.toBeInTheDocument();
  expect(screen.getByRole('combobox', { name: '設施' })).toBeEnabled();
});
it('a foreign receipt link cannot preselect or query another owner', async () => {
  sessionStorage.setItem('allocation.ownerId', ownerB.ownerId);
  const stock = vi.spyOn(client, 'getStockInLocation');
  mount(`/stock?ownerId=${ownerA.ownerId}&facilityId=${order.facilityId}&locationId=${samples.events.stockOperation.operation.fromLocationId}&sku=${order.lines[0]!.skuCode}&returnOrderId=${order.orderId}&returnPage=/orders`);
  expect(await screen.findByRole('alert')).toHaveTextContent('補貨定位參數無效');
  expect(stock).not.toHaveBeenCalled();
  await waitFor(() => expect(screen.getByRole('combobox', { name: '設施' })).toHaveValue(order.facilityId));
  expect(stock).not.toHaveBeenCalled();
});
it('a foreign fulfillment link shows no order data', async () => {
  sessionStorage.setItem('allocation.ownerId', ownerB.ownerId);
  mount(`/orders?orderId=${order.orderId}`);
  expect(await screen.findByRole('alert')).toHaveTextContent('此訂單不屬於目前貨主');
  expect(within(screen.getByRole('dialog')).queryByText(new RegExp(order.externalOrderNo))).not.toBeInTheDocument();
});
it('ignores a removed saved owner and can recover after owner loading fails', async () => {
  sessionStorage.setItem('allocation.ownerId', 'removed');
  vi.mocked(client.listOwners).mockRejectedValueOnce(new Error('offline'));
  mount();
  expect(await screen.findByRole('alert')).toHaveTextContent('offline');
  await act(async () => fireEvent.click(screen.getByRole('button', { name: '重試' })));
  await screen.findByRole('button', { name: '新建訂單' });
  expect(screen.getByRole('combobox', { name: '目前貨主' })).toHaveValue(ownerA.ownerId);
});

it('does not change owner until a blocked navigation is explicitly allowed', async () => {
  function Guard() {
    const blocker = useBlocker(true);
    return blocker.state === 'blocked' ? <><button onClick={() => blocker.reset()}>留在原貨主</button><button onClick={() => blocker.proceed()}>確認切換</button></> : null;
  }
  const router = createMemoryRouter([{ path: '*', element: <OwnerSessionProvider><AppHeader /><Guard /></OwnerSessionProvider> }], { initialEntries: ['/stock'] });
  render(<RouterProvider router={router} />);
  const user = userEvent.setup();
  const select = await screen.findByRole('combobox', { name: '目前貨主' });
  await waitFor(() => expect(select).toHaveValue(ownerA.ownerId));
  await selectOption(user, select, ownerB.ownerId);
  expect(select).toHaveValue(ownerA.ownerId);
  await user.click(screen.getByRole('button', { name: '留在原貨主' }));
  expect(select).toHaveValue(ownerA.ownerId);
  await selectOption(user, select, ownerB.ownerId);
  await user.click(screen.getByRole('button', { name: '確認切換' }));
  expect(select).toHaveValue(ownerB.ownerId);
});
it('a late order list from the previous owner cannot replace the current list', async () => {
  let resolve!: (orders: typeof order[]) => void;
  vi.mocked(client.listRecentOrders).mockImplementation((_limit, _signal, ownerId) => ownerId === ownerA.ownerId
    ? new Promise(r => { resolve = r; })
    : Promise.resolve([{ ...order, ownerId: ownerB.ownerId, externalOrderNo: 'B-ONLY' }]));
  mount();
  await waitFor(() => expect(client.listRecentOrders).toHaveBeenCalled());
  await selectOption(userEvent.setup(), screen.getByRole('combobox', { name: '目前貨主' }), ownerB.ownerId);
  await screen.findByText(/B-ONLY/);
  await act(async () => resolve([order]));
  expect(screen.getByText(/B-ONLY/)).toBeVisible();
  expect(screen.queryByText(new RegExp(order.externalOrderNo))).not.toBeInTheDocument();
});
