import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { StockPanel } from './StockPanel';

const noop = vi.fn();

describe('StockPanel 的失敗路徑', () => {
  it('補貨失敗時就地顯示失敗，且畫面上不出現事件識別碼', () => {
    render(
      <StockPanel
        stock={{ status: 'idle' }}
        replenishment={{ status: 'failure', message: 'Kafka 不可用' }}
        onQuery={noop}
        onReplenish={noop}
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
        onQuery={noop}
        onReplenish={noop}
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
        onQuery={noop}
        onReplenish={noop}
      />,
    );

    expect(screen.getByText(/3d9a-event/)).toBeInTheDocument();
    expect(screen.getByText(/再查一次/)).toBeInTheDocument();
    // 不得宣稱任何訂單已被配置
    expect(screen.queryByText(/已配置/)).not.toBeInTheDocument();
  });
});
