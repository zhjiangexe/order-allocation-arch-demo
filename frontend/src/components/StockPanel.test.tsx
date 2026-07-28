import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { StockPanel } from './StockPanel';

const noop = vi.fn();

describe('StockPanel 的失敗路徑', () => {
  it('補貨失敗時就地顯示失敗，且畫面上不出現事件識別碼', () => {
    render(
      <StockPanel
        stock={{ status: 'idle' }}
        replenishment={{ status: 'failure', message: 'Kafka 不可用' }}
        owners={[]}
        skuCodes={[]}
        onQuery={noop}
        onReplenish={noop}
        onSkuChange={noop}
      />,
    );

    expect(screen.getByText(/Kafka 不可用/)).toBeInTheDocument();
    // 失敗就不該有任何「已受理」的跡象——事件識別碼是受理的證據
    expect(screen.queryByText(/事件識別碼/)).not.toBeInTheDocument();
  });

  it('查詢失敗時不顯示任何庫存數字', () => {
    render(
      <StockPanel
        stock={{ status: 'failure', message: 'StockPool not found: SKU-NOT-A-THING' }}
        replenishment={{ status: 'idle' }}
        owners={[]}
        skuCodes={[]}
        onQuery={noop}
        onReplenish={noop}
        onSkuChange={noop}
      />,
    );

    expect(screen.getByText(/StockPool not found/)).toBeInTheDocument();
    expect(screen.queryByText('available-to-promise')).not.toBeInTheDocument();
  });

  it('補貨受理後顯示事件識別碼，並說明結果要再查一次才看得到', () => {
    render(
      <StockPanel
        stock={{ status: 'idle' }}
        replenishment={{
          status: 'success',
          data: { eventId: '3d9a-event', sku: 'HOT-SKU', quantity: 500 },
        }}
        owners={[]}
        skuCodes={[]}
        onQuery={noop}
        onReplenish={noop}
        onSkuChange={noop}
      />,
    );

    expect(screen.getByText(/3d9a-event/)).toBeInTheDocument();
    expect(screen.getByText(/再查一次/)).toBeInTheDocument();
    // 不得宣稱任何訂單已被配置
    expect(screen.queryByText(/已配置/)).not.toBeInTheDocument();
  });
});

describe('StockPanel 的結果歸屬', () => {
  it('庫存結果標示它屬於哪個 SKU，且該 SKU 取自後端回應而不是輸入框', () => {
    render(
      <StockPanel
        stock={{
          status: 'success',
          data: {
            sku: 'ACC-PARTIAL',
            onHandQuantity: 10,
            reservedQuantity: 4,
            availableToPromise: 6,
          },
        }}
        replenishment={{ status: 'idle' }}
        owners={[]}
        skuCodes={[]}
        onQuery={noop}
        onReplenish={noop}
        onSkuChange={noop}
      />,
    );

    // 輸入框是空的，畫面上的 ACC-PARTIAL 只可能來自後端回應
    expect(screen.getByText(/ACC-PARTIAL/)).toBeInTheDocument();
  });

  it('補貨受理訊息標示它屬於哪個 SKU', () => {
    render(
      <StockPanel
        stock={{ status: 'idle' }}
        replenishment={{
          status: 'success',
          data: { eventId: '3d9a-event', sku: 'ACC-DEMO', quantity: 500 },
        }}
        owners={[]}
        skuCodes={[]}
        onQuery={noop}
        onReplenish={noop}
        onSkuChange={noop}
      />,
    );

    expect(screen.getByText(/ACC-DEMO/)).toBeInTheDocument();
  });

  it('改動 SKU 後通知外層作廢前一次的結果，畫面不會留著別的 SKU 的查詢結果', async () => {
    const onSkuChange = vi.fn();
    render(
      <StockPanel
        stock={{
          status: 'failure',
          message: 'StockPool not found: SKU-NOT-A-THING',
        }}
        replenishment={{ status: 'idle' }}
        owners={[]}
        skuCodes={[]}
        onQuery={noop}
        onReplenish={noop}
        onSkuChange={onSkuChange}
      />,
    );

    await userEvent.setup().type(screen.getByLabelText('SKU'), 'A');

    expect(onSkuChange).toHaveBeenCalled();
  });

  it('未選貨主時補貨鈕不可按，但查詢庫存照常可用', async () => {
    const onQuery = vi.fn();
    const onReplenish = vi.fn();
    render(
      <StockPanel
        stock={{ status: 'idle' }}
        replenishment={{ status: 'idle' }}
        skuCodes={['SKU-AVAILABLE']}
        owners={[
          {
            ownerId: '00000000-0000-0000-0000-000000000001',
            code: 'OWNER-A',
            name: '甲貨主',
            status: 'ACTIVE',
            allowSplitShipment: true,
          },
        ]}
        onQuery={onQuery}
        onReplenish={onReplenish}
        onSkuChange={noop}
      />,
    );
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('SKU'), 'SKU-AVAILABLE');

    // 補貨要喚醒某個貨主的缺貨佇列，SKU 代碼跨貨主撞號、決定不了是誰的
    expect(screen.getByRole('button', { name: '觸發補貨' })).toBeDisabled();

    // 查詢卻不需要貨主——庫存還沒有貨主維度，兩個貨主的同碼 SKU 共用同一列
    await user.click(screen.getByRole('button', { name: '查詢庫存' }));
    expect(onQuery).toHaveBeenCalledWith('SKU-AVAILABLE');
    expect(onReplenish).not.toHaveBeenCalled();
  });

  it('尚未查詢時就說明補貨要選貨主、查詢不用', () => {
    render(
      <StockPanel
        stock={{ status: 'idle' }}
        replenishment={{ status: 'idle' }}
        owners={[]}
        skuCodes={[]}
        onQuery={noop}
        onReplenish={noop}
        onSkuChange={noop}
      />,
    );

    // 疑問發生在按任何按鈕之前，所以說明不能只跟著查詢結果出現
    expect(screen.getByText(/補貨要選貨主/)).toBeInTheDocument();
    expect(screen.getByText(/查詢不用選/)).toBeInTheDocument();
  });
});
