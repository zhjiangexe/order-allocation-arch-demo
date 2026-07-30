import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';

import { AppHeader } from './AppHeader';

/** `NavLink` 需要 router context，這裡只是提供它，與被驗證的行為無關。 */
function renderHeader(strategy: string) {
  render(
    <MemoryRouter>
      <AppHeader config={{ status: 'success', data: { partitionKeyStrategy: strategy } }} />
    </MemoryRouter>,
  );
}

describe('AppHeader 的分區策略標示', () => {
  // 這兩個案例存在的理由是一個真實漏改：R3 把後端的設定值由 `sku` 改名為 `stock`，前端的
  // 比對留在 `sku`。比對失敗不會報錯，只會把 v3 安靜地說成 v1——而「現在跑的是哪個策略」
  // 正是這個操作台要展示的東西，說錯就等於整個 demo 的結論是錯的。
  it('single-writer 策略要標成 v3，且原樣顯示後端給的值', () => {
    renderHeader('stock');

    expect(screen.getByText('stock')).toBeInTheDocument();
    expect(screen.getByText(/v3 single-writer/)).toBeInTheDocument();
  });

  it('預設策略要標成 v1', () => {
    renderHeader('order-id');

    expect(screen.getByText('order-id')).toBeInTheDocument();
    expect(screen.getByText(/v1 樂觀鎖/)).toBeInTheDocument();
  });

  it('未知的值一律當 v1，不猜也不當成 v3', () => {
    // 後端加了新策略而前端還沒跟上時，往保守的方向倒：說成 v1 只是少報了一個能力，
    // 說成 v3 則是宣稱一個並不成立的保證。
    renderHeader('some-future-strategy');

    expect(screen.getByText(/v1 樂觀鎖/)).toBeInTheDocument();
  });

  it('讀取失敗時說明失敗，不顯示任何策略結論', () => {
    render(
      <MemoryRouter>
        <AppHeader config={{ status: 'failure', message: '連不上後端' }} />
      </MemoryRouter>,
    );

    expect(screen.getByText(/連不上後端/)).toBeInTheDocument();
    // 讀不到就不該有結論——顯示 v1 會讓人以為那是後端的實際設定
    expect(screen.queryByText(/v1 樂觀鎖/)).not.toBeInTheDocument();
    expect(screen.queryByText(/v3 single-writer/)).not.toBeInTheDocument();
  });
});
