import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { StockBatchView } from '../api/types';
import { StockPanel } from './StockPanel';

const noop = vi.fn();

const OWNER = {
  ownerId: '00000000-0000-0000-0000-000000000001',
  code: 'OWNER-A',
  name: '甲貨主',
};
const NORTH = { nodeId: 'node-north', code: 'WH-NORTH', name: '北部倉' };

/** 只有查詢與補貨兩件事各自需要的介面，其餘用不到的都給空實作。 */
const scope = {
  owners: [OWNER],
  nodesOf: () => [NORTH],
  skuCodesOf: () => ['SKU-AVAILABLE'],
  nodeLabel: (_ownerId: string, nodeId: string) => (nodeId === NORTH.nodeId ? NORTH.name : nodeId),
};

const emptyScope = {
  owners: [],
  nodesOf: () => [],
  skuCodesOf: () => [],
  nodeLabel: (_ownerId: string, nodeId: string) => nodeId,
};

function batch(overrides: Partial<StockBatchView> = {}): StockBatchView {
  return {
    stockPoolId: crypto.randomUUID(),
    nodeId: NORTH.nodeId,
    inDate: '2026-01-05',
    expiryDate: '2026-12-31',
    onHandQuantity: 10,
    reservedQuantity: 0,
    availableToPromise: 10,
    expired: false,
    ...overrides,
  };
}

