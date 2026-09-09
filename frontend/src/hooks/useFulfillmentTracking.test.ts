import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { getOrderFulfillment } from '../api/client';
import type { OrderFulfillmentView } from '../api/types';
import samples from '../test/fixtures/fulfillment.json';
import { useFulfillmentTracking } from './useFulfillmentTracking';

vi.mock('../api/client', () => ({ getOrderFulfillment: vi.fn() }));
const get = vi.mocked(getOrderFulfillment);
const complete = (mode: 'events' | 'temporal' = 'events'): OrderFulfillmentView => structuredClone(samples[mode]);
const waiting = (): OrderFulfillmentView => {
  const data = complete();
  data.order.status = 'PENDING';
  data.stockOperation = null;
  data.shipments = [];
  return data;
};
const flush = async () => { await act(async () => {}); };
const advance = async (ms: number) => { await act(async () => { await vi.advanceTimersByTimeAsync(ms); }); };
function visibility(hidden: boolean) {
  Object.defineProperty(document, 'hidden', { configurable: true, value: hidden });
  document.dispatchEvent(new Event('visibilitychange'));
}

beforeEach(() => { vi.useFakeTimers(); get.mockReset(); visibility(false); });
afterEach(() => { vi.useRealTimers(); });

describe('useFulfillmentTracking', () => {
  it.each(['events', 'temporal'] as const)('stops when %s completion is proven', async mode => {
    const data = complete(mode);
    get.mockResolvedValue(data);
    const { result, unmount } = renderHook(() => useFulfillmentTracking(data.order.orderId));
    await flush();
    expect(result.current.progress?.state).toBe('completed');
    await advance(10000);
    expect(get).toHaveBeenCalledTimes(1);
    unmount();
  });
  it('serializes slow requests and manual refresh, then polls two seconds after completion', async () => {
    let resolve!: (data: OrderFulfillmentView) => void;
    get.mockImplementationOnce(() => new Promise(r => { resolve = r; })).mockResolvedValue(waiting());
    const { result, unmount } = renderHook(() => useFulfillmentTracking(waiting().order.orderId));
    await advance(10000);
    act(() => result.current.refresh());
    expect(get).toHaveBeenCalledTimes(1);
    await act(async () => resolve(waiting()));
    await advance(1999);
    expect(get).toHaveBeenCalledTimes(1);
    await advance(1);
    expect(get).toHaveBeenCalledTimes(2);
    act(() => result.current.pause());
    await advance(10000);
    expect(get).toHaveBeenCalledTimes(2);
    act(() => result.current.resume());
    await flush();
    expect(get).toHaveBeenCalledTimes(3);
    unmount();
  });
  it.each(['http', 'unavailable'])('preserves the last snapshot and timestamp after %s; retry resumes', async kind => {
    get.mockResolvedValueOnce(waiting());
    const { result, unmount } = renderHook(() => useFulfillmentTracking(waiting().order.orderId));
    await flush();
    const previous = result.current.data;
    const fetchedAt = result.current.fetchedAt;
    if (kind === 'http') get.mockRejectedValueOnce(new Error('HTTP 503'));
    else get.mockResolvedValueOnce({ ...waiting(), orchestrationMode: 'temporal', workflowQueryStatus: 'UNAVAILABLE' });
    await advance(2000);
    expect(result.current.data).toBe(previous);
    expect(result.current.fetchedAt).toBe(fetchedAt);
    expect(result.current.error).toBeTruthy();
    expect(result.current.paused).toBe(true);
    await advance(10000);
    expect(get).toHaveBeenCalledTimes(2);
    get.mockResolvedValue(waiting());
    act(() => result.current.resume());
    await flush();
    expect(result.current.error).toBeNull();
    await advance(2000);
    expect(get).toHaveBeenCalledTimes(4);
    unmount();
  });
  it('ignores a stale response on order switch and aborts on close/unmount', async () => {
    let resolve!: (data: OrderFulfillmentView) => void;
    get.mockImplementationOnce(() => new Promise(r => { resolve = r; })).mockResolvedValue(complete('temporal'));
    const { result, rerender, unmount } = renderHook(({ id }: { id: string | null }) => useFulfillmentTracking(id),
      { initialProps: { id: waiting().order.orderId as string | null } });
    const signal = get.mock.calls[0]![1]!;
    rerender({ id: complete('temporal').order.orderId });
    await flush();
    await act(async () => resolve(waiting()));
    expect(signal.aborted).toBe(true);
    expect(result.current.data?.order.orderId).toBe(complete('temporal').order.orderId);
    rerender({ id: null });
    expect(result.current.data).toBeNull();
    await advance(10000);
    expect(get).toHaveBeenCalledTimes(2);
    unmount();
  });
  it('pauses in the background; late aborted responses cannot change the foreground result', async () => {
    let resolve!: (data: OrderFulfillmentView) => void;
    get.mockImplementationOnce(() => new Promise(r => { resolve = r; })).mockResolvedValue(complete());
    const { result, unmount } = renderHook(() => useFulfillmentTracking(waiting().order.orderId));
    act(() => visibility(true));
    expect(get.mock.calls[0]![1]!.aborted).toBe(true);
    await advance(10000);
    expect(get).toHaveBeenCalledTimes(1);
    act(() => visibility(false));
    await flush();
    await act(async () => resolve(waiting()));
    expect(result.current.progress?.state).toBe('completed');
    await advance(10000);
    expect(get).toHaveBeenCalledTimes(2);
    unmount();
  });
  it('keeps tracking NOT_FOUND and stops on external cancellation', async () => {
    const data = { ...waiting(), orchestrationMode: 'temporal', workflowQueryStatus: 'NOT_FOUND' };
    get.mockResolvedValue(data);
    const { result, unmount } = renderHook(() => useFulfillmentTracking(data.order.orderId));
    await flush();
    await advance(20000);
    expect(get).toHaveBeenCalledTimes(11);
    expect(result.current.progress?.state).toBe('waiting');
    get.mockResolvedValue({ ...data, order: { ...data.order, status: 'CANCELLED' } });
    await advance(2000);
    expect(result.current.progress?.state).toBe('cancelled');
    await advance(10000);
    expect(get).toHaveBeenCalledTimes(12);
    unmount();
  });
});

