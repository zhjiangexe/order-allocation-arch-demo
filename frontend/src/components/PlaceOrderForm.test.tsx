import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { Catalog } from '../api/catalog';
import type { OwnerView } from '../api/types';
import { PlaceOrderForm } from './PlaceOrderForm';

const OWNER_A: OwnerView = {
  ownerId: '00000000-0000-0000-0000-000000000001',
  code: 'OWNER-A',
  name: '甲貨主',
};

const NODE_NORTH = '00000000-0000-0000-0000-000000000011';
const NODE_CENTRAL = '00000000-0000-0000-0000-000000000012';
const NODE_SOUTH = '00000000-0000-0000-0000-000000000013';

const OWNER_B: OwnerView = {
  ownerId: '00000000-0000-0000-0000-000000000002',
  code: 'OWNER-B',
  name: '乙貨主',
};

/** 兩個貨主刻意共用同一個 SKU 代碼——那是 3PL 撞號的最小再現。 */
function catalogOf(...owners: OwnerView[]) {
  return new Catalog(
    owners.map((owner) => {
      const isOwnerA = owner.ownerId === OWNER_A.ownerId;
      return {
        owner,
        // 兩個貨主共用中部倉，但各自還有一個自己的——過濾若以倉庫而非指派關係實作，
        // 這個安排會讓它露餡
        nodes: isOwnerA
          ? [
              { nodeId: NODE_NORTH, code: 'WH-NORTH', name: '北部倉' },
              { nodeId: NODE_CENTRAL, code: 'WH-CENTRAL', name: '中部倉' },
            ]
          : [
              { nodeId: NODE_CENTRAL, code: 'WH-CENTRAL', name: '中部倉' },
              { nodeId: NODE_SOUTH, code: 'WH-SOUTH', name: '南部倉' },
            ],
        products: [
          {
            productId: `product-${owner.ownerId}`,
            ownerId: owner.ownerId,
            productCode: 'P-TEA',
            name: isOwnerA ? '烏龍茶' : '麥茶',
            temperatureZone: 'AMBIENT' as const,
          },
        ],
        skus: [
          {
            skuId: `sku-${owner.ownerId}`,
            ownerId: owner.ownerId,
            skuCode: 'SKU-AVAILABLE',
            productCode: 'P-TEA',
            specName: isOwnerA ? '500ml' : '600ml',
            weightGram: isOwnerA ? 520 : 610,
            productName: isOwnerA ? '烏龍茶' : '麥茶',
          },
        ],
      };
    }),
  );
}

async function selectDownTo(user: ReturnType<typeof userEvent.setup>, owner: OwnerView) {
  await user.selectOptions(screen.getByLabelText('貨主'), owner.ownerId);
  await user.selectOptions(screen.getByLabelText('出貨倉'), NODE_CENTRAL);
  await user.selectOptions(screen.getByLabelText('款'), 'P-TEA');
  await user.selectOptions(screen.getByLabelText('規格'), 'SKU-AVAILABLE');
}

function optionValues(label: string) {
  return [...screen.getByLabelText(label).querySelectorAll('option')].map((o) => o.value);
}

