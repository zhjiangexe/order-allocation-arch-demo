import { transferableAbortController } from 'node:util';
import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as client from '../api/client';
import { StockPage } from './StockPage';

// React Router 的 Node Request 必須搭配同一 realm 的 AbortSignal。
beforeEach(() => vi.stubGlobal('AbortController', class {
  constructor() { return transferableAbortController(); }
}));
afterEach(() => vi.unstubAllGlobals());

const OWNER = {
  ownerId: '00000000-0000-0000-0000-000000000001',
  code: 'OWNER-A',
  name: '甲貨主',
};
const FACILITY = { facilityId: '00000000-0000-0000-0000-000000000011', code: 'WH-NORTH', name: '北部倉' };
const LOCATION = {
  locationId: '00000000-0000-0000-0000-000000000021',
  facilityId: FACILITY.facilityId,
  code: 'WH-NORTH/Stock-A',
  name: '北部倉／A 區',
};

function renderPage(url = '/stock') {
  const router = createMemoryRouter([
    { path: '/stock', element: <StockPage /> },
    { path: '/orders', element: <p>訂單目的頁</p> },
    { path: '/allocations', element: <p>佇列目的頁</p> },
  ], { initialEntries: [url] });
  render(<RouterProvider router={router} />);
  return router;
}

/**
 * 守著「確認收貨之後不自動覆寫查詢快照」。
 *
 * <p>這條規則的實際風險在這一層而不在元件裡：`StockPage` 只要在 `onConfirmReceipt` 之後順手接一句
 * `lines.run(...)`，畫面就會多發一次查詢——而那正是最容易「順手」加上去的一行。元件測試看不
 * 到它，因為元件根本不知道查詢怎麼發。
 *
 * <p>收貨同步完成，但同一交易可能立刻把新量配置給缺貨訂單。刷新仍由使用者明確觸發。
 */
describe('StockPage 的請求時機', () => {
  beforeEach(() => {
    vi.spyOn(client, 'listOwners').mockResolvedValue([OWNER]);
    vi.spyOn(client, 'listFacilities').mockResolvedValue([FACILITY]);
    vi.spyOn(client, 'listStockLocations').mockResolvedValue([LOCATION]);
    vi.spyOn(client, 'listProducts').mockResolvedValue([
      {
        productId: 'p-tea',
        ownerId: OWNER.ownerId,
        productCode: 'P-TEA',
        name: '烏龍茶',
        temperatureZone: 'AMBIENT',
      },
    ]);
    vi.spyOn(client, 'listSkus').mockResolvedValue([
      {
        skuId: 's-tea',
        ownerId: OWNER.ownerId,
        skuCode: 'SKU-TEA',
        productCode: 'P-TEA',
        specName: '500ml',
        weightGram: 520,
      },
    ]);
    vi.spyOn(client, 'getStockInLocation').mockResolvedValue({ skus: [] });
    vi.spyOn(client, 'confirmStockReceipt').mockResolvedValue({
      receiptId: 'receipt-1',
      sku: 'SKU-1',
      quantity: 500,
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('進場不查庫存——查詢是使用者按出來的', async () => {
    renderPage();
    await screen.findByLabelText('貨主');

    expect(client.getStockInLocation).not.toHaveBeenCalled();
  });

  it('按查詢才發一次，且帶著貨主與設施', async () => {
    renderPage();
    const user = userEvent.setup();
    await screen.findByLabelText('貨主');

    await user.selectOptions(screen.getByLabelText('貨主'), OWNER.ownerId);
    await user.selectOptions(screen.getByLabelText('設施'), FACILITY.facilityId);
    await user.selectOptions(screen.getByLabelText('庫位'), LOCATION.locationId);
    await user.click(screen.getByRole('button', { name: '查詢庫存' }));

    expect(client.getStockInLocation).toHaveBeenCalledExactlyOnceWith(
      OWNER.ownerId,
      LOCATION.locationId,
    );
  });

  it('確認收貨後不自動重查，重查鍵按了才發第二次', async () => {
    renderPage();
    const user = userEvent.setup();
    await screen.findByLabelText('貨主');

    await user.selectOptions(screen.getByLabelText('貨主'), OWNER.ownerId);
    await user.selectOptions(screen.getByLabelText('設施'), FACILITY.facilityId);
    await user.selectOptions(screen.getByLabelText('庫位'), LOCATION.locationId);
    await user.click(screen.getByRole('button', { name: '查詢庫存' }));
    await screen.findByRole('button', { name: '收貨' });
    expect(client.getStockInLocation).toHaveBeenCalledTimes(1);

    // 這個倉沒有這個規格，所以兩個日期是空的——要自己填，而那正是「開一批新的」。
    await user.click(screen.getByRole('button', { name: '收貨' }));
    await user.type(screen.getByLabelText('收貨日'), '2026-07-01');
    await user.type(screen.getByLabelText('效期'), '2027-07-01');
    await user.click(screen.getByRole('button', { name: '確認收貨' }));
    await screen.findByRole('button', { name: '重新查詢' });

    // **送出之後庫存查詢的次數不變。** 這一行是這支測試的全部——`onConfirmReceipt` 後面順手接
    // 一句 `lines.run(...)` 就會讓它變成 2，而那一行看起來完全合理。
    expect(client.confirmStockReceipt).toHaveBeenCalledTimes(1);
    expect(client.getStockInLocation).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('button', { name: '重新查詢' }));
    expect(client.getStockInLocation).toHaveBeenCalledTimes(2);
  });
});

const ORDER_ID = '00000000-0000-0000-0000-000000000099';
function receiptPath(overrides: Record<string, string> = {}) {
  return '/stock?' + new URLSearchParams({
    ownerId: OWNER.ownerId, facilityId: FACILITY.facilityId, locationId: LOCATION.locationId,
    sku: 'SKU-TEA', returnOrderId: ORDER_ID, returnPage: '/allocations', return_sku: 'SKU', ...overrides,
  });
}

async function submitReceipt(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: '收貨' }));
  await user.type(screen.getByLabelText('收貨日'), '2026-07-01');
  await user.type(screen.getByLabelText('效期'), '2027-07-01');
  await user.clear(screen.getByLabelText('實收數量'));
  await user.type(screen.getByLabelText('實收數量'), '12');
  await user.click(screen.getByRole('button', { name: '確認收貨' }));
}

