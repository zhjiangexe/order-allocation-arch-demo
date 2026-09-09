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
vi.mock('../components/PlaceOrderForm', () => ({ PlaceOrderForm: ({ onSubmit, formId }: {
  onSubmit: (c: PlaceOrderCommand) => void; formId: string;
}) => <form id={formId} onSubmit={event => { event.preventDefault(); onSubmit(command); }} /> }));
function Probe() { const location = useLocation(); return <output data-testid="url">{location.search}</output>; }
beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', ''); } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open'); } });
  vi.spyOn(client, 'listRecentOrders').mockResolvedValue([]);
  vi.spyOn(client, 'getOrderFulfillment').mockResolvedValue(samples.events);
});
afterEach(() => vi.restoreAllMocks());
const mount = () => {
  render(<MemoryRouter initialEntries={['/orders?sku=TEA']}><OrdersPage /><Probe /></MemoryRouter>);
  fireEvent.click(screen.getByRole('button', { name: '新建訂單' }));
};
it('successful creation opens the matching drawer; duplicate clicks send only once', async () => {
  let resolve!: (order: OrderView) => void;
  const place = vi.spyOn(client, 'placeOrder').mockImplementation(() => new Promise(r => { resolve = r; }));
  mount();
  fireEvent.click(screen.getByRole('button', { name: /送出/ }));
  fireEvent.click(screen.getByRole('button', { name: /送出/ }));
  expect(place).toHaveBeenCalledTimes(1);
  await act(async () => resolve(response));
  expect(await screen.findByRole('dialog')).toBeVisible();
  expect(screen.getByTestId('url')).toHaveTextContent(`orderId=${order.orderId}`);
  fireEvent.click(screen.getByRole('button', { name: '關閉詳情' }));
  expect(screen.getByTestId('url')).toHaveTextContent('?sku=TEA');
  expect(screen.getByRole('button', { name: '新建訂單' })).toBeEnabled();
});
it.each([new client.ApiError(409, 'duplicate'), new TypeError('Failed to fetch')])(
  'uncertain response retains identity and can find an existing order', async error => {
    const place = vi.spyOn(client, 'placeOrder').mockRejectedValue(error);
    mount();
    fireEvent.click(screen.getByRole('button', { name: /送出/ }));
    await screen.findByRole('button', { name: '重查最近訂單確認' });
    expect(screen.getByText(/原單號：/)).toHaveTextContent(order.externalOrderNo);
    expect(screen.getByRole('button', { name: /送出/ })).toBeDisabled();
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
  fireEvent.click(screen.getByRole('button', { name: /送出/ }));
  fireEvent.click(await screen.findByRole('button', { name: '重查最近訂單確認' }));
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('這不代表訂單未建立'));
  expect(screen.getByRole('button', { name: /送出/ })).toBeDisabled();
  expect(place).toHaveBeenCalledTimes(1);
});
it('400 validation error permits correcting the original form', async () => {
  vi.spyOn(client, 'placeOrder').mockRejectedValue(new client.ApiError(400, 'Invalid address'));
  mount();
  fireEvent.click(screen.getByRole('button', { name: /送出/ }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Invalid address');
  expect(screen.getByRole('button', { name: /送出/ })).toBeEnabled();
});

it('starts with a new-order button; cancel closes without placing an order', async () => {
  const place = vi.spyOn(client, 'placeOrder');
  render(<MemoryRouter><OrdersPage /></MemoryRouter>);
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '新建訂單' }));
  expect(screen.getByRole('dialog', { name: '新建訂單' })).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '取消' }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(place).not.toHaveBeenCalled();
  await act(async () => {});
});
it('cannot dismiss an in-flight order using cancel or Escape', async () => {
  let resolve!: (order: OrderView) => void;
  vi.spyOn(client, 'placeOrder').mockImplementation(() => new Promise(r => { resolve = r; }));
  mount();
  fireEvent.click(screen.getByRole('button', { name: /送出/ }));
  expect(screen.getByRole('button', { name: '取消' })).toBeDisabled();
  fireEvent(screen.getByRole('dialog'), new Event('cancel', { bubbles: true, cancelable: true }));
  expect(screen.getByRole('dialog', { name: '新建訂單' })).toBeVisible();
  await act(async () => resolve(response));
  expect(screen.queryByRole('dialog', { name: '新建訂單' })).not.toBeInTheDocument();
  expect(screen.getByRole('dialog', { name: '訂單履約' })).toBeVisible();
});
it('dismissing an uncertain result preserves identity and blocks duplicate submission when reopened', async () => {
  const place = vi.spyOn(client, 'placeOrder').mockRejectedValue(new TypeError('Failed to fetch'));
  mount();
  fireEvent.click(screen.getByRole('button', { name: /送出/ }));
  await screen.findByRole('button', { name: '重查最近訂單確認' });
  fireEvent.click(screen.getByRole('button', { name: '取消' }));
  fireEvent.click(screen.getByRole('button', { name: '繼續確認訂單' }));
  expect(screen.getByText(/原單號：/)).toHaveTextContent(order.externalOrderNo);
  expect(screen.getByRole('button', { name: /送出/ })).toBeDisabled();
  expect(place).toHaveBeenCalledTimes(1);
});
