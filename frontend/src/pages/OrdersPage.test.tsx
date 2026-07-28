import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as client from '../api/client';
import { OrdersPage } from './OrdersPage';

const FIVE_MINUTES = 5 * 60 * 1000;

/** 推進假時鐘並在其間清空 microtask，讓已解決的 promise 與 React 的狀態更新都落地。 */
async function elapse(milliseconds: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(milliseconds);
  });
}

/**
 * 守著規格的「Data is fetched only in response to a user action」——沒有輪詢、沒有會重抓的
 * timer、沒有背景刷新。
 *
 * <p>這件事原本只寫在註解裡，而註解擋不住任何人。這支測試在 render **之前**就換上假時鐘，
 * 因此掛載當下裝的 timer 也在假時鐘的掌握中；先 render 再換時鐘會讓真 timer 逃出去，測試
 * 就會無聲地永遠通過。
 *
 * <p>不用 `waitFor` 等待進場載入：它在假時鐘下不會自行推進（偵測的是 jest 全域），會直接
 * 掛到逾時。這裡改為自己推進時鐘，等待與靜置因此是同一個機制。
 */
describe('OrdersPage 的請求時機', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.spyOn(client, 'listOwners').mockResolvedValue([]);
    vi.spyOn(client, 'listRecentOrders').mockResolvedValue([]);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('進場載入完成後靜置五分鐘，不再發出任何請求', async () => {
    render(<OrdersPage />);

    await elapse(0);
    expect(client.listRecentOrders).toHaveBeenCalledTimes(1);
    expect(client.listOwners).toHaveBeenCalledTimes(1);

    await elapse(FIVE_MINUTES);

    expect(client.listRecentOrders).toHaveBeenCalledTimes(1);
    expect(client.listOwners).toHaveBeenCalledTimes(1);
  });
});
