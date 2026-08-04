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
const NODE = { facilityId: '00000000-0000-0000-0000-000000000011', code: 'WH-NORTH', name: '北部倉' };

/**
 * 守著「送出補貨之後不自動重查」。
 *
 * <p>這條規則的實際風險在這一層而不在元件裡：`StockPage` 只要在 `onReplenish` 之後順手接一句
 * `lines.run(...)`，畫面就會多發一次查詢——而那正是最容易「順手」加上去的一行。元件測試看不
 * 到它，因為元件根本不知道查詢怎麼發。
 *
 * <p>補貨回 202：發布成功不代表配置完成，庫存變更走 Kafka consumer。立刻重查在本機通常快到
 * 看不出來，但慢的那一次畫面會說謊，而且分不出「還沒處理到」與「處理完了但數字真的沒變」。
 */
describe('StockPage 的請求時機', () => {
  beforeEach(() => {
    vi.spyOn(client, 'listOwners').mockResolvedValue([OWNER]);
    vi.spyOn(client, 'listFacilities').mockResolvedValue([NODE]);
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
    vi.spyOn(client, 'getStockInWarehouse').mockResolvedValue({ skus: [] });
    vi.spyOn(client, 'replenish').mockResolvedValue({
      eventId: 'evt-1',
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

    expect(client.getStockInWarehouse).not.toHaveBeenCalled();
  });

  it('按查詢才發一次，且帶著貨主與倉別', async () => {
    render(<StockPage />);
    const user = userEvent.setup();
    await waitFor(() => expect(client.listOwners).toHaveBeenCalled());

    await user.selectOptions(screen.getByLabelText('貨主'), OWNER.ownerId);
    await user.selectOptions(screen.getByLabelText('倉別'), NODE.facilityId);
    await user.click(screen.getByRole('button', { name: '查詢庫存' }));

    expect(client.getStockInWarehouse).toHaveBeenCalledExactlyOnceWith(
      OWNER.ownerId,
      NODE.facilityId,
    );
  });

  it('送出補貨後不自動重查，重查鍵按了才發第二次', async () => {
    render(<StockPage />);
    const user = userEvent.setup();
    await waitFor(() => expect(client.listOwners).toHaveBeenCalled());

    await user.selectOptions(screen.getByLabelText('貨主'), OWNER.ownerId);
    await user.selectOptions(screen.getByLabelText('倉別'), NODE.facilityId);
    await user.click(screen.getByRole('button', { name: '查詢庫存' }));
    await screen.findByRole('button', { name: '補貨' });
    expect(client.getStockInWarehouse).toHaveBeenCalledTimes(1);

    // 這個倉沒有這個規格，所以兩個日期是空的——要自己填，而那正是「開一批新的」。
    await user.click(screen.getByRole('button', { name: '補貨' }));
    await user.type(screen.getByLabelText('入庫日'), '2026-07-01');
    await user.type(screen.getByLabelText('效期'), '2027-07-01');
    await user.click(screen.getByRole('button', { name: '送出補貨' }));
    await screen.findByRole('button', { name: '重新查詢' });

    // **送出之後庫存查詢的次數不變。** 這一行是這支測試的全部——`onReplenish` 後面順手接
    // 一句 `lines.run(...)` 就會讓它變成 2，而那一行看起來完全合理。
    expect(client.replenish).toHaveBeenCalledTimes(1);
    expect(client.getStockInWarehouse).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('button', { name: '重新查詢' }));
    expect(client.getStockInWarehouse).toHaveBeenCalledTimes(2);
  });
});
