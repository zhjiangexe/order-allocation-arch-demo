import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import { Catalog } from '../api/catalog';
import * as client from '../api/client';
import samples from '../test/fixtures/fulfillment.json';
import { FulfillmentDetails, FulfillmentDrawerRoute } from './FulfillmentDrawer';

const catalog = new Catalog([]);
const list = { page: '/orders' as const, filters: {} };
beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) {
    this.setAttribute('open', '');
    this.querySelector<HTMLButtonElement>('button')?.focus();
  } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open'); } });
});
afterEach(() => vi.restoreAllMocks());

it.each(['events', 'temporal'] as const)('renders %s evidence without polling', mode => {
  render(<MemoryRouter><FulfillmentDetails data={samples[mode]} catalog={catalog} list={list} /></MemoryRouter>);
  expect(screen.getByText(mode === 'events' ? 'Events' : 'Temporal')).toBeVisible();
  expect(screen.getByText('庫存作業與分配批次')).toBeVisible();
  expect(screen.getByText(samples[mode].stockOperation.moves[0]!.batches[0]!.stockQuantId)).toBeVisible();
  expect(screen.getByText('訂單履約所屬 Shipment')).toBeVisible();
  if (mode === 'temporal') {
    expect(screen.getByText('FULFILLMENT_COMPLETED')).not.toBeVisible();
    fireEvent.click(screen.getByText('流程協調技術資訊'));
    expect(screen.getByText('FULFILLMENT_COMPLETED')).toBeVisible();
  }
  else expect(screen.queryByText('Temporal Workflow')).not.toBeInTheDocument();
});
it('renders empty allocation and NOT_FOUND without calling it Events', () => {
  render(<MemoryRouter><FulfillmentDetails data={{ ...samples.temporal, stockOperation: null,
    shipments: [], temporalWorkflow: null, workflowQueryStatus: 'NOT_FOUND' }} catalog={catalog} list={list} /></MemoryRouter>);
  expect(screen.getByText('尚無庫存作業或分配資料。')).toBeVisible();
  expect(screen.getByText('尚未建立 Shipment。')).toBeVisible();
  fireEvent.click(screen.getByText('流程協調技術資訊'));
  expect(screen.getByText(/查無 Workflow，可能尚未建立/)).toBeVisible();
  expect(screen.queryByRole('link', { name: '前往庫存補貨' })).not.toBeInTheDocument();
});
function LocationProbe() { const location = useLocation(); return <output data-testid="url">{location.pathname}{location.search}</output>; }
it.each(['/orders', '/allocations'])('deep link on %s closes with filters and restores focus', async page => {
  vi.spyOn(client, 'getOrderFulfillment').mockResolvedValue(samples.events);
  const trigger = document.createElement('button');
  document.body.append(trigger); trigger.focus();
  render(<MemoryRouter initialEntries={[`${page}?sku=TEA&orderId=${samples.events.order.orderId}`]}>
    <FulfillmentDrawerRoute catalog={catalog} /><LocationProbe />
  </MemoryRouter>);
  await screen.findByText('履約完成');
  expect(screen.getByRole('button', { name: '關閉詳情' })).toHaveFocus();
  fireEvent(screen.getByRole('dialog'), new Event('cancel', { bubbles: true, cancelable: true }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(screen.getByTestId('url')).toHaveTextContent(`${page}?sku=TEA`);
  expect(trigger).toHaveFocus(); trigger.remove();
});
it('close aborts an in-flight query; 404 is visible and retryable', async () => {
  const get = vi.spyOn(client, 'getOrderFulfillment').mockRejectedValue(new client.ApiError(404, 'Order not found'));
  render(<MemoryRouter initialEntries={[`/orders?orderId=${samples.events.order.orderId}`]}>
    <FulfillmentDrawerRoute catalog={catalog} />
  </MemoryRouter>);
  expect(await screen.findByRole('alert')).toHaveTextContent('找不到此訂單');
  get.mockImplementation(() => new Promise(() => {}));
  fireEvent.click(screen.getByRole('button', { name: '重新查詢履約' }));
  await waitFor(() => expect(get).toHaveBeenCalledTimes(2));
  const signal = get.mock.calls[1]![1]!;
  fireEvent.click(screen.getByRole('button', { name: '關閉詳情' }));
  expect(signal.aborted).toBe(true);
});
it('invalid deep link does not request the backend', () => {
  const get = vi.spyOn(client, 'getOrderFulfillment');
  render(<MemoryRouter initialEntries={['/orders?orderId=invalid']}><FulfillmentDrawerRoute catalog={catalog} /></MemoryRouter>);
  expect(screen.getByRole('alert')).toHaveTextContent('訂單 ID 格式無效');
  expect(get).not.toHaveBeenCalled();
});
it('refresh failure retains prior detail and exposes stale error', async () => {
  const get = vi.spyOn(client, 'getOrderFulfillment').mockResolvedValue(samples.events);
  render(<MemoryRouter initialEntries={[`/orders?orderId=${samples.events.order.orderId}`]}><FulfillmentDrawerRoute catalog={catalog} /></MemoryRouter>);
  await screen.findByText('履約完成');
  get.mockRejectedValue(new Error('HTTP 503'));
  await act(async () => fireEvent.click(screen.getByRole('button', { name: '重新查詢履約' })));
  expect(screen.getByRole('alert')).toHaveTextContent('資料可能過時');
  expect(screen.getByText(samples.events.order.externalOrderNo)).toBeVisible();
});

it('copies correlation IDs and reports clipboard failure', async () => {
  const copy = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: copy } });
  vi.spyOn(client, 'getOrderFulfillment').mockResolvedValue(samples.events);
  render(<MemoryRouter initialEntries={[`/orders?orderId=${samples.events.order.orderId}`]}><FulfillmentDrawerRoute catalog={catalog} /></MemoryRouter>);
  await screen.findByText('履約完成');
  await act(async () => fireEvent.click(screen.getByRole('button', { name: '複製 IDs' })));
  expect(copy).toHaveBeenCalledWith(expect.stringContaining(samples.events.shipments[0]!.shipmentId));
  expect(screen.getByText('已複製 IDs')).toBeVisible();
  copy.mockRejectedValue(new Error('denied'));
  await act(async () => fireEvent.click(screen.getByRole('button', { name: '複製 IDs' })));
  expect(screen.getByText('無法複製，請從下方技術資訊手動選取')).toBeVisible();
});
it('each line builds a safe stock navigation context only with matching catalog and source', () => {
  const order = samples.events.order;
  const stockCatalog = new Catalog([{ owner: { ownerId: order.ownerId, code: 'O', name: 'Owner' },
    facilities: [{ facilityId: order.facilityId, code: 'F', name: 'Facility' }], products: [],
    locations: [{ facilityId: order.facilityId, locationId: samples.events.stockOperation.operation.fromLocationId, code: 'L', name: 'Location' }],
    skus: [{ skuId: 'sku', ownerId: order.ownerId, skuCode: order.lines[0]!.skuCode, productCode: 'P', productName: 'Product', specName: 'Spec', weightGram: 1 }],
  }]);
  const { rerender } = render(<MemoryRouter><FulfillmentDetails data={samples.events} catalog={stockCatalog} list={list} /></MemoryRouter>);
  const link = screen.getByRole('link', { name: '前往庫存補貨' });
  expect(link).toHaveAttribute('href', expect.stringContaining(`returnOrderId=${order.orderId}`));
  const wrong = structuredClone(samples.events);
  wrong.stockOperation.source.operationUnitKey = 'SECONDARY';
  rerender(<MemoryRouter><FulfillmentDetails data={wrong} catalog={stockCatalog} list={list} /></MemoryRouter>);
  expect(screen.queryByRole('link', { name: '前往庫存補貨' })).not.toBeInTheDocument();
});

