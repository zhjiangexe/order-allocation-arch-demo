import { OwnerSessionContext } from '../owner/OwnerSession';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import * as client from '../api/client';
import { Catalog } from '../api/catalog';
import type { StockOperationView } from '../api/types';
import samples from '../test/fixtures/fulfillment.json';
import { AllocationsPage } from './AllocationsPage';

const order = samples.events.order;
const catalog = new Catalog([{ owner: { ownerId: order.ownerId, code: 'O', name: '甲貨主' },
  facilities: [{ facilityId: order.facilityId, code: 'F', name: '北部倉' }], products: [],
  locations: [{ facilityId: order.facilityId, locationId: samples.events.stockOperation.operation.fromLocationId, code: 'L', name: '庫存' }],
  skus: [{ skuId: 'sku', ownerId: order.ownerId, skuCode: order.lines[0]!.skuCode, productCode: 'P', productName: 'Product', specName: 'Spec', weightGram: 1 }],
}]);
vi.mock('../hooks/useCatalog', () => ({ useCatalog: () => catalog }));
const row = (): StockOperationView => {
  const data = structuredClone(samples.events.stockOperation);
  data.operation.state = 'CONFIRMED';
  data.moves[0]!.state = 'CONFIRMED';
  data.moves[0]!.batches = [];
  return data;
};
function Probe() { const location = useLocation(); return <output data-testid="url">{location.pathname}{location.search}</output>; }
const mount = (url = '/allocations') => render(<MemoryRouter initialEntries={[url]}><AllocationsPage /><Probe /></MemoryRouter>);
beforeEach(() => {
  vi.spyOn(client, 'listConfirmedStockOperations').mockResolvedValue([row()]);
  vi.spyOn(client, 'getOrderFulfillment').mockResolvedValue(samples.events);
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', ''); } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open'); } });
});
afterEach(() => vi.restoreAllMocks());