it('aborts an in-flight request on unmount without scheduling another', async () => {
  let resolve!: (data: OrderFulfillmentView) => void;
  get.mockImplementation(() => new Promise(r => { resolve = r; }));
  const { unmount } = renderHook(() => useFulfillmentTracking(waiting().order.orderId));
  const signal = get.mock.calls[0]![1]!;
  unmount();
  expect(signal.aborted).toBe(true);
  await act(async () => resolve(waiting()));
  await advance(10000);
  expect(get).toHaveBeenCalledTimes(1);
});

it('manual refresh while paused does not restart auto tracking, including background return', async () => {
  get.mockResolvedValue(waiting());
  const { result, unmount } = renderHook(() => useFulfillmentTracking(waiting().order.orderId));
  await flush();
  act(() => result.current.pause());
  act(() => result.current.refresh());
  await flush();
  expect(result.current.paused).toBe(true);
  act(() => visibility(true));
  act(() => visibility(false));
  await advance(10000);
  expect(get).toHaveBeenCalledTimes(2);
  unmount();
});

it('shows initial unavailable business data while paused and stops unknown states', async () => {
  const data = { ...waiting(), orchestrationMode: 'temporal', workflowQueryStatus: 'UNAVAILABLE' };
  get.mockResolvedValueOnce(data);
  const { result, unmount } = renderHook(() => useFulfillmentTracking(data.order.orderId));
  await flush();
  expect(result.current.data).toEqual(data);
  expect(result.current.paused).toBe(true);
  get.mockResolvedValue({ ...waiting(), order: { ...data.order, status: 'FUTURE_STATE' } });
  act(() => result.current.refresh());
  await flush();
  expect(result.current.progress?.state).toBe('unknown');
  await advance(10000);
  expect(get).toHaveBeenCalledTimes(2);
  unmount();
});
