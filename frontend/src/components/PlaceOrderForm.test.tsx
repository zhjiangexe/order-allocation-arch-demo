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
  await user.selectOptions(screen.getByLabelText('款'), 'P-TEA');
  await user.selectOptions(screen.getByLabelText('規格'), 'SKU-AVAILABLE');
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

  it('切換貨主會清空款與規格——它們在另一個貨主底下不成立', async () => {
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
  });

  it('未選貨主時款與規格不可選，也沒有任何選項', () => {
    render(<PlaceOrderForm catalog={catalogOf(OWNER_A)} onSubmit={vi.fn()} pending={false} />);

    expect(screen.getByLabelText('款')).toBeDisabled();
    expect(screen.getByLabelText('規格')).toBeDisabled();
    expect(screen.getByLabelText('款')).toContainHTML('請選擇');
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