describe('PlaceOrderForm', () => {
  it('逐層選到規格後可送出，數量以數字型別傳出、行以清單傳出', async () => {
    const onSubmit = vi.fn();
    render(
      <PlaceOrderForm catalog={catalogOf(OWNER_A, OWNER_B)} onSubmit={onSubmit} pending={false} />,
    );
    const user = userEvent.setup();

    await selectDownTo(user, OWNER_A);
    await user.clear(screen.getByLabelText('上游單號'));
    await user.type(screen.getByLabelText('上游單號'), 'PO-8891');
    await user.clear(screen.getByLabelText('數量'));
    await user.type(screen.getByLabelText('數量'), '3');
    await user.click(screen.getByRole('button', { name: '送出訂單' }));

    expect(onSubmit).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({
        ownerId: OWNER_A.ownerId,
        fulfillmentNodeId: NODE_CENTRAL,
        externalOrderNo: 'PO-8891',
        lines: [{ skuCode: 'SKU-AVAILABLE', quantity: 3 }],
      }),
    );
  });

  it('選項全部來自已載入的主檔，選擇不觸發任何請求', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    render(<PlaceOrderForm catalog={catalogOf(OWNER_A, OWNER_B)} onSubmit={vi.fn()} pending={false} />);
    const user = userEvent.setup();

    await selectDownTo(user, OWNER_A);

    // 訂單列表為了解析名稱本來就載過整份主檔，表單再抓一次只會抓到同一批資料
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(screen.getByLabelText('規格')).toHaveValue('SKU-AVAILABLE');
  });

  it('出貨倉只列出該貨主已指派的倉', async () => {
    render(
      <PlaceOrderForm catalog={catalogOf(OWNER_A, OWNER_B)} onSubmit={vi.fn()} pending={false} />,
    );
    const user = userEvent.setup();

    await user.selectOptions(screen.getByLabelText('貨主'), OWNER_A.ownerId);
    expect(optionValues('出貨倉')).toEqual(['', NODE_NORTH, NODE_CENTRAL]);

    await user.selectOptions(screen.getByLabelText('貨主'), OWNER_B.ownerId);
    // 中部倉兩個貨主共用，南部倉只有乙貨主有——北部倉必須消失
    expect(optionValues('出貨倉')).toEqual(['', NODE_CENTRAL, NODE_SOUTH]);
  });

  it('切換貨主會清空倉庫、款與規格——三者在另一個貨主底下都不成立', async () => {
    render(
      <PlaceOrderForm catalog={catalogOf(OWNER_A, OWNER_B)} onSubmit={vi.fn()} pending={false} />,
    );
    const user = userEvent.setup();

    await selectDownTo(user, OWNER_A);
    expect(screen.getByLabelText('規格')).toHaveValue('SKU-AVAILABLE');

    await user.selectOptions(screen.getByLabelText('貨主'), OWNER_B.ownerId);

    // 兩個貨主的 SKU 代碼相同，若不清空，畫面看起來仍是「已選好」而實際指向另一個貨主的商品
    expect(screen.getByLabelText('款')).toHaveValue('');
    expect(screen.getByLabelText('規格')).toHaveValue('');
    // 倉庫尤其要清：中部倉兩個貨主都有，留著看起來像仍然有效，但有效與否取決於指派關係
    expect(screen.getByLabelText('出貨倉')).toHaveValue('');
  });

  it('未選貨主時款與規格不可選，也沒有任何選項', () => {
    render(<PlaceOrderForm catalog={catalogOf(OWNER_A)} onSubmit={vi.fn()} pending={false} />);

    expect(screen.getByLabelText('款')).toBeDisabled();
    expect(screen.getByLabelText('規格')).toBeDisabled();
    expect(screen.getByLabelText('款')).toContainHTML('請選擇');
  });

  it('未選出貨倉時不送出請求', async () => {
    const onSubmit = vi.fn();
    render(<PlaceOrderForm catalog={catalogOf(OWNER_A)} onSubmit={onSubmit} pending={false} />);
    const user = userEvent.setup();

    await user.selectOptions(screen.getByLabelText('貨主'), OWNER_A.ownerId);
    await user.selectOptions(screen.getByLabelText('款'), 'P-TEA');
    await user.selectOptions(screen.getByLabelText('規格'), 'SKU-AVAILABLE');
    await user.clear(screen.getByLabelText('上游單號'));
    await user.type(screen.getByLabelText('上游單號'), 'PO-1');
    await user.click(screen.getByRole('button', { name: '送出訂單' }));

    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent('出貨倉');
  });

  it.each([
    ['未選貨主', false, 'PO-1', '1'],
    ['上游單號為空', true, '', '1'],
    ['數量為 0', true, 'PO-1', '0'],
    ['數量為負', true, 'PO-1', '-3'],
    ['數量為空', true, 'PO-1', ''],
  ])('%s 時不送出請求', async (_case, selectSku, externalOrderNo, quantity) => {
    const onSubmit = vi.fn();
    render(<PlaceOrderForm catalog={catalogOf(OWNER_A)} onSubmit={onSubmit} pending={false} />);
    const user = userEvent.setup();

    if (selectSku) {
      await selectDownTo(user, OWNER_A);
    }
    await user.clear(screen.getByLabelText('上游單號'));
    if (externalOrderNo !== '') {
      await user.type(screen.getByLabelText('上游單號'), externalOrderNo);
    }
    await user.clear(screen.getByLabelText('數量'));
    if (quantity !== '') {
      await user.type(screen.getByLabelText('數量'), quantity);
    }
    await user.click(screen.getByRole('button', { name: '送出訂單' }));

    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });
});