it('links Temporal workflows using configured UI and namespace even when query is unavailable', () => {
  vi.stubEnv('VITE_TEMPORAL_UI_URL', 'https://temporal.example.test/ui/');
  vi.stubEnv('VITE_TEMPORAL_NAMESPACE', 'allocation-dev');
  try {
    const { rerender } = render(<MemoryRouter><FulfillmentDetails data={{ ...samples.temporal,
      temporalWorkflow: null, workflowQueryStatus: 'UNAVAILABLE' }} catalog={catalog} list={list} /></MemoryRouter>);
    const link = screen.getByRole('link', { name: '在 Temporal UI 查看 Workflow（新分頁）' });
    expect(link).toHaveAttribute('href', `https://temporal.example.test/ui/namespaces/allocation-dev/workflows/${encodeURIComponent(`order-fulfillment/${samples.temporal.order.orderId}`)}`);
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    rerender(<MemoryRouter><FulfillmentDetails data={samples.events} catalog={catalog} list={list} /></MemoryRouter>);
    expect(screen.queryByRole('link', { name: /在 Temporal UI/ })).not.toBeInTheDocument();
  } finally {
    vi.unstubAllEnvs();
  }
});

it('outside click closes the drawer and aborts tracking; inside whitespace stays open', () => {
  const get = vi.spyOn(client, 'getOrderFulfillment').mockImplementation(() => new Promise(() => {}));
  render(<MemoryRouter initialEntries={[`/orders?sku=TEA&orderId=${samples.events.order.orderId}`]}>
    <FulfillmentDrawerRoute catalog={catalog} /><LocationProbe />
  </MemoryRouter>);
  const drawer = screen.getByRole('dialog');
  vi.spyOn(drawer, 'getBoundingClientRect').mockReturnValue({ left: 400, right: 1000, top: 0, bottom: 800 } as DOMRect);
  fireEvent.click(drawer, { clientX: 500, clientY: 200 });
  expect(drawer).toBeInTheDocument();
  fireEvent.click(screen.getByText('訂單履約'));
  expect(drawer).toBeInTheDocument();
  fireEvent.click(drawer, { clientX: 100, clientY: 200 });
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(screen.getByTestId('url')).toHaveTextContent('/orders?sku=TEA');
  expect(get.mock.calls[0]![1]!.aborted).toBe(true);
});