describe('T6 補貨定位與重試', () => {
  beforeEach(() => {
    vi.spyOn(client, 'listOwners').mockResolvedValue([OWNER]);
    vi.spyOn(client, 'listFacilities').mockResolvedValue([FACILITY]);
    vi.spyOn(client, 'listStockLocations').mockResolvedValue([LOCATION]);
    vi.spyOn(client, 'listProducts').mockResolvedValue([{
      productId: 'p', ownerId: OWNER.ownerId, productCode: 'P-TEA', name: '茶', temperatureZone: 'AMBIENT',
    }]);
    vi.spyOn(client, 'listSkus').mockResolvedValue([{
      skuId: 's', ownerId: OWNER.ownerId, skuCode: 'SKU-TEA', productCode: 'P-TEA', specName: '茶', weightGram: 1,
    }]);
    vi.spyOn(client, 'getStockInLocation').mockResolvedValue({ skus: [] });
    vi.spyOn(client, 'confirmStockReceipt').mockImplementation(async request => ({
      receiptId: request.receiptId, sku: request.sku, quantity: request.quantity,
    }));
  });
  afterEach(() => vi.restoreAllMocks());

  it('延遲主檔載入完成才定位正確庫位，沒有自動收貨', async () => {
    let resolve!: (value: typeof OWNER[]) => void;
    vi.mocked(client.listOwners).mockReturnValue(new Promise(done => { resolve = done; }));
    renderPage(receiptPath());
    expect(screen.getByText('載入主檔中…')).toBeInTheDocument();
    expect(client.getStockInLocation).not.toHaveBeenCalled();
    resolve([OWNER]);
    await screen.findByRole('button', { name: '收貨' });
    expect(screen.getByLabelText('庫位')).toHaveValue(LOCATION.locationId);
    expect(client.getStockInLocation).toHaveBeenCalledExactlyOnceWith(OWNER.ownerId, LOCATION.locationId);
    expect(client.confirmStockReceipt).not.toHaveBeenCalled();
    expect(screen.getByText('本次補貨品項')).toBeInTheDocument();
  });

  it.each([
    { locationId: 'invalid' }, { locationId: ORDER_ID }, { ownerId: ORDER_ID },
    { facilityId: ORDER_ID }, { sku: 'NOT-OWNED' }, { returnOrderId: 'invalid' }, { returnPage: '//evil.test' },
  ])('無效定位 %j 不查錯誤庫位也不建立返回訂單', async overrides => {
    renderPage(receiptPath(overrides));
    await screen.findByText(/補貨定位參數無效/);
    expect(screen.getByLabelText('貨主')).toHaveValue('');
    expect(client.getStockInLocation).not.toHaveBeenCalled();
    expect(screen.queryByRole('link', { name: '返回履約' })).not.toBeInTheDocument();
  });

  it('主檔失敗顯示錯誤，不一直等待或猜測庫位', async () => {
    vi.mocked(client.listSkus).mockRejectedValue(new Error('catalog unavailable'));
    renderPage(receiptPath());
    expect(await screen.findByRole('alert')).toHaveTextContent('catalog unavailable');
    expect(client.getStockInLocation).not.toHaveBeenCalled();
  });

  it('直接進場沒有虛構返回履約連結', async () => {
    renderPage();
    await screen.findByLabelText('貨主');
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('成功收貨保留實收結果，返回原履約並恢復篩選', async () => {
    const router = renderPage(receiptPath());
    const user = userEvent.setup();
    await submitReceipt(user);
    await screen.findByText(/已完成收貨/);
    expect(client.confirmStockReceipt).toHaveBeenCalledWith(expect.objectContaining({
      ownerId: OWNER.ownerId, facilityId: FACILITY.facilityId, locationId: LOCATION.locationId,
      sku: 'SKU-TEA', quantity: 12,
    }));
    expect(screen.getByText(/不代表原訂單已完成配貨/)).toBeInTheDocument();
    expect(client.getStockInLocation).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole('link', { name: '返回履約' }));
    expect(router.state.location.pathname).toBe('/allocations');
    expect(new URLSearchParams(router.state.location.search).get('orderId')).toBe(ORDER_ID);
    expect(new URLSearchParams(router.state.location.search).get('sku')).toBe('SKU');
  });

  it('結果不明使用相同 ID 與 payload 重試，送出期間不可重複收貨', async () => {
    vi.mocked(client.confirmStockReceipt).mockRejectedValueOnce(new Error('connection lost'));
    const user = userEvent.setup();
    renderPage(receiptPath());
    await submitReceipt(user);
    await screen.findByRole('button', { name: '重試原收貨' });
    const first = vi.mocked(client.confirmStockReceipt).mock.calls[0]![0];
    expect(screen.getByRole('button', { name: '收貨' })).toBeDisabled();
    expect(screen.getByLabelText('庫位')).toBeDisabled();
    let resolve!: (result: clientResult) => void;
    type clientResult = Awaited<ReturnType<typeof client.confirmStockReceipt>>;
    vi.mocked(client.confirmStockReceipt).mockReturnValueOnce(new Promise(done => { resolve = done; }));
    await user.dblClick(screen.getByRole('button', { name: '重試原收貨' }));
    expect(client.confirmStockReceipt).toHaveBeenCalledTimes(2);
    expect(vi.mocked(client.confirmStockReceipt).mock.calls[1]![0]).toEqual(first);
    const unload = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(unload);
    expect(unload.defaultPrevented).toBe(true);
    resolve({ receiptId: first.receiptId, sku: first.sku, quantity: first.quantity });
    await screen.findByText(/已完成收貨/);
    const after = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(after);
    expect(after.defaultPrevented).toBe(false);
  });

  it('未確認離頁可以留在此頁，明確離開才放行，返回原佇列不開詳情', async () => {
    vi.mocked(client.confirmStockReceipt).mockRejectedValue(new Error('timeout'));
    const router = renderPage(receiptPath());
    const user = userEvent.setup();
    await submitReceipt(user);
    await user.click(screen.getByRole('link', { name: '返回原佇列' }));
    expect(router.state.location.pathname).toBe('/stock');
    await user.click(screen.getByRole('button', { name: '留在此頁' }));
    expect(screen.getByRole('button', { name: '重試原收貨' })).toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: '返回原佇列' }));
    await user.click(screen.getByRole('button', { name: '仍要離開' }));
    expect(router.state.location.pathname).toBe('/allocations');
    expect(router.state.location.search).toBe('?sku=SKU');
  });

  it('另起操作需明確確認，修改數量使用新 ID', async () => {
    vi.mocked(client.confirmStockReceipt).mockRejectedValueOnce(new Error('timeout'));
    renderPage(receiptPath());
    const user = userEvent.setup();
    await submitReceipt(user);
    const first = vi.mocked(client.confirmStockReceipt).mock.calls[0]![0];
    await user.click(screen.getByRole('button', { name: '另起收貨操作' }));
    expect(screen.getByText(/可能重複增加庫存/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '已確認，另起操作' }));
    await submitReceipt(user);
    const second = vi.mocked(client.confirmStockReceipt).mock.calls[1]![0];
    expect(second.receiptId).not.toBe(first.receiptId);
  });
});
