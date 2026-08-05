import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as client from '../api/client';
import { StockPage } from './StockPage';

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
    render(<StockPage />);
    await waitFor(() => expect(client.listOwners).toHaveBeenCalled());

    expect(client.getStockInLocation).not.toHaveBeenCalled();
  });

  it('按查詢才發一次，且帶著貨主與設施', async () => {
    render(<StockPage />);
    const user = userEvent.setup();
    await waitFor(() => expect(client.listOwners).toHaveBeenCalled());

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
    render(<StockPage />);
    const user = userEvent.setup();
    await waitFor(() => expect(client.listOwners).toHaveBeenCalled());

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
