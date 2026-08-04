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
        facilities: isOwnerA
          ? [
              { facilityId: NODE_NORTH, code: 'WH-NORTH', name: '北部倉' },
              { facilityId: NODE_CENTRAL, code: 'WH-CENTRAL', name: '中部倉' },
            ]
          : [
              { facilityId: NODE_CENTRAL, code: 'WH-CENTRAL', name: '中部倉' },
              { facilityId: NODE_SOUTH, code: 'WH-SOUTH', name: '南部倉' },
            ],
        products: [
          {
            productId: `product-${owner.ownerId}`,
            ownerId: owner.ownerId,
            productCode: 'P-TEA',
            name: isOwnerA ? '烏龍茶' : '麥茶',
            temperatureZone: 'AMBIENT' as const,
          },
          {
            productId: `product-coffee-${owner.ownerId}`,
            ownerId: owner.ownerId,
            productCode: 'P-COFFEE',
            name: isOwnerA ? '黑咖啡' : '拿鐵',
            temperatureZone: 'CHILLED' as const,
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
          {
            skuId: `sku-coffee-${owner.ownerId}`,
            ownerId: owner.ownerId,
            skuCode: 'SKU-SECOND',
            productCode: 'P-COFFEE',
            specName: '330ml',
            weightGram: 350,
            productName: isOwnerA ? '黑咖啡' : '拿鐵',
          },
        ],
      };
    }),
  );
}

const product = (lineNo: number) => screen.getByLabelText(`第 ${lineNo} 行・款`);
const sku = (lineNo: number) => screen.getByLabelText(`第 ${lineNo} 行・規格`);
const quantityOf = (lineNo: number) => screen.getByLabelText(`第 ${lineNo} 行・數量`);
const removeLine = (lineNo: number) =>
  screen.getByRole('button', { name: `移除第 ${lineNo} 行` });

type User = ReturnType<typeof userEvent.setup>;

async function selectDownTo(user: User, owner: OwnerView) {
  await user.selectOptions(screen.getByLabelText('貨主'), owner.ownerId);
  await user.selectOptions(screen.getByLabelText('出貨倉'), NODE_CENTRAL);
  await user.selectOptions(product(1), 'P-TEA');
  await user.selectOptions(sku(1), 'SKU-AVAILABLE');
}

