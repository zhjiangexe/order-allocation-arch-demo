import { describe, expect, it } from 'vitest';

import { Catalog } from './catalog';
import { facilityStockLines } from './stockLines';
import type { StockBatchView, StockPoolView } from './types';

const OWNER_ID = '00000000-0000-0000-0000-000000000001';
const FACILITY_ID = '00000000-0000-0000-0000-000000000011';

/** 兩款各一個規格，外加第一款的第二個規格——列的順序要驗得出「款 → 規格」。 */
function catalog() {
  return new Catalog([
    {
      owner: { ownerId: OWNER_ID, code: 'OWNER-A', name: '甲貨主' },
      facilities: [{ facilityId: FACILITY_ID, code: 'WH-NORTH', name: '北部倉' }],
      locations: [],
      products: [
        {
          productId: 'p-tea',
          ownerId: OWNER_ID,
          productCode: 'P-TEA',
          name: '烏龍茶',
          temperatureZone: 'AMBIENT' as const,
        },
        {
          productId: 'p-coffee',
          ownerId: OWNER_ID,
          productCode: 'P-COFFEE',
          name: '黑咖啡',
          temperatureZone: 'CHILLED' as const,
        },
      ],
      skus: [
        {
          skuId: 's-tea-500',
          ownerId: OWNER_ID,
          skuCode: 'SKU-TEA-500',
          productCode: 'P-TEA',
          specName: '500ml',
          weightGram: 520,
          productName: '烏龍茶',
        },
        {
          skuId: 's-tea-1l',
          ownerId: OWNER_ID,
          skuCode: 'SKU-TEA-1L',
          productCode: 'P-TEA',
          specName: '1L',
          weightGram: 1020,
          productName: '烏龍茶',
        },
        {
          skuId: 's-coffee',
          ownerId: OWNER_ID,
          skuCode: 'SKU-COFFEE',
          productCode: 'P-COFFEE',
          specName: '330ml',
          weightGram: 350,
          productName: '黑咖啡',
        },
      ],
    },
  ]);
}

let nextId = 0;

function batch(overrides: Partial<StockBatchView>): StockBatchView {
  nextId += 1;
  return {
    stockPoolId: `pool-${nextId}`,
    inDate: '2026-01-05',
    expiryDate: '2026-12-31',
    onHandQuantity: 0,
    reservedQuantity: 0,
    availableToPromise: 0,
    expired: false,
    ...overrides,
  };
}

function held(skus: StockPoolView['skus']): StockPoolView {
  return { skus };
}