describe('StockPanel 的失敗路徑', () => {
  it('補貨失敗時就地顯示失敗，且畫面上不出現事件識別碼', () => {
    render(
      <StockPanel
        stock={{ status: 'idle' }}
        replenishment={{ status: 'failure', message: 'Kafka 不可用' }}
        {...emptyScope}
        onQuery={noop}
        onReplenish={noop}
        onScopeChange={noop}
      />,
    );

    expect(screen.getByText(/Kafka 不可用/)).toBeInTheDocument();
    // 失敗就不該有任何「已受理」的跡象——事件識別碼是受理的證據
    expect(screen.queryByText(/事件識別碼/)).not.toBeInTheDocument();
  });

  it('查詢失敗時不顯示任何批次', () => {
    render(
      <StockPanel
        stock={{ status: 'failure', message: 'No stock held for SKU SKU-NOT-A-THING' }}
        replenishment={{ status: 'idle' }}
        {...emptyScope}
        onQuery={noop}
        onReplenish={noop}
        onScopeChange={noop}
      />,
    );

    expect(screen.getByText(/No stock held/)).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('補貨受理後顯示事件識別碼，並說明結果要再查一次才看得到', () => {
    render(
      <StockPanel
        stock={{ status: 'idle' }}
        replenishment={{
          status: 'success',
          data: { eventId: '3d9a-event', sku: 'HOT-SKU', quantity: 500 },
        }}
        {...emptyScope}
        onQuery={noop}
        onReplenish={noop}
        onScopeChange={noop}
      />,
    );

    expect(screen.getByText(/3d9a-event/)).toBeInTheDocument();
    expect(screen.getByText(/再查一次/)).toBeInTheDocument();
    // 不得宣稱任何訂單已被配置
    expect(screen.queryByText(/已配置/)).not.toBeInTheDocument();
  });
});

describe('StockPanel 的批次列表', () => {
  it('依後端給的順序逐批列出，而不是重新排序或摺成加總', () => {
    render(
      <StockPanel
        stock={{
          status: 'success',
          data: {
            sku: 'SKU-AVAILABLE',
            batches: [
              batch({ expiryDate: '2026-08-31', onHandQuantity: 60 }),
              batch({ expiryDate: '2026-12-31', inDate: '2026-01-05', onHandQuantity: 40 }),
              batch({ expiryDate: '2026-12-31', inDate: '2026-02-05', onHandQuantity: 30 }),
            ],
          },
        }}
        replenishment={{ status: 'idle' }}
        {...scope}
        onQuery={noop}
        onReplenish={noop}
        onScopeChange={noop}
      />,
    );

    // 順序就是配貨會取用的順序，所以第一列的效期必須最近——重新排序會讓畫面說謊
    const rows = screen.getAllByRole('row').slice(1);
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent('2026-08-31');
    // 同效期的兩批以入庫日分先後，順序不得被打亂
    expect(rows[1]).toHaveTextContent('2026-01-05');
    expect(rows[2]).toHaveTextContent('2026-02-05');
    // 倉別換成看得懂的名字，不是原始 UUID
    expect(rows[0]).toHaveTextContent('北部倉');
  });

  it('已過期的批要顯示出來並標記，不得被濾掉', () => {
    render(
      <StockPanel
        stock={{
          status: 'success',
          data: {
            sku: 'SKU-AVAILABLE',
            batches: [
              batch({ expiryDate: '2025-12-31', onHandQuantity: 25, expired: true }),
              batch({ onHandQuantity: 10 }),
            ],
          },
        }}
        replenishment={{ status: 'idle' }}
        {...scope}
        onQuery={noop}
        onReplenish={noop}
        onScopeChange={noop}
      />,
    );

    // 「有 25 件但一件都出不了」與「什麼都沒有」在畫面上必須分得開，前者要報廢、後者要進貨
    expect(screen.getByText('已過期')).toBeInTheDocument();
    expect(screen.getByText('25')).toBeInTheDocument();
  });

  it('沒過期但被預留光的批標成「已預留完」，與過期分開', () => {
    render(
      <StockPanel
        stock={{
          status: 'success',
          data: {
            sku: 'SKU-AVAILABLE',
            batches: [batch({ onHandQuantity: 40, reservedQuantity: 40, availableToPromise: 0 })],
          },
        }}
        replenishment={{ status: 'idle' }}
        {...scope}
        onQuery={noop}
        onReplenish={noop}
        onScopeChange={noop}
      />,
    );

    // 配貨眼中兩者都是「配不到」，但一個要報廢、一個只是等出貨——畫面不能混為一談
    expect(screen.getByText('已預留完')).toBeInTheDocument();
    expect(screen.queryByText('已過期')).not.toBeInTheDocument();
  });

  it('該貨主一批都沒有時明說，不留空白表格', () => {
    render(
      <StockPanel
        stock={{ status: 'success', data: { sku: 'SKU-AVAILABLE', batches: [] } }}
        replenishment={{ status: 'idle' }}
        {...scope}
        onQuery={noop}
        onReplenish={noop}
        onScopeChange={noop}
      />,
    );

    expect(screen.getByText(/沒有任何批次/)).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });
});

describe('StockPanel 的結果歸屬', () => {
  it('批次列表標示它屬於哪個 SKU，且該 SKU 取自後端回應而不是輸入框', () => {
    render(
      <StockPanel
        stock={{ status: 'success', data: { sku: 'ACC-PARTIAL', batches: [batch()] } }}
        replenishment={{ status: 'idle' }}
        {...scope}
        onQuery={noop}
        onReplenish={noop}
        onScopeChange={noop}
      />,
    );

    // 輸入框是空的，畫面上的 ACC-PARTIAL 只可能來自後端回應
    expect(screen.getByText(/ACC-PARTIAL/)).toBeInTheDocument();
  });

  it('改動貨主或 SKU 後通知外層作廢前一次的結果', async () => {
    const onScopeChange = vi.fn();
    render(
      <StockPanel
        stock={{ status: 'failure', message: 'No stock held for SKU SKU-NOT-A-THING' }}
        replenishment={{ status: 'idle' }}
        {...scope}
        onQuery={noop}
        onReplenish={noop}
        onScopeChange={onScopeChange}
      />,
    );
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('SKU'), 'A');
    expect(onScopeChange).toHaveBeenCalled();

    onScopeChange.mockClear();
    await user.selectOptions(screen.getByLabelText('貨主'), OWNER.ownerId);
    // 換貨主也要作廢——結果屬於「某貨主的某 SKU」，不只屬於 SKU
    expect(onScopeChange).toHaveBeenCalled();
  });
});

describe('StockPanel 的表單守門', () => {
  it('查詢需要貨主與 SKU，缺任一個都不可按', async () => {
    const onQuery = vi.fn();
    render(
      <StockPanel
        stock={{ status: 'idle' }}
        replenishment={{ status: 'idle' }}
        {...scope}
        onQuery={onQuery}
        onReplenish={noop}
        onScopeChange={noop}
      />,
    );
    const user = userEvent.setup();
    const queryButton = screen.getByRole('button', { name: '查詢庫存' });

    // 只有 SKU 不夠——同碼 SKU 在兩個貨主名下是兩批不同的貨，後端會直接回 400
    await user.type(screen.getByLabelText('SKU'), 'SKU-AVAILABLE');
    expect(queryButton).toBeDisabled();

    await user.selectOptions(screen.getByLabelText('貨主'), OWNER.ownerId);
    await user.click(queryButton);
    expect(onQuery).toHaveBeenCalledWith(OWNER.ownerId, 'SKU-AVAILABLE');
  });

  it('補貨的五個維度缺任一個都擋在表單層，不發請求', async () => {
    const onReplenish = vi.fn();
    render(
      <StockPanel
        stock={{ status: 'idle' }}
        replenishment={{ status: 'idle' }}
        {...scope}
        onQuery={noop}
        onReplenish={onReplenish}
        onScopeChange={noop}
      />,
    );
    const user = userEvent.setup();
    const replenishButton = screen.getByRole('button', { name: '觸發補貨' });

    // 逐步補齊，每一步都確認仍然擋著——無效的補貨不該換來一次沒有必要的往返
    expect(replenishButton).toBeDisabled();
    await user.selectOptions(screen.getByLabelText('貨主'), OWNER.ownerId);
    expect(replenishButton).toBeDisabled();
    await user.type(screen.getByLabelText('SKU'), 'SKU-AVAILABLE');
    expect(replenishButton).toBeDisabled();
    await user.selectOptions(screen.getByLabelText('補貨倉別'), NORTH.nodeId);
    expect(replenishButton).toBeDisabled();
    await user.type(screen.getByLabelText('入庫日'), '2026-01-05');
    expect(replenishButton).toBeDisabled();
    await user.type(screen.getByLabelText('效期'), '2026-12-31');

    // 五個維度到齊（數量有預設值）才放行
    expect(replenishButton).toBeEnabled();
    await user.click(replenishButton);
    expect(onReplenish).toHaveBeenCalledWith({
      ownerId: OWNER.ownerId,
      nodeId: NORTH.nodeId,
      sku: 'SKU-AVAILABLE',
      inDate: '2026-01-05',
      expiryDate: '2026-12-31',
      quantity: 500,
    });
  });

  it('未選貨主時倉別下拉不可用——倉是掛在貨主底下的', () => {
    render(
      <StockPanel
        stock={{ status: 'idle' }}
        replenishment={{ status: 'idle' }}
        {...scope}
        onQuery={noop}
        onReplenish={noop}
        onScopeChange={noop}
      />,
    );

    expect(screen.getByLabelText('補貨倉別')).toBeDisabled();
  });

  it('尚未查詢時就說明為什麼兩邊都要選貨主、補貨為什麼要填五個欄位', () => {
    render(
      <StockPanel
        stock={{ status: 'idle' }}
        replenishment={{ status: 'idle' }}
        {...emptyScope}
        onQuery={noop}
        onReplenish={noop}
        onScopeChange={noop}
      />,
    );

    // 疑問發生在按任何按鈕之前，所以說明不能只跟著查詢結果出現
    expect(screen.getByText(/都要選貨主/)).toBeInTheDocument();
    expect(screen.getByText(/五個維度/)).toBeInTheDocument();
  });
});