it('loads the supported queue with a 200 limit, shows fields and expands demand lines', async () => {
  mount();
  expect(await screen.findByText('已載入 1 筆，符合篩選 1 筆。')).toBeVisible();
  expect(client.listConfirmedStockOperations).toHaveBeenCalledWith(200, expect.any(AbortSignal), undefined);
  expect(screen.getByText(row().operation.stockOperationId)).toBeVisible();
  expect(screen.getByText('庫存')).toBeVisible();
  fireEvent.click(screen.getByText('SKU 需求明細（1 行）'));
  expect(screen.getByText(order.lines[0]!.skuCode)).toBeVisible();
  expect(screen.getByRole('link', { name: '前往庫存補貨' })).toHaveAttribute('href', expect.stringContaining('returnPage=%2Fallocations'));
});
it('filters loaded rows locally and preserves filters when closing detail', async () => {
  mount('/allocations?source=ORDER');
  await screen.findByText('已載入 1 筆，符合篩選 1 筆。');
  fireEvent.change(screen.getByLabelText('SKU'), { target: { value: 'absent' } });
  expect(screen.getByText('已載入的作業中沒有符合篩選的資料。')).toBeVisible();
  fireEvent.change(screen.getByLabelText('SKU'), { target: { value: order.lines[0]!.skuCode } });
  expect(client.listConfirmedStockOperations).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button', { name: '查看履約' }));
  expect(await screen.findByRole('dialog')).toBeVisible();
  await waitFor(() => expect(client.getOrderFulfillment).toHaveBeenCalled());
  fireEvent.click(screen.getByRole('button', { name: '關閉詳情' }));
  expect(screen.getByTestId('url')).toHaveTextContent(`source=ORDER&sku=${order.lines[0]!.skuCode}`);
  expect(screen.getByTestId('url')).not.toHaveTextContent('orderId=');
  expect(client.listConfirmedStockOperations).toHaveBeenCalledTimes(1);
});
it.each(['TRANSFER', 'MANUAL', 'ORDER_INVALID', 'ORDER_SECONDARY'])(
  'does not create invalid navigation for %s', async source => {
    const data = row();
    if (source === 'ORDER_INVALID') data.source.sourceId = 'invalid';
    else if (source === 'ORDER_SECONDARY') data.source.operationUnitKey = 'SECONDARY';
    else data.source.type = source;
    data.operation.fromLocationId = null;
    data.operation.dispatchBy = null;
    data.operation.releasePriority = null;
    vi.mocked(client.listConfirmedStockOperations).mockResolvedValue([data]);
    mount();
    await screen.findByText('已載入 1 筆，符合篩選 1 筆。');
    expect(screen.queryByRole('button', { name: '查看履約' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByText('SKU 需求明細（1 行）'));
    expect(screen.queryByRole('link', { name: '前往庫存補貨' })).not.toBeInTheDocument();
    expect(screen.getByText(/主檔尚未解析/)).toBeVisible();
  },
);
it('reaching the limit warns that local filters can omit unloaded matches', async () => {
  vi.mocked(client.listConfirmedStockOperations).mockResolvedValue(Array.from({ length: 200 }, (_, index) => {
    const data = row(); data.operation.stockOperationId = `operation-${index}`; return data;
  }));
  mount('/allocations?sku=absent');
  expect(await screen.findByText(/已達 200 筆上限/)).toBeVisible();
  expect(screen.getByText('已載入 200 筆，符合篩選 0 筆。')).toBeVisible();
});
it('distinguishes empty results, failed loading and stale data on refresh failure', async () => {
  vi.mocked(client.listConfirmedStockOperations).mockRejectedValueOnce(new Error('HTTP 500'));
  mount();
  expect(await screen.findByRole('alert')).toHaveTextContent('HTTP 500');
  expect(screen.queryByText('目前沒有等待分配的作業。')).not.toBeInTheDocument();
  vi.mocked(client.listConfirmedStockOperations).mockResolvedValueOnce([]);
  fireEvent.click(screen.getByRole('button', { name: '重新整理佇列' }));
  expect(await screen.findByText('目前沒有等待分配的作業。')).toBeVisible();
  vi.mocked(client.listConfirmedStockOperations).mockResolvedValueOnce([row()]);
  fireEvent.click(screen.getByRole('button', { name: '重新整理佇列' }));
  await screen.findByText('已載入 1 筆，符合篩選 1 筆。');
  vi.mocked(client.listConfirmedStockOperations).mockRejectedValueOnce(new Error('offline'));
  fireEvent.click(screen.getByRole('button', { name: '重新整理佇列' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('下方保留上次資料');
  expect(screen.getByText(row().operation.stockOperationId)).toBeVisible();
});
it('unknown catalog still shows IDs and disables only replenishment navigation', async () => {
  const data = row(); data.operation.ownerId = '00000000-0000-0000-0000-000000000099';
  vi.mocked(client.listConfirmedStockOperations).mockResolvedValue([data]);
  mount();
  await screen.findByText('已載入 1 筆，符合篩選 1 筆。');
  expect(within(screen.getByRole('article')).getByText(data.operation.ownerId)).toBeVisible();
  expect(screen.getByRole('button', { name: '查看履約' })).toBeEnabled();
  fireEvent.click(screen.getByText('SKU 需求明細（1 行）'));
  expect(screen.queryByRole('link', { name: '前往庫存補貨' })).not.toBeInTheDocument();
});
it('aborts on unmount and ignores the late response', async () => {
  let resolve!: (rows: StockOperationView[]) => void;
  vi.mocked(client.listConfirmedStockOperations).mockImplementation(() => new Promise(r => { resolve = r; }));
  const { unmount } = mount();
  const signal = vi.mocked(client.listConfirmedStockOperations).mock.calls[0]![1]!;
  unmount();
  expect(signal.aborted).toBe(true);
  await act(async () => resolve([row()]));
});

it('scopes the queue to the current owner before applying facility and source filters', async () => {
  const other = row();
  other.operation.stockOperationId = 'other-operation';
  other.operation.ownerId = '00000000-0000-0000-0000-000000000099';
  other.source.type = 'TRANSFER';
  vi.mocked(client.listConfirmedStockOperations).mockResolvedValue([row(), other]);
  render(<MemoryRouter><OwnerSessionContext.Provider value={{ owners: catalog.owners, owner: catalog.owners[0]!, loading: false, error: null, retry: () => {} }}><AllocationsPage /></OwnerSessionContext.Provider></MemoryRouter>);
  await screen.findByText('已載入 1 筆，符合篩選 1 筆。');
  expect(client.listConfirmedStockOperations).toHaveBeenCalledWith(200, expect.any(AbortSignal), order.ownerId);
  expect(screen.queryByLabelText('貨主')).not.toBeInTheDocument();
  expect(screen.queryByText('other-operation')).not.toBeInTheDocument();
  fireEvent.click(screen.getByLabelText('設施'));
  fireEvent.click(screen.getAllByRole('option').find(option => option.getAttribute('data-value') === order.facilityId)!);
  expect(screen.getAllByRole('article')).toHaveLength(1);
  fireEvent.click(screen.getByRole('button', { name: '清除篩選' }));
  expect(screen.getAllByRole('article')).toHaveLength(1);
  expect(client.listConfirmedStockOperations).toHaveBeenCalledTimes(1);
});
