import { useCallback, useState } from 'react';

/**
 * 一個動作的狀態。刻意用 discriminated union 而不是 `{ data?, loading, error? }`：
 * 後者能表達不可能的組合（同時 loading 又有 error、success 但 data 是 undefined），
 * 而那些組合遲早會出現在畫面上。這裡型別本身就排除了它們。
 */
export type AsyncState<T> =
  | { status: 'idle' }
  | { status: 'pending' }
  | { status: 'success'; data: T }
  | { status: 'failure'; message: string };

export interface AsyncAction<TArgs extends unknown[], TResult> {
  state: AsyncState<TResult>;
  /** 成功回傳結果、失敗回傳 null——不拋出，失敗一定落在 state 裡被呈現。 */
  run: (...args: TArgs) => Promise<TResult | null>;
  reset: () => void;
}

/**
 * 把 idle／pending／success／failure 這組狀態機收在一處，讓六個動作不必各自複製一份、
 * 也不會有人漏掉錯誤處理。
 *
 * 這裡不使用 SWR 或 TanStack Query：它們的核心價值是去重、快取與背景重新驗證，而
 * 規格要求「靜置的操作台不發出任何請求」——預設的 revalidateOnFocus 會直接違反它。
 */
export function useAsyncAction<TArgs extends unknown[], TResult>(
  action: (...args: TArgs) => Promise<TResult>,
): AsyncAction<TArgs, TResult> {
  const [state, setState] = useState<AsyncState<TResult>>({ status: 'idle' });

  const run = useCallback(
    async (...args: TArgs): Promise<TResult | null> => {
      // 先進 pending：前一次的成功結果就此消失，失敗後畫面不會殘留看似成功的輸出
      setState({ status: 'pending' });
      try {
        const data = await action(...args);
        setState({ status: 'success', data });
        return data;
      } catch (error) {
        setState({ status: 'failure', message: messageOf(error) });
        return null;
      }
    },
    [action],
  );

  const reset = useCallback(() => setState({ status: 'idle' }), []);

  return { state, run, reset };
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