async function fillLine(user: User, lineNo: number, productCode: string, skuCode: string, qty: string) {
  await user.selectOptions(product(lineNo), productCode);
  await user.selectOptions(sku(lineNo), skuCode);
  await user.clear(quantityOf(lineNo));
  await user.type(quantityOf(lineNo), qty);
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
    await user.clear(quantityOf(1));
    await user.type(quantityOf(1), '3');
    await user.click(screen.getByRole('button', { name: '送出訂單' }));

    expect(onSubmit).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({
        ownerId: OWNER_A.ownerId,
        facilityId: NODE_CENTRAL,
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
    expect(sku(1)).toHaveValue('SKU-AVAILABLE');
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

  it('未選貨主時款與規格不可選，也沒有任何選項', () => {
    render(<PlaceOrderForm catalog={catalogOf(OWNER_A)} onSubmit={vi.fn()} pending={false} />);

    expect(product(1)).toBeDisabled();
    expect(sku(1)).toBeDisabled();
    expect(product(1)).toContainHTML('請選擇');
  });

  it('未選出貨倉時不送出請求', async () => {
    const onSubmit = vi.fn();
    render(<PlaceOrderForm catalog={catalogOf(OWNER_A)} onSubmit={onSubmit} pending={false} />);
    const user = userEvent.setup();

    await user.selectOptions(screen.getByLabelText('貨主'), OWNER_A.ownerId);
    await user.selectOptions(product(1), 'P-TEA');
    await user.selectOptions(sku(1), 'SKU-AVAILABLE');
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
    await user.clear(quantityOf(1));
    if (quantity !== '') {
      await user.type(quantityOf(1), quantity);
    }
    await user.click(screen.getByRole('button', { name: '送出訂單' }));

    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  describe('一張單是一籃行', () => {
    it('加一條指向別的規格的行後可送出，兩條行都在請求裡', async () => {
      const onSubmit = vi.fn();
      render(<PlaceOrderForm catalog={catalogOf(OWNER_A)} onSubmit={onSubmit} pending={false} />);
      const user = userEvent.setup();

      await selectDownTo(user, OWNER_A);
      await user.clear(screen.getByLabelText('上游單號'));
      await user.type(screen.getByLabelText('上游單號'), 'PO-8891');
      await user.clear(quantityOf(1));
      await user.type(quantityOf(1), '10');
      await user.click(screen.getByRole('button', { name: '新增訂單行' }));
      await fillLine(user, 2, 'P-COFFEE', 'SKU-SECOND', '5');
      await user.click(screen.getByRole('button', { name: '送出訂單' }));

      expect(onSubmit).toHaveBeenCalledExactlyOnceWith(
        expect.objectContaining({
          lines: [
            { skuCode: 'SKU-AVAILABLE', quantity: 10 },
            { skuCode: 'SKU-SECOND', quantity: 5 },
          ],
        }),
      );
    });

    it('同一個規格出現在兩條行上也送得出去——收單讀成加總', async () => {
      const onSubmit = vi.fn();
      render(<PlaceOrderForm catalog={catalogOf(OWNER_A)} onSubmit={onSubmit} pending={false} />);
      const user = userEvent.setup();

      await selectDownTo(user, OWNER_A);
      await user.clear(screen.getByLabelText('上游單號'));
      await user.type(screen.getByLabelText('上游單號'), 'PO-8891');
      await user.click(screen.getByRole('button', { name: '新增訂單行' }));
      await fillLine(user, 2, 'P-TEA', 'SKU-AVAILABLE', '2');
      await user.click(screen.getByRole('button', { name: '送出訂單' }));

      // 在這裡擋下只會讓操作台拒絕系統處理得了的訂單。
      expect(onSubmit).toHaveBeenCalledExactlyOnceWith(
        expect.objectContaining({
          lines: [
            { skuCode: 'SKU-AVAILABLE', quantity: 1 },
            { skuCode: 'SKU-AVAILABLE', quantity: 2 },
          ],
        }),
      );
    });

    it('移除中間那條行時，其餘兩條保有自己的選擇與數量', async () => {
      const onSubmit = vi.fn();
      render(<PlaceOrderForm catalog={catalogOf(OWNER_A)} onSubmit={onSubmit} pending={false} />);
      const user = userEvent.setup();

      await selectDownTo(user, OWNER_A);
      await user.clear(screen.getByLabelText('上游單號'));
      await user.type(screen.getByLabelText('上游單號'), 'PO-8891');
      await user.clear(quantityOf(1));
      await user.type(quantityOf(1), '7');
      await user.click(screen.getByRole('button', { name: '新增訂單行' }));
      await fillLine(user, 2, 'P-COFFEE', 'SKU-SECOND', '3');
      await user.click(screen.getByRole('button', { name: '新增訂單行' }));
      await fillLine(user, 3, 'P-COFFEE', 'SKU-SECOND', '9');

      await user.click(removeLine(2));

      // 以陣列索引當 key 的實作會在這裡露餡：剩下的兩條會拿到別人的值。
      expect(sku(1)).toHaveValue('SKU-AVAILABLE');
      expect(quantityOf(1)).toHaveValue('7');
      expect(sku(2)).toHaveValue('SKU-SECOND');
      expect(quantityOf(2)).toHaveValue('9');
    });

    it('只剩一條行時移不掉——沒有需求的訂單送不出去', async () => {
      render(<PlaceOrderForm catalog={catalogOf(OWNER_A)} onSubmit={vi.fn()} pending={false} />);
      const user = userEvent.setup();

      await selectDownTo(user, OWNER_A);

      expect(removeLine(1)).toBeDisabled();
      await user.click(removeLine(1));
      expect(sku(1)).toBeInTheDocument();
    });

    it('切換貨主會清空每一條行，不只第一條', async () => {
      render(
        <PlaceOrderForm catalog={catalogOf(OWNER_A, OWNER_B)} onSubmit={vi.fn()} pending={false} />,
      );
      const user = userEvent.setup();

      await selectDownTo(user, OWNER_A);
      await user.click(screen.getByRole('button', { name: '新增訂單行' }));
      await fillLine(user, 2, 'P-COFFEE', 'SKU-SECOND', '3');

      await user.selectOptions(screen.getByLabelText('貨主'), OWNER_B.ownerId);

      // 兩個貨主的 SKU 代碼相同，留著任何一條都等於留著屬於別的貨主的商品——畫面看起來像
      // 「已選好」，實際指向另一個貨主。倉庫尤其要清：中部倉兩個貨主都有，有效與否取決於
      // 指派關係。
      expect(screen.getByLabelText('出貨倉')).toHaveValue('');
      expect(product(1)).toHaveValue('');
      expect(sku(1)).toHaveValue('');
      expect(screen.queryByLabelText('第 2 行・規格')).not.toBeInTheDocument();
    });

    it('任一條行不完整就擋下整張單，不是只送完整的那幾條', async () => {
      const onSubmit = vi.fn();
      render(<PlaceOrderForm catalog={catalogOf(OWNER_A)} onSubmit={onSubmit} pending={false} />);
      const user = userEvent.setup();

      await selectDownTo(user, OWNER_A);
      await user.clear(screen.getByLabelText('上游單號'));
      await user.type(screen.getByLabelText('上游單號'), 'PO-8891');
      await user.click(screen.getByRole('button', { name: '新增訂單行' }));
      await user.selectOptions(product(2), 'P-COFFEE');

      await user.click(screen.getByRole('button', { name: '送出訂單' }));

      // 靜靜丟掉第二條送出第一條，會讓對方拿到一張少東西的訂單卻以為送對了。
      expect(onSubmit).not.toHaveBeenCalled();
      expect(screen.getByRole('alert')).toHaveTextContent('第 2 行');
    });
  });
});