describe('facilityStockLines', () => {
  it('列出該貨主的每一個規格，這個倉沒有的四個數字都是 0', () => {
    const lines = facilityStockLines(
      catalog(),
      OWNER_ID,
      held([{ sku: 'SKU-TEA-500', batches: [batch({ onHandQuantity: 10, availableToPromise: 10 })] }]),
    );

    // 那些零就是「這個倉缺什麼」，而收貨鍵因此到得了每一個規格。只列有貨的會讓這個倉從未
    // 放過的貨品再也進不去——而現在的表單補得了。
    expect(lines).toHaveLength(3);
    const empty = lines.filter((line) => line.skuCode !== 'SKU-TEA-500');
    expect(empty).toHaveLength(2);
    for (const line of empty) {
      expect(line.onHandQuantity).toBe(0);
      expect(line.reservedQuantity).toBe(0);
      expect(line.availableToPromise).toBe(0);
      expect(line.expiredQuantity).toBe(0);
      expect(line.batches).toHaveLength(0);
    }
  });

  it('順序照主檔的款 → 規格，不隨數量變動', () => {
    const lines = facilityStockLines(
      catalog(),
      OWNER_ID,
      // 刻意讓最後一款有最多貨——若排序跟著數量走，它會跑到第一個。
      held([{ sku: 'SKU-COFFEE', batches: [batch({ onHandQuantity: 999, availableToPromise: 999 })] }]),
    );

    // 收貨的結果要手動重查才看得到，排序一旦跟著數量走，你補的那一列就會跳走——而重查的
    // 整個目的就是看它變了什麼。
    expect(lines.map((line) => line.skuCode)).toEqual([
      'SKU-TEA-500',
      'SKU-TEA-1L',
      'SKU-COFFEE',
    ]);
  });

  it('在手含過期、可承諾不含，差額由已過期解釋', () => {
    const lines = facilityStockLines(
      catalog(),
      OWNER_ID,
      held([
        {
          sku: 'SKU-TEA-500',
          batches: [
            batch({ onHandQuantity: 60, reservedQuantity: 60, availableToPromise: 0 }),
            batch({ onHandQuantity: 40, reservedQuantity: 20, availableToPromise: 20 }),
            batch({ onHandQuantity: 30, reservedQuantity: 0, availableToPromise: 30 }),
            // 過期的批：在手算它，可承諾不算——配貨的取批是「效期 >= 今天」。
            batch({ onHandQuantity: 25, availableToPromise: 25, expired: true }),
          ],
        },
      ]),
    );

    const tea = lines.find((line) => line.skuCode === 'SKU-TEA-500');
    expect(tea).toMatchObject({
      onHandQuantity: 155,
      reservedQuantity: 80,
      availableToPromise: 50,
      expiredQuantity: 25,
    });
    // 三個數字對不起來正是重點：多出來的 25 件出不了貨。合得起來的版本會讓可承諾說謊。
    expect(tea!.onHandQuantity - tea!.reservedQuantity - tea!.availableToPromise).toBe(25);
  });

  it('沒有過期批時三個數字對得起來', () => {
    const lines = facilityStockLines(
      catalog(),
      OWNER_ID,
      held([
        {
          sku: 'SKU-TEA-500',
          batches: [
            batch({ onHandQuantity: 60, reservedQuantity: 60, availableToPromise: 0 }),
            batch({ onHandQuantity: 40, reservedQuantity: 20, availableToPromise: 20 }),
            batch({ onHandQuantity: 30, reservedQuantity: 0, availableToPromise: 30 }),
          ],
        },
      ]),
    );

    expect(lines.find((line) => line.skuCode === 'SKU-TEA-500')).toMatchObject({
      onHandQuantity: 130,
      reservedQuantity: 80,
      availableToPromise: 50,
      expiredQuantity: 0,
    });
  });

  it('有庫存但主檔查不到的規格仍要列出來，排在最後', () => {
    const lines = facilityStockLines(
      catalog(),
      OWNER_ID,
      held([{ sku: 'HOT-SKU', batches: [batch({ onHandQuantity: 500, availableToPromise: 500 })] }]),
    );

    // 漏掉它等於畫面上少報了倉庫裡真實存在的貨。壓測用的貨就是這樣——有庫存、沒主檔。
    expect(lines).toHaveLength(4);
    expect(lines[3]).toMatchObject({
      skuCode: 'HOT-SKU',
      sku: undefined,
      onHandQuantity: 500,
    });
  });

  it('批的順序照後端給的，不重排', () => {
    const near = batch({ expiryDate: '2026-08-31', onHandQuantity: 10, availableToPromise: 10 });
    const far = batch({ expiryDate: '2027-01-31', onHandQuantity: 10, availableToPromise: 10 });
    const lines = facilityStockLines(
      catalog(),
      OWNER_ID,
      held([{ sku: 'SKU-TEA-500', batches: [near, far] }]),
    );

    // 那個順序就是配貨會取用的順序，而 tie-break 一路排到 id——前端重現不了，只能照抄。
    expect(lines[0]).toMatchObject({ batches: [near, far] });
  });

  it('這個倉什麼都沒放時仍列出全部規格', () => {
    const lines = facilityStockLines(catalog(), OWNER_ID, held([]));

    // 空倉是正常答案，而且那正是新倉上線時的狀態——最需要收貨的時候。
    expect(lines).toHaveLength(3);
    expect(lines.every((line) => line.onHandQuantity === 0)).toBe(true);
  });
});
