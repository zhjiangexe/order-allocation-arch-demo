import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { useAsyncAction } from './useAsyncAction';

describe('useAsyncAction', () => {
  it('成功時帶著結果進入 success，過程中經過 pending', async () => {
    const { result } = renderHook(() => useAsyncAction(async (sku: string) => `ok:${sku}`));

    expect(result.current.state.status).toBe('idle');

    let resolved: string | null = null;
    await act(async () => {
      resolved = await result.current.run('HOT-SKU');
    });

    expect(resolved).toBe('ok:HOT-SKU');
    await waitFor(() => expect(result.current.state).toEqual({
      status: 'success',
      data: 'ok:HOT-SKU',
    }));
  });

  it('失敗時進入 failure 並帶可呈現的訊息，run 回傳 null 而不是拋出', async () => {
    const { result } = renderHook(() =>
      useAsyncAction(async () => {
        throw new Error('StockPool not found: SKU-NOT-A-THING');
      }),
    );

    let resolved: unknown = 'untouched';
    await act(async () => {
      resolved = await result.current.run();
    });

    // 回傳 null 而不是拋出：呼叫端不需要 try/catch，失敗一定落在 state 裡被呈現
    expect(resolved).toBeNull();
    await waitFor(() => expect(result.current.state).toEqual({
      status: 'failure',
      message: 'StockPool not found: SKU-NOT-A-THING',
    }));
  });

  it('重新執行時先清掉前一次的結果，失敗後畫面不會殘留成功輸出', async () => {
    let shouldFail = false;
    const { result } = renderHook(() =>
      useAsyncAction(async () => {
        if (shouldFail) {
          throw new Error('boom');
        }
        return 'first';
      }),
    );

    await act(async () => {
      await result.current.run();
    });
    await waitFor(() => expect(result.current.state.status).toBe('success'));

    shouldFail = true;
    await act(async () => {
      await result.current.run();
    });

    await waitFor(() => expect(result.current.state).toEqual({ status: 'failure', message: 'boom' }));
  });
});
