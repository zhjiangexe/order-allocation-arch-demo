import { afterEach, expect, it, vi } from 'vitest';
import { ApiError, getOrderFulfillment, listConfirmedStockOperations } from './client';
import samples from '../test/fixtures/fulfillment.json';

afterEach(() => vi.unstubAllGlobals());
it('reads the additive fulfillment contract and passes AbortSignal', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify(samples.temporal)));
  vi.stubGlobal('fetch', fetch);
  const controller = new AbortController();
  const result = await getOrderFulfillment('id/a', controller.signal);
  expect(fetch).toHaveBeenCalledWith('/api/demo/orders/id%2Fa/fulfillment',
    expect.objectContaining({ signal: controller.signal }));
  expect(result.temporalWorkflow?.updatedAt).toBe(samples.temporal.temporalWorkflow.updatedAt);
  expect(result.workflowQueryStatus).toBe('AVAILABLE');
});
it('reads only the supported CONFIRMED queue', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify([samples.events.stockOperation])));
  vi.stubGlobal('fetch', fetch);
  const controller = new AbortController();
  expect(await listConfirmedStockOperations(200, controller.signal)).toHaveLength(1);
  expect(fetch).toHaveBeenCalledWith('/api/stock-operations?state=CONFIRMED&limit=200',
    expect.objectContaining({ signal: controller.signal }));
});
it('preserves HTTP errors', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('Order not found', { status: 404 })));
  await expect(getOrderFulfillment('missing')).rejects.toEqual(new ApiError(404, 'Order not found'));
});
