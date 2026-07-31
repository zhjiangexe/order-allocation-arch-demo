import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { Catalog } from '../api/catalog';
import type { OrderView, OwnerView } from '../api/types';
import { OrderTable } from './OrderTable';

const OWNER_A: OwnerView = {
  ownerId: '00000000-0000-0000-0000-000000000001',
  code: 'OWNER-A',
  name: '甲貨主',
};

const OWNER_B: OwnerView = {
  ownerId: '00000000-0000-0000-0000-000000000002',
  code: 'OWNER-B',
  name: '乙貨主',
};

/** 兩個貨主刻意共用 SKU 代碼、指向各自不同的商品——這是 3PL 撞號的最小再現。 */
const CATALOG = new Catalog([
  {
    owner: OWNER_A,
    nodes: [{ nodeId: '00000000-0000-0000-0000-000000000011', code: 'WH-NORTH', name: '北部倉' }],
    products: [
      {
        productId: 'p-a',
        ownerId: OWNER_A.ownerId,
        productCode: 'P-TEA',
        name: '烏龍茶',
        temperatureZone: 'AMBIENT',
      },
    ],
    skus: [
      {
        skuId: 'sku-a',
        ownerId: OWNER_A.ownerId,
        skuCode: 'SKU-AVAILABLE',
        productCode: 'P-TEA',
        specName: '500ml',
        weightGram: 520,
        productName: '烏龍茶',
      },
    ],
  },
  {
    owner: OWNER_B,
    nodes: [{ nodeId: '00000000-0000-0000-0000-000000000013', code: 'WH-SOUTH', name: '南部倉' }],
    products: [
      {
        productId: 'p-b',
        ownerId: OWNER_B.ownerId,
        productCode: 'P-TEA',
        name: '麥茶',
        temperatureZone: 'AMBIENT',
      },
    ],
    skus: [
      {
        skuId: 'sku-b',
        ownerId: OWNER_B.ownerId,
        skuCode: 'SKU-AVAILABLE',
        productCode: 'P-TEA',
        specName: '600ml',
        weightGram: 610,
        productName: '麥茶',
      },
    ],
  },
]);

function order(overrides: Partial<OrderView> & Pick<OrderView, 'orderId' | 'ownerId'>): OrderView {
  return {
    externalOrderNo: 'PO-1',
    fulfillmentNodeId: '00000000-0000-0000-0000-000000000011',
    shipToZone: '100',
    shipToAddress: '台北市中正區重慶南路一段 122 號',
    promisedDeliveryDate: '2026-08-03',
    lines: [{ lineNo: 1, skuCode: 'SKU-AVAILABLE', quantity: 3, status: 'PENDING' }],
    status: 'PENDING',
    receivedAt: '2026-07-27T10:00:00Z',
    // 預設不帶上游的下單時刻——「上游沒送」是常態，讓它成為預設值，要驗有值的測試自己覆蓋。
    placedAt: null,
    allocatedAt: null,
    backOrderedSince: null,
    cancelledAt: null,
    ...overrides,
  };
}

describe('OrderTable', () => {
  it('同碼 SKU 的兩張單依貨主解析成不同的品名與規格', () => {
    render(
      <OrderTable
        orders={[
          order({ orderId: 'aaaaaaaa-0000-0000-0000-00000000000a', ownerId: OWNER_A.ownerId }),
          order({ orderId: 'bbbbbbbb-0000-0000-0000-00000000000b', ownerId: OWNER_B.ownerId }),
        ]}
        catalog={CATALOG}
      />,
    );

    const [, rowA, rowB] = screen.getAllByRole('row');
    expect(within(rowA!).getByText('甲貨主')).toBeInTheDocument();
    expect(within(rowA!).getByText('烏龍茶 · 500ml')).toBeInTheDocument();
    expect(within(rowB!).getByText('乙貨主')).toBeInTheDocument();
    expect(within(rowB!).getByText('麥茶 · 600ml')).toBeInTheDocument();
  });

  it('多行時每一行各自成列，不只顯示第一行', () => {
    render(
      <OrderTable
        orders={[
          order({
            orderId: 'cccccccc-0000-0000-0000-00000000000c',
            ownerId: OWNER_A.ownerId,
            lines: [
              { lineNo: 1, skuCode: 'SKU-AVAILABLE', quantity: 3, status: 'PENDING' },
              { lineNo: 2, skuCode: 'SKU-UNKNOWN', quantity: 5, status: 'PENDING' },
            ],
          }),
        ]}
        catalog={CATALOG}
      />,
    );

    const row = screen.getAllByRole('row')[1]!;
    expect(within(row).getByText('烏龍茶 · 500ml')).toBeInTheDocument();
    // 第二行的主檔查不到，那一行就只剩代碼——不會空白，也不會把代碼印兩次
    expect(within(row).getAllByText('SKU-UNKNOWN')).toHaveLength(1);
    expect(within(row).getByText('3、5')).toBeInTheDocument();
  });

  it('每一行都顯示 SKU 代碼，讓人拿去庫存頁查詢或補貨', () => {
    render(
      <OrderTable
        orders={[order({ orderId: 'eeeeeeee-0000-0000-0000-00000000000e', ownerId: OWNER_A.ownerId })]}
        catalog={CATALOG}
      />,
    );

    // 品名好讀，但庫存頁認的是代碼；只顯示品名就等於要人自己回想代碼
    const row = screen.getAllByRole('row')[1]!;
    expect(within(row).getByText('烏龍茶 · 500ml')).toBeInTheDocument();
    expect(within(row).getByText('SKU-AVAILABLE')).toBeInTheDocument();
  });

  it('貨主主檔還沒載到時退回顯示識別碼尾段，不留白', () => {
    render(
      <OrderTable
        orders={[order({ orderId: 'dddddddd-0000-0000-0000-00000000000d', ownerId: OWNER_A.ownerId })]}
        catalog={new Catalog([])}
      />,
    );

    expect(screen.getByText(OWNER_A.ownerId.slice(-12))).toBeInTheDocument();
    expect(screen.getAllByText('SKU-AVAILABLE')).toHaveLength(1);
  });

  it('上游有送下單時刻時，與收單時刻分別顯示', () => {
    render(
      <OrderTable
        orders={[order({
          orderId: 'ffffffff-0000-0000-0000-00000000000f',
          ownerId: OWNER_A.ownerId,
          receivedAt: '2026-07-27T10:00:00Z',
          placedAt: '2026-07-27T08:30:00Z',
        })]}
        catalog={CATALOG}
      />,
    );

    const row = screen.getAllByRole('row')[1]!;
    const cells = within(row).getAllByRole('cell');
    // 收單與上游下單是相鄰的兩欄，值必須不同——相同的話看的人分不出上游是否真的送了。
    expect(cells[5]!.textContent).not.toEqual(cells[6]!.textContent);
    expect(cells[5]!.textContent).not.toEqual('—');
    expect(cells[6]!.textContent).not.toEqual('—');
  });

  it('上游沒送下單時刻時該欄留白，不重複收單時刻', () => {
    render(
      <OrderTable
        orders={[order({
          orderId: 'ffffffff-1111-0000-0000-00000000000f',
          ownerId: OWNER_A.ownerId,
          receivedAt: '2026-07-27T10:00:00Z',
          placedAt: null,
        })]}
        catalog={CATALOG}
      />,
    );

    const row = screen.getAllByRole('row')[1]!;
    const cells = within(row).getAllByRole('cell');
    expect(cells[5]!.textContent).not.toEqual('—');
    expect(cells[6]!.textContent).toEqual('—');
  });
});
