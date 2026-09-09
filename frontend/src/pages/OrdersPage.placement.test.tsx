import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import * as client from '../api/client';
import { Catalog } from '../api/catalog';
import type { OrderView, PlaceOrderCommand } from '../api/types';
import samples from '../test/fixtures/fulfillment.json';
import { OrdersPage } from './OrdersPage';

const order = samples.events.order;
const command: PlaceOrderCommand = { ownerId: order.ownerId, externalOrderNo: order.externalOrderNo,
  facilityId: order.facilityId, shipToZone: order.shipToZone, shipToAddress: order.shipToAddress,
  promisedDeliveryDate: order.promisedDeliveryDate, dispatchBy: order.dispatchBy, releasePriority: 0,
  lines: order.lines.map(l => ({ skuCode: l.skuCode, quantity: l.quantity })) };
const response: OrderView = { ...order, status: 'PENDING', lines: order.lines.map(l => ({ ...l, status: 'PENDING' })) };
vi.mock('../hooks/useCatalog', () => ({ useCatalog: () => new Catalog([]) }));
vi.mock('../components/PlaceOrderForm', () => ({ PlaceOrderForm: ({ onSubmit, pending, blocked }: {
  onSubmit: (c: PlaceOrderCommand) => void; pending: boolean; blocked: boolean;
}) => <button disabled={pending || blocked} onClick={() => onSubmit(command)}>送出訂單</button> }));
function Probe() { const location = useLocation(); return <output data-testid="url">{location.search}</output>; }
beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', ''); } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open'); } });
  vi.spyOn(client, 'listRecentOrders').mockResolvedValue([]);
  vi.spyOn(client, 'getOrderFulfillment').mockResolvedValue(samples.events);
});
afterEach(() => vi.restoreAllMocks());
const mount = () => render(<MemoryRouter initialEntries={['/orders?sku=TEA']}><OrdersPage /><Probe /></MemoryRouter>);
it('successful creation opens the matching drawer; duplicate clicks send only once', async () => {
  let resolve!: (order: OrderView) => void;
  const place = vi.spyOn(client, 'placeOrder').mockImplementation(() => new Promise(r => { resolve = r; }));
  mount();
  fireEvent.click(screen.getByRole('button', { name: '送出訂單' }));
  fireEvent.click(screen.getByRole('button', { name: '送出訂單' }));
  expect(place).toHaveBeenCalledTimes(1);
  await act(async () => resolve(response));
  expect(await screen.findByRole('dialog')).toBeVisible();
  expect(screen.getByTestId('url')).toHaveTextContent(`orderId=${order.orderId}`);
  fireEvent.click(screen.getByRole('button', { name: '關閉詳情' }));
  expect(screen.getByTestId('url')).toHaveTextContent('?sku=TEA');
  expect(screen.getByRole('button', { name: '送出訂單' })).toBeDisabled();
});
it.each([new client.ApiError(409, 'duplicate'), new TypeError('Failed to fetch')])(
  'uncertain response retains identity and can find an existing order', async error => {
    const place = vi.spyOn(client, 'placeOrder').mockRejectedValue(error);
    mount();
    fireEvent.click(screen.getByRole('button', { name: '送出訂單' }));
    await screen.findByRole('button', { name: '重查最近訂單確認' });
    expect(screen.getByText(/原單號：/)).toHaveTextContent(order.externalOrderNo);
    expect(screen.getByRole('button', { name: '送出訂單' })).toBeDisabled();
    vi.mocked(client.listRecentOrders).mockResolvedValue([response]);
    fireEvent.click(screen.getByRole('button', { name: '重查最近訂單確認' }));
    fireEvent.click(await screen.findByRole('button', { name: '查看此訂單履約' }));
    expect(await screen.findByRole('dialog')).toBeVisible();
    expect(place).toHaveBeenCalledTimes(1);
  },
);
it('not found in recent list is not permission to resubmit', async () => {
  const place = vi.spyOn(client, 'placeOrder').mockRejectedValue(new TypeError('Failed to fetch'));
  mount();
  fireEvent.click(screen.getByRole('button', { name: '送出訂單' }));
  fireEvent.click(await screen.findByRole('button', { name: '重查最近訂單確認' }));
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('這不代表訂單未建立'));
  expect(screen.getByRole('button', { name: '送出訂單' })).toBeDisabled();
  expect(place).toHaveBeenCalledTimes(1);
});
it('400 validation error permits correcting the original form', async () => {
  vi.spyOn(client, 'placeOrder').mockRejectedValue(new client.ApiError(400, 'Invalid address'));
  mount();
  fireEvent.click(screen.getByRole('button', { name: '送出訂單' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Invalid address');
  expect(screen.getByRole('button', { name: '送出訂單' })).toBeEnabled();
});