it('keeps the same business status when Temporal query is unavailable, with diagnostics collapsed', async () => {
  vi.spyOn(client, 'getOrderFulfillment').mockResolvedValue({ ...samples.events,
    orchestrationMode: 'temporal', workflowQueryStatus: 'UNAVAILABLE', temporalWorkflow: null });
  render(<MemoryRouter initialEntries={[`/orders?orderId=${samples.events.order.orderId}`]}>
    <FulfillmentDrawerRoute catalog={catalog} />
  </MemoryRouter>);
  await screen.findByText(samples.events.order.externalOrderNo);
  expect(screen.getByLabelText(/FULFILLED，.*目前狀態/)).toHaveAttribute('aria-current', 'step');
  expect(screen.getByText('自動追蹤已停止')).toBeVisible();
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  const diagnostic = screen.getByText(/Workflow 查詢暫時不可用；/);
  expect(diagnostic).not.toBeVisible();
  fireEvent.click(screen.getByText('流程協調技術資訊'));
  expect(diagnostic).toBeVisible();
});

it('shows the Temporal shortcut next to tracking only for a confirmed Workflow', async () => {
  const get = vi.spyOn(client, 'getOrderFulfillment').mockResolvedValue(samples.temporal);
  const open = vi.spyOn(window, 'open').mockReturnValue(null);
  render(<MemoryRouter initialEntries={[`/orders?orderId=${samples.temporal.order.orderId}`]}>
    <FulfillmentDrawerRoute catalog={catalog} />
  </MemoryRouter>);
  const button = await screen.findByRole('button', { name: 'Temporal' });
  expect(button.previousElementSibling).toHaveTextContent('自動追蹤');
  fireEvent.click(button);
  expect(open).toHaveBeenCalledWith(screen.getByRole('link', { name: /在 Temporal UI 查看 Workflow/ }).getAttribute('href'),
    '_blank', 'noopener,noreferrer');
  get.mockResolvedValue({ ...samples.temporal, temporalWorkflow: null, workflowQueryStatus: 'NOT_FOUND' });
  await act(async () => fireEvent.click(screen.getByRole('button', { name: '重新查詢履約' })));
  expect(screen.queryByRole('button', { name: 'Temporal' })).not.toBeInTheDocument();
  get.mockResolvedValue({ ...samples.temporal, orchestrationMode: 'events', temporalWorkflow: null, workflowQueryStatus: 'NOT_APPLICABLE' });
  await act(async () => fireEvent.click(screen.getByRole('button', { name: '重新查詢履約' })));
  expect(screen.queryByRole('button', { name: 'Temporal' })).not.toBeInTheDocument();
});
